package com.fs.starfarer.campaign;

import com.fs.starfarer.api.campaign.SectorEntityToken;
import java.util.ArrayList;
import java.util.List;

/** Minimal test-side shape for the shipped exact-id candidate validity check. */
public class BaseCampaignEntity implements SectorEntityToken {
    private String id;
    private final ContainingLocation containingLocation = new ContainingLocation();

    public BaseCampaignEntity(String id) {
        this.id = id;
        containingLocation.entities.add(this);
    }

    @Override
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public ContainingLocation getContainingLocation() {
        return containingLocation;
    }

    public static final class ContainingLocation {
        private final List<SectorEntityToken> entities = new ArrayList<>();

        public List<SectorEntityToken> getAllEntities() {
            return entities;
        }
    }
}
