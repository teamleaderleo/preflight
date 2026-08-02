package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.ResourceIndex;
import java.io.IOException;
import java.nio.file.Path;

/** Exact identity for only the inputs that can change vanilla's merged projectile JSON. */
final class ProjectileJsonProfileIdentityBuilder {
    private static final String SCHEMA = "starsector-preflight-projectile-json-profile-v1";

    private ProjectileJsonProfileIdentityBuilder() {
    }

    static Result build(Path installRoot, ResourceIndex resources) throws IOException {
        try (ProfileIdentityContext context = ProfileIdentityContext.of(installRoot, resources)) {
            return build(context);
        }
    }

    static Result build(ProfileIdentityContext context) throws IOException {
        MergedJsonProfileIdentity.Result merged = MergedJsonProfileIdentity.build(
                context, SCHEMA, ProjectileJsonProfileIdentityBuilder::selects);
        return new Result(
                merged.identitySha256(),
                merged.gameJar(),
                merged.logicalPaths(),
                merged.providerCount(),
                merged.providerBytes());
    }

    private static boolean selects(String logicalPath) {
        boolean projectile = logicalPath.startsWith("data/weapons/proj/");
        boolean shipSystem = logicalPath.startsWith("data/shipsystems/proj/");
        return (projectile || shipSystem) && logicalPath.endsWith(".proj");
    }

    record Result(
            String identitySha256,
            Path gameJar,
            int logicalPaths,
            long providerCount,
            long providerBytes) {
    }
}
