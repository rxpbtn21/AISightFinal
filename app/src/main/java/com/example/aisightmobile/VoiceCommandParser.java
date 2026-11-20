package com.example.aisightmobile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns what the speech recognizer heard ("eyesight pause person five minutes") into a Command.
 * Matches whole words only (the old code used substring checks). Pure Java.
 */
final class VoiceCommandParser {
    private VoiceCommandParser() {}

    enum Type {
        WAKE_ONLY, UNKNOWN, START, QUIET, SHUTDOWN, YES, NO, ALARM_STOP, RECORD, RECORD_STOP, READ_TEXT,
        PAUSE_CLASS, RESUME_CLASS, BATTERY, LANGUAGE, MODE, GENDER, SPEED, VOLUME, MAX_OBJECTS,
        REPEAT, WEARABLE, HELP
    }

    static final class Command {
        final Type type;
        final String arg;     // class label / mode / "fil" / "female" ...
        final int n;          // minutes / seconds / object count
        final boolean flag;   // RECORD: the request was longer than the limit and got capped

        Command(Type type, String arg, int n, boolean flag) {
            this.type = type; this.arg = arg; this.n = n; this.flag = flag;
        }
        static Command of(Type type) { return new Command(type, "", 0, false); }
        static Command of(Type type, String arg) { return new Command(type, arg, 0, false); }
        static Command of(Type type, int n) { return new Command(type, "", n, false); }

        @Override public String toString() { return type + (arg.isEmpty() ? "" : "(" + arg + ")") + (n != 0 ? "#" + n : ""); }
    }

    private static final String[] NUMBERS = {"one", "two", "three", "four", "five"};

    // ---- wake word --------------------------------------------------------------------------------------------
    static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.US).replaceAll("[^a-z0-9 ]+", " ").replaceAll("\\s+", " ").trim();
    }

    /**
     * The part of the sentence after the wake word, or null if the wake word wasn't said.
     * Accepts "eyesight", "aisight", "eye sight", "ai sight", "a i sight".
     */
    static String afterWake(String heard) {
        String[] t = normalize(heard).split(" ");
        for (int i = 0; i < t.length; i++) {
            String next = i + 1 < t.length ? t[i + 1] : "";
            if (t[i].equals("eyesight") || t[i].equals("aisight")) return join(t, i + 1);
            if (t[i].equals("eye") && next.equals("sight")) return join(t, i + 2);
            if ((t[i].equals("ai") || t[i].equals("i")) && next.equals("sight")) return join(t, i + 2);
        }
        return null;
    }

    private static String join(String[] t, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < t.length; i++) {
            if (t[i].isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(t[i]);
        }
        return sb.toString();
    }

    // ---- numbers ----------------------------------------------------------------------------------------------
    private static int numberWord(String tok) {
        for (int i = 0; i < NUMBERS.length; i++) if (NUMBERS[i].equals(tok)) return i + 1;
        return -1;
    }

    /** Minutes for "pause person five minutes". LAST number wins: "for five" is often heard as "four five". */
    static int parsePauseMinutes(String cmd) {
        int minutes = -1;
        for (String tok : cmd.split(" ")) {
            int n = numberWord(tok);
            if (n > 0) minutes = n;
        }
        if (minutes < 0) return Tuning.PAUSE_DEFAULT_MIN;
        return Math.max(1, Math.min(Tuning.PAUSE_MAX_MIN, minutes));
    }

    /** {seconds, cappedFlag}. Supports five / ten / thirty / sixty seconds and "one minute". */
    static int[] parseRecordSeconds(String cmd) {
        String[] toks = cmd.split(" ");
        List<String> list = Arrays.asList(toks);
        int wanted;
        if (list.contains("minute") || list.contains("minutes")) {
            int n = 1;
            for (String tok : toks) {
                int v = numberWord(tok);
                if (v > 0) n = v;
            }
            wanted = n * 60;
        } else {
            wanted = Tuning.RECORD_DEFAULT_S;
            for (String tok : toks) {
                switch (tok) {
                    case "five": wanted = 5; break;
                    case "ten": wanted = 10; break;
                    case "thirty": wanted = 30; break;
                    case "sixty": wanted = 60; break;
                    default: break;
                }
            }
        }
        return new int[]{Math.min(wanted, Tuning.RECORD_MAX_S), wanted > Tuning.RECORD_MAX_S ? 1 : 0};
    }

    // ---- the parser -------------------------------------------------------------------------------------------
    private static boolean any(Set<String> w, String... words) {
        for (String s : words) if (w.contains(s)) return true;
        return false;
    }

    /** @param cmd text AFTER the wake word. */
    static Command parse(String cmd, boolean awaitingConfirmation) {
        String c = normalize(cmd);
        if (c.isEmpty()) return Command.of(Type.WAKE_ONLY);
        Set<String> w = new HashSet<>(Arrays.asList(c.split(" ")));

        if (awaitingConfirmation) {
            if (any(w, "yes", "sure")) return Command.of(Type.YES);
            if (any(w, "no", "cancel")) return Command.of(Type.NO);
            return Command.of(Type.UNKNOWN);
        }

        if (any(w, "buzzer", "alarm")) {
            return any(w, "stop", "off", "silence", "quiet", "shut") ? Command.of(Type.ALARM_STOP) : Command.of(Type.UNKNOWN);
        }

        if (any(w, "record", "recording")) {
            if (w.contains("stop")) return Command.of(Type.RECORD_STOP);
            int[] r = parseRecordSeconds(c);
            return new Command(Type.RECORD, "", r[0], r[1] == 1);
        }

        if (any(w, "read", "text", "ocr")) return Command.of(Type.READ_TEXT);

        String label = Lexicon.findClass(c);
        if (label != null) {
            if (w.contains("pause")) return new Command(Type.PAUSE_CLASS, label, parsePauseMinutes(c), false);
            if (any(w, "resume", "continue")) return Command.of(Type.RESUME_CLASS, label);
        }

        if ((w.contains("shut") && w.contains("down")) || w.contains("shutdown")
                || (w.contains("turn") && w.contains("off")) || w.contains("close")) {
            return Command.of(Type.SHUTDOWN);
        }

        if (w.contains("battery")) return Command.of(Type.BATTERY);

        if (any(w, "tagalog", "filipino")) return Command.of(Type.LANGUAGE, "fil");
        if (w.contains("english")) return Command.of(Type.LANGUAGE, "en");

        if (any(w, "navigation", "navigate")) return Command.of(Type.MODE, "navigation");
        if (any(w, "advance", "advanced")) return Command.of(Type.MODE, "advanced");
        if (any(w, "default", "basic")) return Command.of(Type.MODE, "default");

        if (w.contains("female")) return Command.of(Type.GENDER, "female");
        if (w.contains("male")) return Command.of(Type.GENDER, "male");

        if (w.contains("fast")) return Command.of(Type.SPEED, "fast");
        if (w.contains("slow")) return Command.of(Type.SPEED, "slow");
        if (w.contains("normal") && !w.contains("volume")) return Command.of(Type.SPEED, "normal");

        if (w.contains("volume")) {
            if (w.contains("high")) return Command.of(Type.VOLUME, "high");
            if (w.contains("low")) return Command.of(Type.VOLUME, "low");
            return Command.of(Type.VOLUME, "normal");
        }

        if (any(w, "object", "objects", "detection", "detections")) {
            for (String tok : c.split(" ")) {
                int v = numberWord(tok);
                if (v >= 1 && v <= 3) return Command.of(Type.MAX_OBJECTS, v);
            }
            return Command.of(Type.UNKNOWN);
        }

        if (any(w, "repeat", "again")) return Command.of(Type.REPEAT);
        if (any(w, "help", "commands")) return Command.of(Type.HELP);
        if (w.contains("show")) return Command.of(Type.WEARABLE, "off");
        if (w.contains("wearable")) return Command.of(Type.WEARABLE, w.contains("off") ? "off" : "on");
        if (w.contains("hide")) return Command.of(Type.WEARABLE, "on");

        if (any(w, "stop", "pause", "quiet") || (w.contains("shut") && w.contains("up"))) return Command.of(Type.QUIET);
        if (any(w, "start", "begin", "launch", "go", "continue", "resume") || (w.contains("turn") && w.contains("on"))) {
            return Command.of(Type.START);
        }
        return Command.of(Type.UNKNOWN);
    }

    // ---- recognizer vocabulary ----------------------------------------------------------------------------------
    private static final String[] BASE_WORDS = {
            "eyesight", "aisight", "a", "i", "ai", "eye", "sight", "hey",
            "yes", "no", "sure", "cancel",
            "buzzer", "alarm", "silence", "quiet", "off", "on", "stop", "shut", "up", "down", "turn", "close",
            "record", "recording", "second", "seconds", "minute", "minutes",
            "one", "two", "three", "four", "five", "ten", "thirty", "sixty",
            "read", "text", "ocr", "pause", "resume", "continue",
            "battery", "reset", "tagalog", "filipino", "english", "language",
            "navigation", "navigate", "advance", "advanced", "default", "basic", "mode", "switch", "to", "back",
            "male", "female", "voice", "fast", "slow", "normal", "speed", "volume", "high", "low", "medium",
            "object", "objects", "detection", "detections",
            "start", "begin", "launch", "go", "repeat", "again", "help", "commands", "wearable", "screen", "show", "hide"};

    /** Words the recognizer is allowed to output (a small closed vocabulary is far more accurate). */
    static List<String> grammarWords() {
        List<String> words = new ArrayList<>(Arrays.asList(BASE_WORDS));
        for (String w : Lexicon.aliasWords()) if (!words.contains(w)) words.add(w);
        words.add("[unk]");
        return words;
    }

    static String grammarJson() {
        StringBuilder sb = new StringBuilder("[");
        List<String> words = grammarWords();
        for (int i = 0; i < words.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(words.get(i)).append('"');
        }
        return sb.append(']').toString();
    }
}
