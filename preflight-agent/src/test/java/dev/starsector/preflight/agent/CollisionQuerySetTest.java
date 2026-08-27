package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CollisionQuerySetTest {
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
}
