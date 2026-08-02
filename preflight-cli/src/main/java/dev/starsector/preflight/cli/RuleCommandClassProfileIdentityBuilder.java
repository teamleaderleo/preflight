package dev.starsector.preflight.cli;

import static dev.starsector.preflight.cli.MergedJsonProfileIdentity.sha256;
import static dev.starsector.preflight.cli.MergedJsonProfileIdentity.update;

import dev.starsector.preflight.core.ResourceIndex;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Exact identity for the two things that decide which package a rule command name resolves in.
 *
 * <p>Vanilla walks the merged {@code ruleCommandPackages} list in order and takes the first package
 * whose class loads and instantiates. So the answer moves when either of these moves:
 *
 * <ul>
 *   <li><b>the declared package list</b>, merged from every {@code data/config/settings.json}
 *       provider -- reorder it, or insert an entry ahead of a winner, and a different class wins;
 *   <li><b>the code on the classpath</b>, because a mod update that adds a same-named class to an
 *       already-declared earlier package changes the winner without changing the list at all. That
 *       is the only way this cache can go silently stale, which is why every jar is hashed rather
 *       than stamped by size or timestamp.
 * </ul>
 *
 * <p>Both come out of the resource index the launch already built, so this needs no extra scan.
 */
final class RuleCommandClassProfileIdentityBuilder {
    private static final String SCHEMA = "starsector-preflight-rule-command-class-profile-v1";
    private static final String SETTINGS_PATH = "data/config/settings.json";
    private static final String JAR_SUFFIX = ".jar";

    private RuleCommandClassProfileIdentityBuilder() {
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

        List<ResourceIndex.Provider> settings =
                resources.entries().getOrDefault(SETTINGS_PATH, List.of());

        // Sorted by the index's own key order, so two runs over the same install agree.
        List<String> jarPaths = new ArrayList<>();
        List<ResourceIndex.Provider> jarProviders = new ArrayList<>();
        for (Map.Entry<String, List<ResourceIndex.Provider>> entry : resources.entries().entrySet()) {
            String path = entry.getKey();
            if (!path.toLowerCase(Locale.ROOT).endsWith(JAR_SUFFIX)) {
                continue;
            }
            for (ResourceIndex.Provider provider : entry.getValue()) {
                jarPaths.add(path);
                jarProviders.add(provider);
            }
        }

        List<ResourceIndex.Provider> flattened = new ArrayList<>(settings);
        flattened.addAll(jarProviders);
        List<String> hashes = context.sha256All(context.resolveAll(flattened));

        update(digest, SETTINGS_PATH);
        update(digest, settings.size());
        int next = 0;
        for (ResourceIndex.Provider provider : settings) {
            hashProvider(digest, resources, SETTINGS_PATH, provider, hashes.get(next++));
        }

        long jarBytes = 0;
        for (int index = 0; index < jarProviders.size(); index++) {
            ResourceIndex.Provider provider = jarProviders.get(index);
            hashProvider(digest, resources, jarPaths.get(index), provider, hashes.get(next++));
            jarBytes = Math.addExact(jarBytes, provider.size());
        }
        update(digest, jarProviders.size());
        update(digest, jarBytes);

        return new Result(
                HexFormat.of().formatHex(digest.digest()),
                context.gameJar(),
                settings.size(),
                jarProviders.size(),
                jarBytes);
    }

    private static void hashProvider(
            MessageDigest digest,
            ResourceIndex resources,
            String path,
            ResourceIndex.Provider provider,
            String sha256) {
        ResourceIndex.Root root = resources.roots().get(provider.rootIndex());
        update(digest, path);
        update(digest, provider.rootIndex());
        update(digest, root.id());
        update(digest, root.core());
        update(digest, provider.relativePath());
        update(digest, provider.size());
        update(digest, sha256);
    }

    record Result(
            String identitySha256,
            Path gameJar,
            long settingsProviderCount,
            long jarCount,
            long jarBytes) {
    }
}
