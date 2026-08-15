package dev.starsector.preflight.cli;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * A name for one launch that stays the same everywhere it is written down.
 *
 * <p>The ledger first used the run directory's name, which turned out not to identify a launch: a
 * named trace directory is reused across runs, and on this machine eleven rows collided that way
 * within one import. Anything built on that key -- refusing a duplicate, applying a correction to
 * one launch, merging two machines' histories -- would have been quietly attaching itself to the
 * wrong session.
 *
 * <p>Live launches get a random id, which is the only honest answer for something that has not
 * happened before. Imported ones get a digest of the facts that were already recorded, so importing
 * the same run directory twice produces the same id twice and the second import is recognisably a
 * duplicate rather than a new launch. That is what makes the backfill safe to re-run, and what
 * would make two machines' ledgers mergeable rather than merely concatenated.
 */
final class LaunchIdentity {
    private static final String IMPORT_NAMESPACE = "preflight-launch-import-v1";

    private LaunchIdentity() {
    }

    /** For a launch happening now. */
    static String fresh() {
        return UUID.randomUUID().toString();
    }

    /**
     * For a launch reconstructed from evidence it left behind.
     *
     * <p>Derived from when it started and where it was written, because those are the two things a
     * run directory always has and neither changes on re-read. Not a security boundary -- it exists
     * so the same past launch keeps the same name, not to stop anybody forging one.
     */
    static String imported(Instant started, String runDirectory) {
        String material = IMPORT_NAMESPACE + "\u0020"
                + (started == null ? "" : started.toString()) + "\u0020"
                + (runDirectory == null ? "" : runDirectory);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(digest);
            // Shaped like a UUID so nothing downstream has to care which kind of id it holds.
            return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
                    + "-" + hex.substring(16, 20) + "-" + hex.substring(20, 32);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }
}
