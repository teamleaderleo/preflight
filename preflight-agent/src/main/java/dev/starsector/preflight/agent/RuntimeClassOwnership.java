package dev.starsector.preflight.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Report-time ownership for concrete runtime callback classes. */
final class RuntimeClassOwnership {
    private static final long MAX_MOD_INFO_BYTES = 256L * 1024L;
    private static final ClassValue<RuntimeClassOwnership> OWNERS = new ClassValue<>() {
        @Override
        protected RuntimeClassOwnership computeValue(Class<?> type) {
            return resolveUncached(type);
        }
    };

    private final String className;
    private final String ownerKey;
    private final String ownerKind;
    private final String ownerName;
    private final String modId;
    private final String modDirectory;
    private final String modRoot;
    private final String sourceArtifact;
    private final String sourceKind;
    private final String codeSource;
    private final String normalizedSource;
    private final String loaderClass;
    private final String loaderName;
    private final String resolution;

    private RuntimeClassOwnership(
            String className,
            String ownerKey,
            String ownerKind,
            String ownerName,
            String modId,
            String modDirectory,
            String modRoot,
            String sourceArtifact,
            String sourceKind,
            String codeSource,
            String normalizedSource,
            String loaderClass,
            String loaderName,
            String resolution) {
        this.className = text(className);
        this.ownerKey = text(ownerKey);
        this.ownerKind = text(ownerKind);
        this.ownerName = text(ownerName);
        this.modId = text(modId);
        this.modDirectory = text(modDirectory);
        this.modRoot = text(modRoot);
        this.sourceArtifact = text(sourceArtifact);
        this.sourceKind = text(sourceKind);
        this.codeSource = text(codeSource);
        this.normalizedSource = text(normalizedSource);
        this.loaderClass = text(loaderClass);
        this.loaderName = text(loaderName);
        this.resolution = text(resolution);
    }

    static RuntimeClassOwnership resolve(Class<?> type) {
        return type == null
                ? unresolved("", AdapterSourceIdentity.unknown(), "null-class")
                : OWNERS.get(type);
    }

    private static RuntimeClassOwnership resolveUncached(Class<?> type) {
        AdapterSourceIdentity source;
        try {
            ProtectionDomain domain = type.getProtectionDomain();
            source = AdapterSourceIdentity.capture(type.getClassLoader(), domain, false);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            source = AdapterSourceIdentity.unknown();
        }
        return resolve(type.getName(), source);
    }

    static RuntimeClassOwnership resolve(String className, AdapterSourceIdentity source) {
        AdapterSourceIdentity identity = source == null ? AdapterSourceIdentity.unknown() : source;
        String kind = identity.sourceKind();
        String normalized = identity.normalizedSource();
        Path sourcePath = localPath(normalized);
        String artifact = sourcePath == null || sourcePath.getFileName() == null
                ? "" : sourcePath.getFileName().toString();

        Path root = "MOD".equals(kind) ? modRoot(sourcePath) : null;
        if (root != null) {
            String directory = root.getFileName() == null ? "" : root.getFileName().toString();
            String id = readModId(root);
            if (!id.isBlank()) {
                return new RuntimeClassOwnership(
                        className,
                        "mod:" + id,
                        "MOD",
                        id,
                        id,
                        directory,
                        root.toString(),
                        artifact,
                        kind,
                        identity.codeSource(),
                        normalized,
                        identity.loaderClass(),
                        identity.loaderName(),
                        "mod-info-id");
            }
            String key = directory.isBlank() ? normalized : directory;
            return new RuntimeClassOwnership(
                    className,
                    "mod-directory:" + key,
                    "MOD",
                    directory.isBlank() ? "unresolved mod" : directory,
                    "",
                    directory,
                    root.toString(),
                    artifact,
                    kind,
                    identity.codeSource(),
                    normalized,
                    identity.loaderClass(),
                    identity.loaderName(),
                    "mod-directory-only");
        }

        if (janino(className, identity)) {
            return new RuntimeClassOwnership(
                    className,
                    "dynamic:janino",
                    "DYNAMIC_JANINO",
                    "Janino generated/loaded class",
                    "",
                    "",
                    "",
                    artifact,
                    kind,
                    identity.codeSource(),
                    normalized,
                    identity.loaderClass(),
                    identity.loaderName(),
                    "dynamic-janino-origin-unresolved");
        }
        if ("STARSECTOR_CORE".equals(kind)) {
            return fixed(className, identity, artifact,
                    "starsector-core", "STARSECTOR_CORE", "Starsector core", "source-kind");
        }
        if ("FAST_RENDERING".equals(kind)) {
            return fixed(className, identity, artifact,
                    "fast-rendering", "FAST_RENDERING", "Fast Rendering", "source-kind");
        }
        if (!normalized.isBlank()) {
            return fixed(className, identity, artifact,
                    "source:" + normalized, "OTHER_SOURCE",
                    artifact.isBlank() ? normalized : artifact, "source-only");
        }
        return unresolved(className, identity, "source-unresolved");
    }

    Map<String, Object> report() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("className", className);
        value.put("ownerKey", ownerKey);
        value.put("ownerKind", ownerKind);
        value.put("ownerName", ownerName);
        value.put("modId", emptyToNull(modId));
        value.put("modDirectory", emptyToNull(modDirectory));
        value.put("modRoot", emptyToNull(modRoot));
        value.put("sourceArtifact", emptyToNull(sourceArtifact));
        value.put("sourceKind", sourceKind);
        value.put("codeSource", emptyToNull(codeSource));
        value.put("normalizedSource", emptyToNull(normalizedSource));
        value.put("loaderClass", emptyToNull(loaderClass));
        value.put("loaderName", emptyToNull(loaderName));
        value.put("resolution", resolution);
        return value;
    }

    String ownerKey() {
        return ownerKey;
    }

    String ownerKind() {
        return ownerKind;
    }

    String ownerName() {
        return ownerName;
    }

    private static RuntimeClassOwnership fixed(
            String className,
            AdapterSourceIdentity identity,
            String artifact,
            String key,
            String ownerKind,
            String ownerName,
            String resolution) {
        return new RuntimeClassOwnership(
                className, key, ownerKind, ownerName, "", "", "", artifact,
                identity.sourceKind(), identity.codeSource(), identity.normalizedSource(),
                identity.loaderClass(), identity.loaderName(), resolution);
    }

    private static RuntimeClassOwnership unresolved(
            String className, AdapterSourceIdentity identity, String resolution) {
        String loader = identity.loaderClass();
        String key = loader.isBlank() ? "unresolved" : "unresolved-loader:" + loader;
        return new RuntimeClassOwnership(
                className, key, "UNRESOLVED", "Unresolved", "", "", "", "",
                identity.sourceKind(), identity.codeSource(), identity.normalizedSource(),
                identity.loaderClass(), identity.loaderName(), resolution);
    }

    private static boolean janino(String className, AdapterSourceIdentity identity) {
        String combined = (text(className) + " " + identity.loaderClass() + " "
                + identity.loaderName() + " " + identity.normalizedSource()).toLowerCase(Locale.ROOT);
        return combined.contains("janino") || combined.contains("org.codehaus.commons.compiler");
    }

    private static Path localPath(String normalized) {
        if (normalized == null || normalized.isBlank()) return null;
        try {
            return Path.of(normalized).toAbsolutePath().normalize();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static Path modRoot(Path source) {
        if (source == null) return null;
        Path absolute = source.toAbsolutePath().normalize();
        for (int index = 0; index + 1 < absolute.getNameCount(); index++) {
            if (!"mods".equalsIgnoreCase(absolute.getName(index).toString())) continue;
            Path result = absolute.getRoot();
            for (int part = 0; part <= index + 1; part++) {
                result = result == null ? absolute.getName(part) : result.resolve(absolute.getName(part));
            }
            return result.normalize();
        }
        return null;
    }

    static String readModId(Path root) {
        if (root == null) return "";
        Path metadata = root.resolve("mod_info.json");
        try {
            if (!Files.isRegularFile(metadata) || Files.size(metadata) > MAX_MOD_INFO_BYTES) return "";
            return topLevelString(Files.readString(metadata, StandardCharsets.UTF_8), "id");
        } catch (IOException | RuntimeException ignored) {
            return "";
        }
    }

    static String topLevelString(String json, String wantedKey) {
        if (json == null || wantedKey == null || wantedKey.isBlank()) return "";
        int depth = 0;
        int index = 0;
        while (index < json.length()) {
            char current = json.charAt(index);
            if (current == '"') {
                JsonString token = parseString(json, index);
                if (token == null) return "";
                if (depth == 1) {
                    int after = skipWhitespace(json, token.end());
                    if (after < json.length() && json.charAt(after) == ':'
                            && wantedKey.equals(token.value())) {
                        int valueStart = skipWhitespace(json, after + 1);
                        if (valueStart >= json.length() || json.charAt(valueStart) != '"') return "";
                        JsonString value = parseString(json, valueStart);
                        return value == null ? "" : value.value().trim();
                    }
                }
                index = token.end();
                continue;
            }
            if (current == '{' || current == '[') depth++;
            else if (current == '}' || current == ']') depth--;
            if (depth < 0) return "";
            index++;
        }
        return "";
    }

    private static JsonString parseString(String json, int quote) {
        StringBuilder value = new StringBuilder();
        for (int index = quote + 1; index < json.length(); index++) {
            char current = json.charAt(index);
            if (current == '"') return new JsonString(value.toString(), index + 1);
            if (current != '\\') {
                if (current < 0x20) return null;
                value.append(current);
                continue;
            }
            if (++index >= json.length()) return null;
            char escaped = json.charAt(index);
            switch (escaped) {
                case '"', '\\', '/' -> value.append(escaped);
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'u' -> {
                    if (index + 4 >= json.length()) return null;
                    int decoded = 0;
                    for (int digit = 1; digit <= 4; digit++) {
                        int hex = Character.digit(json.charAt(index + digit), 16);
                        if (hex < 0) return null;
                        decoded = (decoded << 4) | hex;
                    }
                    value.append((char) decoded);
                    index += 4;
                }
                default -> {
                    return null;
                }
            }
        }
        return null;
    }

    private static int skipWhitespace(String value, int index) {
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
        return index;
    }

    private static Object emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private record JsonString(String value, int end) {
    }
}
