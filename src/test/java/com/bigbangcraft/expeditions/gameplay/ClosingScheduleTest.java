package com.bigbangcraft.expeditions.gameplay;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClosingScheduleTest {

    private static final long MIN = 60_000L;

    @Test
    void nothingDueImmediatelyAfterStart() {
        // remaining (16m) still above every threshold
        long deadline = 100_000 + 16 * MIN;
        assertTrue(ClosingSchedule.dueWarnings(List.of(15, 5, 1), deadline, 100_000, -1).isEmpty());
    }

    @Test
    void fifteenMinuteWarningFiresWhenReached() {
        long deadline = 115 * MIN;
        assertEquals(List.of(15),
                ClosingSchedule.dueWarnings(List.of(15, 5, 1), deadline, deadline - 15 * MIN, -1));
    }

    @Test
    void multipleOverdueThresholdsEmitInOrder() {
        // server was down; now only 30s remain — all thresholds due at once
        long deadline = 10 * MIN;
        List<Integer> due = ClosingSchedule.dueWarnings(List.of(15, 5, 1), deadline, deadline - 500, -1);
        assertEquals(List.of(15, 5, 1), due);
    }

    @Test
    void alreadyWarnedThresholdsNeverRepeat() {
        long deadline = 4 * MIN;
        // 15 and 5 were announced before; only the 1-minute threshold remains
        List<Integer> due = ClosingSchedule.dueWarnings(List.of(15, 5, 1), deadline,
                deadline - 500, 5);
        assertEquals(List.of(1), due);
    }

    @Test
    void largerThresholdNeverRefiresAfterSmallerAnnounced() {
        long deadline = 20 * MIN;
        // smallest announced = 1 (all warnings done) — nothing may re-fire
        assertTrue(ClosingSchedule.dueWarnings(List.of(15, 5, 1), deadline,
                deadline - 500, 1).isEmpty());
    }

    @Test
    void advanceKeepsMinimum() {
        assertEquals(5, ClosingSchedule.advance(-1, List.of(5)));
        assertEquals(1, ClosingSchedule.advance(15, List.of(5, 1)));
        assertEquals(15, ClosingSchedule.advance(15, List.of()));
    }

    @Test
    void extractionDueOnlyAtOrPastDeadline() {
        long deadline = 50_000;
        assertFalse(ClosingSchedule.extractionDue(deadline, 49_999));
        assertTrue(ClosingSchedule.extractionDue(deadline, 50_000));
        assertTrue(ClosingSchedule.extractionDue(deadline, 99_000));
    }

    @Test
    void noDeadlineMeansNothingEverDue() {
        assertTrue(ClosingSchedule.dueWarnings(List.of(15), 0, Long.MAX_VALUE, -1).isEmpty());
        assertFalse(ClosingSchedule.extractionDue(0, Long.MAX_VALUE));
    }


}
