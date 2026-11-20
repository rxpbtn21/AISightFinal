package com.example.aisightmobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.aisightmobile.AnnouncementPlanner.Plan;
import com.example.aisightmobile.AnnouncementPlanner.Settings;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class AnnouncementPlannerTest {
    static final int W = 640, H = 480;

    static Detection det(String label, float centerX, float meters) {
        Detection d = new Detection(label, 0.9f, 0, centerX - 50, 200, centerX + 50, 400);
        d.meters = meters;
        return d;
    }
    static List<Detection> list(Detection... ds) {
        List<Detection> l = new ArrayList<>();
        for (Detection d : ds) l.add(d);
        return l;
    }
    static Settings s(String mode, int max, boolean fil, boolean armed) { return new Settings(mode, max, fil, armed); }
    static Plan plan(AnnouncementPlanner p, List<Detection> l, long now, Settings st) {
        return p.plan(l, W, H, false, Float.NaN, now, st, true);
    }

    @Test public void closerAndMoreDangerousThingsComeFirst() {
        AnnouncementPlanner p = new AnnouncementPlanner();
        Plan plan = plan(p, list(det("bench", 320, 1.0f), det("person", 320, 1.0f)), 0, s("advanced", 2, false, true));
        assertEquals("person", plan.shown.get(0).label);     // same distance: high tier beats medium
        plan = new AnnouncementPlanner().plan(list(det("person", 320, 5.5f), det("person", 100, 1.0f)),
                W, H, false, Float.NaN, 0, s("advanced", 1, false, true), true);
        assertEquals(1.0f, plan.spoken.get(0).meters, 0.001f);   // closeness outweighs everything else
    }

    @Test public void silentUntilArmed() {
        AnnouncementPlanner p = new AnnouncementPlanner();
        Plan plan = plan(p, list(det("person", 320, 2f)), 0, s("default", 2, false, false));
        assertFalse(plan.speak);
        assertTrue(plan.message.length() > 0);
    }

    @Test public void cooldownAndNoRepeatOfSameSentence() {
        AnnouncementPlanner p = new AnnouncementPlanner();
        Settings st = s("advanced", 2, false, true);
        assertTrue(plan(p, list(det("person", 320, 2.0f)), 0, st).speak);
        assertFalse("inside cooldown", plan(p, list(det("person", 320, 3.0f)), 2000, st).speak);
        assertFalse("same sentence again", plan(p, list(det("person", 320, 2.0f)), 6000, st).speak);
        assertTrue("different sentence after cooldown", plan(p, list(det("person", 320, 3.0f)), 7000, st).speak);
    }

    @Test public void pausedClassIsSkippedAndTheNextOneIsSpoken() {
        AnnouncementPlanner p = new AnnouncementPlanner();
        p.pauseClass("person", 5 * 60_000L);
        Plan plan = plan(p, list(det("person", 320, 1.0f), det("dog", 100, 3.0f)), 1000, s("advanced", 1, false, true));
        assertEquals(1, plan.spoken.size());
        assertEquals("dog", plan.spoken.get(0).label);
        Plan later = plan(p, list(det("person", 320, 1.0f), det("dog", 100, 3.0f)), 5 * 60_000L + 1, s("advanced", 1, false, true));
        assertEquals("person", later.spoken.get(0).label);
        assertTrue(p.activePauses(5 * 60_000L + 1).isEmpty());
        p.pauseClass("dog", 99_999_999L);
        assertTrue(p.resumeClass("dog"));
        assertFalse(p.resumeClass("dog"));
    }

    @Test public void aDroppedAnnouncementIsOfferedAgain() {
        AnnouncementPlanner p = new AnnouncementPlanner();
        Settings st = s("advanced", 2, false, true);
        assertTrue(plan(p, list(det("person", 320, 2.0f)), 0, st).speak);
        p.unmarkSpoken();                                    // the speech engine was busy and dropped it
        assertTrue("must be offered again immediately", plan(p, list(det("person", 320, 2.0f)), 100, st).speak);
    }

    @Test public void unknownClassesAreDroppedForTheCustomModel() {
        AnnouncementPlanner p = new AnnouncementPlanner();
        Plan plan = plan(p, list(det("banana", 320, 1f)), 0, s("advanced", 2, false, true));
        assertTrue(plan.shown.isEmpty());
    }

    @Test public void objectCountIsRespected() {
        AnnouncementPlanner p = new AnnouncementPlanner();
        List<Detection> l = list(det("person", 100, 1f), det("car", 320, 2f), det("dog", 500, 3f));
        assertEquals(1, plan(p, l, 0, s("default", 1, false, true)).spoken.size());
        assertEquals(3, new AnnouncementPlanner().plan(l, W, H, false, 0f, 0, s("default", 3, false, true), true).spoken.size());
    }

    @Test public void wordsEnglish() {
        List<Detection> one = list(det("stop_sign", 320, 3.0f));
        assertEquals("stop sign, far, straight ahead.", AnnouncementPlanner.buildPhrase(withPos(one), "default", false, null));
        assertEquals("stop sign, straight ahead, 3.0 meters.", AnnouncementPlanner.buildPhrase(withPos(one), "advanced", false, null));
        Detection e = det("e-bike", 40, 1.0f);
        assertEquals("electric bike, very close, on your left.", AnnouncementPlanner.buildPhrase(withPos(list(e)), "default", false, null));
    }

    @Test public void wordsFilipino() {
        List<Detection> one = list(det("person", 320, 1.8f));
        assertEquals("tao, harap, 1.8 metro.", AnnouncementPlanner.buildPhrase(withPos(one), "advanced", true, null));
        assertEquals("tao, malapit, sa harap mo.", AnnouncementPlanner.buildPhrase(withPos(one), "default", true, null));
    }

    private static List<Detection> withPos(List<Detection> l) {
        for (Detection d : l) { d.pos3 = AnnouncementPlanner.position3(d.centerX(), W); d.pos5 = AnnouncementPlanner.position5(d.centerX(), W); }
        return l;
    }

    // ---- navigation -------------------------------------------------------------------------------------------
    @Test public void navigationAdvice() {
        Settings nav = s("navigation", 2, false, true);
        Plan a = plan(new AnnouncementPlanner(), list(det("person", 320, 1.0f)), 0, nav);
        assertEquals("stop", a.advice);                                     // in the way and very close
        assertEquals("person, straight ahead, 1.0 meters. stop.", a.message);

        Plan b = plan(new AnnouncementPlanner(), list(det("person", 320, 3.0f)), 0, nav);
        assertEquals("step right", b.advice);

        Plan c = plan(new AnnouncementPlanner(), list(det("car", 200, 3.0f)), 0, nav);    // left lane only
        assertEquals("step right", c.advice);

        Plan d = plan(new AnnouncementPlanner(), list(det("car", 500, 3.0f)), 0, nav);    // right lane only
        assertEquals("step left", d.advice);

        Plan e = plan(new AnnouncementPlanner(), list(det("car", 200, 3.0f), det("car", 500, 3.0f)), 0, nav);
        assertEquals("center is clear", e.advice);

        Plan far = plan(new AnnouncementPlanner(), list(det("car", 60, 3.0f)), 0, nav);   // far left = outside the path
        assertEquals("center is clear", far.advice);
    }

    @Test public void neverStepsTowardABlockedSide() {
        Settings nav = s("navigation", 2, false, true);
        assertEquals("step left", plan(new AnnouncementPlanner(), list(det("person", 320, 3.0f), det("truck", 500, 4.0f)), 0, nav).advice);
        assertEquals("step right", plan(new AnnouncementPlanner(), list(det("person", 320, 3.0f), det("truck", 200, 4.0f)), 0, nav).advice);
        assertEquals("stop", plan(new AnnouncementPlanner(), list(det("person", 320, 3.0f), det("truck", 200, 4.0f), det("car", 500, 4.0f)), 0, nav).advice);
    }

    @Test public void navigationLooksAtEverythingInRangeNotJustWhatItNames() {
        // only two things are NAMED, but the third one (right lane) is what makes "stop" the safe answer
        AnnouncementPlanner p = new AnnouncementPlanner();
        Plan plan = plan(p, list(det("person", 330, 1.5f), det("car", 200, 2.0f), det("truck", 500, 5.0f)), 0, s("navigation", 2, false, true));
        assertEquals(2, plan.spoken.size());
        assertEquals("stop", plan.advice);
    }

    @Test public void navigationIsQuietAboutFarAwayThings() {
        Plan plan = plan(new AnnouncementPlanner(), list(det("person", 320, 20.0f)), 0, s("navigation", 2, false, true));
        assertTrue(plan.spoken.isEmpty());
        assertFalse(plan.speak);
    }

    @Test public void navigationDoesNotNagAboutAParkedObject() {
        AnnouncementPlanner p = new AnnouncementPlanner();
        Settings nav = s("navigation", 2, false, true);
        assertTrue(plan(p, list(det("person", 320, 3.0f)), 0, nav).speak);
        assertFalse("3.0 m -> 2.9 m is the same situation", plan(p, list(det("person", 320, 2.9f)), 5000, nav).speak);
        assertFalse(plan(p, list(det("person", 320, 3.1f)), 9000, nav).speak);
        assertTrue("but remind after NAV_REPEAT", plan(p, list(det("person", 320, 3.0f)), 13000, nav).speak);
        assertTrue("and speak when it really changes", plan(p, list(det("person", 320, 1.0f)), 18000, nav).speak);
    }

    @Test public void navigationWithoutDepthSaysSo() {
        Detection d = new Detection("person", 0.9f, 0, 270, 200, 370, 400);   // meters stays infinite
        Plan plan = plan(new AnnouncementPlanner(), list(d), 0, s("navigation", 2, false, true));
        assertEquals(Lexicon.ADVICE_UNAVAILABLE, plan.advice);
        assertTrue(plan.message.contains("distance unavailable"));
    }

    // ---- wall ---------------------------------------------------------------------------------------------------
    @Test public void wallNeedsThreeSecondsOfProofAndFollowsPauses() {
        AnnouncementPlanner p = new AnnouncementPlanner();
        Settings st = s("advanced", 2, false, true);
        List<Detection> none = new ArrayList<>();
        assertTrue(p.plan(none, W, H, true, 0.9f, 0, st, true).shown.isEmpty());
        assertTrue(p.plan(none, W, H, true, 0.9f, 2900, st, true).shown.isEmpty());
        Plan confirmed = p.plan(none, W, H, true, 0.9f, 3000, st, true);
        assertEquals(Lexicon.WALL, confirmed.shown.get(0).label);
        // a pan interrupts it: the timer restarts
        p.plan(none, W, H, false, Float.NaN, 3100, st, true);
        assertTrue(p.plan(none, W, H, true, 0.9f, 3200, st, true).shown.isEmpty());
        // paused wall stays quiet
        p.pauseClass(Lexicon.WALL, 99_999_999L);
        assertTrue(p.plan(none, W, H, true, 0.9f, 7000, st, true).shown.isEmpty());
    }
}
