package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.starsector.preflight.core.Hashes;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The one Preflight path the child JVM reads for itself has to reach it intact. */
class AgentJarStagingTest {
    /** Representable on a Greek system, and lost on the cp1252 one this exists for. */
    private static final String OUTSIDE_THE_CODE_PAGE = "Ωμέγα";

    private static final byte[] CONTENT = "not really a JAR".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path directory;

    @Test
    void aPathTheEncodingCanCarryIsHandedOverUntouched() throws IOException {
        Path jar = jarUnder("plain");
        Path root = Files.createDirectory(directory.resolve("root"));

        Path injected = AgentJarStaging.readableByTheChildJvm(jar, StandardCharsets.US_ASCII, List.of(root));

        assertEquals(jar, injected);
        try (var entries = Files.list(root)) {
            assertEquals(0, entries.count(), "an installation the JVM can already read is not worth copying");
        }
    }

    @Test
    void aPathOutsideTheEncodingIsStagedWhereTheChildCanReadIt() throws IOException {
        Path jar = jarUnder(OUTSIDE_THE_CODE_PAGE);
        Path root = Files.createDirectory(directory.resolve("root"));

        Path injected = AgentJarStaging.readableByTheChildJvm(jar, StandardCharsets.US_ASCII, List.of(root));

        assertNotEquals(jar, injected);
        assertTrue(AgentJarStaging.survives(injected.toString(), StandardCharsets.US_ASCII),
                "the whole point is a path that reaches the JVM as itself");
        assertArrayEqualsContent(jar, injected);
        assertTrue(injected.getParent().getFileName().toString().startsWith(AgentJarStaging.DIRECTORY_NAME + "-"));
        assertOwnerPrivateWhenPosix(injected.getParent());
    }

    @Test
    void aclOnlyDirectoryIsPrivateAtItsCreationBoundary() throws IOException {
        Path root = Files.createDirectory(directory.resolve("acl-root"));
        PosixFileAttributeView posix = Files.getFileAttributeView(
                root, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        AclFileAttributeView rootAcl = Files.getFileAttributeView(
                root, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        assumeTrue(posix == null && rootAcl != null,
                "this regression exercises the ACL-only creation path");

        AtomicBoolean observed = new AtomicBoolean();
        Path created = AgentJarStaging.createPrivateDirectory(root, fresh -> {
            AclFileAttributeView acl = Files.getFileAttributeView(
                    fresh, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            assertNotNull(acl);
            var owner = Files.getOwner(fresh, LinkOption.NOFOLLOW_LINKS);
            var entries = acl.getAcl();
            assertFalse(entries.isEmpty(), "creation must install a real owner ACL");
            assertTrue(entries.stream().allMatch(entry -> entry.principal().equals(owner)),
                    "no inherited shared-root principal may observe the fresh staging directory");
            assertTrue(entries.stream().anyMatch(entry -> entry.type() == AclEntryType.ALLOW),
                    "the current owner needs an allow entry at creation");
            observed.set(true);
        });

        assertTrue(observed.get());
        Files.delete(created);
    }

    @Test
    void wrapperCleanupRemovesChildBeforeItsPrivateDirectory() throws IOException {
        Path jar = jarUnder(OUTSIDE_THE_CODE_PAGE);
        Path root = Files.createDirectory(directory.resolve("cleanup-root"));
        Path injected = AgentJarStaging.readableByTheChildJvm(
                jar, StandardCharsets.US_ASCII, List.of(root));
        Path privateDirectory = injected.getParent();

        AgentJarStaging.cleanupStagedCopy(injected, privateDirectory);

        assertFalse(Files.exists(injected, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(privateDirectory, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void aPreexistingSameSizeSharedAgentIsNeverTrusted() throws IOException {
        Path jar = jarUnder(OUTSIDE_THE_CODE_PAGE);
        Path root = Files.createDirectory(directory.resolve("root"));
        Path attackerDirectory = Files.createDirectory(root.resolve(AgentJarStaging.DIRECTORY_NAME));
        String oldPredictableName = "preflight-" + Hashes.sha256(jar).substring(0, 16) + ".jar";
        Path attackerJar = attackerDirectory.resolve(oldPredictableName);
        byte[] poison = CONTENT.clone();
        poison[0] ^= 1;
        Files.write(attackerJar, poison);
        assertEquals(Files.size(jar), Files.size(attackerJar));

        Path injected = AgentJarStaging.readableByTheChildJvm(jar, StandardCharsets.US_ASCII, List.of(root));

        assertNotEquals(attackerJar, injected,
                "a predictable same-size file in shared temp must never become -javaagent");
        assertArrayEqualsContent(jar, injected);
        assertNotEquals(-1L, Files.mismatch(attackerJar, injected),
                "the attacker-controlled file remains distinct from the trusted copy");
    }

    @Test
    void repeatedStagingUsesIndependentPrivateDirectories() throws IOException {
        Path jar = jarUnder(OUTSIDE_THE_CODE_PAGE);
        Path root = Files.createDirectory(directory.resolve("root"));

        Path first = AgentJarStaging.readableByTheChildJvm(jar, StandardCharsets.US_ASCII, List.of(root));
        Path second = AgentJarStaging.readableByTheChildJvm(jar, StandardCharsets.US_ASCII, List.of(root));

        assertNotEquals(first, second,
                "shared staging must not depend on a predictable persistent executable path");
        assertNotEquals(first.getParent(), second.getParent());
        assertArrayEqualsContent(jar, first);
        assertArrayEqualsContent(jar, second);
    }

    @Test
    void aDifferentJarStagesUnderADifferentName() throws IOException {
        Path root = Files.createDirectory(directory.resolve("root"));
        Path original = jarUnder(OUTSIDE_THE_CODE_PAGE);
        Path rebuilt = jarUnder(OUTSIDE_THE_CODE_PAGE + "-next");
        Files.write(rebuilt, "a later build".getBytes(StandardCharsets.UTF_8));

        Path first = AgentJarStaging.readableByTheChildJvm(original, StandardCharsets.US_ASCII, List.of(root));
        Path second = AgentJarStaging.readableByTheChildJvm(rebuilt, StandardCharsets.US_ASCII, List.of(root));

        assertNotEquals(first.getFileName(), second.getFileName(),
                "the filename carries the full content identity");
        assertArrayEqualsContent(rebuilt, second);
    }

    @Test
    void aRootThatIsItselfOutsideTheEncodingIsSkippedForOneThatIsNot() throws IOException {
        Path jar = jarUnder(OUTSIDE_THE_CODE_PAGE);
        Path unusable = Files.createDirectory(directory.resolve("root-" + OUTSIDE_THE_CODE_PAGE));
        Path usable = Files.createDirectory(directory.resolve("root-plain"));

        Path injected = AgentJarStaging.readableByTheChildJvm(
                jar, StandardCharsets.US_ASCII, List.of(unusable, usable));

        assertTrue(injected.startsWith(usable), "staging into an unreadable folder would fix nothing");
    }

    @Test
    void aSymlinkedStagingRootIsSkipped() throws IOException {
        Path jar = jarUnder(OUTSIDE_THE_CODE_PAGE);
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path link = directory.resolve("shared-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | SecurityException | IOException unavailable) {
            assumeTrue(false, "Symbolic links are unavailable in this test environment: " + unavailable);
        }
        Path usable = Files.createDirectory(directory.resolve("root-plain"));

        Path injected = AgentJarStaging.readableByTheChildJvm(
                jar, StandardCharsets.US_ASCII, List.of(link, usable));

        assertTrue(injected.startsWith(usable));
        try (var entries = Files.list(outside)) {
            assertEquals(0, entries.count(), "a symlinked shared root must remain untouched");
        }
    }

    @Test
    void havingNowhereToStageIsReportedRatherThanLeftToTheJvm() throws IOException {
        Path jar = jarUnder(OUTSIDE_THE_CODE_PAGE);
        Path unusable = Files.createDirectory(directory.resolve("root-" + OUTSIDE_THE_CODE_PAGE));

        IOException failure = assertThrows(IOException.class, () ->
                AgentJarStaging.readableByTheChildJvm(jar, StandardCharsets.US_ASCII, List.of(unusable)));

        // A -javaagent the JVM cannot open aborts VM initialization, so the alternative to this
        // message is the game refusing to start with nothing pointing at the cause.
        assertTrue(failure.getMessage().contains(jar.toString()), failure.getMessage());
        assertTrue(failure.getMessage().contains("US-ASCII"), failure.getMessage());
    }

    @Test
    void aCodePageIsWhatTheChildWouldHaveBeenGivenInstead() {
        // Not a test of our code: it pins why staging exists at all. HotSpot reads JAVA_TOOL_OPTIONS
        // through the narrow getenv, so Windows converts it to the active ANSI code page first.
        Charset codePage = Charset.forName("windows-1252");

        assertFalse(AgentJarStaging.survives(OUTSIDE_THE_CODE_PAGE, codePage));
        assertEquals("?????", new String(OUTSIDE_THE_CODE_PAGE.getBytes(codePage), codePage),
                "every character becomes a literal question mark, naming a file that is not there");
        assertTrue(AgentJarStaging.survives(OUTSIDE_THE_CODE_PAGE, Charset.forName("windows-1253")),
                "while a Greek system carries a Greek account name perfectly well");
    }

    private Path jarUnder(String folder) throws IOException {
        Path parent = Files.createDirectories(directory.resolve(folder));
        Path jar = parent.resolve("preflight.jar");
        Files.write(jar, CONTENT);
        return jar;
    }

    private static void assertArrayEqualsContent(Path expected, Path actual) throws IOException {
        assertEquals(-1L, Files.mismatch(expected, actual), "the staged copy must be the same JAR");
    }

    private static void assertOwnerPrivateWhenPosix(Path path) throws IOException {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            assertFalse(permissions.contains(PosixFilePermission.GROUP_WRITE));
            assertFalse(permissions.contains(PosixFilePermission.OTHERS_WRITE));
            assertFalse(permissions.contains(PosixFilePermission.GROUP_READ));
            assertFalse(permissions.contains(PosixFilePermission.OTHERS_READ));
        } catch (UnsupportedOperationException ignored) {
            // Windows uses an owner-only ACL instead of POSIX mode bits.
        }
    }
}
