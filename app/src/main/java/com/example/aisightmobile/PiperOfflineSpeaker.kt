package com.example.aisightmobile

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import dev.ihorshevchuk.piper.engine.PiperCreateOptions
import dev.ihorshevchuk.piper.engine.PiperEngine
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Bundled, fully on-device neural speech for AISight's Male setting.
 * The language-specific Piper model and eSpeak data are packaged in the APK.
 * Playback follows piper-kotlin's sample-rate-aware AudioTrack streaming path.
 */
class PiperOfflineSpeaker(context: Context) {
    interface Callback {
        fun onComplete(error: String?)
    }

    private val appContext = context.applicationContext
    private val generation = AtomicInteger(0)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "aisight-piper-speaker").apply { isDaemon = true }
    }

    @Volatile private var engine: PiperEngine? = null
    @Volatile private var engineModelPath: String? = null
    @Volatile private var activeTrack: AudioTrack? = null
    @Volatile private var closed = false

    fun isAvailable(filipino: Boolean): Boolean {
        if (closed) return false
        val model = modelName(filipino)
        val voices = appContext.assets.list("piper/voices") ?: return false
        val espeak = appContext.assets.list("piper/espeak-ng-data") ?: return false
        return voices.contains(model) && voices.contains("$model.json") &&
            espeak.contains("phondata") && espeak.contains("phonindex") &&
            espeak.contains("phontab") && espeak.contains("id_dict") &&
            espeak.contains("en_dict")
    }

    fun speak(
        text: String,
        filipino: Boolean,
        rate: Float,
        volume: Float,
        callback: Callback
    ) {
        val ticket = generation.get()
        if (closed) {
            callback.onComplete("Offline voice engine is closed.")
            return
        }

        worker.execute {
            var track: AudioTrack? = null
            var samplesWritten = 0L
            try {
                if (ticket != generation.get()) return@execute
                val modelFile = stageVoice(filipino)
                val espeakDir = stageEspeakData()
                val currentEngine = getEngine(modelFile, espeakDir)
                if (ticket != generation.get()) return@execute

                val lengthScale = when {
                    rate <= 0.8f -> 1.24f
                    rate >= 1.15f -> 0.86f
                    else -> 1.0f
                }
                val options = currentEngine.defaultSynthesizeOptions().copy(lengthScale = lengthScale)
                currentEngine.synthesize(text, options, onSamples = { samples ->
                    if (ticket != generation.get() || samples.isEmpty()) return@synthesize

                    // Read the actual sample rate reported by Piper for this audio chunk.
                    val sampleRate = currentEngine.currentSampleRate.get().takeIf { it > 0 } ?: 22050
                    var output = track
                    if (output == null) {
                        val created = createTrack(sampleRate, volume)
                        track = created
                        activeTrack = created
                        created.play()
                        output = created
                    }
                    val activeOutput = output

                    val pcm = ShortArray(samples.size) { index ->
                        (samples[index].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
                    }
                    var offset = 0
                    var emptyWrites = 0
                    while (offset < pcm.size && ticket == generation.get()) {
                        val written = activeOutput.write(
                            pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING
                        )
                        if (written < 0) {
                            throw IOException("AudioTrack write failed (code $written)")
                        }
                        if (written == 0) {
                            emptyWrites++
                            if (emptyWrites >= 5) throw IOException("AudioTrack stopped accepting audio samples")
                        } else {
                            offset += written
                            samplesWritten += written.toLong()
                            emptyWrites = 0
                        }
                    }
                })

                if (ticket != generation.get()) return@execute
                if (samplesWritten == 0L) {
                    throw IOException("Piper generated no playable audio samples. Check the bundled model and phonemizer data.")
                }
                try {
                    track?.stop()
                } catch (_: IllegalStateException) {
                    // Track may already have stopped during an interruption.
                }
                callback.onComplete(null)
            } catch (error: Exception) {
                if (ticket == generation.get()) {
                    callback.onComplete(error.message ?: error.javaClass.simpleName)
                }
            } catch (error: LinkageError) {
                if (ticket == generation.get()) {
                    callback.onComplete("Piper native audio engine could not load: ${error.message ?: error.javaClass.simpleName}")
                }
            } finally {
                if (activeTrack === track) activeTrack = null
                try { track?.release() } catch (_: Exception) { }
            }
        }
    }

    fun stop() {
        generation.incrementAndGet()
        engine?.cancel()
        val track = activeTrack
        try {
            track?.pause()
            track?.flush()
        } catch (_: Exception) {
        }
    }

    fun close() {
        if (closed) return
        closed = true
        stop()
        worker.execute {
            try { engine?.close() } catch (_: Exception) { }
            engine = null
            engineModelPath = null
        }
        worker.shutdown()
    }

    private fun modelName(filipino: Boolean): String = if (filipino)
        "tl_PH-filipino-medium-model.onnx"
    else
        "en_US-mike-medium.onnx"

    private fun stageVoice(filipino: Boolean): File {
        val name = modelName(filipino)
        val dir = File(appContext.filesDir, "aisight-piper/voices")
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Cannot create voice storage directory")
        val model = File(dir, name)
        val config = File(dir, "$name.json")
        if (!model.isFile || model.length() == 0L) copyAsset("piper/voices/$name", model)
        if (!config.isFile || config.length() == 0L) copyAsset("piper/voices/$name.json", config)
        return model
    }

    private fun stageEspeakData(): File {
        val target = File(appContext.filesDir, "aisight-piper/espeak-ng-data")
        val marker = File(target, ".aisight-assets-ready-v2")
        if (marker.isFile) return target
        if (target.exists() && !target.deleteRecursively()) throw IOException("Cannot replace old phonemizer data")
        if (!target.mkdirs()) throw IOException("Cannot create phonemizer data directory")
        copyAssetTree("piper/espeak-ng-data", target)
        val required = listOf("phondata", "phonindex", "phontab", "id_dict", "en_dict")
        for (file in required) {
            if (!File(target, file).isFile) throw IOException("Missing offline phonemizer file: $file")
        }
        marker.writeText("AISight Piper phonemizer data v2")
        return target
    }

    private fun copyAssetTree(assetPath: String, destination: File) {
        val children = appContext.assets.list(assetPath) ?: emptyArray()
        if (children.isNotEmpty()) {
            if (!destination.exists() && !destination.mkdirs()) throw IOException("Cannot create ${destination.path}")
            for (child in children) copyAssetTree("$assetPath/$child", File(destination, child))
        } else {
            copyAsset(assetPath, destination)
        }
    }

    private fun copyAsset(assetPath: String, destination: File) {
        destination.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Cannot create ${it.path}") }
        val temporary = File(destination.parentFile, destination.name + ".partial")
        appContext.assets.open(assetPath).use { input ->
            temporary.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        if (destination.exists() && !destination.delete()) throw IOException("Cannot replace ${destination.path}")
        if (!temporary.renameTo(destination)) throw IOException("Cannot finalize ${destination.path}")
    }

    @Synchronized
    private fun getEngine(model: File, espeak: File): PiperEngine {
        val existing = engine
        if (existing != null && engineModelPath == model.absolutePath) return existing
        try { existing?.close() } catch (_: Exception) { }
        val created = PiperEngine(
            PiperCreateOptions(
                modelPath = model.absolutePath,
                configPath = model.absolutePath + ".json",
                espeakDataPath = espeak.absolutePath
            ),
            appFilesDir = appContext.filesDir
        )
        engine = created
        engineModelPath = model.absolutePath
        return created
    }

    private fun createTrack(sampleRate: Int, volume: Float): AudioTrack {
        val attributes = AudioAttributes.Builder()
            // Use the normal media volume stream. Some OEM builds keep the
            // assistant volume stream separately muted, which can make a
            // correctly synthesized AudioTrack appear completely silent.
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minimum = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minimum <= 0) throw IOException("Android rejected the Piper audio format (buffer error $minimum)")
        val output = AudioTrack(
            attributes,
            format,
            minimum * 4,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        if (output.state != AudioTrack.STATE_INITIALIZED) {
            output.release()
            throw IOException("Android could not initialize the Piper audio output track")
        }
        output.setVolume(volume.coerceIn(0f, 1f))
        return output
    }
}
