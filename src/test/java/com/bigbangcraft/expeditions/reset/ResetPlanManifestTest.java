package com.bigbangcraft.expeditions.reset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResetPlanManifestTest {

    private ResetPlanManifest sample() {
        ResetPlanManifest m = new ResetPlanManifest();
        m.planId = "11111111-2222-3333-4444-555555555555";
        m.sectorId = "b04";
        m.dimension = "bigbangexpeditions:expedition";
        m.minChunkX = 128; m.minChunkZ = 128;
        m.maxChunkX = 159; m.maxChunkZ = 159;
        m.expectedRegionFiles = List.of("r.4.4.mca");
        m.baselineId = "baseline-1";
        m.sectorResetCountAtPlanTime = 0;
        m.profileFingerprint = "aa".repeat(32);
        m.worldSeedHash = "c87cf428";
        m.createdAtEpochMs = 1_787_555_093_490L;
        m.createdBy = "console";
        return m;
    }

    @Test
    void checksumDeterministicAcrossInstancesAndOrder() {
        ResetPlanManifest a = sample();
        a.computeChecksum();

        // rebuild with fields set in a different order
        ResetPlanManifest b = new ResetPlanManifest();
        b.worldSeedHash = "c87cf428";
        b.createdBy = "console";
        b.createdAtEpochMs = 1_787_555_093_490L;
        b.profileFingerprint = "aa".repeat(32);
        b.sectorResetCountAtPlanTime = 0;
        b.baselineId = "baseline-1";
        b.expectedRegionFiles = List.of("r.4.4.mca");
        b.maxChunkX = 159; b.maxChunkZ = 159;
        b.minChunkX = 128; b.minChunkZ = 128;
        b.dimension = "bigbangexpeditions:expedition";
        b.sectorId = "b04";
        b.planId = "11111111-2222-3333-4444-555555555555";
        b.computeChecksum();

        assertEquals(a.manifestChecksum, b.manifestChecksum);
    }

    @Test
    void jsonRoundTripKeepsChecksumValid() {
        ResetPlanManifest m = sample();
        m.computeChecksum();
        ResetPlanManifest back = ResetPlanManifest.fromJson(m.toJson());
        assertTrue(back.checksumValid());
    }

    @Test
    void tamperedFieldDetected() {
        ResetPlanManifest m = sample();
        m.computeChecksum();
        ResetPlanManifest tampered = ResetPlanManifest.fromJson(m.toJson());
        tampered.maxChunkX = 999; // attacker widens the deletion area
        assertFalse(tampered.checksumValid());
    }

    @Test
    void tamperedRegionFileListDetected() {
        ResetPlanManifest m = sample();
        m.computeChecksum();
        ResetPlanManifest evil = ResetPlanManifest.fromJson(m.toJson());
        evil.expectedRegionFiles = List.of("r.0.0.mca", "../../../home/pedro/.ssh/id_rsa");
        assertFalse(evil.checksumValid());
    }

    @Test
    void missingChecksumInvalid() {
        ResetPlanManifest m = sample();
        assertFalse(m.checksumValid());
    }

    @Test
    void regionFileNameStrict() {
        assertTrue(PathConfinement.isRegionFileName("r.4.-3.mca"));
        assertTrue(PathConfinement.isRegionFileName("r.0.0.mca"));
        assertFalse(PathConfinement.isRegionFileName("../../etc/passwd"));
        assertFalse(PathConfinement.isRegionFileName("r..mca"));
        assertFalse(PathConfinement.isRegionFileName(null));
    }

    @Test
    void confinementRejectsTraversal(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("world");
        Files.createDirectories(root);

        assertNotNull(PathConfinement.confine(root, "dimensions", "bigbangexpeditions", "expedition"));
        assertNull(PathConfinement.confine(root, ".."));
        assertNull(PathConfinement.confine(root, "a", "..", "..", "escape"));
        assertNull(PathConfinement.confine(root, "sub/dir/traversal"));
        assertNull(PathConfinement.confine(root, "C:\\evil"));
    }

    @Test
    void expeditionDimDirDerivation(@TempDir Path tmp) {
        Path dir = PathConfinement.expeditionDimensionDir(tmp);
        assertEquals(tmp.resolve("dimensions").resolve("bigbangexpeditions").resolve("expedition"), dir);
    }
}
