package dev.starsector.preflight.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class ScanCommand {
    private ScanCommand() {
    }

    static int execute(ScanOptions options) throws IOException {
        DiscoveryResult discovery = StarsectorDiscovery.discover(
                Platform.current(),
                Path.of(System.getProperty("user.home")),
                Path.of(System.getProperty("user.dir")),
                System.getenv(),
                options.game(),
                options.launcher());
        LaunchTarget target = discovery.selected();
        if (target == null) {
            System.err.println("Preflight could not locate Starsector. Run `doctor` or provide --game.");
            return 3;
        }

        ProfileCensus.Result result = ProfileCensus.scan(target.installRoot(), options.censusOptions());
        String json = result.toJson();
        if (options.output() == null) {
            System.out.println(json);
        } else {
            Path output = options.output().toAbsolutePath().normalize();
            if (output.getParent() != null) {
                Files.createDirectories(output.getParent());
            }
            Files.writeString(output, json + System.lineSeparator());
            System.out.println(output);
        }
        return 0;
    }
}
