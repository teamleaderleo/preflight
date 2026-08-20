package dev.starsector.preflight.cli;

import dev.starsector.preflight.agent.AdapterMode;
import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.PreparedAudioCache;
import dev.starsector.preflight.core.PreparedAudioManifest;
import dev.starsector.preflight.core.PreparedAudioManifestIO;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.SpecStoreCacheDirectories;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Resolves every content-addressed cache context needed by one game launch. */
final class LaunchCacheContexts {
    private static final String PARALLEL_PROFILE_SELECTION_PROPERTY =
            "preflight.launch.parallelProfileSelection";

    private LaunchCacheContexts() {
    }

    static Result select(CommandLine options, LaunchTarget target, boolean janinoCacheOwned)
            throws IOException {
        Texture texture = textureContext(options, target);
        Profiles profiles = profileContexts(options, target, texture, janinoCacheOwned);
        return new Result(
                texture,
                profiles.variantJson(),
                profiles.weaponJson(),
                profiles.projectileJson(),
                profiles.hullJson(),
                profiles.rulesCsv(),
                profiles.ruleCommand(),
                profiles.mergedRead(),
                profiles.janino(),
                profiles.preparedAudio());
    }

    private static Texture textureContext(CommandLine options, LaunchTarget target) throws IOException {
        if (options.textureAuto()) {
            System.out.println("Preflight is matching prepared textures to the current installed profile...");
            CurrentTextureCache.Resolution resolved = CurrentTextureCache.resolve(
                    target.installRoot(), options.textureCacheDirectory());
            return new Texture(
                    resolved.cacheDirectory(),
                    resolved.manifest(),
                    resolved.index(),
                    resolved.resourceIndex(),
                    true,
                    resolved.profileFingerprint(),
                    resolved.manifestSha256(),
                    resolved.indexSha256(),
                    resolved.checkedProviders(),
                    resolved.indexBuildMillis(),
                    resolved.sourceGenerationValidated(),
                    resolved.sourceGenerationProvider(),
                    resolved.sourceGenerationEntries(),
                    resolved.sourceGenerationBytes(),
                    resolved.sourceGenerationValidationMillis(),
                    resolved.sourceGenerationProblem());
        }
        if (options.textureManifest() == null) {
            return null;
        }
        return new Texture(
                options.textureCacheDirectory().toAbsolutePath().normalize(),
                options.textureManifest().toAbsolutePath().normalize(),
                options.textureIndex().toAbsolutePath().normalize(),
                null,
                false,
                null,
                null,
                null,
                0,
                0,
                false,
                null,
                0,
                0,
                0,
                null);
    }

    /**
     * Selects every spec-store cache artifact from one pass over the launch profile.
     *
     * <p>Each cache fails independently: an identity that cannot be built leaves that one context
     * null and vanilla loading handles that corpus.
     */
    private static Profiles profileContexts(
            CommandLine options,
            LaunchTarget target,
            Texture textures,
            boolean janinoCacheOwned) {
        boolean textureProfiles = textures != null && textures.automatic();
        if (options.adapterMode() != AdapterMode.ENABLED
                || (!textureProfiles && !janinoCacheOwned && !options.preparedAudio())) {
            return Profiles.none();
        }
        long opened = System.nanoTime();
        try {
            ResourceIndex resources = textureProfiles
                    ? textures.resourceIndex()
                    : ResourceIndexBuilder.build(target.installRoot()).index();
            Path cacheRoot = textureProfiles
                    ? textures.cacheDirectory()
                    : PrepareCommand.defaultCacheDirectory().toAbsolutePath().normalize();
            try (ProfileIdentityContext context =
                    ProfileIdentityContext.of(target.installRoot(), resources)) {
                System.out.printf(Locale.ROOT,
                        "Preflight read the launch profile in %.1fms (%d providers).%n",
                        (System.nanoTime() - opened) / 1_000_000.0,
                        context.resources().providerCount());
                return selectProfileContexts(
                        options, target, textures, context, cacheRoot, textureProfiles, janinoCacheOwned);
            }
        } catch (Exception error) {
            System.err.println("Preflight launch profile identity failed: " + message(error)
                    + "; vanilla loading remains active.");
            return Profiles.none();
        }
    }

    private static Profiles selectProfileContexts(
            CommandLine options,
            LaunchTarget target,
            Texture textures,
            ProfileIdentityContext context,
            Path cacheRoot,
            boolean textureProfiles,
            boolean janinoCacheOwned) {
        if (!parallelProfileSelectionEnabled() || profileSelectionWorkers() == 1) {
            return selectProfileContextsSerial(
                    options, target, textures, context, cacheRoot, textureProfiles, janinoCacheOwned);
        }

        int workers = profileSelectionWorkers();
        AtomicInteger threadNumber = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(workers, operation -> {
            Thread thread = new Thread(
                    operation,
                    "preflight-launch-profile-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        long started = System.nanoTime();
        try {
            Future<VariantJson> variant = profileTask(
                    executor, textureProfiles, () -> variantJsonCacheContext(context, textures));
            Future<WeaponJson> weapon = profileTask(
                    executor, textureProfiles, () -> weaponJsonCacheContext(context, textures));
            Future<ProjectileJson> projectile = profileTask(
                    executor, textureProfiles, () -> projectileJsonCacheContext(context, textures));
            Future<HullJson> hull = profileTask(
                    executor, textureProfiles, () -> hullJsonCacheContext(context, textures));
            Future<RulesCsv> rules = profileTask(
                    executor, textureProfiles, () -> rulesCsvCacheContext(context, textures));
            Future<RuleCommand> commands = profileTask(
                    executor,
                    textureProfiles && options.ruleCommandClassCache(),
                    () -> ruleCommandCacheContext(context, textures));
            Future<MergedRead> merged = profileTask(
                    executor, textureProfiles, () -> mergedReadCacheContext(context, textures));
            Future<Janino> janino = profileTask(
                    executor,
                    janinoCacheOwned,
                    () -> janinoBytecodeCacheContext(context, target, cacheRoot));
            Future<PreparedAudio> audio = profileTask(
                    executor,
                    options.preparedAudio(),
                    () -> preparedAudioCacheContext(
                            context,
                            PrepareCommand.defaultCacheDirectory().toAbsolutePath().normalize()));

            Profiles selected = new Profiles(
                    profileResult("variant JSON", variant),
                    profileResult("weapon JSON", weapon),
                    profileResult("projectile JSON", projectile),
                    profileResult("hull JSON", hull),
                    profileResult("rules CSV", rules),
                    profileResult("rule command class", commands),
                    profileResult("merged read", merged),
                    profileResult("Janino bytecode", janino),
                    profileResult("prepared audio", audio));
            System.out.printf(Locale.ROOT,
                    "Preflight selected launch cache profiles concurrently in %.1fms "
                            + "(%d bounded workers; join completed before game start).%n",
                    (System.nanoTime() - started) / 1_000_000.0,
                    workers);
            return selected;
        } finally {
            stopProfileSelection(executor);
        }
    }

    private static Profiles selectProfileContextsSerial(
            CommandLine options,
            LaunchTarget target,
            Texture textures,
            ProfileIdentityContext context,
            Path cacheRoot,
            boolean textureProfiles,
            boolean janinoCacheOwned) {
        return new Profiles(
                textureProfiles ? variantJsonCacheContext(context, textures) : null,
                textureProfiles ? weaponJsonCacheContext(context, textures) : null,
                textureProfiles ? projectileJsonCacheContext(context, textures) : null,
                textureProfiles ? hullJsonCacheContext(context, textures) : null,
                textureProfiles ? rulesCsvCacheContext(context, textures) : null,
                textureProfiles && options.ruleCommandClassCache()
                        ? ruleCommandCacheContext(context, textures)
                        : null,
                textureProfiles ? mergedReadCacheContext(context, textures) : null,
                janinoCacheOwned ? janinoBytecodeCacheContext(context, target, cacheRoot) : null,
                options.preparedAudio()
                        ? preparedAudioCacheContext(
                                context,
                                PrepareCommand.defaultCacheDirectory().toAbsolutePath().normalize())
                        : null);
    }

    private static boolean parallelProfileSelectionEnabled() {
        return Boolean.parseBoolean(System.getProperty(PARALLEL_PROFILE_SELECTION_PROPERTY, "true"));
    }

    private static int profileSelectionWorkers() {
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
    }

    private static <T> Future<T> profileTask(
            ExecutorService executor, boolean enabled, Supplier<T> operation) {
        return enabled
                ? executor.submit(operation::get)
                : CompletableFuture.completedFuture(null);
    }

    private static <T> T profileResult(String label, Future<T> result) {
        try {
            return result.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            System.err.println("Preflight " + label
                    + " cache selection was interrupted; vanilla loading remains active.");
            return null;
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Error error) {
                throw error;
            }
            System.err.println("Preflight " + label + " cache selection failed: " + message(cause)
                    + "; vanilla loading remains active.");
            return null;
        }
    }

    /** Do not close the shared identity context while a cancelled selector can still use it. */
    private static void stopProfileSelection(ExecutorService executor) {
        executor.shutdownNow();
        boolean interrupted = false;
        while (!executor.isTerminated()) {
            try {
                executor.awaitTermination(1, TimeUnit.DAYS);
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static Janino janinoBytecodeCacheContext(
            ProfileIdentityContext context, LaunchTarget target, Path cacheRoot) {
        long started = System.nanoTime();
        try {
            List<JaninoProfileIdentityBuilder.OrderedArchive> archives =
                    JaninoProfileIdentityBuilder.discoverOrderedArchives(context);
            String launchContract = janinoLaunchContract(context, target);
            long classpathFinished = System.nanoTime();
            JaninoProfileIdentityBuilder.Result profile =
                    JaninoProfileIdentityBuilder.build(context, archives, launchContract);
            System.out.printf(Locale.ROOT,
                    "Preflight matched Janino compilation profile %s in %.1fms "
                            + "(archive ordering %.1fms, identity %.1fms; %d archives/%.1f MB, "
                            + "%d loose providers/%.1f MB, %d core jars).%n",
                    profile.context().keySha256(),
                    (System.nanoTime() - started) / 1_000_000.0,
                    (classpathFinished - started) / 1_000_000.0,
                    (System.nanoTime() - classpathFinished) / 1_000_000.0,
                    profile.archiveCount(),
                    profile.archiveBytes() / 1_048_576.0,
                    profile.looseProviderCount(),
                    profile.looseProviderBytes() / 1_048_576.0,
                    profile.coreJarCount());
            return new Janino(
                    cacheRoot.toAbsolutePath().normalize(), profile.context().portableToken());
        } catch (Exception error) {
            declined("Janino bytecode", error);
            return null;
        }
    }

    private static PreparedAudio preparedAudioCacheContext(
            ProfileIdentityContext context, Path cacheRoot) {
        long started = System.nanoTime();
        try {
            Path preparedRoot = PreparedAudioCache.root(cacheRoot);
            if (!Files.isDirectory(preparedRoot)) {
                System.out.println("No prepared audio for this installation yet; "
                        + "run `preflight audio prepare` to build it.");
                return null;
            }
            List<Path> gameJars = PrepareAudioCommand.jars(context.installRoot());
            String decoder = PrepareAudioCommand.decoderPolicyIdentity(gameJars);
            Path manifestPath = PreparedAudioCache.manifestDirectory(cacheRoot)
                    .resolve(context.resources().profileFingerprint() + ".spam")
                    .toAbsolutePath().normalize();
            if (!Files.isRegularFile(manifestPath)) {
                System.out.println("Prepared audio predates path-indexed lookup; "
                        + "run `preflight audio prepare` once to refresh it.");
                return new PreparedAudio(cacheRoot, decoder, null, null);
            }
            PreparedAudioManifest manifest = PreparedAudioManifestIO.read(manifestPath);
            if (!manifest.profileFingerprintSha256().equals(context.resources().profileFingerprint())
                    || !manifest.starsectorBuildSha256().equals(context.gameJarSha256())
                    || !manifest.decoderPolicyIdentitySha256().equals(decoder)) {
                throw new IOException("prepared audio manifest identity does not match this launch");
            }

            List<PreparedAudioManifest.Entry> entries = manifest.entries().values().stream()
                    .filter(entry -> entry.policy().cacheEligible())
                    .toList();
            List<ResourceIndex.Provider> providers = new ArrayList<>(entries.size());
            for (PreparedAudioManifest.Entry entry : entries) {
                ResourceIndex.Provider provider = context.resources().winner(entry.logicalPath())
                        .orElseThrow(() -> new IOException(
                                "prepared audio source is no longer present: " + entry.logicalPath()));
                if (provider.size() != entry.sourceBytes()
                        || provider.modifiedMillis() != entry.sourceModifiedMillis()) {
                    throw new IOException("prepared audio source metadata changed: " + entry.logicalPath());
                }
                providers.add(provider);
            }
            List<Path> sources = context.resolveAll(providers);
            List<String> hashes = context.sha256All(sources);
            for (int index = 0; index < entries.size(); index++) {
                if (!entries.get(index).sourceSha256().equals(hashes.get(index))) {
                    throw new IOException(
                            "prepared audio source content changed: " + entries.get(index).logicalPath());
                }
            }
            System.out.printf(Locale.ROOT,
                    "Preflight validated %,d prepared audio paths (%.1f MB) in %.1fms.%n",
                    entries.size(),
                    entries.stream().mapToLong(PreparedAudioManifest.Entry::sourceBytes).sum()
                            / 1_000_000.0,
                    (System.nanoTime() - started) / 1_000_000.0);
            return new PreparedAudio(
                    cacheRoot, decoder, manifestPath, manifest.manifestSha256());
        } catch (Exception error) {
            declined("prepared audio path index", error);
            try {
                if (Files.isDirectory(PreparedAudioCache.root(cacheRoot))) {
                    String decoder = PrepareAudioCommand.decoderPolicyIdentity(
                            PrepareAudioCommand.jars(context.installRoot()));
                    return new PreparedAudio(cacheRoot, decoder, null, null);
                }
            } catch (Exception ignored) {
                // The caller receives null and the game decodes exactly as vanilla would.
            }
            return null;
        }
    }

    static String janinoLaunchContract(
            ProfileIdentityContext context, LaunchTarget target) throws IOException {
        List<Path> candidates = List.of(
                target.launcher(),
                target.launcher().resolveSibling("fr.vmparams"),
                target.launcher().resolveSibling("starsector.vmparams"),
                target.launcher().resolveSibling("vmparams"),
                target.installRoot().resolve("Contents/MacOS/compiler_directives.txt"),
                target.installRoot().resolve("starsector-core/vmparams"));
        List<Path> files = candidates.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .distinct()
                .filter(Files::isRegularFile)
                .toList();
        List<String> hashes = context.sha256All(files);
        StringBuilder canonical = new StringBuilder("starsector-preflight-janino-launch-contract-v1\n")
                .append("launcher-kind=").append(target.kind()).append('\n');
        Path install = target.installRoot().toAbsolutePath().normalize();
        for (int index = 0; index < files.size(); index++) {
            Path file = files.get(index);
            String name = file.startsWith(install)
                    ? install.relativize(file).toString().replace('\\', '/')
                    : file.getFileName().toString();
            canonical.append(name).append('=').append(hashes.get(index)).append('\n');
        }
        return Hashes.sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static VariantJson variantJsonCacheContext(
            ProfileIdentityContext context, Texture textures) {
        long started = System.nanoTime();
        try {
            VariantJsonProfileIdentityBuilder.Result profile =
                    VariantJsonProfileIdentityBuilder.build(context);
            Path artifact = artifact(
                    SpecStoreCacheDirectories.variantJson(textures.cacheDirectory()),
                    profile.identitySha256(), ".spvj");
            report("variant JSON", profile.identitySha256(), started, artifact,
                    String.format(Locale.ROOT, "%d paths, %d providers",
                            profile.logicalPaths(), profile.providerCount()));
            return new VariantJson(artifact);
        } catch (Exception error) {
            declined("variant JSON", error);
            return null;
        }
    }

    private static WeaponJson weaponJsonCacheContext(
            ProfileIdentityContext context, Texture textures) {
        long started = System.nanoTime();
        try {
            WeaponJsonProfileIdentityBuilder.Result profile =
                    WeaponJsonProfileIdentityBuilder.build(context);
            Path artifact = artifact(
                    SpecStoreCacheDirectories.weaponJson(textures.cacheDirectory()),
                    profile.identitySha256(), ".spwj");
            report("weapon JSON", profile.identitySha256(), started, artifact,
                    String.format(Locale.ROOT, "%d paths, %d providers",
                            profile.logicalPaths(), profile.providerCount()));
            return new WeaponJson(artifact);
        } catch (Exception error) {
            declined("weapon JSON", error);
            return null;
        }
    }

    private static ProjectileJson projectileJsonCacheContext(
            ProfileIdentityContext context, Texture textures) {
        long started = System.nanoTime();
        try {
            ProjectileJsonProfileIdentityBuilder.Result profile =
                    ProjectileJsonProfileIdentityBuilder.build(context);
            Path artifact = artifact(
                    SpecStoreCacheDirectories.projectileJson(textures.cacheDirectory()),
                    profile.identitySha256(), ".sppj");
            report("projectile JSON", profile.identitySha256(), started, artifact,
                    String.format(Locale.ROOT, "%d paths, %d providers",
                            profile.logicalPaths(), profile.providerCount()));
            return new ProjectileJson(artifact);
        } catch (Exception error) {
            declined("projectile JSON", error);
            return null;
        }
    }

    private static HullJson hullJsonCacheContext(
            ProfileIdentityContext context, Texture textures) {
        long started = System.nanoTime();
        try {
            HullJsonProfileIdentityBuilder.Result profile =
                    HullJsonProfileIdentityBuilder.build(context);
            Path artifact = artifact(
                    SpecStoreCacheDirectories.hullJson(textures.cacheDirectory()),
                    profile.identitySha256(), ".sphj");
            report("hull JSON", profile.identitySha256(), started, artifact,
                    String.format(Locale.ROOT, "%d paths, %d providers",
                            profile.logicalPaths(), profile.providerCount()));
            return new HullJson(artifact);
        } catch (Exception error) {
            declined("hull JSON", error);
            return null;
        }
    }

    private static RulesCsv rulesCsvCacheContext(
            ProfileIdentityContext context, Texture textures) {
        long started = System.nanoTime();
        try {
            RulesCsvProfileIdentityBuilder.Result profile =
                    RulesCsvProfileIdentityBuilder.build(context);
            Path artifact = artifact(
                    SpecStoreCacheDirectories.rulesCsv(textures.cacheDirectory()),
                    profile.identitySha256(), ".sprc");
            report("rules CSV", profile.identitySha256(), started, artifact,
                    String.format(Locale.ROOT, "%d providers", profile.providerCount()));
            return new RulesCsv(artifact);
        } catch (Exception error) {
            declined("rules CSV", error);
            return null;
        }
    }

    private static RuleCommand ruleCommandCacheContext(
            ProfileIdentityContext context, Texture textures) {
        long started = System.nanoTime();
        try {
            RuleCommandClassProfileIdentityBuilder.Result profile =
                    RuleCommandClassProfileIdentityBuilder.build(context);
            Path artifact = artifact(
                    SpecStoreCacheDirectories.ruleCommandClasses(textures.cacheDirectory()),
                    profile.identitySha256(), ".sprk");
            report("rule command class", profile.identitySha256(), started, artifact,
                    String.format(Locale.ROOT, "%d settings.json providers, %d jars, %.1f MB",
                            profile.settingsProviderCount(), profile.jarCount(),
                            profile.jarBytes() / 1_048_576.0));
            return new RuleCommand(artifact);
        } catch (Exception error) {
            declined("rule command class", error);
            return null;
        }
    }

    private static MergedRead mergedReadCacheContext(
            ProfileIdentityContext context, Texture textures) {
        long started = System.nanoTime();
        try {
            MergedReadProfileIdentityBuilder.Result profile =
                    MergedReadProfileIdentityBuilder.build(context);
            Path artifact = artifact(
                    SpecStoreCacheDirectories.mergedReads(textures.cacheDirectory()),
                    profile.identitySha256(), ".spmr");
            report("merged read", profile.identitySha256(), started, artifact,
                    String.format(Locale.ROOT, "%d paths, %d providers",
                            profile.logicalPaths(), profile.providerCount()));
            return new MergedRead(artifact);
        } catch (Exception error) {
            declined("merged read", error);
            return null;
        }
    }

    private static Path artifact(Path store, String identity, String extension) {
        return store.resolve(identity + extension).toAbsolutePath().normalize();
    }

    private static void report(
            String label, String identity, long started, Path artifact, String detail) {
        System.out.printf(Locale.ROOT,
                "Preflight matched %s dependency profile %s in %.1fms (%s, %s).%n",
                label,
                identity,
                (System.nanoTime() - started) / 1_000_000.0,
                detail,
                Files.isRegularFile(artifact) ? "hit" : "learning run");
    }

    private static void declined(String label, Exception error) {
        System.err.println("Preflight " + label + " cache selection failed: " + message(error)
                + "; vanilla loading remains active.");
    }

    private static String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value;
    }

    record Result(
            Texture texture,
            VariantJson variantJson,
            WeaponJson weaponJson,
            ProjectileJson projectileJson,
            HullJson hullJson,
            RulesCsv rulesCsv,
            RuleCommand ruleCommand,
            MergedRead mergedRead,
            Janino janino,
            PreparedAudio preparedAudio) {
    }

    private record Profiles(
            VariantJson variantJson,
            WeaponJson weaponJson,
            ProjectileJson projectileJson,
            HullJson hullJson,
            RulesCsv rulesCsv,
            RuleCommand ruleCommand,
            MergedRead mergedRead,
            Janino janino,
            PreparedAudio preparedAudio) {

        static Profiles none() {
            return new Profiles(null, null, null, null, null, null, null, null, null);
        }
    }

    record Texture(
            Path cacheDirectory,
            Path manifest,
            Path index,
            ResourceIndex resourceIndex,
            boolean automatic,
            String profileFingerprint,
            String manifestSha256,
            String indexSha256,
            long checkedProviders,
            double indexBuildMillis,
            boolean sourceGenerationValidated,
            String sourceGenerationProvider,
            int sourceGenerationEntries,
            long sourceGenerationBytes,
            double sourceGenerationValidationMillis,
            String sourceGenerationProblem) {
    }

    record VariantJson(Path artifact) {
    }

    record WeaponJson(Path artifact) {
    }

    record ProjectileJson(Path artifact) {
    }

    record HullJson(Path artifact) {
    }

    record RulesCsv(Path artifact) {
    }

    record RuleCommand(Path artifact) {
    }

    record MergedRead(Path artifact) {
    }

    record Janino(Path cacheRoot, String contextToken) {
    }

    record PreparedAudio(
            Path cacheRoot, String decoderIdentity, Path manifest, String manifestIdentity) {
    }
}
