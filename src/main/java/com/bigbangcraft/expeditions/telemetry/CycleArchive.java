package com.bigbangcraft.expeditions.telemetry;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure model for the bounded archive of completed expedition cycles (Goal 05
 * requirement 46). Keeps the newest {@link #CAP} summaries; older entries are
 * dropped, never allowed to grow without bound across years of operation.
 * Persistence lives in {@link CycleArchiveStore}.
 */
public final class CycleArchive {
    public static final int CAP = 50;
    public static final int SCHEMA_VERSION = 1;

    public int schemaVersion = SCHEMA_VERSION;
    public List<CycleSummary> summaries = new ArrayList<>();

    /**
     * @return parsed model, or null when the payload is corrupt or from an
     * unsupported future schema (caller decides fail-safe handling).
     */
    public static CycleArchive parseOrNull(String json) {
        try {
            CycleArchive loaded = CycleArchiveStore.GSON.fromJson(json, CycleArchive.class);
            if (loaded == null || loaded.schemaVersion > SCHEMA_VERSION) {
                return null; // unsupported future schema: caller starts clean rather than guess
            }
            if (loaded.summaries == null) loaded.summaries = new ArrayList<>();
            trim(loaded);
            loaded.schemaVersion = SCHEMA_VERSION;
            return loaded;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public void append(CycleSummary s) {
        summaries.removeIf(x -> x.generation == s.generation); // idempotent per generation
        summaries.add(s);
        trim(this);
    }

    public CycleSummary byGeneration(int generation) {
        for (CycleSummary s : summaries) if (s.generation == generation) return s;
        return null;
    }

    public void update(CycleSummary s) {
        append(s);
    }

    static void trim(CycleArchive a) {
        while (a.summaries.size() > CAP) a.summaries.remove(0);
    }
}
