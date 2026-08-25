package com.bigbangcraft.expeditions.telemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CycleArchiveTest {
    private static final long T0 = 1_700_000_000_000L;

    private static GenerationTelemetry closed(int gen, long closedAt) {
        GenerationTelemetry t = new GenerationTelemetry(gen, T0);
        t.recordEntry(java.util.UUID.nameUUIDFromBytes(("p" + gen).getBytes()), gen, T0);
        t.markClosed(closedAt);
        return t;
    }

    private static CycleSummary summary(int gen, String reason, String actor, long closedAt) {
        return CycleSummary.of(closed(gen, closedAt), reason, actor, closedAt);
    }

    @Test
    void appendIsIdempotentPerGeneration(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("cycle-history.json");
        CycleArchiveStore store = new CycleArchiveStore(f);
        CycleArchive a = store.loadTolerant();
        a.append(summary(3, "manual", "op", T0 + 10));
        store.save(a);
        a = store.loadTolerant();
        a.append(summary(3, "manual", "op", T0 + 20));
        store.save(a);

        CycleArchive loaded = new CycleArchiveStore(f).loadTolerant();
        assertEquals(1, loaded.summaries.size());
        assertEquals(T0 + 20, loaded.summaries.get(0).closedAtEpochMs);
    }

    @Test
    void archiveBoundedToCap(@TempDir Path dir) throws IOException {
        CycleArchiveStore store = new CycleArchiveStore(dir.resolve("cycle-history.json"));
        CycleArchive a = store.loadTolerant();
        for (int g = 1; g <= CycleArchive.CAP + 12; g++) {
            a.append(summary(g, "depletion", "automation:AUTOMATIC_CLOSURE", T0 + g));
        }
        assertEquals(CycleArchive.CAP, a.summaries.size());
        assertEquals(13, a.summaries.get(0).generation); // oldest dropped (62-50+1)
        assertEquals(CycleArchive.CAP + 12, a.summaries.get(a.summaries.size() - 1).generation);
        store.save(a);

        CycleArchive reloaded = new CycleArchiveStore(dir.resolve("cycle-history.json")).loadTolerant();
        assertEquals(CycleArchive.CAP, reloaded.summaries.size());
    }

    @Test
    void corruptArchiveStartsCleanAndQuarantines(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("cycle-history.json"), "not-json-at-all");
        CycleArchive a = new CycleArchiveStore(dir.resolve("cycle-history.json")).loadTolerant();
        assertEquals(0, a.summaries.size());
        try (var s = Files.list(dir)) {
            assertTrue(s.anyMatch(p -> p.getFileName().toString().contains(".corrupt-")));
        }
    }

    @Test
    void futureSchemaStartsCleanWithoutDestructiveGuess(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("cycle-history.json"),
                "{\"schemaVersion\":42,\"summaries\":[]}");
        assertTrue(new CycleArchiveStore(dir.resolve("cycle-history.json")).loadTolerant().summaries.isEmpty());
    }

    @Test
    void truncatedSummaryJsonStartsClean(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("cycle-history.json"),
                "{\"schemaVersion\":1,\"summaries\":[{\"generation\":5");
        assertTrue(new CycleArchiveStore(dir.resolve("cycle-history.json")).loadTolerant().summaries.isEmpty());
    }

    @Test
    void summaryCarriesClosureAttribution() {
        GenerationTelemetry t = closed(9, T0 + 5_000);
        t.deathsTotal = 4; // direct fact injection for assertion
        CycleSummary s = CycleSummary.of(t, "max-age", "automation:SCHEDULED_WITH_APPROVAL", T0 + 5_000);
        assertEquals(9, s.generation);
        assertEquals("max-age", s.closureReason);
        assertEquals("automation:SCHEDULED_WITH_APPROVAL", s.closureActor);
        assertEquals(5_000, s.durationMs);
        assertEquals("PENDING", s.resetResult);
        assertEquals(4, s.deathsTotal);
        assertEquals(1, s.distinctExplorers);
    }
}
