package com.example.aisightmobile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides WHAT to say and WHEN, from the raw detections of one frame. Pure Java, no Android, no clock of its own
 * (callers pass `now`), so every rule here is unit-tested.
 */
final class AnnouncementPlanner {

    static final class Settings {
        final String phraseMode;   // "default" | "advanced" | "navigation"
        final int maxSpeak;        // 1..3
        final boolean filipino;
        final boolean armed;       // false = silent until the user says start
        Settings(String phraseMode, int maxSpeak, boolean filipino, boolean armed) {
            this.phraseMode = phraseMode; this.maxSpeak = maxSpeak; this.filipino = filipino; this.armed = armed;
        }
    }

    static final class Plan {
        final List<Detection> shown;    // everything worth drawing, best first
        final List<Detection> spoken;   // what the sentence is about
        final String advice;            // navigation mode only, else null
        final String message;           // "" if nothing to say
        final boolean speak;            // say it NOW
        Plan(List<Detection> shown, List<Detection> spoken, String advice, String message, boolean speak) {
            this.shown = shown; this.spoken = spoken; this.advice = advice; this.message = message; this.speak = speak;
        }
    }

    private final WallDebouncer wall = new WallDebouncer();
    private final Map<String, Long> pausedUntil = new HashMap<>();
    private long lastSpokenAt = -1;
    private String lastMessage = "";
    private String lastNavSignature = null;
    private String lastAnnouncement = "";

    // ---- per-class pauses ("pause person five minutes") ----------------------------------------------------------
    synchronized void pauseClass(String label, long untilMs) { pausedUntil.put(label, untilMs); }
    synchronized boolean resumeClass(String label) { return pausedUntil.remove(label) != null; }

    synchronized Set<String> activePauses(long now) {
        Set<String> out = new HashSet<>();
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, Long> e : pausedUntil.entrySet()) {
            if (e.getValue() <= now) expired.add(e.getKey()); else out.add(e.getKey());
        }
        for (String k : expired) pausedUntil.remove(k);
        return out;
    }

    /** The sentence could not actually be spoken (engine busy): forget it so it is offered again next frame. */
    synchronized void unmarkSpoken() {
        lastSpokenAt = -1;
        lastMessage = "";
        lastNavSignature = null;
    }

    synchronized String lastAnnouncement() { return lastAnnouncement; }
    synchronized void rememberAnnouncement(String text) { lastAnnouncement = text; }

    // ---- scoring helpers ---------------------------------------------------------------------------------------
    static String distanceBucket(float meters) {
        if (Float.isNaN(meters) || Float.isInfinite(meters)) return "distance unavailable";
        if (meters <= Tuning.NEAR_M) return "very close";
        if (meters <= Tuning.MID_M) return "nearby";
        return "far";
    }

    static String position3(float cx, float width) {
        float r = cx / Math.max(1f, width);
        if (r < 0.38f) return "on your left";
        if (r > 0.62f) return "on your right";
        return "straight ahead";
    }

    static String position5(float cx, float width) {
        float r = cx / Math.max(1f, width);
        if (r < 0.20f) return "far left";
        if (r < 0.40f) return "left";
        if (r < 0.60f) return "front";
        if (r < 0.80f) return "right";
        return "far right";
    }

    static float closeness(float meters) {
        if (Float.isNaN(meters) || Float.isInfinite(meters)) return 0f;
        return Math.max(0f, 1f - Math.min(Math.max(0.05f, meters) / Tuning.CLOSE_RANGE_M, 1f));
    }

    /** "step left" / "step right" / "stop" / "center is clear", from what is actually in the way. */
    static String navigationAdvice(List<Detection> inRange) {
        List<Detection> blocking = new ArrayList<>();
        for (Detection d : inRange) if (d.hasDistance() && d.meters <= Tuning.NAV_CLEAR_M) blocking.add(d);
        if (blocking.isEmpty()) return "center is clear";

        boolean center = false, left = false, right = false, tooClose = false;
        for (Detection d : blocking) {
            if ("front".equals(d.pos5)) {
                center = true;
                if (d.meters <= Tuning.NAV_STOP_M) tooClose = true;
            } else if ("left".equals(d.pos5)) left = true;
            else if ("right".equals(d.pos5)) right = true;
        }
        if (tooClose) return "stop";
        if (center) {
            // never send the user toward a side that is blocked too
            if (!right) return "step right";
            if (!left) return "step left";
            return "stop";
        }
        if (left && !right) return "step right";
        if (right && !left) return "step left";
        return "center is clear";
    }

    /** Coarse on purpose: a parked object must not be re-announced because 1.2 m became 1.1 m. */
    static String navSignature(List<Detection> spoken, String advice) {
        StringBuilder sb = new StringBuilder();
        for (Detection d : spoken) {
            sb.append(d.label).append('|').append(d.pos5).append('|');
            sb.append(d.hasDistance() ? Math.round(d.meters * 2f) / 2f : -1f).append(';');
        }
        return sb.append(advice).toString();
    }

    static String buildPhrase(List<Detection> spoken, String mode, boolean fil, String advice) {
        if (spoken.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        for (Detection d : spoken) {
            String name = Lexicon.spokenLabel(d.label, fil);
            if ("advanced".equals(mode) || "navigation".equals(mode)) {
                String where = Lexicon.term(d.pos5, fil);
                String how = d.hasDistance()
                        ? String.format(java.util.Locale.US, "%.1f %s", d.meters, Lexicon.term("meters", fil))
                        : Lexicon.term("distance unavailable", fil);
                parts.add(name + ", " + where + ", " + how);
            } else {
                parts.add(name + ", " + Lexicon.term(distanceBucket(d.meters), fil) + ", " + Lexicon.term(d.pos3, fil));
            }
        }
        String phrase = join(parts, ", ");
        if ("navigation".equals(mode) && advice != null) {
            String step = Lexicon.term(advice, fil);
            return Tuning.NAV_ADVICE_FIRST ? step + ". " + phrase + "." : phrase + ". " + step + ".";
        }
        return phrase + ".";
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) { if (i > 0) sb.append(sep); sb.append(parts.get(i)); }
        return sb.toString();
    }

    // ---- the main entry point ---------------------------------------------------------------------------------
    /**
     * @param raw         detections from the model, with .meters already filled in from the depth map
     * @param wallRaw     the depth map currently looks like a wall straight ahead
     * @param wallMeters  mean distance of that wall region
     * @param filterUnknown drop classes that aren't in the Lexicon (true for the custom model)
     */
    synchronized Plan plan(List<Detection> raw, int frameW, int frameH, boolean wallRaw, float wallMeters,
                           long now, Settings s, boolean filterUnknown) {
        Set<String> paused = activePauses(now);
        List<Detection> shown = new ArrayList<>();

        for (Detection d : raw) {
            if (filterUnknown && !Lexicon.isKnown(d.label)) continue;
            if (paused.contains(d.label)) continue;
            d.pos3 = position3(d.centerX(), frameW);
            d.pos5 = position5(d.centerX(), frameW);
            float areaRatio = d.area() / Math.max(1f, (float) frameW * frameH);
            d.priority = Lexicon.weight(d.label) * (areaRatio * Tuning.AREA_WEIGHT + closeness(d.meters) * Tuning.CLOSE_WEIGHT);
            shown.add(d);
        }

        // the wall alert is always evaluated so the debouncer keeps running, even while it's paused
        boolean wallConfirmed = wall.update(wallRaw, now);
        if (wallConfirmed && !paused.contains(Lexicon.WALL)) {
            Detection w = new Detection(Lexicon.WALL, 1f, -1,
                    frameW * Tuning.WALL_X0, frameH * Tuning.WALL_Y0, frameW * Tuning.WALL_X1, frameH * Tuning.WALL_Y1);
            w.meters = wallMeters;
            w.pos3 = position3(w.centerX(), frameW);
            w.pos5 = position5(w.centerX(), frameW);
            w.priority = Lexicon.weight(Lexicon.WALL) * (0.15f + closeness(wallMeters) * Tuning.CLOSE_WEIGHT);
            shown.add(w);
        }

        Collections.sort(shown, (a, b) -> Float.compare(b.priority, a.priority));

        boolean nav = "navigation".equals(s.phraseMode);
        List<Detection> spoken = new ArrayList<>();
        String advice = null;

        if (nav) {
            List<Detection> inRange = new ArrayList<>(), noDistance = new ArrayList<>();
            for (Detection d : shown) {
                if (d.hasDistance()) { if (d.meters <= Tuning.NAV_ANNOUNCE_RANGE_M) inRange.add(d); }
                else noDistance.add(d);
            }
            List<Detection> pool = inRange.isEmpty() ? noDistance : inRange;
            int limit = Math.min(s.maxSpeak, Tuning.NAV_MAX_OBJECTS);
            for (int i = 0; i < pool.size() && i < limit; i++) spoken.add(pool.get(i));
            if (!spoken.isEmpty()) {
                // advice looks at EVERYTHING in range, not just the 1-2 things being named
                advice = inRange.isEmpty() ? Lexicon.ADVICE_UNAVAILABLE : navigationAdvice(inRange);
            }
        } else {
            for (int i = 0; i < shown.size() && i < s.maxSpeak; i++) spoken.add(shown.get(i));
        }

        String message = buildPhrase(spoken, s.phraseMode, s.filipino, advice);
        boolean speak = false;
        if (s.armed && !spoken.isEmpty()) {
            boolean cooled = lastSpokenAt < 0 || now - lastSpokenAt >= Tuning.COOLDOWN_MS;
            if (nav) {
                String sig = navSignature(spoken, advice);
                boolean changed = !sig.equals(lastNavSignature);
                boolean stale = lastSpokenAt < 0 || now - lastSpokenAt >= Tuning.NAV_REPEAT_MS;
                speak = cooled && (changed || stale);
                if (speak) lastNavSignature = sig;
            } else {
                speak = cooled && !message.equals(lastMessage);
            }
            if (speak) {
                lastSpokenAt = now;
                lastMessage = message;
                lastAnnouncement = message;
            }
        }
        return new Plan(shown, spoken, advice, message, speak);
    }
}
