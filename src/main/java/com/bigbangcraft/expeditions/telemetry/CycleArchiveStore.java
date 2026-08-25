package com.bigbangcraft.expeditions.telemetry;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Atomic persistence for {@link CycleArchive}. History is advisory-grade
 * evidence: a corrupt archive starts clean (quarantined) rather than blocking
 * operation, but the raw bytes are preserved for operator inspection.
 */
public final class CycleArchiveStore {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final ReentrantLock lock = new ReentrantLock();
    private final Path file;

    public CycleArchiveStore(Path file) {
        this.file = file;
    }

    public CycleArchive loadTolerant() {
        lock.lock();
        try {
            if (!Files.exists(file)) return new CycleArchive();
            CycleArchive parsed = CycleArchive.parseOrNull(Files.readString(file));
            if (parsed == null) {
                quarantine();
                return new CycleArchive();
            }
            return parsed;
        } catch (Exception e) {
            quarantine();
            return new CycleArchive();
        } finally {
            lock.unlock();
        }
    }

    public void save(CycleArchive a) throws IOException {
        lock.lock();
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(a));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            lock.unlock();
        }
    }

    private void quarantine() {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis()),
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
        }
    }
}
