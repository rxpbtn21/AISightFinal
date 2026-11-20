package com.example.aisightmobile;

/** Numbers that were scattered through the old MainActivity, in one place. Same values as the Pi build. */
final class Tuning {
    private Tuning() {}

    // wall / obstruction heuristic (depth map only)
    static final float WALL_THRESHOLD_M   = 1.3f;
    static final float WALL_COVERAGE      = 0.50f;
    static final float WALL_X0 = 0.35f, WALL_X1 = 0.65f;
    static final float WALL_Y0 = 0.25f, WALL_Y1 = 0.85f;
    static final long  WALL_CONFIRM_MS    = 3000L;

    // distance words
    static final float NEAR_M = 1.0f;
    static final float MID_M  = 2.5f;

    // priority = tierWeight * (area * AREA_WEIGHT + closeness * CLOSE_WEIGHT)
    static final float AREA_WEIGHT  = 0.3f;
    static final float CLOSE_WEIGHT = 0.7f;
    static final float CLOSE_RANGE_M = 6.0f;

    // speech pacing
    static final long COOLDOWN_MS = 4500L;

    // navigation mode (experimental)
    static final int   NAV_MAX_OBJECTS      = 2;
    static final float NAV_ANNOUNCE_RANGE_M = 16.7f;
    static final float NAV_CLEAR_M          = 6.7f;
    static final float NAV_STOP_M           = 1.2f;
    static final long  NAV_REPEAT_MS        = 12000L;
    static final boolean NAV_ADVICE_FIRST   = false;   // true = "step left. person, ahead..."

    // voice command limits
    static final int PAUSE_DEFAULT_MIN = 1;
    static final int PAUSE_MAX_MIN     = 5;
    static final int RECORD_DEFAULT_S  = 10;
    static final int RECORD_MAX_S      = 60;
}
