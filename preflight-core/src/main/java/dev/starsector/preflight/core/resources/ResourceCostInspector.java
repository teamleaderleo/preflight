package dev.starsector.preflight.core.resources;

import dev.starsector.preflight.core.GpuTextureFootprint;
import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.ImageHeaderReader;
import dev.starsector.preflight.core.OggVorbisIdentification;
import dev.starsector.preflight.core.OggVorbisStreamLength;
import dev.starsector.preflight.core.PathContainment;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.resources.LargestAllocations.LargestAudioAllocation;
import dev.starsector.preflight.core.resources.LargestAllocations.LargestJarAllocation;
import dev.starsector.preflight.core.resources.LargestAllocations.LargestTextureAllocation;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Unified, non-decoding, per-mod resource and prepared-data cost inspector.
 */
public final class ResourceCostInspector {
    private static final int LARGEST_ALLOCATIONS_LIMIT = 25;
    private static final String SOUNDS_CONFIG_PATH = "data/config/sounds.json";

    private ResourceCostInspector() {
    }

    public static ResourceCostReport inspect(Path installRoot) throws IOException {
        return inspect(installRoot, null);
    }

    public static ResourceCostReport inspect(Path installRoot, String targetModId) throws IOException {
        long startedNanos = System.nanoTime();
        if (installRoot == null) {
            throw new IllegalArgumentException("installRoot is required");
        }
        Path normalizedRoot = installRoot.toAbsolutePath().normalize();
        List<String> diagnostics = new ArrayList<>();

        boolean isStandaloneMod = isStandaloneModDirectory(normalizedRoot);
        List<ModCandidate> candidates;
        List<String> enabledIds = new ArrayList<>();

        if (isStandaloneMod) {
            ModCandidate mod = readModCandidate(normalizedRoot, 0, diagnostics);
            candidates = List.of(mod);
            enabledIds.add(mod.id());
        } else {
            candidates = discoverProfileRoots(normalizedRoot, enabledIds, diagnostics);
        }

        // Build file catalog and provider index for override winning determination
        Map<String, List<ProviderEntry>> providersByLogicalPath = new TreeMap<>();
        List<ModScanData> modScanDataList = new ArrayList<>();

        for (int rootIndex = 0; rootIndex < candidates.size(); rootIndex++) {
            ModCandidate candidate = candidates.get(rootIndex);
            ModScanData scanData = scanModFiles(candidate, rootIndex, diagnostics);
            modScanDataList.add(scanData);

            for (FileEntry file : scanData.files()) {
                providersByLogicalPath.computeIfAbsent(file.logicalPath(), ignored -> new ArrayList<>())
                        .add(new ProviderEntry(rootIndex, candidate.id(), file));
            }
        }

        // Read and union data/config/sounds.json across roots in resolution order
        SoundDeclarations soundDeclarations = readSoundDeclarations(modScanDataList, diagnostics);

        // Process each mod's textures, audio, JARs
        List<ModResourceCost> modCosts = new ArrayList<>();
        List<LargestAllocations.LargestTextureAllocation> allLargestTextures = new ArrayList<>();
        List<LargestAllocations.LargestAudioAllocation> allLargestAudio = new ArrayList<>();
        List<LargestAllocations.LargestJarAllocation> allLargestJars = new ArrayList<>();

        // Map for cross-mod class duplicate collision detection
        Map<String, List<String>> classProviders = new TreeMap<>();

        for (ModScanData scanData : modScanDataList) {
            for (JarFileData jar : scanData.jars()) {
                for (String className : jar.classes()) {
                    classProviders.computeIfAbsent(className, ignored -> new ArrayList<>())
                            .add(scanData.candidate().id() + ":" + jar.relativePath());
                }
            }
        }

        long totalProfileDiskBytes = 0;
        long totalProfileEstimatedMemoryBytes = 0;
        long profileTextureCount = 0;
        long profileTextureDiskBytes = 0;
        long profileTextureDecodedBytes = 0;
        long profileTextureResidentBytes = 0;
        long profileTexturePaddingWaste = 0;
        long profileTextureMipChainUpperBound = 0;

        long profileSoundCount = 0;
        long profileSoundDiskBytes = 0;
        long profileEffectPcmBytes = 0;
        long profileEffectCount = 0;
        long profileMusicDiskBytes = 0;
        long profileMusicCount = 0;
        long profileUnreferencedCount = 0;
        long profileUnreferencedDiskBytes = 0;

        long profileJarCount = 0;
        long profileJarDiskBytes = 0;
        long profileUncompressedBytecodeBytes = 0;
        long profileClassCount = 0;

        for (ModScanData scanData : modScanDataList) {
            ModCandidate mod = scanData.candidate();
            int rootIndex = scanData.rootIndex();

            // 1. Textures
            int modTextureCount = 0;
            long modTextureDiskBytes = 0;
            long modTextureDecodedBytes = 0;
            long modTextureResidentBytes = 0;
            long modTexturePaddingWaste = 0;
            int modTextureUnmeasured = 0;
            int modTexturesOverridden = 0;
            long modVramShadowedBytes = 0;

            for (FileEntry file : scanData.files()) {
                if (isImageExtension(file.relativePath())) {
                    modTextureCount++;
                    modTextureDiskBytes += file.size();

                    Optional<ImageHeaderReader.ImageDimensions> dims = Optional.empty();
                    try {
                        dims = ImageHeaderReader.read(file.physicalPath());
                    } catch (IOException | RuntimeException e) {
                        diagnostics.add("Could not read image header for " + file.physicalPath() + ": " + e.getMessage());
                    }

                    if (dims.isPresent()) {
                        ImageHeaderReader.ImageDimensions d = dims.get();
                        long resident = GpuTextureFootprint.residentBytes(d.width(), d.height());
                        long padding = GpuTextureFootprint.paddingBytes(d.width(), d.height());
                        long decoded = d.decodedBytes();
                        long mips = GpuTextureFootprint.residentBytesWithMipChain(d.width(), d.height());

                        // Check winning provider
                        List<ProviderEntry> providers = providersByLogicalPath.get(file.logicalPath());
                        ProviderEntry winner = (providers == null || providers.isEmpty()) ? null : providers.get(providers.size() - 1);
                        boolean isWinner = (winner != null && winner.rootIndex() == rootIndex);

                        if (isWinner) {
                            modTextureDecodedBytes += decoded;
                            modTextureResidentBytes += resident;
                            modTexturePaddingWaste += padding;

                            profileTextureDecodedBytes += decoded;
                            profileTextureResidentBytes += resident;
                            profileTexturePaddingWaste += padding;
                            profileTextureMipChainUpperBound += mips;

                            allLargestTextures.add(new LargestAllocations.LargestTextureAllocation(
                                    file.logicalPath(),
                                    mod.id(),
                                    d.width(),
                                    d.height(),
                                    d.channels(),
                                    file.size(),
                                    resident,
                                    padding,
                                    winner.modId()));
                        } else {
                            modTexturesOverridden++;
                            modVramShadowedBytes += resident;
                        }
                    } else {
                        modTextureUnmeasured++;
                    }
                }
            }

            profileTextureCount += modTextureCount;
            profileTextureDiskBytes += modTextureDiskBytes;

            // 2. Audio
            int modSoundCount = 0;
            long modSoundDiskBytes = 0;
            long modEffectPcmBytes = 0;
            long modMusicBytes = 0;
            long modUnreferencedBytes = 0;

            for (FileEntry file : scanData.files()) {
                if (file.relativePath().toLowerCase(Locale.ROOT).endsWith(".ogg")) {
                    modSoundCount++;
                    modSoundDiskBytes += file.size();

                    SoundKind kind = soundDeclarations.classify(file.logicalPath());
                    OggVorbisIdentification.Result idResult = OggVorbisIdentification.inspect(file.physicalPath());
                    OggVorbisStreamLength.Measurement lenResult = OggVorbisStreamLength.measure(file.physicalPath());

                    List<ProviderEntry> providers = providersByLogicalPath.get(file.logicalPath());
                    ProviderEntry winner = (providers == null || providers.isEmpty()) ? null : providers.get(providers.size() - 1);
                    boolean isWinner = (winner != null && winner.rootIndex() == rootIndex);

                    int channels = idResult.supported() ? idResult.channels() : 0;
                    int sampleRate = idResult.supported() ? idResult.sampleRate() : 0;
                    long pcm = (lenResult.measured() && channels > 0) ? lenResult.decodedBytes(channels) : 0;
                    double durationSeconds = (sampleRate > 0 && lenResult.measured())
                            ? (double) lenResult.totalFrames() / sampleRate
                            : 0.0;

                    if (kind == SoundKind.EFFECT) {
                        if (isWinner) {
                            modEffectPcmBytes += pcm;
                            profileEffectPcmBytes += pcm;
                            profileEffectCount++;
                        }
                    } else if (kind == SoundKind.MUSIC) {
                        modMusicBytes += file.size();
                        if (isWinner) {
                            profileMusicDiskBytes += file.size();
                            profileMusicCount++;
                        }
                    } else {
                        modUnreferencedBytes += file.size();
                        if (isWinner) {
                            profileUnreferencedDiskBytes += file.size();
                            profileUnreferencedCount++;
                        }
                    }

                    if (isWinner && idResult.supported()) {
                        allLargestAudio.add(new LargestAllocations.LargestAudioAllocation(
                                file.logicalPath(),
                                mod.id(),
                                kind.name().toLowerCase(Locale.ROOT),
                                channels,
                                sampleRate,
                                durationSeconds,
                                file.size(),
                                pcm));
                    }
                }
            }

            profileSoundCount += modSoundCount;
            profileSoundDiskBytes += modSoundDiskBytes;

            // 3. Bytecode & Classes
            int modJarCount = scanData.jars().size();
            long modJarDiskBytes = 0;
            long modUncompressedBytecodeBytes = 0;
            int modClassCount = 0;
            int modDuplicateClassCount = 0;

            Set<String> modUniqueClasses = new LinkedHashSet<>();
            for (JarFileData jar : scanData.jars()) {
                modJarDiskBytes += jar.diskBytes();
                modUncompressedBytecodeBytes += jar.uncompressedBytecodeBytes();
                modClassCount += jar.classCount();
                modUniqueClasses.addAll(jar.classes());

                allLargestJars.add(new LargestAllocations.LargestJarAllocation(
                        mod.id(),
                        jar.relativePath(),
                        jar.diskBytes(),
                        jar.uncompressedBytecodeBytes(),
                        jar.classCount()));
            }

            for (String className : modUniqueClasses) {
                List<String> providers = classProviders.get(className);
                if (providers != null && providers.size() > 1) {
                    modDuplicateClassCount++;
                }
            }

            profileJarCount += modJarCount;
            profileJarDiskBytes += modJarDiskBytes;
            profileUncompressedBytecodeBytes += modUncompressedBytecodeBytes;
            profileClassCount += modClassCount;

            // 4. Prepared Data for Mod
            ModResourceCost.ModPreparedCost modPrepared = measureModPreparedData(mod, normalizedRoot);

            // 5. Total Disk and Memory for Mod
            long modTotalDisk = scanData.totalDiskBytes();
            long modEstimatedMemory = modTextureResidentBytes + modEffectPcmBytes + modUncompressedBytecodeBytes;

            totalProfileDiskBytes += modTotalDisk;
            totalProfileEstimatedMemoryBytes += modEstimatedMemory;

            ModResourceCost cost = new ModResourceCost(
                    mod.id(),
                    mod.name(),
                    mod.version(),
                    mod.order(),
                    mod.enabled(),
                    modTotalDisk,
                    modEstimatedMemory,
                    new ModResourceCost.ModTextureCost(
                            modTextureCount,
                            modTextureDiskBytes,
                            modTextureDecodedBytes,
                            modTextureResidentBytes,
                            modTexturePaddingWaste,
                            modTextureUnmeasured),
                    new ModResourceCost.ModAudioCost(
                            modSoundCount,
                            modSoundDiskBytes,
                            modEffectPcmBytes,
                            modMusicBytes,
                            modUnreferencedBytes),
                    new ModResourceCost.ModBytecodeCost(
                            modJarCount,
                            modJarDiskBytes,
                            modUncompressedBytecodeBytes,
                            modClassCount,
                            modDuplicateClassCount),
                    modPrepared,
                    new ModResourceCost.ModShadowedCost(
                            modTexturesOverridden,
                            modVramShadowedBytes));

            modCosts.add(cost);
        }

        long totalDuplicateClasses = classProviders.values().stream()
                .filter(providers -> providers.size() > 1)
                .count();

        // 6. Prepared Data Caches Profile-Wide
        PreparedDataCostSummary preparedSummary = measurePreparedDataSummary(normalizedRoot);

        // 7. Sort and Bounded Largest Allocations
        allLargestTextures.sort(Comparator.comparingLong(LargestAllocations.LargestTextureAllocation::residentBytes).reversed()
                .thenComparing(LargestAllocations.LargestTextureAllocation::logicalPath));
        List<LargestAllocations.LargestTextureAllocation> topTextures = allLargestTextures.stream()
                .limit(LARGEST_ALLOCATIONS_LIMIT)
                .toList();

        allLargestAudio.sort(Comparator.comparingLong(LargestAllocations.LargestAudioAllocation::pcmBytes).reversed()
                .thenComparingLong(LargestAllocations.LargestAudioAllocation::diskBytes).reversed()
                .thenComparing(LargestAllocations.LargestAudioAllocation::logicalPath));
        List<LargestAllocations.LargestAudioAllocation> topAudio = allLargestAudio.stream()
                .limit(LARGEST_ALLOCATIONS_LIMIT)
                .toList();

        allLargestJars.sort(Comparator.comparingLong(LargestAllocations.LargestJarAllocation::uncompressedBytecodeBytes).reversed()
                .thenComparingLong(LargestAllocations.LargestJarAllocation::diskBytes).reversed()
                .thenComparing(LargestAllocations.LargestJarAllocation::relativePath));
        List<LargestAllocations.LargestJarAllocation> topJars = allLargestJars.stream()
                .limit(LARGEST_ALLOCATIONS_LIMIT)
                .toList();

        LargestAllocations largestAllocations = new LargestAllocations(topTextures, topAudio, topJars);

        // 8. Filter by targetModId if requested
        List<ModResourceCost> reportedMods = modCosts;
        if (targetModId != null && !targetModId.isBlank()) {
            reportedMods = modCosts.stream()
                    .filter(m -> targetModId.equalsIgnoreCase(m.id()))
                    .toList();
        }

        ResourceCostSummary summary = new ResourceCostSummary(
                modCosts.stream().filter(m -> !m.id().equals("core") && m.enabled()).mapToInt(m -> 1).sum(),
                totalProfileDiskBytes,
                totalProfileEstimatedMemoryBytes,
                new TextureCostSummary(
                        profileTextureCount,
                        profileTextureDiskBytes,
                        profileTextureDecodedBytes,
                        profileTextureResidentBytes,
                        profileTexturePaddingWaste,
                        profileTextureMipChainUpperBound),
                new AudioCostSummary(
                        profileSoundCount,
                        profileSoundDiskBytes,
                        profileEffectPcmBytes,
                        profileEffectCount,
                        profileMusicDiskBytes,
                        profileMusicCount,
                        profileUnreferencedCount,
                        profileUnreferencedDiskBytes),
                new BytecodeCostSummary(
                        profileJarCount,
                        profileJarDiskBytes,
                        profileUncompressedBytecodeBytes,
                        profileClassCount,
                        totalDuplicateClasses),
                preparedSummary);

        String profileFingerprint = computeFingerprint(enabledIds, modCosts);
        double scanDurationMs = (System.nanoTime() - startedNanos) / 1_000_000.0;

        return new ResourceCostReport(
                ResourceCostReport.FORMAT_VERSION,
                Instant.now().toString(),
                normalizedRoot.toString(),
                profileFingerprint,
                Math.round(scanDurationMs * 1000.0) / 1000.0,
                summary,
                reportedMods,
                largestAllocations,
                List.copyOf(new LinkedHashSet<>(diagnostics)));
    }

    private static boolean isImageExtension(String relativePath) {
        String lower = relativePath.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
    }

    private static boolean isStandaloneModDirectory(Path root) {
        return Files.isRegularFile(root.resolve("mod_info.json"));
    }

    private static ModCandidate readModCandidate(Path directory, int order, List<String> diagnostics) {
        String id = directory.getFileName().toString();
        String name = id;
        String version = "unknown";

        Path modInfo = directory.resolve("mod_info.json");
        if (Files.isRegularFile(modInfo)) {
            try {
                String json = Files.readString(modInfo, StandardCharsets.UTF_8);
                String readId = extractJsonStringField(json, "id");
                if (readId != null && !readId.isBlank()) {
                    id = readId;
                }
                String readName = extractJsonStringField(json, "name");
                if (readName != null && !readName.isBlank()) {
                    name = readName;
                }
                String readVersion = extractJsonStringField(json, "version");
                if (readVersion != null && !readVersion.isBlank()) {
                    version = readVersion;
                }
            } catch (IOException | RuntimeException e) {
                diagnostics.add("Could not parse " + modInfo + ": " + e.getMessage());
            }
        }
        return new ModCandidate(id, name, version, directory, order, true, false);
    }

    private static List<ModCandidate> discoverProfileRoots(
            Path installRoot, List<String> enabledIds, List<String> diagnostics) throws IOException {
        List<ModCandidate> roots = new ArrayList<>();
        int order = 0;

        // Core directory
        Path coreDir = locateCoreDirectory(installRoot);
        if (coreDir != null) {
            roots.add(new ModCandidate("core", "Starsector Core", "0.97a", coreDir, order++, true, true));
        }

        Path modsDir = installRoot.resolve("mods");
        if (Files.isDirectory(modsDir)) {
            Path enabledFile = modsDir.resolve("enabled_mods.json");
            List<String> enabledList = new ArrayList<>();
            if (Files.isRegularFile(enabledFile)) {
                try {
                    String enabledJson = Files.readString(enabledFile, StandardCharsets.UTF_8);
                    enabledList = extractJsonStringArrayField(enabledJson, "enabledMods");
                } catch (IOException | RuntimeException e) {
                    diagnostics.add("Could not read " + enabledFile + ": " + e.getMessage());
                }
            }
            enabledIds.addAll(enabledList);

            Map<String, Path> discoveredMods = new LinkedHashMap<>();
            try (Stream<Path> stream = Files.list(modsDir)) {
                for (Path dir : stream.filter(Files::isDirectory).sorted().toList()) {
                    ModCandidate candidate = readModCandidate(dir, -1, diagnostics);
                    discoveredMods.putIfAbsent(candidate.id(), dir);
                }
            }

            for (String enabledId : enabledList) {
                Path dir = discoveredMods.get(enabledId);
                if (dir != null) {
                    ModCandidate cand = readModCandidate(dir, order++, diagnostics);
                    roots.add(new ModCandidate(cand.id(), cand.name(), cand.version(), cand.directory(), cand.order(), true, false));
                } else {
                    diagnostics.add("Enabled mod directory not found for ID: " + enabledId);
                }
            }
        }
        return roots;
    }

    private static Path locateCoreDirectory(Path installRoot) {
        List<Path> candidates = List.of(
                installRoot.resolve("starsector-core"),
                installRoot.resolve("Contents/Resources/Java/starsector-core"),
                installRoot.resolve("Contents/Resources/Java"),
                installRoot.resolve("Contents/Resources/starsector-core"),
                installRoot.resolve("Contents/Java/starsector-core"));
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate) && (Files.isDirectory(candidate.resolve("graphics")) || Files.isDirectory(candidate.resolve("data")))) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        if (Files.isDirectory(installRoot.resolve("graphics")) && Files.isDirectory(installRoot.resolve("data"))) {
            return installRoot.toAbsolutePath().normalize();
        }
        return null;
    }

    private static ModScanData scanModFiles(ModCandidate candidate, int rootIndex, List<String> diagnostics) {
        List<FileEntry> files = new ArrayList<>();
        List<JarFileData> jars = new ArrayList<>();
        long totalDiskBytes = 0;

        try {
            Path root = candidate.directory();
            try (Stream<Path> stream = Files.walk(root)) {
                for (Path path : stream.filter(Files::isRegularFile).sorted().toList()) {
                    try {
                        long size = Files.size(path);
                        totalDiskBytes += size;
                        String rel = root.relativize(path).toString().replace('\\', '/');
                        String log = rel.toLowerCase(Locale.ROOT);
                        files.add(new FileEntry(rel, log, path, size));

                        if (rel.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                            JarFileData jar = scanJar(candidate.id(), rel, path, size, diagnostics);
                            if (jar != null) {
                                jars.add(jar);
                            }
                        }
                    } catch (IOException | RuntimeException e) {
                        diagnostics.add("Could not inspect file " + path + ": " + e.getMessage());
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            diagnostics.add("Could not walk mod directory " + candidate.directory() + ": " + e.getMessage());
        }

        return new ModScanData(candidate, rootIndex, files, jars, totalDiskBytes);
    }

    private static JarFileData scanJar(String modId, String relativePath, Path jarFile, long diskBytes, List<String> diagnostics) {
        try (ZipFile zip = new ZipFile(jarFile.toFile())) {
            List<? extends ZipEntry> entries = zip.stream()
                    .filter(e -> !e.isDirectory())
                    .sorted(Comparator.comparing(ZipEntry::getName))
                    .toList();

            List<String> classes = new ArrayList<>();
            long uncompressedBytecode = 0;
            for (ZipEntry entry : entries) {
                String name = entry.getName();
                if (name.endsWith(".class") && !name.endsWith("module-info.class")) {
                    classes.add(name.substring(0, name.length() - 6).replace('/', '.'));
                    uncompressedBytecode += Math.max(0, entry.getSize());
                }
            }
            return new JarFileData(relativePath, diskBytes, uncompressedBytecode, classes.size(), List.copyOf(classes));
        } catch (IOException | RuntimeException e) {
            diagnostics.add("Could not read JAR " + jarFile + ": " + e.getMessage());
            return new JarFileData(relativePath, diskBytes, 0, 0, List.of());
        }
    }

    private static SoundDeclarations readSoundDeclarations(List<ModScanData> scans, List<String> diagnostics) {
        Set<String> effectFiles = new LinkedHashSet<>();
        Set<String> musicFiles = new LinkedHashSet<>();
        Set<String> musicSources = new LinkedHashSet<>();

        for (ModScanData scan : scans) {
            for (FileEntry file : scan.files()) {
                if (file.logicalPath().equals(SOUNDS_CONFIG_PATH)) {
                    try {
                        String content = Files.readString(file.physicalPath(), StandardCharsets.UTF_8);
                        SoundDeclarations decl = parseSoundsJson(content);
                        effectFiles.addAll(decl.effectFiles());
                        musicFiles.addAll(decl.musicFiles());
                        musicSources.addAll(decl.musicSources());
                    } catch (IOException | RuntimeException e) {
                        diagnostics.add("Could not read " + file.physicalPath() + ": " + e.getMessage());
                    }
                }
            }
        }
        return new SoundDeclarations(effectFiles, musicFiles, musicSources);
    }

    private enum SoundKind {
        EFFECT,
        MUSIC,
        UNREFERENCED
    }

    private record SoundDeclarations(Set<String> effectFiles, Set<String> musicFiles, Set<String> musicSources) {
        SoundKind classify(String logicalPath) {
            if (effectFiles.contains(logicalPath)) {
                return SoundKind.EFFECT;
            }
            if (musicFiles.contains(logicalPath)) {
                return SoundKind.MUSIC;
            }
            for (String source : musicSources) {
                if (logicalPath.startsWith(source + "/")) {
                    return SoundKind.MUSIC;
                }
            }
            return SoundKind.UNREFERENCED;
        }
    }

    private static SoundDeclarations parseSoundsJson(String json) {
        if (json == null || json.isBlank()) {
            return new SoundDeclarations(Set.of(), Set.of(), Set.of());
        }
        String text = blankComments(json);
        int[] span = musicSpan(text);

        Set<String> effectFiles = new LinkedHashSet<>();
        Set<String> musicFiles = new LinkedHashSet<>();
        Set<String> musicSources = new LinkedHashSet<>();

        if (span == null) {
            collectSounds(text, 0, text.length(), effectFiles, musicSources);
        } else {
            collectSounds(text, span[0], span[1], musicFiles, musicSources);
            collectSounds(text, 0, span[0], effectFiles, new LinkedHashSet<>());
            collectSounds(text, span[1], text.length(), effectFiles, new LinkedHashSet<>());
        }
        return new SoundDeclarations(effectFiles, musicFiles, musicSources);
    }

    private static String blankComments(String json) {
        char[] out = json.toCharArray();
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < out.length; i++) {
            char c = out[i];
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '#' || (c == '/' && i + 1 < out.length && out[i + 1] == '/')) {
                while (i < out.length && out[i] != '\n') {
                    out[i++] = ' ';
                }
            }
        }
        return new String(out);
    }

    private static int[] musicSpan(String text) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                if (depth == 1 && text.startsWith("\"music\"", i)) {
                    int open = indexOfValue(text, i + "\"music\"".length());
                    if (open >= 0 && text.charAt(open) == '{') {
                        int close = matchBrace(text, open);
                        if (close >= 0) {
                            return new int[] {open, close + 1};
                        }
                    }
                }
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
        }
        return null;
    }

    private static int indexOfValue(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ':') {
                for (int j = i + 1; j < text.length(); j++) {
                    if (!Character.isWhitespace(text.charAt(j))) {
                        return j;
                    }
                }
                return -1;
            }
            if (!Character.isWhitespace(c)) {
                return -1;
            }
        }
        return -1;
    }

    private static int matchBrace(String text, int open) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static void collectSounds(String text, int from, int to, Set<String> files, Set<String> sources) {
        Deque<Integer> starts = new ArrayDeque<>();
        boolean nested = false;
        boolean inString = false;
        boolean escaped = false;
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                starts.push(i);
                nested = false;
            } else if (c == '}') {
                if (starts.isEmpty()) {
                    continue;
                }
                int start = starts.pop();
                if (!nested) {
                    readSoundEntry(text.substring(start + 1, i), files, sources);
                }
                nested = true;
            }
        }
    }

    private static void readSoundEntry(String entry, Set<String> files, Set<String> sources) {
        String file = extractField(entry, "file");
        if (file == null) {
            return;
        }
        String source = extractField(entry, "source");
        if (source != null) {
            sources.add(normalizePath(source));
        } else {
            files.add(normalizePath(file));
        }
    }

    private static String extractField(String entry, String key) {
        String quoted = '"' + key + '"';
        int at = 0;
        while (true) {
            at = entry.indexOf(quoted, at);
            if (at < 0) {
                return null;
            }
            int colon = indexOfValue(entry, at + quoted.length());
            if (colon >= 0 && entry.charAt(colon) == '"') {
                int end = colon + 1;
                StringBuilder text = new StringBuilder();
                while (end < entry.length() && entry.charAt(end) != '"') {
                    if (entry.charAt(end) == '\\' && end + 1 < entry.length()) {
                        end++;
                    }
                    text.append(entry.charAt(end++));
                }
                return text.toString();
            }
            at += quoted.length();
        }
    }

    private static String normalizePath(String path) {
        String value = path.replace('\\', '/').trim().toLowerCase(Locale.ROOT);
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static ModResourceCost.ModPreparedCost measureModPreparedData(ModCandidate mod, Path installRoot) {
        // Look for cache artifacts specific to this mod if present
        return new ModResourceCost.ModPreparedCost(0, 0, 0);
    }

    private static PreparedDataCostSummary measurePreparedDataSummary(Path installRoot) {
        Path homeCache = Path.of(System.getProperty("user.home"), ".starsector-preflight", "cache");
        Path localCache = installRoot.resolve("cache");
        Path cacheRoot = Files.isDirectory(homeCache) ? homeCache : (Files.isDirectory(localCache) ? localCache : null);

        long textureBytes = 0;
        long audioBytes = 0;
        long janinoBytes = 0;
        long specBytes = 0;

        if (cacheRoot != null) {
            textureBytes = directorySize(cacheRoot.resolve("prepared-textures")) + directorySize(cacheRoot.resolve("texture-packs"));
            audioBytes = directorySize(cacheRoot.resolve("prepared-audio"));
            janinoBytes = directorySize(cacheRoot.resolve("bytecode-bundles")) + directorySize(cacheRoot.resolve("bytecode-packs"));
            specBytes = directorySize(cacheRoot.resolve("spec-store")) + directorySize(cacheRoot.resolve("indexes"));
        }

        return new PreparedDataCostSummary(textureBytes, audioBytes, janinoBytes, specBytes);
    }

    private static long directorySize(Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException ignored) {
                    return 0;
                }
            }).sum();
        } catch (IOException ignored) {
            return 0;
        }
    }

    private static String extractJsonStringField(String json, String field) {
        String pattern = "\"" + field + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + pattern.length());
        if (colon < 0) return null;
        int firstQuote = json.indexOf('"', colon + 1);
        if (firstQuote < 0) return null;
        int endQuote = json.indexOf('"', firstQuote + 1);
        if (endQuote < 0) return null;
        return json.substring(firstQuote + 1, endQuote);
    }

    private static List<String> extractJsonStringArrayField(String json, String field) {
        String pattern = "\"" + field + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return List.of();
        int openBracket = json.indexOf('[', idx + pattern.length());
        if (openBracket < 0) return List.of();
        int closeBracket = json.indexOf(']', openBracket + 1);
        if (closeBracket < 0) return List.of();

        String body = json.substring(openBracket + 1, closeBracket);
        List<String> result = new ArrayList<>();
        int pos = 0;
        while (pos < body.length()) {
            int q1 = body.indexOf('"', pos);
            if (q1 < 0) break;
            int q2 = body.indexOf('"', q1 + 1);
            if (q2 < 0) break;
            result.add(body.substring(q1 + 1, q2));
            pos = q2 + 1;
        }
        return result;
    }

    private static String computeFingerprint(List<String> enabledIds, List<ModResourceCost> mods) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("starsector-preflight-resource-cost-v1".getBytes(StandardCharsets.UTF_8));
            for (String id : enabledIds) {
                digest.update(id.getBytes(StandardCharsets.UTF_8));
            }
            for (ModResourceCost m : mods) {
                digest.update(m.id().getBytes(StandardCharsets.UTF_8));
                digest.update(m.version().getBytes(StandardCharsets.UTF_8));
                digest.update(Long.toString(m.totalDiskBytes()).getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            return "unknown";
        }
    }

    private record ModCandidate(
            String id,
            String name,
            String version,
            Path directory,
            int order,
            boolean enabled,
            boolean core) {
    }

    private record FileEntry(
            String relativePath,
            String logicalPath,
            Path physicalPath,
            long size) {
    }

    private record JarFileData(
            String relativePath,
            long diskBytes,
            long uncompressedBytecodeBytes,
            int classCount,
            List<String> classes) {
    }

    private record ModScanData(
            ModCandidate candidate,
            int rootIndex,
            List<FileEntry> files,
            List<JarFileData> jars,
            long totalDiskBytes) {
    }

    private record ProviderEntry(
            int rootIndex,
            String modId,
            FileEntry file) {
    }
}
