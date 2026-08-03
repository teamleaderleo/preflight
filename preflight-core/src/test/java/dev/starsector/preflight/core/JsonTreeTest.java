package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTreeTest {
    private static final Object DECODED_NULL = new Object();
    private static final JsonTreeSink SINK = new CollectionSink();

    @Test
    void roundTripsEverySupportedValueWithoutLosingNumericTypes() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("null", JsonTree.NULL);
        source.put("false", false);
        source.put("true", true);
        source.put("byte", (byte) -7);
        source.put("short", (short) 300);
        source.put("int", Integer.MIN_VALUE);
        source.put("long", Long.MAX_VALUE);
        source.put("float", 1.25f);
        source.put("double", -0.0d);
        source.put("string", "starsector \u2605");
        source.put("array", List.of("x", 4L, Map.of("nested", true)));

        @SuppressWarnings("unchecked")
        Map<String, Object> decoded = (Map<String, Object>) JsonTree.decode(
                JsonTree.encode(source), SINK);

        assertSame(DECODED_NULL, decoded.get("null"));
        assertEquals(false, decoded.get("false"));
        assertEquals(true, decoded.get("true"));
        assertEquals(-7, decoded.get("byte"));
        assertEquals(300, decoded.get("short"));
        assertEquals(Integer.MIN_VALUE, decoded.get("int"));
        assertEquals(Long.MAX_VALUE, decoded.get("long"));
        assertEquals(1.25d, decoded.get("float"));
        assertEquals(Double.doubleToRawLongBits(-0.0d),
                Double.doubleToRawLongBits((Double) decoded.get("double")));
        assertEquals("starsector \u2605", decoded.get("string"));
        assertEquals(List.of("x", 4L, Map.of("nested", true)), decoded.get("array"));
    }

    @Test
    void reusesDecodedStringInstancesFromThePerEntryTable() {
        @SuppressWarnings("unchecked")
        List<Object> decoded = (List<Object>) JsonTree.decode(
                JsonTree.encode(List.of("repeated", "repeated", Map.of("repeated", "repeated"))),
                SINK);
        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) decoded.get(2);
        String objectKey = object.keySet().iterator().next();

        assertSame(decoded.get(0), decoded.get(1));
        assertSame(decoded.get(0), objectKey);
        assertSame(decoded.get(0), object.get(objectKey));
    }

    @Test
    void preservesAPresentNullInsteadOfTurningItIntoAMissingMember() {
        @SuppressWarnings("unchecked")
        Map<String, Object> decoded = (Map<String, Object>) JsonTree.decode(
                JsonTree.encode(Map.of("present", JsonTree.NULL)), SINK);

        assertTrue(decoded.containsKey("present"));
        assertSame(DECODED_NULL, decoded.get("present"));
        assertTrue(!decoded.containsKey("missing"));
    }

    @Test
    void refusesTypesThatWouldHaveToBeApproximated() {
        assertThrows(IllegalArgumentException.class, () -> JsonTree.encode(new Object()));
        assertThrows(IllegalArgumentException.class,
                () -> JsonTree.encode(Map.of("decimal", java.math.BigDecimal.ONE)));
        assertThrows(IllegalArgumentException.class, () -> JsonTree.encode(Map.of(1, "not a key")));
    }

    @Test
    void rejectsMalformedAndTruncatedTrees() {
        assertThrows(IllegalArgumentException.class, () -> JsonTree.decode(new byte[0], SINK));
        assertThrows(IllegalArgumentException.class,
                () -> JsonTree.decode(new byte[] {(byte) 0x7F}, SINK));
        assertThrows(IllegalArgumentException.class,
                () -> JsonTree.decode(new byte[] {0, 0}, SINK));
        assertThrows(IllegalArgumentException.class,
                () -> JsonTree.decode(new byte[] {7, 0}, SINK));
        assertThrows(IllegalArgumentException.class,
                () -> JsonTree.decode(new byte[] {6, 2, 'x'}, SINK));
        assertThrows(IllegalArgumentException.class,
                () -> JsonTree.decode(new byte[] {8, (byte) 0x80}, SINK));
        assertThrows(IllegalArgumentException.class,
                () -> JsonTree.decode(new byte[] {8, (byte) 0x80, (byte) 0x80,
                        (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80,
                        (byte) 0x80, (byte) 0x80, (byte) 0x80, 2}, SINK));
    }

    private static final class CollectionSink implements JsonTreeSink {
        @Override
        public Object nullValue() {
            return DECODED_NULL;
        }

        @Override
        public Object newObject() {
            return new LinkedHashMap<String, Object>();
        }

        @Override
        @SuppressWarnings("unchecked")
        public void put(Object object, String key, Object value) {
            ((Map<String, Object>) object).put(key, value);
        }

        @Override
        public Object newArray() {
            return new ArrayList<>();
        }

        @Override
        @SuppressWarnings("unchecked")
        public void add(Object array, Object value) {
            ((List<Object>) array).add(value);
        }
    }
}
