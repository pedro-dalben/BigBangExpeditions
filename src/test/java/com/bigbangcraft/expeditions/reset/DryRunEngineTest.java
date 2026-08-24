package com.bigbangcraft.expeditions.reset;

import com.bigbangcraft.expeditions.env.EnvironmentProfile;
import com.bigbangcraft.expeditions.env.InstallFingerprint;
import com.bigbangcraft.expeditions.sector.SectorRecord;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class DryRunEngineTest {

    private AuthorizationService.IssueInputs inputs(EnvironmentProfile env) {
        AuthorizationService.IssueInputs in = new AuthorizationService.IssueInputs();
        in.env = env;
        SectorRecord s = new SectorRecord("prod", "bigbangexpeditions:expedition",
                128, 128, 159, 159);
        s.status = com.bigbangcraft.expeditions.sector.SectorState.LOCKED;
        s.lastBaselineId = "baseline-1";
        in.sector = s;
        in.live = cleanLive();
        in.lootPolicy = com.bigbangcraft.expeditions.loot.LootPolicy.loadEmbedded();
        in.savedDataClassification = Map.of("scoreboard.dat", "PLAYER_PROGRESS");
        in.currentFingerprint = fingerprint("");
        in.qualificationFingerprint = fingerprint("");
        in.lifecycleGeneration = 2;
        return in;
    }

    private InstallFingerprint fingerprint(String marker) {
        InstallFingerprint f = new InstallFingerprint();
        f.bbeVersion = "1.0.0";
        f.minecraftVersion = "1.20.1";
        f.forgeVersion = "47.4.0";
        f.dimensionId = "bigbangexpeditions:expedition";
        f.lostCitiesProfile = "deceasedcraft_onlycities";
        f.lostCitiesProfileSha256 = ("aa" + marker + "ab".repeat(64)).substring(0, 64);
        f.worldSeedHash = "seed";
        return f;
    }

    private com.bigbangcraft.expeditions.safety.SectorLiveState cleanLive() {
        return new com.bigbangcraft.expeditions.safety.SectorLiveState() {
            @Override public int playersInside() { return 0; }
            @Override public int claimedChunks() { return 0; }
            @Override public int forceloadedChunks() { return 0; }
            @Override public Map<String, Integer> blockEntitiesByType() { return Map.of(); }
            @Override public boolean scanIncomplete() { return false; }
        };
    }

    private DryRunEngine.DiskProbe disk(long avail, long need) {
        return new DryRunEngine.DiskProbe() {
            @Override public long availableBytes() { return avail; }
            @Override public long usableDimensionBytes() { return need / 2 - DryRunEngine.MIN_HEADROOM_BYTES; }
        };
    }

    @Test
    void cleanProductionPipelineWouldReset() {
        var r = DryRunEngine.run(inputs(EnvironmentProfile.PRODUCTION),
                disk(10L * 1024 * 1024 * 1024, 100L * 1024 * 1024), true);
        assertEquals(DryRunEngine.Verdict.WOULD_RESET, r.verdict,
                () -> r.steps.stream().map(s -> s.name + ":" + s.status + " " + s.detail)
                        .reduce((a, b) -> a + "\n" + b).orElse(""));
        assertNotNull(r.wouldIssueArtifact);
        assertTrue(r.steps.stream().anyMatch(s -> s.name.equals("EXECUTION")
                && s.detail.contains("WOULD DELETE")));
    }

    @Test
    void playersInsideRefusesWithReason() {
        var in = inputs(EnvironmentProfile.PRODUCTION);
        in.live = liveWithPlayers(3);
        var r = DryRunEngine.run(in, disk(Long.MAX_VALUE / 4, 100), true);
        assertEquals(DryRunEngine.Verdict.RESET_WOULD_BE_REFUSED, r.verdict);
        assertTrue(r.steps.stream().anyMatch(s -> s.name.equals("PREFLIGHT")
                && s.status == DryRunEngine.StepStatus.FAIL && s.detail.contains("PLAYERS_INSIDE")));
        assertTrue(r.steps.stream().anyMatch(s -> s.name.equals("EXECUTION") && s.status == DryRunEngine.StepStatus.FAIL));
    }

    @Test
    void driftRefusalBlocksDryRun() {
        var in = inputs(EnvironmentProfile.PRODUCTION);
        in.currentFingerprint.worldSeedHash = "different-seed";
        var r = DryRunEngine.run(in, disk(Long.MAX_VALUE / 4, 100), true);
        assertEquals(DryRunEngine.Verdict.RESET_WOULD_BE_REFUSED, r.verdict);
        assertTrue(r.steps.stream().anyMatch(s -> s.name.equals("DRIFT") && s.status == DryRunEngine.StepStatus.FAIL));
    }

    @Test
    void heldLockRefuses() {
        var r = DryRunEngine.run(inputs(EnvironmentProfile.PRODUCTION),
                disk(Long.MAX_VALUE / 4, 100), false);
        assertEquals(DryRunEngine.Verdict.RESET_WOULD_BE_REFUSED, r.verdict);
        assertTrue(r.steps.stream().anyMatch(s -> s.name.equals("RESET_LOCK") && s.status == DryRunEngine.StepStatus.FAIL));
    }

    @Test
    void insufficientDiskRefuses() {
        var r = DryRunEngine.run(inputs(EnvironmentProfile.PRODUCTION),
                disk(1024, Long.MAX_VALUE / 2), true);
        assertEquals(DryRunEngine.Verdict.RESET_WOULD_BE_REFUSED, r.verdict);
        assertTrue(r.steps.stream().anyMatch(s -> s.name.equals("BACKUP_SPACE") && s.status == DryRunEngine.StepStatus.FAIL));
    }

    @Test
    void wrongLifecycleStateRefuses() {
        var in = inputs(EnvironmentProfile.PRODUCTION);
        in.sector.status = com.bigbangcraft.expeditions.sector.SectorState.OPEN;
        var r = DryRunEngine.run(in, disk(Long.MAX_VALUE / 4, 100), true);
        assertEquals(DryRunEngine.Verdict.RESET_WOULD_BE_REFUSED, r.verdict);
        assertTrue(r.steps.stream().anyMatch(s -> s.name.equals("LIFECYCLE") && s.status == DryRunEngine.StepStatus.FAIL));
    }

    @Test
    void dryRunInStagingStillRunsPipelineButWarns() {
        var in = inputs(EnvironmentProfile.STAGING);
        in.qualificationFingerprint = null; // staging tolerates
        var r = DryRunEngine.run(in, disk(Long.MAX_VALUE / 4, 100), true);
        assertTrue(r.steps.stream().anyMatch(s -> s.name.equals("ENVIRONMENT")
                && s.status == DryRunEngine.StepStatus.WARN));
        assertTrue(r.wouldIssueArtifact != null || r.verdict == DryRunEngine.Verdict.WOULD_RESET);
    }

    @Test
    void reportNeverContainsDeletionConfirmationWhenRefused() {
        var in = inputs(EnvironmentProfile.PRODUCTION);
        in.live = liveWithPlayers(1);
        var r = DryRunEngine.run(in, disk(Long.MAX_VALUE / 4, 100), true);
        assertEquals(DryRunEngine.Verdict.RESET_WOULD_BE_REFUSED, r.verdict);
        assertTrue(r.steps.stream().noneMatch(s -> s.name.equals("EXECUTION") && s.status == DryRunEngine.StepStatus.PASS));
    }

    private com.bigbangcraft.expeditions.safety.SectorLiveState liveWithPlayers(int n) {
        return new com.bigbangcraft.expeditions.safety.SectorLiveState() {
            @Override public int playersInside() { return n; }
            @Override public int claimedChunks() { return 0; }
            @Override public int forceloadedChunks() { return 0; }
            @Override public Map<String, Integer> blockEntitiesByType() { return Map.of(); }
            @Override public boolean scanIncomplete() { return false; }
        };
    }
}
