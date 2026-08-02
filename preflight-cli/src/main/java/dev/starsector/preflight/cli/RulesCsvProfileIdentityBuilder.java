package dev.starsector.preflight.cli;

import static dev.starsector.preflight.cli.MergedJsonProfileIdentity.sha256;
import static dev.starsector.preflight.cli.MergedJsonProfileIdentity.update;

import dev.starsector.preflight.core.ResourceIndex;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** Exact identity for only the inputs that can change vanilla's merged campaign-rules rows. */
final class RulesCsvProfileIdentityBuilder {
    private static final String SCHEMA = "starsector-preflight-rules-csv-profile-v1";
    private static final String RULES_PATH = "data/campaign/rules.csv";

    private RulesCsvProfileIdentityBuilder() {
    }

    static Result build(Path installRoot, ResourceIndex resources) throws IOException {
        try (ProfileIdentityContext context = ProfileIdentityContext.of(installRoot, resources)) {
            return build(context);
        }
    }

    static Result build(ProfileIdentityContext context) throws IOException {
        ResourceIndex resources = context.resources();
        MessageDigest digest = sha256();
        update(digest, SCHEMA);
        update(digest, context.gameJarSha256());

        List<ResourceIndex.Provider> providers =
                resources.entries().getOrDefault(RULES_PATH, List.of());
        List<String> hashes = context.sha256All(context.resolveAll(providers));
        update(digest, RULES_PATH);
        update(digest, providers.size());
        long providerBytes = 0;
        for (int index = 0; index < providers.size(); index++) {
            ResourceIndex.Provider provider = providers.get(index);
            ResourceIndex.Root root = resources.roots().get(provider.rootIndex());
            update(digest, provider.rootIndex());
            update(digest, root.id());
            update(digest, root.core());
            update(digest, provider.relativePath());
            update(digest, provider.size());
            update(digest, hashes.get(index));
            providerBytes = Math.addExact(providerBytes, provider.size());
        }
        update(digest, providerBytes);
        return new Result(
                HexFormat.of().formatHex(digest.digest()),
                context.gameJar(),
                providers.size(),
                providerBytes);
    }

    record Result(
            String identitySha256,
            Path gameJar,
            long providerCount,
            long providerBytes) {
    }
}
