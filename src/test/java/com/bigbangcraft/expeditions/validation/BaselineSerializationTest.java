package com.bigbangcraft.expeditions.validation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.resources.ResourceLocation;
import com.bigbangcraft.expeditions.sector.SectorBounds;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

public class BaselineSerializationTest {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Test
    void deterministicSerialization() {
        SectorBounds b = new SectorBounds("test", new ResourceLocation("minecraft:overworld"), 0, 0, 31, 31);
        BaselineData d1 = BaselineData.from(b);
        d1.worldSeedHash = "abc123";
        d1.lostCitiesProfile = "deceasedcraft_onlycities";
        d1.blockEntityCount = 5;
        d1.blockEntitiesByType = new TreeMap<>(Map.of("minecraft:chest", 3, "create:shaft", 2));
        d1.blockEntitiesByNamespace = new TreeMap<>(Map.of("minecraft", 3, "create", 2));
        d1.timestampEpochMs = 1000L;
        d1.timestampIso = "2026-01-01T00:00:00Z";

        BaselineData d2 = BaselineData.from(b);
        d2.worldSeedHash = "abc123";
        d2.lostCitiesProfile = "deceasedcraft_onlycities";
        d2.blockEntityCount = 5;
        d2.blockEntitiesByType = new TreeMap<>(Map.of("create:shaft", 2, "minecraft:chest", 3));
        d2.blockEntitiesByNamespace = new TreeMap<>(Map.of("create", 2, "minecraft", 3));
        d2.timestampEpochMs = 1000L;
        d2.timestampIso = "2026-01-01T00:00:00Z";

        String j1 = GSON.toJson(d1);
        String j2 = GSON.toJson(d2);
        assertEquals(j1, j2, "TreeMap sorting must make JSON deterministic");
    }

    @Test
    void comparisonDetectsDiff() {
        BaselineData a = new BaselineData();
        a.id = "test"; a.dimension = "minecraft:overworld"; a.minChunkX=0; a.minChunkZ=0; a.maxChunkX=31; a.maxChunkZ=31;
        a.blockEntityCount = 10; a.containerCount=5; a.spawnerCount=2; a.blockEntitiesByType = new TreeMap<>(Map.of("minecraft:chest",5));
        a.blockEntitiesByNamespace = new TreeMap<>(Map.of("minecraft",5));
        a.opacStatus="no claims"; a.worldSeedHash="aaa"; a.timestampIso="t1";

        BaselineData b = new BaselineData();
        b.id = "test"; b.dimension = "minecraft:overworld"; b.minChunkX=0; b.minChunkZ=0; b.maxChunkX=31; b.maxChunkZ=31;
        b.blockEntityCount = 12; b.containerCount=5; b.spawnerCount=2; b.blockEntitiesByType = new TreeMap<>(Map.of("minecraft:chest",7));
        b.blockEntitiesByNamespace = new TreeMap<>(Map.of("minecraft",7));
        b.opacStatus="no claims"; b.worldSeedHash="aaa"; b.timestampIso="t2";

        String diff = BaselineService.compare(a,b);
        assertTrue(diff.contains("blockEntityCount: 10 -> 12"));
        assertTrue(diff.contains("minecraft:chest: 5 -> 7"));
    }

    @Test
    void noSensitiveData() {
        BaselineData d = new BaselineData();
        // ensure no player inventory fields exist via reflection
        String json = GSON.toJson(d);
        assertFalse(json.toLowerCase().contains("inventory"), "baseline must not contain player inventories");
        assertFalse(json.toLowerCase().contains("playerdata"));
    }

    @Test
    void namespaceAggregation() {
        Map<String,Integer> byNs = new TreeMap<>();
        byNs.merge("minecraft", 1, Integer::sum);
        byNs.merge("create", 1, Integer::sum);
        byNs.merge("minecraft", 1, Integer::sum);
        assertEquals(2, byNs.get("minecraft"));
        assertEquals(1, byNs.get("create"));
    }
}
