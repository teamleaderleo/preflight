package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.nio.file.Path;
import java.util.Map;

/** Small read-only dependency and mod-metadata check for the desktop launch path. */
final class DesktopModReadinessCommand {
    private DesktopModReadinessCommand() {
    }

    static int execute(String[] args, int offset) throws Exception {
        if (offset >= args.length || !"--game".equals(args[offset]) || offset + 2 != args.length) {
            throw new IllegalArgumentException(
                    "Expected desktop bridge request: desktop mod-readiness --game <path>");
        }
        Path installRoot = InstallRoot.resolve(Path.of(args[offset + 1]));
        System.out.println(Json.object(read(installRoot)));
        return 0;
    }

    static Map<String, Object> read(Path installRoot) throws Exception {
        return ModMetadataCheck.check(installRoot).view();
    }
}
