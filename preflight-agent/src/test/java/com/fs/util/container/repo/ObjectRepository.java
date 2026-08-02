package com.fs.util.container.repo;

import java.util.HashSet;
import java.util.Set;

/**
 * Test fixture standing in for the game's object repository.
 *
 * <p>Only the membership half matters here. The shipped class keeps a {@code forFastContains}
 * HashSet alongside its classified lists, adds to both in {@code add} and removes from both in
 * {@code remove}, and exposes {@code contains(Object)} as an O(1) answer -- which is the fast path
 * {@link dev.starsector.preflight.agent.EntityLookupPlan} calls to validate anything the index
 * offers. This reproduces that contract; it is not the game's code, which is not redistributable and
 * is not in this repository.
 */
public class ObjectRepository {
    private final Set<Object> forFastContains = new HashSet<>();

    public boolean contains(Object value) {
        return forFastContains.contains(value);
    }

    public boolean add(Object value) {
        return forFastContains.add(value);
    }

    public boolean remove(Object value) {
        return forFastContains.remove(value);
    }
}
