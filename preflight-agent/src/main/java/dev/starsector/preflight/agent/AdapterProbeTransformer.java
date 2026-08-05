package dev.starsector.preflight.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Objects;

/** Observes candidate classes and only delegates exact source-bound targets to a registered plan. */
final class AdapterProbeTransformer implements ClassFileTransformer {
    private static final String TEXTURE_LOADER = "com/fs/graphics/TextureLoader";
    private static final String JANINO_LOADER = "org/codehaus/janino/JavaSourceClassLoader";

    private final AdapterMode mode;
    private final AdapterTargetRegistry registry;
    private final List<String> candidatePrefixes;
    private final AdapterReport report;
    private final CodeLoaderSignatureReport codeLoaderReport;
    private final AudioDecoderSignatureReport audioDecoderReport;
    private final SoundLoaderContractReport soundLoaderReport;
    private final BytecodeShapeReport textureLoaderReport;
    private final BytecodeShapeReport janinoLoaderReport;

    AdapterProbeTransformer(
            AdapterMode mode,
            AdapterTargetRegistry registry,
            List<String> candidatePrefixes,
            AdapterReport report) {
        this(mode, registry, candidatePrefixes, report, null, null, null, null, null);
    }

    AdapterProbeTransformer(
            AdapterMode mode,
            AdapterTargetRegistry registry,
            List<String> candidatePrefixes,
            AdapterReport report,
            CodeLoaderSignatureReport codeLoaderReport) {
        this(mode, registry, candidatePrefixes, report, codeLoaderReport, null, null, null, null);
    }

    AdapterProbeTransformer(
            AdapterMode mode,
            AdapterTargetRegistry registry,
            List<String> candidatePrefixes,
            AdapterReport report,
            CodeLoaderSignatureReport codeLoaderReport,
            AudioDecoderSignatureReport audioDecoderReport) {
        this(mode, registry, candidatePrefixes, report, codeLoaderReport, audioDecoderReport, null, null, null);
    }

    AdapterProbeTransformer(
            AdapterMode mode,
            AdapterTargetRegistry registry,
            List<String> candidatePrefixes,
            AdapterReport report,
            CodeLoaderSignatureReport codeLoaderReport,
            AudioDecoderSignatureReport audioDecoderReport,
            SoundLoaderContractReport soundLoaderReport) {
        this(mode, registry, candidatePrefixes, report, codeLoaderReport, audioDecoderReport, soundLoaderReport, null, null);
    }

    AdapterProbeTransformer(
            AdapterMode mode,
            AdapterTargetRegistry registry,
            List<String> candidatePrefixes,
            AdapterReport report,
            CodeLoaderSignatureReport codeLoaderReport,
            AudioDecoderSignatureReport audioDecoderReport,
            SoundLoaderContractReport soundLoaderReport,
            BytecodeShapeReport textureLoaderReport,
            BytecodeShapeReport janinoLoaderReport) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.candidatePrefixes = List.copyOf(candidatePrefixes);
        this.report = Objects.requireNonNull(report, "report");
        this.codeLoaderReport = codeLoaderReport;
        this.audioDecoderReport = audioDecoderReport;
        this.soundLoaderReport = soundLoaderReport;
        this.textureLoaderReport = textureLoaderReport;
        this.janinoLoaderReport = janinoLoaderReport;
    }

    @Override
    public byte[] transform(
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer) {
        boolean adapterCandidate = adapterCandidate(className);
        boolean codeCandidate = codeLoaderReport != null && codeLoaderReport.interested(className);
        boolean audioCandidate = audioDecoderReport != null && audioDecoderReport.interested(className);
        boolean soundCandidate = soundLoaderReport != null && soundLoaderReport.interested(className);
        boolean textureCandidate = textureLoaderReport != null && TEXTURE_LOADER.equals(className);
        boolean janinoCandidate = janinoLoaderReport != null && JANINO_LOADER.equals(className);
        if (mode == AdapterMode.OFF
                || className == null
                || classfileBuffer == null
                || (!adapterCandidate
                && !codeCandidate
                && !audioCandidate
                && !soundCandidate
                && !textureCandidate
                && !janinoCandidate)) {
            return null;
        }
        try {
            ClassSignature signature = ClassSignature.parse(classfileBuffer);
            List<AdapterTarget> targets = registry.forClass(signature.internalName());
            boolean hashSource = codeCandidate
                    || audioCandidate
                    || soundCandidate
                    || textureCandidate
                    || janinoCandidate
                    || targets.stream().anyMatch(AdapterTarget::requiresSourceHash);
            AdapterSourceIdentity source = AdapterSourceIdentity.capture(loader, protectionDomain, hashSource);
            if (adapterCandidate) {
                report.observed(signature, source);
            }
            if (codeCandidate) {
                codeLoaderReport.observed(signature, source);
            }
            if (audioCandidate) {
                audioDecoderReport.observed(signature, source);
            }
            if (soundCandidate) {
                soundLoaderReport.observed(signature, source, classfileBuffer);
            }
            if (textureCandidate) {
                textureLoaderReport.observed(signature, source, classfileBuffer);
            }
            if (janinoCandidate) {
                janinoLoaderReport.observed(signature, source, classfileBuffer);
            }
            for (AdapterTarget target : targets) {
                AdapterTarget.Match match = target.match(signature, source);
                report.evaluation(target, match);
                if (!match.exact()) {
                    // Distinguish "another agent supplied this class" from "these bytes drifted".
                    // Both decline the target and both fail open; only one of them is something the
                    // user can act on, and only one of them means their cache is fine.
                    if (target.shadowed(signature, source)) {
                        report.shadowed(target, source);
                    }
                    continue;
                }
                if (mode != AdapterMode.ENABLED) {
                    continue;
                }
                if (!target.hasLiveSourceBinding()) {
                    report.unbound(target);
                    continue;
                }
                if (!AdapterTransformationRegistry.hasPlan(target.planId())) {
                    report.eligible(target);
                    continue;
                }
                long cacheStarted = System.nanoTime();
                byte[] cached = AdapterTransformationCache.lookup(target, signature);
                long cacheNanos = System.nanoTime() - cacheStarted;
                if (cached != null) {
                    AdapterInstallationEffects.replay(target, signature, cached);
                    report.transformedFromCache(target, cacheNanos,
                            classfileBuffer.length, cached.length);
                    return cached;
                }
                long transformationStarted = System.nanoTime();
                byte[] transformed = AdapterTransformationRegistry.transform(
                        target, signature, classfileBuffer);
                long transformationNanos = System.nanoTime() - transformationStarted;
                if (transformed != null) {
                    AdapterTransformationCache.record(target, signature, transformed);
                    report.transformed(target, transformationNanos,
                            classfileBuffer.length, transformed.length);
                    return transformed;
                }
                report.declined(target);
            }
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            if (adapterCandidate) {
                report.malformed(className, error);
            }
            if (codeCandidate) {
                codeLoaderReport.contained("Could not retain code-loader identity " + className, error);
            }
            if (audioCandidate) {
                audioDecoderReport.contained("Could not retain audio-decoder identity " + className, error);
            }
            if (soundCandidate) {
                soundLoaderReport.contained("Could not retain sound-loader contract " + className, error);
            }
        }
        return null;
    }

    private boolean adapterCandidate(String className) {
        if (className == null) return false;
        if (!registry.forClass(className).isEmpty()) {
            return true;
        }
        for (String prefix : candidatePrefixes) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
