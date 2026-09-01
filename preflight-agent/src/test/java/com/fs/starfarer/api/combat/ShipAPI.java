package com.fs.starfarer.api.combat;

import com.fs.starfarer.api.fleet.FleetMemberAPI;

/** Minimal test-side ship API for retained combat-workload fingerprints. */
public interface ShipAPI extends CombatEntityAPI {
    FleetMemberAPI getFleetMember();

    int getOwner();

    boolean isAlive();

    boolean isHulk();

    boolean isFighter();

    float getHitpoints();

    float getMaxHitpoints();

    float getFluxLevel();
}
