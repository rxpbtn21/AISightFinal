package com.example.aisightmobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LexiconTest {
    @Test public void everyModelClassHasATierAndAFilipinoName() {
        assertEquals(27, Lexicon.MODEL_LABELS.length);
        for (String label : Lexicon.MODEL_LABELS) {
            assertTrue("no tier for " + label, Lexicon.isKnown(label));
            String fil = Lexicon.spokenLabel(label, true);
            assertTrue("no Filipino name for " + label, fil.length() > 0);
            assertTrue("spoken name must not contain _ or -: " + label, !Lexicon.spokenLabel(label, false).matches(".*[_-].*"));
        }
    }

    @Test public void dangerousThingsOutrankFurniture() {
        assertTrue(Lexicon.weight("stairs") > Lexicon.weight("bench"));
        assertTrue(Lexicon.weight("bench") > Lexicon.weight("manhole"));
        assertTrue(Lexicon.weight("uncovered_manhole") > Lexicon.weight("manhole"));
    }

    @Test public void longestAliasWins() {
        assertEquals("uncovered_manhole", Lexicon.findClass("pause uncovered manhole"));
        assertEquals("manhole", Lexicon.findClass("pause manhole"));
        assertEquals("e-trike", Lexicon.findClass("pause electric trike"));
        assertEquals(null, Lexicon.findClass("pause everything"));
    }

    @Test public void termsTranslate() {
        assertEquals("straight ahead", Lexicon.term("front", false));
        assertEquals("harap", Lexicon.term("front", true));
        assertEquals("humakbang pakanan", Lexicon.term("step right", true));
        assertEquals("step right", Lexicon.term("step right", false));
    }
}
