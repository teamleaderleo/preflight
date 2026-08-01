package dev.starsector.preflight.core;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.Objects;

/**
 * Finds which XStream object ids a save file actually refers back to.
 *
 * <p>Starsector serialises campaigns with abbreviated id references: an element that might be
 * pointed at later carries {@code z="N"}, and a pointer to it is {@code ref="N"}. XStream's
 * unmarshaller registers <em>every</em> {@code z=} it meets into a reference map so that a later
 * {@code ref=} can resolve. On a measured 42.6 MB campaign that is 440,117 registrations serving
 * 40,659 lookups -- <b>90.8% of the map is never read</b>
 * ([evidence](../../../../../../../docs/evidence/2026-08-02-what-is-left-measured-without-launching.md)).
 *
 * <p>The set of ids that matter is computable from the same bytes being loaded, by one linear scan
 * for {@code ref="}. No cache, no prepare step, no persisted artifact, and no dependence on a
 * hardware intrinsic -- which matters here, because Starsector runs under Rosetta 2 on Apple
 * Silicon and anything that leans on an x86 extension runs at emulated speed.
 *
 * <h2>The scan may only ever over-approximate</h2>
 *
 * <p>Registering an id nobody asks for wastes a map entry. Failing to register one that is asked
 * for breaks the load. The two errors are not symmetric, so every ambiguity in this class resolves
 * towards including more ids, and anything it does not fully understand makes the whole result
 * {@linkplain Result#usable() unusable} so the caller registers everything and behaves exactly as
 * it does today.
 */
public final class SaveReferenceScan {
    /** Attribute that points back at a previously defined object. */
    private static final byte[] REFERENCE = ascii(" ref=\"");
    /** Attribute that defines an object other elements may point at. */
    private static final byte[] DEFINITION = ascii(" z=\"");

    /**
     * Ids above this are treated as unrecognised rather than allocated for. A campaign an order of
     * magnitude past the largest one measured is more likely to be a parse gone wrong than a real
     * save, and a wrong guess here would allocate a bitset sized by attacker-ish input.
     */
    static final int MAX_SUPPORTED_ID = 64_000_000;

    private static final int BUFFER_BYTES = 1 << 20;

    private SaveReferenceScan() {
    }

    public static Result scan(Path saveFile) throws IOException {
        Objects.requireNonNull(saveFile, "saveFile");
        try (InputStream input = new BufferedInputStream(Files.newInputStream(saveFile), BUFFER_BYTES)) {
            return scan(input);
        }
    }

    public static Result scan(byte[] content) {
        Objects.requireNonNull(content, "content");
        try (InputStream input = new java.io.ByteArrayInputStream(content)) {
            return scan(input);
        } catch (IOException impossible) {
            throw new IllegalStateException("Reading an in-memory array cannot fail", impossible);
        }
    }

    /**
     * Streams the input once, matching both attributes with byte-level state machines.
     *
     * <p>Matching byte by byte rather than searching within a window is deliberate: an attribute can
     * straddle any read boundary, and a state machine handles that without carry buffers or the
     * off-by-one bugs that come with them.
     */
    public static Result scan(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        BitSet referenced = new BitSet();
        Matcher reference = new Matcher(REFERENCE);
        Matcher definition = new Matcher(DEFINITION);
        long referenceUses = 0;
        long definitions = 0;
        int highestReferenced = -1;
        boolean understood = true;

        byte[] buffer = new byte[BUFFER_BYTES];
        int read;
        while ((read = input.read(buffer)) != -1) {
            for (int index = 0; index < read; index++) {
                byte current = buffer[index];

                int referencedId = reference.accept(current);
                if (referencedId == Matcher.MALFORMED) {
                    understood = false;
                } else if (referencedId >= 0) {
                    referenceUses++;
                    referenced.set(referencedId);
                    if (referencedId > highestReferenced) {
                        highestReferenced = referencedId;
                    }
                }

                int definedId = definition.accept(current);
                if (definedId == Matcher.MALFORMED) {
                    understood = false;
                } else if (definedId >= 0) {
                    definitions++;
                }
            }
        }

        return new Result(
                understood,
                definitions,
                referenceUses,
                referenced.cardinality(),
                highestReferenced,
                referenced);
    }

    /**
     * What one scan learned.
     *
     * @param usable            false when the scan met something it could not interpret; the caller
     *                          must then register every id, which is current behaviour
     * @param definedIds        occurrences of {@code z="N"}
     * @param referenceUses     occurrences of {@code ref="N"}, including repeats of the same target
     * @param referencedIds     distinct ids that are pointed at
     * @param highestReferencedId largest referenced id, or -1 when nothing is referenced
     */
    public record Result(
            boolean usable,
            long definedIds,
            long referenceUses,
            long referencedIds,
            int highestReferencedId,
            BitSet referenced) {
        public Result {
            referenced = (BitSet) Objects.requireNonNull(referenced, "referenced").clone();
        }

        /**
         * Whether an id has to be registered.
         *
         * <p>Always true when the scan is unusable, so a caller that consults this without checking
         * {@link #usable()} still behaves correctly -- it simply registers everything.
         */
        public boolean mustRegister(int id) {
            return !usable || id < 0 || referenced.get(id);
        }

        /** Fraction of definitions that are never pointed at, in {@code [0,1]}. */
        public double redundancy() {
            if (!usable || definedIds <= 0) {
                return 0.0;
            }
            long dead = Math.max(0, definedIds - referencedIds);
            return (double) dead / (double) definedIds;
        }

        public BitSet referenced() {
            return (BitSet) referenced.clone();
        }
    }

    /** Matches one literal, then accumulates the decimal id that follows it. */
    private static final class Matcher {
        static final int NONE = -1;
        static final int MALFORMED = -2;

        private final byte[] literal;
        private int progress;
        private boolean readingDigits;
        private long value;
        private boolean sawDigit;

        Matcher(byte[] literal) {
            this.literal = literal;
        }

        /**
         * @return the completed id, {@link #NONE} while still matching, or {@link #MALFORMED} for an
         *         attribute whose value this class does not understand
         */
        int accept(byte current) {
            if (readingDigits) {
                if (current >= '0' && current <= '9') {
                    sawDigit = true;
                    value = value * 10 + (current - '0');
                    if (value > MAX_SUPPORTED_ID) {
                        reset();
                        return MALFORMED;
                    }
                    return NONE;
                }
                if (current == '"') {
                    boolean complete = sawDigit;
                    int result = (int) value;
                    reset();
                    // An empty or non-numeric id is a format this class was not written for.
                    return complete ? result : MALFORMED;
                }
                // A non-digit inside the value: the attribute is not a plain integer.
                reset();
                return MALFORMED;
            }

            if (current == literal[progress]) {
                progress++;
                if (progress == literal.length) {
                    progress = 0;
                    readingDigits = true;
                    value = 0;
                    sawDigit = false;
                }
                return NONE;
            }

            // Restart, but allow this byte to begin a fresh match -- otherwise a run such as
            // "  ref=" would be missed because the first space consumed the only start position.
            progress = current == literal[0] ? 1 : 0;
            return NONE;
        }

        private void reset() {
            progress = 0;
            readingDigits = false;
            value = 0;
            sawDigit = false;
        }
    }

    private static byte[] ascii(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }
}
