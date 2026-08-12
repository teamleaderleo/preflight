package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Hashes;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A path to the agent JAR that the child JVM can still read.
 *
 * <p>Every path Preflight hands the agent travels as Base64 UTF-8, which no code page can damage.
 * The agent JAR's own path is the one exception, because the JVM reads it out of
 * {@code JAVA_TOOL_OPTIONS} itself, before any Preflight code exists to decode it. HotSpot reads
 * that variable through the narrow {@code getenv}, so Windows converts the value to the active ANSI
 * code page and replaces every character the page cannot represent with {@code '?'}. The JVM then
 * looks for a JAR at a path that does not exist.
 *
 * <p>That failure is not a lost optimization. A {@code -javaagent} the JVM cannot open aborts VM
 * initialization, so the game does not start at all. An installation under a user folder holding a
 * character outside the system code page would be unable to launch through Preflight while launching
 * normally without it.
 *
 * <p>Code pages cover their own language, so an ordinary localized account name is representable and
 * nothing here does any work: {@link #readableByTheChildJvm} returns the JAR untouched and copies
 * nothing. It is mixed scripts that fall outside — a Greek account name on a cp1252 system, say.
 * Those are staged: the JAR is copied once to a directory whose own path survives the encoding, and
 * that copy's path is what {@code JAVA_TOOL_OPTIONS} carries. The copy is named for its SHA-256, so a
 * changed JAR stages under a new name and a stale one is never mistaken for the current build.
 *
 * <p>The encoding consulted is the wrapper's own {@code sun.jnu.encoding}. The wrapper and the game
 * share an environment and a system locale, so the wrapper's value is what the child will use.
 * {@code sun.jnu.encoding} is derived from the System Locale and is documented as not settable with
 * {@code -D}, which is why the JAR moves instead of the encoding.
 */
final class AgentJarStaging {
    /** Directory created under a staging root. Deliberately ASCII. */
    static final String DIRECTORY_NAME = "preflight-agent";

    private AgentJarStaging() {
    }

    /**
     * Returns {@code agentJar} when the child JVM can read its path, otherwise a staged copy.
     *
     * @throws IOException when the path needs staging and no candidate root can hold it, which is
     *     reported rather than left to surface as a JVM that will not initialize
     */
    static Path readableByTheChildJvm(Path agentJar) throws IOException {
        return readableByTheChildJvm(agentJar, nativeEncoding(), stagingRoots());
    }

    /** The parameterized form, so a test can pose an encoding and roots the host does not have. */
    static Path readableByTheChildJvm(Path agentJar, Charset encoding, List<Path> stagingRoots)
            throws IOException {
        Path jar = agentJar.toAbsolutePath().normalize();
        if (survives(jar.toString(), encoding)) {
            return jar;
        }
        String name = "preflight-" + Hashes.sha256(jar).substring(0, 16) + ".jar";
        List<String> refusals = new ArrayList<>();
        for (Path root : stagingRoots) {
            Path directory = root.resolve(DIRECTORY_NAME);
            Path staged = directory.resolve(name);
            if (!survives(staged.toString(), encoding)) {
                refusals.add(root + " (also outside " + encoding.name() + ")");
                continue;
            }
            try {
                return copyInto(jar, directory, staged);
            } catch (IOException error) {
                refusals.add(root + " (" + error + ")");
            }
        }
        throw new IOException(
                "The Preflight JAR sits at a path the game's JVM cannot read under "
                        + encoding.name() + ", and no staging directory was usable: " + jar
                        + "; tried " + refusals);
    }

    /** True when {@code value} reaches the child JVM as itself rather than as {@code '?'}. */
    static boolean survives(String value, Charset encoding) {
        return encoding.newEncoder().canEncode(value);
    }

    /**
     * The encoding the child JVM will apply to {@code JAVA_TOOL_OPTIONS}.
     *
     * <p>Falls back to the default charset only if a JVM ever omits the property. Guessing too
     * narrow here costs one copy; guessing too wide costs a launch.
     */
    static Charset nativeEncoding() {
        String name = System.getProperty("sun.jnu.encoding");
        if (name != null && !name.isBlank()) {
            try {
                return Charset.forName(name);
            } catch (IllegalArgumentException unsupported) {
                // An unrecognizable name says nothing about what the child will accept, so fall
                // through to the default rather than treating it as permissive.
            }
        }
        return Charset.defaultCharset();
    }

    /**
     * Directories that might hold a staged copy, best first.
     *
     * <p>The temporary directory is preferred because it is already the place for regenerable files.
     * On the systems this exists for it usually sits under the same unrepresentable user folder as
     * the JAR, so Windows contributes {@code %PUBLIC%} and {@code %ProgramData%}, both fixed ASCII
     * paths that ordinary users may write to.
     */
    static List<Path> stagingRoots() {
        Set<Path> roots = new LinkedHashSet<>();
        addRoot(roots, System.getProperty("java.io.tmpdir"));
        addRoot(roots, System.getenv("PUBLIC"));
        addRoot(roots, System.getenv("ProgramData"));
        addRoot(roots, "/tmp");
        addRoot(roots, "/var/tmp");
        return List.copyOf(roots);
    }

    private static void addRoot(Set<Path> roots, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            Path root = Path.of(value).toAbsolutePath().normalize();
            if (Files.isDirectory(root)) {
                roots.add(root);
            }
        } catch (InvalidPathException unusable) {
            // A root the platform cannot even spell is simply not a candidate.
        }
    }

    private static Path copyInto(Path jar, Path directory, Path staged) throws IOException {
        Files.createDirectories(directory);
        if (isAlready(jar, staged)) {
            return staged;
        }
        Path scratch = Files.createTempFile(directory, "preflight-", ".jar.tmp");
        try {
            Files.copy(jar, scratch, StandardCopyOption.REPLACE_EXISTING);
            move(scratch, staged);
        } finally {
            Files.deleteIfExists(scratch);
        }
        return staged;
    }

    /**
     * Publishes the scratch copy under its final name.
     *
     * <p>Atomic first, so a concurrent launch never sees a partly written JAR. Two things can refuse
     * it: a filesystem that has no atomic replace, and Windows, which will not replace a file another
     * launch already has open. The name carries the content hash, so a file of the right size sitting
     * there is this same JAR, and the loser of that race has nothing left to do.
     */
    private static void move(Path scratch, Path staged) throws IOException {
        try {
            Files.move(scratch, staged,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return;
        } catch (AtomicMoveNotSupportedException unsupported) {
            // Fall through and take the plain replace this filesystem does support.
        } catch (FileSystemException contended) {
            if (isAlready(scratch, staged)) {
                return;
            }
            throw contended;
        }
        try {
            Files.move(scratch, staged, StandardCopyOption.REPLACE_EXISTING);
        } catch (FileSystemException contended) {
            if (!isAlready(scratch, staged)) {
                throw contended;
            }
        }
    }

    private static boolean isAlready(Path source, Path staged) throws IOException {
        return Files.isRegularFile(staged) && Files.size(staged) == Files.size(source);
    }
}
