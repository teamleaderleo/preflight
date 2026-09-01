package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SaveCommandTest {
    @TempDir
    Path temporary;

    @Test
    void plansThenAtomicallyRelocatesActiveAndBackupSaveXml() throws Exception {
        Path game = temporary.resolve("game");
        Path mod = Files.createDirectories(game.resolve("mods/AI Tweaks-2.2.10"));
        Path slot = Files.createDirectories(game.resolve("saves/save_Test_1"));
        String foreign = "/Applications/Starsector.app/Contents/Resources/Java/../../../mods/"
                + mod.getFileName();
        Path descriptor = slot.resolve("descriptor.xml");
        Path campaignBackup = slot.resolve("campaign.xml.bak");
        Files.writeString(descriptor, "<spec><path>" + foreign + "</path></spec>\n");
        Files.writeString(campaignBackup, "<spec><path>" + foreign + "</path></spec>\n");

        SaveCommand.Report plan = SaveCommand.relocate(
                game, temporary.resolve("backups"), false);
        assertEquals("planned", plan.status());
        assertEquals(2, plan.filesChanged());
        assertEquals(2, plan.pathsChanged());
        assertTrue(Files.readString(descriptor).contains(foreign));

        SaveCommand.Report applied = SaveCommand.relocate(
                game, temporary.resolve("backups"), true);
        assertEquals("applied", applied.status());
        assertNotNull(applied.backup());
        assertTrue(Files.readString(descriptor).contains(mod.toString()));
        assertTrue(Files.readString(campaignBackup).contains(mod.toString()));
        assertTrue(Files.readString(applied.backup().resolve(
                "saves/save_Test_1/descriptor.xml")).contains(foreign));

        SaveCommand.Report repeated = SaveCommand.relocate(
                game, temporary.resolve("backups"), true);
        assertEquals("unchanged", repeated.status());
        assertEquals(0, repeated.pathsChanged());
    }

    @Test
    void refusesAnUnmappedForeignModBeforeChangingAnything() throws Exception {
        Path game = temporary.resolve("missing-game");
        Files.createDirectories(game.resolve("mods"));
        Path slot = Files.createDirectories(game.resolve("saves/save_Test_1"));
        Path descriptor = slot.resolve("descriptor.xml");
        String original = "<path>/Applications/Starsector.app/mods/Missing</path>\n";
        Files.writeString(descriptor, original);

        assertThrows(Exception.class, () -> SaveCommand.relocate(
                game, temporary.resolve("backups"), true));
        assertEquals(original, Files.readString(descriptor));
    }

    @Test
    void repairsAStaleContinuePointerToTheNewestLiveSlot() throws Exception {
        Path game = temporary.resolve("continue-game");
        Files.createDirectories(game.resolve("mods"));
        Path older = Files.createDirectories(game.resolve("saves/save_Older_1"));
        Path newer = Files.createDirectories(game.resolve("saves/save_Newer_2"));
        Files.writeString(older.resolve("descriptor.xml"),
                "<slotCreationTimestamp>10</slotCreationTimestamp>");
        Files.writeString(newer.resolve("descriptor.xml"),
                "<slotCreationTimestamp>20</slotCreationTimestamp>");

        SaveCommand.ContinuePlan plan = SaveCommand.planContinue(
                game, "./saves/save_Missing_3");

        assertTrue(plan.changeRequired());
        assertEquals("./saves/save_Newer_2", plan.target());
        assertTrue(!SaveCommand.planContinue(game, plan.target()).changeRequired());
    }
}
