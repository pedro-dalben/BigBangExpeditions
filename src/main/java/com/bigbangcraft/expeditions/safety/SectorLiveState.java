package com.bigbangcraft.expeditions.safety;

import java.util.Map;

/**
 * Live, server-side facts about a sector, abstracted so the preflight engine
 * is unit-testable without bootstrapping Minecraft.
 */
public interface SectorLiveState {
    int playersInside();

    /** chunk coords claimed inside bounds -> sample descriptions (max ~5). */
    int claimedChunks();

    int forceloadedChunks();

    /** block entity type id (namespace:path) -> count, from loaded chunks + baseline diff source. */
    Map<String, Integer> blockEntitiesByType();

    /** true when the live scan could not be completed (must fail closed). */
    boolean scanIncomplete();
}
