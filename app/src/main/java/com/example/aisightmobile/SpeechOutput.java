package com.example.aisightmobile;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Speech output with two engines:
 * - Female: keeps the phone TTS behaviour used by the original AISight build.
 * - Male: prefers the bundled offline Piper neural model. If Piper fails at
 *   runtime, it falls back to an installed offline phone voice instead of silence.
 *
 * Routine announcements are dropped while speech is active. Priority speech
 * interrupts routine speech and queues behind other priority speech.
 */
final class SpeechOutput {
    interface Listener {
        void onReady(boolean ok);
        void onLanguageMissing(boolean filipino);
        void onVoiceError(String message);
    }

    private static final class MaleRequest {
        final String text;
        final boolean priority;
        final boolean filipino;
        MaleRequest(String text, boolean priority, boolean filipino) {
            this.text = text;
            this.priority = priority;
            this.filipino = filipino;
        }
    }

    private final AppSettings settings;
    private final Listener listener;
    private final PiperOfflineSpeaker piper;
    private TextToSpeech tts;
    private volatile boolean ready;
    private volatile boolean phoneVoiceReady;
    private volatile boolean malePiperFailed;
    private volatile String voiceStatus = "Voice selection has not been checked yet.";
    private final AtomicInteger counter = new AtomicInteger();
    private final Map<String, long[]> live = new ConcurrentHashMap<>();
    private final Object maleLock = new Object();
    private final ArrayDeque<MaleRequest> malePending = new ArrayDeque<>();
    private MaleRequest maleActive;

    SpeechOutput(Context context, AppSettings settings, Listener listener) {
        this.settings = settings;
        this.listener = listener;
        this.piper = new PiperOfflineSpeaker(context.getApplicationContext());
        tts = new TextToSpeech(context.getApplicationContext(), status -> {
            ready = status == TextToSpeech.SUCCESS;
            if (ready) {
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { }
                    @Override public void onDone(String id) { finish(id); }
                    @Override public void onError(String id) { finish(id); }
                    @Override public void onStop(String id, boolean interrupted) { finish(id); }
                });
                configure();
            } else {
                voiceStatus = "The phone's TTS engine did not initialize. AISight's bundled male voice may still work.";
            }
            listener.onReady(isReady());
        });
    }

    boolean isReady() {
        if (isMale() && !malePiperFailed) return piper.isAvailable(settings.filipino);
        return ready && phoneVoiceReady;
    }

    /** Re-apply language / voice / speed after a setting changed. */
    void configure() {
        stopMaleQueue();
        live.clear();
        malePiperFailed = false;
        phoneVoiceReady = false;

        if (isMale()) {
            if (ready && tts != null) tts.stop();
            if (piper.isAvailable(settings.filipino)) {
                voiceStatus = settings.filipino
                        ? "Bundled offline male Filipino/Tagalog neural voice selected. No internet required."
                        : "Bundled offline male English neural voice selected. No internet required.";
                return;
            }

            // Assets missing? Preserve speech by trying an already-installed local phone voice.
            malePiperFailed = true;
            if (configurePhoneMaleFallback()) {
                voiceStatus = "Bundled male voice is missing. Using the phone's installed offline voice instead.";
            } else {
                voiceStatus = "Bundled male voice is missing and no suitable installed offline phone voice is available.";
            }
            return;
        }

        // FEMALE MODE: intentionally preserve the original AISight Android TTS path.
        // This restores the phone's previous default female timbre and pitch.
        if (!ready || tts == null) {
            voiceStatus = "The phone's TTS engine is not ready.";
            return;
        }

        boolean fil = settings.filipino;
        Locale wanted = fil ? new Locale("fil", "PH") : Locale.US;
        int result = tts.setLanguage(wanted);
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.US);
            listener.onLanguageMissing(fil);
            wanted = Locale.US;
        }
        tts.setPitch(1.07f);
        tts.setSpeechRate(speechRate());
        pickVoice(wanted.getLanguage(), true);
        phoneVoiceReady = true;
        voiceStatus = "Using the original phone text-to-speech voice.";
    }

    String getVoiceStatus() { return voiceStatus; }

    /** Original AISight voice selection: only override the engine default when its name marks gender. */
    private void pickVoice(String language, boolean female) {
        try {
            Set<Voice> voices = tts.getVoices();
            if (voices == null) return;
            Voice best = null;
            for (Voice voice : voices) {
                if (voice.getLocale() == null || !language.equals(voice.getLocale().getLanguage())
                        || voice.isNetworkConnectionRequired()) continue;
                String name = voice.getName() == null ? "" : voice.getName().toLowerCase(Locale.US);
                boolean nameFemale = name.contains("female") || name.contains("-f-") || name.contains("#female");
                boolean nameMale = !nameFemale && (name.contains("male") || name.contains("-m-") || name.contains("#male"));
                if ((female && nameFemale) || (!female && nameMale)) {
                    best = voice;
                    break;
                }
            }
            if (best != null) tts.setVoice(best);
        } catch (RuntimeException ignored) {
            // Keep the Android engine's selected default voice, like the original build.
        }
    }

    /** Offline-only fallback for Male mode if Piper cannot synthesize or play audio. */
    private boolean configurePhoneMaleFallback() {
        phoneVoiceReady = false;
        if (!ready || tts == null) return false;
        try {
            boolean fil = settings.filipino;
            Locale wanted = fil ? new Locale("fil", "PH") : Locale.US;
            int result = tts.setLanguage(wanted);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                if (fil) listener.onLanguageMissing(true);
                wanted = Locale.US;
                result = tts.setLanguage(wanted);
            }
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) return false;

            Set<Voice> voices = tts.getVoices();
            Voice male = null;
            Voice fallback = null;
            int fallbackScore = Integer.MIN_VALUE;
            if (voices != null) {
                for (Voice voice : voices) {
                    Locale locale = voice.getLocale();
                    if (locale == null || !sameLanguage(wanted, locale) || voice.isNetworkConnectionRequired()) continue;
                    Set<String> features = voice.getFeatures();
                    if (features != null && features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                    String name = voice.getName() == null ? "" : voice.getName().toLowerCase(Locale.US);
                    int marker = genderMarker(name);
                    if (marker == 1) { male = voice; break; }
                    int score = 0;
                    if (wanted.getCountry().equalsIgnoreCase(locale.getCountry())) score += 20;
                    if (voice.getQuality() == Voice.QUALITY_HIGH) score += 5;
                    if (score > fallbackScore) { fallback = voice; fallbackScore = score; }
                }
            }
            Voice chosen = male != null ? male : fallback;
            if (chosen != null && tts.setVoice(chosen) != TextToSpeech.SUCCESS) return false;
            if (chosen == null) return false;

            tts.setSpeechRate(speechRate());
            tts.setPitch(male != null ? 1.0f : 0.92f);
            phoneVoiceReady = true;
            voiceStatus = male != null
                    ? "Using the phone's installed offline male voice as fallback."
                    : "Using an installed offline phone voice with a lower pitch as fallback; this is not a separate male speaker.";
            return true;
        } catch (RuntimeException ex) {
            phoneVoiceReady = false;
            return false;
        }
    }

    private boolean sameLanguage(Locale a, Locale b) {
        return normalizedLanguage(a.getLanguage()).equals(normalizedLanguage(b.getLanguage()));
    }

    private String normalizedLanguage(String language) {
        if ("fil".equalsIgnoreCase(language) || "tl".equalsIgnoreCase(language)) return "fil";
        return language == null ? "" : language.toLowerCase(Locale.US);
    }

    private int genderMarker(String name) {
        if (name.contains("female") || name.contains("#f") || name.contains("_f_")) return -1;
        if (name.contains("male") || name.contains("#m") || name.contains("_m_")) return 1;
        return 0;
    }

    private boolean isMale() { return "male".equalsIgnoreCase(settings.gender); }
    private float speechRate() {
        return "slow".equals(settings.speed) ? 0.78f : "fast".equals(settings.speed) ? 1.2f : 1.0f;
    }
    private float volumeValue() {
        switch (settings.volume) {
            case "low": return 0.45f;
            case "high": return 1.0f;
            default: return 0.8f;
        }
    }

    boolean say(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        if (isMale() && !malePiperFailed) {
            synchronized (maleLock) {
                if (maleActive != null || !malePending.isEmpty()) return false;
                startMaleLocked(new MaleRequest(text, false, settings.filipino));
                return true;
            }
        }
        if (!ready || !phoneVoiceReady) return false;
        purgeStale();
        if (!live.isEmpty()) return false;
        speakPhone(text, TextToSpeech.QUEUE_ADD, false);
        return true;
    }

    void sayPriority(String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (isMale() && !malePiperFailed) {
            synchronized (maleLock) {
                if (maleActive != null && !maleActive.priority) {
                    piper.stop();
                    maleActive = null;
                }
                if (malePending.size() < 12) malePending.addLast(new MaleRequest(text, true, settings.filipino));
                startNextMaleLocked();
            }
            return;
        }
        if (!ready || !phoneVoiceReady) return;
        purgeStale();
        boolean priorityBusy = false;
        for (long[] value : live.values()) if (value[1] == 1) { priorityBusy = true; break; }
        speakPhone(text, priorityBusy ? TextToSpeech.QUEUE_ADD : TextToSpeech.QUEUE_FLUSH, true);
    }

    private void startNextMaleLocked() {
        if (maleActive != null || malePending.isEmpty()) return;
        MaleRequest next = malePending.removeFirst();
        if (malePiperFailed) {
            if (ready && phoneVoiceReady) {
                speakPhone(next.text, TextToSpeech.QUEUE_ADD, next.priority);
            } else {
                listener.onVoiceError("No offline male speech engine is available for the queued announcement.");
            }
            startNextMaleLocked();
            return;
        }
        startMaleLocked(next);
    }

    private void startMaleLocked(MaleRequest request) {
        maleActive = request;
        if (!piper.isAvailable(request.filipino)) {
            maleActive = null;
            malePiperFailed = true;
            if (configurePhoneMaleFallback()) {
                voiceStatus = "Bundled offline male voice is unavailable; using the phone's offline TTS fallback.";
                speakPhone(request.text, request.priority ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, request.priority);
            } else {
                voiceStatus = "The bundled offline male voice is unavailable, and no local phone voice could be activated.";
                listener.onVoiceError(voiceStatus);
            }
            startNextMaleLocked();
            return;
        }

        piper.speak(request.text, request.filipino, speechRate(), volumeValue(), error -> {
            synchronized (maleLock) {
                if (maleActive != request) return;
                maleActive = null;
                if (error != null && !error.isEmpty()) {
                    // Do not leave the user with silence. Try a locally installed voice only;
                    // network-required voices are filtered out in configurePhoneMaleFallback().
                    malePiperFailed = true;
                    if (configurePhoneMaleFallback()) {
                        voiceStatus = "Piper could not play the bundled voice (" + error
                                + "). Switched to the phone's offline TTS fallback.";
                        listener.onVoiceError("Bundled male voice failed. Using an installed offline phone voice instead.");
                        speakPhone(request.text,
                                request.priority ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD,
                                request.priority);
                    } else {
                        voiceStatus = "Offline male voice failed: " + error
                                + ". No installed offline phone voice is available as fallback.";
                        listener.onVoiceError(voiceStatus);
                    }
                } else {
                    voiceStatus = request.filipino
                            ? "Bundled offline male Filipino voice played successfully."
                            : "Bundled offline male English voice played successfully.";
                }
                startNextMaleLocked();
            }
        });
    }

    private void stopMaleQueue() {
        synchronized (maleLock) {
            malePending.clear();
            maleActive = null;
            piper.stop();
        }
    }

    private void speakPhone(String text, int mode, boolean priority) {
        String id = (priority ? "p-" : "a-") + counter.incrementAndGet();
        live.put(id, new long[]{SystemClock.elapsedRealtime(), priority ? 1 : 0});
        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volumeValue());
        if (tts.speak(text, mode, params, id) != TextToSpeech.SUCCESS) live.remove(id);
    }

    private void finish(String id) { if (id != null) live.remove(id); }
    private void purgeStale() {
        long now = SystemClock.elapsedRealtime();
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : live.entrySet()) {
            if (now - entry.getValue()[0] > 20_000) stale.add(entry.getKey());
        }
        for (String key : stale) live.remove(key);
    }

    void stopAll() {
        if (ready && tts != null) tts.stop();
        live.clear();
        stopMaleQueue();
    }

    void shutdown() {
        ready = false;
        stopMaleQueue();
        piper.close();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }
}
