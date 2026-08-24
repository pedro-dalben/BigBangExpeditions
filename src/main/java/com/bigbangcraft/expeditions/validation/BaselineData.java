package com.bigbangcraft.expeditions.validation;

import com.bigbangcraft.expeditions.sector.SectorBounds;

import java.time.Instant;
import java.util.Map;

/**
 * Deterministic JSON baseline. No player inventories.
 */
public final class BaselineData {
    public String version = "1";
    public String id;
    public String dimension;
    public int minChunkX, minChunkZ, maxChunkX, maxChunkZ;
    public long timestampEpochMs;
    public String timestampIso;
    public String worldSeedHash; // hex or "unavailable"
    public String lostCitiesProfile;
    public int chunkCount;
    public int blockEntityCount;
    public int containerCount;
    public int spawnerCount;
    public int entityCount;
    public int loadedChunks;
    public int playersInside;
    public Map<String, Integer> blockEntitiesByType;
    public Map<String, Integer> blockEntitiesByNamespace;
    public Map<String, Integer> entitiesByType;
    public String opacStatus;
    public int opacIntersecting;
    public int opacForceloads;
    public boolean opacAvailable;
    public java.util.List<String> warnings;
    public java.util.List<String> unknownNamespaces;

    public static BaselineData from(SectorBounds b) {
        BaselineData d = new BaselineData();
        d.id = b.id();
        d.dimension = b.dimension().toString();
        d.minChunkX = b.minChunkX();
        d.minChunkZ = b.minChunkZ();
        d.maxChunkX = b.maxChunkX();
        d.maxChunkZ = b.maxChunkZ();
        d.chunkCount = b.chunkCount();
        d.timestampEpochMs = Instant.now().toEpochMilli();
        d.timestampIso = Instant.now().toString();
        return d;
    }
}
