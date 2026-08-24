package com.bigbangcraft.expeditions.env;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class InstallFingerprintTest {

    private InstallFingerprint sample() {
        InstallFingerprint f = new InstallFingerprint();
        f.bbeVersion = "1.1.0";
        f.minecraftVersion = "1.20.1";
        f.forgeVersion = "47.4.0";
        f.modVersions = new TreeMap<>(Map.of(
                "lostcities", "1.20.1-1.2.1",
                "openpartiesandclaims", "0.25.8"));
        f.dimensionId = "bigbangexpeditions:expedition";
        f.lostCitiesProfile = "deceasedcraft_onlycities";
        f.lostCitiesProfileSha256 = "aa".repeat(32);
        f.worldSeedHash = "1a2b3c4d";
        f.configSha256 = new TreeMap<>(Map.of("loot-policy.json", "bb".repeat(32)));
        return f;
    }

    @Test
    void deterministicJsonIsStable() {
        assertEquals(sample().toDeterministicJson(), sample().toDeterministicJson());
    }

    @Test
    void mapInsertionOrderDoesNotMatter() {
        InstallFingerprint a = sample();
        a.modVersions = new TreeMap<>();
        a.modVersions.put("openpartiesandclaims", "0.25.8");
        a.modVersions.put("lostcities", "1.20.1-1.2.1");
        assertEquals(sample().sha256(), a.sha256());
    }

    @Test
    void sha256ChangesOnAnyTrackedComponent() {
        String base = sample().sha256();

        InstallFingerprint f = sample(); f.bbeVersion = "1.1.1";
        assertNotEquals(base, f.sha256());

        f = sample(); f.minecraftVersion = "1.20.2";
        assertNotEquals(base, f.sha256());

        f = sample(); f.forgeVersion = "47.3.0";
        assertNotEquals(base, f.sha256());

        f = sample(); f.dimensionId = "other:dim";
        assertNotEquals(base, f.sha256());

        f = sample(); f.lostCitiesProfileSha256 = "cc".repeat(32);
        assertNotEquals(base, f.sha256());

        f = sample(); f.worldSeedHash = "ffff";
        assertNotEquals(base, f.sha256());

        f = sample();
        f.modVersions = new TreeMap<>(f.modVersions);
        f.modVersions.put("lostcities", "9.9.9");
        assertNotEquals(base, f.sha256());

        f = sample();
        f.configSha256 = new TreeMap<>(f.configSha256);
        f.configSha256.put("loot-policy.json", "dd".repeat(32));
        assertNotEquals(base, f.sha256());
    }

    @Test
    void shortHashIsTwelveHexChars() {
        String h = sample().shortHash();
        assertEquals(12, h.length());
        assertTrue(h.chars().allMatch(c -> Character.isDigit(c) || (c >= 'a' && c <= 'f')));
    }

    @Test
    void jsonRoundTripPreservesCanonicalForm() {
        InstallFingerprint f = sample();
        InstallFingerprint g = InstallFingerprint.fromJson(f.toJson());
        assertEquals(f.toDeterministicJson(), g.toDeterministicJson());
        assertEquals(f.sha256(), g.sha256());
    }

    @Test
    void nullMapsNormalizeToEmpty() {
        InstallFingerprint f = new InstallFingerprint();
        f.modVersions = null;
        f.configSha256 = null;
        InstallFingerprint g = InstallFingerprint.fromJson(f.toJson());
        assertTrue(g.modVersions.isEmpty());
        assertTrue(g.configSha256.isEmpty());
    }
}
