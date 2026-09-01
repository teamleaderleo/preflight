package com.fs.starfarer.api.combat;

import com.fs.starfarer.api.fleet.FleetMemberAPI;
import java.util.List;
import org.lwjgl.util.vector.Vector2f;

/** Minimal test-side fleet-manager API used by adapter and stress-fixture regression tests. */
public interface CombatFleetManagerAPI {
    default List<FleetMemberAPI> getDeployedCopy() {
        return List.of();
    }

    default ShipAPI getShipFor(FleetMemberAPI member) {
        return null;
    }

    default ShipAPI spawnShipOrWing(
            String variantId, Vector2f location, float facing, float delay) {
        return null;
    }

    default void removeDeployed(ShipAPI ship, boolean withExplosion) {
    }
}
