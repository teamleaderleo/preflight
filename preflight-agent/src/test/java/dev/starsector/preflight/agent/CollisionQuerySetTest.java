package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CollisionQuerySetTest {
    @BeforeEach
    void resetTelemetryAndCapacityHint() {
        CollisionQuerySet.beginSession();
    }

    @Test
    void learnsThePreviousCompletedQueryCapacityForTheSameShape() {
        CollisionQuerySet first = new CollisionQuerySet(10, 14, 20, 24);
        for (int index = 0; index < 100; index++) first.add(index);
        assertEquals(100, toList(first).size());

        CollisionQuerySet second = new CollisionQuerySet(104, 100, 204, 200);
        for (int index = 0; index < 100; index++) second.add(index);
        assertEquals(100, toList(second).size());

        Map<String, Object> telemetry = CollisionQuerySet.telemetry();
        assertEquals(2L, telemetry.get("completedQueries"));
        assertEquals(200L, telemetry.get("totalValues"));
        assertEquals(100L, telemetry.get("maximumValues"));
        assertEquals(4L, telemetry.get("totalGrowths"));
        assertEquals(4L, telemetry.get("avoidedGrowths"));
        assertEquals(1L, telemetry.get("exactInitialCapacities"));
        assertEquals(1L, telemetry.get("undersizedInitialCapacities"));
        assertEquals(0L, telemetry.get("oversizedInitialCapacities"));
        assertEquals(1L, telemetry.get("hintHits"));
        assertEquals(1L, telemetry.get("hintMisses"));
        assertEquals(1L, telemetry.get("hintSlotFills"));
    }

    @Test
    void aDifferentShapeDoesNotInheritAnOversizedCapacity() {
        CollisionQuerySet large = new CollisionQuerySet(0, 100, 0, 100);
        for (int index = 0; index < 5_000; index++) large.add(index);
        assertEquals(5_000, toList(large).size());

        CollisionQuerySet small = new CollisionQuerySet(0, 1, 0, 1);
        small.add("one");
        assertEquals(List.of("one"), toList(small));

        CollisionQuerySet nextSmall = new CollisionQuerySet(50, 51, 70, 71);
        nextSmall.add("two");
        assertEquals(List.of("two"), toList(nextSmall));

        Map<String, Object> telemetry = CollisionQuerySet.telemetry();
        assertEquals(3L, telemetry.get("completedQueries"));
        assertEquals(5_002L, telemetry.get("totalValues"));
        assertEquals(5_000L, telemetry.get("maximumValues"));
        assertEquals(0L, telemetry.get("oversizedInitialCapacities"));
        assertEquals(48L, telemetry.get("initialCapacitySlots"));
        assertEquals(1L, telemetry.get("hintHits"));
        assertEquals(2L, telemetry.get("hintMisses"));
    }

    @Test
    void sameShapeCanShrinkAfterASmallerCompletedQuery() {
        CollisionQuerySet large = new CollisionQuerySet(0, 10, 0, 10);
        for (int index = 0; index < 100; index++) large.add(index);
        assertEquals(100, toList(large).size());

        CollisionQuerySet small = new CollisionQuerySet(20, 30, 40, 50);
        small.add("one");
        assertEquals(List.of("one"), toList(small));

        CollisionQuerySet nextSmall = new CollisionQuerySet(-10, 0, -10, 0);
        nextSmall.add("two");
        assertEquals(List.of("two"), toList(nextSmall));

        Map<String, Object> telemetry = CollisionQuerySet.telemetry();
        assertEquals(2L, telemetry.get("hintHits"));
        assertEquals(1L, telemetry.get("hintMisses"));
        assertEquals(1L, telemetry.get("oversizedInitialCapacities"));
        assertEquals(288L, telemetry.get("initialCapacitySlots"));
        assertEquals(0L, telemetry.get("avoidedGrowths"));
    }

    @Test
    void directMappedShapeCollisionLosesOnlyTheHint() {
        CollisionQuerySet first = new CollisionQuerySet(0, 39, 0, 1);
        for (int index = 0; index < 100; index++) first.add(index);
        assertEquals(100, toList(first).size());

        // Widths 40 and 41 with height 2 deliberately map to the same one of 1,024 hint slots.
        CollisionQuerySet collider = new CollisionQuerySet(0, 40, 0, 1);
        collider.add("collider");
        assertEquals(List.of("collider"), toList(collider));

        CollisionQuerySet firstAgain = new CollisionQuerySet(10, 49, 20, 21);
        firstAgain.add("first-again");
        assertEquals(List.of("first-again"), toList(firstAgain));

        Map<String, Object> telemetry = CollisionQuerySet.telemetry();
        assertEquals(0L, telemetry.get("hintHits"));
        assertEquals(3L, telemetry.get("hintMisses"));
        assertEquals(1L, telemetry.get("hintSlotFills"));
        assertEquals(2L, telemetry.get("hintReplacements"));
        assertEquals(48L, telemetry.get("initialCapacitySlots"));
    }

    @Test
    void matchesLinkedHashSetEncounterOrderAcrossNullDuplicatesCollisionsAndGrowth() {
        List<Object> values = new ArrayList<>();
        values.add(null);
        values.add(null);
        for (int index = 0; index < 96; index++) {
            values.add(new CollidingValue(index));
            if ((index & 3) == 0) values.add(new CollidingValue(index));
        }

        LinkedHashSet<Object> expected = new LinkedHashSet<>();
        CollisionQuerySet actual = new CollisionQuerySet();
        for (Object value : values) {
            assertEquals(expected.add(value), actual.add(value));
        }

        assertEquals(expected.size(), actual.size());
        assertEquals(new ArrayList<>(expected), toList(actual));
        assertTrue(actual.contains(null));
        assertTrue(actual.contains(new CollidingValue(75)));
        assertFalse(actual.contains(new CollidingValue(500)));
    }

    @Test
    void retainsInsertionHashesAcrossMutableKeyGrowthLikeLinkedHashSet() {
        MutableHash value = new MutableHash(7);
        LinkedHashSet<Object> expected = new LinkedHashSet<>();
        CollisionQuerySet actual = new CollisionQuerySet();
        assertTrue(expected.add(value));
        assertTrue(actual.add(value));

        value.hash = 19;
        assertEquals(expected.contains(value), actual.contains(value));
        assertEquals(expected.add(value), actual.add(value));
        for (int index = 0; index < 100; index++) {
            assertEquals(expected.add(index), actual.add(index));
        }
        assertEquals(new ArrayList<>(expected), toList(actual));
    }

    @Test
    void storesCandidatesOnceAndUsesPrimitiveHashSlots() throws IllegalAccessException {
        long objectArrays = Arrays.stream(CollisionQuerySet.class.getDeclaredFields())
                .filter(field -> field.getType() == Object[].class)
                .count();
        long integerArrays = Arrays.stream(CollisionQuerySet.class.getDeclaredFields())
                .filter(field -> field.getType() == int[].class)
                .count();
        assertEquals(1L, objectArrays);
        assertEquals(2L, integerArrays);

        Object candidate = new Object();
        CollisionQuerySet values = new CollisionQuerySet();
        values.add(candidate);
        long references = 0L;
        for (var field : CollisionQuerySet.class.getDeclaredFields()) {
            if (field.getType() != Object[].class) continue;
            field.setAccessible(true);
            for (Object value : (Object[]) field.get(values)) {
                if (value == candidate) references++;
            }
        }
        assertEquals(1L, references);
    }

    @Test
    void repeatedIteratorsAreIndependentAndMutationIsFailFast() {
        CollisionQuerySet values = new CollisionQuerySet();
        values.add("first");
        values.add("second");

        assertEquals(List.of("first", "second"), toList(values));
        assertEquals(List.of("first", "second"), toList(values));

        var iterator = values.iterator();
        assertEquals("first", iterator.next());
        values.add("third");
        assertThrows(ConcurrentModificationException.class, iterator::next);
        assertThrows(UnsupportedOperationException.class, () -> values.remove("first"));
    }

    @Test
    void exactArrayListBulkCopyMatchesSetAddAllAndFallbackRemainsAvailable() {
        CollisionQuerySet optimized = new CollisionQuerySet();
        assertTrue(CollisionQuerySet.addAllFrom(
                optimized, new ArrayList<>(List.of("first", "second", "first"))));
        assertFalse(CollisionQuerySet.addAllFrom(
                optimized, new ArrayList<>(List.of("second"))));
        assertEquals(List.of("first", "second"), toList(optimized));

        Set<Object> fallback = new LinkedHashSet<>();
        assertTrue(CollisionQuerySet.addAllFrom(fallback, List.of("third", "fourth")));
        assertEquals(List.of("third", "fourth"), new ArrayList<>(fallback));
    }

    private static List<Object> toList(Iterable<Object> values) {
        List<Object> result = new ArrayList<>();
        values.forEach(result::add);
        return result;
    }

    private record CollidingValue(int value) {
        @Override
        public int hashCode() {
            return 7;
        }
    }

    private static final class MutableHash {
        private int hash;

        private MutableHash(int hash) {
            this.hash = hash;
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
