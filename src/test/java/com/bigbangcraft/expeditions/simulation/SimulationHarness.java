package com.bigbangcraft.expeditions.simulation;

import com.bigbangcraft.expeditions.telemetry.DayActivity;
import com.bigbangcraft.expeditions.telemetry.GenerationTelemetry;
import com.bigbangcraft.expeditions.telemetry.Saturation;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Deterministic expedition-telemetry simulation harness (Goal 05 requirement
 * 52). Generates thousands of logical activity events against the REAL
 * {@link GenerationTelemetry} model with a seeded RNG — same seed, same world.
 * No Minecraft runtime involved.
 *
 * Used by regression, property, exploit-resistance, fairness and calibration
 * campaigns; results are asserted, never hand-waved.
 */
public final class SimulationHarness {

    public static final class Scenario {
        public String name = "scenario";
        public int generation = 1;
        public int players = 20;
        public int days = 14;
        /** average first-entry chunks per active player-day */
        public double chunksPerPlayerDay = 40;
        /** probability a given player explores on a given day */
        public double participationRate = 0.6;
        /** container opens per participating player-day */
        public double opensPerPlayerDay = 3;
        /** deaths per participating player-day */
        public double deathProbabilityPerPlayerDay = 0.02;
        /** mob kills per participating player-day */
        public double killsPerPlayerDay = 2;
        /** structures discovered per participating player-day */
        public double structuresPerPlayerDay = 1.0;
        /** players that join only after day >= lateJoinDay (0 = from the start) */
        public int lateJoinDay = 0;
        /** fraction of players that are AFK shells (enter once, then nothing) */
        public double afkFraction = 0.0;
        /** one-day exploration burst on this day (x multiplier) */
        public int spikeDay = -1;
        public int spikeMultiplier = 1;
        /** after this day, all exploration stops (abandonment patterns) */
        public int quietAfterDay = Integer.MAX_VALUE;
    }

    public static final long HOUR = 3600_000L;
    public static final long DAY_MS = 24 * HOUR;

    private final Random random;
    public final List<String> log = new ArrayList<>();

    public SimulationHarness(long seed) {
        this.random = new Random(seed);
    }

    public Result run(Scenario s) {
        // openedAt anchored at epoch-ms 1 so age arithmetic works while staying
        // clearly synthetic
        GenerationTelemetry t = new GenerationTelemetry(s.generation, 1);
        List<UUID> roster = new ArrayList<>();
        for (int i = 0; i < s.players; i++) {
            UUID u = UUID.nameUUIDFromBytes((s.name + "-p" + i).getBytes());
            roster.add(u);
        }
        int afkCount = (int) Math.round(s.players * s.afkFraction);

        for (int day = 0; day < s.days; day++) {
            long dayStart = day * DAY_MS;
            int mult = day == s.spikeDay ? s.spikeMultiplier : 1;
            for (int pi = 0; pi < roster.size(); pi++) {
                UUID id = roster.get(pi);
                boolean afk = pi < afkCount;
                if (afk) {
                    if (day == 0) {
                        t.recordEntry(id, s.generation, dayStart + 9 * HOUR);
                        // AFK shells never generate further facts
                    }
                    continue;
                }
                if (day < s.lateJoinDay) continue;
                boolean participates = day < s.quietAfterDay
                        && random.nextDouble() < s.participationRate;
                if (!participates) continue;

                long ts = dayStart + (long) ((8 + random.nextInt(12)) % 22) * HOUR;
                t.recordEntry(id, s.generation, ts);

                int chunks = (int) Math.max(0, Math.round(s.chunksPerPlayerDay * mult
                        * (0.5 + random.nextDouble())));
                for (int c = 0; c < chunks; c++) {
                    long packed = random.nextLong(); // unique-enough packed coords
                    t.recordChunkFirstEntry(packed, s.generation,
                            ts + (c % 20) * 60_000L);
                }
                int structures = (int) Math.max(0, Math.round(s.structuresPerPlayerDay * mult));
                for (int st = 0; st < structures; st++) {
                    t.recordStructure("sim:building_" + random.nextInt(64),
                            random.nextLong(), s.generation, ts);
                }
                int opens = (int) Math.max(0, Math.round(s.opensPerPlayerDay * mult));
                for (int o = 0; o < opens; o++) {
                    t.recordContainerOpen(id, s.generation, ts + (o % 30) * 60_000L);
                }
                if (random.nextDouble() < s.deathProbabilityPerPlayerDay) {
                    t.recordDeath(id, s.generation, ts);
                }
                int kills = (int) Math.max(0, Math.round(s.killsPerPlayerDay));
                for (int k = 0; k < kills; k++) {
                    t.recordPlayerMobKill(id, s.generation, ts);
                }
                if (random.nextDouble() < 0.7) {
                    t.recordExit(id, s.generation, ts + 3 * HOUR);
                }
            }
        }
        return new Result(s, t, log);
    }

    /** Daily aggregate view for calibration output. */
    public static String daySummary(GenerationTelemetry t) {
        StringBuilder sb = new StringBuilder();
        for (DayActivity d : t.days.values()) {
            sb.append(String.format("%s entries=%d chunks=%d structures=%d opens=%d%n",
                    d.day, d.entries, d.chunkDiscoveries, d.structureDiscoveries, d.containerOpens));
        }
        return sb.toString();
    }

    public record Result(Scenario scenario, GenerationTelemetry telemetry, List<String> log) {
        public long distinctChunks() { return telemetry.distinctChunks(); }
        public long distinctExplorers() { return telemetry.distinctExplorers(); }
        public long opensTotal() { return telemetry.containerOpensTotal; }
    }

    /** Guard against accidental unbounded growth in future edits. */
    public static void assertBounded(GenerationTelemetry t) {
        org.junit.jupiter.api.Assertions.assertTrue(t.days.size() <= GenerationTelemetry.DAY_WINDOW_MAX);
        org.junit.jupiter.api.Assertions.assertTrue(t.uniqueExplorers.size() <= GenerationTelemetry.UNIQUE_CAP);
        org.junit.jupiter.api.Assertions.assertTrue(t.firstEntryChunks.size() <= GenerationTelemetry.CHUNK_CAP);
        org.junit.jupiter.api.Assertions.assertTrue(Saturation.clamp(t.entriesTotal) == t.entriesTotal);
    }
}
