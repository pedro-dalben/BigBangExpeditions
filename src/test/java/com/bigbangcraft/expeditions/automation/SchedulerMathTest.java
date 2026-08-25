package com.bigbangcraft.expeditions.automation;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SchedulerMathTest {
    private static final ZoneId Z = ZoneId.of("UTC");
    // 2023-11-15T00:00:00Z is a Wednesday
    private static final long MIDNIGHT = java.time.LocalDateTime.of(2023, 11, 15, 0, 0)
            .atZone(Z).toInstant().toEpochMilli();

    @Test
    void parseHHMM() {
        assertEquals(240, SchedulerMath.parseHHMM("04:00"));
        assertEquals(0, SchedulerMath.parseHHMM("00:00"));
        assertEquals(23 * 60 + 59, SchedulerMath.parseHHMM("23:59"));
    }

    @Test
    void inWindowNormalAndEdge() {
        long at0400 = MIDNIGHT + 240 * SchedulerMath.MINUTE_MS;
        long at0459 = MIDNIGHT + 299 * SchedulerMath.MINUTE_MS;
        long at0500 = MIDNIGHT + 300 * SchedulerMath.MINUTE_MS;
        assertTrue(SchedulerMath.inWindow(at0400, 240, 300, Z));
        assertTrue(SchedulerMath.inWindow(at0459, 240, 300, Z));
        assertFalse(SchedulerMath.inWindow(at0500, 240, 300, Z)); // end exclusive
    }

    @Test
    void equalWindowMeansAnyTime() {
        assertTrue(SchedulerMath.inWindow(MIDNIGHT, 300, 300, Z));
        assertEquals(MIDNIGHT, SchedulerMath.nextWindowStart(MIDNIGHT, 300, 300, Z));
    }

    @Test
    void overnightWrapWindow() {
        // 22:00-02:00 window
        long at2300 = MIDNIGHT + 22 * 60 * SchedulerMath.MINUTE_MS;
        long at0100Next = MIDNIGHT + (24L * 60 + 60) * SchedulerMath.MINUTE_MS;
        assertTrue(SchedulerMath.inWindow(at2300, 1320, 120, Z));
        assertTrue(SchedulerMath.inWindow(at0100Next, 1320, 120, Z));
        assertFalse(SchedulerMath.inWindow(MIDNIGHT + 12 * 60 * SchedulerMath.MINUTE_MS, 1320, 120, Z));
    }

    @Test
    void nextWindowStartComputesFutureOpening() {
        int s = 240, e = 300; // 04:00-05:00
        long noon = MIDNIGHT + 720 * SchedulerMath.MINUTE_MS;
        long expectedTomorrow0400 = MIDNIGHT + (24L * 60 + 240) * SchedulerMath.MINUTE_MS;
        assertEquals(expectedTomorrow0400, SchedulerMath.nextWindowStart(noon, s, e, Z));

        long inside = MIDNIGHT + 250 * SchedulerMath.MINUTE_MS; // already in window
        assertTrue(SchedulerMath.nextWindowStart(inside, s, e, Z) <= inside);
    }

    @Test
    void missedScheduleCatchesUpDeterministically() {
        long interval = 60;
        assertTrue(SchedulerMath.dueForEvaluation(0, interval, 1000)); // never evaluated
        long last = 1000_000;
        assertFalse(SchedulerMath.dueForEvaluation(last, interval, last + 59 * SchedulerMath.MINUTE_MS));
        assertTrue(SchedulerMath.dueForEvaluation(last, interval, last + 60 * SchedulerMath.MINUTE_MS));
        assertTrue(SchedulerMath.dueForEvaluation(last, interval,
                last + 40 * 24 * 60 * SchedulerMath.MINUTE_MS), "offline days still resolve to run-now");
    }

    @Test
    void capTrimsOldest() {
        List<Integer> l = new ArrayList<>(List.of(1, 2, 3, 4, 5));
        SchedulerMath.cap(l, 2);
        assertEquals(List.of(4, 5), l);
    }
}
