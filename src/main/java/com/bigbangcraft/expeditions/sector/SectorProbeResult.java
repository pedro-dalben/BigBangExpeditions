package com.bigbangcraft.expeditions.sector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Result of read-only probe. Never mutates world.
 */
public final class SectorProbeResult {
    public enum Verdict { PASS, WARN, REFUSED }

    private final SectorBounds bounds;
    private Verdict verdict = Verdict.PASS;
    private final List<String> warnings = new ArrayList<>();
    private final List<String> reasons = new ArrayList<>();

    // metrics
    public int chunkCount;
    public int playersInside;
    public List<String> playerNames = new ArrayList<>();
    public int loadedChunks;
    public int blockEntityCount;
    public int containerCount;
    public int spawnerCount;
    public int entityCount;
    public int createCount;
    public int immersiveCount;
    public int refinedStorageCount;
    public int securityCraftCount;
    public Map<String, Integer> blockEntitiesByType;
    public Map<String, Integer> blockEntitiesByNamespace;
    public List<String> unknownNamespaces = new ArrayList<>();

    // OPAC
    public String opacStatus; // e.g. "no claims", "intersects: 3 chunks", "REFUSED: dimension unavailable"
    public int opacIntersectingChunks;
    public int opacForceloads;
    public boolean opacAvailable;

    public SectorProbeResult(SectorBounds bounds) {
        this.bounds = bounds;
        this.chunkCount = bounds.chunkCount();
    }

    public SectorBounds bounds() { return bounds; }
    public Verdict verdict() { return verdict; }
    public List<String> warnings() { return warnings; }
    public List<String> reasons() { return reasons; }

    public void warn(String msg) {
        warnings.add(msg);
        if (verdict == Verdict.PASS) verdict = Verdict.WARN;
    }

    public void refuse(String reason) {
        reasons.add(reason);
        verdict = Verdict.REFUSED;
    }

    public void addWarningIf(boolean condition, String msg) {
        if (condition) warn(msg);
    }
}
