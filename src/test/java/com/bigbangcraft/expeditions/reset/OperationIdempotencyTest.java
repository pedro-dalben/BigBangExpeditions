package com.bigbangcraft.expeditions.reset;

import com.bigbangcraft.expeditions.env.EnvironmentProfile;
import com.bigbangcraft.expeditions.env.InstallFingerprint;
import com.bigbangcraft.expeditions.sector.SectorRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Idempotency guarantees for repeated operational commands:
 * prepare twice, execute the same plan twice, resume recovery twice.
 */
class OperationIdempotencyTest {

    @TempDir
    Path tmp;

    private SectorRecord lockedSector() {
        SectorRecord s = new SectorRecord("prod", "bigbangexpeditions:expedition",
                128, 128, 159, 159);
        s.status = com.bigbangcraft.expeditions.sector.SectorState.LOCKED;
        s.lastBaselineId = "baseline-1";
        return s;
    }

    private AuthorizationService.IssueInputs productionInputs(long now) {
        AuthorizationService.IssueInputs in = new AuthorizationService.IssueInputs();
        in.env = EnvironmentProfile.PRODUCTION;
        in.sector = lockedSector();
        in.live = cleanLive();
        in.lootPolicy = com.bigbangcraft.expeditions.loot.LootPolicy.loadEmbedded();
        in.savedDataClassification = java.util.Map.of("scoreboard.dat", "PLAYER_PROGRESS");
        in.currentFingerprint = fingerprint("");
        in.qualificationFingerprint = fingerprint("");
        in.lifecycleGeneration = 1;
        in.nowEpochMs = now;
        in.actor = "admin";
        return in;
    }

    private InstallFingerprint fingerprint(String marker) {
        InstallFingerprint f = new InstallFingerprint();
        f.bbeVersion = "1.0.0";
        f.minecraftVersion = "1.20.1";
        f.forgeVersion = "47.4.0";
        f.dimensionId = "bigbangexpeditions:expedition";
        f.lostCitiesProfile = "p";
        f.lostCitiesProfileSha256 = ("aa" + marker + "ab".repeat(64)).substring(0, 64);
        f.worldSeedHash = "seed";
        return f;
    }

    private com.bigbangcraft.expeditions.safety.SectorLiveState cleanLive() {
        return new com.bigbangcraft.expeditions.safety.SectorLiveState() {
            @Override public int playersInside() { return 0; }
            @Override public int claimedChunks() { return 0; }
            @Override public int forceloadedChunks() { return 0; }
            @Override public java.util.Map<String, Integer> blockEntitiesByType() { return java.util.Map.of(); }
            @Override public boolean scanIncomplete() { return false; }
        };
    }

    @Test
    void reissueSupersedesPriorAuthorization() throws Exception {
        AuthorizationLedger ledger = new AuthorizationLedger(tmp.resolve("ledger.json"));

        ResetAuthorization first = AuthorizationService.issue(productionInputs(1000L)).artifact;
        assertTrue(ledger.recordIssued(first.authId, 1, "prod", "admin", 1000L).isEmpty());

        // operator re-runs prepare (timeout / uncertain client response)
        ResetAuthorization second = AuthorizationService.issue(productionInputs(2000L)).artifact;
        assertTrue(ledger.recordIssued(second.authId, 1, "prod", "admin", 2000L).isEmpty());
        assertEquals(AuthorizationService.supersedePriorIssued(ledger, "prod", second.authId, "admin", 2000L),
                java.util.Optional.empty());

        // exactly one ISSUED remains, and it is the newest
        var issued = ledger.issuedFor("prod");
        assertEquals(java.util.List.of(second.authId), issued);

        // the superseded artifact can no longer be consumed by any executor
        assertTrue(ledger.consume(first.authId, "executor", 3000L).isPresent());
    }

    @Test
    void executingSamePlanTwiceIsRefusedByLedger() throws Exception {
        String id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeee99";
        AuthorizationLedger ledger = new AuthorizationLedger(tmp.resolve("l2.json"));
        ledger.recordIssued(id, 1, "prod", "admin", 1L);

        assertTrue(ledger.consume(id, "executor", 2L).isEmpty());          // first execution OK
        var replay = ledger.consume(id, "executor", 3L);                    // replay attempt
        assertTrue(replay.isPresent(), "replay of consumed authorization must refuse");
    }

    @Test
    void lockMakesDuplicatePrepareHarmless() throws Exception {
        ResetLock lock = new ResetLock(tmp.resolve("lock.json"));
        assertTrue(lock.acquire("prepare", "issue auth", 1000L, 60_000L).isEmpty());
        // duplicate prepare while first still running: refused, nothing corrupted
        assertTrue(lock.acquire("prepare-dup", "issue auth", 1100L, 60_000L).isPresent());
        assertTrue(lock.release("prepare", 1200L).isEmpty());
    }
}
