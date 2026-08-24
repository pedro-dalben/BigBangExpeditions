package com.bigbangcraft.expeditions.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BackupManifestTest {

    @TempDir
    Path tmp;

    private BackupManifest manifest() {
        BackupManifest m = new BackupManifest();
        m.backupId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        m.authorizationSha256 = "aa".repeat(32);
        m.lifecycleGeneration = 4;
        m.bbeVersion = "1.0.0";
        m.minecraftVersion = "1.20.1";
        m.forgeVersion = "47.4.0";
        m.createdAtEpochMs = 123456L;
        BackupManifest.FileEntry f1 = new BackupManifest.FileEntry();
        f1.path = "region/r.0.0.mca";
        f1.sha256 = "bb".repeat(32);
        f1.bytes = 1024;
        BackupManifest.FileEntry f2 = new BackupManifest.FileEntry();
        f2.path = "backup-plan.json";
        f2.sha256 = "cc".repeat(32);
        f2.bytes = 10;
        m.files.add(f1);
        m.files.add(f2);
        m.totalBytes = 1034;
        m.computeChecksum();
        return m;
    }

    @Test
    void checksumValidAndTamperEvident() {
        BackupManifest m = manifest();
        assertTrue(m.checksumValid());
        m.totalBytes = 999;
        assertFalse(m.checksumValid());
    }

    @Test
    void fileOrderDoesNotAffectChecksum() {
        BackupManifest a = manifest();
        BackupManifest b = manifest();
        java.util.Collections.reverse(b.files);
        b.computeChecksum();
        assertEquals(a.manifestChecksum, b.manifestChecksum);
    }

    @Test
    void roundTripPreservesVerification() throws IOException {
        BackupManifest m = manifest();
        Path p = tmp.resolve("m.json");
        Files.writeString(p, m.toJson());
        assertTrue(BackupManifest.fromJson(Files.readString(p)).checksumValid());
    }
}
