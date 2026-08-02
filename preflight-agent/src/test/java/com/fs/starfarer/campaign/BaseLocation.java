package com.fs.starfarer.campaign;

import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.util.container.repo.ObjectRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Test fixture reproducing the shipped {@code getEntityById}'s <em>behaviour</em>, so that the
 * rewrite can be checked against answers rather than against bytecode.
 *
 * <p>The shape is taken from the disassembly and written out here in Java:
 *
 * <ul>
 *   <li>{@code idToEntity} is nulled every frame by {@code advance} and rebuilt lazily from
 *       {@code getAllEntities()}, so a duplicate id resolves to the <em>last</em> entity in list
 *       order;
 *   <li>on any failure of that fast path -- a map miss, a null containing location, or the
 *       membership check failing -- it falls back to a linear scan comparing lowercased ids, which
 *       returns the <em>first</em> match in list order;
 *   <li>the fallback applies no membership or location check of its own.
 * </ul>
 *
 * <p>Nothing here is copied from Starsector, whose jars are not redistributable and are not in this
 * repository. It is a model of the observed behaviour, written to be disagreed with by a test if the
 * rewrite ever diverges from it.
 */
public class BaseLocation {
    private final ObjectRepository objects = new ObjectRepository();
    private final List<SectorEntityToken> entities = new ArrayList<>();
    private Map<String, SectorEntityToken> idToEntity;

    public List<SectorEntityToken> getAllEntities() {
        return entities;
    }

    public ObjectRepository getObjects() {
        return objects;
    }

    /** What {@code advance} does to the map once a frame. */
    public void advance() {
        idToEntity = null;
    }

    public SectorEntityToken getEntityById(String id) {
        if (idToEntity == null) {
            idToEntity = new HashMap<>();
            for (SectorEntityToken entity : getAllEntities()) {
                idToEntity.put(entity.getId(), entity);
            }
        }
        if (idToEntity.containsKey(id)) {
            SectorEntityToken entity = idToEntity.get(id);
            if (entity != null && getObjects().contains(entity)) {
                return entity;
            }
        }
        for (SectorEntityToken entity : getAllEntities()) {
            if (entity.getId() != null
                    && entity.getId().toLowerCase(Locale.ROOT).equals(id.toLowerCase(Locale.ROOT))) {
                return entity;
            }
        }
        return null;
    }
}
