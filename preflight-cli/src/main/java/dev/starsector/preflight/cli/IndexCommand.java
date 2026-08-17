package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceIndexIO;
import dev.starsector.preflight.core.ResourceIndexValidator;
import dev.starsector.preflight.core.ResourceProviderComparison;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class IndexCommand {
    private IndexCommand() {
    }

    static int execute(String[] args, int offset) throws Exception {
        if (offset >= args.length) {
            throw new IllegalArgumentException("Expected: index <build|inspect|query|validate> ...");
        }
        return switch (args[offset]) {
            case "build" -> build(parseBuild(args, offset + 1));
            case "inspect" -> inspect(requirePath(args, offset + 1, "index inspect <index.spfi>"));
            case "query" -> query(parseQuery(args, offset + 1));
            case "validate" -> validate(requirePath(args, offset + 1, "index validate <index.spfi>"));
            default -> throw new IllegalArgumentException("Unknown index command: " + args[offset]);
        };
    }

    private static int build(BuildOptions options) throws Exception {
        DiscoveryResult discovery = StarsectorDiscovery.discover(
                Platform.current(),
                Path.of(System.getProperty("user.home")),
                Path.of(System.getProperty("user.dir")),
                System.getenv(),
                options.game(),
                options.launcher());
        LaunchTarget target = discovery.selected();
        if (target == null) {
            System.err.println("Preflight could not locate Starsector. Run `doctor` or provide --game.");
            return 3;
        }

        System.out.println("Preflight is indexing resource providers...");
        ResourceIndexBuilder.BuildResult result = ResourceIndexBuilder.build(target.installRoot());
        ResourceIndex index = result.index();
        Path output = options.output() == null
                ? defaultOutput(PreflightHome.current(), index)
                : options.output().toAbsolutePath().normalize();
        ResourceIndexIO.write(output, index);

        Map<String, Object> summary = summary(index, output);
        summary.put("buildDurationMs", result.durationMillis());
        summary.put("diagnostics", result.diagnostics());
        System.out.println(Json.object(summary));
        return 0;
    }

    static Path defaultOutput(PreflightHome home, ResourceIndex index) {
        return ResourceIndexIO.directory(home.cache())
                .resolve(index.profileFingerprint() + ".spfi")
                .toAbsolutePath()
                .normalize();
    }

    private static int inspect(Path indexFile) throws IOException {
        ResourceIndex index = ResourceIndexIO.read(indexFile);
        Map<String, Object> output = summary(index, indexFile.toAbsolutePath().normalize());
        long comparisonStarted = System.nanoTime();
        ResourceProviderComparison.Result comparison = ResourceProviderComparison.analyze(
                index, ResourceProviderContentIdentity.direct(index));
        Map<String, Object> providerOverlaps = new LinkedHashMap<>(comparison.toPublicMap());
        providerOverlaps.put("comparisonDurationMs", (System.nanoTime() - comparisonStarted) / 1_000_000.0);
        providerOverlaps.put("identityAuthority", "sha-256-of-contained-current-provider-bytes");
        providerOverlaps.put("winnerAuthority", "resource-index-provider-order-last-wins");
        output.put("providerOverlaps", providerOverlaps);
        System.out.println(Json.object(output));
        return 0;
    }

    private static int query(QueryOptions options) throws IOException {
        ResourceIndex index = ResourceIndexIO.read(options.index());
        List<ResourceIndex.Provider> providers = index.providers(options.logicalPath());
        if (providers.isEmpty()) {
            System.out.println(Json.object(Map.of(
                    "path", ResourceIndex.normalizeLogicalPath(options.logicalPath()),
                    "present", false,
                    "providers", List.of())));
            return 4;
        }

        List<ResourceIndex.Provider> selected = options.all()
                ? providers
                : List.of(providers.get(providers.size() - 1));
        List<Map<String, Object>> outputProviders = new ArrayList<>();
        for (ResourceIndex.Provider provider : selected) {
            ResourceIndex.Root root = index.roots().get(provider.rootIndex());
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("rootId", root.id());
            value.put("root", root.path());
            value.put("core", root.core());
            value.put("relativePath", provider.relativePath());
            value.put("file", index.resolve(provider));
            value.put("size", provider.size());
            value.put("modifiedMillis", provider.modifiedMillis());
            outputProviders.add(value);
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("path", ResourceIndex.normalizeLogicalPath(options.logicalPath()));
        output.put("present", true);
        output.put("mode", options.all() ? "all" : "winner");
        output.put("providers", outputProviders);
        System.out.println(Json.object(output));
        return 0;
    }

    private static int validate(Path indexFile) throws IOException {
        ResourceIndex index = ResourceIndexIO.read(indexFile);
        ResourceIndexValidator.Result result = ResourceIndexValidator.validate(index);
        List<Map<String, Object>> problems = result.problems().stream().map(problem -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("kind", problem.kind());
            value.put("rootId", problem.rootId());
            value.put("logicalPath", problem.logicalPath());
            value.put("relativePath", problem.relativePath());
            value.put("expected", problem.expected());
            value.put("actual", problem.actual());
            return value;
        }).toList();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("file", indexFile.toAbsolutePath().normalize());
        output.put("valid", result.valid());
        output.put("checkedProviders", result.checkedProviders());
        output.put("invalidProviders", result.invalidProviders());
        output.put("truncated", result.truncated());
        output.put("problems", problems);
        System.out.println(Json.object(output));
        return result.valid() ? 0 : 5;
    }

    private static Map<String, Object> summary(ResourceIndex index, Path file) throws IOException {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("file", file);
        output.put("fileBytes", Files.isRegularFile(file) ? Files.size(file) : null);
        output.put("formatVersion", ResourceIndex.FORMAT_VERSION);
        output.put("profileFingerprint", index.profileFingerprint());
        output.put("rootCount", index.roots().size());
        output.put("entryCount", index.entryCount());
        output.put("providerCount", index.providerCount());
        output.put("roots", index.roots().stream().map(root -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", root.id());
            value.put("path", root.path());
            value.put("core", root.core());
            return value;
        }).toList());
        return output;
    }

    private static BuildOptions parseBuild(String[] args, int offset) {
        Path game = null;
        Path launcher = null;
        Path output = null;
        for (int i = offset; i < args.length; i++) {
            switch (args[i]) {
                case "--game" -> game = Path.of(requireValue(args, ++i, "--game"));
                case "--launcher" -> launcher = Path.of(requireValue(args, ++i, "--launcher"));
                case "--output" -> output = Path.of(requireValue(args, ++i, "--output"));
                default -> throw new IllegalArgumentException("Unknown index build option: " + args[i]);
            }
        }
        return new BuildOptions(game, launcher, output);
    }

    private static QueryOptions parseQuery(String[] args, int offset) {
        if (offset + 1 >= args.length) {
            throw new IllegalArgumentException("Expected: index query <index.spfi> <logical-path> [--all]");
        }
        Path index = Path.of(args[offset]);
        String logicalPath = args[offset + 1];
        boolean all = false;
        for (int i = offset + 2; i < args.length; i++) {
            if (args[i].equals("--all")) {
                all = true;
            } else {
                throw new IllegalArgumentException("Unknown index query option: " + args[i]);
            }
        }
        return new QueryOptions(index, logicalPath, all);
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

    private record BuildOptions(Path game, Path launcher, Path output) {
    }

    private record QueryOptions(Path index, String logicalPath, boolean all) {
    }
}
