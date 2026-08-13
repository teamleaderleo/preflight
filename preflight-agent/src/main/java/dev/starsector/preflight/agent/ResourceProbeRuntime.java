package dev.starsector.preflight.agent;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Answers "is there a file here?" from a remembered directory listing instead of a syscall.
 *
 * <p>The game finds a resource by walking an ordered list of roots -- one per enabled mod plus the
 * core directory -- and asking each in turn whether it has the path. On a large profile that list
 * is long, and most roots do not contain most paths, so the walk is dominated by confirming
 * absence. On the measured 83-mod install one launch performs <b>1,618,401</b> of these probes for
 * 38,018 lookups, 42.6 per lookup, and they cost 5.25 s.
 *
 * <p>Listing a directory once answers every later question about it. The remembered set is small
 * -- 7,868 distinct directories across the whole launch -- because it is bounded by the shape of
 * the mod tree rather than by how many times the game searched it. A directory that does not exist
 * is remembered as an empty listing, which is the same answer for every path under it.
 *
 * <p>A listing compared by exact name is not the same question the filesystem answers. macOS and
 * Windows are case-insensitive, so {@code mod/data/strings/ship_names.json} opens a file stored as
 * {@code ship_names.JSON}, and on the measured install two mods rely on exactly that. Comparing
 * names as strings would have made those files disappear, silently, on the two platforms nearly
 * every player uses. So each directory is remembered twice: once by exact name, and once by a
 * folded key. An exact hit is a hit; a folded miss is a miss on any filesystem, because a
 * case-insensitive one can only match a name that folds equal; and the narrow case in between --
 * folds equal but is not byte-identical -- is the only one that asks the filesystem, which is the
 * one authority on whether this particular disk cares about case.
 *
 * <p>This assumes the game data on disk does not change while the game is loading it. That is what
 * a launch already assumes everywhere else: the profile fingerprint, the resource index and every
 * prepared artifact are all computed before the JVM starts and used throughout it. A mod that
 * generated a data file mid-launch and then read it back through the resolver would not see it,
 * and that is the one behaviour this trades away.
 *
 * <p>Every path fails open. A miss, an exception, or a disabled runtime all end in the same
 * {@code File.exists()} the game would have called.
 */
public final class ResourceProbeRuntime {
    static final String PLAN_ID = "resource-probe-cache-v3";
    private static final String ENABLED_PROPERTY = "preflight.resource.probeCache";

    /**
     * Absolute directory path to what is in it. Bounded by the mod tree's shape rather than by the
     * number of lookups, so it does not grow with how hard the game searches.
     */
    private static final Map<String, Listing> directories = new ConcurrentHashMap<>();

    /** Listing a directory that does not exist and one that is empty are the same answer here. */
    private static final Listing ABSENT = new Listing(Set.of(), Set.of(), true);

    /** A directory that could not be listed cannot prove that any requested child is absent. */
    private static final Listing UNKNOWN = new Listing(Set.of(), Set.of(), false);

    private static final AtomicLong probes = new AtomicLong();
    private static final AtomicLong avoided = new AtomicLong();
    private static final AtomicLong deferred = new AtomicLong();
    private static final AtomicLong failures = new AtomicLong();
    private static final AtomicLong listingFailures = new AtomicLong();

    private static volatile boolean enabled = "on".equalsIgnoreCase(System.getProperty(ENABLED_PROPERTY));

    private ResourceProbeRuntime() {
    }

    static void enable(boolean value) {
        enabled = value;
    }

    static boolean ready() {
        return enabled;
    }

    /**
     * Replaces {@code File.exists()} at every call site inside the game's resource resolver.
     *
     * <p>Public because the rewritten game class calls it, and that class is not in this package.
     */
    public static boolean exists(File file) {
        if (!enabled || file == null) {
            return file != null && file.exists();
        }
        try {
            probes.incrementAndGet();
            String parent = file.getParent();
            if (parent == null) {
                return file.exists();
            }
            Listing listing = directories.get(parent);
            if (listing == null) {
                listing = read(parent);
                directories.put(parent, listing);
            }
            Verdict verdict = listing.verdictFor(file.getName());
            if (verdict == Verdict.ASK) {
                // Something differs from the requested name only by case or Unicode form. Whether
                // that counts as the same file is a property of this disk, not something to assume.
                deferred.incrementAndGet();
                return file.exists();
            }
            avoided.incrementAndGet();
            return verdict == Verdict.PRESENT;
        } catch (RuntimeException | LinkageError unexpected) {
            // Fail open: the game must behave exactly as it would have, whatever went wrong here.
            failures.incrementAndGet();
            return file.exists();
        }
    }

    /**
     * Preserves the resolver's per-root open while keeping the transformed call boundary stable.
     *
     * <p>An earlier version tried to skip an entire root from a remembered directory listing. Two
     * separate live launches proved that shortcut could report absence for an exactly named file
     * that existed in an enabled mod. Since the runtime could not distinguish its false negative
     * from a real miss, the only safe behavior at this boundary is to call the original method.
     * The lower-level {@link #exists(File)} memo remains active and still removes the syscalls from
     * the original walk without taking ownership of resource selection.
     *
     * <p>Public because the rewritten game class calls it, and that class is not in this package.
     */
    public static InputStream open(Object resolver, String path, Object root, MethodHandle vanilla)
            throws Throwable {
        return (InputStream) vanilla.invoke(resolver, path, root);
    }

    private static Listing read(String directory) {
        String[] snapshot = new File(directory).list();
        if (snapshot != null) {
            return listing(snapshot, true);
        }

        // File.list() deliberately collapses both absence and an I/O failure into null. Retry the
        // exceptional path through NIO so only a positively identified absence becomes cacheable.
        ArrayList<String> listed = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(Path.of(directory))) {
            for (Path entry : entries) {
                Path name = entry.getFileName();
                if (name != null) {
                    listed.add(name.toString());
                }
            }
        } catch (NoSuchFileException | NotDirectoryException absent) {
            return ABSENT;
        } catch (IOException | DirectoryIteratorException | SecurityException unavailable) {
            // File.list() reports both a missing directory and an I/O failure as null. Treating
            // that ambiguous result as an empty directory can make a real resource disappear for
            // the rest of the launch. NIO distinguishes proven absence; everything else is an
            // unknown listing and therefore defers every child probe to vanilla.
            listingFailures.incrementAndGet();
            return UNKNOWN;
        }
        return listing(listed.toArray(String[]::new), true);
    }

    private static Listing listing(String[] listed, boolean complete) {
        Set<String> exact = Set.of(listed);
        Set<String> folded = new HashSet<>(Math.max(4, listed.length * 2));
        for (String name : listed) {
            folded.add(fold(name));
        }
        return new Listing(exact, Set.copyOf(folded), complete);
    }

    /** Exposes the safety rule to tests without exposing or performing filesystem I/O. */
    static boolean listingClaimsAbsent(String[] listed, boolean complete, String requestedName) {
        return listing(listed, complete).verdictFor(requestedName) == Verdict.ABSENT;
    }

    /** What a remembered listing can say about a name on its own. */
    private enum Verdict {
        PRESENT,
        ABSENT,
        /** Folds equal to something here but is not spelled the same; only the disk knows. */
        ASK
    }

    /**
     * The key two names share when a case-insensitive filesystem might treat them as one file.
     *
     * <p>Case-insensitive filesystems tend also to be normalisation-insensitive -- APFS matches a
     * composed name against a decomposed one on disk -- so the key folds both.
     */
    private static String fold(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    /** One directory's names, by exact spelling and by the key case-insensitive disks compare. */
    private record Listing(Set<String> exact, Set<String> folded, boolean complete) {
        Verdict verdictFor(String name) {
            if (exact.contains(name)) {
                return Verdict.PRESENT;
            }
            if (!complete) {
                return Verdict.ASK;
            }
            // Nothing here folds to this name, so no filesystem -- however case-insensitive -- has
            // a file to offer. This is the answer for the overwhelming majority of probes.
            return folded.contains(fold(name)) ? Verdict.ASK : Verdict.ABSENT;
        }
    }

    static Map<String, Object> report() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("enabled", enabled);
        report.put("probes", probes.get());
        report.put("probesAnsweredWithoutSyscall", avoided.get());
        report.put("probesDeferredToTheFilesystem", deferred.get());
        report.put("rootOpenShortcutEnabled", false);
        report.put("rootOpensSkippedWholesale", 0L);
        report.put("directoriesRemembered", directories.size());
        report.put("failures", failures.get());
        report.put("listingFailures", listingFailures.get());
        return report;
    }

    static void reset() {
        directories.clear();
        probes.set(0);
        avoided.set(0);
        deferred.set(0);
        failures.set(0);
        listingFailures.set(0);
    }
}
