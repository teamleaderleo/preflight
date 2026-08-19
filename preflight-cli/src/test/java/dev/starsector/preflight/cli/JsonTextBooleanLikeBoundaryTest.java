package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class JsonTextBooleanLikeBoundaryTest {
    @Test
    void rejectsLoneSlashAfterBareBoolean() {
        assertThrows(
                IllegalArgumentException.class,
                () -> JsonText.booleanLike("{\"totalConversion\":true/x}", "totalConversion"));
        assertThrows(
                IllegalArgumentException.class,
                () -> JsonText.booleanLike("{\"totalConversion\":false/garbage}", "totalConversion"));
    }

    @Test
    void acceptsSupportedCommentBoundariesAfterBareBoolean() {
        assertEquals(
                Boolean.TRUE,
                JsonText.booleanLike("{\"totalConversion\":true// comment\n}", "totalConversion"));
        assertEquals(
                Boolean.FALSE,
                JsonText.booleanLike("{\"totalConversion\":false/* comment */}", "totalConversion"));
        assertEquals(
                Boolean.TRUE,
                JsonText.booleanLike("{\"totalConversion\":true# comment\n}", "totalConversion"));
    }
}
