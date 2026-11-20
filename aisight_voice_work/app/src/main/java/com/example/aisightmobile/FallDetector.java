package com.example.aisightmobile;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;

/**
 * Same two-phase rule as the Pi build: a free-fall dip followed by an impact spike within a second.
 * Baseline is the real constant for gravity (the phone reports m/s^2), not a one-off calibration: the old code
 * calibrated once at launch, so starting the app while walking skewed the thresholds for the whole session.
 */
final class FallDetector implements SensorEventListener {
    interface Listener { void onFall(); }

    private static final float FREEFALL_RATIO = 0.4f;
    private static final float IMPACT_RATIO = 2.5f;
    private static final long WINDOW_MS = 1000;
    private static final long COOLDOWN_MS = 30_000;

    private final SensorManager sensors;
    private final Listener listener;
    private boolean running;
    private long freefallAt = -1;
    private long lastFallAt = -1;

    FallDetector(Context context, Listener listener) {
        this.sensors = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.listener = listener;
    }

    boolean isAvailable() { return sensors != null && sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null; }

    void start() {
        if (running || !isAvailable()) return;
        running = sensors.registerListener(this, sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                SensorManager.SENSOR_DELAY_GAME);
        freefallAt = -1;
    }

    void stop() {
        if (!running) return;
        sensors.unregisterListener(this);
        running = false;
    }

    @Override public void onSensorChanged(SensorEvent e) {
        float ratio = (float) Math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                / SensorManager.GRAVITY_EARTH;
        long now = SystemClock.elapsedRealtime();
        if (ratio <= FREEFALL_RATIO) {
            freefallAt = now;
        } else if (freefallAt >= 0) {
            if (now - freefallAt > WINDOW_MS) {
                freefallAt = -1;
            } else if (ratio >= IMPACT_RATIO) {
                freefallAt = -1;
                if (lastFallAt < 0 || now - lastFallAt >= COOLDOWN_MS) {
                    lastFallAt = now;
                    listener.onFall();
                }
            }
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
}
