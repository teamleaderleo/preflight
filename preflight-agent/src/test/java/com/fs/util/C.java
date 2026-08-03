package com.fs.util;

/**
 * A stand-in for the game's resource resolver, shaped the way the runtime reads it.
 *
 * <p>{@link dev.starsector.preflight.agent.ResourceProbeRuntime} decides whether a root is a
 * directory on disk by reading the resolver's own {@code switch} map over root kinds, and it finds
 * that map by name on {@code com.fs.util.C}. Testing that decision needs a class of that name with
 * that shape; testing it against the real one would need the game's jar. So this is the shape,
 * written out, and nothing here is copied from the game -- only the names the runtime looks up.
 *
 * <p>One field name is non-ASCII, because the name is what the runtime looks up. The build pins
 * {@code project.build.sourceEncoding} to UTF-8, so it reads the same everywhere.
 */
public final class C {
    /** The kinds of root the resolver knows, in the order the real class declares them. */
    public enum o {
        DIRECTORY,
        BARE_PATH,
        CLASSPATH
    }

    /** One root on the search path: what kind it is, and where it points. */
    public static final class Oo {
        public o o00000;
        /** The directory a directory-kind root stands for. */
        public String Ó00000;

        public Oo(o kind, String directory) {
            this.o00000 = kind;
            this.Ó00000 = directory;
        }
    }

    /**
     * The {@code switch} map: root kind ordinal to the case label the resolver branches on. Case 1
     * is the directory branch, which is the only one the runtime answers for.
     */
    static int[] o00000() {
        return new int[] {1, 2, 3};
    }

    private C() {
    }
}
