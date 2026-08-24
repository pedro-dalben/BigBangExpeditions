package com.bigbangcraft.expeditions.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AuditLogTest {

    @TempDir
    Path tmp;

    private Path file() {
        return tmp.resolve("audit/audit.jsonl");
    }

    @Test
    void appendCreatesReadableJsonLines() throws IOException {
        AuditLog log = new AuditLog(file(), 1_000_000, 3);
        log.append(AuditEvent.of("LIFECYCLE_TRANSITION", "alice")
                .action("close").subject("dimension")
                .states("OPEN", "CLOSING").outcome("OK"));
        log.append(AuditEvent.of("RESET_PLAN_CREATED", "bob")
                .subject("auth-123").outcome("OK"));

        List<AuditEvent> all = log.readAll();
        assertEquals(2, all.size());
        assertEquals("alice", all.get(0).actor);
        assertEquals("OPEN", all.get(0).fromState);
        assertEquals("CLOSING", all.get(0).toState);
        assertEquals("auth-123", all.get(1).subject);
        assertTrue(all.get(0).tsEpochMs > 0);
    }

    @Test
    void refusalsAreRecorded() throws IOException {
        AuditLog log = new AuditLog(file(), 1_000_000, 2);
        log.append(AuditEvent.of("RESET_EXECUTE", "mallory")
                .outcome("REFUSED").reason("stale authorization"));
        assertEquals("REFUSED", log.readAll().get(0).outcome);
        assertTrue(log.readAll().get(0).reason.contains("stale"));
    }

    @Test
    void rotatesBySizeKeepingConfiguredCount() throws IOException {
        // tiny threshold forces rotation quickly
        AuditLog log = new AuditLog(file(), 4096, 2);
        String filler = "x".repeat(300);
        for (int i = 0; i < 60; i++) {
            log.append(AuditEvent.of("BULK", "sys").reason(filler));
        }
        List<Path> files;
        try (var s = Files.list(file().getParent())) {
            files = s.toList();
        }
        long active = files.stream().filter(p -> p.getFileName().toString().equals("audit.jsonl")).count();
        assertEquals(1, active);
        // rotated files exist, at most maxRotatedFiles of them
        long rotated = files.stream().filter(p -> p.getFileName().toString().matches("audit-\\d+\\.jsonl")).count();
        assertTrue(rotated >= 1 && rotated <= 2, () -> "rotated=" + rotated + " files=" + files);

        List<AuditEvent> all = log.readAll();
        // bounded retention: current + at most maxRotatedFiles historical files
        assertTrue(all.size() > 0 && all.size() < 60);
        AuditEvent newest = all.get(all.size() - 1);
        assertEquals("BULK", newest.event);
    }

    @Test
    void concurrentAppendsDoNotCorruptLines() throws Exception {
        AuditLog log = new AuditLog(file(), 10_000_000, 3);
        int threads = 8;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final int id = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        log.record(AuditEvent.of("CONCURRENT", "t" + id).detail("i", "" + i));
                    }
                } catch (Exception e) {
                    fail(e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        List<AuditEvent> all = log.readAll();
        assertEquals(threads * perThread, all.size());
    }

    @Test
    void recordThrowsLoudlyWhenWriteImpossible() throws IOException {
        Path blocker = tmp.resolve("missing-dir-subdir");
        Files.writeString(blocker, "i am a file");
        // parent path is a FILE so createDirectories must fail
        Path badFile = blocker.resolve("audit.jsonl");
        AuditLog log = new AuditLog(badFile, 1_000_000, 1);
        assertThrows(IllegalStateException.class,
                () -> log.record(AuditEvent.of("X", "y")));
    }
}
