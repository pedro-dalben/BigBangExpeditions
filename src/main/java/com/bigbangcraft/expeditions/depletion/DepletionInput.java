package com.bigbangcraft.expeditions.depletion;

import com.bigbangcraft.expeditions.telemetry.TelemetrySnapshot;

/**
 * Everything the engine needs, pre-resolved — the engine itself performs NO IO
 * and reads NO clocks (determinism requirement 11/53): {@code nowEpochMs} is a
 * parameter.
 */
public final class DepletionInput {
    public final TelemetrySnapshot snapshot;
    /** Total chunks in the expedition area; <=0 means census unknown. */
    public final long totalExpeditionChunks;
    /**
     * Operator-pinned structure census (structureId -> expected placements);
     * empty means no census (structure component UNKNOWN unless signal absent).
     */
    public final long totalStructureCensus;
    public final long nowEpochMs;
    public final int playersInsideNow;

    public DepletionInput(TelemetrySnapshot snapshot, long totalExpeditionChunks,
                          long totalStructureCensus, long nowEpochMs, int playersInsideNow) {
        this.snapshot = snapshot;
        this.totalExpeditionChunks = totalExpeditionChunks;
        this.totalStructureCensus = totalStructureCensus;
        this.nowEpochMs = nowEpochMs;
        this.playersInsideNow = playersInsideNow;
    }

    public static DepletionInput of(TelemetrySnapshot snapshot, long totalChunks,
                                    long structureCensus, long now) {
        return new DepletionInput(snapshot, totalChunks, structureCensus, now, 0);
    }
}
