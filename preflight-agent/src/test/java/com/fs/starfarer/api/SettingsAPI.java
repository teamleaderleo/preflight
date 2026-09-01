package com.fs.starfarer.api;

import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import java.util.List;

public interface SettingsAPI {
    List<String> getAllVariantIds();

    ShipVariantAPI getVariant(String id);

    default FleetMemberAPI createFleetMember(FleetMemberType type, ShipVariantAPI variant) {
        return null;
    }

    default boolean doesVariantExist(String id) {
        return false;
    }
}
