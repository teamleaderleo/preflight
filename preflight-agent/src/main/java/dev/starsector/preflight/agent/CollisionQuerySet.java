package dev.starsector.preflight.agent;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Insertion-ordered set for one exact Starsector collision-grid query.
 *
 * <p>The reviewed game iterator only adds grid-cell contents, creates one or more iterators, and
 * then discards the set. Open addressing preserves {@link java.util.LinkedHashSet} encounter and
 * equality semantics for that boundary without allocating a linked node for every candidate.
 */
public final class CollisionQuerySet extends AbstractSet<Object> {
    private static final int INITIAL_CAPACITY = 16;
    private static final int MAXIMUM_HINT_CAPACITY = 4096;
    private static final int MAXIMUM_CAPACITY = 1 << 30;
    private static final int HINT_SLOT_COUNT = 1024;
    private static final Object NULL_KEY = new Object();
    private static final ThreadLocal<CapacityHints> CAPACITY_HINTS =
            ThreadLocal.withInitial(CapacityHints::new);

    private static long completedQueries;
    private static long totalValues;
    private static long maximumValues;
    private static long totalGrowths;
    private static long avoidedGrowths;
    private static long exactInitialCapacities;
    private static long undersizedInitialCapacities;
    private static long oversizedInitialCapacities;
    private static long initialCapacitySlots;
    private static long finalCapacitySlots;
    private static long hintHits;
    private static long hintMisses;
    private static long hintSlotFills;
    private static long hintReplacements;

    /** One-based indexes into {@link #order}; zero denotes an empty hash slot. */
    private int[] slots;
    private Object[] order;
    /** Hash captured at insertion, matching HashMap/LinkedHashSet mutable-key behavior. */
    private int[] orderHashes;
    private int size;
    private int threshold;
    private int modCount;
    private final int initialCapacity;
    private int growthCount;
    private boolean completionRecorded;
    private final int shapeWidth;
    private final int shapeHeight;

    /** Constructor retained for direct tests and defensive fallback use. */
    public CollisionQuerySet() {
        this(0, 0, 0, 0);
    }

    /** Receives the collision-grid bounds without retaining any game object or save state. */
    public CollisionQuerySet(int minimumX, int maximumX, int minimumY, int maximumY) {
        shapeWidth = span(minimumX, maximumX);
        shapeHeight = span(minimumY, maximumY);
        initialCapacity = CAPACITY_HINTS.get().capacity(shapeWidth, shapeHeight);
    }

    static void beginSession() {
        completedQueries = 0;
        totalValues = 0;
        maximumValues = 0;
        totalGrowths = 0;
        avoidedGrowths = 0;
        exactInitialCapacities = 0;
        undersizedInitialCapacities = 0;
        oversizedInitialCapacities = 0;
        initialCapacitySlots = 0;
        finalCapacitySlots = 0;
        hintHits = 0;
        hintMisses = 0;
        hintSlotFills = 0;
        hintReplacements = 0;
        CAPACITY_HINTS.remove();
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("completedQueries", completedQueries);
        result.put("totalValues", totalValues);
        result.put("maximumValues", maximumValues);
        result.put("totalGrowths", totalGrowths);
        result.put("avoidedGrowths", avoidedGrowths);
        result.put("exactInitialCapacities", exactInitialCapacities);
        result.put("undersizedInitialCapacities", undersizedInitialCapacities);
        result.put("oversizedInitialCapacities", oversizedInitialCapacities);
        result.put("initialCapacitySlots", initialCapacitySlots);
        result.put("finalCapacitySlots", finalCapacitySlots);
        result.put("hintHits", hintHits);
        result.put("hintMisses", hintMisses);
        result.put("hintSlotFills", hintSlotFills);
        result.put("hintReplacements", hintReplacements);
        return result;
    }

    /** Copies exact collision-cell ArrayLists without allocating their short-lived iterators. */
    public static boolean addAllFrom(Set<Object> target, Collection<?> values) {
        if (target instanceof CollisionQuerySet && values instanceof ArrayList<?> list) {
            boolean modified = false;
            int length = list.size();
            for (int index = 0; index < length; index++) {
                modified |= target.add(list.get(index));
            }
            return modified;
        }
        return target.addAll(values);
    }

    @Override
    public boolean add(Object value) {
        Object key = maskNull(value);
        int hash = spread(value == null ? 0 : value.hashCode());
        if (slots == null) initialize();
        if (size >= slots.length - 1) grow();
        int slot = findSlot(key, hash, slots, order, orderHashes);
        if (slots[slot] != 0) return false;
        if (size + 1 > threshold) {
            grow();
            slot = findSlot(key, hash, slots, order, orderHashes);
        }
        ensureOrderCapacity();
        order[size] = key;
        orderHashes[size] = hash;
        slots[slot] = size + 1;
        size++;
        modCount++;
        return true;
    }

    @Override
    public boolean contains(Object value) {
        if (slots == null) return false;
        Object key = maskNull(value);
        int hash = spread(value == null ? 0 : value.hashCode());
        return slots[findSlot(key, hash, slots, order, orderHashes)] != 0;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public Iterator<Object> iterator() {
        recordCompletion();
        return new OrderedIterator(modCount);
    }

    @Override
    public boolean remove(Object value) {
        throw new UnsupportedOperationException();
    }

    private void initialize() {
        slots = new int[initialCapacity];
        order = new Object[initialCapacity];
        orderHashes = new int[initialCapacity];
        threshold = threshold(initialCapacity);
    }

    private void grow() {
        int oldCapacity = slots.length;
        if (oldCapacity >= MAXIMUM_CAPACITY) {
            throw new IllegalStateException("Collision query set is too large");
        }
        int newCapacity = oldCapacity << 1;
        slots = new int[newCapacity];
        threshold = threshold(newCapacity);
        growthCount++;
        for (int index = 0; index < size; index++) {
            int slot = emptySlot(orderHashes[index], slots);
            slots[slot] = index + 1;
        }
    }

    private void ensureOrderCapacity() {
        if (size < order.length) return;
        Object[] expanded = new Object[order.length << 1];
        int[] expandedHashes = new int[orderHashes.length << 1];
        System.arraycopy(order, 0, expanded, 0, size);
        System.arraycopy(orderHashes, 0, expandedHashes, 0, size);
        order = expanded;
        orderHashes = expandedHashes;
    }

    private static int findSlot(
            Object key, int hash, int[] indexes, Object[] values, int[] valueHashes) {
        int mask = indexes.length - 1;
        int slot = hash & mask;
        while (true) {
            int encodedIndex = indexes[slot];
            if (encodedIndex == 0) return slot;
            int index = encodedIndex - 1;
            if (valueHashes[index] == hash && equalKey(key, values[index])) {
                return slot;
            }
            slot = (slot + 1) & mask;
        }
    }

    private static int emptySlot(int hash, int[] indexes) {
        int mask = indexes.length - 1;
        int slot = hash & mask;
        while (indexes[slot] != 0) slot = (slot + 1) & mask;
        return slot;
    }

    private static boolean equalKey(Object key, Object existing) {
        return key == existing || (key != NULL_KEY && key.equals(existing));
    }

    private static Object maskNull(Object value) {
        return value == null ? NULL_KEY : value;
    }

    private static Object unmaskNull(Object value) {
        return value == NULL_KEY ? null : value;
    }

    private static int spread(int hash) {
        return hash ^ (hash >>> 16);
    }

    private static int threshold(int capacity) {
        return capacity - (capacity >>> 2);
    }

    private void recordCompletion() {
        if (completionRecorded) return;
        completionRecorded = true;
        int requiredCapacity = capacityForSize(size);
        CAPACITY_HINTS.get().record(shapeWidth, shapeHeight,
                Math.min(requiredCapacity, MAXIMUM_HINT_CAPACITY));
        completedQueries++;
        totalValues += size;
        maximumValues = Math.max(maximumValues, size);
        totalGrowths += growthCount;
        avoidedGrowths += Math.max(0, growthsForCapacity(requiredCapacity) - growthCount);
        initialCapacitySlots += initialCapacity;
        finalCapacitySlots += slots == null ? 0 : slots.length;
        if (initialCapacity == requiredCapacity) {
            exactInitialCapacities++;
        } else if (initialCapacity < requiredCapacity) {
            undersizedInitialCapacities++;
        } else {
            oversizedInitialCapacities++;
        }
    }

    private static int capacityForSize(int valueCount) {
        int capacity = INITIAL_CAPACITY;
        while (valueCount > threshold(capacity) && capacity < MAXIMUM_CAPACITY) {
            capacity <<= 1;
        }
        return capacity;
    }

    private static int growthsForCapacity(int capacity) {
        return Integer.numberOfTrailingZeros(capacity)
                - Integer.numberOfTrailingZeros(INITIAL_CAPACITY);
    }

    private static int span(int first, int second) {
        long difference = (long) second - first;
        long absolute = difference < 0 ? -difference : difference;
        return (int) Math.min(Integer.MAX_VALUE, absolute + 1L);
    }

    private static int hintSlot(int width, int height) {
        int mixed = width * 0x9E3779B9 ^ Integer.rotateLeft(height * 0x85EBCA6B, 16);
        return spread(mixed) & (HINT_SLOT_COUNT - 1);
    }

    private static final class CapacityHints {
        private final int[] widths = new int[HINT_SLOT_COUNT];
        private final int[] heights = new int[HINT_SLOT_COUNT];
        private final int[] capacities = new int[HINT_SLOT_COUNT];

        private int capacity(int width, int height) {
            int slot = hintSlot(width, height);
            if (capacities[slot] != 0 && widths[slot] == width && heights[slot] == height) {
                hintHits++;
                return capacities[slot];
            }
            hintMisses++;
            return INITIAL_CAPACITY;
        }

        private void record(int width, int height, int capacity) {
            int slot = hintSlot(width, height);
            if (capacities[slot] == 0) {
                hintSlotFills++;
            } else if (widths[slot] != width || heights[slot] != height) {
                hintReplacements++;
            }
            widths[slot] = width;
            heights[slot] = height;
            capacities[slot] = capacity;
        }
    }

    private final class OrderedIterator implements Iterator<Object> {
        private final int expectedModCount;
        private int index;

        private OrderedIterator(int expectedModCount) {
            this.expectedModCount = expectedModCount;
        }

        @Override
        public boolean hasNext() {
            return index < size;
        }

        @Override
        public Object next() {
            if (modCount != expectedModCount) throw new ConcurrentModificationException();
            if (index >= size) throw new NoSuchElementException();
            return unmaskNull(order[index++]);
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }
}
