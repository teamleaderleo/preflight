package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class SoundsConfigTest {
    @Test
    void separatesMusicEntriesFromEffectEntries() {
        SoundsConfig.Declaration declaration = SoundsConfig.parse("""
                {
                  "music":{
                    "music_title":[
                      {"file":"sounds/music/title.ogg","volume":0.6}
                    ]
                  },
                  "weapon_fire":[
                    {"file":"sounds/sfx_wpn/laser.ogg","pitch":1,"volume":0.4}
                  ]
                }
                """);

        assertEquals(Set.of("sounds/music/title.ogg"), declaration.musicFiles());
        assertEquals(Set.of("sounds/sfx_wpn/laser.ogg"), declaration.effectFiles());
        assertTrue(declaration.musicSources().isEmpty());
    }

    /**
     * The reviewed profile declares almost all of its music this way. Treating {@code file} as a
     * path here would classify 141 music files as unreferenced and leave 2.8 GB of decoded audio
     * looking eligible for a cache that must never hold it.
     */
    @Test
    void recordsTheSourceWhenAnEntryDrawsFromAnArchiveOrDirectory() {
        SoundsConfig.Declaration declaration = SoundsConfig.parse("""
                {
                  "music":{
                    "music_campaign":[
                      {"file":"corvus_campaign.ogg","source":"sounds/music/music.bin","volume":1},
                      {"file":"faction_theme.ogg","source":"sounds/wh/music/","volume":1}
                    ]
                  }
                }
                """);

        assertEquals(Set.of("sounds/music/music.bin", "sounds/wh/music"), declaration.musicSources());
        assertTrue(declaration.musicFiles().isEmpty(), "a sourced file names an archive member, not a path");
    }

    /** A commented-out entry is not loaded by the game, so collecting it would overstate the profile. */
    @Test
    void ignoresCommentedOutEntries() {
        SoundsConfig.Declaration declaration = SoundsConfig.parse("""
                {
                  "mote_launch":[
                    #{"file":"sounds/sfx_systems/disabled.ogg","volume":1},
                    {"file":"sounds/sfx_systems/live.ogg","volume":1},
                    //{"file":"sounds/sfx_systems/also_disabled.ogg","volume":1}
                  ]
                }
                """);

        assertEquals(Set.of("sounds/sfx_systems/live.ogg"), declaration.effectFiles());
    }

    @Test
    void keepsAHashThatIsInsideAStringValue() {
        SoundsConfig.Declaration declaration = SoundsConfig.parse("""
                {
                  "odd":[
                    {"file":"sounds/sfx/track #3.ogg","volume":1}
                  ]
                }
                """);

        assertEquals(Set.of("sounds/sfx/track #3.ogg"), declaration.effectFiles());
    }

    /** Core {@code sounds.json} wraps some effects in a {@code "sounds"} array inside an object. */
    @Test
    void readsEntriesNestedInsideASoundsWrapper() {
        SoundsConfig.Declaration declaration = SoundsConfig.parse("""
                {
                  "music_victory_theme":{
                    "sounds":[
                      {"file":"sounds/sfx/victory.ogg","pitch":1,"volume":1}
                    ]
                  }
                }
                """);

        assertEquals(Set.of("sounds/sfx/victory.ogg"), declaration.effectFiles());
    }

    /**
     * An id may merely contain "music" — only the top-level {@code "music"} object marks streamed
     * audio. Getting this wrong silently moves effects out of the eligible set.
     */
    @Test
    void doesNotTreatAnIdContainingMusicAsTheMusicObject() {
        SoundsConfig.Declaration declaration = SoundsConfig.parse("""
                {
                  "music_defeat_theme":{
                    "sounds":[
                      {"file":"sounds/sfx/defeat.ogg","volume":1}
                    ]
                  },
                  "music":{
                    "music_title":[
                      {"file":"sounds/music/title.ogg","volume":1}
                    ]
                  }
                }
                """);

        assertEquals(Set.of("sounds/sfx/defeat.ogg"), declaration.effectFiles());
        assertEquals(Set.of("sounds/music/title.ogg"), declaration.musicFiles());
    }

    @Test
    void normalizesSeparatorsAndCase() {
        SoundsConfig.Declaration declaration = SoundsConfig.parse("""
                {
                  "x":[
                    {"file":"Sounds\\\\SFX\\\\Boom.OGG","volume":1}
                  ]
                }
                """);

        assertEquals(Set.of("sounds/sfx/boom.ogg"), declaration.effectFiles());
    }

    @Test
    void toleratesTrailingCommasAndMissingMusicObject() {
        SoundsConfig.Declaration declaration = SoundsConfig.parse("""
                {
                  "a":[
                    {"file":"sounds/a.ogg","volume":1},
                  ],
                }
                """);

        assertEquals(Set.of("sounds/a.ogg"), declaration.effectFiles());
        assertTrue(declaration.musicFiles().isEmpty());
    }

    @Test
    void emptyAndBlankInputProduceNoDeclarations() {
        assertTrue(SoundsConfig.parse(null).isEmpty());
        assertTrue(SoundsConfig.parse("   ").isEmpty());
        assertFalse(SoundsConfig.parse("{\"a\":[{\"file\":\"sounds/a.ogg\"}]}").isEmpty());
    }
}
