package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.PreparedAudio;
import dev.starsector.preflight.core.PreparedAudioCache;
import dev.starsector.preflight.core.ResourceIndex;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
            "-Xmx256m",
            "-XX:MaxDirectMemorySize=128m",
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:-BytecodeVerificationLocal",
            "-XX:-BytecodeVerificationRemote");
    private static final long CHILD_TIMEOUT_MINUTES = 30;
    private static final int MAX_CHILD_OUTPUT_BYTES = 256 * 1024;
    static final int MAX_WORK_FILE_BYTES = 8 * 1024 * 1024;
    static final long MAX_ENCODED_FILE_BYTES = 64L * 1024 * 1024;
    static final long MAX_TOTAL_ENCODED_BYTES = 512L * 1024 * 1024;
    static final long MAX_TOTAL_PCM_BYTES = 2L * 1024 * 1024 * 1024;
    static final int MAX_SOUND_COUNT = 10_000;

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
        long workBytes = 0;
        long encodedBytes = 0;
        long predictedPcmBytes = 0;
        for (AudioCensus.Sound sound : census.sounds()) {
            if (sound.kind() != AudioCensus.Kind.EFFECT || !sound.decodable()) {
                continue;
            }
            ResourceIndex.Provider winner = index.winner(sound.logicalPath()).orElse(null);
            if (winner == null) {
                continue;
            }
            Path file;
            try {
                file = index.resolveExisting(winner);
            } catch (IOException | RuntimeException changed) {
                continue;
            }
            if (sound.encodedBytes() > MAX_ENCODED_FILE_BYTES
                    || sound.decodedBytes() > PreparedAudio.MAX_PCM_BYTES) {
                throw new IOException("Refusing to prepare oversized sound effect "
                        + sound.logicalPath());
            }
            if (work.size() >= MAX_SOUND_COUNT
                    || encodedBytes > MAX_TOTAL_ENCODED_BYTES - sound.encodedBytes()
                    || predictedPcmBytes > MAX_TOTAL_PCM_BYTES - sound.decodedBytes()) {
                throw new IOException("Refusing to prepare audio: the profile exceeds the safe "
                        + "sound count or aggregate size budget");
            }
            String item = sound.logicalPath() + "\t" + file;
            if (item.length() > MAX_WORK_FILE_BYTES) {
                throw new IOException("Refusing to prepare audio: one work item exceeds the safe work-list budget");
            }
            int itemBytes = item.getBytes(StandardCharsets.UTF_8).length + 1;
            if (workBytes > MAX_WORK_FILE_BYTES - itemBytes) {
                throw new IOException("Refusing to prepare audio: the work list exceeds the safe byte budget");
            }
            work.add(item);
            workBytes += itemBytes;
            encodedBytes += sound.encodedBytes();
            predictedPcmBytes += sound.decodedBytes();
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
        try {
            Files.write(workFile, work, StandardCharsets.UTF_8);
            System.out.printf("Preparing %,d declared sound effects (%.1f MB of encoded audio)...%n",
                    work.size(), encodedBytes / 1e6);
            System.out.println("  decoder identity: " + decoderIdentity);
            System.out.println("  cache:            " + cache);

            List<String> command = new ArrayList<>();
            command.add(javaExecutable.toString());
            command.addAll(CHILD_JVM_OPTIONS);
            command.add("-cp");
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
            ChildOutputDrain drain = new ChildOutputDrain(process.getInputStream());
            drain.start();
            boolean finished = false;
            boolean waitCompleted = false;
            try {
                finished = process.waitFor(CHILD_TIMEOUT_MINUTES, TimeUnit.MINUTES);
                waitCompleted = true;
                if (!finished) {
                    process.destroyForcibly();
                    process.waitFor();
                }
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
                // A forced close can make the drain see a broken pipe. Preserve timeout or
                // interruption as the authoritative outcome; only a normal child exit turns a
                // drain failure into the command failure.
                drain.await(waitCompleted && finished);
            }
            String childOutput = drain.text();
            if (!finished) {
                System.err.println("The decode child did not finish within "
                        + CHILD_TIMEOUT_MINUTES + " minutes.");
                return 7;
            }
            if (process.exitValue() != 0) {
                System.err.println("The decode child failed:");
                System.err.println(childOutput);
                return process.exitValue();
            }
            System.out.println(Files.readString(output).strip());
            return 0;
        } finally {
            Files.deleteIfExists(workFile);
        }
    }

    /** Continuously drains child output so the process timeout cannot be defeated by an open/full pipe. */
    private static final class ChildOutputDrain {
        private final InputStream input;
        private final ByteArrayOutputStream retained = new ByteArrayOutputStream(MAX_CHILD_OUTPUT_BYTES);
        private final AtomicReference<IOException> failure = new AtomicReference<>();
        private Thread thread;

        private ChildOutputDrain(InputStream input) {
            this.input = input;
        }

        private void start() {
            thread = new Thread(this::run, "preflight-prepared-audio-output");
            thread.setDaemon(true);
            thread.start();
        }

        private void run() {
            byte[] buffer = new byte[16 * 1024];
            try (InputStream stream = input) {
                int count;
                while ((count = stream.read(buffer)) >= 0) {
                    if (count == 0) {
                        continue;
                    }
                    int room = MAX_CHILD_OUTPUT_BYTES - retained.size();
                    if (room > 0) {
                        retained.write(buffer, 0, Math.min(room, count));
                    }
                }
            } catch (IOException error) {
                failure.set(error);
            }
        }

        private void await(boolean requireCleanDrain) throws InterruptedException, IOException {
            thread.join();
            IOException error = failure.get();
            if (requireCleanDrain && error != null) {
                throw error;
            }
        }

        private String text() {
            return retained.toString(StandardCharsets.UTF_8);
        }
    }

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
