package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.util.container.repo.ObjectRepository;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The index in front of {@code BaseLocation.getEntityById} is only worth having if it cannot answer
 * with an entity the shipped method would not. So these run the rewritten class against
 * {@link com.fs.starfarer.campaign.BaseLocation}, a fixture that reproduces the shipped behaviour,
 * and compare answers rather than inspecting instructions.
 */
class EntityLookupPlanTest {
    private static final String ORIGINAL = "preflight$original$getEntityById";

    @BeforeEach
    @AfterEach
    void clearGate() {
        System.clearProperty(EntityLookupRuntime.ENABLED_PROPERTY);
        EntityLookupRuntime.beginSession();
    }

    @Test
    void theOriginalIsKeptVerbatimAndTheWrapperTakesItsName() throws Exception {
        ClassNode before = read(original());
        ClassNode after = read(rewritten());

        MethodNode shipped = method(before, EntityLookupPlan.LOOKUP_METHOD);
        MethodNode kept = method(after, ORIGINAL);
        assertNotNull(kept, "the shipped method must survive under a new name");
        assertEquals(shipped.instructions.size(), kept.instructions.size(),
                "and survive verbatim, not rewritten");
        assertNotNull(method(after, EntityLookupPlan.LOOKUP_METHOD),
                "the wrapper must occupy the original name");
        assertEquals(EntityLookupPlan.LOOKUP_DESCRIPTOR,
                method(after, EntityLookupPlan.LOOKUP_METHOD).desc);
    }

    @Test
    void withTheGateShutEveryAnswerComesFromTheShippedMethod() throws Exception {
        Fixture fixture = Fixture.of(64);

        assertFalse(EntityLookupRuntime.enabled(), "installing must not switch it on");
        assertSame(fixture.entities.get(7), fixture.lookup("entity_7"));
        assertNull(fixture.lookup("no_such_entity"));
        assertEquals(0L, EntityLookupRuntime.counters().get("served"));
    }

    @Test
    void theGatedAndUngatedAnswersAreIdenticalAcrossTheWholeLocation() throws Exception {
        Fixture gated = Fixture.of(64);
        Fixture plain = Fixture.of(64);

        System.setProperty(EntityLookupRuntime.ENABLED_PROPERTY, "true");
        assertTrue(EntityLookupRuntime.enabled());
        for (int i = 0; i < 64; i++) {
            String id = "entity_" + i;
            assertEquals(index(plain, plain.lookupWithGateOff(id)), index(gated, gated.lookup(id)),
                    "the index must resolve " + id + " to the same position in the entity list");
        }
        assertNull(gated.lookup("no_such_entity"), "an absent id must still be absent");
        assertTrue((Long) EntityLookupRuntime.counters().get("served") > 0,
                "and the index must actually have been used");
    }

    @Test
    void theCaseInsensitiveFallbackIsPreserved() throws Exception {
        Fixture fixture = Fixture.of(32);

        Object ungated = fixture.lookupWithGateOff("ENTITY_3");
        System.setProperty(EntityLookupRuntime.ENABLED_PROPERTY, "true");
        Object gated = fixture.lookup("ENTITY_3");

        assertSame(fixture.entities.get(3), ungated, "the shipped fallback matches case-insensitively");
        assertSame(ungated, gated, "so the index has to as well");
    }

    @Test
    void aDuplicatedIdResolvesTheSameWayItDidBefore() throws Exception {
        Fixture fixture = Fixture.of(32);
        // Two entities, same id, different case: the exact map takes the last, the fallback the
        // first. Getting that precedence backwards is the one way an index can be wrong and look right.
        Object first = fixture.add("Twin");
        Object second = fixture.add("twin");

        Object exactUngated = fixture.lookupWithGateOff("twin");
        Object fuzzyUngated = fixture.lookupWithGateOff("TWIN");
        System.setProperty(EntityLookupRuntime.ENABLED_PROPERTY, "true");

        assertSame(exactUngated, fixture.lookup("twin"), "exact match precedence must be preserved");
        assertSame(fuzzyUngated, fixture.lookup("TWIN"),
                "and so must the fallback's first-match-wins");
        assertSame(second, exactUngated);
        assertSame(first, fuzzyUngated);
    }

    @Test
    void anEntityRemovedFromTheRepositoryFallsThroughToTheShippedMethod() throws Exception {
        Fixture fixture = Fixture.of(32);
        System.setProperty(EntityLookupRuntime.ENABLED_PROPERTY, "true");
        Object doomed = fixture.entities.get(5);
        assertSame(doomed, fixture.lookup("entity_5"), "indexed while present");

        // Out of the repository, still in the list, and the list length unchanged -- so the index is
        // stale in exactly the way the size stamp cannot see. The membership check is what catches it.
        fixture.repository.remove(doomed);

        assertSame(fixture.lookupWithGateOff("entity_5"), fixture.lookup("entity_5"),
                "a stale index entry must not change the answer, only who computes it");
    }

    @Test
    void aLocationTooSmallToBeWorthIndexingIsLeftAlone() throws Exception {
        Fixture fixture = Fixture.of(4);
        System.setProperty(EntityLookupRuntime.ENABLED_PROPERTY, "true");

        assertSame(fixture.entities.get(2), fixture.lookup("entity_2"));
        assertEquals(0L, EntityLookupRuntime.counters().get("served"),
                "a four-entity location is not worth an index");
    }

    @Test
    void theIndexIsRebuiltWhenTheEntityListGrows() throws Exception {
        Fixture fixture = Fixture.of(32);
        System.setProperty(EntityLookupRuntime.ENABLED_PROPERTY, "true");
        fixture.lookup("entity_0");
        long rebuilds = (Long) EntityLookupRuntime.counters().get("rebuilds");

        Object added = fixture.add("late_arrival");

        assertSame(added, fixture.lookup("late_arrival"));
        assertTrue((Long) EntityLookupRuntime.counters().get("rebuilds") > rebuilds,
                "a changed entity count has to invalidate the index");
    }

    @Test
    void anAlreadyRewrittenClassIsDeclined() throws Exception {
        byte[] once = rewritten();
        assertNull(EntityLookupPlan.transform(ClassSignature.parse(once), once),
                "weaving twice would leave the wrapper calling itself");
    }

    @Test
    void aClassThatIsNotTheLocationIsDeclined() throws Exception {
        byte[] other = bytesOf(ObjectRepository.class);
        assertNull(EntityLookupPlan.transform(ClassSignature.parse(other), other));
    }

    // ---------------------------------------------------------------------------------------------

    /** The rewritten location, loaded, beside an unrewritten one for comparison. */
    private static final class Fixture {
        final Object location;
        final List<SectorEntityToken> entities;
        final ObjectRepository repository;
        private final Method lookup;
        private final com.fs.starfarer.campaign.BaseLocation shipped;

        @SuppressWarnings("unchecked")
        static Fixture of(int size) throws Exception {
            Class<?> type = define(rewritten());
            Object location = type.getDeclaredConstructor().newInstance();
            Fixture fixture = new Fixture(
                    location,
                    type.getMethod(EntityLookupPlan.LOOKUP_METHOD, String.class),
                    (List<SectorEntityToken>) type.getMethod(EntityLookupPlan.ENTITIES_METHOD)
                            .invoke(location),
                    (ObjectRepository) type.getMethod(EntityLookupPlan.OBJECTS_METHOD)
                            .invoke(location));
            for (int i = 0; i < size; i++) {
                fixture.add("entity_" + i);
            }
            return fixture;
        }

        private Fixture(Object location, Method lookup, List<SectorEntityToken> entities,
                ObjectRepository repository) {
            this.location = location;
            this.lookup = lookup;
            this.entities = entities;
            this.repository = repository;
            this.shipped = new com.fs.starfarer.campaign.BaseLocation();
        }

        /** Through the wrapper. */
        Object lookup(String id) throws Exception {
            return lookup.invoke(location, id);
        }

        /**
         * What the shipped method answers, computed by mirroring this location's contents onto an
         * untouched fixture. Simply clearing the gate would also work, but running both against one
         * object leaves the two answers sharing a rebuilt map and hides an ordering divergence.
         */
        Object lookupWithGateOff(String id) {
            shipped.getAllEntities().clear();
            shipped.advance();
            for (SectorEntityToken entity : entities) {
                shipped.getAllEntities().add(entity);
                if (repository.contains(entity)) {
                    shipped.getObjects().add(entity);
                } else {
                    shipped.getObjects().remove(entity);
                }
            }
            return shipped.getEntityById(id);
        }

        Object add(String id) {
            SectorEntityToken entity = () -> id;
            entities.add(entity);
            repository.add(entity);
            return entity;
        }
    }

    private static int index(Fixture fixture, Object entity) {
        return fixture.entities.indexOf(entity);
    }

    private static byte[] original() throws Exception {
        return bytesOf(com.fs.starfarer.campaign.BaseLocation.class);
    }

    private static byte[] rewritten() throws Exception {
        byte[] original = original();
        byte[] result = EntityLookupPlan.transform(ClassSignature.parse(original), original);
        assertNotNull(result, "the plan must rewrite a location carrying all three methods");
        return result;
    }

    private static byte[] bytesOf(Class<?> type) throws Exception {
        String resource = type.getName().replace('.', '/') + ".class";
        try (var stream = EntityLookupPlanTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream, resource);
            return stream.readAllBytes();
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> name.equals(m.name)).findFirst().orElse(null);
    }

    /** Defines the rewritten location under its own name, shadowing the fixture on the classpath. */
    private static Class<?> define(byte[] bytes) {
        return new ClassLoader(EntityLookupPlanTest.class.getClassLoader()) {
            Class<?> define() {
                return defineClass(
                        EntityLookupPlan.TARGET_CLASS.replace('/', '.'), bytes, 0, bytes.length);
            }
        }.define();
    }
}
