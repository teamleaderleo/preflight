package com.fs.starfarer.api;

import com.fs.starfarer.api.combat.ShipVariantAPI;
import java.util.List;

public interface SettingsAPI {
    List<String> getAllVariantIds();

    ShipVariantAPI getVariant(String id);
}
