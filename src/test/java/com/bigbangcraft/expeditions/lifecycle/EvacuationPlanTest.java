package com.bigbangcraft.expeditions.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EvacuationPlanTest {

    @Test
    void teleportsEveryoneCurrentlyInside() {
        var plan = EvacuationPlan.plan(List.of("alice", "bob"), List.of());
        assertEquals(2, plan.size());
        assertTrue(plan.stream().allMatch(a -> a.type() == EvacuationPlan.ActionType.TELEPORT_OUT));
    }

    @Test
    void staleMarkersBecomeJoinEvictions() {
        var plan = EvacuationPlan.plan(List.of(), List.of("carol"));
        assertEquals(1, plan.size());
        assertEquals(EvacuationPlan.ActionType.EVICT_ON_JOIN, plan.get(0).type());
        assertEquals("carol", plan.get(0).playerName());
    }

    @Test
    void noDuplicateActionsForPlayerBothInsideAndStale() {
        var plan = EvacuationPlan.plan(List.of("dave"), List.of("dave"));
        assertEquals(1, plan.size());
        assertEquals(EvacuationPlan.ActionType.TELEPORT_OUT, plan.get(0).type());
    }

    @Test
    void emptyInputsProduceEmptyPlan() {
        assertTrue(EvacuationPlan.plan(List.of(), List.of()).isEmpty());
        assertTrue(EvacuationPlan.plan(null, null).isEmpty());
    }

    @Test
    void everyActionTypeClearsTheDimension() {
        // the plan shape itself guarantees nobody is left inside: both action
        // types remove the occupant (now or at next join)
        var plan = EvacuationPlan.plan(List.of("a"), List.of("b"));
        assertTrue(EvacuationPlan.clearsDimension(plan));
    }
}
