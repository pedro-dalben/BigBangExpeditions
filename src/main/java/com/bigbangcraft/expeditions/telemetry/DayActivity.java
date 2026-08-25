package com.bigbangcraft.expeditions.telemetry;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * One UTC-day activity bucket. Bounded by design: unique-player sets saturate
 * at {@code uniqueCap} and further distinct players only bump a counter, so a
 * hostile population cannot grow the file without bound.
 */
public final class DayActivity {
    public static final int UNIQUE_CAP = 512;

    /** yyyy-MM-dd (UTC) bucket key; also the JSON map key. */
    public String day;

    public long entries;
    public long chunkDiscoveries;
    public long structureDiscoveries;
    public long containerOpens;
    public long deaths;
    public long playerMobKills;

    public Set<String> uniquePlayers = new HashSet<>();
    public long uniqueOverflow;

    public DayActivity() {}

    public DayActivity(String day) {
        this.day = day;
    }

    public void recordPlayer(UUID id) {
        if (id == null) return;
        if (uniquePlayers.size() < UNIQUE_CAP) {
            uniquePlayers.add(id.toString());
        } else if (!uniquePlayers.contains(id.toString())) {
            uniqueOverflow = Saturation.inc(uniqueOverflow);
        }
    }

    public int uniqueCount() {
        return uniquePlayers.size();
    }
}
