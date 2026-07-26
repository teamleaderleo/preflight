package dev.starsector.preflight.cli;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the install root for commands that read a profile but never launch it.
 *
 * <p>An explicit {@code --game} is taken as the root directly. The launching commands rightly insist
 * on discovering a launcher beside it; a command that only reads a resource tree would gain nothing
 * from that and would refuse profiles it can analyse perfectly well.</p>
 */
final class InstallRoot {
    private InstallRoot() {
    }

    /** @return the resolved root, or null when discovery found no installation */
    static Path resolve(Path game) throws Exception {
        if (game != null) {
            Path root = game.toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                throw new IllegalArgumentException("--game is not a directory: " + root);
            }
            return root;
        }
        DiscoveryResult discovery = StarsectorDiscovery.discover(
                Platform.current(),
                Path.of(System.getProperty("user.home")),
                Path.of(System.getProperty("user.dir")),
                System.getenv(),
                null,
                null);
        LaunchTarget target = discovery.selected();
        return target == null ? null : target.installRoot();
    }
}
