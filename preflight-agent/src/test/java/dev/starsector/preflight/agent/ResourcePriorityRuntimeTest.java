package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ResourcePriorityRuntimeTest {
    @BeforeEach
    @AfterEach
    void reset() {
        ResourcePriorityRuntime.beginSession();
    }

    @Test
    void indexedRemovalMatchesArrayListAcrossDuplicatesAndNulls() {
        Random random = new Random(0x51cedL);
        List<String> domain = new ArrayList<>(List.of("a", "b", "c", "d", "e"));
        domain.add(null);
        for (int sample = 0; sample < 1_000; sample++) {
            List<String> source = randomList(random, domain, 80);
            List<String> removed = randomList(random, domain, 40);
            List<String> expected = new ArrayList<>(source);
            List<String> actual = new ArrayList<>(source);
            assertEquals(expected.removeAll(removed),
                    ResourcePriorityRuntime.removeAll(actual, removed));
            assertEquals(expected, actual);
        }
        assertEquals(1_000L, ResourcePriorityRuntime.telemetry().get("calls"));
    }

    private static List<String> randomList(Random random, List<String> domain, int limit) {
        List<String> result = new ArrayList<>();
        int size = random.nextInt(limit + 1);
        for (int index = 0; index < size; index++) {
            result.add(domain.get(random.nextInt(domain.size())));
        }
        return result;
    }
}
