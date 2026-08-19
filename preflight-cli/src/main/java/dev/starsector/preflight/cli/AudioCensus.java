package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.OggVorbisIdentification;
import dev.starsector.preflight.core.OggVorbisStreamLength;
import dev.starsector.preflight.core.ResourceIndex;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Sizes the audio a profile would have to prepare, by reading container headers rather than decoding.
 *
 * <p>The texture census reordered the asset program by showing what the decoded working set actually
 * costs. This is the same measurement for sound, and it needs to exist before any prepared-audio
 * policy, because Vorbis expands about eight-fold on decode: a profile whose sounds occupy 516 MB on
 * disk holds over four gigabytes of PCM. A cache that stores decoded effects without a policy is a
 * cache that fills a disk.</p>
 *
 * <p>Two facts decide what that policy can be, and neither is visible from file sizes. The first is
 * the effect/music split, which only {@code sounds.json} can answer — see {@link SoundsConfig}.
 * The second is decoded length, which comes from the final Ogg page's granule position — see
 * {@link OggVorbisStreamLength}.</p>
 *
 * <p>This reads only headers and the first and last page of each file. It writes nothing, prepares
 * nothing, and makes no eligibility decision; it reports what a decision would be spending.</p>
 */
final class AudioCensus {
    private static final String SOUNDS_CONFIG_PATH = "data/config/sounds.json";
    private static final int LARGEST_LIMIT = 20;
    private static final double[] DURATION_BUCKETS = {1, 5, 15, 60};

    private AudioCensus() {
    }

    /** How the profile's own configuration classifies a file. */
    enum Kind {
        /** Declared outside the music object: fully decoded, and the only prepared-audio candidate. */
        EFFECT,
        /** Declared inside the music object, by path or by source directory. Streamed, never cached. */
        MUSIC,
        /** Present in the profile but declared nowhere. Never loaded, so never worth preparing. */
        UNREFERENCED
    }

    record Sound(
            String logicalPath,
            String rootId,
            long encodedBytes,
            Kind kind,
            String codec,
            int channels,
            int sampleRate,
            long frames,
            long decodedBytes,
            boolean decodable,
            String detail) {

        double seconds() {
            return sampleRate <= 0 ? 0 : (double) frames / sampleRate;
        }

        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("path", logicalPath);
            values.put("provider", rootId);
            values.put("kind", kind.name().toLowerCase(java.util.Locale.ROOT));
            values.put("codec", codec);
            values.put("channels", channels);
            values.put("sampleRate", sampleRate);
            values.put("frames", frames);
            values.put("seconds", round(seconds()));
            values.put("encodedBytes", encodedBytes);
            values.put("decodedBytes", decodedBytes);
            values.put("decodable", decodable);
            if (!decodable) {
                values.put("detail", detail);
            }
            return values;
        }
    }

    /**
     * @param missingDeclarations sound files named by a {@code sounds.json} that no provider supplies
     */
    record Result(Map<String, Object> report, List<Sound> sounds, List<String> missingDeclarations) {
    }

    static Result scan(Path installRoot) throws IOException {
        ResourceIndexBuilder.BuildResult built = ResourceIndexBuilder.build(installRoot);
        return scan(installRoot, built.index(), new ArrayList<>(built.diagnostics()));
    }

    /** Shares an already-built index, so a caller measuring several asset kinds walks the profile once. */
    static Result scan(Path installRoot, ResourceIndex index, List<String> diagnostics) {
        long started = System.nanoTime();
        Declarations declarations = readDeclarations(index, diagnostics);
        List<Sound> sounds = new ArrayList<>();
        for (Map.Entry<String, List<ResourceIndex.Provider>> entry : index.entries().entrySet()) {
            String logicalPath = entry.getKey();
            if (!logicalPath.endsWith(".ogg")) {
                continue;
            }
            ResourceIndex.Provider winner = entry.getValue().get(entry.getValue().size() - 1);
            sounds.add(inspect(index, logicalPath, winner, declarations));
        }
        sounds.sort(Comparator.comparing(Sound::logicalPath));
        List<String> missing = missingDeclarations(index, declarations);

        return new Result(
                report(installRoot, index, sounds, declarations, missing, diagnostics,
                        System.nanoTime() - started),
                List.copyOf(sounds),
                missing);
    }

    private record Declarations(Set<String> effectFiles, Set<String> musicFiles, Set<String> musicSources, int configs) {
        Kind classify(String logicalPath) {
            if (effectFiles.contains(logicalPath)) {
                return Kind.EFFECT;
            }
            if (musicFiles.contains(logicalPath)) {
                return Kind.MUSIC;
            }
            for (String source : musicSources) {
                if (logicalPath.startsWith(source + "/")) {
                    return Kind.MUSIC;
                }
            }
            return Kind.UNREFERENCED;
        }
    }

    /**
     * Reads every provider of {@code sounds.json}, not just the winning one. Starsector merges these
     * by id across mods rather than letting one replace another, so the declarations a profile
     * actually loads are the union.
     */
    private static Declarations readDeclarations(ResourceIndex index, List<String> diagnostics) {
        Set<String> effects = new LinkedHashSet<>();
        Set<String> music = new LinkedHashSet<>();
        Set<String> sources = new LinkedHashSet<>();
        int configs = 0;
        for (ResourceIndex.Provider provider : index.providers(SOUNDS_CONFIG_PATH)) {
            Path file;
            try {
                file = index.resolveExisting(provider);
            } catch (IOException | RuntimeException error) {
                diagnostics.add("Could not resolve " + SOUNDS_CONFIG_PATH + ": " + error.getMessage());
                continue;
            }
            try {
                SoundsConfig.Declaration declaration =
                        SoundsConfig.parse(Files.readString(file, StandardCharsets.UTF_8));
                effects.addAll(declaration.effectFiles());
                music.addAll(declaration.musicFiles());
                declaration.musicSources().forEach(source -> sources.add(SoundsConfig.normalize(source)));
                configs++;
            } catch (IOException | RuntimeException error) {
                diagnostics.add("Could not read " + file + ": " + error.getMessage());
            }
        }
        return new Declarations(effects, music, sources, configs);
    }

    private static Sound inspect(
            ResourceIndex index,
            String logicalPath,
            ResourceIndex.Provider winner,
            Declarations declarations) {
        String rootId = index.roots().get(winner.rootIndex()).id();
        Kind kind = declarations.classify(logicalPath);
        Path file;
        try {
            file = index.resolveExisting(winner);
        } catch (IOException | RuntimeException error) {
            return new Sound(logicalPath, rootId, winner.size(), kind, "unknown", 0, 0, 0, 0, false,
                    error.getClass().getSimpleName() + ": " + error.getMessage());
        }

        OggVorbisIdentification.Result identification = OggVorbisIdentification.inspect(file);
        if (!identification.supported()) {
            return new Sound(logicalPath, rootId, winner.size(), kind, identification.codec(),
                    0, 0, 0, 0, false, identification.detail());
        }
        OggVorbisStreamLength.Measurement length = OggVorbisStreamLength.measure(file);
        if (!length.measured()) {
            return new Sound(logicalPath, rootId, winner.size(), kind, identification.codec(),
                    identification.channels(), identification.sampleRate(), 0, 0, false, length.detail());
        }
        return new Sound(logicalPath, rootId, winner.size(), kind, identification.codec(),
                identification.channels(), identification.sampleRate(), length.totalFrames(),
                length.decodedBytes(identification.channels()), true, identification.detail());
    }

    /**
     * Declared sound files that no provider supplies. The game cannot play these at all, and unlike
     * the size measurements this covers every declared extension rather than only {@code .ogg} —
     * a missing {@code .wav} is just as broken as a missing Vorbis file.
     */
    private static List<String> missingDeclarations(ResourceIndex index, Declarations declarations) {
        List<String> missing = new ArrayList<>();
        for (String declared : declarations.effectFiles()) {
            if (isMissing(index, declared)) {
                missing.add(declared);
            }
        }
        for (String declared : declarations.musicFiles()) {
            if (isMissing(index, declared)) {
                missing.add(declared);
            }
        }
        missing.sort(Comparator.naturalOrder());
        return List.copyOf(missing);
    }

    private static boolean isMissing(ResourceIndex index, String declared) {
        try {
            return index.providers(declared).isEmpty();
        } catch (IllegalArgumentException invalidPath) {
            return true;
        }
    }

    private static Map<String, Object> report(
            Path installRoot,
            ResourceIndex index,
            List<Sound> sounds,
            Declarations declarations,
            List<String> missingDeclarations,
            List<String> diagnostics,
            long elapsedNanos) {
        List<Sound> effects = sounds.stream().filter(s -> s.kind() == Kind.EFFECT).toList();
        List<Sound> undecodable = sounds.stream().filter(s -> !s.decodable()).toList();

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("reportFormat", "starsector-preflight-audio-census-v1");
        values.put("generatedAt", java.time.Instant.now().toString());
        values.put("installRoot", installRoot.toAbsolutePath().normalize().toString());
        values.put("profileFingerprint", index.profileFingerprint());
        values.put("resourceRoots", index.roots().size());
        values.put("soundsConfigsRead", declarations.configs());
        values.put("scanDurationMs", round(elapsedNanos / 1_000_000.0));
        values.put("measurementBasis", "final-ogg-page-granule-position");

        Map<String, Object> byKind = new LinkedHashMap<>();
        for (Kind kind : Kind.values()) {
            byKind.put(kind.name().toLowerCase(java.util.Locale.ROOT),
                    totals(sounds.stream().filter(s -> s.kind() == kind).toList()));
        }
        values.put("byKind", byKind);
        values.put("all", totals(sounds));

        Map<String, Object> eligible = new LinkedHashMap<>();
        eligible.put("totals", totals(effects));
        eligible.put("byDuration", byDuration(effects));
        eligible.put("bySampleRate", bySampleRate(effects));
        eligible.put("byProvider", byProvider(effects));
        eligible.put("largest", effects.stream()
                .sorted(Comparator.comparingLong(Sound::decodedBytes).reversed()
                        .thenComparing(Sound::logicalPath))
                .limit(LARGEST_LIMIT)
                .map(Sound::toMap)
                .toList());
        values.put("declaredEffects", eligible);

        Map<String, Object> undecodableReport = new LinkedHashMap<>();
        undecodableReport.put("count", undecodable.size());
        undecodableReport.put("files", undecodable.stream().map(Sound::toMap).toList());
        values.put("undecodable", undecodableReport);

        Map<String, Object> missingReport = new LinkedHashMap<>();
        missingReport.put("count", missingDeclarations.size());
        missingReport.put("files", missingDeclarations);
        values.put("missingDeclarations", missingReport);

        values.put("diagnostics", diagnostics);
        return values;
    }

    private static Map<String, Object> totals(List<Sound> sounds) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("files", sounds.size());
        values.put("encodedBytes", sounds.stream().mapToLong(Sound::encodedBytes).sum());
        values.put("decodedBytes", sounds.stream().mapToLong(Sound::decodedBytes).sum());
        values.put("seconds", round(sounds.stream().mapToDouble(Sound::seconds).sum()));
        values.put("undecodable", sounds.stream().filter(sound -> !sound.decodable()).count());
        return values;
    }

    private static List<Map<String, Object>> byDuration(List<Sound> sounds) {
        List<Map<String, Object>> buckets = new ArrayList<>();
        double low = 0;
        for (int i = 0; i <= DURATION_BUCKETS.length; i++) {
            double high = i < DURATION_BUCKETS.length ? DURATION_BUCKETS[i] : Double.MAX_VALUE;
            double lowBound = low;
            List<Sound> selected = sounds.stream()
                    .filter(sound -> sound.seconds() >= lowBound && sound.seconds() < high)
                    .toList();
            Map<String, Object> bucket = new LinkedHashMap<>();
            bucket.put("fromSeconds", low);
            if (high != Double.MAX_VALUE) {
                bucket.put("toSeconds", high);
            }
            bucket.putAll(totals(selected));
            buckets.add(bucket);
            low = high;
        }
        return buckets;
    }

    private static List<Map<String, Object>> bySampleRate(List<Sound> sounds) {
        TreeMap<Integer, List<Sound>> grouped = new TreeMap<>(Comparator.reverseOrder());
        for (Sound sound : sounds) {
            grouped.computeIfAbsent(sound.sampleRate(), ignored -> new ArrayList<>()).add(sound);
        }
        List<Map<String, Object>> rates = new ArrayList<>();
        for (Map.Entry<Integer, List<Sound>> entry : grouped.entrySet()) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("sampleRate", entry.getKey());
            values.putAll(totals(entry.getValue()));
            rates.add(values);
        }
        return rates;
    }

    private static List<Map<String, Object>> byProvider(List<Sound> sounds) {
        Map<String, List<Sound>> grouped = new LinkedHashMap<>();
        for (Sound sound : sounds) {
            grouped.computeIfAbsent(sound.rootId(), ignored -> new ArrayList<>()).add(sound);
        }
        return grouped.entrySet().stream()
                .sorted(Comparator.comparingLong(
                        (Map.Entry<String, List<Sound>> entry) ->
                                entry.getValue().stream().mapToLong(Sound::decodedBytes).sum())
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(entry -> {
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("provider", entry.getKey());
                    values.putAll(totals(entry.getValue()));
                    return values;
                })
                .toList();
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
