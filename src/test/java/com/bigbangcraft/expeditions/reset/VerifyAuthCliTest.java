package com.bigbangcraft.expeditions.reset;

import com.bigbangcraft.expeditions.env.InstallFingerprint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class VerifyAuthCliTest {

    @TempDir
    Path tmp;

    private Path bbeRoot() {
        return tmp.resolve("server/bigbangexpeditions");
    }

    private String issueAndRecord(long now, boolean ledgerIssued) throws Exception {
        var in = TestIssues.inputs(now);
        ResetAuthorization a = AuthorizationService.issue(in).artifact;
        Path dir = bbeRoot().resolve("authorizations");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(a.authId + ".json"), a.toJson());
        if (ledgerIssued) {
            new AuthorizationLedger(bbeRoot().resolve("authorization-ledger.json"))
                    .recordIssued(a.authId, a.generationAtIssue, "prod", "admin", now);
        }
        return a.authId;
    }

    @Test
    void validArtifactAuthorizes() throws Exception {
        long now = System.currentTimeMillis();
        String id = issueAndRecord(now, true);
        var r = VerifyAuthCli.verify(bbeRoot(), id, now + 1000, null,
                bbeRoot().resolve("authorization-ledger.json"));
        assertTrue(r.ok(), r.message());
    }

    @Test
    void missingLedgerEntryRefuses() throws Exception {
        long now = System.currentTimeMillis();
        String id = issueAndRecord(now, false); // artifact written, ledger never recorded
        var r = VerifyAuthCli.verify(bbeRoot(), id, now + 1000, null,
                bbeRoot().resolve("authorization-ledger.json"));
        assertFalse(r.ok());
        assertEquals("LEDGER_UNKNOWN", r.message());
    }

    @Test
    void consumedArtifactRefuses() throws Exception {
        long now = System.currentTimeMillis();
        String id = issueAndRecord(now, true);
        new AuthorizationLedger(bbeRoot().resolve("authorization-ledger.json")).consume(id, "executor", now + 1);
        var r = VerifyAuthCli.verify(bbeRoot(), id, now + 2000, null,
                bbeRoot().resolve("authorization-ledger.json"));
        assertFalse(r.ok());
        assertEquals("LEDGER_CONSUMED", r.message());
    }

    @Test
    void fingerprintMismatchRefuses() throws Exception {
        long now = System.currentTimeMillis();
        String id = issueAndRecord(now, true);

        // current export differs (seed changed)
        InstallFingerprint drifted = TestIssues.fingerprint("");
        drifted.worldSeedHash = "changed";
        Path fpFile = tmp.resolve("current-fingerprint.json");
        Files.writeString(fpFile, drifted.toJson());

        var r = VerifyAuthCli.verify(bbeRoot(), id, now + 1000, fpFile,
                bbeRoot().resolve("authorization-ledger.json"));
        assertFalse(r.ok());
        assertEquals("FINGERPRINT_MISMATCH", r.message());
    }

    @Test
    void matchingFingerprintPasses() throws Exception {
        long now = System.currentTimeMillis();
        String id = issueAndRecord(now, true);
        Path fpFile = tmp.resolve("current-fingerprint.json");
        Files.writeString(fpFile, TestIssues.fingerprint("").toJson());

        var r = VerifyAuthCli.verify(bbeRoot(), id, now + 1000, fpFile,
                bbeRoot().resolve("authorization-ledger.json"));
        assertTrue(r.ok(), r.message());
    }

    @Test
    void malformedIdsRefuseWithoutIO() {
        for (String bad : new String[]{"../etc/passwd", "", null, "short"}) {
            var r = VerifyAuthCli.verify(bbeRoot(), bad, 1, null, bbeRoot().resolve("l.json"));
            assertFalse(r.ok());
        }
    }

    @Test
    void tamperedArtifactRefusesStructureOrChecksum() throws Exception {
        long now = System.currentTimeMillis();
        String id = issueAndRecord(now, true);
        Path file = bbeRoot().resolve("authorizations").resolve(id + ".json");
        ResetAuthorization a = ResetAuthorization.fromJson(Files.readString(file));
        a.expiresAtEpochMs += 100_000_000L; // extend validity without re-signing
        Files.writeString(file, a.toJson());

        var r = VerifyAuthCli.verify(bbeRoot(), id, now + 1000, null,
                bbeRoot().resolve("authorization-ledger.json"));
        assertFalse(r.ok());
        assertEquals("CHECKSUM_INVALID", r.message());
    }

    /** Shared fixture builder. */
    static final class TestIssues {
        static AuthorizationService.IssueInputs inputs(long now) {
            AuthorizationService.IssueInputs in = new AuthorizationService.IssueInputs();
            in.env = com.bigbangcraft.expeditions.env.EnvironmentProfile.PRODUCTION;
            com.bigbangcraft.expeditions.sector.SectorRecord s =
                    new com.bigbangcraft.expeditions.sector.SectorRecord("prod",
                            "bigbangexpeditions:expedition", 128, 128, 159, 159);
            s.status = com.bigbangcraft.expeditions.sector.SectorState.LOCKED;
            s.lastBaselineId = "baseline-1";
            in.sector = s;
            in.live = cleanLive();
            in.lootPolicy = com.bigbangcraft.expeditions.loot.LootPolicy.loadEmbedded();
            in.savedDataClassification = java.util.Map.of("scoreboard.dat", "PLAYER_PROGRESS");
            in.currentFingerprint = fingerprint("");
            in.qualificationFingerprint = fingerprint("");
            in.lifecycleGeneration = 4;
            in.nowEpochMs = now;
            return in;
        }

        static InstallFingerprint fingerprint(String marker) {
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

        private static com.bigbangcraft.expeditions.safety.SectorLiveState cleanLive() {
            return new com.bigbangcraft.expeditions.safety.SectorLiveState() {
                @Override public int playersInside() { return 0; }
                @Override public int claimedChunks() { return 0; }
                @Override public int forceloadedChunks() { return 0; }
                @Override public java.util.Map<String, Integer> blockEntitiesByType() { return java.util.Map.of(); }
                @Override public boolean scanIncomplete() { return false; }
            };
        }
    }
}
