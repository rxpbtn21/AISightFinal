package com.example.aisightmobile;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Decides whether the camera is REALLY in trouble. Pure Java; every method takes the time as an argument.
 *
 * Why this exists: the old watchdog measured "time since the analyzer callback last STARTED". That callback ran the
 * whole YOLO + depth pipeline on the same thread, so one slow frame (>4 s on a busy phone) looked like a dead camera
 * -> "Camera disconnected" -> the camera was rebound, which also reset the "already faulted" flag -> announced again,
 * forever (about 22 announcements per 2 minutes in a simulation of the old logic). Here:
 *   - the heartbeat is stamped by a callback that does NO work (see CameraController), so slow analysis can't starve it;
 *   - a fault is announced ONCE per episode and at most once a minute, no matter how often the state flaps;
 *   - "restored" is only said if the fault was announced, and only after frames have flowed steadily for a while;
 *   - rebinding backs off (3 s, 6 s, 12 s, 24 s, then every 30 s) instead of hammering the camera;
 *   - nothing is evaluated while the app is in the background, and every return to the foreground gets a fresh grace.
 */
final class CameraHealth {

    enum State { STARTING, HEALTHY, FAULT }

    interface Listener {
        /** Once per fault episode (rate limited): sound the alarm and say it. */
        void onFaultAnnounced(String reason);
        /** Only if the fault was announced. */
        void onRestoredAnnounced();
        /** Unbind and bind the camera again. `attempt` starts at 1. */
        void onRebindRequested(int attempt);
        /** Every state change, for the status chip. */
        void onStateChanged(State state, String detail);
    }

    static final long FIRST_FRAME_GRACE_MS = 10_000;  // first start is slow: permission dialog, model loading
    static final long NO_FRAME_MS          = 5_000;   // frames were flowing, then nothing for this long
    static final long HARD_ERROR_MS        = 2_500;   // CameraX reported an error (in use, disabled, ...) this long ago
    static final long RESTORE_HOLD_MS      = 1_500;   // frames must flow steadily this long before we say "restored"
    static final int  RESTORE_FRAMES       = 5;
    static final long MIN_ANNOUNCE_GAP_MS  = 60_000;  // never announce a camera fault more than once a minute
    static final long[] BACKOFF_MS = {3_000, 6_000, 12_000, 24_000, 30_000};

    private final Listener listener;

    // touched from the camera thread
    private volatile long lastFrameAt = 0;
    private final AtomicInteger framesSinceBind = new AtomicInteger();

    // main thread only
    private State state = State.STARTING;
    private boolean visible = false;
    private long bindAt = 0;
    private String hardError = null;
    private long hardErrorSince = 0;
    private long lastAnnounceAt = -1;
    private boolean faultWasAnnounced = false;
    private long recoveringSince = -1;
    private int rebindAttempts = 0;
    private long nextRebindAt = 0;

    CameraHealth(Listener listener) { this.listener = listener; }

    State state() { return state; }

    // ---- inputs ------------------------------------------------------------------------------------------------
    /** Camera thread. Must stay trivially cheap: this is the heartbeat. */
    void onFrame(long now) {
        lastFrameAt = now;
        framesSinceBind.incrementAndGet();
    }

    /** The camera is being (re)bound: wait for a first frame before judging anything. */
    void onBindStarted(long now) {
        bindAt = now;
        lastFrameAt = 0;
        framesSinceBind.set(0);
        recoveringSince = -1;
    }

    /** From CameraX's CameraState: a human-readable error, or null when there is none. */
    void onCameraError(String errorOrNull, long now) {
        if (errorOrNull == null) {
            hardError = null;
        } else if (!errorOrNull.equals(hardError)) {
            hardError = errorOrNull;
            hardErrorSince = now;
        }
    }

    void onVisibility(boolean nowVisible, long now) {
        if (nowVisible && !visible) {
            // coming back to the foreground is a brand-new session: fresh grace, no leftover fault
            bindAt = now;
            lastFrameAt = 0;
            framesSinceBind.set(0);
            recoveringSince = -1;
            hardError = null;
            faultWasAnnounced = false;
            rebindAttempts = 0;
            setState(State.STARTING, "");
        }
        visible = nowVisible;
    }

    // ---- the decision (call about once a second on the main thread) --------------------------------------------
    void tick(long now) {
        if (!visible) return;

        int frames = framesSinceBind.get();
        boolean noFrames = frames == 0 ? (now - bindAt) > FIRST_FRAME_GRACE_MS
                                       : (now - lastFrameAt) > NO_FRAME_MS;
        boolean hard = hardError != null && (now - hardErrorSince) >= HARD_ERROR_MS;

        if (state != State.FAULT) {
            if (noFrames || hard) {
                enterFault(now, hard ? hardError : "no frames from the camera");
            } else if (state == State.STARTING && frames >= 1) {
                setState(State.HEALTHY, "");     // quietly: nothing to announce about a normal start
            }
            return;
        }

        // FAULT: wait for steady frames, otherwise keep trying to rebind with backoff
        boolean flowing = frames >= RESTORE_FRAMES && (now - lastFrameAt) < 1_000 && hardError == null;
        if (flowing) {
            if (recoveringSince < 0) recoveringSince = now;
            if (now - recoveringSince >= RESTORE_HOLD_MS) leaveFault();
        } else {
            recoveringSince = -1;
            if (now >= nextRebindAt) requestRebind(now);
        }
    }

    // ---- transitions -------------------------------------------------------------------------------------------
    private void enterFault(long now, String reason) {
        rebindAttempts = 0;
        nextRebindAt = now + BACKOFF_MS[0];
        recoveringSince = -1;
        boolean announce = lastAnnounceAt < 0 || now - lastAnnounceAt >= MIN_ANNOUNCE_GAP_MS;
        faultWasAnnounced = announce;
        setState(State.FAULT, reason);
        if (announce) {
            lastAnnounceAt = now;
            listener.onFaultAnnounced(reason);
        }
    }

    private void requestRebind(long now) {
        rebindAttempts++;
        nextRebindAt = now + BACKOFF_MS[Math.min(rebindAttempts, BACKOFF_MS.length - 1)];
        listener.onRebindRequested(rebindAttempts);
    }

    private void leaveFault() {
        boolean announceRestore = faultWasAnnounced;
        faultWasAnnounced = false;
        rebindAttempts = 0;
        recoveringSince = -1;
        setState(State.HEALTHY, "");
        if (announceRestore) listener.onRestoredAnnounced();
    }

    private void setState(State s, String detail) {
        state = s;
        listener.onStateChanged(s, detail);
    }
}
