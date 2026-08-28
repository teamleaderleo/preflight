package com.fs.starfarer.api.combat;

import java.util.List;

/** Minimal test-side combat API used by adapter and stress-fixture regression tests. */
public interface CombatEngineAPI {
    CombatFleetManagerAPI getFleetManager(int owner);

    default boolean isSimulation() {
        return false;
    }

    default boolean isPaused() {
        return false;
    }

    default void setPaused(boolean paused) {
    }

    default void setDoNotEndCombat(boolean value) {
    }

    default void setPlayerShipExternal(ShipAPI ship) {
    }

    default float getMapWidth() {
        return 0f;
    }

    default float getMapHeight() {
        return 0f;
    }

    default List<ShipAPI> getShips() {
        return List.of();
    }

    default List<Object> getMissiles() {
        return List.of();
    }

    default List<Object> getProjectiles() {
        return List.of();
    }

    default float getTotalElapsedTime(boolean includingPaused) {
        return 0f;
    }

    default boolean isCombatOver() {
        return false;
    }
}
