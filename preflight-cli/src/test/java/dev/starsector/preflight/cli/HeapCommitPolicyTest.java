package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeapCommitPolicyTest {
    @TempDir
    Path temporary;

    @Test
    void launcherDefaultDoesNotAlterTheUsersEnvironment() throws Exception {
        HeapCommitPolicy.Resolution resolution =
                HeapCommitPolicy.LAUNCHER_DEFAULT.resolve(target("ignored"));
        assertEquals(false, resolution.active());
        assertEquals(null, resolution.appendTo(null));
        assertEquals("-Duser.option=true", resolution.appendTo("-Duser.option=true"));
    }

    @Test
    void onDemandAppendsAfterExistingOptionsSoItOverridesAReviewedLauncher() throws Exception {
        HeapCommitPolicy.Resolution resolution = HeapCommitPolicy.ON_DEMAND.resolve(target(
                "java -Xms6g -Xmx6g -XX:+AlwaysPreTouch game.Main"));
        assertEquals(true, resolution.active());
        assertEquals("-XX:-AlwaysPreTouch", resolution.appendTo(null));
        assertEquals(
                "-XX:+AlwaysPreTouch -Duser.option=true -XX:-AlwaysPreTouch",
                resolution.appendTo(" -XX:+AlwaysPreTouch -Duser.option=true "));
    }

    @Test
    void onDemandFailsOpenWhenTheLauncherDoesNotRequestPreTouch() throws Exception {
        HeapCommitPolicy.Resolution resolution =
                HeapCommitPolicy.ON_DEMAND.resolve(target("java -Xms6g -Xmx6g game.Main"));
        assertEquals(false, resolution.active());
        assertEquals("-Duser.option=true", resolution.appendTo("-Duser.option=true"));
        assertEquals(null, resolution.toReportValues().get("javaOption"));
    }

    private LaunchTarget target(String launcherText) throws Exception {
        Path launcher = temporary.resolve("launcher-" + Math.abs(launcherText.hashCode()) + ".sh");
        Files.writeString(launcher, launcherText);
        return new LaunchTarget(
                temporary, launcher, temporary, List.of(launcher.toString()), "test", 1, "test");
    }
}
