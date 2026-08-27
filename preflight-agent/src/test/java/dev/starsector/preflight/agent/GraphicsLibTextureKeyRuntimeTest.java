package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GraphicsLibTextureKeyRuntimeTest {
    @BeforeEach
    void reset() {
        GraphicsLibTextureKeyRuntime.beginSession();
    }

    @Test
    void reusesExactGeneratedStringsAcrossStableKeys() {
        String ship = new String("graphics/ship.png$$$ship");
        assertNull(GraphicsLibTextureKeyRuntime.lookup("graphics/ship.png", ObjectType.SHIP, 99));
        assertSame(ship, GraphicsLibTextureKeyRuntime.record(
                "graphics/ship.png", ObjectType.SHIP, 99, ship));
        assertSame(ship, GraphicsLibTextureKeyRuntime.lookup(
                "graphics/ship.png", ObjectType.SHIP, 0));

        String turret3 = new String("graphics/gun.png$$$turret3");
        String turret4 = new String("graphics/gun.png$$$turret4");
        GraphicsLibTextureKeyRuntime.record("graphics/gun.png", ObjectType.TURRET, 3, turret3);
        GraphicsLibTextureKeyRuntime.record("graphics/gun.png", ObjectType.TURRET, 4, turret4);
        assertSame(turret3, GraphicsLibTextureKeyRuntime.lookup(
                "graphics/gun.png", ObjectType.TURRET, 3));
        assertSame(turret4, GraphicsLibTextureKeyRuntime.lookup(
                "graphics/gun.png", ObjectType.TURRET, 4));
        assertNull(GraphicsLibTextureKeyRuntime.lookup(
                "graphics/gun.png", ObjectType.TURRET, 5));

        Map<String, Object> telemetry = GraphicsLibTextureKeyRuntime.telemetry();
        assertEquals(3L, telemetry.get("records"));
        assertEquals(3L, telemetry.get("cachedValues"));
        assertEquals(3L, telemetry.get("hits"));
        assertEquals(2L, telemetry.get("misses"));
        assertEquals(2, telemetry.get("baseKeys"));
    }

    @Test
    void invalidInputsAndOversizedFramesFailOpen() {
        assertNull(GraphicsLibTextureKeyRuntime.lookup("gun", null, 0));
        assertNull(GraphicsLibTextureKeyRuntime.lookup("gun", ObjectType.TURRET, -1));
        assertNull(GraphicsLibTextureKeyRuntime.lookup("gun", ObjectType.HARDPOINT, 1_024));
        assertEquals("generated", GraphicsLibTextureKeyRuntime.record(
                "gun", ObjectType.HARDPOINT, 1_024, "generated"));
        assertEquals(4L, GraphicsLibTextureKeyRuntime.telemetry().get("bypasses"));
        assertEquals(0, GraphicsLibTextureKeyRuntime.telemetry().get("baseKeys"));
    }

    private enum ObjectType {
        SHIP,
        TURRET,
        TURRET_BARREL,
        TURRET_UNDER,
        TURRET_COVER_SMALL,
        TURRET_COVER_MEDIUM,
        TURRET_COVER_LARGE,
        HARDPOINT,
        HARDPOINT_BARREL,
        HARDPOINT_UNDER,
        HARDPOINT_COVER_SMALL,
        HARDPOINT_COVER_MEDIUM,
        HARDPOINT_COVER_LARGE,
        MISSILE,
        ASTEROID
    }
}
