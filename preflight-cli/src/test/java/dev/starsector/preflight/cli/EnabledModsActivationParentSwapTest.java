package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Adversarial parent-generation races for the final enabled_mods publication boundary. */
final class EnabledModsActivationParentSwapTest {
    private static final String TRANSACTION_PREFIX = ".preflight-enabled-mods-txn-";

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void requireFilesystemPrimitives() throws Exception {
        Path target = Files.createDirectory(temporaryDirectory.resolve("symlink-target"));
        Path link = temporaryDirectory.resolve("symlink-probe");
        try {
            Files.createSymbolicLink(link, target);
            Files.delete(link);
        } catch (UnsupportedOperationException | IOException unavailable) {
            assumeTrue(false, "symbolic links are unavailable on this test filesystem");
        }

        Path original = temporaryDirectory.resolve("hard-link-original");
        Path copy = temporaryDirectory.resolve("hard-link-copy");
        Files.writeString(original, "probe", StandardCharsets.UTF_8);
        try {
            Files.createLink(copy, original);
            Files.delete(copy);
        } catch (UnsupportedOperationException | IOException unavailable) {
            assumeTrue(false, "hard links are unavailable on this test filesystem");
        } finally {
            Files.deleteIfExists(original);
        }
    }

    @Test
    void parentReplacementBeforeSourceCaptureCannotRedirectTheTransactionOutsideInstall() throws Exception {
        Fixture fixture = fixture();
        byte[] reviewed = Files.readAllBytes(fixture.enabled());
        byte[] outsideOriginal = reviewed.clone();
        byte[] outsideReplacement = json("outside-replacement");

        assertThrows(IOException.class, () -> EnabledModsActivationTransaction.replace(
                fixture.enabled(),
                reviewed,
                json("alpha"),
                new EnabledModsActivationTransaction.Hook() {
                    @Override
                    public void beforeSourceCapture(Path ignored) throws IOException {
                        Path transaction = transactionIn(fixture.mods());
                        Files.move(fixture.mods(), fixture.parkedMods());
                        Files.createSymbolicLink(fixture.mods(), fixture.outside());

                        Path outsideEnabled = fixture.outside().resolve("enabled_mods.json");
                        Files.write(outsideEnabled, outsideOriginal);
                        Path outsideTransaction = Files.createDirectory(
                                fixture.outside().resolve(transaction.getFileName()));
                        // Mirror the exact evidence the pathname-based transaction expects. If the
                        // parent generation is not pinned, the later capture/proof can be tricked
                        // into treating this outside inode as the reviewed public generation.
                        Files.createLink(outsideTransaction.resolve("reviewed"), outsideEnabled);
                        Files.write(outsideTransaction.resolve("snapshot"), outsideOriginal);
                        Files.write(outsideTransaction.resolve("replacement"), outsideReplacement);
                    }
                }));

        assertArrayEquals(
                outsideOriginal,
                Files.readAllBytes(fixture.outside().resolve("enabled_mods.json")),
                "activation must not publish through a replacement mods parent");
    }

    @Test
    void parentReplacementAfterCaptureCannotRedirectFinalPublicationOutsideInstall() throws Exception {
        Fixture fixture = fixture();
        byte[] reviewed = Files.readAllBytes(fixture.enabled());
        byte[] outsideReplacement = json("outside-replacement");

        assertThrows(IOException.class, () -> EnabledModsActivationTransaction.replace(
                fixture.enabled(),
                reviewed,
                json("alpha"),
                new EnabledModsActivationTransaction.Hook() {
                    @Override
                    public void beforeTargetPublication(Path ignored) throws IOException {
                        Path transaction = transactionIn(fixture.mods());
                        Files.move(fixture.mods(), fixture.parkedMods());
                        Files.createSymbolicLink(fixture.mods(), fixture.outside());

                        Path outsideTransaction = Files.createDirectory(
                                fixture.outside().resolve(transaction.getFileName()));
                        // At this point the reviewed generation was already captured in the old
                        // parent. A lexical publish will nevertheless consume this outside file.
                        Files.write(outsideTransaction.resolve("replacement"), outsideReplacement);
                    }
                }));

        assertFalse(
                Files.exists(fixture.outside().resolve("enabled_mods.json")),
                "activation must not create an enabled_mods file through a replacement mods parent");
    }

    private Fixture fixture() throws IOException {
        Path game = Files.createDirectory(temporaryDirectory.resolve("game"));
        Path mods = Files.createDirectory(game.resolve("mods"));
        Path enabled = mods.resolve("enabled_mods.json");
        Files.write(enabled, json("beta"));
        Path outside = Files.createDirectory(temporaryDirectory.resolve("outside"));
        return new Fixture(
                game,
                mods,
                enabled,
                game.resolve("mods-reviewed-generation"),
                outside);
    }

    private static Path transactionIn(Path mods) throws IOException {
        try (var entries = Files.list(mods)) {
            return entries
                    .filter(path -> path.getFileName().toString().startsWith(TRANSACTION_PREFIX))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("activation transaction was not staged"));
        }
    }

    private static byte[] json(String mod) {
        return ("{\"enabledMods\":[\"" + mod + "\"]}\n").getBytes(StandardCharsets.UTF_8);
    }

    private record Fixture(Path game, Path mods, Path enabled, Path parkedMods, Path outside) {}
}
