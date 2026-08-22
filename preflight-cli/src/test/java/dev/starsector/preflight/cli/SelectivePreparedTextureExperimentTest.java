package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.PreparedTexture;
import dev.starsector.preflight.core.PreparedTextureIO;
import dev.starsector.preflight.core.PreparedTexturePack;
import dev.starsector.preflight.core.PreparedTexturePackIO;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceIndexIO;
import dev.starsector.preflight.core.TextureManifest;
import dev.starsector.preflight.core.TextureManifestIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SelectivePreparedTextureExperimentTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void publishesOnlySelectedEntriesThenRemovesMinimalMarker() throws Exception {
        Fixture fixture = fixture();
        Path selection = temporaryDirectory.resolve("selection.paths");
        Files.writeString(
                selection,
                "# startup trace\n"
                        + "01".repeat(32)
                        + "\t12\t5\tblobs/first-identity.spft\tgraphics/first.jpg\t/tmp/first.jpg\n");

        SelectivePreparedTextureExperiment.Report report =
                SelectivePreparedTextureExperiment.build(
                        fixture.sourceCache(), fixture.profile(), selection, fixture.outputCache());

        assertEquals(1, report.selectedEntries());
        assertEquals(1, report.selectedBlobs());
        assertFalse(Files.exists(MinimalPreparationMarker.path(
                fixture.outputCache(), fixture.profile())));
        TextureManifest manifest = TextureManifestIO.read(report.manifest());
        assertEquals(List.of("graphics/first.jpg"), List.copyOf(manifest.entries().keySet()));
        assertTrue(manifest.entry("graphics/second.jpg").isEmpty());
        try (PreparedTexturePack pack = PreparedTexturePackIO.open(
                report.pack(), fixture.profile(), List.of(fixture.firstBlob()))) {
            assertEquals(12, pack.readTrusted(fixture.firstBlob()).pixelBytes());
        }
    }

    @Test
    void unknownSelectionLeavesMinimalModeActive() throws Exception {
        Fixture fixture = fixture();
        Path selection = temporaryDirectory.resolve("unknown.paths");
        Files.writeString(selection, "graphics/absent.jpg\n");

        assertThrows(
                IllegalArgumentException.class,
                () -> SelectivePreparedTextureExperiment.build(
                        fixture.sourceCache(), fixture.profile(), selection, fixture.outputCache()));
        assertTrue(Files.isRegularFile(MinimalPreparationMarker.path(
                fixture.outputCache(), fixture.profile())));
        assertFalse(Files.exists(TextureManifestIO.directory(fixture.outputCache())
                .resolve(fixture.profile() + ".spfm")));
    }

    private Fixture fixture() throws Exception {
        String profile = "ab".repeat(32);
        Path source = temporaryDirectory.resolve("source-cache");
        Path output = temporaryDirectory.resolve("output-cache");
        Path root = temporaryDirectory.resolve("resources");
        Files.createDirectories(source);
        Files.createDirectories(output);
        Files.createDirectories(root.resolve("graphics"));
        Files.write(root.resolve("graphics/first.jpg"), new byte[] {1});
        Files.write(root.resolve("graphics/second.jpg"), new byte[] {2});

        ResourceIndex index = new ResourceIndex(
                profile,
                List.of(new ResourceIndex.Root("core", root, true)),
                Map.of(
                        "graphics/first.jpg",
                        List.of(new ResourceIndex.Provider(0, "graphics/first.jpg", 1, 0)),
                        "graphics/second.jpg",
                        List.of(new ResourceIndex.Provider(0, "graphics/second.jpg", 1, 0))));
        ResourceIndexIO.write(
                ResourceIndexIO.directory(source).resolve(profile + ".spfi"), index);

        String firstBlob = "blobs/first-identity.spft";
        String secondBlob = "blobs/second-identity-lz4.spft";
        PreparedTexture first = texture("01".repeat(32), (byte) 1);
        PreparedTexture second = texture("02".repeat(32), (byte) 2);
        PreparedTextureIO.write(
                source.resolve(firstBlob), first, PreparedTextureIO.StorageCodec.RAW);
        PreparedTextureIO.write(
                source.resolve(secondBlob), second, PreparedTextureIO.StorageCodec.LZ4);
        LinkedHashMap<String, TextureManifest.Entry> entries = new LinkedHashMap<>();
        entries.put("graphics/first.jpg", entry(first, firstBlob));
        entries.put("graphics/second.jpg", entry(second, secondBlob));
        TextureManifestIO.write(
                TextureManifestIO.directory(source).resolve(profile + ".spfm"),
                new TextureManifest(profile, entries));
        PreparedTexturePackIO.write(
                PreparedTexturePackIO.path(source, profile),
                profile,
                source,
                List.of(firstBlob, secondBlob));
        MinimalPreparationMarker.write(output, profile);
        return new Fixture(profile, source, output, firstBlob);
    }

    private static PreparedTexture texture(String hash, byte fill) {
        byte[] pixels = new byte[12];
        java.util.Arrays.fill(pixels, fill);
        return new PreparedTexture(
                hash,
                PreparedTexture.Transformation.IDENTITY,
                2,
                2,
                2,
                2,
                3,
                0,
                0,
                0,
                pixels);
    }

    private static TextureManifest.Entry entry(PreparedTexture texture, String blob) {
        return new TextureManifest.Entry(
                texture.sourceSha256(),
                texture.transformation(),
                blob,
                texture.uploadWidth(),
                texture.uploadHeight(),
                texture.channels(),
                texture.pixelBytes());
    }

    private record Fixture(String profile, Path sourceCache, Path outputCache, String firstBlob) {
    }
}
