package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.campaign.BaseCampaignEntity;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EntityLookupMutationTrackingTest {
    @BeforeEach
    void enable() {
        EntityLookupRuntime.beginSession();
        EntityLookupRuntime.locationInstalled();
        EntityLookupRuntime.repositoryInstalled();
        EntityLookupRuntime.idMutationInstalled();
        System.setProperty(EntityLookupRuntime.ENABLED_PROPERTY, "true");
    }

    @AfterEach
    void reset() {
        System.clearProperty(EntityLookupRuntime.ENABLED_PROPERTY);
        EntityLookupRuntime.beginSession();
    }

    @Test
    void repeatedTrackedMissesValidateInConstantTime() {
        List<Object> entities = trackedEntities(32);
        Object location = new Object();

        assertTrue(EntityLookupRuntime.missing(
                EntityLookupRuntime.lookup(entities, location, "missing")));
        assertTrue(EntityLookupRuntime.missing(
                EntityLookupRuntime.lookup(entities, location, "missing")));

        assertEquals(2L, EntityLookupRuntime.counters().get("fastValidations"));
        assertEquals(0L, EntityLookupRuntime.counters().get("deepValidations"));
        assertEquals(0L, EntityLookupRuntime.counters().get("validatedReferences"));
        assertEquals(1L, EntityLookupRuntime.counters().get("trackedRepositoryLists"));
        assertEquals(0L, EntityLookupRuntime.counters().get("untrackedListValidations"));
    }

    @Test
    void sameSizeDirectListReplacementRebuildsBeforeAnswering() {
        List<Object> entities = trackedEntities(32);
        Object location = new Object();
        assertTrue(EntityLookupRuntime.missing(
                EntityLookupRuntime.lookup(entities, location, "replacement")));
        long rebuilds = (Long) EntityLookupRuntime.counters().get("rebuilds");

        BaseCampaignEntity replacement = new BaseCampaignEntity("replacement");
        entities.set(7, replacement);

        assertSame(replacement, EntityLookupRuntime.lookup(entities, location, "replacement"));
        assertTrue((Long) EntityLookupRuntime.counters().get("rebuilds") > rebuilds);
    }

    @Test
    void sameSizeSubListReplacementAlsoRebuilds() {
        List<Object> entities = trackedEntities(32);
        Object location = new Object();
        assertTrue(EntityLookupRuntime.missing(
                EntityLookupRuntime.lookup(entities, location, "replacement")));
        long rebuilds = (Long) EntityLookupRuntime.counters().get("rebuilds");

        BaseCampaignEntity replacement = new BaseCampaignEntity("replacement");
        entities.subList(5, 10).set(2, replacement);

        assertSame(replacement, EntityLookupRuntime.lookup(entities, location, "replacement"));
        assertTrue((Long) EntityLookupRuntime.counters().get("rebuilds") > rebuilds);
    }

    @Test
    void reviewedIdMutationRebuildsBeforeAnswering() {
        List<Object> entities = trackedEntities(32);
        Object location = new Object();
        assertTrue(EntityLookupRuntime.missing(
                EntityLookupRuntime.lookup(entities, location, "renamed")));
        long rebuilds = (Long) EntityLookupRuntime.counters().get("rebuilds");

        BaseCampaignEntity renamed = (BaseCampaignEntity) entities.get(9);
        EntityLookupRuntime.entityIdChanging(renamed);
        renamed.setId("renamed");

        assertSame(renamed, EntityLookupRuntime.lookup(entities, location, "renamed"));
        assertTrue((Long) EntityLookupRuntime.counters().get("rebuilds") > rebuilds);
        assertEquals(1L, EntityLookupRuntime.counters().get("idMutations"));
    }

    @Test
    void anOverriddenIdSetterRetainsDeepValidation() {
        List<Object> entities = trackedEntities(31);
        entities.add(new OverridingEntity("custom"));
        Object location = new Object();

        assertTrue(EntityLookupRuntime.missing(
                EntityLookupRuntime.lookup(entities, location, "missing")));
        assertTrue(EntityLookupRuntime.missing(
                EntityLookupRuntime.lookup(entities, location, "missing")));

        assertEquals(0L, EntityLookupRuntime.counters().get("fastValidations"));
        assertTrue((Long) EntityLookupRuntime.counters().get("deepValidations") >= 2L);
        assertTrue((Long) EntityLookupRuntime.counters().get("validatedReferences") >= 64L);
        assertTrue((Long) EntityLookupRuntime.counters().get("unsafeIdEntities") >= 1L);
    }

    @Test
    void partialInstallationCannotEnableTheIndex() {
        EntityLookupRuntime.beginSession();
        EntityLookupRuntime.locationInstalled();
        assertNull(EntityLookupRuntime.lookup(trackedEntities(32), new Object(), "entity_1"));
    }

    @Test
    void exactCandidateValidationUsesRepositoryMembership() {
        BaseCampaignEntity candidate = new BaseCampaignEntity("candidate");
        assertTrue(EntityLookupRuntime.validCandidate(candidate, "candidate"));

        candidate.getContainingLocation().getObjects().remove(candidate);

        assertFalse(EntityLookupRuntime.validCandidate(candidate, "candidate"));
    }

    private static List<Object> trackedEntities(int size) {
        List<Object> entities = EntityLookupRuntime.newRepositoryList(SectorEntityToken.class);
        for (int i = 0; i < size; i++) {
            entities.add(new BaseCampaignEntity("entity_" + i));
        }
        return entities;
    }

    private static final class OverridingEntity extends BaseCampaignEntity {
        private OverridingEntity(String id) {
            super(id);
        }

        @Override
        public void setId(String id) {
            super.setId(id);
        }
    }
}
