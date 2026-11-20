package com.example.aisightmobile;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * Loud repeating tone (alarm volume) + vibration. Same sound and pattern as the old build.
 * start() never SHORTENS an alarm that is already running longer (a 5 s camera alert must not cut a 30 s fall alarm),
 * and the auto-stop runs on its own timer, not on the camera loop.
 */
final class AlarmController {
    interface Listener { void onAlarmChanged(boolean active, String reason); }

    private static final long[] PATTERN = {0, 350, 180, 350, 450};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final Vibrator vibrator;
    private ToneGenerator tone;
    private boolean active;
    private long endsAt;
    private String reason = "";

    private final Runnable beep = new Runnable() {
        @Override public void run() {
            if (!active) return;
            if (tone != null) tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 800);
            main.postDelayed(this, 1100);
        }
    };
    private final Runnable autoStop = this::stop;

    @SuppressWarnings("deprecation")
    AlarmController(Context context, Listener listener) {
        this.listener = listener;
        Vibrator v;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            v = vm == null ? null : vm.getDefaultVibrator();
        } else {
            v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        }
        vibrator = v;
    }

    boolean isActive() { return active; }
    String reason() { return reason; }

    @SuppressWarnings("deprecation")
    void start(long durationMs, String why) {
        long end = SystemClock.elapsedRealtime() + durationMs;
        if (active && end <= endsAt) return;            // never shorten
        endsAt = end;
        main.removeCallbacks(autoStop);
        main.postDelayed(autoStop, durationMs);
        if (active) return;
        active = true;
        reason = why;
        try {
            tone = new ToneGenerator(AudioManager.STREAM_ALARM, 90);
        } catch (RuntimeException ex) {
            tone = null;                                 // vibration still works
        }
        main.post(beep);
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(PATTERN, 0));
            } else {
                vibrator.vibrate(PATTERN, 0);
            }
        }
        listener.onAlarmChanged(true, why);
    }

    void stop() {
        main.removeCallbacks(autoStop);
        main.removeCallbacks(beep);
        if (!active) return;
        active = false;
        if (tone != null) {
            tone.stopTone();
            tone.release();
            tone = null;
        }
        if (vibrator != null) vibrator.cancel();
        listener.onAlarmChanged(false, "");
    }
}
