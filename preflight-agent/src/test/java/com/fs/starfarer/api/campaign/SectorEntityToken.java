package com.fs.starfarer.api.campaign;

/**
 * Test fixture. Declares only the one member {@link dev.starsector.preflight.agent.EntityLookupPlan}
 * needs to exist for its {@code CHECKCAST} to resolve and for the runtime to read an id.
 *
 * <p>This is written here from the method signature, not copied from the game: Starsector's jars are
 * not redistributable and nothing from them is in this repository. A declaration written to
 * interoperate with an installed jar is not the jar.
 */
public interface SectorEntityToken {
    String getId();
}
