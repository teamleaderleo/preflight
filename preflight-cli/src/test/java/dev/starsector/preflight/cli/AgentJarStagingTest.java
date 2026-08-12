package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
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
        assertFalse(Files.exists(root.resolve(AgentJarStaging.DIRECTORY_NAME)),
                "an installation the JVM can already read is not worth copying");
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
    }

    @Test
    void theSameJarStagesOnceAndIsReusedAfterwards() throws IOException {
        Path jar = jarUnder(OUTSIDE_THE_CODE_PAGE);
        Path root = Files.createDirectory(directory.resolve("root"));

        Path first = AgentJarStaging.readableByTheChildJvm(jar, StandardCharsets.US_ASCII, List.of(root));
        // A stamp no copy would reproduce, so a second write is visible on every platform.
        FileTime staged = FileTime.fromMillis(1_000_000_000L);
        Files.setLastModifiedTime(first, staged);
        Path second = AgentJarStaging.readableByTheChildJvm(jar, StandardCharsets.US_ASCII, List.of(root));

        assertEquals(first, second);
        assertEquals(staged, Files.getLastModifiedTime(second),
                "a launch that finds its copy already there must not rewrite it");
        try (var listing = Files.list(first.getParent())) {
            assertEquals(1, listing.count(), "and must leave no scratch file behind");
        }
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
                "the name carries the content, so an update can never be served a stale copy");
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
}
