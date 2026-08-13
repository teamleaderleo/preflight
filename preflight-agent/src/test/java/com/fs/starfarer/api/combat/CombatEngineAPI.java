package com.fs.starfarer.api.combat;

/** Minimal test-side signature used to execute the GraphicsLib adapter fixture. */
public interface CombatEngineAPI {
    CombatFleetManagerAPI getFleetManager(int owner);
}
