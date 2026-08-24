package com.bigbangcraft.expeditions.reset;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Persistent mutual-exclusion for destructive reset operations.
 *
 * Two layers:
 *  - OS flock (executor scripts) guards simultaneous processes on one machine;
 *  - THIS lock file guards the logical operation across processes and restarts,
 *    including the offline window where the game server itself is stopped.
 *
 * Semantics:
 *  - acquire fails while a live lock exists;
 *  - a lock older than its TTL is STALE and may be taken over (the takeover is
 *    recorded by writing the new lock);
 *  - a CORRUPT lock file is treated as held-by-unknown forever (fail-closed):
 *    operators must delete it manually after inspection — never automatic.
 */
public final class ResetLock {
    public static final long DEFAULT_TTL_MS = 12L * 60 * 60 * 1000; // 12h

    public static final class LockData {
        public String holder;
        public String purpose;
        public String pidNote = "";
        public long acquiredAtEpochMs;
        public long expiresAtEpochMs;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;

    public ResetLock(Path file) {
        this.file = file;
    }

    /** Error message on refusal, empty on success. */
    public synchronized Optional<String> acquire(String holder, String purpose, long nowEpochMs, long ttlMs)
            throws IOException {
        LockData cur = readRaw();
        if (cur != null && !isStale(cur, nowEpochMs)) {
            boolean sameHolder = holder != null && holder.equals(cur.holder);
            if (!sameHolder) {
                return Optional.of("reset locked since " + cur.acquiredAtEpochMs
                        + " by '" + cur.holder + "' (" + cur.purpose + ") — concurrent resets are forbidden");
            }
            // same holder re-acquiring (retry after uncertain response) is idempotent-safe
        }
        LockData next = new LockData();
        next.holder = holder == null ? "" : holder;
        next.purpose = purpose == null ? "" : purpose;
        next.acquiredAtEpochMs = nowEpochMs;
        next.expiresAtEpochMs = nowEpochMs + Math.max(1000, ttlMs);
        write(next);
        return Optional.empty();
    }

    /** Only the holder may release; unknown/foreign locks require manual action. */
    public synchronized Optional<String> release(String holder, long nowEpochMs) throws IOException {
        LockData cur = readRaw();
        if (cur == null) return Optional.of("no reset lock present");
        if (!cur.holder.equals(holder)) {
            return Optional.of("lock held by '" + cur.holder + "' — refusing release by '" + holder + "'");
        }
        Files.deleteIfExists(file);
        return Optional.empty();
    }

    public synchronized boolean isLocked(long nowEpochMs) throws IOException {
        LockData cur = readRaw();
        return cur != null && !isStale(cur, nowEpochMs);
    }

    public synchronized LockData current() throws IOException {
        return readRaw();
    }

    private boolean isStale(LockData d, long nowEpochMs) {
        return nowEpochMs > d.expiresAtEpochMs;
    }

    /** Null when absent; sentinel-with-null-holder when corrupt (fail-closed). */
    private LockData readRaw() throws IOException {
        if (!Files.isRegularFile(file)) return null;
        try {
            return GSON.fromJson(Files.readString(file), LockData.class);
        } catch (Exception e) {
            LockData corrupt = new LockData();
            corrupt.holder = "\0corrupt";
            corrupt.purpose = "corrupt lock file — manual inspection required";
            corrupt.acquiredAtEpochMs = Long.MAX_VALUE / 2;
            corrupt.expiresAtEpochMs = Long.MAX_VALUE / 2;
            return corrupt;
        }
    }

    private void write(LockData d) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(d));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
