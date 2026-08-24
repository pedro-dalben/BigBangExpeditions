package com.bigbangcraft.expeditions.safety;

import com.bigbangcraft.expeditions.loot.LootPolicy;
import com.bigbangcraft.expeditions.reset.PathConfinement;
import com.bigbangcraft.expeditions.sector.SectorRecord;
import com.bigbangcraft.expeditions.sector.SectorState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PreflightEngineTest {

    private SectorRecord openSector() {
        SectorRecord s = new SectorRecord("b04", "bigbangexpeditions:expedition",
                128, 128, 159, 159);
        s.status = SectorState.LOCKED;
        s.lastBaselineId = "baseline-1";
        return s;
    }

    private ResetPreflightEngine.ResetPlanInput baseInput() {
        ResetPreflightEngine.ResetPlanInput in = new ResetPreflightEngine.ResetPlanInput();
        in.guard = new ProductionGuard(false, false);
        in.sector = openSector();
        in.requiredState = SectorState.LOCKED;
        in.live = new SectorLiveState() {
            @Override public int playersInside() { return 0; }
            @Override public int claimedChunks() { return 0; }
            @Override public int forceloadedChunks() { return 0; }
            @Override public Map<String, Integer> blockEntitiesByType() { return Map.of(); }
            @Override public boolean scanIncomplete() { return false; }
        };
        in.baselineByType = Map.of();
        in.lootPolicy = LootPolicy.loadEmbedded();
        in.savedDataClassification = Map.of(
                "random_sequences.dat", "POSITION_SCOPED",
                "scoreboard.dat", "PLAYER_PROGRESS");
        return in;
    }

    @Test
    void cleanSectorPasses() {
        ResetPreflightResult r = new ResetPreflightEngine().validate(baseInput());
        assertTrue(r.passed(), () -> "unexpected refusals: " + r.refusalReasons());
    }

    @Test
    void playerInsideRefuses() {
        ResetPreflightEngine.ResetPlanInput in = baseInput();
        in.live = stubLive(2, 0, 0, Map.of(), false);
        ResetPreflightResult r = new ResetPreflightEngine().validate(in);
        assertFalse(r.passed());
        assertTrue(r.refusalReasons().stream().anyMatch(s -> s.contains("PLAYERS_INSIDE")));
    }

    @Test
    void claimRefusesAndForceloadWarns() {
        ResetPreflightEngine.ResetPlanInput in = baseInput();
        in.live = stubLive(0, 7, 3, Map.of(), false);
        ResetPreflightResult r = new ResetPreflightEngine().validate(in);
        assertFalse(r.passed());
        assertTrue(r.refusalReasons().stream().anyMatch(s -> s.contains("CLAIMS_INTERSECT")));
        assertTrue(r.issues().stream().anyMatch(i -> i.code.equals("FORCELOADS") && i.severity == ValidationIssue.Severity.WARN));
    }

    @Test
    void playerAdditionsVsBaselineRefuse() {
        ResetPreflightEngine.ResetPlanInput in = baseInput();
        in.baselineByType = Map.of("minecraft:chest", 10, "lootr:lootr_chest", 4);
        in.live = stubLive(0, 0, 0,
                new HashMap<>(Map.of("minecraft:chest", 12, "create:mechanical_drill", 1)), false);
        ResetPreflightResult r = new ResetPreflightEngine().validate(in);
        assertFalse(r.passed());
        String reason = String.join("\n", r.refusalReasons());
        assertTrue(reason.contains("PLAYER_ADDITIONS"));
        assertTrue(reason.contains("minecraft:chest(+2)"), reason);
        assertTrue(reason.contains("create:mechanical_drill(+1)"));
    }

    @Test
    void consumedWorldgenContentIsNotAnAddition() {
        // players looted 3 of 10 chests -> fewer than baseline is fine under Policy A
        ResetPreflightEngine.ResetPlanInput in = baseInput();
        in.baselineByType = Map.of("minecraft:chest", 10);
        in.live = stubLive(0, 0, 0, Map.of("minecraft:chest", 7), false);
        ResetPreflightResult r = new ResetPreflightEngine().validate(in);
        assertTrue(r.passed(), r.refusalReasons().toString());
    }

    @Test
    void wrongStateRefuses() {
        ResetPreflightEngine.ResetPlanInput in = baseInput();
        in.sector.status = SectorState.OPEN;
        ResetPreflightResult r = new ResetPreflightEngine().validate(in);
        assertFalse(r.passed());
        assertTrue(r.refusalReasons().stream().anyMatch(s -> s.contains("SECTOR_STATE")));
    }

    @Test
    void foreignDimensionRefuses() {
        ResetPreflightEngine.ResetPlanInput in = baseInput();
        in.sector.dimension = "minecraft:overworld";
        ResetPreflightResult r = new ResetPreflightEngine().validate(in);
        assertFalse(r.passed());
        assertTrue(r.refusalReasons().stream().anyMatch(s -> s.contains("DIMENSION_NOT_ALLOWED")));
    }

    @Test
    void productionGuardRefuses() {
        ResetPreflightEngine.ResetPlanInput in = baseInput();
        in.guard = new ProductionGuard(true, true); // even if someone enables it
        ResetPreflightResult r = new ResetPreflightEngine().validate(in);
        assertFalse(r.passed());
        assertTrue(r.refusalReasons().stream().anyMatch(s ->
                s.contains("PRODUCTION_ENVIRONMENT")));
    }

    @Test
    void unknownSavedDataBlocksQualification() {
        ResetPreflightEngine.ResetPlanInput in = baseInput();
        in.savedDataClassification = new HashMap<>(Map.of("mystery.dat", "UNKNOWN"));
        ResetPreflightResult r = new ResetPreflightEngine().validate(in);
        assertFalse(r.passed());
        assertTrue(r.refusalReasons().stream().anyMatch(s -> s.contains("SAVEDDATA_UNKNOWN")));
    }

    @Test
    void missingBaselineRefuses() {
        ResetPreflightEngine.ResetPlanInput in = baseInput();
        in.sector.lastBaselineId = "";
        ResetPreflightResult r = new ResetPreflightEngine().validate(in);
        assertFalse(r.passed());
        assertTrue(r.refusalReasons().stream().anyMatch(s -> s.contains("BASELINE_MISSING")));
    }

    @Test
    void incompleteScanFailsClosed() {
        ResetPreflightEngine.ResetPlanInput in = baseInput();
        in.live = stubLive(0, 0, 0, Map.of(), true);
        ResetPreflightResult r = new ResetPreflightEngine().validate(in);
        assertFalse(r.passed());
        assertTrue(r.refusalReasons().stream().anyMatch(s -> s.contains("SCAN_INCOMPLETE")));
    }

    @Test
    void unalignedBoundsRefuse() {
        ResetPreflightEngine.ResetPlanInput in = baseInput();
        in.sector.minChunkX = 5;
        ResetPreflightResult r = new ResetPreflightEngine().validate(in);
        assertFalse(r.passed());
        assertTrue(r.refusalReasons().stream().anyMatch(s -> s.contains("BOUNDS_UNALIGNED")));
    }

    private SectorLiveState stubLive(int players, int claims, int forceloads,
                                     Map<String, Integer> beTypes, boolean incomplete) {
        return new SectorLiveState() {
            @Override public int playersInside() { return players; }
            @Override public int claimedChunks() { return claims; }
            @Override public int forceloadedChunks() { return forceloads; }
            @Override public Map<String, Integer> blockEntitiesByType() { return beTypes; }
            @Override public boolean scanIncomplete() { return incomplete; }
        };
    }
}
