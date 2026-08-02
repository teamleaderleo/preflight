package dev.starsector.preflight.agent.commandsb;

/** A command name absent from every earlier package, so its whole prefix is ClassNotFoundException. */
public class Beta {
    public static volatile int constructed;

    public Beta() {
        constructed++;
    }
}
