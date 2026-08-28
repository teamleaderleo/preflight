package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Thread-safe bounded diagnostics for adapter probing and activation. */
final class AdapterReport {
    private static final int CANDIDATE_LIMIT = 500;
    private static final int RANKED_CANDIDATE_LIMIT = 50;
    private static final int DIAGNOSTIC_LIMIT = 200;
    private static final int EVALUATION_LIMIT = 200;
    private static final Comparator<Candidate> CANDIDATE_RANKING = Comparator
            .comparingInt(Candidate::relevanceScore)
            .reversed()
            .thenComparing(Candidate::className)
            .thenComparing(Candidate::sha256)
            .thenComparing(Candidate::normalizedSource)
            .thenComparing(Candidate::loaderClass)
            .thenComparing(Candidate::loaderName);

    private final AdapterMode mode;
    private final Path destination;
    private final Path targetFile;
    private final List<String> prefixes;
    private final Instant startedAt = Instant.now();
    private final Map<String, Candidate> candidates = new LinkedHashMap<>();
    private final List<String> diagnostics = new ArrayList<>();
    private final List<Evaluation> evaluations = new ArrayList<>();
    private boolean transformerInstalled;
    private boolean killSwitchActive;
    private int registryTargets;
    private final Map<String, Integer> registeredPlanTargets = new TreeMap<>();
    private long observedClasses;
    private long parsedClasses;
    private long malformedClasses;
    private long exactMatches;
    private long sourceBindingRejected;
    private long transformationEligible;
    private long transformationDeclined;
    private long transformationsApplied;
    private long transformationsFromCache;
    private long transformationNanos;
    private final List<TransformationTiming> transformationTimings = new ArrayList<>();
    private long shadowedTargets;
    private final Map<String, String> shadowingSources = new LinkedHashMap<>();
    private long containedFailures;
    private boolean candidateTruncated;
    private boolean diagnosticsTruncated;
    private boolean evaluationsTruncated;

    AdapterReport(AdapterMode mode, Path destination, Path targetFile, List<String> prefixes) {
        this.mode = mode;
        this.destination = destination.toAbsolutePath().normalize();
        this.targetFile = targetFile == null ? null : targetFile.toAbsolutePath().normalize();
        this.prefixes = List.copyOf(prefixes);
    }

    synchronized void transformerInstalled(AdapterTargetRegistry registry) {
        transformerInstalled = true;
        registryTargets = registry.targets().size();
        registeredPlanTargets.clear();
        for (AdapterTarget target : registry.targets()) {
            registeredPlanTargets.merge(target.planId(), 1, Integer::sum);
        }
    }

    synchronized void killSwitch(String detail) {
        killSwitchActive = true;
        diagnostic(detail);
    }

    synchronized void observed(ClassSignature signature, AdapterSourceIdentity source) {
        observedClasses++;
        parsedClasses++;
        String key = signature.internalName()
                + "@" + signature.sha256()
                + "@" + source.normalizedSource()
                + "@" + source.loaderClass()
                + "@" + source.loaderName();
        if (candidates.containsKey(key)) {
            return;
        }

        AdapterCandidateScorer.Score score = AdapterCandidateScorer.score(signature, source.codeSource());
        Candidate candidate = new Candidate(
                signature.internalName(),
                signature.sha256(),
                signature.majorVersion(),
                signature.methods().size(),
                source.codeSource(),
                source.normalizedSource(),
                source.sourceKind(),
                source.sourceSha256(),
                source.sourceHashProblem(),
                source.loaderClass(),
                source.loaderName(),
                score.value(),
                score.evidence(),
                score.relevantMethods(),
                score.methodsTruncated());
        if (candidates.size() < CANDIDATE_LIMIT) {
            candidates.put(key, candidate);
            return;
        }

        candidateTruncated = true;
        Map.Entry<String, Candidate> worst = candidates.entrySet().stream()
                .max((left, right) -> CANDIDATE_RANKING.compare(left.getValue(), right.getValue()))
                .orElseThrow();
        if (CANDIDATE_RANKING.compare(candidate, worst.getValue()) < 0) {
            candidates.remove(worst.getKey());
            candidates.put(key, candidate);
        }
    }

    synchronized void malformed(String className, Throwable error) {
        observedClasses++;
        malformedClasses++;
        containedFailures++;
        diagnostic("Could not parse " + className + ": " + message(error));
    }

    synchronized void evaluation(AdapterTarget target, AdapterTarget.Match match) {
        if (match.exact()) {
            exactMatches++;
        }
        if (evaluations.size() >= EVALUATION_LIMIT) {
            evaluationsTruncated = true;
        } else {
            evaluations.add(new Evaluation(
                    target.id(),
                    target.internalClassName(),
                    target.planId(),
                    match.exact(),
                    target.sourceKind(),
                    target.sourceSuffix(),
                    target.sourceSha256(),
                    target.loaderClass(),
                    target.loaderName(),
                    match.problems()));
        }
    }

    /**
     * Records that a target class was supplied by an archive other than the one it is pinned to.
     *
     * <p>Without this, the only trace a shadowed target leaves is "class SHA-256 differs", which
     * reads as a stale cache or a game update and sends a user to re-prepare artifacts that are
     * perfectly fine. Naming the archive that actually supplied the class turns an inscrutable
     * no-op into a one-line explanation.
     */
    synchronized void shadowed(AdapterTarget target, AdapterSourceIdentity source) {
        shadowedTargets++;
        String owner = source.normalizedSource().isBlank() ? "<unknown>" : source.normalizedSource();
        shadowingSources.putIfAbsent(target.internalClassName(), owner);
        diagnostic("Target " + target.id() + " (" + target.internalClassName() + ") was supplied by "
                + owner + " [" + source.sourceKind() + "], not by the pinned "
                + target.sourceSuffix() + "; another agent or mod owns this class, so Preflight left"
                + " it alone. This is not a stale cache and re-preparing will not change it.");
    }

    synchronized void unbound(AdapterTarget target) {
        sourceBindingRejected++;
        diagnostic("Exact target " + target.id() + " lacks required live source bindings: "
                + String.join(", ", target.missingLiveSourceBindings())
                + "; original bytes retained");
    }

    synchronized void eligible(AdapterTarget target) {
        transformationEligible++;
        diagnostic("Exact source-bound target " + target.id() + " matched, but plan " + target.planId()
                + " is unavailable for this session; original bytes retained");
    }

    synchronized void declined(AdapterTarget target) {
        transformationDeclined++;
        diagnostic("Transformation plan " + target.planId() + " declined " + target.internalClassName()
                + "; original bytes retained");
    }

    synchronized void transformed(
            AdapterTarget target, long elapsedNanos, int inputBytes, int outputBytes) {
        transformationsApplied++;
        transformationNanos += Math.max(0, elapsedNanos);
        transformationTimings.add(new TransformationTiming(
                target.id(), target.internalClassName(), target.planId(),
                Math.max(0, elapsedNanos), inputBytes, outputBytes, false));
        diagnostic("Applied transformation plan " + target.planId() + " to " + target.internalClassName());
    }

    synchronized void transformedFromCache(
            AdapterTarget target, long elapsedNanos, int inputBytes, int outputBytes) {
        transformationsApplied++;
        transformationsFromCache++;
        transformationTimings.add(new TransformationTiming(
                target.id(), target.internalClassName(), target.planId(),
                Math.max(0, elapsedNanos), inputBytes, outputBytes, true));
        diagnostic("Restored cached transformation plan " + target.planId()
                + " for " + target.internalClassName());
    }

    synchronized void contained(String detail, Throwable error) {
        containedFailures++;
        diagnostic(detail + ": " + message(error));
    }

    synchronized void diagnostic(String detail) {
        if (diagnostics.size() >= DIAGNOSTIC_LIMIT) {
            diagnosticsTruncated = true;
        } else {
            diagnostics.add(detail == null ? "" : detail);
        }
    }

    synchronized void write() throws IOException {
        Path parent = destination.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String json = toJson();
        Path temporary = destination.resolveSibling(destination.getFileName()
                + ".tmp-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
        boolean moved = false;
        try {
            Files.writeString(temporary, json + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private String toJson() {
        List<Candidate> orderedCandidates = candidates.values().stream()
                .sorted(Comparator.comparing(Candidate::className)
                        .thenComparing(Candidate::sha256)
                        .thenComparing(Candidate::normalizedSource)
                        .thenComparing(Candidate::loaderClass)
                        .thenComparing(Candidate::loaderName))
                .toList();
        List<Candidate> rankedCandidates = candidates.values().stream()
                .filter(candidate -> candidate.relevanceScore() > 0)
                .sorted(CANDIDATE_RANKING)
                .limit(RANKED_CANDIDATE_LIMIT)
                .toList();
        StringBuilder output = new StringBuilder(20_000).append('{');
        field(output, "generatedAt", Instant.now().toString());
        field(output, "startedAt", startedAt.toString());
        field(output, "mode", mode.name());
        field(output, "destination", destination.toString());
        nullableField(output, "targetFile", targetFile == null ? null : targetFile.toString());
        numberField(output, "jvmMaxHeapBytes", Runtime.getRuntime().maxMemory());
        arrayField(output, "candidatePrefixes", prefixes);
        booleanField(output, "transformerInstalled", transformerInstalled);
        booleanField(output, "killSwitchActive", killSwitchActive);
        numberField(output, "registryTargets", registryTargets);
        key(output, "registeredPlanTargets")
                .append(Json.value(registeredPlanTargets)).append(',');
        key(output, "planInventory")
                .append(Json.value(AdapterPlanCatalog.inventory(registeredPlanTargets))).append(',');
        numberField(output, "observedClasses", observedClasses);
        numberField(output, "parsedClasses", parsedClasses);
        numberField(output, "retainedCandidates", orderedCandidates.size());
        numberField(output, "rankedCandidatesCount", rankedCandidates.size());
        numberField(output, "malformedClasses", malformedClasses);
        numberField(output, "exactMatches", exactMatches);
        numberField(output, "sourceBindingRejected", sourceBindingRejected);
        numberField(output, "transformationEligible", transformationEligible);
        numberField(output, "transformationDeclined", transformationDeclined);
        numberField(output, "transformationsApplied", transformationsApplied);
        numberField(output, "transformationsFromCache", transformationsFromCache);
        numberField(output, "transformationNanos", transformationNanos);
        numberField(output, "shadowedTargets", shadowedTargets);
        arrayField(output, "shadowedBy", shadowingSources.entrySet().stream()
                .map(entry -> entry.getKey() + " <- " + entry.getValue())
                .toList());
        numberField(output, "containedFailures", containedFailures);
        booleanField(output, "liveTransformationPlansRegistered", AdapterTransformationRegistry.anyPlanCompiled());
        booleanField(output, "candidateTruncated", candidateTruncated);
        booleanField(output, "diagnosticsTruncated", diagnosticsTruncated);
        booleanField(output, "evaluationsTruncated", evaluationsTruncated);
        key(output, "planControl").append(Json.value(AdapterPlanControl.telemetry())).append(',');
        key(output, "textureCompatibility").append(Json.value(TextureCompatibilityRuntime.telemetry())).append(',');
        key(output, "texturePreparedPixels")
                .append(Json.value(TexturePreparedPixelRuntime.telemetry())).append(',');
        key(output, "texturePadding").append(Json.value(TexturePaddingRuntime.report())).append(',');
        key(output, "adapterTransformationCache")
                .append(Json.value(AdapterTransformationCache.telemetry())).append(',');
        key(output, "startupPhases").append(Json.value(StartupPhaseRuntime.telemetry())).append(',');
        key(output, "variantJsonCache").append(Json.value(VariantJsonCacheRuntime.telemetry())).append(',');
        key(output, "weaponJsonCache").append(Json.value(WeaponJsonCacheRuntime.telemetry())).append(',');
        key(output, "projectileJsonCache").append(Json.value(ProjectileJsonCacheRuntime.telemetry())).append(',');
        key(output, "hullJsonCache").append(Json.value(HullJsonCacheRuntime.telemetry())).append(',');
        key(output, "rulesCsvCache").append(Json.value(RulesCsvCacheRuntime.telemetry())).append(',');
        key(output, "ruleCommandClassCache")
                .append(Json.value(RuleCommandClassCacheRuntime.telemetry())).append(',');
        key(output, "rulesDuplicateIndex").append(Json.value(RulesDuplicateIndexRuntime.telemetry())).append(',');
        key(output, "ruleTokenCache").append(Json.value(RuleTokenCacheRuntime.telemetry())).append(',');
        key(output, "rulesRegexCache").append(Json.value(RulesRegexCacheRuntime.telemetry())).append(',');
        key(output, "resourcePriority").append(Json.value(ResourcePriorityRuntime.telemetry())).append(',');
        key(output, "saveDescriptorCompatibility")
                .append(Json.value(SaveDescriptorCompatibilityRuntime.telemetry())).append(',');
        key(output, "industryDemandSupplyMemo")
                .append(Json.value(IndustryDemandSupplyMemoRuntime.telemetry())).append(',');
        key(output, "codexLazyFleetMembers")
                .append(Json.value(CodexLazyFleetMemberRuntime.telemetry())).append(',');
        key(output, "indEvoSyntheticMarket")
                .append(Json.value(IndEvoSyntheticMarketRuntime.telemetry())).append(',');
        key(output, "mergedReadCache").append(Json.value(MergedReadCacheRuntime.telemetry())).append(',');
        key(output, "loadingUtilsReader")
                .append(Json.value(LoadingUtilsReaderRuntime.telemetry())).append(',');
        key(output, "sourceArchiveHashes")
                .append(Json.value(SourceArchiveHashes.telemetry())).append(',');
        key(output, "campaignEntityIndex").append(Json.value(EntityLookupRuntime.counters())).append(',');
        key(output, "fleetAiProfiler")
                .append(Json.value(FleetAiProfilerRuntime.telemetry())).append(',');
        key(output, "campaignRadarRender")
                .append(Json.value(RadarRenderRuntime.telemetry())).append(',');
        key(output, "deploymentIconCache")
                .append(Json.value(DeploymentIconCacheRuntime.telemetry())).append(',');
        key(output, "commodityEventModMemo")
                .append(Json.value(CommodityEventModMemoRuntime.telemetry())).append(',');
        key(output, "simOpponentSafety")
                .append(Json.value(SimOpponentSafetyRuntime.telemetry())).append(',');
        key(output, "sourceHintIsolation")
                .append(Json.value(SourceHintIsolationRuntime.telemetry())).append(',');
        // These three kept their counters to themselves until now, which meant a launch could not
        // be asked afterwards how much any of them actually did.
        key(output, "resourceProbeCache").append(Json.value(ResourceProbeRuntime.report())).append(',');
        key(output, "loadJsonMemo").append(Json.value(LoadJsonMemoRuntime.report())).append(',');
        key(output, "preparedAudio").append(Json.value(PreparedAudioRuntime.report())).append(',');
        key(output, "audioStreamSourceError")
                .append(Json.value(AudioStreamSourceErrorRuntime.telemetry())).append(',');
        key(output, "audioResourceFallback")
                .append(Json.value(AudioResourceFallbackRuntime.telemetry())).append(',');
        key(output, "audioMusicTransitions")
                .append(Json.value(AudioMusicTransitionRuntime.telemetry())).append(',');
        key(output, "ashLibVariantLookup")
                .append(Json.value(AshLibVariantLookupRuntime.telemetry())).append(',');
        key(output, "graphicsLibCompactReplay")
                .append(Json.value(GraphicsLibCompactReplayPlan.telemetry())).append(',');
        key(output, "janinoBytecodeCache")
                .append(Json.value(JaninoBytecodeCacheRuntime.telemetry())).append(',');
        key(output, "graphicsLibInsigniaManagerCache")
                .append(Json.value(GraphicsLibInsigniaManagerCacheRuntime.telemetry())).append(',');
        key(output, "graphicsLibHotSettings")
                .append(Json.value(GraphicsLibHotSettingsRuntime.telemetry())).append(',');
        key(output, "ratAbyssFactionFlag")
                .append(Json.value(RatAbyssFactionFlagPlan.telemetry())).append(',');
        key(output, "mnemonicSensorsEntityFilter")
                .append(Json.value(MnemonicSensorsEntityFilterPlan.telemetry())).append(',');
        key(output, "mutableStatTempAdvance")
                .append(Json.value(MutableStatTempAdvancePlan.telemetry())).append(',');
        key(output, "versionCheckResponseDedup")
                .append(Json.value(VersionCheckResponseDedupRuntime.telemetry())).append(',');
        key(output, "magicLibPaintjob")
                .append(Json.value(MagicLibPaintjobRuntime.telemetry())).append(',');
        key(output, "magicLibPaintjobNotification")
                .append(Json.value(MagicLibPaintjobNotificationRuntime.telemetry())).append(',');
        key(output, "magicLibPaintjobFrameSnapshot")
                .append(Json.value(MagicLibPaintjobSnapshotRuntime.telemetry())).append(',');
        key(output, "magicLibPaintjobLoad")
                .append(Json.value(MagicLibPaintjobLoadRuntime.telemetry())).append(',');
        key(output, "magicLibPaintjobCache")
                .append(Json.value(MagicLibPaintjobCacheRuntime.telemetry())).append(',');
        key(output, "lunaCampaignRendererSnapshot")
                .append(Json.value(LunaCampaignRendererSnapshotRuntime.telemetry())).append(',');
        key(output, "contrailRenderScratch")
                .append(Json.value(ContrailRenderScratchRuntime.telemetry())).append(',');
        key(output, "fontWrapAllocation")
                .append(Json.value(FontWrapAllocationRuntime.telemetry())).append(',');
        key(output, "stelnetMarketUpdater")
                .append(Json.value(StelnetMarketUpdaterRuntime.telemetry())).append(',');
        key(output, "logisticsNotificationsFuel")
                .append(Json.value(LogisticsNotificationsFuelRuntime.telemetry())).append(',');
        key(output, "macMemoryWarning")
                .append(Json.value(MacMemoryWarningRuntime.telemetry())).append(',');
        key(output, "combatRuntimeIntegrity")
                .append(Json.value(CombatRuntimeIntegrityRuntime.telemetry())).append(',');
        key(output, "collisionQuerySet")
                .append(Json.value(CollisionQuerySet.telemetry())).append(',');
        key(output, "combatListenerRangeSnapshot")
                .append(Json.value(CombatListenerRangeSnapshotRuntime.telemetry())).append(',');
        key(output, FrameTimeTelemetry.REPORT)
                .append(Json.value(FrameTimeRuntime.telemetry())).append(',');
        key(output, "runtimeSemanticState")
                .append(Json.value(RuntimeSemanticState.telemetry())).append(',');
        key(output, "consoleCombatFixture")
                .append(Json.value(ConsoleCombatFixtureRuntime.telemetry())).append(',');
        key(output, "combatStressFixture")
                .append(Json.value(CombatStressFixtureRuntime.telemetry())).append(',');
        key(output, "campaignCallTimes")
                .append(Json.value(CampaignCallTimeRuntime.telemetry())).append(',');
        key(output, "campaignEngineTimes")
                .append(Json.value(CampaignEngineTimeRuntime.telemetry())).append(',');
        key(output, "campaignLocationEconomyTimes")
                .append(Json.value(CampaignLocationEconomyTimeRuntime.telemetry())).append(',');
        key(output, "campaignMarketFleetTimes")
                .append(Json.value(CampaignMarketFleetTimeRuntime.telemetry())).append(',');
        key(output, "fleetAiModuleTimes")
                .append(Json.value(FleetAiModuleTimeRuntime.telemetry())).append(',');
        key(output, "tacticalFleetAiTimes")
                .append(Json.value(TacticalFleetAiTimeRuntime.telemetry())).append(',');
        key(output, "campaignEntityMaintenance")
                .append(Json.value(CampaignEntityMaintenanceRuntime.telemetry())).append(',');

        key(output, "transformationTimings").append('[');
        List<TransformationTiming> orderedTimings = transformationTimings.stream()
                .sorted(Comparator.comparingLong(TransformationTiming::elapsedNanos).reversed())
                .toList();
        for (int i = 0; i < orderedTimings.size(); i++) {
            if (i > 0) output.append(',');
            TransformationTiming timing = orderedTimings.get(i);
            output.append('{');
            field(output, "targetId", timing.targetId());
            field(output, "className", timing.className());
            field(output, "planId", timing.planId());
            numberField(output, "elapsedNanos", timing.elapsedNanos());
            numberField(output, "inputBytes", timing.inputBytes());
            numberField(output, "outputBytes", timing.outputBytes());
            booleanField(output, "cacheHit", timing.cacheHit());
            trimComma(output).append('}');
        }
        output.append("],");

        key(output, "rankedCandidates").append('[');
        for (int i = 0; i < rankedCandidates.size(); i++) {
            if (i > 0) output.append(',');
            writeRankedCandidate(output, rankedCandidates.get(i));
        }
        output.append("],");

        key(output, "candidates").append('[');
        for (int i = 0; i < orderedCandidates.size(); i++) {
            if (i > 0) output.append(',');
            writeCandidateSummary(output, orderedCandidates.get(i));
        }
        output.append("],");

        key(output, "evaluations").append('[');
        for (int i = 0; i < evaluations.size(); i++) {
            if (i > 0) output.append(',');
            Evaluation evaluation = evaluations.get(i);
            output.append('{');
            field(output, "targetId", evaluation.targetId());
            field(output, "className", evaluation.className());
            field(output, "planId", evaluation.planId());
            booleanField(output, "exact", evaluation.exact());
            nullableField(output, "requiredSourceKind", emptyToNull(evaluation.sourceKind()));
            nullableField(output, "requiredSourceSuffix", emptyToNull(evaluation.sourceSuffix()));
            nullableField(output, "requiredSourceSha256", emptyToNull(evaluation.sourceSha256()));
            nullableField(output, "requiredLoaderClass", emptyToNull(evaluation.loaderClass()));
            nullableField(output, "requiredLoaderName", emptyToNull(evaluation.loaderName()));
            arrayField(output, "problems", evaluation.problems());
            trimComma(output).append('}');
        }
        output.append("],");
        arrayField(output, "diagnostics", diagnostics);
        return trimComma(output).append('}').toString();
    }

    private static void writeCandidateSummary(StringBuilder output, Candidate candidate) {
        output.append('{');
        field(output, "className", candidate.className());
        field(output, "sha256", candidate.sha256());
        numberField(output, "majorVersion", candidate.majorVersion());
        numberField(output, "methodCount", candidate.methodCount());
        writeSourceIdentity(output, candidate);
        numberField(output, "relevanceScore", candidate.relevanceScore());
        trimComma(output).append('}');
    }

    private static void writeRankedCandidate(StringBuilder output, Candidate candidate) {
        output.append('{');
        field(output, "className", candidate.className());
        field(output, "sha256", candidate.sha256());
        numberField(output, "majorVersion", candidate.majorVersion());
        numberField(output, "methodCount", candidate.methodCount());
        writeSourceIdentity(output, candidate);
        numberField(output, "relevanceScore", candidate.relevanceScore());
        arrayField(output, "evidence", candidate.evidence());
        booleanField(output, "methodsTruncated", candidate.methodsTruncated());
        key(output, "relevantMethods").append('[');
        for (int i = 0; i < candidate.relevantMethods().size(); i++) {
            if (i > 0) output.append(',');
            AdapterCandidateScorer.RelevantMethod method = candidate.relevantMethods().get(i);
            output.append('{');
            field(output, "name", method.name());
            field(output, "descriptor", method.descriptor());
            numberField(output, "access", method.access());
            numberField(output, "score", method.score());
            arrayField(output, "evidence", method.evidence());
            trimComma(output).append('}');
        }
        output.append("],");
        trimComma(output).append('}');
    }

    private static void writeSourceIdentity(StringBuilder output, Candidate candidate) {
        nullableField(output, "codeSource", emptyToNull(candidate.codeSource()));
        nullableField(output, "normalizedSource", emptyToNull(candidate.normalizedSource()));
        field(output, "sourceKind", candidate.sourceKind());
        nullableField(output, "sourceSha256", emptyToNull(candidate.sourceSha256()));
        nullableField(output, "sourceHashProblem", emptyToNull(candidate.sourceHashProblem()));
        field(output, "loaderClass", candidate.loaderClass());
        nullableField(output, "loaderName", emptyToNull(candidate.loaderName()));
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static StringBuilder key(StringBuilder output, String name) {
        return output.append(quote(name)).append(':');
    }

    private static void field(StringBuilder output, String name, String value) {
        key(output, name).append(quote(value)).append(',');
    }

    private static void nullableField(StringBuilder output, String name, String value) {
        key(output, name).append(value == null ? "null" : quote(value)).append(',');
    }

    private static void numberField(StringBuilder output, String name, long value) {
        key(output, name).append(value).append(',');
    }

    private static void booleanField(StringBuilder output, String name, boolean value) {
        key(output, name).append(value).append(',');
    }

    private static void arrayField(StringBuilder output, String name, List<String> values) {
        key(output, name).append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) output.append(',');
            output.append(quote(values.get(i)));
        }
        output.append("],");
    }

    private static StringBuilder trimComma(StringBuilder output) {
        if (!output.isEmpty() && output.charAt(output.length() - 1) == ',') {
            output.setLength(output.length() - 1);
        }
        return output;
    }

    private static String quote(String value) {
        String text = value == null ? "" : value;
        StringBuilder output = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (c < 0x20) output.append(String.format("\\u%04x", (int) c));
                    else output.append(c);
                }
            }
        }
        return output.append('"').toString();
    }

    private static String message(Throwable error) {
        String text = error.getMessage();
        return error.getClass().getSimpleName() + (text == null || text.isBlank() ? "" : ": " + text);
    }

    private record Candidate(
            String className,
            String sha256,
            int majorVersion,
            int methodCount,
            String codeSource,
            String normalizedSource,
            String sourceKind,
            String sourceSha256,
            String sourceHashProblem,
            String loaderClass,
            String loaderName,
            int relevanceScore,
            List<String> evidence,
            List<AdapterCandidateScorer.RelevantMethod> relevantMethods,
            boolean methodsTruncated) {
        private Candidate {
            evidence = List.copyOf(evidence);
            relevantMethods = List.copyOf(relevantMethods);
        }
    }

    private record Evaluation(
            String targetId,
            String className,
            String planId,
            boolean exact,
            String sourceKind,
            String sourceSuffix,
            String sourceSha256,
            String loaderClass,
            String loaderName,
            List<String> problems) {
        private Evaluation {
            problems = List.copyOf(problems);
        }
    }

    private record TransformationTiming(
            String targetId,
            String className,
            String planId,
            long elapsedNanos,
            int inputBytes,
            int outputBytes,
            boolean cacheHit) {
    }
}
