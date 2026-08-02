package dev.starsector.preflight.agent;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A class loader that counts the lookups the walk performs.
 *
 * <p>Every {@code Class.forName(fqn, false, loader)} in the walk reaches {@code loadClass}, which
 * makes the shortcut's whole point directly measurable: the standalone replay of the real profile
 * needed 7,022 attempts to resolve 665 names, against 665 when the winning package was known.
 *
 * <p>The generated fixture reads {@link #LOADER} and then calls {@code getParent()} on it, exactly as
 * the game calls {@code getParent()} on {@code ScriptStore}'s loader, so the counting loader is the
 * parent of the one the fixture returns.
 */
public final class RuleCommandLoaderProbe extends ClassLoader {
    public static final AtomicLong ATTEMPTS = new AtomicLong();

    /** Read by the generated fixture. Replaced by {@link #reset()} so counts start from zero. */
    public static volatile ClassLoader LOADER = fresh();

    private RuleCommandLoaderProbe() {
        super(RuleCommandLoaderProbe.class.getClassLoader());
    }

    public static void reset() {
        ATTEMPTS.set(0);
        LOADER = fresh();
    }

    private static ClassLoader fresh() {
        return new ClassLoader(new RuleCommandLoaderProbe()) {
        };
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        ATTEMPTS.incrementAndGet();
        return super.loadClass(name, resolve);
    }
}
