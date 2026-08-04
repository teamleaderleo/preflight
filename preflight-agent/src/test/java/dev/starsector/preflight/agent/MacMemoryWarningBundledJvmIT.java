package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Opt-in real-process check against Starsector's bundled x86_64 JVM. */
class MacMemoryWarningBundledJvmIT {
    @Test
    void bundledJvmCanRunThePressureProbeEvenWhenJspawnhelperLostItsExecuteBit()
            throws Exception {
        String configured = System.getProperty("preflight.starsector.java", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.java=<Starsector bundled java>");
        Path java = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isExecutable(java));

        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(MacMemoryWarningProcessProbeChild.class.getName());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        assertTrue(process.waitFor(10L, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("\"corrections\":1"), output);
        assertTrue(output.contains("\"probeFailures\":0"), output);
        assertTrue(output.contains("\"processLaunchRepairApplied\":true"), output);
    }
}
