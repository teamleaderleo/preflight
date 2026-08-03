package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MergedReadKeyTest {

    @Test
    void namesOnlyCoveredDataPathsAndEveryArgumentThatChangesTheAnswer() {
        String csv = MergedReadKey.csv(
                "data/hulls/ship_data.csv", true, false, List.of("id", "name"));
        String json = MergedReadKey.json(
                "data/config/settings.json", List.of("protected", "alsoProtected"));

        assertNotNull(csv);
        assertNotNull(json);
        assertTrue(MergedReadKey.wellFormed(csv));
        assertTrue(MergedReadKey.wellFormed(json));
        assertNotEquals(csv, MergedReadKey.csv(
                "data/hulls/ship_data.csv", false, false, List.of("id", "name")));
        assertNotEquals(csv, MergedReadKey.csv(
                "data/hulls/ship_data.csv", true, false, List.of("name", "id")));
        assertNotEquals(json, MergedReadKey.json(
                "data/config/settings.json", List.of("alsoProtected", "protected")));

        assertNull(MergedReadKey.json("graphics/settings.json", List.of()));
        assertNull(MergedReadKey.csv("ship_data.csv", true, false, List.of()));
        assertNull(MergedReadKey.json("data/config/../settings.json", List.of()));
    }

    @Test
    void keepsAbsoluteAndRelativeRequestsDistinct() {
        String relative = MergedReadKey.json("data/variants/wolf.variant", List.of());
        String absolute = MergedReadKey.json(
                "/Applications/Starsector.app/mods/example/data/variants/wolf.variant", List.of());
        String windowsAbsolute = MergedReadKey.json(
                "C:/Games/Starsector/mods/example/data/variants/wolf.variant", List.of());

        assertNotNull(relative);
        assertNotNull(absolute);
        assertNotNull(windowsAbsolute);
        assertNotEquals(relative, absolute);
        assertEquals(absolute, windowsAbsolute,
                "the runtime collision guard, not an install prefix, protects absolute keys");
        assertEquals("abs:data/variants/wolf.variant",
                MergedReadKey.path("/tmp/mod/data/variants/wolf.variant"));
    }

    @Test
    void refusesBackslashesAndUnnameableArguments() {
        assertNull(MergedReadKey.json("data\\config\\settings.json", List.of()));
        assertNull(MergedReadKey.json("C:\\Games\\Starsector\\data/config/settings.json", List.of()));
        assertNull(MergedReadKey.json("data/config/settings.json", null));
        assertNull(MergedReadKey.csv("data/hulls/ship_data.csv", true, false,
                java.util.Arrays.asList("id", null)));
        assertNull(MergedReadKey.json("data/config/settings.json", List.of("bad\u0000key")));
    }
}
