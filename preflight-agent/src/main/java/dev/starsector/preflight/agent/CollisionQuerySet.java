package dev.starsector.preflight.agent;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
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
    private static final int MAXIMUM_CAPACITY = 1 << 30;
    private static final Object NULL_KEY = new Object();

    private Object[] table;
    private int[] hashes;
    private Object[] order;
    private int size;
    private int threshold;
    private int modCount;

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
        if (table == null) initialize();
        if (size >= table.length - 1) grow();
        int slot = findSlot(key, hash, table, hashes);
        if (table[slot] != null) return false;
        if (size + 1 > threshold) {
            grow();
            slot = findSlot(key, hash, table, hashes);
        }
        ensureOrderCapacity();
        table[slot] = key;
        hashes[slot] = hash;
        order[size++] = key;
        modCount++;
        return true;
    }

    @Override
    public boolean contains(Object value) {
        if (table == null) return false;
        Object key = maskNull(value);
        int hash = spread(value == null ? 0 : value.hashCode());
        return table[findSlot(key, hash, table, hashes)] != null;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public Iterator<Object> iterator() {
        return new OrderedIterator(modCount);
    }

    @Override
    public boolean remove(Object value) {
        throw new UnsupportedOperationException();
    }

    private void initialize() {
        table = new Object[INITIAL_CAPACITY];
        hashes = new int[INITIAL_CAPACITY];
        order = new Object[INITIAL_CAPACITY];
        threshold = threshold(INITIAL_CAPACITY);
    }

    private void grow() {
        int oldCapacity = table.length;
        if (oldCapacity >= MAXIMUM_CAPACITY) {
            throw new IllegalStateException("Collision query set is too large");
        }
        int newCapacity = oldCapacity << 1;
        Object[] oldTable = table;
        int[] oldHashes = hashes;
        table = new Object[newCapacity];
        hashes = new int[newCapacity];
        threshold = threshold(newCapacity);
        for (int index = 0; index < oldTable.length; index++) {
            Object key = oldTable[index];
            if (key == null) continue;
            int hash = oldHashes[index];
            int slot = emptySlot(hash, table);
            table[slot] = key;
            hashes[slot] = hash;
        }
    }

    private void ensureOrderCapacity() {
        if (size < order.length) return;
        Object[] expanded = new Object[order.length << 1];
        System.arraycopy(order, 0, expanded, 0, size);
        order = expanded;
    }

    private static int findSlot(Object key, int hash, Object[] values, int[] valueHashes) {
        int mask = values.length - 1;
        int slot = hash & mask;
        while (true) {
            Object existing = values[slot];
            if (existing == null || (valueHashes[slot] == hash && equalKey(key, existing))) {
                return slot;
            }
            slot = (slot + 1) & mask;
        }
    }

    private static int emptySlot(int hash, Object[] values) {
        int mask = values.length - 1;
        int slot = hash & mask;
        while (values[slot] != null) slot = (slot + 1) & mask;
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
