package com.example.aisightmobile;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

/**
 * The phone knows its real battery level, so the old "8 hour powerbank" time estimate (a workaround for the Pi) is gone.
 * Warns once at 20% and once at 10% (re-armed after charging).
 */
final class BatteryMonitor {
    private final Context context;
    private boolean warned20, warned10;

    BatteryMonitor(Context context) { this.context = context.getApplicationContext(); }

    /** @return percent 0..100, or -1 if unknown. */
    int percent() {
        Intent i = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i == null) return -1;
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        return level < 0 || scale <= 0 ? -1 : Math.round(level * 100f / scale);
    }

    boolean charging() {
        Intent i = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i == null) return false;
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
    }

    String phrase(boolean fil) {
        int p = percent();
        if (p < 0) return fil ? "Hindi makuha ang antas ng baterya." : "Battery level unavailable.";
        String base = fil ? "Baterya ng telepono, " + p + " porsyento." : "Phone battery " + p + " percent.";
        if (charging()) base += fil ? " Naka-charge." : " Charging.";
        return base;
    }

    /** @return a warning to speak, or null. Call about once a minute. */
    String warning(boolean fil) {
        int p = percent();
        if (p < 0 || charging()) {
            if (charging()) { warned20 = false; warned10 = false; }
            return null;
        }
        if (p <= 10 && !warned10) {
            warned10 = true; warned20 = true;
            return fil ? "Babala. Baterya, " + p + " porsyento. I-charge na." : "Warning. Battery " + p + " percent. Please charge soon.";
        }
        if (p <= 20 && !warned20) {
            warned20 = true;
            return fil ? "Baterya, " + p + " porsyento." : "Battery " + p + " percent.";
        }
        return null;
    }
}
