package com.bigbangcraft.expeditions.reset;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;

/**
 * Persisted single-use ledger for authorization artifacts.
 *
 * Lifecycle of a ledger entry: ISSUED -> CONSUMED | REVOKED.
 * An artifact that is missing from the ledger, already consumed, or revoked
 * can never authorize destructive work. Atomic writes; corrupt files throw.
 */
public final class AuthorizationLedger {
    public enum Status { ISSUED, CONSUMED, REVOKED }

    public static final class Entry {
        public Status status = Status.ISSUED;
        public long issuedAtEpochMs;
        public long finalizedAtEpochMs = -1;
        public String issuedBy = "";
        public String finalizedBy = "";
        public int generationAtIssue = -1;
        public String sectorId = "";
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;

    public AuthorizationLedger(Path file) {
        this.file = file;
    }

    private TreeMap<String, Entry> load() throws IOException {
        if (!Files.isRegularFile(file)) return new TreeMap<>();
        Map<String, Entry> m = GSON.fromJson(Files.readString(file),
                new TypeToken<TreeMap<String, Entry>>() {}.getType());
        if (m == null) throw new IOException("authorization ledger unreadable: " + file);
        return new TreeMap<>(m);
    }

    private void save(TreeMap<String, Entry> entries) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(entries));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Error message on refusal, empty on success. */
    public synchronized java.util.Optional<String> recordIssued(String authId, int generation,
                                                                String by, long nowEpochMs) throws IOException {
        return recordIssued(authId, generation, "", by, nowEpochMs);
    }

    public synchronized java.util.Optional<String> recordIssued(String authId, int generation, String sectorId,
                                                                String by, long nowEpochMs) throws IOException {
        if (authId == null || authId.isBlank()) return java.util.Optional.of("auth id blank");
        TreeMap<String, Entry> all = load();
        if (all.containsKey(authId)) return java.util.Optional.of("auth already recorded: " + authId);
        Entry e = new Entry();
        e.issuedAtEpochMs = nowEpochMs;
        e.issuedBy = by == null ? "" : by;
        e.generationAtIssue = generation;
        e.sectorId = sectorId == null ? "" : sectorId;
        all.put(authId, e);
        save(all);
        return java.util.Optional.empty();
    }

    /** All entries (read-only view). */
    public synchronized java.util.Map<String, Entry> all() throws IOException {
        return new TreeMap<>(load());
    }

    /** Auth ids currently in ISSUED state for the given sector. */
    public synchronized java.util.List<String> issuedFor(String sectorId) throws IOException {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Map.Entry<String, Entry> e : load().entrySet()) {
            if (e.getValue().status == Status.ISSUED && e.getValue().sectorId.equals(sectorId)) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /**
     * Atomically consumes an artifact: only an entry in state ISSUED may flip
     * to CONSUMED. Second consumption attempts fail (single-use guarantee).
     */
    public synchronized java.util.Optional<String> consume(String authId, String by, long nowEpochMs) throws IOException {
        TreeMap<String, Entry> all = load();
        Entry e = all.get(authId);
        if (e == null) return java.util.Optional.of("auth unknown to ledger — never issued here");
        if (e.status != Status.ISSUED) {
            return java.util.Optional.of("auth not consumable (status=" + e.status
                    + ", finalizedBy=" + e.finalizedBy + ")");
        }
        e.status = Status.CONSUMED;
        e.finalizedAtEpochMs = nowEpochMs;
        e.finalizedBy = by == null ? "" : by;
        save(all);
        return java.util.Optional.empty();
    }

    public synchronized java.util.Optional<String> revoke(String authId, String by, long nowEpochMs) throws IOException {
        TreeMap<String, Entry> all = load();
        Entry e = all.get(authId);
        if (e == null) return java.util.Optional.of("auth unknown to ledger");
        if (e.status != Status.ISSUED) return java.util.Optional.of("auth already final (" + e.status + ")");
        e.status = Status.REVOKED;
        e.finalizedAtEpochMs = nowEpochMs;
        e.finalizedBy = by == null ? "" : by;
        save(all);
        return java.util.Optional.empty();
    }

    public synchronized Entry get(String authId) throws IOException {
        return load().get(authId);
    }
}
