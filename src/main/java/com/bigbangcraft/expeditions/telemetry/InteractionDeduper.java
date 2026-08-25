package com.bigbangcraft.expeditions.telemetry;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Pure time-window deduplicator for repeatable interactions (Goal 05
 * requirement 39). Minecraft can deliver repeated events for one logical act
 * (right-click spam, menu reopen); a container opened repeatedly by the same
 * player inside {@code windowMs} counts ONCE.
 *
 * <p>Bounded: when tracked keys exceed {@code capacity} the oldest entries are
 * dropped first, so hostile spam grows memory O(capacity), never O(events).
 */
public final class InteractionDeduper {
    private final long windowMs;
    private final int capacity;
    /** composite key -> last accepted epoch ms */
    private final Map<String, Long> seen = new HashMap<>();

    public InteractionDeduper(long windowMs, int capacity) {
        this.windowMs = Math.max(1, windowMs);
        this.capacity = Math.max(16, capacity);
    }

    /**
     * @param key stable identity of the logical act (e.g. player + block pos)
     * @return true when this act should COUNT (first occurrence, or window elapsed)
     */
    public boolean tryAccept(String key, long nowEpochMs) {
        evict(nowEpochMs);
        Long last = seen.get(key);
        if (last != null && nowEpochMs - last < windowMs) {
            return false;
        }
        seen.put(key, nowEpochMs);
        return true;
    }

    private void evict(long nowEpochMs) {
        if (seen.size() < capacity) return;
        Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
        while (it.hasNext()) {
            if (nowEpochMs - it.next().getValue() >= windowMs) it.remove();
        }
        while (seen.size() >= capacity) { // pathological flood: shed oldest insertions
            String oldestKey = null;
            long oldestVal = Long.MAX_VALUE;
            for (Map.Entry<String, Long> e : seen.entrySet()) {
                if (e.getValue() < oldestVal) {
                    oldestVal = e.getValue();
                    oldestKey = e.getKey();
                }
            }
            if (oldestKey == null) break;
            seen.remove(oldestKey);
        }
    }

    public int trackedCount() {
        return seen.size();
    }

    public void clear() {
        seen.clear();
    }
}
