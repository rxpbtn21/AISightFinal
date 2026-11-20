package com.example.aisightmobile;

import android.content.Context;
import android.content.SharedPreferences;

/** Everything the user can change. Fields are volatile so the analysis thread can read them without locks. */
final class AppSettings {
    private final SharedPreferences prefs;

    volatile String phraseMode = "advanced";   // "default" | "advanced" | "navigation"
    volatile int maxSpeak = 2;                 // 1..3
    volatile boolean filipino = false;
    volatile String gender = "female";         // "female" | "male"
    volatile String speed = "normal";          // "slow" | "normal" | "fast"
    volatile String volume = "normal";         // "low" | "normal" | "high"
    volatile float confidence = 0.45f;
    volatile boolean wearable = false;         // screen goes black, camera/voice keep working
    volatile boolean autoStart = false;        // start announcing as soon as the app opens
    volatile boolean fallDetection = true;
    volatile boolean volumeKeys = true;        // volume buttons as shortcuts in wearable mode

    /** Runtime only: announcements are off until the user says "start" (or taps START). */
    volatile boolean armed = false;

    AppSettings(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("aisight_settings_v2", Context.MODE_PRIVATE);
        phraseMode = prefs.getString("phraseMode", phraseMode);
        maxSpeak = clamp(prefs.getInt("maxSpeak", maxSpeak), 1, 3);
        filipino = prefs.getBoolean("filipino", filipino);
        gender = prefs.getString("gender", gender);
        speed = prefs.getString("speed", speed);
        volume = prefs.getString("volume", volume);
        confidence = Math.max(0.25f, Math.min(0.75f, prefs.getFloat("confidence", confidence)));
        wearable = prefs.getBoolean("wearable", wearable);
        autoStart = prefs.getBoolean("autoStart", autoStart);
        fallDetection = prefs.getBoolean("fallDetection", fallDetection);
        volumeKeys = prefs.getBoolean("volumeKeys", volumeKeys);
        armed = autoStart;
    }

    void save() {
        prefs.edit()
                .putString("phraseMode", phraseMode).putInt("maxSpeak", maxSpeak).putBoolean("filipino", filipino)
                .putString("gender", gender).putString("speed", speed).putString("volume", volume)
                .putFloat("confidence", confidence).putBoolean("wearable", wearable).putBoolean("autoStart", autoStart)
                .putBoolean("fallDetection", fallDetection).putBoolean("volumeKeys", volumeKeys)
                .apply();
    }

    AnnouncementPlanner.Settings planner() {
        return new AnnouncementPlanner.Settings(phraseMode, maxSpeak, filipino, armed);
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
