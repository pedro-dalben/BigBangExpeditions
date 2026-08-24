package com.bigbangcraft.expeditions.loot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LootPolicyTest {

    @Test
    void embeddedPolicyLoadsAndClassifies() {
        LootPolicy p = LootPolicy.loadEmbedded();
        assertEquals(LootPolicy.ItemClass.PROGRESSION_ITEM, p.classify("deceasedcraft:research_paper_1"));
        assertEquals(LootPolicy.ItemClass.PROGRESSION_ITEM, p.classify("deceasedcraft:research_paper_4"));
        assertEquals(LootPolicy.ItemClass.UNIQUE_ITEM, p.classify("deceasedcraft:research_paper_5"));
        assertEquals(LootPolicy.ItemClass.UNIQUE_ITEM, p.classify("deceasedcraft:x_factor"));
        assertEquals(LootPolicy.ItemClass.PROGRESSION_ITEM, p.classify("deceasedcraft:formula_x"));
        assertEquals(LootPolicy.ItemClass.PROGRESSION_ITEM, p.classify("minecraft:golden_apple"));
        assertEquals(LootPolicy.ItemClass.RARE_LOOT, p.classifyTable("lostcities:chests/lostcitychest"));
    }

    @Test
    void unknownItemFailsClosed() {
        LootPolicy p = LootPolicy.loadEmbedded();
        assertEquals(LootPolicy.ItemClass.UNKNOWN, p.classify("somerandommod:mystery_item"));
        assertEquals(LootPolicy.ItemClass.UNKNOWN, p.classify(null));
        assertEquals(LootPolicy.ItemClass.UNKNOWN, p.classify(""));
    }

    @Test
    void blockingClassesIncludeUnknown() {
        for (LootPolicy.ItemClass c : new LootPolicy.ItemClass[]{
                LootPolicy.ItemClass.PROGRESSION_ITEM,
                LootPolicy.ItemClass.UNIQUE_ITEM,
                LootPolicy.ItemClass.UNKNOWN}) {
            assertTrue(c.blocksResetDuplication(), c + " must block reset duplication");
        }
        assertFalse(LootPolicy.ItemClass.REGULAR_LOOT.blocksResetDuplication());
        assertFalse(LootPolicy.ItemClass.RARE_LOOT.blocksResetDuplication());
    }

    @Test
    void missingFileFailsClosed(@TempDir Path tmp) {
        LootPolicy p = LootPolicy.load(tmp.resolve("does-not-exist.json"));
        // everything UNKNOWN — including previously known items
        assertEquals(LootPolicy.ItemClass.UNKNOWN, p.classify("deceasedcraft:research_paper_1"));
    }

    @Test
    void corruptFileFailsClosed(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("bad.json");
        Files.writeString(f, "{ not json !!!");
        LootPolicy p = LootPolicy.load(f);
        assertEquals(LootPolicy.ItemClass.UNKNOWN, p.classify("deceasedcraft:x_factor"));
    }

    @Test
    void invalidClassNameRejectedAtLoad(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("bad-class.json");
        Files.writeString(f, "{\"items\": {\"a:b\": {\"clazz\": \"NOT_A_CLASS\"}}}");
        LootPolicy p = LootPolicy.load(f);
        // load failed -> fallback -> UNKNOWN
        assertEquals(LootPolicy.ItemClass.UNKNOWN, p.classify("a:b"));
    }

    @Test
    void paper5AndXFactorAreUniquePerAuditEvidence() {
        // Audit evidence (2026-08-24 full pack sweep): no loot table / recipe /
        // structure source exists for these items anywhere in the pack.
        LootPolicy p = LootPolicy.loadEmbedded();
        assertEquals(LootPolicy.ItemClass.UNIQUE_ITEM, p.classify("deceasedcraft:research_paper_5"),
                "paper 5 has NO renewable source; must never be treated as regenerable");
        assertEquals(LootPolicy.ItemClass.UNIQUE_ITEM, p.classify("deceasedcraft:x_factor"),
                "x_factor is 'END OF CONTENT'; fixed stock only");
    }
}
