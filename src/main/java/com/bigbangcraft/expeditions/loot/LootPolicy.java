package com.bigbangcraft.expeditions.loot;

import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Machine-readable loot classification (Goal 02 Phases 11-12).
 *
 * Backed by config/bigbangexpeditions/loot-policy.json. Fail-closed:
 * any item not present in the map classifies as UNKNOWN, and UNKNOWN is
 * treated as reset-blocking everywhere.
 */
public final class LootPolicy {
    private static final Logger LOG = LogManager.getLogger("BigBangExpeditions/LootPolicy");
    private static final Gson GSON = new Gson();

    public enum ItemClass {
        REGULAR_LOOT, RARE_LOOT, PROGRESSION_ITEM, UNIQUE_ITEM, UNKNOWN;

        public boolean blocksResetDuplication() {
            return this == PROGRESSION_ITEM || this == UNIQUE_ITEM || this == UNKNOWN;
        }
    }

    private Map<String, Entry> items = Collections.emptyMap();
    @com.google.gson.annotations.SerializedName("loot_tables")
    private Map<String, Entry> lootTables = Collections.emptyMap();
    private Set<String> classes = Collections.emptySet();
    private String strategy = "unknown";

    public static final class Entry {
        @com.google.gson.annotations.SerializedName("class")
        String clazz;
        String note = "";
        java.util.List<String> sources = Collections.emptyList();
    }

    // ---------- loading ----------

    public static LootPolicy load(Path file) {
        try {
            LootPolicy p = GSON.fromJson(Files.readString(file), LootPolicy.class);
            if (p == null || p.items == null) throw new IOException("empty policy file: " + file);
            for (Map.Entry<String, Entry> e : p.items.entrySet()) {
                if (e.getValue() == null || e.getValue().clazz == null) {
                    throw new IOException("item without class in policy: " + e.getKey());
                }
                try {
                    ItemClass.valueOf(e.getValue().clazz.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException iae) {
                    throw new IOException("unknown item class '" + e.getValue().clazz + "' for " + e.getKey());
                }
            }
            return p;
        } catch (Exception e) {
            LOG.error("Loot policy load failed — every item will classify UNKNOWN (fail-closed): {}", e.toString());
            LootPolicy fallback = new LootPolicy();
            fallback.items = Collections.emptyMap();
            return fallback;
        }
    }

    /** Default embedded copy from mod resources (same fail-closed guarantees). */
    public static LootPolicy loadEmbedded() {
        try (var in = LootPolicy.class.getResourceAsStream("/config/bigbangexpeditions/loot-policy.json")) {
            if (in == null) throw new IOException("embedded loot-policy.json missing");
            LootPolicy p = GSON.fromJson(new String(in.readAllBytes()), LootPolicy.class);
            if (p == null) throw new IOException("embedded loot-policy.json empty");
            return p;
        } catch (Exception e) {
            LOG.error("Embedded loot policy unavailable — failing closed", e);
            return new LootPolicy();
        }
    }

    // ---------- queries ----------

    public ItemClass classify(String itemId) {
        return classifyFrom(items, itemId);
    }

    /** Classifies a loot-table id (e.g. lostcities:chests/lostcitychest). */
    public ItemClass classifyTable(String tableId) {
        return classifyFrom(lootTables, tableId);
    }

    private static ItemClass classifyFrom(Map<String, Entry> map, String id) {
        if (id == null) return ItemClass.UNKNOWN;
        Entry e = map.get(id);
        if (e == null || e.clazz == null) return ItemClass.UNKNOWN;
        try {
            return ItemClass.valueOf(e.clazz.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException x) {
            return ItemClass.UNKNOWN;
        }
    }

    public Optional<Entry> entry(String itemId) {
        return Optional.ofNullable(items.get(itemId));
    }

    public boolean isKnown(String itemId) {
        return classify(itemId) != ItemClass.UNKNOWN;
    }
}
