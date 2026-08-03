package dev.starsector.preflight.agent;

import java.lang.reflect.InvocationTargetException;

/** Flushes and closes log4j 1.2 appenders on a normal JVM shutdown without linking the game jar. */
final class QuietLogShutdown {
    private QuietLogShutdown() {
    }

    static void close(boolean enabled) throws ReflectiveOperationException {
        if (!enabled) {
            return;
        }
        try {
            Class.forName("org.apache.log4j.LogManager")
                    .getMethod("shutdown")
                    .invoke(null);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Error fatal) {
                throw fatal;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw error;
        }
    }
}
