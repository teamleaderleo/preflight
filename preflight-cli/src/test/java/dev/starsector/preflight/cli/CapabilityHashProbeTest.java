package dev.starsector.preflight.cli;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class CapabilityHashProbeTest {
    @Test
    void printLauncherCapabilityDigestsForReview() throws Exception {
        for (String name : new String[] {
                "ExclusiveMove.java",
                "InstallCommand.java",
                "IntegrationMutation.java",
                "IntegrationOwnership.java",
                "PreflightHome.java"
        }) {
            Path path = Path.of("src/main/java/dev/starsector/preflight/cli").resolve(name);
            byte[] normalized = Files.readString(path, StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .getBytes(StandardCharsets.UTF_8);
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized));
            System.out.println("CAPABILITY_SHA256 " + name + " " + digest);
        }
    }
}
