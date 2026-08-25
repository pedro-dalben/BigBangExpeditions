package com.bigbangcraft.expeditions.telemetry;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Atomic persistence for one generation's telemetry.
 *
 * <p>Fail-safe contract (Goal 05 requirement 7/43): a corrupt or
 * unsupported-schema file NEVER yields a usable-looking empty record that an
 * engine could misread as "no activity". The caller receives an outcome that
 * forces unknown-signal semantics; the raw bytes are quarantined next to the
 * original for operator inspection. Telemetry corruption can therefore never
 * manufacture evidence of depletion.
 */
public final class TelemetryStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final ReentrantLock lock = new ReentrantLock();

    private final Path dir;

    public TelemetryStore(Path telemetryDir) {
        this.dir = telemetryDir;
    }

    public enum Status { AVAILABLE, MISSING, UNSUPPORTED_SCHEMA, CORRUPT }

    public static final class LoadResult {
        public final Status status;
        public final GenerationTelemetry record; // non-null only for AVAILABLE/MISSING(fresh)
        public final String detail;

        LoadResult(Status status, GenerationTelemetry record, String detail) {
            this.status = status;
            this.record = record;
            this.detail = detail;
        }

        public boolean usable() {
            return status == Status.AVAILABLE || status == Status.MISSING;
        }
    }

    public Path fileFor(int generation) {
        return dir.resolve("gen-" + generation + ".json");
    }

    public LoadResult load(int generation) {
        lock.lock();
        try {
            Path file = fileFor(generation);
            if (!Files.exists(file)) {
                return new LoadResult(Status.MISSING, new GenerationTelemetry(generation, 0L), "absent");
            }
            String json;
            try {
                json = Files.readString(file);
            } catch (IOException e) {
                quarantine(file, e.toString());
                return new LoadResult(Status.CORRUPT, null, "unreadable: " + e.getMessage());
            }
            GenerationTelemetry t;
            try {
                t = GSON.fromJson(json, GenerationTelemetry.class);
            } catch (RuntimeException e) { // JsonParseException family
                quarantine(file, e.toString());
                return new LoadResult(Status.CORRUPT, null, "parse failure: " + e.getMessage());
            }
            if (t == null || t.generation != generation) {
                quarantine(file, "generation-mismatch");
                return new LoadResult(Status.CORRUPT, null,
                        "record generation " + (t == null ? "?" : t.generation) + " != requested " + generation);
            }
            if (t.schemaVersion > GenerationTelemetry.SCHEMA_VERSION) {
                return new LoadResult(Status.UNSUPPORTED_SCHEMA, null,
                        "schema " + t.schemaVersion + " > supported " + GenerationTelemetry.SCHEMA_VERSION);
            }
            normalize(t);
            return new LoadResult(Status.AVAILABLE, t, "ok");
        } finally {
            lock.unlock();
        }
    }

    /** Older schemas are migrated forward here; v1 is current so only hygiene runs. */
    private void normalize(GenerationTelemetry t) {
        if (t.uniqueExplorers == null) t.uniqueExplorers = new java.util.HashSet<>();
        if (t.firstEntryChunks == null) t.firstEntryChunks = new java.util.HashSet<>();
        if (t.structures == null) t.structures = new java.util.TreeMap<>();
        if (t.days == null) t.days = new java.util.TreeMap<>();
        if (t.qualityFlags == null) t.qualityFlags = new java.util.ArrayList<>();
        t.trimDays(GenerationTelemetry.DAY_WINDOW_MAX);
    }

    public void save(GenerationTelemetry t) throws IOException {
        lock.lock();
        try {
            Files.createDirectories(dir);
            Path file = fileFor(t.generation);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(t));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            lock.unlock();
        }
    }

    /** Best-effort quarantine of unreadable bytes; never masks the original failure. */
    private void quarantine(Path original, String why) {
        try {
            long stamp = System.currentTimeMillis();
            Path kept = original.resolveSibling(original.getFileName() + ".corrupt-" + stamp);
            Files.move(original, kept, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            // Quarantine failure must not throw past the CORRUPT verdict.
        }
    }
}
