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
 * Offline speech output.
 * - Male: bundled Piper neural voice, independent of the phone's TTS engine.
 * - Female: an already-installed, on-device Android TTS voice only.
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
    private volatile boolean offlineVoiceReady;
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
                voiceStatus = "The phone's TTS engine did not initialize. Built-in male voices may still work.";
            }
            listener.onReady(isReady());
        });
    }

    boolean isReady() {
        if (isMale()) return piper.isAvailable(settings.filipino);
        return ready && offlineVoiceReady;
    }

    /** Re-apply language / voice / speed after a setting changed. */
    void configure() {
        stopMaleQueue();
        live.clear();

        if (isMale()) {
            if (ready && tts != null) tts.stop();
            offlineVoiceReady = piper.isAvailable(settings.filipino);
            if (offlineVoiceReady) {
                voiceStatus = settings.filipino
                        ? "Built-in offline male Filipino/Tagalog neural voice selected. No internet required."
                        : "Built-in offline male English neural voice selected. No internet required.";
            } else {
                voiceStatus = "The bundled offline male voice is missing from this APK. Rebuild and reinstall AISight.";
            }
            return;
        }

        offlineVoiceReady = false;
        if (!ready || tts == null) {
            voiceStatus = "The phone's TTS engine is not ready. Male voice is bundled separately in AISight.";
            return;
        }

        boolean filipino = settings.filipino;
        Locale wanted = filipino ? new Locale("fil", "PH") : Locale.US;
        boolean languageSet = tts.setLanguage(wanted) >= TextToSpeech.LANG_AVAILABLE;
        if (!languageSet && filipino) {
            listener.onLanguageMissing(true);
            wanted = Locale.US;
            tts.setLanguage(wanted);
        }

        float rate = speechRate();
        tts.setSpeechRate(rate);
        boolean actualGender = pickLocalVoice(wanted, false);
        if (!offlineVoiceReady && filipino) {
            listener.onLanguageMissing(true);
            tts.setLanguage(Locale.US);
            actualGender = pickLocalVoice(Locale.US, false);
        }
        tts.setPitch(actualGender ? 1.0f : 1.06f);
        if (!offlineVoiceReady) {
            voiceStatus = "No installed offline female voice is available. Speech is paused rather than using network TTS.";
        }
    }

    String getVoiceStatus() { return voiceStatus; }

    private boolean pickLocalVoice(Locale wanted, boolean male) {
        offlineVoiceReady = false;
        try {
            Set<Voice> voices = tts.getVoices();
            if (voices == null || voices.isEmpty()) {
                voiceStatus = "This TTS engine exposes no voices. Install an offline voice in Android settings.";
                return false;
            }
            Voice genderMatch = null;
            Voice fallback = null;
            int fallbackScore = Integer.MIN_VALUE;
            for (Voice voice : voices) {
                Locale locale = voice.getLocale();
                if (locale == null || !sameLanguage(wanted, locale)) continue;
                if (voice.isNetworkConnectionRequired()) continue;
                Set<String> features = voice.getFeatures();
                if (features != null && features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                String name = voice.getName() == null ? "" : voice.getName().toLowerCase(Locale.US);
                int marker = genderMarker(name);
                if ((male && marker == 1) || (!male && marker == -1)) {
                    genderMatch = voice;
                    break;
                }
                if ((male && marker == -1) || (!male && marker == 1)) continue;
                int score = 0;
                if (wanted.getCountry().equalsIgnoreCase(locale.getCountry())) score += 20;
                if (voice.getQuality() == Voice.QUALITY_HIGH) score += 5;
                if (score > fallbackScore) { fallback = voice; fallbackScore = score; }
            }
            Voice chosen = genderMatch != null ? genderMatch : fallback;
            if (chosen == null) {
                voiceStatus = "No installed offline " + wanted.getDisplayLanguage(Locale.US)
                        + " female voice is available. Install offline voice data in Android settings.";
                return false;
            }
            if (tts.setVoice(chosen) != TextToSpeech.SUCCESS) {
                voiceStatus = "Could not activate the installed offline voice.";
                return false;
            }
            offlineVoiceReady = true;
            voiceStatus = genderMatch != null
                    ? "Installed offline female voice selected: " + chosen.getName()
                    : "Installed offline voice selected: " + chosen.getName()
                        + ". The engine did not identify its gender; slight pitch adjustment is used.";
            return genderMatch != null;
        } catch (RuntimeException ex) {
            voiceStatus = "Could not inspect installed offline voices. Check Android Text-to-speech settings.";
            return false;
        }
    }

    private boolean sameLanguage(Locale a, Locale b) {
        String one = normalizedLanguage(a.getLanguage());
        String two = normalizedLanguage(b.getLanguage());
        return one.equals(two);
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
        if (isMale()) {
            synchronized (maleLock) {
                if (maleActive != null || !malePending.isEmpty()) return false;
                startMaleLocked(new MaleRequest(text, false, settings.filipino));
                return true;
            }
        }
        if (!ready || !offlineVoiceReady) return false;
        purgeStale();
        if (!live.isEmpty()) return false;
        speakPhone(text, TextToSpeech.QUEUE_ADD, false);
        return true;
    }

    void sayPriority(String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (isMale()) {
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
        if (!ready || !offlineVoiceReady) return;
        purgeStale();
        boolean priorityBusy = false;
        for (long[] value : live.values()) if (value[1] == 1) { priorityBusy = true; break; }
        speakPhone(text, priorityBusy ? TextToSpeech.QUEUE_ADD : TextToSpeech.QUEUE_FLUSH, true);
    }

    private void startNextMaleLocked() {
        if (maleActive != null || malePending.isEmpty()) return;
        startMaleLocked(malePending.removeFirst());
    }

    private void startMaleLocked(MaleRequest request) {
        maleActive = request;
        if (!piper.isAvailable(request.filipino)) {
            maleActive = null;
            voiceStatus = "The bundled offline male voice for this language is unavailable.";
            listener.onVoiceError(voiceStatus);
            startNextMaleLocked();
            return;
        }
        piper.speak(request.text, request.filipino, speechRate(), volumeValue(), error -> {
            synchronized (maleLock) {
                if (maleActive != request) return;
                maleActive = null;
                if (error != null && !error.isEmpty()) {
                    voiceStatus = "Offline male voice error: " + error;
                    listener.onVoiceError(voiceStatus);
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
