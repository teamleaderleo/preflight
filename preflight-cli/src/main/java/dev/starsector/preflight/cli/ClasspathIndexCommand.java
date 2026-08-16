package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.ClasspathProfileIndex;
import dev.starsector.preflight.core.ClasspathProfileIndexIO;
import dev.starsector.preflight.core.JarArchiveIndex;
import dev.starsector.preflight.core.JarArchiveIndexIO;
import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ClasspathIndexCommand {
    private ClasspathIndexCommand() {
    }

    static int execute(String[] args, int offset) throws Exception {
        if (offset >= args.length) {
            throw new IllegalArgumentException("Expected: classpath index <build|inspect|query|validate> ...");
        }
        return switch (args[offset]) {
            case "build" -> build(parseBuild(args, offset + 1));
            case "inspect" -> inspect(requirePath(args, offset + 1, "classpath index inspect <profile.spfc>"));
            case "query" -> query(parseQuery(args, offset + 1));
            case "validate" -> validate(parseValidate(args, offset + 1));
            default -> throw new IllegalArgumentException("Unknown classpath index command: " + args[offset]);
        };
    }

    private static int build(BuildOptions options) throws Exception {
        LaunchTarget target = discover(options.game(), options.launcher());
        if (target == null) {
            return 3;
        }
        Path cache = options.cacheDirectory() == null ? defaultCacheDirectory() : options.cacheDirectory();
        ClasspathIndexBuilder.Result result = ClasspathIndexBuilder.build(target.installRoot(), cache);
        Map<String, Object> values = summary(result.profile(), result.profilePath(), cache);
        values.put("profileHit", result.profileHit());
        values.put("profileWritten", result.profileWritten());
        values.put("archiveHits", result.archiveHits());
        values.put("archiveBuilds", result.archiveBuilds());
        values.put("quarantinedIndexes", result.quarantinedIndexes());
        values.put("failedArchives", result.failedArchives());
        values.put("indexedEntries", result.indexedEntries());
        values.put("diagnostics", result.diagnostics());
        values.put("durationMs", result.durationMillis());
        System.out.println(Json.object(values));
        return 0;
    }

    private static int inspect(Path profileFile) throws IOException {
        ClasspathProfileIndex profile = ClasspathProfileIndexIO.read(profileFile);
        System.out.println(Json.object(summary(
                profile,
                profileFile.toAbsolutePath().normalize(),
                inferCacheDirectory(profileFile))));
        return 0;
    }

    private static int query(QueryOptions options) throws IOException {
        ClasspathProfileIndex profile = ClasspathProfileIndexIO.read(options.profile());
        List<Integer> providerIndexes = profile.providerIndexes(options.entryName());
        if (providerIndexes.isEmpty()) {
            System.out.println(Json.object(Map.of(
                    "entry", JarArchiveIndex.normalizeEntryName(options.entryName()),
                    "present", false,
                    "providers", List.of())));
            return 4;
        }

        List<Integer> selected = options.all()
                ? providerIndexes
                : List.of(providerIndexes.get(providerIndexes.size() - 1));
        Path cache = options.cacheDirectory() == null
                ? inferCacheDirectory(options.profile())
                : options.cacheDirectory().toAbsolutePath().normalize();
        List<Map<String, Object>> providers = new ArrayList<>();
        for (int index : selected) {
            ClasspathProfileIndex.Archive archive = profile.archives().get(index);
            Map<String, Object> value = archiveMap(archive, cache);
            Path archiveIndex = cache.resolve(archive.archiveIndexRelativePath()).normalize();
            try {
                JarArchiveIndex inventory = JarArchiveIndexIO.read(archiveIndex);
                value.put("entryMetadata", inventory.entry(options.entryName()).map(entry -> {
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("uncompressedBytes", entry.uncompressedBytes());
                    metadata.put("compressedBytes", entry.compressedBytes());
                    metadata.put("crc32", entry.crc32());
                    metadata.put("compressionMethod", entry.compressionMethod());
                    metadata.put("classEntry", entry.classEntry());
                    metadata.put("className", entry.className().orElse(null));
                    return metadata;
                }).orElse(null));
            } catch (IOException error) {
                value.put("archiveIndexError", error.getMessage());
            }
            providers.add(value);
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("entry", JarArchiveIndex.normalizeEntryName(options.entryName()));
        output.put("present", true);
        output.put("mode", options.all() ? "all" : "winner");
        output.put("providers", providers);
        System.out.println(Json.object(output));
        return 0;
    }

    private static int validate(ValidateOptions options) throws IOException {
        ClasspathProfileIndex profile = ClasspathProfileIndexIO.read(options.profile());
        Path cache = options.cacheDirectory() == null
                ? inferCacheDirectory(options.profile())
                : options.cacheDirectory().toAbsolutePath().normalize();
        ClasspathIndexBuilder.Validation validation = ClasspathIndexBuilder.validate(profile, cache, options.deep());
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("profile", options.profile().toAbsolutePath().normalize());
        output.put("cacheDirectory", cache);
        output.put("valid", validation.valid());
        output.put("deep", validation.deep());
        output.put("checkedArchives", validation.checkedArchives());
        output.put("checkedEntries", validation.checkedEntries());
        output.put("problems", validation.problems());
        System.out.println(Json.object(output));
        return validation.valid() ? 0 : 5;
    }

    private static Map<String, Object> summary(
            ClasspathProfileIndex profile,
            Path profileFile,
            Path cacheDirectory) throws IOException {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("profile", profileFile);
        output.put("profileBytes", Files.isRegularFile(profileFile) ? Files.size(profileFile) : null);
        output.put("cacheDirectory", cacheDirectory.toAbsolutePath().normalize());
        output.put("formatVersion", ClasspathProfileIndex.FORMAT_VERSION);
        output.put("profileFingerprint", profile.profileFingerprint());
        output.put("archiveCount", profile.archives().size());
        output.put("entryCount", profile.entryCount());
        output.put("providerCount", profile.providerCount());
        output.put("archives", profile.archives().stream()
                .map(archive -> archiveMap(archive, cacheDirectory))
                .toList());
        return output;
    }

    private static Map<String, Object> archiveMap(
            ClasspathProfileIndex.Archive archive,
            Path cacheDirectory) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("modId", archive.modId());
        value.put("relativePath", archive.relativePath());
        value.put("physicalPath", archive.physicalPath());
        value.put("sourceSha256", archive.sourceSha256());
        value.put("sourceBytes", archive.sourceBytes());
        value.put("modifiedMillis", archive.modifiedMillis());
        value.put("declared", archive.declared());
        value.put("archiveIndex", cacheDirectory.toAbsolutePath().normalize()
                .resolve(archive.archiveIndexRelativePath()).normalize());
        return value;
    }

    private static LaunchTarget discover(Path game, Path launcher) throws IOException {
        DiscoveryResult discovery = StarsectorDiscovery.discover(
                Platform.current(),
                Path.of(System.getProperty("user.home")),
                Path.of(System.getProperty("user.dir")),
                System.getenv(),
                game,
                launcher);
        LaunchTarget target = discovery.selected();
        if (target == null) {
            System.err.println("Preflight could not locate Starsector. Run `doctor` or provide --game.");
        }
        return target;
    }

    private static BuildOptions parseBuild(String[] args, int offset) {
        Path game = null;
        Path launcher = null;
        Path cache = null;
        for (int i = offset; i < args.length; i++) {
            switch (args[i]) {
                case "--game" -> game = Path.of(requireValue(args, ++i, "--game"));
                case "--launcher" -> launcher = Path.of(requireValue(args, ++i, "--launcher"));
                case "--cache-dir" -> cache = Path.of(requireValue(args, ++i, "--cache-dir"));
                default -> throw new IllegalArgumentException("Unknown classpath index build option: " + args[i]);
            }
        }
        return new BuildOptions(game, launcher, cache);
    }

    private static QueryOptions parseQuery(String[] args, int offset) {
        if (offset + 1 >= args.length) {
            throw new IllegalArgumentException(
                    "Expected: classpath index query <profile.spfc> <entry-name> [--all] [--cache-dir <path>]");
        }
        Path profile = Path.of(args[offset]);
        String entry = args[offset + 1];
        boolean all = false;
        Path cache = null;
        for (int i = offset + 2; i < args.length; i++) {
            switch (args[i]) {
                case "--all" -> all = true;
                case "--cache-dir" -> cache = Path.of(requireValue(args, ++i, "--cache-dir"));
                default -> throw new IllegalArgumentException("Unknown classpath index query option: " + args[i]);
            }
        }
        return new QueryOptions(profile, entry, all, cache);
    }

    private static ValidateOptions parseValidate(String[] args, int offset) {
        if (offset >= args.length) {
            throw new IllegalArgumentException(
                    "Expected: classpath index validate <profile.spfc> [--cache-dir <path>] [--deep]");
        }
        Path profile = Path.of(args[offset]);
        Path cache = null;
        boolean deep = false;
        for (int i = offset + 1; i < args.length; i++) {
            switch (args[i]) {
                case "--cache-dir" -> cache = Path.of(requireValue(args, ++i, "--cache-dir"));
                case "--deep" -> deep = true;
                default -> throw new IllegalArgumentException("Unknown classpath index validate option: " + args[i]);
            }
        }
        return new ValidateOptions(profile, cache, deep);
    }

    private static Path requirePath(String[] args, int offset, String usage) {
        if (offset >= args.length || offset + 1 != args.length) {
            throw new IllegalArgumentException("Expected: " + usage);
        }
        return Path.of(args[offset]);
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }

    private static Path defaultCacheDirectory() {
        return PreflightHome.current().cache();
    }

    private static Path inferCacheDirectory(Path profile) {
        Path absolute = profile.toAbsolutePath().normalize();
        Path profiles = absolute.getParent();
        Path classpath = profiles == null ? null : profiles.getParent();
        Path cache = classpath == null ? null : classpath.getParent();
        if (profiles != null
                && classpath != null
                && cache != null
                && "profiles".equals(profiles.getFileName().toString())
                && "classpath".equals(classpath.getFileName().toString())) {
            return cache;
        }
        return defaultCacheDirectory();
    }

    private record BuildOptions(Path game, Path launcher, Path cacheDirectory) {
    }

    private record QueryOptions(Path profile, String entryName, boolean all, Path cacheDirectory) {
    }

    private record ValidateOptions(Path profile, Path cacheDirectory, boolean deep) {
    }
}
