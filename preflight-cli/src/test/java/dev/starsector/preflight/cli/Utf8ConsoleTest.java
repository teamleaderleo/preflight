package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The answer channel has to survive a console that is not UTF-8. */
class Utf8ConsoleTest {
    /** The fixture that exposed this on a cp1252 Windows runner. */
    private static final String PATH = "Synthetic Game – path Ω";

    @Test
    void answersAreWrittenAsUtf8WhateverTheConsoleCodePageIs() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        try (PrintStream stream = Utf8Console.utf8(sink)) {
            stream.println(PATH);
        }

        assertEquals(PATH, sink.toString(StandardCharsets.UTF_8).strip());
    }

    @Test
    void aCodePageConsoleIsWhatTheReaderWouldHaveSeenInstead() {
        // Not a test of our code: it pins why the explicit charset is needed. cp1252 can represent
        // the en dash, so it survives as a byte that is not valid UTF-8 and reads back as U+FFFD.
        // It cannot represent the omega at all, which becomes '?' and is unrecoverable.
        Charset codePage = Charset.forName("windows-1252");
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        try (PrintStream stream = new PrintStream(sink, true, codePage)) {
            stream.print(PATH);
        }
        String asReadByTheDesktop = sink.toString(StandardCharsets.UTF_8);

        assertNotEquals(PATH, asReadByTheDesktop);
        assertTrue(asReadByTheDesktop.contains("�"), "the en dash arrives undecodable");
        assertTrue(asReadByTheDesktop.contains("?"), "and the omega does not arrive at all");
    }
}
