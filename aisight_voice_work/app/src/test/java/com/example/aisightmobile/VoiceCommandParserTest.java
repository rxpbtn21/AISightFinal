package com.example.aisightmobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.aisightmobile.VoiceCommandParser.Command;
import com.example.aisightmobile.VoiceCommandParser.Type;
import org.junit.Test;

public class VoiceCommandParserTest {

    private static Command say(String heard) {
        String after = VoiceCommandParser.afterWake(heard);
        assertTrue("wake word not found in: " + heard, after != null);
        return VoiceCommandParser.parse(after, false);
    }

    @Test public void wakeWordVariants() {
        assertEquals("stop", VoiceCommandParser.afterWake("eyesight stop"));
        assertEquals("stop", VoiceCommandParser.afterWake("AISight, stop!"));
        assertEquals("record ten seconds", VoiceCommandParser.afterWake("hey eye sight record ten seconds"));
        assertEquals("read", VoiceCommandParser.afterWake("a i sight read"));
        assertEquals("read", VoiceCommandParser.afterWake("ai sight read"));
        assertEquals("", VoiceCommandParser.afterWake("aisight"));
        assertNull(VoiceCommandParser.afterWake("good morning everyone"));
    }

    @Test public void wakeWordMustBeAWholeWord() {
        // the old code used indexOf, so "disight" or "eyesights" could wake it up
        assertNull(VoiceCommandParser.afterWake("disight stop"));
        assertNull(VoiceCommandParser.afterWake("eyesights stop"));
    }

    @Test public void wakeOnly() {
        assertEquals(Type.WAKE_ONLY, VoiceCommandParser.parse("", false).type);
    }

    @Test public void basicCommands() {
        assertEquals(Type.START, say("eyesight start").type);
        assertEquals(Type.START, say("eyesight turn on").type);
        assertEquals(Type.START, say("eyesight launch").type);
        assertEquals(Type.QUIET, say("eyesight stop").type);
        assertEquals(Type.QUIET, say("eyesight shut up").type);
        assertEquals(Type.QUIET, say("eyesight pause").type);
        assertEquals(Type.READ_TEXT, say("eyesight read text").type);
        assertEquals(Type.BATTERY, say("eyesight battery").type);
        assertEquals(Type.REPEAT, say("eyesight say again").type);
        assertEquals(Type.HELP, say("eyesight help").type);
        assertEquals(Type.SHUTDOWN, say("eyesight shut down").type);
        assertEquals(Type.SHUTDOWN, say("eyesight turn off").type);
    }

    @Test public void alarmIsNotConfusedWithMute() {
        assertEquals(Type.ALARM_STOP, say("eyesight buzzer stop").type);
        assertEquals(Type.ALARM_STOP, say("eyesight silence alarm").type);
        assertEquals(Type.ALARM_STOP, say("eyesight alarm off").type);   // would be SHUTDOWN if "turn off" matched loosely
    }

    @Test public void settings() {
        assertEquals("fil", say("eyesight switch to tagalog").arg);
        assertEquals("fil", say("eyesight filipino").arg);
        assertEquals("en", say("eyesight switch back to english").arg);
        assertEquals("navigation", say("eyesight navigation mode").arg);
        assertEquals("advanced", say("eyesight advance").arg);
        assertEquals("default", say("eyesight default").arg);
        assertEquals("female", say("eyesight female").arg);
        assertEquals("male", say("eyesight male").arg);   // "female" contains "male": whole-word matching must not mix them up
        assertEquals("fast", say("eyesight fast").arg);
        assertEquals("high", say("eyesight volume high").arg);
        assertEquals("normal", say("eyesight volume normal").arg);
        assertEquals(Type.SPEED, say("eyesight normal").type);
        Command c = say("eyesight object detection two");
        assertEquals(Type.MAX_OBJECTS, c.type);
        assertEquals(2, c.n);
    }

    @Test public void wearableMode() {
        assertEquals("on", say("eyesight wearable mode").arg);
        assertEquals("on", say("eyesight hide screen").arg);
        assertEquals("off", say("eyesight show screen").arg);
        assertEquals("off", say("eyesight wearable off").arg);
    }

    @Test public void recording() {
        assertEquals(10, say("eyesight record").n);
        assertEquals(5, say("eyesight record five seconds").n);
        assertEquals(30, say("eyesight record thirty seconds").n);
        assertEquals(60, say("eyesight record sixty seconds").n);
        assertEquals(60, say("eyesight record one minute").n);
        assertEquals(5, say("eyesight record for five seconds").n);   // "for" is often heard as "four"
        Command capped = say("eyesight record five minutes");
        assertEquals(60, capped.n);
        assertTrue(capped.flag);
        assertEquals(Type.RECORD_STOP, say("eyesight stop recording").type);   // must NOT mute everything
    }

    @Test public void pausingAClassDoesNotMuteEverything() {
        Command c = say("eyesight pause person five minutes");
        assertEquals(Type.PAUSE_CLASS, c.type);
        assertEquals("person", c.arg);
        assertEquals(5, c.n);
        assertEquals(1, say("eyesight pause jeepney").n);                      // default minutes
        assertEquals(5, say("eyesight pause person for five minutes").n);      // for/four
        assertEquals("stop_sign", say("eyesight pause stop sign two minutes").arg);
        assertEquals("uncovered_manhole", say("eyesight pause uncovered manhole").arg);
        assertEquals("manhole", say("eyesight pause manhole").arg);
        assertEquals("e-bike", say("eyesight pause electric bike").arg);
        assertEquals("bike", say("eyesight pause bike").arg);
        assertEquals(Lexicon.WALL, say("eyesight pause wall three minutes").arg);
        assertEquals(Type.QUIET, say("eyesight pause").type);                  // no class: global mute
        Command r = say("eyesight resume person");
        assertEquals(Type.RESUME_CLASS, r.type);
        assertEquals("person", r.arg);
    }

    @Test public void confirmationState() {
        assertEquals(Type.YES, VoiceCommandParser.parse("yes", true).type);
        assertEquals(Type.NO, VoiceCommandParser.parse("no", true).type);
        assertEquals(Type.UNKNOWN, VoiceCommandParser.parse("banana", true).type);
    }

    @Test public void grammarHasEveryWordTheParserNeeds() {
        String json = VoiceCommandParser.grammarJson();
        for (String w : new String[]{"eyesight", "record", "sixty", "jeepney", "uncovered", "manhole", "wearable", "[unk]"}) {
            assertTrue("missing from recognizer vocabulary: " + w, json.contains("\"" + w + "\""));
        }
        assertTrue(json.startsWith("[") && json.endsWith("]"));
    }
}
