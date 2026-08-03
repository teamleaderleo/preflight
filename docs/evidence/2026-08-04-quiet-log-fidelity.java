package dev.starsector.preflight.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.log4j.Logger;

/** Exercises the production-generated configuration through the installed game log4j. */
class QuietLogFidelity {
    public static void main(String[] args) throws Exception {
        Path configuration = Path.of(args[0]).toAbsolutePath().normalize();
        Path logs = Path.of(args[1]).toAbsolutePath().normalize();
        Files.createDirectories(logs);
        QuietLogConfiguration.write(configuration);

        Logger logger = Logger.getLogger("preflight.quiet-log-fidelity");
        for (int index = 0; index < 10_000; index++) {
            logger.info("fidelity-line-" + index);
        }
        logger.error("fidelity-final-line");
    }
}
