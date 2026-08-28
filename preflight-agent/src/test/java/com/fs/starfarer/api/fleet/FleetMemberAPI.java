package com.fs.starfarer.api.fleet;

public interface FleetMemberAPI {
    default float getDeploymentPointsCost() {
        return 0f;
    }
}
