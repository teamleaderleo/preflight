package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.ModCompatibilityReadiness.Finding;
import dev.starsector.preflight.core.ModCompatibilityReadiness.ProfileChange;
import dev.starsector.preflight.core.ModCompatibilityReadiness.ReasonCode;
import dev.starsector.preflight.core.ModCompatibilityReadiness.Result;
import dev.starsector.preflight.core.ModCompatibilityReadiness.Severity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Cheap, metadata-only validation of the enabled mod profile. */
final class ModCompatibilityPrecheck {
    record Version(String raw, Integer major, Integer minor, Integer patch) {
        boolean hasNumericMajor() {
            return major != null;
        }
    }

    record Dependency(String id, String name, Version version, boolean optional) {}

    record Metadata(
        Path directory,
        String id,
        String name,
        Version version,
        String gameVersion,
        List<String> missingFields,
        List<Dependency> dependencies,
        Long requiredMemoryMb) {}


    private ModCompatibilityPrecheck() {}

    static Result inspect(Path installRoot) throws IOException {
    GameLayout layout;
    try {
        layout = GameLayout.locate(installRoot);
    } catch (IOException unavailable) {
        return unavailableProfileResult(installRoot, unavailable);
    }
    List<String> enabled;
    try {
        enabled = readEnabled(layout.enabledModsFile());
    } catch (IOException | RuntimeException unavailable) {
        return unavailableProfileResult(installRoot, unavailable);
    }
    JvmMemorySettings.Snapshot memory = JvmMemorySettings.inspect(layout.installRoot());
    Long heap = memory.available() && memory.maxHeapMiB() != null ? memory.maxHeapMiB().longValue() : null;
    return inspect(layout.modsDirectory(), enabled, null, heap);
}

    static Result inspect(Path installRoot, LaunchTarget target) throws IOException {
        GameLayout layout;
        try {
            layout = GameLayout.locate(installRoot);
        } catch (IOException unavailable) {
            return unavailableProfileResult(installRoot, unavailable);
        }
        List<String> enabled;
        try {
            enabled = readEnabled(layout.enabledModsFile());
        } catch (IOException | RuntimeException unavailable) {
            return unavailableProfileResult(installRoot, unavailable);
        }
        JvmMemorySettings.Snapshot memory = JvmMemorySettings.inspect(layout.installRoot(), target);
        Long heap = memory.available() && memory.maxHeapMiB() != null ? memory.maxHeapMiB().longValue() : null;
        return inspect(layout.modsDirectory(), enabled, null, heap);
    }

    private static Result unavailableProfileResult(Path installRoot, Exception failure) {
        Finding finding = new Finding(
                ReasonCode.ENABLED_MODS_UNAVAILABLE,
                Severity.WARNING,
                null,
                null,
                "Enabled-mod profile could not be established under "
                        + installRoot.toAbsolutePath().normalize()
                        + ": "
                        + failure.getMessage());
        return new Result(List.of(), List.of(finding), null, false, true);
    }

    private static List<String> readEnabled(Path enabledFile) throws IOException {
    Object root;
    try {
        root = new Parser(Files.readString(enabledFile, StandardCharsets.UTF_8)).parse();
    } catch (IllegalArgumentException malformed) {
        throw new IOException("enabled_mods.json is malformed: " + malformed.getMessage(), malformed);
    }
    if (!(root instanceof Map<?, ?> raw)) {
        throw new IOException("enabled_mods.json root must be an object");
    }
    Object declared = stringMap(raw).get("enabledMods");
    if (!(declared instanceof List<?> list)) {
        throw new IOException("enabled_mods.json must contain an enabledMods array");
    }
    List<String> enabled = new ArrayList<>();
    for (Object value : list) {
        if (!(value instanceof String id) || id.isBlank()) {
            throw new IOException("enabled_mods.json enabledMods entries must be non-blank strings");
        }
        enabled.add(id);
    }
    return List.copyOf(enabled);
}

    static Result inspect(Path modsDirectory, List<String> enabledMods, String gameVersion, Long maxHeapMiB)
        throws IOException {
    List<String> enabled = List.copyOf(new LinkedHashSet<>(enabledMods));
    Set<String> enabledSet = new LinkedHashSet<>(enabled);
    List<Finding> findings = new ArrayList<>();
    Map<String, List<Metadata>> providers = new LinkedHashMap<>();
    Set<String> partialReported = new LinkedHashSet<>();
    boolean evidenceComplete = true;

    if (Files.isDirectory(modsDirectory)) {
        try (var entries = Files.list(modsDirectory)) {
            for (Path directory : entries.filter(Files::isDirectory).sorted().toList()) {
                Path info = directory.resolve("mod_info.json");
                if (!Files.isRegularFile(info)) {
                    continue;
                }
                try {
                    Metadata metadata = parseMetadata(
                            directory, Files.readString(info, StandardCharsets.UTF_8));
                    providers.computeIfAbsent(metadata.id(), ignored -> new ArrayList<>()).add(metadata);
                } catch (IllegalArgumentException | IOException malformed) {
                    findings.add(new Finding(
                            ReasonCode.METADATA_INVALID,
                            Severity.WARNING,
                            directory.getFileName().toString(),
                            null,
                            "Unreadable or invalid mod_info.json in "
                                    + directory.getFileName() + ": " + malformed.getMessage()));
                    evidenceComplete = false;
                }
            }
        }
    }

    providers.values().forEach(list -> list.sort(
            Comparator.comparing(metadata -> metadata.directory().toString())));
    for (Map.Entry<String, List<Metadata>> entry : providers.entrySet()) {
        if (entry.getValue().size() > 1) {
            findings.add(new Finding(
                    ReasonCode.DUPLICATE_MOD_ID,
                    Severity.ERROR,
                    entry.getKey(),
                    entry.getKey(),
                    "Multiple installed mods declare id '" + entry.getKey() + "': "
                            + entry.getValue().stream()
                                    .map(value -> value.directory().getFileName().toString())
                                    .toList()));
        }
    }

    LinkedHashSet<String> proposedEnable = new LinkedHashSet<>();
    long largestDeclaredMemoryMiB = 0;
    boolean anyValidMemory = false;
    for (String modId : enabled) {
        List<Metadata> candidates = providers.get(modId);
        if (candidates == null || candidates.isEmpty()) {
            findings.add(new Finding(
                    ReasonCode.METADATA_ABSENT,
                    Severity.WARNING,
                    modId,
                    null,
                    "Enabled mod '" + modId + "' has no uniquely readable installed metadata"));
            evidenceComplete = false;
            continue;
        }
        if (candidates.size() != 1) {
            continue;
        }

        Metadata metadata = candidates.get(0);
        if (reportPartialMetadata(metadata, findings, partialReported)) {
            evidenceComplete = false;
        }
        if (metadata.requiredMemoryMb() != null) {
            if (metadata.requiredMemoryMb() <= 0) {
                findings.add(new Finding(
                        ReasonCode.MEMORY_REQUIREMENT_INVALID,
                        Severity.WARNING,
                        modId,
                        null,
                        "requiredMemoryMB must be positive; declared " + metadata.requiredMemoryMb()));
                evidenceComplete = false;
            } else {
                largestDeclaredMemoryMiB = Math.max(largestDeclaredMemoryMiB, metadata.requiredMemoryMb());
                anyValidMemory = true;
            }
        }

        if (metadata.gameVersion() != null && !metadata.gameVersion().isBlank()) {
            if (gameVersion == null || gameVersion.isBlank()) {
                findings.add(new Finding(
                        ReasonCode.GAME_VERSION_UNKNOWN,
                        Severity.INFO,
                        modId,
                        null,
                        "Mod declares game version " + metadata.gameVersion()
                                + ", but the installed game version was not established"));
                evidenceComplete = false;
            } else if (!sameGameVersion(metadata.gameVersion(), gameVersion)) {
                findings.add(new Finding(
                        ReasonCode.GAME_VERSION_MISMATCH,
                        Severity.WARNING,
                        modId,
                        null,
                        "Mod declares Starsector " + metadata.gameVersion()
                                + "; selected installation reports " + gameVersion));
            }
        }

        for (Dependency dependency : metadata.dependencies()) {
            List<Metadata> dependencyProviders = providers.get(dependency.id());
            if (dependency.optional()) {
                if (dependencyProviders == null || dependencyProviders.isEmpty()) {
                    findings.add(new Finding(
                            ReasonCode.OPTIONAL_DEPENDENCY,
                            Severity.INFO,
                            modId,
                            dependency.id(),
                            "Optional dependency '" + dependency.id() + "' is not installed"));
                }
                continue;
            }
            if (dependencyProviders == null || dependencyProviders.isEmpty()) {
                findings.add(new Finding(
                        ReasonCode.REQUIRED_DEPENDENCY_MISSING,
                        Severity.ERROR,
                        modId,
                        dependency.id(),
                        "Required dependency '" + dependency.id() + "' is not installed"));
                continue;
            }
            if (dependencyProviders.size() != 1) {
                continue;
            }

            Metadata provider = dependencyProviders.get(0);
            if (reportPartialMetadata(provider, findings, partialReported)) {
                evidenceComplete = false;
            }
            if (!enabledSet.contains(dependency.id())) {
                findings.add(new Finding(
                        ReasonCode.REQUIRED_DEPENDENCY_DISABLED,
                        Severity.ERROR,
                        modId,
                        dependency.id(),
                        "Required dependency '" + dependency.id() + "' is installed but disabled"));
                proposedEnable.add(dependency.id());
            }
            if (!compareVersion(modId, dependency, provider, findings)) {
                evidenceComplete = false;
            }
        }
    }

    if (anyValidMemory) {
        if (maxHeapMiB == null) {
            findings.add(new Finding(
                    ReasonCode.MEMORY_REQUIREMENT_UNKNOWN,
                    Severity.INFO,
                    null,
                    null,
                    "Enabled mods declare up to " + largestDeclaredMemoryMiB
                            + " MiB required memory; effective -Xmx was not established"));
            evidenceComplete = false;
        } else if (largestDeclaredMemoryMiB > maxHeapMiB) {
            findings.add(new Finding(
                    ReasonCode.MEMORY_REQUIREMENT_EXCEEDS_HEAP,
                    Severity.WARNING,
                    null,
                    null,
                    "Largest enabled-mod requiredMemoryMB is " + largestDeclaredMemoryMiB
                            + " MiB; effective -Xmx is " + maxHeapMiB + " MiB"));
        }
    }

    findings.sort(Comparator
            .comparing((Finding finding) -> finding.reason().code())
            .thenComparing(finding -> nullSafe(finding.modId()))
            .thenComparing(finding -> nullSafe(finding.subjectId()))
            .thenComparing(Finding::summary));

    ProfileChange change = null;
    if (!proposedEnable.isEmpty()) {
        List<String> additions = proposedEnable.stream().sorted().toList();
        List<String> after = new ArrayList<>(enabled);
        after.addAll(additions);
        change = new ProfileChange(enabled, after, additions);
    }
    return new Result(enabled, findings, change, evidenceComplete, true);
}

    private static Metadata parseMetadata(Path directory, String json) {
    Object root = new Parser(json).parse();
    if (!(root instanceof Map<?, ?> raw)) {
        throw new IllegalArgumentException("metadata root must be an object");
    }
    Map<String, Object> values = stringMap(raw);
    String id = requiredString(values, "id");
    String name = optionalString(values.get("name"));
    Version version = parseVersion(values.get("version"));
    String gameVersion = optionalString(values.get("gameVersion"));
    List<String> missingFields = new ArrayList<>();
    if (name == null || name.isBlank()) missingFields.add("name");
    if (version == null) missingFields.add("version");
    if (gameVersion == null || gameVersion.isBlank()) missingFields.add("gameVersion");
    Long memory = optionalLong(values.get("requiredMemoryMB"));
    List<Dependency> dependencies = new ArrayList<>();
    Object declared = values.get("dependencies");
    if (declared != null) {
        if (!(declared instanceof List<?> list)) {
            throw new IllegalArgumentException("dependencies must be an array");
        }
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> dependencyRaw)) {
                throw new IllegalArgumentException("dependency entries must be objects");
            }
            Map<String, Object> dependency = stringMap(dependencyRaw);
            dependencies.add(new Dependency(
                    requiredString(dependency, "id"),
                    optionalString(dependency.get("name")),
                    parseVersion(dependency.get("version")),
                    Boolean.TRUE.equals(dependency.get("optional"))));
        }
    }
    return new Metadata(
            directory,
            id,
            name,
            version,
            gameVersion,
            List.copyOf(missingFields),
            List.copyOf(dependencies),
            memory);
}

    private static boolean reportPartialMetadata(
            Metadata metadata, List<Finding> findings, Set<String> reported) {
        if (metadata.missingFields().isEmpty()) {
            return false;
        }
        if (reported.add(metadata.id())) {
            findings.add(new Finding(
                    ReasonCode.METADATA_PARTIAL,
                    Severity.WARNING,
                    metadata.id(),
                    null,
                    "Metadata is missing compatibility fields: "
                            + String.join(", ", metadata.missingFields())));
        }
        return true;
    }

    /** Returns false only when the declaration uses version semantics this pass cannot establish. */
    private static boolean compareVersion(
            String dependentId, Dependency dependency, Metadata provider, List<Finding> findings) {
        if (dependency.version() == null || provider.version() == null) {
            return true;
        }
        Version required = dependency.version();
        Version actual = provider.version();
        if (!required.hasNumericMajor() || !actual.hasNumericMajor()) {
            if (!required.raw().equals(actual.raw())) {
                findings.add(new Finding(
                        ReasonCode.DEPENDENCY_VERSION_ADVISORY,
                        Severity.WARNING,
                        dependentId,
                        dependency.id(),
                        "Dependency version declaration '" + required.raw()
                                + "' does not match installed '" + actual.raw()
                                + "'; unsupported range semantics remain advisory"));
                return false;
            }
            return true;
        }
        if (!required.major().equals(actual.major())) {
            findings.add(new Finding(
                    ReasonCode.DEPENDENCY_VERSION_MAJOR_MISMATCH,
                    Severity.ERROR,
                    dependentId,
                    dependency.id(),
                    "Dependency requires major version " + required.major()
                            + "; installed version is " + actual.raw()));
            return true;
        }
        if ((required.minor() != null && actual.minor() != null
                        && !required.minor().equals(actual.minor()))
                || (required.patch() != null && actual.patch() != null
                        && !required.patch().equals(actual.patch()))) {
            findings.add(new Finding(
                    ReasonCode.DEPENDENCY_VERSION_ADVISORY,
                    Severity.WARNING,
                    dependentId,
                    dependency.id(),
                    "Dependency declares version " + required.raw()
                            + "; installed version is " + actual.raw()));
        }
        return true;
    }

    private static Version parseVersion(Object value) {
        if (value == null) return null;
        if (value instanceof String text) {
            String trimmed = text.trim();
            String[] parts = trimmed.split("\\.");
            Integer major = numericPrefix(parts, 0);
            Integer minor = numericPrefix(parts, 1);
            Integer patch = numericPrefix(parts, 2);
            return new Version(trimmed, major, minor, patch);
        }
        if (value instanceof Number number) {
            return new Version(number.toString(), number.intValue(), null, null);
        }
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> map = stringMap(raw);
            Integer major = optionalInt(map.get("major"));
            if (major == null) throw new IllegalArgumentException("version object requires numeric major");
            Integer minor = optionalInt(map.get("minor"));
            Integer patch = optionalInt(map.get("patch"));
            String rendered = major + (minor == null ? "" : "." + minor) + (patch == null ? "" : "." + patch);
            return new Version(rendered, major, minor, patch);
        }
        throw new IllegalArgumentException("version must be a string, number, or object");
    }

    private static Integer numericPrefix(String[] parts, int index) {
        if (index >= parts.length) return null;
        String digits = parts[index].replaceFirst("^(\\d+).*$", "$1");
        if (!digits.matches("\\d+")) return null;
        try {
            return Integer.valueOf(digits);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

private static boolean sameGameVersion(String declared, String actual) {
        return canonicalGameVersion(declared).equals(canonicalGameVersion(actual));
    }

    private static String canonicalGameVersion(String version) {
        return version.trim().toLowerCase(Locale.ROOT).replace(" ", "");
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static Map<String, Object> stringMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("object keys must be strings");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static String requiredString(Map<String, Object> values, String key) {
        String value = optionalString(values.get(key));
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " must be a non-empty string");
        return value;
    }

    private static String optionalString(Object value) {
        return value instanceof String text ? text : null;
    }

    private static Long optionalLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        throw new IllegalArgumentException("requiredMemoryMB must be numeric");
    }

    private static Integer optionalInt(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.intValue();
        throw new IllegalArgumentException("version components must be numeric");
    }

    /** Parser for Starsector's JSON dialect: standard JSON plus #, //, and block comments and trailing commas. */
    private static final class Parser {
        private final String text;
        private int offset;

        Parser(String text) {
            this.text = text;
        }

        Object parse() {
            skip();
            Object value = value();
            skip();
            if (offset != text.length()) throw error("unexpected trailing content");
            return value;
        }

        private Object value() {
            skip();
            if (offset >= text.length()) throw error("expected value");
            return switch (text.charAt(offset)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skip();
            while (!consume('}')) {
                if (peek() != '"') throw error("expected object key");
                String key = string();
                skip();
                expect(':');
                map.put(key, value());
                skip();
                if (consume('}')) break;
                expect(',');
                skip();
                if (consume('}')) break;
            }
            return map;
        }

        private List<Object> array() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skip();
            while (!consume(']')) {
                list.add(value());
                skip();
                if (consume(']')) break;
                expect(',');
                skip();
                if (consume(']')) break;
            }
            return list;
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (offset < text.length()) {
                char c = text.charAt(offset++);
                if (c == '"') return out.toString();
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (offset >= text.length()) throw error("unterminated escape");
                char escaped = text.charAt(offset++);
                switch (escaped) {
                    case '"', '\\', '/' -> out.append(escaped);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (offset + 4 > text.length()) throw error("incomplete unicode escape");
                        out.append((char) Integer.parseInt(text.substring(offset, offset + 4), 16));
                        offset += 4;
                    }
                    default -> throw error("unsupported escape");
                }
            }
            throw error("unterminated string");
        }

        private Object number() {
            int start = offset;
            if (peek() == '-') offset++;
            while (Character.isDigit(peek())) offset++;
            if (consume('.')) while (Character.isDigit(peek())) offset++;
            if (offset == start) throw error("expected value");
            String raw = text.substring(start, offset);
            try {
                return raw.contains(".") ? Double.valueOf(raw) : Long.valueOf(raw);
            } catch (NumberFormatException malformed) {
                throw error("invalid number");
            }
        }

        private Object literal(String literal, Object value) {
            if (!text.startsWith(literal, offset)) throw error("invalid literal");
            offset += literal.length();
            return value;
        }

        private void skip() {
            while (true) {
                while (Character.isWhitespace(peek())) offset++;
                if (peek() == '#') {
                    lineComment(1);
                    continue;
                }
                if (peek() == '/' && next() == '/') {
                    lineComment(2);
                    continue;
                }
                if (peek() == '/' && next() == '*') {
                    offset += 2;
                    int end = text.indexOf("*/", offset);
                    if (end < 0) throw error("unterminated comment");
                    offset = end + 2;
                    continue;
                }
                return;
            }
        }

        private void lineComment(int prefix) {
            offset += prefix;
            while (offset < text.length() && text.charAt(offset) != '\n' && text.charAt(offset) != '\r') offset++;
        }

        private char peek() {
            return offset >= text.length() ? '\0' : text.charAt(offset);
        }

        private char next() {
            return offset + 1 >= text.length() ? '\0' : text.charAt(offset + 1);
        }

        private boolean consume(char expected) {
            if (peek() != expected) return false;
            offset++;
            return true;
        }

        private void expect(char expected) {
            skip();
            if (!consume(expected)) throw error("expected '" + expected + "'");
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at offset " + offset);
        }
    }
}
