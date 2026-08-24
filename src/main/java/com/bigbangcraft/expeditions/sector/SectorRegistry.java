package com.bigbangcraft.expeditions.sector;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Persistent sector registry backed by an atomically-written JSON file.
 *
 * Storage location decision (Goal 02 Phase 8): the registry lives OUTSIDE the
 * world directory (&lt;server&gt;/bigbangexpeditions/sectors.json) so that even a
 * whole-expedition-dimension regeneration experiment cannot destroy the
 * lifecycle bookkeeping. Writes are temp-file + atomic move; readers never
 * mutate.
 *
 * All mutations go through transition validation — there is no API to set
 * status directly.
 */
public final class SectorRegistry {
    private static final Logger LOG = LogManager.getLogger("BigBangExpeditions/Registry");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path file;
    private Map<String, SectorRecord> sectors = new TreeMap<>();

    public SectorRegistry(Path file) {
        this.file = file;
        load();
    }

    // ---------- persistence ----------

    public synchronized void load() {
        sectors = new TreeMap<>();
        if (!Files.exists(file)) return;
        try {
            String json = Files.readString(file);
            RegistryFile rf = GSON.fromJson(json, RegistryFile.class);
            if (rf != null && rf.sectors != null) {
                for (SectorRecord r : rf.sectors) {
                    sectors.put(r.id, r);
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to read sector registry {} — starting empty (fail-closed callers must re-verify)", file, e);
            throw new IllegalStateException("sector registry unreadable: " + file, e);
        }
    }

    public synchronized void save() throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        RegistryFile rf = new RegistryFile();
        rf.sectors = new ArrayList<>(sectors.values());
        Files.writeString(tmp, GSON.toJson(rf));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static final class RegistryFile {
        List<SectorRecord> sectors = new ArrayList<>();
    }

    // ---------- queries ----------

    public synchronized Optional<SectorRecord> get(String id) {
        return Optional.ofNullable(sectors.get(id));
    }

    public synchronized List<SectorRecord> list() {
        return new ArrayList<>(sectors.values());
    }

    // ---------- mutation ----------

    /**
     * Creates a sector in OPEN state. Rejects duplicate ids and invalid bounds.
     * Returns error message on failure, empty on success.
     */
    public synchronized Optional<String> create(String id, String dimension,
                                                int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ,
                                                long nowEpochMs) {
        String idErr = checkId(id);
        if (idErr != null) return Optional.of(idErr);
        if (sectors.containsKey(id)) return Optional.of("sector already exists: " + id);

        SectorBounds probe = new SectorBounds(id,
                new net.minecraft.resources.ResourceLocation(dimension),
                minChunkX, minChunkZ, maxChunkX, maxChunkZ);
        String boundsErr = probe.validate();
        if (boundsErr != null) return Optional.of("invalid bounds: " + boundsErr);

        SectorRecord r = new SectorRecord(id, dimension, minChunkX, minChunkZ, maxChunkX, maxChunkZ);
        r.createdAtEpochMs = nowEpochMs;
        r.updatedAtEpochMs = nowEpochMs;
        r.lastOpenedAtEpochMs = nowEpochMs;
        sectors.put(id, r);
        return Optional.empty();
    }

    /**
     * Transitions status through the explicit state machine.
     * Returns error message on failure, empty on success.
     */
    public synchronized Optional<String> transition(String id, SectorState target, long nowEpochMs) {
        SectorRecord r = sectors.get(id);
        if (r == null) return Optional.of("unknown sector: " + id);
        Optional<String> err = SectorState.rejectTransition(r.status, target);
        if (err.isPresent()) return err;
        if (r.status == target) return Optional.empty(); // no-op
        applySideEffects(r, target, nowEpochMs);
        r.status = target;
        r.updatedAtEpochMs = nowEpochMs;
        return Optional.empty();
    }

    private static void applySideEffects(SectorRecord r, SectorState target, long now) {
        if (target == SectorState.OPEN) {
            r.lastOpenedAtEpochMs = now;
            if ("VALIDATING".equals(r.lastValidationResult)) { /* keep */ }
        }
        if (target == SectorState.OPEN || target == SectorState.FAILED) {
            // leaving RESETTING means a reset cycle completed one way or another
            if (r.status == SectorState.VALIDATING) {
                r.resetCount++;
                r.lastResetAtEpochMs = now;
            }
        }
        if (target == SectorState.RESET_PLANNED) {
            r.failureReason = "";
        }
    }

    public synchronized void setBaseline(String id, String baselineId, long nowEpochMs) {
        SectorRecord r = sectors.get(id);
        if (r == null) throw new IllegalArgumentException("unknown sector: " + id);
        r.lastBaselineId = baselineId;
        r.updatedAtEpochMs = nowEpochMs;
    }

    public synchronized void setValidationResult(String id, String result, long nowEpochMs) {
        SectorRecord r = sectors.get(id);
        if (r == null) throw new IllegalArgumentException("unknown sector: " + id);
        r.lastValidationResult = result;
        r.updatedAtEpochMs = nowEpochMs;
    }

    public synchronized void setFailureReason(String id, String reason, long nowEpochMs) {
        SectorRecord r = sectors.get(id);
        if (r == null) throw new IllegalArgumentException("unknown sector: " + id);
        r.failureReason = reason == null ? "" : reason;
        r.updatedAtEpochMs = nowEpochMs;
    }

    static String checkId(String id) {
        if (id == null || id.isBlank()) return "id blank";
        if (id.length() > 64) return "id too long";
        if (!id.matches("[a-z0-9_\\-]+")) return "id must be [a-z0-9_-]";
        return null;
    }
}
