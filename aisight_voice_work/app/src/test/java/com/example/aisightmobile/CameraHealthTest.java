package com.example.aisightmobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Reproduces the "Camera disconnected" spam and proves the new state machine can't do it. */
public class CameraHealthTest {

    /** Simulated clock + recorder around a CameraHealth. */
    static final class Sim implements CameraHealth.Listener {
        final CameraHealth health = new CameraHealth(this);
        long now = 1_000_000;
        int announcedFaults, announcedRestores;
        final List<Long> rebindTimes = new ArrayList<>();

        @Override public void onFaultAnnounced(String reason) { announcedFaults++; }
        @Override public void onRestoredAnnounced() { announcedRestores++; }
        @Override public void onRebindRequested(int attempt) {
            rebindTimes.add(now);
            health.onBindStarted(now);          // what CameraController does
        }
        @Override public void onStateChanged(CameraHealth.State state, String detail) { }

        Sim start() {
            health.onVisibility(true, now);
            health.onBindStarted(now);
            return this;
        }

        /** Runs the clock for `ms`, with the camera delivering a frame every `frameEveryMs` (0 = camera dead). */
        void run(long ms, long frameEveryMs) {
            long end = now + ms, nextFrame = now, nextTick = now;
            while (now < end) {
                if (frameEveryMs > 0 && now >= nextFrame) { health.onFrame(now); nextFrame = now + frameEveryMs; }
                if (now >= nextTick) { health.tick(now); nextTick = now + 1000; }
                now += 50;
            }
        }
    }

    @Test public void slowAnalysisNeverLooksLikeADeadCamera() {
        // THE BUG: frames keep arriving but each analysis takes 8 s. Heartbeat is stamped on arrival, so: no fault.
        Sim s = new Sim().start();
        s.run(120_000, 100);
        assertEquals(0, s.announcedFaults);
        assertEquals(0, s.rebindTimes.size());
        assertEquals(CameraHealth.State.HEALTHY, s.health.state());
    }

    @Test public void deadCameraIsAnnouncedExactlyOnceAndRebindsBackOff() {
        Sim s = new Sim().start();
        s.run(3_000, 100);                       // healthy first
        s.run(300_000, 0);                       // then the camera goes silent for 5 minutes
        assertEquals(1, s.announcedFaults);
        assertEquals(0, s.announcedRestores);
        assertEquals(CameraHealth.State.FAULT, s.health.state());
        assertTrue("expected several rebinds, got " + s.rebindTimes.size(), s.rebindTimes.size() >= 8);
        long gap1 = s.rebindTimes.get(1) - s.rebindTimes.get(0);
        long gap2 = s.rebindTimes.get(2) - s.rebindTimes.get(1);
        long gapLate = s.rebindTimes.get(s.rebindTimes.size() - 1) - s.rebindTimes.get(s.rebindTimes.size() - 2);
        assertTrue("backoff should grow: " + gap1 + " then " + gap2, gap2 > gap1);
        assertTrue("late retries are capped near 30 s, got " + gapLate, gapLate >= 29_000 && gapLate <= 32_000);
    }

    @Test public void restoredIsAnnouncedOnceAfterSteadyFrames() {
        Sim s = new Sim().start();
        s.run(3_000, 100);
        s.run(20_000, 0);
        assertEquals(1, s.announcedFaults);
        s.run(10_000, 100);                      // camera comes back
        assertEquals(CameraHealth.State.HEALTHY, s.health.state());
        assertEquals(1, s.announcedRestores);
        s.run(60_000, 100);
        assertEquals(1, s.announcedFaults);
        assertEquals(1, s.announcedRestores);
    }

    @Test public void flappingIsRateLimitedToOneAnnouncementPerMinute() {
        Sim s = new Sim().start();
        s.run(3_000, 100);
        for (int i = 0; i < 3; i++) {            // 3 outages inside one minute
            s.run(8_000, 0);
            s.run(6_000, 100);
        }
        assertEquals("only the first outage may be announced", 1, s.announcedFaults);
        assertEquals("and only that one gets a 'restored'", 1, s.announcedRestores);
        // after a quiet minute, a new outage IS announced again
        s.run(70_000, 100);
        s.run(12_000, 0);
        assertEquals(2, s.announcedFaults);
    }

    @Test public void firstFrameGetsAGenerousGrace() {
        Sim s = new Sim().start();
        s.run(9_000, 0);                         // model loading, permission dialog... no frames yet
        assertEquals(0, s.announcedFaults);
        s.run(3_000, 0);                         // now past the 10 s grace
        assertEquals(1, s.announcedFaults);
    }

    @Test public void nothingIsJudgedInTheBackground() {
        Sim s = new Sim().start();
        s.run(3_000, 100);
        s.health.onVisibility(false, s.now);     // user switches apps; camera legitimately stops
        s.run(120_000, 0);
        assertEquals(0, s.announcedFaults);
        s.health.onVisibility(true, s.now);      // back again: fresh grace period
        s.run(8_000, 0);
        assertEquals(0, s.announcedFaults);
        s.run(5_000, 100);
        assertEquals(CameraHealth.State.HEALTHY, s.health.state());
    }

    @Test public void returningFromBackgroundClearsAnOldFault() {
        Sim s = new Sim().start();
        s.run(3_000, 100);
        s.run(15_000, 0);
        assertEquals(1, s.announcedFaults);
        s.health.onVisibility(false, s.now);
        s.run(5_000, 0);
        s.health.onVisibility(true, s.now);
        s.run(3_000, 100);
        assertEquals(CameraHealth.State.HEALTHY, s.health.state());
        assertEquals("no 'restored' for a fault the user already left behind", 0, s.announcedRestores);
    }

    @Test public void cameraTakenByAnotherAppIsReportedEvenIfAStaleFrameArrives() {
        Sim s = new Sim().start();
        s.run(3_000, 100);
        s.health.onCameraError("Camera is in use by another app", s.now);
        s.run(5_000, 100);                       // frames still trickling, but CameraX says it's broken
        assertEquals(1, s.announcedFaults);
        s.health.onCameraError(null, s.now);     // the other app let go
        s.run(10_000, 100);
        assertEquals(CameraHealth.State.HEALTHY, s.health.state());
        assertEquals(1, s.announcedRestores);
    }
}
