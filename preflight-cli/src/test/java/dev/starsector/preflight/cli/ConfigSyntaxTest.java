package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ConfigSyntaxTest {
    private static ConfigSyntax.Verdict verdictOf(String text) {
        return ConfigSyntax.read(text).verdict();
    }

    // --- What the dialect has to accept -------------------------------------------------------
    //
    // Every case below is a shape observed in a mod that ships and works. A rule that fires on any
    // of them would be telling authors their working file is broken, which costs far more than the
    // checks below are worth.

    @Test
    void acceptsHashAndSlashComments() {
        assertEquals(ConfigSyntax.Verdict.WELL_FORMED, verdictOf("""
                { # a hash comment
                  "id":"x", // a slash comment
                  /* and a block */
                  "n":1
                }
                """));
    }

    @Test
    void acceptsTrailingCommas() {
        assertEquals(ConfigSyntax.Verdict.WELL_FORMED, verdictOf("""
                {"weapons":["a","b",],"n":1,}
                """));
    }

    /** 26 mods ship a tips.json keyed exactly like this. */
    @Test
    void acceptsUnquotedKeys() {
        assertEquals(ConfigSyntax.Verdict.WELL_FORMED, verdictOf("""
                {
                	tips:["one tip",]
                }
                """));
    }

    /** Observed as "duration":0.1f — a Java float literal inside config. */
    @Test
    void acceptsNumericSuffixesAndBareWords() {
        assertEquals(ConfigSyntax.Verdict.WELL_FORMED, verdictOf("""
                {"duration":0.1f,"collisionClass":PROJECTILE_FF}
                """));
    }

    /**
     * The correction that shaped this class. 27 files across MagicLib, Nexerelin, Arma Armatura and
     * others end with one brace too many, and all of them work: a reader takes one value and never
     * looks further. Flagging this would report normal practice as a defect.
     */
    @Test
    void ignoresStrayBracketsAfterTheTopLevelValue() {
        assertEquals(ConfigSyntax.Verdict.WELL_FORMED, verdictOf("""
                {"a":1}
                }
                """));
        assertEquals(ConfigSyntax.Verdict.WELL_FORMED, verdictOf("""
                {"a":1},
                # a parting comment
                ]
                """));
    }

    @Test
    void acceptsAByteOrderMark() {
        assertEquals(ConfigSyntax.Verdict.WELL_FORMED, verdictOf("﻿{\"a\":1}"));
    }

    @Test
    void acceptsBracketsAndCommentMarkersInsideStrings() {
        assertEquals(ConfigSyntax.Verdict.WELL_FORMED, verdictOf("""
                {"desc":"a } and a # and a // inside a string"}
                """));
    }

    // --- What it has to catch -----------------------------------------------------------------

    @Test
    void reportsABraceThatNeverCloses() {
        ConfigSyntax.Reading reading = ConfigSyntax.read("""
                {
                  "hull":{
                    "n":1
                }
                """);

        assertEquals(ConfigSyntax.Verdict.UNPARSEABLE, reading.verdict());
        assertEquals(1, reading.line());
        assertTrue(reading.detail().contains("never closed"), reading.detail());
    }

    @Test
    void reportsAStringThatNeverCloses() {
        ConfigSyntax.Reading reading = ConfigSyntax.read("""
                {
                  "name":"unterminated,
                  "n":1
                }
                """);

        assertEquals(ConfigSyntax.Verdict.UNPARSEABLE, reading.verdict());
        assertEquals(2, reading.line());
    }

    @Test
    void reportsMismatchedBrackets() {
        ConfigSyntax.Reading reading = ConfigSyntax.read("""
                {"weapons":["a","b"}
                """);

        assertEquals(ConfigSyntax.Verdict.UNPARSEABLE, reading.verdict());
        assertTrue(reading.detail().contains("closed by"), reading.detail());
    }

    @Test
    void reportsABlockCommentThatNeverCloses() {
        ConfigSyntax.Reading reading = ConfigSyntax.read("""
                {"a":1,
                /* still going
                """);

        assertEquals(ConfigSyntax.Verdict.UNPARSEABLE, reading.verdict());
        assertTrue(reading.detail().contains("/* comment"), reading.detail());
    }

    @Test
    void reportsAFileThatOpensWithACloser() {
        assertEquals(ConfigSyntax.Verdict.UNPARSEABLE, verdictOf("""
                }
                "diableavionics":{"x":1}
                """));
    }

    /** mayasuran_guard.json begins "0{": a stray digit a reader would take as the entire file. */
    @Test
    void reportsAStrayTokenBeforeTheOpeningBrace() {
        ConfigSyntax.Reading reading = ConfigSyntax.read("""
                0{
                    "playableFaction":false
                }
                """);

        assertEquals(ConfigSyntax.Verdict.UNPARSEABLE, reading.verdict());
        assertTrue(reading.detail().contains("rather than opening an object"), reading.detail());
    }

    @Test
    void reportsAnEmptyFile() {
        assertEquals(ConfigSyntax.Verdict.UNPARSEABLE, verdictOf("   \n # nothing here \n"));
    }

    /**
     * The shape found in expsp_beat_msl.proj: a brace closes the top-level object early and a real
     * block — there, a PROXIMITY_FUSE behaviour — sits outside it, silently never applied.
     */
    @Test
    void reportsConfigurationLeftOutsideTheTopLevelValue() {
        ConfigSyntax.Reading reading = ConfigSyntax.read("""
                {
                  "id":"missile"
                }
                	"behaviorSpec":{"behavior":"PROXIMITY_FUSE",
                					"range":60}
                """);

        assertEquals(ConfigSyntax.Verdict.TRAILING_CONTENT, reading.verdict());
        assertEquals(4, reading.line());
        assertTrue(reading.detail().contains("behaviorSpec"), reading.detail());
    }

    /** A single key past the close is the whole finding; there is no size floor to hide behind. */
    @Test
    void reportsOneStrandedKey() {
        ConfigSyntax.Reading reading = ConfigSyntax.read("""
                {"a":1},
                  "fireSoundTwo": "hurricane_mirv_fire"
                }
                """);

        assertEquals(ConfigSyntax.Verdict.TRAILING_CONTENT, reading.verdict());
        assertEquals(2, reading.line());
    }

    // --- Robustness ---------------------------------------------------------------------------

    /**
     * These files come from strangers. Depth is handled with an explicit stack precisely so a corrupt
     * one cannot end a lint run with a StackOverflowError.
     */
    @Test
    void survivesPathologicalNestingWithoutOverflowing() {
        assertEquals(ConfigSyntax.Verdict.RESOURCE_LIMIT,
                verdictOf("[".repeat(ConfigSyntax.MAX_NESTING_DEPTH + 1)));
        assertEquals(ConfigSyntax.Verdict.WELL_FORMED, verdictOf(
                "[".repeat(ConfigSyntax.MAX_NESTING_DEPTH) + "]".repeat(ConfigSyntax.MAX_NESTING_DEPTH)));
    }

    @Test
    void survivesATrailingBackslash() {
        assertEquals(ConfigSyntax.Verdict.UNPARSEABLE, verdictOf("{\"a\":\"x\\"));
    }
}
