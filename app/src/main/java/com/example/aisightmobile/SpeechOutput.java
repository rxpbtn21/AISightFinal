package com.example.aisightmobile;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Text-to-speech with TWO lanes, like the Pi build:
 *   say()          routine announcements. If anything is already being said they are DROPPED (stale news is worse
 *                  than none), and they never cut anything off.
 *   sayPriority()  command confirmations, alerts, OCR results. Interrupts routine speech, but queues behind other
 *                  priority speech so two alerts never cut each other off.
 * The old code used QUEUE_FLUSH for both, so a routine "chair, 2 m" could cut a "Camera disconnected" mid-sentence.
 */
final class SpeechOutput {
    interface Listener {
        void onReady(boolean ok);
        /** The phone has no voice for the chosen language; speech falls back to English. */
        void onLanguageMissing(boolean filipino);
    }

    private final AppSettings settings;
    private final Listener listener;
    private TextToSpeech tts;
    private volatile boolean ready;
    private final AtomicInteger counter = new AtomicInteger();
    /** utterance id -> {startedAt, isPriority} for everything queued or speaking */
    private final Map<String, long[]> live = new ConcurrentHashMap<>();

    SpeechOutput(Context context, AppSettings settings, Listener listener) {
        this.settings = settings;
        this.listener = listener;
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
            }
            listener.onReady(ready);
        });
    }

    boolean isReady() { return ready; }

    /** Re-apply language / voice / speed after a setting changed. */
    void configure() {
        if (!ready) return;
        boolean fil = settings.filipino;
        Locale wanted = fil ? new Locale("fil", "PH") : Locale.US;
        int result = tts.setLanguage(wanted);
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.US);
            listener.onLanguageMissing(fil);
        }
        boolean female = !"male".equals(settings.gender);
        tts.setPitch(female ? 1.07f : 0.92f);
        float rate = "slow".equals(settings.speed) ? 0.78f : "fast".equals(settings.speed) ? 1.2f : 1.0f;
        tts.setSpeechRate(rate);
        pickVoice(wanted.getLanguage(), female);
    }

    /** Prefer a voice that says it is male/female; if none does, the pitch above still tells them apart. */
    private void pickVoice(String language, boolean female) {
        try {
            Set<Voice> voices = tts.getVoices();
            if (voices == null) return;
            Voice best = null;
            for (Voice v : voices) {
                if (!language.equals(v.getLocale().getLanguage()) || v.isNetworkConnectionRequired()) continue;
                String name = v.getName().toLowerCase(Locale.US);
                boolean nameFemale = name.contains("female") || name.contains("-f-") || name.contains("#female");
                boolean nameMale = !nameFemale && (name.contains("male") || name.contains("-m-") || name.contains("#male"));
                if ((female && nameFemale) || (!female && nameMale)) { best = v; break; }
            }
            if (best != null) tts.setVoice(best);
        } catch (RuntimeException ignored) {
            // some engines throw from getVoices(); the pitch fallback is enough
        }
    }

    private float volumeValue() {
        switch (settings.volume) {
            case "low": return 0.45f;
            case "high": return 1.0f;
            default: return 0.8f;
        }
    }

    /** Routine announcement: dropped if the phone is already talking. @return false if it was dropped. */
    boolean say(String text) {
        if (!ready || text == null || text.isEmpty()) return false;
        purgeStale();
        if (!live.isEmpty()) return false;
        speak(text, TextToSpeech.QUEUE_ADD, false);
        return true;
    }

    /** Important: interrupts routine speech, waits behind other priority speech. */
    void sayPriority(String text) {
        if (!ready || text == null || text.isEmpty()) return;
        purgeStale();
        boolean priorityBusy = false;
        for (long[] v : live.values()) if (v[1] == 1) { priorityBusy = true; break; }
        speak(text, priorityBusy ? TextToSpeech.QUEUE_ADD : TextToSpeech.QUEUE_FLUSH, true);
    }

    private void speak(String text, int mode, boolean priority) {
        String id = (priority ? "p-" : "a-") + counter.incrementAndGet();
        live.put(id, new long[]{SystemClock.elapsedRealtime(), priority ? 1 : 0});
        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volumeValue());
        if (tts.speak(text, mode, params, id) != TextToSpeech.SUCCESS) live.remove(id);
    }

    private void finish(String id) { if (id != null) live.remove(id); }

    /** If an engine never reports completion, don't stay silent forever. */
    private void purgeStale() {
        long now = SystemClock.elapsedRealtime();
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, long[]> e : live.entrySet()) if (now - e.getValue()[0] > 20_000) stale.add(e.getKey());
        for (String k : stale) live.remove(k);
    }

    void stopAll() {
        if (ready) tts.stop();
        live.clear();
    }

    void shutdown() {
        ready = false;
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }
}
