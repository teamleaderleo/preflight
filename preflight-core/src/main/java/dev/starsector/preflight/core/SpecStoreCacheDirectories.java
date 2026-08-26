package dev.starsector.preflight.core;

import java.nio.file.Path;
import java.util.Objects;

/** On-disk namespaces for the prepared spec stores selected at launch. */
public final class SpecStoreCacheDirectories {
    private SpecStoreCacheDirectories() {
    }

    public static Path profiles(Path cacheRoot) {
        return store(cacheRoot, "profiles", SpecStoreProfileIdentity.FORMAT_VERSION, 1);
    }

    public static Path variantJson(Path cacheRoot) {
        return store(cacheRoot, "variant-json", PreparedVariantJsonCache.FORMAT_VERSION, 2);
    }

    public static Path weaponJson(Path cacheRoot) {
        return store(cacheRoot, "weapon-json", PreparedWeaponJsonCache.FORMAT_VERSION, 2);
    }

    public static Path projectileJson(Path cacheRoot) {
        return store(cacheRoot, "projectile-json", PreparedProjectileJsonCache.FORMAT_VERSION, 2);
    }

    public static Path hullJson(Path cacheRoot) {
        return store(cacheRoot, "hull-json", PreparedHullJsonCache.FORMAT_VERSION, 2);
    }

    public static Path rulesCsv(Path cacheRoot) {
        Path root = root(cacheRoot);
        if (PreparedRulesCsvCache.FORMAT_VERSION == 2
                && PreparedRuleTokenCache.FORMAT_VERSION == 1) {
            return root.resolve("rules-csv");
        }
        return root.resolve("rules-csv-v" + PreparedRulesCsvCache.FORMAT_VERSION
                + "-tokens-v" + PreparedRuleTokenCache.FORMAT_VERSION);
    }

    public static Path ruleCommandClasses(Path cacheRoot) {
        return store(
                cacheRoot,
                "rule-command-classes",
                PreparedRuleCommandClassCache.FORMAT_VERSION,
                1);
    }

    public static Path mergedReads(Path cacheRoot) {
        return store(cacheRoot, "merged-reads", PreparedMergedReadCache.FORMAT_VERSION, 1);
    }

    public static Path magicPaintjobs(Path cacheRoot) {
        return store(
                cacheRoot,
                "magic-paintjobs",
                PreparedMagicPaintjobCache.FORMAT_VERSION,
                1);
    }

    private static Path store(
            Path cacheRoot, String name, int currentFormatVersion, int establishedFormatVersion) {
        return CacheFormatNamespace.directory(
                root(cacheRoot), name, currentFormatVersion, establishedFormatVersion);
    }

    private static Path root(Path cacheRoot) {
        return Objects.requireNonNull(cacheRoot, "cacheRoot").resolve("spec-store");
    }
}
