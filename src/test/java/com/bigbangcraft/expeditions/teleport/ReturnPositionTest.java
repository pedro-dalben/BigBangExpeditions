package com.bigbangcraft.expeditions.teleport;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ReturnPositionTest {

    @Test
    void serializeDeserializeRoundTrip() {
        ReturnPosition rp = new ReturnPosition("minecraft:overworld", 12.5, -64.0, 300.75, 90f, -45f);
        Optional<ReturnPosition> back = ReturnPosition.deserialize(rp.serialize());
        assertTrue(back.isPresent());
        assertEquals("minecraft:overworld", back.get().dimension);
        assertEquals(12.5, back.get().x, 1e-9);
        assertEquals(-64.0, back.get().y, 1e-9);
        assertEquals(300.75, back.get().z, 1e-9);
        assertEquals(90f, back.get().yaw, 1e-6);
        assertEquals(-45f, back.get().pitch, 1e-6);
    }

    @Test
    void rejectsGarbage() {
        assertFalse(ReturnPosition.deserialize(null).isPresent());
        assertFalse(ReturnPosition.deserialize("").isPresent());
        assertFalse(ReturnPosition.deserialize("nonsense").isPresent());
        assertFalse(ReturnPosition.deserialize("a|b|c|d|e|f").isPresent());
        assertFalse(ReturnPosition.deserialize("dim|1|2|3").isPresent());
    }

    @Test
    void rejectsTraversalDimension() {
        assertFalse(ReturnPosition.deserialize("../../etc|1|2|3|0|0").isPresent(),
            "path traversal in dimension must be rejected");
        assertFalse(ReturnPosition.deserialize("minecraft:../../evil|1|2|3|0|0").isPresent());
    }

    @Test
    void rejectsNonFiniteNumbers() {
        assertFalse(ReturnPosition.deserialize("minecraft:overworld|Infinity|2|3|0|0").isPresent());
        assertFalse(ReturnPosition.deserialize("minecraft:overworld|NaN|2|3|0|0").isPresent());
    }

    @Test
    void expeditionDimAccepted() {
        ReturnPosition rp = new ReturnPosition("bigbangexpeditions:expedition", 2048.5, 71, 2048.5, 0f, 0f);
        assertTrue(ReturnPosition.deserialize(rp.serialize()).isPresent());
    }
}
