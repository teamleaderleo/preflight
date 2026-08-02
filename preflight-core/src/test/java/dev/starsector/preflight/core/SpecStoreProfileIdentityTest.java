package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SpecStoreProfileIdentityTest {
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final String C = "c".repeat(64);

    @Test
    void identityIncludesEveryStrictInputDimension() {
        SpecStoreProfileIdentity baseline = new SpecStoreProfileIdentity(A, B, C, 10, 20, 3, 40);
        assertEquals(baseline.identitySha256(),
                new SpecStoreProfileIdentity(A, B, C, 10, 20, 3, 40).identitySha256());
        assertNotEquals(baseline.identitySha256(),
                new SpecStoreProfileIdentity(B, B, C, 10, 20, 3, 40).identitySha256());
        assertNotEquals(baseline.identitySha256(),
                new SpecStoreProfileIdentity(A, C, C, 10, 20, 3, 40).identitySha256());
        assertNotEquals(baseline.identitySha256(),
                new SpecStoreProfileIdentity(A, B, A, 10, 20, 3, 40).identitySha256());
        assertNotEquals(baseline.identitySha256(),
                new SpecStoreProfileIdentity(A, B, C, 11, 20, 3, 40).identitySha256());
    }

    @Test
    void rejectsMalformedHashesAndCounts() {
        assertThrows(IllegalArgumentException.class,
                () -> new SpecStoreProfileIdentity("no", B, C, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new SpecStoreProfileIdentity(A, B, C, -1, 0, 0, 0));
    }
}
