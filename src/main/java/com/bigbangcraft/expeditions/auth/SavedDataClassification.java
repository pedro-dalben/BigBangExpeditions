package com.bigbangcraft.expeditions.auth;

import java.util.Map;

/**
 * Known SavedData owners from the Goal 00/02 inventory audit
 * (docs/investigations/saved-data-inventory.md). The preflight engine refuses
 * on any UNKNOWN classification; this map is the allow-list of understood files.
 */
public final class SavedDataClassification {
    private SavedDataClassification() {}

    public static final Map<String, String> KNOWN_OWNERS = Map.ofEntries(
            Map.entry("random_sequences.dat", "POSITION_SCOPED"),
            Map.entry("scoreboard.dat", "PLAYER_PROGRESS"),
            Map.entry("advancements", "PLAYER_PROGRESS"),
            Map.entry("playerdata", "PLAYER_PROGRESS"),
            Map.entry("stats", "PLAYER_PROGRESS"),
            Map.entry("raids.dat", "POSITION_SCOPED_OVERWORLD_ONLY"),
            Map.entry("map_data", "PLAYER_PROGRESS"),
            Map.entry("entities", "DIMENSION_LOCAL"),
            Map.entry("poi", "DIMENSION_LOCAL"),
            Map.entry("region", "DIMENSION_LOCAL"),
            Map.entry("level.dat", "MIXED_SEED_SCOPED"),
            Map.entry("lootr_stats", "PLAYER_PROGRESS"));
}
