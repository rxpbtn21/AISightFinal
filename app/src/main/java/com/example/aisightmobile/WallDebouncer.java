package com.example.aisightmobile;

/** A wall only counts once it has been seen continuously for WALL_CONFIRM_MS (a camera pan must not trigger it). */
final class WallDebouncer {
    private long trueSince = -1;

    boolean update(boolean rawWall, long nowMs) {
        if (!rawWall) {
            trueSince = -1;
            return false;
        }
        if (trueSince < 0) trueSince = nowMs;
        return nowMs - trueSince >= Tuning.WALL_CONFIRM_MS;
    }

    void reset() { trueSince = -1; }
}
