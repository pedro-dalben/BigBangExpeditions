package com.bigbangcraft.expeditions.telemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GenerationTelemetryTest {
    private static final int GEN = 7;
    private static final long T0 = 1_700_000_000_000L;
    private static final UUID P1 = UUID.nameUUIDFromBytes("p1".getBytes());
    private static final UUID P2 = UUID.nameUUIDFromBytes("p2".getBytes());

    @Test
    void generationIsolation_refusesFactsFromOtherGenerations() {
        GenerationTelemetry t = new GenerationTelemetry(GEN, T0);
        assertFalse(t.recordEntry(P1, GEN + 1, T0));
        assertFalse(t.recordChunkFirstEntry(123L, GEN - 1, T0));
        assertFalse(t.recordStructure("lostcities:city", 5L, 99, T0));
        assertEquals(0, t.entriesTotal);
        assertEquals(0, t.distinctChunks());
        assertEquals(0, t.totalStructurePlacements());
    }

    @Test
    void closedGenerationAcceptsNoFurtherFacts() {
        GenerationTelemetry t = new GenerationTelemetry(GEN, T0);
        t.markClosed(T0 + 1000);
        assertTrue(t.isClosed());
        assertFalse(t.recordEntry(P1, GEN, T0 + 2000));
        assertEquals(0, t.entriesTotal);
    }

    @Test
    void duplicateEntryDoesNotDoubleCountUniqueExplorer() {
        GenerationTelemetry t = new GenerationTelemetry(GEN, T0);
        assertTrue(t.recordEntry(P1, GEN, T0));
        t.recordEntry(P1, GEN, T0); // re-entry same cycle: accepted boundary fact
        assertEquals(1, t.distinctExplorers());
        assertEquals(2, t.entriesTotal); // but the boundary event still counts
    }

    @Test
    void chunkFirstEntryDeduplicates() {
        GenerationTelemetry t = new GenerationTelemetry(GEN, T0);
        assertTrue(t.recordChunkFirstEntry(42L, GEN, T0));
        assertFalse(t.recordChunkFirstEntry(42L, GEN, T0));
        assertTrue(t.recordChunkFirstEntry(43L, GEN, T0));
        assertEquals(2, t.distinctChunks());
    }

    @Test
    void structureSectionDuplicatesDoNotDoubleCount() {
        GenerationTelemetry t = new GenerationTelemetry(GEN, T0);
        assertTrue(t.recordStructure("lostcities:city", 9L, GEN, T0));
        assertFalse(t.recordStructure("lostcities:city", 9L, GEN, T0));
        assertTrue(t.recordStructure("lostcities:city", 10L, GEN, T0));
        assertEquals(2, t.totalStructurePlacements());
        assertEquals(1, t.structures.size());
    }

    @Test
    void countersSaturateNeverGoNegative() {
        GenerationTelemetry t = new GenerationTelemetry(GEN, T0);
        long big = Saturation.CEILING;
        t.entriesTotal = big;
        t.recordEntry(P1, GEN, T0);
        assertEquals(Saturation.CEILING, t.entriesTotal);

        // hostile negative mutation through clamp path stays bounded on next add
        t.containerOpensTotal = -50; // simulate corrupted in-memory value
        t.recordContainerOpen(P1, GEN, T0);
        assertEquals(1, t.containerOpensTotal);
    }

    @Test
    void uniqueSetSaturatesWithOverflowCounting() {
        GenerationTelemetry t = new GenerationTelemetry(GEN, T0);
        for (int i = 0; i < GenerationTelemetry.UNIQUE_CAP + 25; i++) {
            t.recordEntry(UUID.nameUUIDFromBytes(("u" + i).getBytes()), GEN, T0);
        }
        assertEquals(GenerationTelemetry.UNIQUE_CAP, t.uniqueExplorers.size());
        assertEquals(25, t.uniqueExplorerOverflow);
        assertEquals(GenerationTelemetry.UNIQUE_CAP + 25, t.distinctExplorers());
        assertTrue(t.qualityFlags.contains("UNIQUE_SET_SATURATED"));
    }

    @Test
    void chunkSetSaturatesWithOverflowAndFlag() {
        GenerationTelemetry t = new GenerationTelemetry(GEN, T0);
        for (long c = 0; c < GenerationTelemetry.CHUNK_CAP + 5; c++) {
            t.recordChunkFirstEntry(c, GEN, T0);
        }
        assertEquals(GenerationTelemetry.CHUNK_CAP, t.firstEntryChunks.size());
        assertEquals(5, t.firstEntryOverflow);
        assertTrue(t.qualityFlags.contains("CHUNK_SET_SATURATED"));
    }

    @Test
    void dayBucketsTrimToRollingWindow() {
        GenerationTelemetry t = new GenerationTelemetry(GEN, T0);
        long day = 24L * 3600 * 1000;
        for (int i = 0; i < 120; i++) {
            t.recordEntry(UUID.nameUUIDFromBytes(("d" + i).getBytes()), GEN, T0 + i * day);
        }
        assertTrue(t.days.size() <= GenerationTelemetry.DAY_WINDOW_MAX);
        t.trimDays(30);
        assertEquals(30, t.days.size());
    }

    @Test
    void peakConcurrentTracksMaximumOnly() {
        GenerationTelemetry t = new GenerationTelemetry(GEN, T0);
        t.observeConcurrentInside(3, GEN, T0);
        t.observeConcurrentInside(9, GEN, T0 + 1);
        t.observeConcurrentInside(2, GEN, T0 + 2);
        assertEquals(9, t.peakConcurrentInside);
    }

    @Test
    void nullPlayerIdIsToleratedWithoutUniqueTracking() {
        GenerationTelemetry t = new GenerationTelemetry(GEN, T0);
        assertTrue(t.recordEntry(null, GEN, T0));
        assertEquals(0, t.uniqueExplorers.size());
        assertEquals(1, t.entriesTotal);
    }

    // ---------------------------------------------------------------- store

    @Test
    void storeRoundTripPreservesAggregates(@TempDir Path dir) throws IOException {
        TelemetryStore store = new TelemetryStore(dir);
        GenerationTelemetry t = new GenerationTelemetry(GEN, T0);
        t.recordEntry(P1, GEN, T0);
        t.recordContainerOpen(P2, GEN, T0 + 1);
        t.recordDeath(P1, GEN, T0 + 2);
        t.recordChunkFirstEntry(777L, GEN, T0 + 3);
        t.recordStructure("lostcities:building_officebank", 31L, GEN, T0 + 4);
        store.save(t);

        TelemetryStore.LoadResult r = store.load(GEN);
        assertEquals(TelemetryStore.Status.AVAILABLE, r.status);
        GenerationTelemetry back = r.record;
        assertEquals(1, back.distinctExplorers());
        assertEquals(1, back.containerOpensTotal);
        assertEquals(1, back.deathsTotal);
        assertEquals(1, back.distinctChunks());
        assertEquals(1, back.totalStructurePlacements());
        assertEquals(GEN, back.generation);
    }

    @Test
    void missingFileYieldsMissingNotCorrupt(@TempDir Path dir) {
        TelemetryStore.LoadResult r = new TelemetryStore(dir).load(GEN);
        assertEquals(TelemetryStore.Status.MISSING, r.status);
        assertTrue(r.usable());
        assertEquals(GEN, r.record.generation);
    }

    @Test
    void corruptFileQuarantinedAndRefused(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("gen-" + GEN + ".json"), "{ this is not json ");
        TelemetryStore.LoadResult r = new TelemetryStore(dir).load(GEN);
        assertEquals(TelemetryStore.Status.CORRUPT, r.status);
        assertFalse(r.usable());
        try (var stream = Files.list(dir)) {
            assertTrue(stream.anyMatch(p -> p.getFileName().toString().contains(".corrupt-")));
        }
    }

    @Test
    void truncatedFileQuarantinedAndRefused(@TempDir Path dir) throws IOException {
        TelemetryStore store = new TelemetryStore(dir);
        GenerationTelemetry t = new GenerationTelemetry(GEN, T0);
        t.recordEntry(P1, GEN, T0);
        store.save(t);
        Path f = dir.resolve("gen-" + GEN + ".json");
        String full = Files.readString(f);
        Files.writeString(f, full.substring(0, full.length() / 2));

        TelemetryStore.LoadResult r = store.load(GEN);
        assertEquals(TelemetryStore.Status.CORRUPT, r.status);
        assertNull(r.record);
    }

    @Test
    void futureSchemaRefusedWithoutQuarantine(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("gen-" + GEN + ".json"),
                "{\"schemaVersion\":999,\"generation\":" + GEN + "}");
        TelemetryStore.LoadResult r = new TelemetryStore(dir).load(GEN);
        assertEquals(TelemetryStore.Status.UNSUPPORTED_SCHEMA, r.status);
        assertFalse(r.usable());
        // original preserved for operator/migration tooling
        assertTrue(Files.exists(dir.resolve("gen-" + GEN + ".json")));
    }

    @Test
    void mismatchedGenerationBindingRefused(@TempDir Path dir) throws IOException {
        TelemetryStore store = new TelemetryStore(dir);
        // file named for gen 8 but bound to gen 7 (stale/misnamed rollover artifact)
        GenerationTelemetry stale = new GenerationTelemetry(GEN, T0);
        store.save(stale);
        Files.move(dir.resolve("gen-" + GEN + ".json"), dir.resolve("gen-" + (GEN + 1) + ".json"));
        TelemetryStore.LoadResult r = store.load(GEN + 1);
        assertEquals(TelemetryStore.Status.CORRUPT, r.status);
        assertTrue(r.detail.contains("generation"));
    }

    @Test
    void impossibleNegativeCountersNormalizedOnLoad(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("gen-" + GEN + ".json"),
                "{\"schemaVersion\":1,\"generation\":" + GEN + ",\"entriesTotal\":-9000}");
        TelemetryStore.LoadResult r = new TelemetryStore(dir).load(GEN);
        assertEquals(TelemetryStore.Status.AVAILABLE, r.status);
        // negatives are treated as unusable evidence by engine via quality gate; raw load keeps field
        // but any further increment must land at a sane saturated value:
        r.record.recordEntry(P2, GEN, T0);
        assertEquals(1, r.record.entriesTotal);
    }
}
