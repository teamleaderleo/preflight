package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.PreparedAudioCache;
import dev.starsector.preflight.core.ResourceIndex;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Bakes the profile's sound effects into prepared PCM, so a launch does not have to decode them.
 *
 * <p>Only declared effects are candidates. Music is streamed rather than fully decoded, and an
 * unreferenced file is never loaded at all, so neither is worth preparing --
 * {@link AudioCensus} already draws that line and this follows it.
 *
 * <p>The decode itself happens in a child process on the installation's own Java with the
 * installation's own jars, because the blobs have to be what <em>that</em> decoder produces. The
 * decoder's identity is hashed into every blob's cache key, so a game update that changes the
 * decoder cannot be served last week's audio: the key simply stops matching and every sound falls
 * through to a real decode.
 */
public final class PrepareAudioCommand {
    private static final String KEY_SCHEMA = "starsector-preflight-audio-decoder-policy-v1";
    /**
     * Verification off, because the game's own classes cannot pass it: obfuscation gave them names
     * like {@code sound.int} and {@code sound.while}, which are illegal identifiers. The game's
     * launcher disables it for the same reason. Shared with the installed test so the two cannot
     * drift into spawning differently configured children.
     */
    static final List<String> CHILD_JVM_OPTIONS = List.of(
            "-noverify",
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:-BytecodeVerificationLocal",
            "-XX:-BytecodeVerificationRemote");
    private static final long CHILD_TIMEOUT_MINUTES = 30;
    private static final int MAX_CHILD_OUTPUT_BYTES = 256 * 1024;

    private PrepareAudioCommand() {
    }

    static int execute(String[] args, int from) throws Exception {
        Path game = null;
        Path cache = PreflightHome.current().cache();
        Path output = null;
        Path java = null;
        for (int i = from; i < args.length; i++) {
            switch (args[i]) {
                case "--game" -> game = Path.of(args[++i]);
                case "--cache" -> cache = Path.of(args[++i]);
                case "--output" -> output = Path.of(args[++i]);
                case "--java" -> java = Path.of(args[++i]);
                default -> {
                    System.err.println("Unknown option: " + args[i]);
                    return 2;
                }
            }
        }
        Path install = InstallRoot.resolve(game);
        cache = cache.toAbsolutePath().normalize();
        if (output == null) {
            output = PreparedAudioCache.root(cache).resolve("bake.json");
        }

        ResourceIndexBuilder.BuildResult built = ResourceIndexBuilder.build(install);
        ResourceIndex index = built.index();
        AudioCensus.Result census = AudioCensus.scan(install, index, new ArrayList<>(built.diagnostics()));

        List<String> work = new ArrayList<>();
        long encodedBytes = 0;
        for (AudioCensus.Sound sound : census.sounds()) {
            if (sound.kind() != AudioCensus.Kind.EFFECT || !sound.decodable()) {
                continue;
            }
            Optional<Path> file = index.winningFile(sound.logicalPath());
            if (file.isEmpty()) {
                continue;
            }
            work.add(sound.logicalPath() + "\t" + file.get());
            encodedBytes += sound.encodedBytes();
        }
        if (work.isEmpty()) {
            System.out.println("No declared sound effects to prepare.");
            return 0;
        }

        Path javaExecutable = java != null
                ? java
                : SoundWrapperObservationRuntimeLauncher.selectJava(install, null).executable();
        List<Path> gameJars = jars(install);
        String decoderIdentity = decoderPolicyIdentity(gameJars);
        String gameBuildIdentity = starsectorBuildIdentity(gameJars);
        Path manifest = PreparedAudioCache.manifestDirectory(cache)
                .resolve(index.profileFingerprint() + ".spam");

        Path workFile = Files.createTempFile("preflight-prepared-audio", ".tsv");
        Files.writeString(workFile, String.join("\n", work), StandardCharsets.UTF_8);
        System.out.printf("Preparing %,d declared sound effects (%.1f MB of encoded audio)...%n",
                work.size(), encodedBytes / 1e6);
        System.out.println("  decoder identity: " + decoderIdentity);
        System.out.println("  cache:            " + cache);

        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.addAll(CHILD_JVM_OPTIONS);
        command.add("-cp");
        // Preflight's jar alone, staged where the launcher can read it. The game's jars follow as
        // arguments instead: the launcher consumes -cp itself, before any Preflight code exists to
        // decode it, and Windows converts that value to the system code page on the way in. A path
        // outside the page arrives as question marks and the class simply is not found. Arguments
        // can be carried as Base64; a class path cannot, so nothing that might need it goes there.
        command.add(AgentJarStaging.readableByTheChildJvm(SelfJar.locate()).toString());
        command.add(PrepareAudioChild.class.getName());
        List<String> childArguments = new ArrayList<>(List.of(
                workFile.toString(),
                cache.toString(),
                decoderIdentity,
                output.toAbsolutePath().normalize().toString(),
                index.profileFingerprint(),
                gameBuildIdentity,
                manifest.toAbsolutePath().normalize().toString()));
        for (Path jar : gameJars) {
            childArguments.add(jar.toAbsolutePath().normalize().toString());
        }
        command.addAll(List.of(Utf8Argv.encode(childArguments.toArray(new String[0]))));

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String childOutput;
        try (InputStream stream = process.getInputStream()) {
            childOutput = new String(stream.readNBytes(MAX_CHILD_OUTPUT_BYTES), StandardCharsets.UTF_8);
        }
        boolean finished = process.waitFor(CHILD_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            System.err.println("The decode child did not finish within "
                    + CHILD_TIMEOUT_MINUTES + " minutes.");
            return 7;
        }
        Files.deleteIfExists(workFile);
        if (process.exitValue() != 0) {
            System.err.println("The decode child failed:");
            System.err.println(childOutput);
            return process.exitValue();
        }
        System.out.println(Files.readString(output).strip());
        return 0;
    }

    /**
     * What a blob has to have been baked by to be served.
     *
     * <p>Every jar that contributes to the decode, hashed in a fixed order. Change any of them and
     * the key changes, so prepared audio from before the change is simply never found.
     */
    static String decoderPolicyIdentity(List<Path> gameJars) throws IOException {
        StringBuilder canonical = new StringBuilder(KEY_SCHEMA).append('\n');
        for (Path jar : gameJars) {
            String name = jar.getFileName().toString();
            if (name.equals("fs.sound_obf.jar") || name.startsWith("jorbis")
                    || name.startsWith("jogg") || name.equals("lwjgl.jar")) {
                canonical.append(name).append('=').append(Hashes.sha256(jar)).append('\n');
            }
        }
        return Hashes.sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String starsectorBuildIdentity(List<Path> gameJars) throws IOException {
        for (Path jar : gameJars) {
            if (jar.getFileName().toString().equals("starfarer_obf.jar")) {
                return Hashes.sha256(jar);
            }
        }
        throw new IOException("Could not find starfarer_obf.jar in the installation jar set");
    }

    static List<Path> jars(Path install) throws IOException {
        Path javaDirectory = install.resolve("Contents/Resources/Java");
        if (!Files.isDirectory(javaDirectory)) {
            javaDirectory = install.resolve("starsector-core");
        }
        if (!Files.isDirectory(javaDirectory)) {
            throw new IOException("Could not find the installation's jar directory under " + install);
        }
        try (Stream<Path> entries = Files.list(javaDirectory)) {
            return entries.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }
}
