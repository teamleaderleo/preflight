package dev.starsector.preflight.agent.commandsb;

/** The class vanilla's walk actually resolves {@code Alpha} to, after the abstract one fails. */
public class Alpha {
    public static volatile int constructed;

    public Alpha() {
        constructed++;
    }
}
