package com.bigbangcraft.expeditions.teleport;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Goal 04: return-position validation policy.
 */
class ReturnLocationPolicyTest {

    private static ReturnPosition pos(double y) {
        return new ReturnPosition("minecraft:overworld", 10.5, y, -3.25, 0f, 0f);
    }

    @Test
    void acceptsValidStoredPosition() {
        var r = ReturnLocationPolicy.evaluate(Optional.of(pos(64)), true, -64, 319);
        assertTrue(r.accepted());
        assertEquals("", r.fallbackReason());
    }

    @Test
    void missingStorageFallsBack() {
        var r = ReturnLocationPolicy.evaluate(Optional.empty(), true, -64, 319);
        assertFalse(r.accepted());
        assertEquals("no_stored_position", r.fallbackReason());
    }

    @Test
    void staleDimensionFallsBackWithReason() {
        var r = ReturnLocationPolicy.evaluate(Optional.of(pos(64)), false, -64, 319);
        assertFalse(r.accepted());
        assertEquals("stale_dimension", r.fallbackReason());
    }

    @Test
    void belowWorldIsRejected() {
        var r = ReturnLocationPolicy.evaluate(Optional.of(pos(-70)), true, -64, 319);
        assertFalse(r.accepted());
        assertEquals("out_of_bounds", r.fallbackReason());
    }

    @Test
    void aboveBuildLimitIsRejected() {
        var r = ReturnLocationPolicy.evaluate(Optional.of(pos(400)), true, -64, 319);
        assertFalse(r.accepted());
        assertEquals("out_of_bounds", r.fallbackReason());
    }

    @Test
    void toleranceAllowsSurfaceRecordedOneAboveMax() {
        // recorded while standing on a block at max height
        assertTrue(ReturnLocationPolicy.evaluate(Optional.of(pos(320)), true, -64, 319).accepted());
    }
}
