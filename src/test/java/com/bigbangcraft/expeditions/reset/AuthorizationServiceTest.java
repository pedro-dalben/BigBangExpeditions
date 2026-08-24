package com.bigbangcraft.expeditions.reset;

import com.bigbangcraft.expeditions.env.DriftPolicy;
import com.bigbangcraft.expeditions.env.EnvironmentProfile;
import com.bigbangcraft.expeditions.env.InstallFingerprint;
import com.bigbangcraft.expeditions.loot.LootPolicy;
import com.bigbangcraft.expeditions.sector.SectorRecord;
import com.bigbangcraft.expeditions.sector.SectorState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class AuthorizationServiceTest {

    private SectorRecord lockedSector() {
        SectorRecord s = new SectorRecord("prod", "bigbangexpeditions:expedition",
                128, 128, 159, 159);
        s.status = SectorState.LOCKED;
        s.lastBaselineId = "baseline-1";
        return s;
    }

    private InstallFingerprint fingerprint(String marker) {
        InstallFingerprint f = new InstallFingerprint();
        f.bbeVersion = "1.0.0";
        f.minecraftVersion = "1.20.1";
        f.forgeVersion = "47.4.0";
        f.modVersions = new TreeMap<>(Map.of(
                "lostcities", "1.20.1-1.2.1",
                "openpartiesandclaims", "0.25.8"));
        f.dimensionId = "bigbangexpeditions:expedition";
        f.lostCitiesProfile = "deceasedcraft_onlycities";
        f.lostCitiesProfileSha256 = ("aa" + marker + "ab".repeat(64)).substring(0, 64);
        f.worldSeedHash = "1a2b3c4d";
        return f;
    }

    private AuthorizationService.IssueInputs inputs(EnvironmentProfile env) {
        AuthorizationService.IssueInputs in = new AuthorizationService.IssueInputs();
        in.env = env;
        in.sector = lockedSector();
        in.live = cleanLive();
        in.lootPolicy = LootPolicy.loadEmbedded();
        in.savedDataClassification = Map.of("scoreboard.dat", "PLAYER_PROGRESS");
        in.currentFingerprint = fingerprint("");
        in.qualificationFingerprint = fingerprint("");
        in.lifecycleGeneration = 3;
        in.nowEpochMs = 1000000L;
        in.actor = "admin";
        return in;
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

    @Test
    void productionIssuesDimensionScopedArtifact() {
        var out = AuthorizationService.issue(inputs(EnvironmentProfile.PRODUCTION));
        assertTrue(out.ok(), () -> "refusals=" + out.refusals);
        assertEquals(ResetAuthorization.SCOPE_DIMENSION, out.artifact.scope);
        assertEquals(3, out.artifact.generationAtIssue);
        assertTrue(out.artifact.checksumValid());
        assertTrue(out.artifact.expiresAtEpochMs > out.artifact.createdAtEpochMs);
        // dimension scope: no region targets stored
        assertTrue(out.artifact.expectedRegionFiles.isEmpty());
    }

    @Test
    void sectorScopeDerivesRegionTargets() {
        var in = inputs(EnvironmentProfile.PRODUCTION);
        in.scope = ResetAuthorization.SCOPE_SECTOR;
        var out = AuthorizationService.issue(in);
        assertTrue(out.ok());
        List<String> files = out.artifact.sortedTargets();
        assertEquals(1, files.size()); // chunks 128..159 => single region (4,4)
        assertEquals("r.4.4.mca", files.get(0));
    }

    @Test
    void preflightRefusalsBlockIssuance() {
        var in = inputs(EnvironmentProfile.PRODUCTION);
        in.live = stubLive(2); // players inside
        var out = AuthorizationService.issue(in);
        assertFalse(out.ok());
        assertTrue(out.refusals.stream().anyMatch(s -> s.contains("PLAYERS_INSIDE")));
        assertNull(out.artifact);
    }

    @Test
    void missingLootPolicyBlocksIssuance() {
        var in = inputs(EnvironmentProfile.PRODUCTION);
        in.lootPolicy = null;
        var out = AuthorizationService.issue(in);
        assertFalse(out.ok());
        assertTrue(out.refusals.stream().anyMatch(s -> s.contains("LOOT_POLICY")));
    }

    @Test
    void wrongSectorStateBlocksIssuance() {
        var in = inputs(EnvironmentProfile.PRODUCTION);
        in.sector.status = SectorState.OPEN;
        var out = AuthorizationService.issue(in);
        assertFalse(out.ok());
        assertTrue(out.refusals.stream().anyMatch(s -> s.contains("SECTOR_STATE")));
    }

    @Test
    void driftRevalidationRefusesProduction() {
        var in = inputs(EnvironmentProfile.PRODUCTION);
        in.currentFingerprint = fingerprint("ff"); // LC profile changed
        var out = AuthorizationService.issue(in);
        assertFalse(out.ok());
        assertTrue(out.refusals.stream().anyMatch(s -> s.contains("DRIFT_REVALIDATE")));
        assertNull(out.artifact);
    }

    @Test
    void driftIdentityChangeRefusesEverywhere() {
        for (EnvironmentProfile env : EnvironmentProfile.values()) {
            var in = inputs(env);
            in.currentFingerprint.worldSeedHash = "99999999"; // seed change = identity change
            var out = AuthorizationService.issue(in);
            assertFalse(out.ok(), () -> env + " must refuse seed drift");
            assertTrue(out.refusals.stream().anyMatch(s -> s.contains("DRIFT_REFUSE")), env::toString);
        }
    }

    @Test
    void stagingToleratesMissingQualificationButProductionDoesNot() {
        var st = inputs(EnvironmentProfile.STAGING);
        st.qualificationFingerprint = null;
        var outSt = AuthorizationService.issue(st);
        assertTrue(outSt.ok());
        assertTrue(outSt.warnings.stream().anyMatch(w -> w.contains("qualification")));

        var pr = inputs(EnvironmentProfile.PRODUCTION);
        pr.qualificationFingerprint = null;
        var outPr = AuthorizationService.issue(pr);
        assertFalse(outPr.ok());
        assertTrue(outPr.refusals.stream().anyMatch(s -> s.contains("QUALIFICATION_MISSING")));
    }

    @Test
    void dryRunWarnsOnDimensionScopeOutsideProductionButStillIssues() {
        var in = inputs(EnvironmentProfile.PRODUCTION_DRY_RUN);
        var out = AuthorizationService.issue(in);
        assertTrue(out.ok());
        assertTrue(out.warnings.stream().anyMatch(w -> w.contains("PRODUCTION_DRY_RUN")
                || w.contains("execution will still refuse")));
    }

    @Test
    void artifactChecksumDetectsTampering() {
        ResetAuthorization a = AuthorizationService.issue(inputs(EnvironmentProfile.PRODUCTION)).artifact;
        assertTrue(a.checksumValid());

        String saved = a.authChecksum;
        a.generationAtIssue = 99; // tamper
        assertFalse(a.checksumValid());
        a.generationAtIssue = 3;
        a.authChecksum = saved;
        assertTrue(a.checksumValid());
    }

    @Test
    void structureValidationRejectsExpiryAndForeignDimension() {
        ResetAuthorization a = AuthorizationService.issue(inputs(EnvironmentProfile.PRODUCTION)).artifact;

        assertNull(a.validateStructure(a.createdAtEpochMs + 1));

        a.expiresAtEpochMs = a.createdAtEpochMs - 1;
        assertEquals("LIFETIME_INVALID", a.validateStructure(a.createdAtEpochMs));

        ResetAuthorization b = AuthorizationService.issue(inputs(EnvironmentProfile.PRODUCTION)).artifact;
        long now = System.currentTimeMillis();
        b.createdAtEpochMs = now - 10_000;
        b.expiresAtEpochMs = now - 5_000;
        assertEquals("AUTH_EXPIRED", b.validateStructure(now));

        // a fresh, unexpired lifetime validates cleanly
        ResetAuthorization c = AuthorizationService.issue(inputs(EnvironmentProfile.PRODUCTION)).artifact;
        assertNull(c.validateStructure(c.createdAtEpochMs + 1));

        ResetAuthorization d = AuthorizationService.issue(inputs(EnvironmentProfile.PRODUCTION)).artifact;
        d.dimension = "minecraft:overworld";
        assertEquals("DIMENSION_NOT_ALLOWED", d.validateStructure(d.createdAtEpochMs));
    }

    @Test
    void jsonRoundTripPreservesVerification() {
        ResetAuthorization a = AuthorizationService.issue(inputs(EnvironmentProfile.PRODUCTION)).artifact;
        ResetAuthorization b = ResetAuthorization.fromJson(a.toJson());
        assertTrue(b.checksumValid());
        assertEquals(a.authId, b.authId);
        assertEquals(a.installFingerprint.sha256(), b.installFingerprint.sha256());
    }

    @Test
    void fingerprintEmbedsCurrentInstallation() {
        ResetAuthorization a = AuthorizationService.issue(inputs(EnvironmentProfile.PRODUCTION)).artifact;
        assertEquals(fingerprint("").sha256(), a.installFingerprint.sha256());
    }

    @Test
    void unknownScopeRefusedByStructureCheck() {
        ResetAuthorization a = AuthorizationService.issue(inputs(EnvironmentProfile.PRODUCTION)).artifact;
        a.scope = "GALAXY";
        assertEquals("UNKNOWN_SCOPE", a.validateStructure(System.currentTimeMillis()));
    }

    @Test
    void driftReportAttachedToOutcome() {
        var in = inputs(EnvironmentProfile.PRODUCTION);
        in.currentFingerprint.modVersions = new TreeMap<>(in.currentFingerprint.modVersions);
        in.currentFingerprint.modVersions.put("openpartiesandclaims", "0.26.0"); // WARN tier
        var out = AuthorizationService.issue(in);
        assertTrue(out.ok()); // WARN does not block
        assertNotNull(out.drift);
        assertEquals(DriftPolicy.Verdict.WARN, out.drift.overall);
    }

    private com.bigbangcraft.expeditions.safety.SectorLiveState stubLive(int players) {
        return new com.bigbangcraft.expeditions.safety.SectorLiveState() {
            @Override public int playersInside() { return players; }
            @Override public int claimedChunks() { return 0; }
            @Override public int forceloadedChunks() { return 0; }
            @Override public Map<String, Integer> blockEntitiesByType() { return Map.of(); }
            @Override public boolean scanIncomplete() { return false; }
        };
    }
}
