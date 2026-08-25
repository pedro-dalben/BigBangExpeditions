package com.bigbangcraft.expeditions.automation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Atomic persistence for {@link AutomationState}. Corruption fails SAFE:
 * paused=true + reason, pending dropped, streak reset — never silently
 * operational with unknown state (safety invariant 9/13).
 */
public final class AutomationStateStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final ReentrantLock lock = new ReentrantLock();
    private final Path file;

    public AutomationStateStore(Path file) {
        this.file = file;
    }

    public LoadResult load() {
        lock.lock();
        try {
            if (!Files.exists(file)) {
                return LoadResult.ok(AutomationState.fresh(), "absent");
            }
            String json = Files.readString(file);
            AutomationState s = GSON.fromJson(json, AutomationState.class);
            if (s == null || s.schemaVersion > AutomationState.SCHEMA_VERSION) {
                return LoadResult.failed("unsupported or unreadable schema");
            }
            if (s.shadow == null) s.shadow = new java.util.ArrayList<>();
            if (s.pending != null && (s.pending.reasons == null)) s.pending.reasons = new java.util.ArrayList<>();
            return LoadResult.ok(s, "ok");
        } catch (Exception e) {
            quarantine();
            return LoadResult.failed(e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    public void save(AutomationState s) throws IOException {
        lock.lock();
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(s));
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

    public record LoadResult(AutomationState state, boolean ok, String detail) {
        static LoadResult ok(AutomationState s, String d) { return new LoadResult(s, true, d); }
        static LoadResult failed(String why) { return new LoadResult(null, false, why); }
    }
}
