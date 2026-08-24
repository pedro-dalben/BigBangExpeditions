package com.bigbangcraft.expeditions.env;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class DriftPolicyTest {

    private InstallFingerprint base() {
        InstallFingerprint f = new InstallFingerprint();
        f.bbeVersion = "1.1.0";
        f.minecraftVersion = "1.20.1";
        f.forgeVersion = "47.4.0";
        f.modVersions = new TreeMap<>(Map.of(
                "lostcities", "1.20.1-1.2.1",
                "openpartiesandclaims", "0.25.8",
                "lootr", "1.20-0.7.3"));
        f.dimensionId = "bigbangexpeditions:expedition";
        f.lostCitiesProfile = "deceasedcraft_onlycities";
        f.lostCitiesProfileSha256 = "aa".repeat(32);
        f.worldSeedHash = "1a2b3c4d";
        f.configSha256 = new TreeMap<>(Map.of("loot-policy.json", "bb".repeat(32)));
        return f;
    }

    @Test
    void identicalFingerprintsAllow() {
        DriftPolicy.Report r = DriftPolicy.evaluate(base(), base());
        assertEquals(DriftPolicy.Verdict.ALLOW, r.overall);
        assertFalse(r.executionBlocked());
        assertTrue(r.entries.stream().allMatch(e -> e.verdict == DriftPolicy.Verdict.ALLOW));
    }

    @Test
    void minecraftVersionChangeRefuses() {
        InstallFingerprint c = base(); c.minecraftVersion = "1.20.4";
        assertEquals(DriftPolicy.Verdict.REFUSE, DriftPolicy.evaluate(base(), c).overall);
    }

    @Test
    void forgeVersionChangeRefuses() {
        InstallFingerprint c = base(); c.forgeVersion = "47.2.0";
        assertEquals(DriftPolicy.Verdict.REFUSE, DriftPolicy.evaluate(base(), c).overall);
    }

    @Test
    void dimensionChangeRefuses() {
        InstallFingerprint c = base(); c.dimensionId = "minecraft:overworld";
        assertEquals(DriftPolicy.Verdict.REFUSE, DriftPolicy.evaluate(base(), c).overall);
    }

    @Test
    void seedChangeRefuses() {
        InstallFingerprint c = base(); c.worldSeedHash = "9999";
        assertEquals(DriftPolicy.Verdict.REFUSE, DriftPolicy.evaluate(base(), c).overall);
    }

    @Test
    void lostCitiesUpdateRequiresRevalidation() {
        InstallFingerprint c = base();
        c.modVersions = new TreeMap<>(c.modVersions);
        c.modVersions.put("lostcities", "1.20.1-1.3.0");
        DriftPolicy.Report r = DriftPolicy.evaluate(base(), c);
        assertEquals(DriftPolicy.Verdict.REQUIRE_REVALIDATION, r.overall);
        assertTrue(r.executionBlocked());
    }

    @Test
    void bbeUpgradeRequiresRevalidation() {
        InstallFingerprint c = base(); c.bbeVersion = "2.0.0";
        assertEquals(DriftPolicy.Verdict.REQUIRE_REVALIDATION, DriftPolicy.evaluate(base(), c).overall);
    }

    @Test
    void lootPolicyContentChangeRequiresRevalidation() {
        InstallFingerprint c = base();
        c.configSha256 = new TreeMap<>(c.configSha256);
        c.configSha256.put("loot-policy.json", "cc".repeat(32));
        assertEquals(DriftPolicy.Verdict.REQUIRE_REVALIDATION, DriftPolicy.evaluate(base(), c).overall);
    }

    @Test
    void opacUpdateWarnsButDoesNotBlock() {
        InstallFingerprint c = base();
        c.modVersions = new TreeMap<>(c.modVersions);
        c.modVersions.put("openpartiesandclaims", "0.26.0");
        DriftPolicy.Report r = DriftPolicy.evaluate(base(), c);
        assertEquals(DriftPolicy.Verdict.WARN, r.overall);
        assertFalse(r.executionBlocked());
    }

    @Test
    void trackedModDisappearingRefuses() {
        InstallFingerprint c = base();
        c.modVersions = new TreeMap<>(c.modVersions);
        c.modVersions.remove("openpartiesandclaims");
        assertEquals(DriftPolicy.Verdict.REFUSE, DriftPolicy.evaluate(base(), c).overall);
    }

    @Test
    void trackedConfigMissingRefuses() {
        InstallFingerprint c = base();
        c.configSha256 = new TreeMap<>();
        assertEquals(DriftPolicy.Verdict.REFUSE, DriftPolicy.evaluate(base(), c).overall);
    }

    @Test
    void newTrackedModOrConfigWarns() {
        InstallFingerprint c = base();
        c.modVersions = new TreeMap<>(c.modVersions);
        c.modVersions.put("hordes", "1.0");
        c.configSha256 = new TreeMap<>(c.configSha256);
        c.configSha256.put("extra.json", "00".repeat(32));
        assertEquals(DriftPolicy.Verdict.WARN, DriftPolicy.evaluate(base(), c).overall);
    }

    @Test
    void missingValuesAnywhereRefuse() {
        InstallFingerprint b = base();
        b.lostCitiesProfileSha256 = "";
        assertEquals(DriftPolicy.Verdict.REFUSE, DriftPolicy.evaluate(b, base()).overall);

        InstallFingerprint c = base();
        c.worldSeedHash = null;
        assertEquals(DriftPolicy.Verdict.REFUSE, DriftPolicy.evaluate(base(), c).overall);
    }

    @Test
    void overallTakesWorstVerdict() {
        InstallFingerprint c = base();
        c.minecraftVersion = "1.21"; // REFUSE
        c.lostCitiesProfileSha256 = "ff".repeat(32); // REQUIRE_REVALIDATION
        assertEquals(DriftPolicy.Verdict.REFUSE, DriftPolicy.evaluate(base(), c).overall);
    }
}
