package com.bigbangcraft.expeditions.simulation;

import com.bigbangcraft.expeditions.telemetry.CycleArchive;
import com.bigbangcraft.expeditions.telemetry.CycleArchiveStore;
import com.bigbangcraft.expeditions.telemetry.GenerationTelemetry;
import com.bigbangcraft.expeditions.telemetry.TelemetryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Accelerated lifecycle soak (Goal 05 requirement 71): 200 full
 * open→activity→close→archive generations through the REAL stores.
 * Watches for progressive growth, generation mismatch and drift.
 */
class LongRunSoakTest {
    private static final long DAY = SimulationHarness.DAY_MS;

    @Test
    void twoHundredGenerations_stayBoundedAndConsistent(@TempDir Path dir) throws IOException {
        Path tdir = dir.resolve("telemetry");
        TelemetryStore store = new TelemetryStore(tdir);
        CycleArchiveStore archiveStore = new CycleArchiveStore(dir.resolve("cycle-history.json"));
        CycleArchive archive = archiveStore.loadTolerant();

        long t0 = System.nanoTime();
        for (int gen = 1; gen <= 200; gen++) {
            GenerationTelemetry t = new GenerationTelemetry(gen, (gen - 1) * DAY);
            for (int p = 0; p < 25; p++) {
                UUID u = UUID.nameUUIDFromBytes(("soak" + gen + "-" + p).getBytes());
                assertTrue(t.recordEntry(u, gen, (gen - 1) * DAY));
                for (int c = 0; c < 30; c++) {
                    t.recordChunkFirstEntry((long) gen * 100_000 + c, gen, (gen - 1) * DAY);
                }
                for (int o = 0; o < 5; o++) {
                    t.recordContainerOpen(u, gen, (gen - 1) * DAY);
                }
            }
            t.markClosed(gen * DAY);
            store.save(t);

            // restart-consistency: reload from disk equals in-memory aggregates
            var r = store.load(gen);
            assertEquals(TelemetryStore.Status.AVAILABLE, r.status);
            assertEquals(t.distinctChunks(), r.record.distinctChunks());

            archive.append(com.bigbangcraft.expeditions.telemetry.CycleSummary.of(
                    t, "depletion", "automation:AUTOMATIC_CLOSURE", gen * DAY));
            archiveStore.save(archive);
            assertTrue(Files.deleteIfExists(store.fileFor(gen)),
                    "generation file must be removable after archival");
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;

        // bounded history despite 200 cycles
        assertEquals(CycleArchive.CAP, archive.summaries.size());
        assertEquals(200, archive.summaries.get(archive.summaries.size() - 1).generation);
        assertEquals(151, archive.summaries.get(0).generation); // oldest trimmed

        // no telemetry file accumulation
        try (var s = Files.list(tdir)) {
            assertEquals(0, s.filter(p -> p.getFileName().toString().endsWith(".json")).count(),
                    "no per-generation files may survive archival");
        }

        // archive size bounded on disk
        long bytes = Files.size(dir.resolve("cycle-history.json"));
        assertTrue(bytes < 512 * 1024, "archive grew to " + bytes + " bytes");

        // throughput sane for a soak (generous bound keeps CI stable)
        assertTrue(ms < 60_000, "soak took " + ms + "ms");
        System.out.printf("[soak] 200 generations archived in %d ms; archive %d bytes%n", ms, bytes);

        // reloaded archive remains consistent after all churn
        CycleArchive reloaded = archiveStore.loadTolerant();
        assertEquals(200, reloaded.byGeneration(200).generation);
        assertNull(reloaded.byGeneration(150), "trimmed entry must stay gone");
    }
}
