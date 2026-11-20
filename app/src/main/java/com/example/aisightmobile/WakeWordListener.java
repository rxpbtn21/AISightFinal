package com.example.aisightmobile;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Offline speech recognition (Vosk) for "eyesight ..." commands.
 *  - the model is loaded ONCE per process (the old code re-read it from the APK every time the app resumed);
 *  - the recognizer only knows the words in VoiceCommandParser.grammarJson(), like the Pi build: much more accurate than
 *    open dictation, and it ignores everything else people say around you.
 */
final class WakeWordListener {
    interface Callback {
        void onHeard(String text);                 // a finished utterance, any words (the parser decides)
        void onStatus(Status status, String detail);
        void onMicFault(String reason);            // once per outage
        void onMicRestored();
    }

    enum Status { LOADING, LISTENING, FAILED }

    private static final String MODEL_DIR = "vosk-model-small-en-us-0.15";
    private static final int SAMPLE_RATE = 16000;

    private static Model sharedModel;                       // guarded by WakeWordListener.class
    private final Context context;
    private final Callback callback;
    private volatile boolean running;
    private volatile int generation;         // a stop() + start() while an old thread is still loading must not revive it
    private Thread thread;

    WakeWordListener(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

    synchronized void start() {
        if (running) return;
        running = true;
        final int gen = ++generation;
        thread = new Thread(() -> loop(gen), "aisight-voice");
        thread.start();
    }

    synchronized void stop() {
        running = false;
        generation++;
        if (thread != null) thread.interrupt();
        thread = null;
    }

    private boolean current(int gen) { return running && gen == generation; }

    // ---- model --------------------------------------------------------------------------------------------------
    private static synchronized Model loadModel(Context context) throws IOException {
        if (sharedModel != null) return sharedModel;
        File target = new File(context.getFilesDir(), MODEL_DIR);
        File marker = new File(target, ".installed");
        String stamp = ModelStore.buildStamp(context);
        boolean fresh = marker.exists() && stamp.equals(readText(marker));
        if (!fresh) {
            deleteRecursively(target);
            copyAssets(context.getAssets(), MODEL_DIR, target);
            try (FileOutputStream out = new FileOutputStream(marker)) {
                out.write(stamp.getBytes(StandardCharsets.UTF_8));
            }
        }
        sharedModel = new Model(target.getAbsolutePath());
        return sharedModel;
    }

    private static void copyAssets(AssetManager assets, String path, File dest) throws IOException {
        String[] children = assets.list(path);
        if (children == null || children.length == 0) {          // a file
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("mkdir " + parent);
            try (InputStream in = assets.open(path); FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            return;
        }
        if (!dest.exists() && !dest.mkdirs()) throw new IOException("mkdir " + dest);
        for (String child : children) copyAssets(assets, path + "/" + child, new File(dest, child));
    }

    private static void deleteRecursively(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursively(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static String readText(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 256)];
            int n = in.read(b);
            return new String(b, 0, Math.max(0, n), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }

    // ---- the listening loop -------------------------------------------------------------------------------------
    private void loop(final int gen) {
        Recognizer recognizer = null;
        try {
            callback.onStatus(Status.LOADING, "");
            Model model = loadModel(context);
            try {
                recognizer = new Recognizer(model, SAMPLE_RATE, VoiceCommandParser.grammarJson());
            } catch (IOException | RuntimeException ex) {
                recognizer = new Recognizer(model, SAMPLE_RATE);           // open vocabulary still works, just less accurate
            }
        } catch (IOException | RuntimeException ex) {
            if (current(gen)) {
                callback.onStatus(Status.FAILED, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
                running = false;
            }
            return;
        }
        if (!current(gen)) { recognizer.close(); return; }      // we were stopped while the model was loading

        boolean faulted = false;
        short[] buffer = new short[SAMPLE_RATE / 4];
        while (current(gen)) {
            AudioRecord record = null;
            try {
                int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) throw new IllegalStateException("No microphone available");
                record = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(min, buffer.length * 4));
                if (record.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("Microphone is busy or blocked");
                record.startRecording();
                callback.onStatus(Status.LISTENING, "");
                if (faulted) { faulted = false; callback.onMicRestored(); }

                while (current(gen)) {
                    int n = record.read(buffer, 0, buffer.length);
                    if (n < 0) throw new IllegalStateException("Microphone read failed (" + n + ")");
                    if (n == 0) continue;
                    if (recognizer.acceptWaveForm(buffer, n)) {
                        String text = new JSONObject(recognizer.getResult()).optString("text", "").trim();
                        if (!text.isEmpty()) callback.onHeard(text);
                    }
                }
            } catch (SecurityException | IllegalStateException | org.json.JSONException ex) {
                if (!current(gen)) break;
                if (!faulted) {
                    faulted = true;
                    callback.onMicFault(ex.getMessage() == null ? "Microphone unavailable" : ex.getMessage());
                }
                try { Thread.sleep(1500); } catch (InterruptedException ie) { break; }
            } finally {
                if (record != null) {
                    try { record.stop(); } catch (IllegalStateException ignored) { }
                    record.release();
                }
            }
        }
        recognizer.close();
    }
}
