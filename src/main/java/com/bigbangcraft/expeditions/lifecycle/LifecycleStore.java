package com.bigbangcraft.expeditions.lifecycle;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Atomic persistence for the dimension lifecycle record. Corrupt files throw
 * (fail-closed): callers must not silently assume OPEN.
 */
public final class LifecycleStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final ReentrantLock lock = new ReentrantLock();

    private final Path file;

    public LifecycleStore(Path file) {
        this.file = file;
    }

    public static Path defaultFile(Path bbeDir) {
        return bbeDir.resolve("lifecycle.json");
    }

    public LifecycleRecord load() throws IOException {
        lock.lock();
        try {
            if (!Files.exists(file)) return new LifecycleRecord();
            String json = Files.readString(file);
            LifecycleRecord r = GSON.fromJson(json, LifecycleRecord.class);
            if (r == null || r.status == null) {
                throw new IOException("lifecycle record unreadable/incomplete: " + file);
            }
            if (r.recent == null) r.recent = new ArrayList<>();
            return r;
        } catch (com.google.gson.JsonParseException e) {
            throw new IOException("lifecycle record corrupt: " + file, e);
        } finally {
            lock.unlock();
        }
    }

    public void save(LifecycleRecord r) throws IOException {
        lock.lock();
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(r));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            lock.unlock();
        }
    }
}
