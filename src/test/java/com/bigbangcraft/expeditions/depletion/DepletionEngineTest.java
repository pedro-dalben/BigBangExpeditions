package com.bigbangcraft.expeditions.depletion;

import com.bigbangcraft.expeditions.telemetry.DayActivity;
import com.bigbangcraft.expeditions.telemetry.GenerationTelemetry;
import com.bigbangcraft.expeditions.telemetry.TelemetrySnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DepletionEngineTest {
    private static final long DAY = 86_400_000L;
    private static final long T0 = 1_700_000_000_000L; // day 0

    private static GenerationTelemetry gen(int chunks, int censusPlacements) {
        GenerationTelemetry t = new GenerationTelemetry(5, T0);
        for (long c = 0; c < chunks; c++) t.recordChunkFirstEntry(c, 5, T0);
        for (long s = 0; s < censusPlacements; s++) t.recordStructure("lostcities:city", s, 5, T0);
        return t;
    }

    private static void opens(GenerationTelemetry t, int dayIndex, int count) {
        long ts = T0 + dayIndex * DAY + 3600_000L;
        for (int i = 0; i < count; i++) {
            t.recordContainerOpen(java.util.UUID.nameUUIDFromBytes(("u" + dayIndex + i).getBytes()), 5, ts);
        }
    }

    private static DepletionInput input(GenerationTelemetry t, long now, long totalChunks, long census) {
        return new DepletionInput(TelemetrySnapshot.of(t, TelemetrySnapshot.Availability.AVAILABLE),
                totalChunks, census, now, 0);
    }

    private static DepletionPolicy policy() {
        return DepletionPolicy.validated(new DepletionPolicy(), new java.util.ArrayList<>());
    }

    // ------------------------------------------------------------- basics

    @Test
    void deterministicSameInputsSameOutput() {
        GenerationTelemetry t = gen(500, 40);
        opens(t, 1, 100);
        DepletionPolicy p = policy();
        HysteresisTracker h1 = new HysteresisTracker(0);
        HysteresisTracker h2 = new HysteresisTracker(0);
        var r1 = DepletionEngine.evaluate(input(t, T0 + 5 * DAY, 10_000, 400), p, h1);
        var r2 = DepletionEngine.evaluate(input(t, T0 + 5 * DAY, 10_000, 400), p, h2);
        assertEquals(r1.score, r2.score, 1e-9);
        assertEquals(r1.health, r2.health);
        assertEquals(r1.recommendClosure, r2.recommendClosure);
        assertEquals(h1.consecutiveHits, h2.consecutiveHits);
    }

    @Test
    void freshExpeditionIsHealthyAndNeverRecommends() {
        GenerationTelemetry t = gen(50, 2);
        var r = DepletionEngine.evaluate(input(t, T0 + DAY, 10_000, 400), policy(),
                new HysteresisTracker(0));
        assertEquals(DepletionResult.Health.HEALTHY, r.health);
        assertFalse(r.recommendClosure);
        assertTrue(r.blockers.stream().anyMatch(b -> b.contains("minimum lifetime")));
    }

    @Test
    void unavailableTelemetryBlocksEverything() {
        var snap = TelemetrySnapshot.unavailable(5, TelemetrySnapshot.Availability.CORRUPT);
        var in = new DepletionInput(snap, 10_000, 400, T0 + 30 * DAY, 0);
        var r = DepletionEngine.evaluate(in, policy(), new HysteresisTracker(0));
        assertEquals(DepletionResult.Health.UNKNOWN, r.health);
        assertFalse(r.recommendClosure);
        assertTrue(r.blockers.get(0).contains("telemetry unavailable"));
    }

    @Test
    void explainContainsEveryComponentLine() {
        GenerationTelemetry t = gen(9000, 380);
        opens(t, 2, 200);
        var r = DepletionEngine.evaluate(input(t, T0 + 10 * DAY, 10_000, 400), policy(),
                new HysteresisTracker(0));
        List<String> lines = r.explain();
        String joined = String.join("\n", lines);
        for (String c : List.of("coverage", "structures", "loot", "activity", "age")) {
            assertTrue(joined.contains(c), "missing component " + c);
        }
        assertTrue(joined.contains("RECOMMENDATION"));
    }

    // ------------------------------------------------------------- exploit paths

    @Test
    void roadSprinterCannotTriggerClosureAlone() {
        // coverage ~100% via chunk spam, but zero container evidence, structures census unpinned
        GenerationTelemetry t = gen(9900, 300);
        var r = DepletionEngine.evaluate(input(t, T0 + 10 * DAY, 10_000, 0), policy(),
                new HysteresisTracker(0));
        // spatial BLOCK because structures UNKNOWN and loot UNKNOWN -> known fraction too thin or blocked
        assertFalse(r.recommendClosure, "coverage-only spam must never close a zone");
    }

    @Test
    void afkPresenceDoesNotRefreshActivitySignal() {
        GenerationTelemetry t = gen(100, 5);
        t.observeConcurrentInside(3, 5, T0 + 3 * DAY); // AFK player observed
        t.recordEntry(java.util.UUID.randomUUID(), 5, T0);
        long quietDays = DepletionEngine.lastActivityAgeDays(t.lastActivityEpochMs, T0 + 15 * DAY);
        assertEquals(15, quietDays, "presence sampling must not count as activity");
    }

    @Test
    void deathSpamDoesNotMoveScore() {
        GenerationTelemetry a = gen(1000, 50);
        GenerationTelemetry b = gen(1000, 50);
        for (int i = 0; i < 500; i++) {
            a.recordDeath(java.util.UUID.randomUUID(), 5, T0 + DAY);
            b.recordPlayerMobKill(java.util.UUID.randomUUID(), 5, T0 + DAY);
        }
        var ra = DepletionEngine.evaluate(input(a, T0 + 8 * DAY, 10_000, 400), policy(), new HysteresisTracker(0));
        var rb = DepletionEngine.evaluate(input(b, T0 + 8 * DAY, 10_000, 400), policy(), new HysteresisTracker(0));
        assertEquals(ra.score, rb.score, 1e-9, "deaths/kills are not depletion evidence");
    }

    // ------------------------------------------------------------- full path

    @Test
    void fullDepletionMaturesOnlyAfterSustainedWindow() {
        GenerationTelemetry t = gen(9800, 395);          // near-full coverage + census
        for (int d = 0; d < 7; d++) opens(t, d, 120);     // heavy early looting
        // days 7..13: nothing -> decay ratio 0 -> loot score max
        long now = T0 + 20 * DAY;                          // age beyond min, quiet 13d
        DepletionPolicy p = policy();
        HysteresisTracker h = new HysteresisTracker(p.minSustainedSpanMs);

        var r1 = DepletionEngine.evaluate(input(t, now, 10_000, 400), p, h);
        assertTrue(r1.score >= p.closeScoreThreshold, "expected DEPLETED-candidate, got " + r1.score);
        assertFalse(r1.recommendClosure, "first crossing must not fire");
        assertTrue(r1.sustainedSummary.contains("pending") || r1.sustainedSummary.contains("1/"));

        long step = p.minSustainedSpanMs / 2 + 1;
        DepletionEngine.evaluate(input(t, now + step, 10_000, 400), p, h);
        var r3 = DepletionEngine.evaluate(input(t, now + 2 * step, 10_000, 400), p, h);
        assertTrue(r3.recommendClosure, "matured sustained condition must recommend: " + r3.blockers);
        assertEquals(DepletionResult.Health.DEPLETED, r3.health);
    }

    @Test
    void oscillationAroundThresholdNeverMaturesNorResets() {
        GenerationTelemetry high = gen(9800, 395);
        for (int d = 0; d < 7; d++) opens(high, d, 120);
        GenerationTelemetry low = gen(4000, 160);
        for (int d = 0; d < 7; d++) opens(low, d, 120);

        DepletionPolicy p = policy();
        HysteresisTracker h = new HysteresisTracker(p.minSustainedSpanMs);
        long now = T0 + 20 * DAY;
        long step = p.minSustainedSpanMs / 4;

        var rh1 = DepletionEngine.evaluate(input(high, now, 10_000, 400), p, h);
        var rl1 = DepletionEngine.evaluate(input(low, now + step, 10_000, 400), p, h);
        var rh2 = DepletionEngine.evaluate(input(high, now + 2 * step, 10_000, 400), p, h);
        assertFalse(rh1.recommendClosure && rl1.recommendClosure && rh2.recommendClosure);
        // low score inside recovery band keeps the streak alive (dead zone)
        assertTrue(rl1.score < p.closeScoreThreshold);
        assertTrue(h.consecutiveHits >= 1, "dead-zone dip must not reset the streak");
        assertFalse(rh2.recommendClosure, "streak cannot mature inside one span");
    }

    @Test
    void dropBelowRecoveryBandResetsStreak() {
        DepletionPolicy p = policy();
        HysteresisTracker h = new HysteresisTracker(0);
        GenerationTelemetry high = gen(9800, 395);
        for (int d = 0; d < 7; d++) opens(high, d, 120);
        DepletionEngine.evaluate(input(high, T0 + 20 * DAY, 10_000, 400), p, h);
        assertTrue(h.consecutiveHits == 1);

        GenerationTelemetry fresh = gen(100, 4);
        var r = DepletionEngine.evaluate(input(fresh, T0 + 20 * DAY, 10_000, 400), p, h);
        assertEquals(0, h.consecutiveHits, "clear recovery below band must reset");
        assertFalse(r.recommendClosure);
    }

    @Test
    void minimumAgeGateHoldsEvenWithPerfectDepletionEvidence() {
        GenerationTelemetry t = gen(9900, 398);
        for (int d = 0; d < 7; d++) opens(t, d, 150);
        DepletionPolicy p = policy(); // minAgeDays=3
        HysteresisTracker h = new HystererFix().get(p);
        var r = DepletionEngine.evaluate(input(t, T0 + 2 * DAY, 10_000, 400), p, h);
        assertFalse(r.recommendClosure);
        assertTrue(r.blockers.stream().anyMatch(b -> b.startsWith("minimum lifetime")));
    }

    /** tiny shim so the test above reads cleanly */
    private static final class HystererFix {
        HysteresisTracker get(DepletionPolicy p) { return new HysteresisTracker(p.minSustainedSpanMs); }
    }

    @Test
    void maxAgeBackstopFiresEvenWhenSpatialUnknownUnderBlock() {
        DepletionPolicy p = policy();
        // both spatial signals unknown, zone ancient and abandoned
        GenerationTelemetry t = new GenerationTelemetry(5, T0);
        var r = DepletionEngine.evaluate(
                new DepletionInput(TelemetrySnapshot.of(t, TelemetrySnapshot.Availability.AVAILABLE),
                        -1, -1, T0 + 40 * DAY, 0),
                p, new HysteresisTracker(0));
        assertTrue(r.recommendClosure, "max-age backstop must fire: " + r.blockers);
        assertTrue(r.blockers.stream().anyMatch(b -> b.contains("NOTE")));
    }

    @Test
    void fallbackModeStillRequiresMinimumKnownWeight() {
        DepletionPolicy p = policy();
        p.unknownSpatialHandling = "FALLBACK";
        p = DepletionPolicy.validated(p, new java.util.ArrayList<>());
        // only loot+activity+age known => 45/95 of non-disabled weight < 55%
        GenerationTelemetry t = new GenerationTelemetry(5, T0);
        opens(t, 1, 100);
        var r = DepletionEngine.evaluate(input(t, T0 + 20 * DAY, -1, -1), p, new HysteresisTracker(0));
        assertFalse(r.recommendClosure);
        assertTrue(r.blockers.stream().anyMatch(b -> b.contains("known evidence too thin")));
    }
}
