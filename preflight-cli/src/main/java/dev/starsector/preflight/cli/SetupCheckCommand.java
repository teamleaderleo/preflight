package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.ResourceIndex;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Explicit read-only consumer for the shared setup-analysis providers. */
final class SetupCheckCommand {
    private static final String METADATA_PROVIDER = "mod-metadata";
    private static final String STATIC_PROVIDER = "static-links";
    private static final String BENIGN_INDEX_DIAGNOSTIC =
            "Excluded runtime-generated file from the resource index:";
    private static final int HUMAN_FINDING_LIMIT = 20;

    private SetupCheckCommand() {
    }

    static int execute(String[] args, int offset) throws Exception {
        Path game = null;
        boolean json = false;
        for (int index = offset; index < args.length; index++) {
            switch (args[index]) {
                case "--game" -> game = Path.of(requireValue(args, ++index, "--game"));
                case "--json" -> json = true;
                case "--help", "-h" -> {
                    System.out.println("Usage:");
                    System.out.println("  preflight analyze setup [--game <path>] [--json]");
                    System.out.println("  Runs the explicit deep setup check. It does not launch or modify Starsector.");
                    return 0;
                }
                default -> throw new IllegalArgumentException("Unknown analyze setup option: " + args[index]);
            }
        }

        Path installRoot = InstallRoot.resolve(game);
        if (installRoot == null) {
            System.err.println("Preflight could not locate Starsector. Run `doctor` or provide --game.");
            return 3;
        }

        SetupAnalysis.Result result = analyze(installRoot);
        if (json) {
            System.out.println(result.toJson());
        } else {
            System.out.print(render(result));
        }
        // Findings are analysis offered to the player, not a build gate imposed by the CLI.
        return 0;
    }

    static SetupAnalysis.Result analyze(Path installRoot) throws Exception {
        List<SetupAnalysis.Finding> findings = new ArrayList<>();
        List<String> unavailableProviders = new ArrayList<>();
        ModMetadataCheck.ConversionMode conversionMode = ModMetadataCheck.ConversionMode.UNKNOWN;

        try {
            ModMetadataCheck.Result metadata = ModMetadataCheck.check(installRoot);
            findings.addAll(metadata.findings());
            conversionMode = metadata.conversionMode();
        } catch (IOException unavailable) {
            unavailableProviders.add(METADATA_PROVIDER);
        }

        ResourceIndexBuilder.BuildResult build = ResourceIndexBuilder.build(installRoot);
        ResourceIndex index = build.index();
        String installationIdentity = installationIdentity(installRoot, index);

        if (!staticCoverageComplete(build)) {
            unavailableProviders.add(STATIC_PROVIDER);
        } else {
            try {
                StaticReferenceCheck.Result staticLinks =
                        StaticReferenceCheck.checkVariantHullLinks(index, conversionMode);
                findings.addAll(staticLinks.findings());
            } catch (IOException unavailable) {
                unavailableProviders.add(STATIC_PROVIDER);
            }
        }

        return SetupAnalysis.compose(
                installationIdentity,
                index.profileFingerprint(),
                findings,
                unavailableProviders);
    }

    /**
     * #812 can make blocking absence claims only over a complete resolved graph. ResourceIndexBuilder
     * currently exposes coverage detail as diagnostics, so this first consumer accepts only its one
     * intentional non-resource exclusion. Any other diagnostic makes the deep provider unavailable
     * rather than interpreting a partial graph as complete.
     */
    private static boolean staticCoverageComplete(ResourceIndexBuilder.BuildResult build) {
        boolean hasCore = build.index().roots().stream().anyMatch(ResourceIndex.Root::core);
        return hasCore && build.diagnostics().stream()
                .allMatch(diagnostic -> diagnostic.startsWith(BENIGN_INDEX_DIAGNOSTIC));
    }

    private static String installationIdentity(Path installRoot, ResourceIndex index) throws Exception {
        Path canonicalRoot = installRoot.toRealPath();
        try (ProfileIdentityContext context = ProfileIdentityContext.of(installRoot, index)) {
            String material = canonicalRoot + "\u0000" + context.gameJarSha256();
            return "install-v1:" + Hashes.sha256(material.getBytes(StandardCharsets.UTF_8));
        }
    }

    static Outcome outcome(SetupAnalysis.Result result) {
        if (result.count(SetupAnalysis.Severity.BLOCKING) > 0) {
            return Outcome.PROBLEM;
        }
        if (!result.unavailableProviders().isEmpty()
                || result.count(SetupAnalysis.Severity.UNKNOWN) > 0) {
            return Outcome.UNKNOWN;
        }
        if (result.count(SetupAnalysis.Severity.WARNING) > 0) {
            return Outcome.WARNING;
        }
        return Outcome.READY;
    }

    static String render(SetupAnalysis.Result result) {
        StringBuilder output = new StringBuilder();
        output.append("Setup check: ").append(outcome(result)).append('\n');
        output.append("Blocking: ").append(result.count(SetupAnalysis.Severity.BLOCKING))
                .append("  Warnings: ").append(result.count(SetupAnalysis.Severity.WARNING))
                .append("  Unknown: ").append(result.count(SetupAnalysis.Severity.UNKNOWN))
                .append("  Info: ").append(result.count(SetupAnalysis.Severity.INFO)).append('\n');
        output.append("Installation: ").append(terminalSafe(result.installationIdentity())).append('\n');
        output.append("Profile: ").append(terminalSafe(result.profileFingerprint())).append('\n');
        if (!result.unavailableProviders().isEmpty()) {
            output.append("Unavailable providers: ")
                    .append(terminalSafe(String.join(", ", result.unavailableProviders())))
                    .append('\n');
        }

        if (result.findings().isEmpty()) {
            output.append("No deterministic findings from the covered providers.\n");
        } else {
            output.append('\n');
            int shown = Math.min(HUMAN_FINDING_LIMIT, result.findings().size());
            for (SetupAnalysis.Finding finding : result.findings().subList(0, shown)) {
                output.append("[")
                        .append(finding.severity().name().toLowerCase(Locale.ROOT))
                        .append("] ")
                        .append(terminalSafe(finding.provider()))
                        .append('/')
                        .append(terminalSafe(finding.code()))
                        .append(" — ")
                        .append(terminalSafe(finding.summary()))
                        .append('\n');
            }
            if (result.findings().size() > shown) {
                output.append("... and ").append(result.findings().size() - shown).append(" more findings.\n");
            }
        }
        output.append("Nothing was changed.\n");
        return output.toString();
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }

    private static String terminalSafe(String text) {
        StringBuilder safe = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isISOControl(character)) {
                safe.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
            } else {
                safe.append(character);
            }
        }
        return safe.toString();
    }

    enum Outcome {
        READY,
        WARNING,
        UNKNOWN,
        PROBLEM
    }
}
