package dev.starsector.preflight.agent;

import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.Locale;

/** Portable source and loader identity used by fail-closed adapter targets. */
record AdapterSourceIdentity(
        String codeSource,
        String normalizedSource,
        String sourceKind,
        String sourceSha256,
        String sourceHashProblem,
        String loaderClass,
        String loaderName) {
    AdapterSourceIdentity {
        codeSource = text(codeSource);
        normalizedSource = normalizePath(normalizedSource);
        sourceKind = text(sourceKind).toUpperCase(Locale.ROOT);
        sourceSha256 = text(sourceSha256).toLowerCase(Locale.ROOT);
        sourceHashProblem = text(sourceHashProblem);
        loaderClass = normalizeClass(loaderClass);
        loaderName = text(loaderName);
    }

    static AdapterSourceIdentity capture(
            ClassLoader loader,
            ProtectionDomain domain,
            boolean hashArchive) {
        URL location = location(domain);
        Path localPath = localPath(location);
        String raw = location == null ? "" : location.toString();
        String normalized = localPath == null
                ? normalizePath(raw)
                : normalizePath(localPath.toAbsolutePath().normalize().toString());
        SourceArchiveHashes.Result hash = hashArchive
                ? SourceArchiveHashes.sha256(localPath)
                : SourceArchiveHashes.notRequested();
        return new AdapterSourceIdentity(
                raw,
                normalized,
                classify(normalized.isBlank() ? raw : normalized),
                hash.sha256(),
                hash.problem(),
                loader == null ? "<bootstrap>" : loader.getClass().getName(),
                loaderName(loader));
    }

    static AdapterSourceIdentity unknown() {
        return new AdapterSourceIdentity("", "", "UNKNOWN", "", "", "", "");
    }

    boolean sourceEndsWith(String suffix) {
        String expected = normalizePath(suffix).toLowerCase(Locale.ROOT);
        return !expected.isBlank() && normalizedSource.toLowerCase(Locale.ROOT).endsWith(expected);
    }

    private static URL location(ProtectionDomain domain) {
        try {
            return domain == null || domain.getCodeSource() == null
                    ? null
                    : domain.getCodeSource().getLocation();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Path localPath(URL location) {
        if (location == null || !"file".equalsIgnoreCase(location.getProtocol())) {
            return null;
        }
        try {
            URI uri = location.toURI();
            return Path.of(uri);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String loaderName(ClassLoader loader) {
        if (loader == null) {
            return "<bootstrap>";
        }
        try {
            String value = loader.getName();
            return value == null ? "" : value;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String classify(String source) {
        String normalized = normalizePath(source).toLowerCase(Locale.ROOT);
        // The file name has to be tested before any path substring. Fast Rendering installs its
        // archives as fr.jar and fr.agent.jar *inside* the game directory, so a path-substring test
        // for "starsector" matches them first and reports a foreign artifact as STARSECTOR_CORE --
        // exactly backwards, and precisely the case shadow detection has to get right.
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (fileName.equals("fr.jar") || fileName.equals("fr.agent.jar")
                || normalized.contains("fast-render") || normalized.contains("fastrender")
                || normalized.contains("fast_render") || normalized.contains("starsector-render")
                || normalized.contains("com/genir/")) {
            return "FAST_RENDERING";
        }
        if (normalized.contains("/mods/")) {
            return "MOD";
        }
        if (normalized.contains("starsector-core") || normalized.contains("starfarer_obf")
                || normalized.contains("starsector")) {
            return "STARSECTOR_CORE";
        }
        return normalized.isBlank() ? "UNKNOWN" : "OTHER";
    }

    private static String normalizePath(String value) {
        String source = text(value).replace('\\', '/');
        StringBuilder output = new StringBuilder(source.length());
        boolean slash = false;
        for (int i = 0; i < source.length(); i++) {
            char current = source.charAt(i);
            if (current == '/') {
                if (!slash) {
                    output.append('/');
                }
                slash = true;
            } else {
                output.append(current);
                slash = false;
            }
        }
        while (output.length() > 1 && output.charAt(output.length() - 1) == '/') {
            output.setLength(output.length() - 1);
        }
        return output.toString();
    }

    private static String normalizeClass(String value) {
        String normalized = text(value);
        if (normalized.startsWith("<") && normalized.endsWith(">")) {
            return normalized;
        }
        return normalized.replace('.', '/');
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
