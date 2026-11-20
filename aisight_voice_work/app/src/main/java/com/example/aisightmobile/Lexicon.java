package com.example.aisightmobile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every word the app knows, in one place: the model's classes, priority tiers, how each class is
 * spoken in English and Filipino, and how the user can say a class name out loud. Pure Java.
 */
final class Lexicon {
    private Lexicon() {}

    /** Pseudo-class produced by the depth map (not a YOLO class). */
    static final String WALL = "wall/obstruction";
    static final String ADVICE_UNAVAILABLE = "distance unavailable; do not rely on navigation advice";

    /** Class order of the exported aisight.pt. MUST match training order. */
    static final String[] MODEL_LABELS = {
            "stop_sign", "e-tricycle", "car", "person", "e-bike", "e-trike", "tricycle", "van", "bench", "bike",
            "bus", "cat", "crosswalk", "dog", "manhole", "hazard_sign", "jeepney", "motorcycle", "door", "pit_hole",
            "puddle", "ramps", "stairs", "tree", "truck", "uncovered_manhole", "utility_pole"};

    // ---- priority tiers ---------------------------------------------------------------------------------------
    private static final Map<String, String> TIER = new HashMap<>();
    static {
        for (String s : new String[]{"person", "car", "van", "bus", "truck", "jeepney", "motorcycle", "tricycle",
                "e-tricycle", "e-trike", "e-bike", "bike", "stop_sign", "stairs", "pit_hole", "uncovered_manhole",
                "hazard_sign", "utility_pole"}) TIER.put(s, "high");
        for (String s : new String[]{"bench", "cat", "dog", "tree", "door", "puddle", "crosswalk", "ramps"}) TIER.put(s, "medium");
        TIER.put("manhole", "low");
        TIER.put(WALL, "low");
    }

    static boolean isKnown(String label) { return TIER.containsKey(label); }

    /** "high" / "medium" / "low" (unknown labels count as low). */
    static String tier(String label) {
        String t = TIER.get(label);
        return t == null ? "low" : t;
    }

    static float weight(String label) {
        switch (tier(label)) {
            case "high": return 1.0f;
            case "medium": return 0.6f;
            default: return 0.3f;
        }
    }

    // ---- how things are said ----------------------------------------------------------------------------------
    private static final Map<String, String> ENGLISH = new HashMap<>();
    static {
        ENGLISH.put("stop_sign", "stop sign");
        ENGLISH.put("e-tricycle", "electric tricycle");
        ENGLISH.put("e-bike", "electric bike");
        ENGLISH.put("e-trike", "electric trike");
        ENGLISH.put("hazard_sign", "hazard sign");
        ENGLISH.put("pit_hole", "pit hole");
        ENGLISH.put("uncovered_manhole", "uncovered manhole");
        ENGLISH.put("utility_pole", "utility pole");
    }

    private static final Map<String, String> FIL = new HashMap<>();
    static {
        FIL.put("person", "tao"); FIL.put("car", "kotse"); FIL.put("van", "van");
        FIL.put("bus", "bus"); FIL.put("truck", "trak"); FIL.put("jeepney", "jeepney");
        FIL.put("motorcycle", "motorsiklo"); FIL.put("tricycle", "traysikel");
        FIL.put("e-tricycle", "de-kuryenteng traysikel"); FIL.put("e-trike", "e-trike");
        FIL.put("e-bike", "de-kuryenteng bisikleta"); FIL.put("bike", "bisikleta");
        FIL.put("stop_sign", "karatula ng hinto"); FIL.put("stairs", "hagdan");
        FIL.put("pit_hole", "butas sa daan"); FIL.put("uncovered_manhole", "bukas na manhole");
        FIL.put("hazard_sign", "karatula ng babala"); FIL.put("utility_pole", "poste ng kuryente");
        FIL.put("bench", "bangko"); FIL.put("cat", "pusa"); FIL.put("dog", "aso");
        FIL.put("tree", "puno"); FIL.put("door", "pinto"); FIL.put("puddle", "tubig sa daan");
        FIL.put("crosswalk", "tawiran"); FIL.put("ramps", "rampa"); FIL.put("manhole", "manhole");
        FIL.put(WALL, "hadlang"); FIL.put("object", "bagay");
    }

    private static final Map<String, String> FIL_TERMS = new HashMap<>();
    static {
        FIL_TERMS.put("on your left", "sa kaliwa mo"); FIL_TERMS.put("on your right", "sa kanan mo");
        FIL_TERMS.put("straight ahead", "sa harap mo"); FIL_TERMS.put("far left", "malayong kaliwa");
        FIL_TERMS.put("left", "kaliwa"); FIL_TERMS.put("front", "harap"); FIL_TERMS.put("right", "kanan");
        FIL_TERMS.put("far right", "malayong kanan"); FIL_TERMS.put("nearby", "malapit");
        FIL_TERMS.put("very close", "napakalapit"); FIL_TERMS.put("far", "malayo");
        FIL_TERMS.put("distance unavailable", "hindi matukoy ang distansya");
        FIL_TERMS.put("meters", "metro");
        FIL_TERMS.put("step left", "humakbang pakaliwa"); FIL_TERMS.put("step right", "humakbang pakanan");
        FIL_TERMS.put("stop", "huminto"); FIL_TERMS.put("center is clear", "deretcho lang");
        FIL_TERMS.put(ADVICE_UNAVAILABLE, "hindi matukoy ang distansya; huwag umasa sa direksiyon");
    }

    /** Spoken name of a class ("stop_sign" -> "stop sign" / "karatula ng hinto"). */
    static String spokenLabel(String label, boolean fil) {
        String lower = label.toLowerCase(Locale.US);
        String plain = ENGLISH.containsKey(lower) ? ENGLISH.get(lower) : lower.replace('_', ' ').replace('-', ' ');
        if (!fil) return plain;
        String t = FIL.get(lower);
        return t != null ? t : plain;
    }

    /** Positions, distance buckets, navigation advice. English text passes through (except "front"). */
    static String term(String term, boolean fil) {
        if (fil) {
            String t = FIL_TERMS.get(term);
            return t != null ? t : term;
        }
        return "front".equals(term) ? "straight ahead" : term;
    }

    // ---- how the user says a class out loud ("pause person five minutes") -------------------------------------
    private static final Map<String, String> ALIASES = new HashMap<>();
    static {
        ALIASES.put("stop sign", "stop_sign");
        ALIASES.put("electric tricycle", "e-tricycle"); ALIASES.put("electric trike", "e-trike");
        ALIASES.put("electric bike", "e-bike"); ALIASES.put("tricycle", "tricycle");
        ALIASES.put("car", "car"); ALIASES.put("person", "person"); ALIASES.put("people", "person");
        ALIASES.put("van", "van"); ALIASES.put("bench", "bench"); ALIASES.put("bike", "bike");
        ALIASES.put("bicycle", "bike"); ALIASES.put("bus", "bus"); ALIASES.put("cat", "cat");
        ALIASES.put("crosswalk", "crosswalk"); ALIASES.put("dog", "dog"); ALIASES.put("manhole", "manhole");
        ALIASES.put("hazard sign", "hazard_sign"); ALIASES.put("jeepney", "jeepney");
        ALIASES.put("motorcycle", "motorcycle"); ALIASES.put("door", "door");
        ALIASES.put("pit hole", "pit_hole"); ALIASES.put("pothole", "pit_hole"); ALIASES.put("puddle", "puddle");
        ALIASES.put("ramp", "ramps"); ALIASES.put("ramps", "ramps"); ALIASES.put("stairs", "stairs");
        ALIASES.put("stair", "stairs"); ALIASES.put("tree", "tree"); ALIASES.put("truck", "truck");
        ALIASES.put("uncovered manhole", "uncovered_manhole");
        ALIASES.put("utility pole", "utility_pole"); ALIASES.put("pole", "utility_pole");
        ALIASES.put("wall", WALL); ALIASES.put("obstruction", WALL);
    }

    private static final List<String> ALIAS_PHRASES_LONGEST_FIRST;
    static {
        List<String> phrases = new ArrayList<>(ALIASES.keySet());
        Collections.sort(phrases, (a, b) -> b.length() != a.length() ? b.length() - a.length() : a.compareTo(b));
        ALIAS_PHRASES_LONGEST_FIRST = Collections.unmodifiableList(phrases);
    }

    /** Longest spoken alias found in the command ("uncovered manhole" beats "manhole"), or null. */
    static String findClass(String command) {
        String padded = " " + command + " ";
        for (String phrase : ALIAS_PHRASES_LONGEST_FIRST) {
            if (padded.contains(" " + phrase + " ")) return ALIASES.get(phrase);
        }
        return null;
    }

    /** Every individual word used in a class alias (for the speech recognizer's vocabulary). */
    static List<String> aliasWords() {
        List<String> words = new ArrayList<>();
        for (String phrase : ALIASES.keySet()) {
            for (String w : phrase.split(" ")) if (!words.contains(w)) words.add(w);
        }
        Collections.sort(words);
        return words;
    }
}
