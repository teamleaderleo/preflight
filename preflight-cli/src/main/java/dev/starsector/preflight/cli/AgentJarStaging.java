package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Hashes;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.ArrayList;
import java.util.EnumSet;
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
 * Those are staged into a fresh private directory below a path the child can spell. A shared staging
 * root is never trusted to contain an existing agent: Preflight creates an unpredictable directory
 * with owner-private permissions as a creation attribute, creates a new file inside it, and verifies
 * the full SHA-256 before handing the path to the child. One wrapper-owned shutdown hook removes the
 * staged JAR first and then its private directory.
 *
 * <p>The encoding consulted is the wrapper's own {@code sun.jnu.encoding}. The wrapper and the game
 * share an environment and a system locale, so the wrapper's value is what the child will use.
 * {@code sun.jnu.encoding} is derived from the System Locale and is documented as not settable with
 * {@code -D}, which is why the JAR moves instead of the encoding.
 */
final class AgentJarStaging {
    /** Prefix for private staging directories. Deliberately ASCII. */
    static final String DIRECTORY_NAME = "preflight-agent";

    private AgentJarStaging() {
    }

    /**
     * Returns {@code agentJar} when the child JVM can read its path, otherwise a staged copy.
     *
     * @throws IOException when the path needs staging and no candidate root can hold a private copy,
     *     which is reported rather than left to surface as a JVM that will not initialize
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
        String digest = Hashes.sha256(jar);
        String name = "preflight-" + digest + ".jar";
        List<String> refusals = new ArrayList<>();
        for (Path root : stagingRoots) {
            Path spellingProbe = root.resolve(DIRECTORY_NAME + "-0000000000000000").resolve(name);
            if (!survives(spellingProbe.toString(), encoding)) {
                refusals.add(root + " (also outside " + encoding.name() + ")");
                continue;
            }
            try {
                return copyIntoPrivateDirectory(jar, digest, root, name);
            } catch (IOException | RuntimeException error) {
                refusals.add(root + " (" + error + ")");
            }
        }
        throw new IOException(
                "The Preflight JAR sits at a path the game's JVM cannot read under "
                        + encoding.name() + ", and no private staging directory was usable: " + jar
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
     * On Windows it can live under the same unrepresentable user folder as the JAR, so ProgramData is
     * the fail-closed ASCII fallback. A broadly shared Public directory is intentionally not used for
     * executable staging. Unix contributes its conventional temporary roots; every actual copy still
     * lives below a newly created owner-private directory.
     */
    static List<Path> stagingRoots() {
        Set<Path> roots = new LinkedHashSet<>();
        addRoot(roots, System.getProperty("java.io.tmpdir"));
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
            if (!Files.isSymbolicLink(root)
                    && Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                roots.add(root);
            }
        } catch (InvalidPathException unusable) {
            // A root the platform cannot even spell is simply not a candidate.
        }
    }

    private static Path copyIntoPrivateDirectory(
            Path jar, String expectedSha256, Path root, String name) throws IOException {
        Path ownedRoot = root.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(ownedRoot)
                || !Files.isDirectory(ownedRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("staging root is not a real directory: " + ownedRoot);
        }

        Path directory = createPrivateDirectory(ownedRoot);
        Path staged = directory.resolve(name);
        boolean keep = false;
        try {
            Files.copy(jar, staged);
            if (Files.isSymbolicLink(staged)
                    || !Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("staged agent is not a real regular file: " + staged);
            }
            String actualSha256 = Hashes.sha256(staged);
            if (!expectedSha256.equals(actualSha256)) {
                throw new IOException("staged agent checksum changed while copying: " + staged);
            }
            registerCleanup(staged, directory);
            keep = true;
            return staged;
        } finally {
            if (!keep) {
                cleanupStagedCopy(staged, directory);
            }
        }
    }

    private static Path createPrivateDirectory(Path root) throws IOException {
        return createPrivateDirectory(root, ignored -> {});
    }

    static Path createPrivateDirectory(Path root, CreationObserver observer) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(
                root, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        Path directory;
        if (posix != null) {
            directory = Files.createTempDirectory(
                    root,
                    DIRECTORY_NAME + "-",
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------")));
        } else {
            AclFileAttributeView acl = Files.getFileAttributeView(
                    root, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (acl == null) {
                throw new IOException("staging filesystem cannot enforce owner-private permissions: "
                        + root);
            }
            UserPrincipal owner = currentOwner(root);
            directory = Files.createTempDirectory(
                    root, DIRECTORY_NAME + "-", ownerOnlyAclAttribute(owner));
        }

        boolean keep = false;
        try {
            if (Files.isSymbolicLink(directory)
                    || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("private staging path is not a real directory: " + directory);
            }
            observer.created(directory);
            restrictToCurrentOwner(directory);
            keep = true;
            return directory;
        } finally {
            if (!keep) {
                Files.deleteIfExists(directory);
            }
        }
    }

    private static UserPrincipal currentOwner(Path root) throws IOException {
        UserPrincipalLookupService lookup = root.getFileSystem().getUserPrincipalLookupService();
        String user = System.getProperty("user.name");
        if (user == null || user.isBlank()) {
            throw new IOException("current user is unavailable for private agent staging");
        }
        String domain = System.getenv("USERDOMAIN");
        if (domain != null && !domain.isBlank() && !user.contains("\\")) {
            try {
                return lookup.lookupPrincipalByName(domain + "\\" + user);
            } catch (IOException unavailable) {
                // Some providers use the short name even for a domain account.
            }
        }
        return lookup.lookupPrincipalByName(user);
    }

    private static FileAttribute<List<AclEntry>> ownerOnlyAclAttribute(UserPrincipal owner) {
        List<AclEntry> acl = List.of(ownerOnlyAclEntry(owner));
        return new FileAttribute<>() {
            @Override
            public String name() {
                return "acl:acl";
            }

            @Override
            public List<AclEntry> value() {
                return acl;
            }
        };
    }

    private static AclEntry ownerOnlyAclEntry(UserPrincipal owner) {
        return AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                .build();
    }

    private static void restrictToCurrentOwner(Path directory) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(
                directory, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString("rwx------"));
            return;
        }

        AclFileAttributeView acl = Files.getFileAttributeView(
                directory, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) {
            throw new IOException("staging filesystem cannot enforce owner-private permissions: "
                    + directory);
        }
        UserPrincipal owner = Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS);
        acl.setAcl(List.of(ownerOnlyAclEntry(owner)));
    }

    private static void registerCleanup(Path staged, Path directory) throws IOException {
        Thread hook = new Thread(
                () -> {
                    try {
                        cleanupStagedCopy(staged, directory);
                    } catch (IOException ignored) {
                        // The wrapper is already exiting. A later process can reclaim only this
                        // unpredictable private directory; never delete a replacement by name here.
                    }
                },
                "preflight-agent-staging-cleanup");
        try {
            Runtime.getRuntime().addShutdownHook(hook);
        } catch (IllegalStateException | SecurityException error) {
            throw new IOException("could not register staged-agent cleanup", error);
        }
    }

    static void cleanupStagedCopy(Path staged, Path directory) throws IOException {
        if (!staged.getParent().equals(directory)) {
            throw new IOException("staged agent escaped its private directory: " + staged);
        }
        Files.deleteIfExists(staged);
        Files.deleteIfExists(directory);
    }

    @FunctionalInterface
    interface CreationObserver {
        void created(Path directory) throws IOException;
    }
}
