package dev.starsector.preflight.cli;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

final class ProfileCommandCapabilityDigestProbeTest {
    @Test
    void printReviewedProfileCommandDigest() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/dev/starsector/preflight/cli/ProfileCommand.java"),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        String digest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
        throw new AssertionError("PROFILE_COMMAND_CAPABILITY_SHA256=" + digest);
    }
}
