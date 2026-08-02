package dev.starsector.preflight.agent.commandsa;

/**
 * A command name that exists in an earlier package but cannot be instantiated.
 *
 * <p>This is the case the cache must never shortcut. {@code Class.forName} is called with
 * {@code initialize = false}, but the {@code newInstance()} that follows initialises, so by the time
 * vanilla catches the {@code InstantiationException} this class's static initialiser has already
 * run. Skipping the walk would skip that, which is why {@code initialised} is observable.
 */
public abstract class Alpha {
    public static volatile boolean initialised;

    static {
        initialised = true;
    }
}
