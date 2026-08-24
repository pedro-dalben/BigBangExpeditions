package com.bigbangcraft.expeditions.reset;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Crash-safe phase journal for one destructive operation.
 *
 * The executor appends a phase marker ONLY AFTER the phase fully completed
 * (atomic write). A crash therefore always leaves an unambiguous last-known
 * state, which {@code StartupRecovery} translates into fail-closed behavior.
 *
 * Phase order (see docs/architecture/production-lifecycle.md):
 *   AUTH_VERIFIED -> BACKUP_START -> BACKUP_DONE -> DELETION_INTENT ->
 *   DELETION_DONE -> LIFECYCLE_RESETTING
 */
public final class OperationJournal {
    public static final String PHASE_AUTH_VERIFIED = "AUTH_VERIFIED";
    public static final String PHASE_BACKUP_START = "BACKUP_START";
    public static final String PHASE_BACKUP_DONE = "BACKUP_DONE";
    public static final String PHASE_DELETION_INTENT = "DELETION_INTENT";
    public static final String PHASE_DELETION_DONE = "DELETION_DONE";
    public static final String PHASE_LIFECYCLE_RESETTING = "LIFECYCLE_RESETTING";
    public static final String PHASE_FINALIZED = "FINALIZED";

    public String authId;
    public long startedAtEpochMs;
    public List<Phase> phases = new ArrayList<>();

    public static final class Phase {
        public String name;
        public long atEpochMs;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final transient Path dir;

    public OperationJournal(Path dir) {
        this.dir = dir;
    }

    private Path fileFor(String authId) {
        return dir.resolve(sanitize(authId) + ".op.json");
    }

    private static String sanitize(String id) {
        if (id == null || !id.matches("[0-9a-zA-Z\\-]{1,64}")) {
            throw new IllegalArgumentException("illegal auth id for journal");
        }
        return id;
    }

    /** True when a completed marker exists for the phase. */
    public synchronized boolean hasPhase(String authId, String phase) throws IOException {
        OperationJournal j = load(authId);
        return j != null && j.phases.stream().anyMatch(p -> phase.equals(p.name));
    }

    /** Appends a completed-phase marker atomically; creates the journal lazily. */
    public synchronized void recordCompleted(String authId, String phase, long nowEpochMs) throws IOException {
        Files.createDirectories(dir);
        OperationJournal j = load(authId);
        if (j == null) {
            j = newBlank(authId);
            j.startedAtEpochMs = nowEpochMs;
        }
        Phase p = new Phase();
        p.name = phase;
        p.atEpochMs = nowEpochMs;
        j.phases.add(p);
        Path tmp = fileFor(authId).resolveSibling(fileFor(authId).getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(j));
        Files.move(tmp, fileFor(authId), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private OperationJournal newBlank(String authId) {
        OperationJournal j = new OperationJournal(this.dir);
        j.authId = authId;
        return j;
    }

    /** Null when no journal exists for this auth id. */
    public synchronized OperationJournal load(String authId) throws IOException {
        Path f = fileFor(authId);
        if (!Files.isRegularFile(f)) return null;
        OperationJournal j = GSON.fromJson(Files.readString(f), OperationJournal.class);
        if (j != null && j.phases == null) j.phases = new ArrayList<>();
        return j;
    }

    /** Most recent unfinished-or-finished operation across the directory, or null. */
    public synchronized OpSummary summarizeLatest() throws IOException {
        if (!Files.isDirectory(dir)) return null;
        OpSummary best = null;
        try (var stream = Files.list(dir)) {
            for (Path p : stream.filter(f -> f.getFileName().toString().endsWith(".op.json")).toList()) {
                try {
                    OperationJournal j = GSON.fromJson(Files.readString(p), OperationJournal.class);
                    if (j == null || j.authId == null) continue;
                    boolean finished = j.phases.stream().anyMatch(ph -> PHASE_FINALIZED.equals(ph.name));
                    // last COMPLETED OPERATIONAL phase (FINALIZED is pure bookkeeping)
                    String last = j.phases.stream().map(ph -> ph.name)
                            .filter(n -> !PHASE_FINALIZED.equals(n))
                            .reduce((a, b) -> b)
                            .orElse(null);
                    OpSummary s = new OpSummary(j.authId, !finished, last, j.startedAtEpochMs);
                    if (best == null || s.startedAt > best.startedAt) best = s;
                } catch (Exception ignored) {
                    // unreadable journal is itself evidence of interruption
                    OpSummary s = new OpSummary(p.getFileName().toString(), true, "UNREADABLE", Long.MAX_VALUE);
                    if (best == null) best = s;
                }
            }
        }
        return best;
    }

    public record OpSummary(String authId, boolean hasActiveOp, String lastCompletedPhase, long startedAt) {}

    public void remove(String authId) throws IOException {
        Files.deleteIfExists(fileFor(sanitize(authId)));
    }
}
