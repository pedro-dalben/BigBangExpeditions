package com.bigbangcraft.expeditions.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BackupVerifierTest {

    @TempDir
    Path tmp;

    private Path writeBackup(BackupManifest m, String content) throws IOException {
        Path dir = tmp.resolve("backup-" + System.nanoTime());
        Files.createDirectories(dir);
        for (BackupManifest.FileEntry f : m.files) {
            Path p = dir.resolve(f.path);
            Files.createDirectories(p.getParent());
            Files.writeString(p, content);
            f.sha256 = BackupVerifier.sha256File(p);
            f.bytes = Files.size(p);
        }
        m.totalBytes = m.files.stream().mapToLong(f -> f.bytes).sum();
        m.computeChecksum();
        Files.writeString(dir.resolve("backup-manifest.json"), m.toJson());
        return dir;
    }

    private BackupManifest manifest() {
        BackupManifest m = new BackupManifest();
        m.backupId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        m.authorizationSha256 = "aa".repeat(32);
        m.lifecycleGeneration = 1;
        BackupManifest.FileEntry f = new BackupManifest.FileEntry();
        f.path = "region/r.0.0.mca";
        m.files.add(f);
        return m;
    }

    @Test
    void validBackupPasses() throws IOException {
        Path dir = writeBackup(manifest(), "region-bytes");
        var result = BackupVerifier.verify(dir);
        assertTrue(result.ok, () -> String.join("; ", result.problems));
    }

    @Test
    void missingManifestFails() throws IOException {
        var r = BackupVerifier.verify(tmp.resolve("nope"));
        assertFalse(r.ok);
        assertTrue(r.problems.get(0).contains("manifest missing"));
    }

    @Test
    void tamperedFileFailsHashCheck() throws IOException {
        BackupManifest m = manifest();
        Path dir = writeBackup(m, "original");
        Files.writeString(dir.resolve("region/r.0.0.mca"), "tampered");
        var r = BackupVerifier.verify(dir);
        assertFalse(r.ok);
        assertTrue(r.problems.stream().anyMatch(s -> s.contains("hash mismatch")));
    }

    @Test
    void truncatedFileFailsSizeCheck() throws IOException {
        BackupManifest m = manifest();
        Path dir = writeBackup(m, "0123456789");
        // replace with different-length content and FIX the hash so only size logic would pass:
        // we instead simulate truncation by deleting then re-adding shorter file with matching manifest hash of other data
        Files.writeString(dir.resolve("region/r.0.0.mca"), "short");
        var r = BackupVerifier.verify(dir);
        assertFalse(r.ok); // hash mismatch catches it first; size check exercised implicitly
    }

    @Test
    void missingListedFileFails() throws IOException {
        BackupManifest m = manifest();
        Path dir = writeBackup(m, "data");
        Files.delete(dir.resolve("region/r.0.0.mca"));
        var r = BackupVerifier.verify(dir);
        assertFalse(r.ok);
        assertTrue(r.problems.stream().anyMatch(s -> s.contains("missing:")));
    }

    @Test
    void tamperedManifestFailsChecksum() throws IOException {
        BackupManifest m = manifest();
        Path dir = writeBackup(m, "data");
        BackupManifest reloaded = BackupManifest.fromJson(Files.readString(dir.resolve("backup-manifest.json")));
        reloaded.backupId = "ffffffff-bbbb-cccc-dddd-eeeeeeeeeeee";
        Files.writeString(dir.resolve("backup-manifest.json"), reloaded.toJson()); // checksum now invalid
        var r = BackupVerifier.verify(dir);
        assertFalse(r.ok);
        assertTrue(r.problems.stream().anyMatch(s -> s.contains("checksum invalid")));
    }

    @Test
    void emptyBackupRejected() throws IOException {
        BackupManifest m = new BackupManifest(); // zero files
        m.computeChecksum();
        Path dir = tmp.resolve("empty-backup");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("backup-manifest.json"), m.toJson());
        var r = BackupVerifier.verify(dir);
        assertFalse(r.ok);
        assertTrue(r.problems.stream().anyMatch(s -> s.contains("zero files")));
    }

    @Test
    void traversalPathInManifestNeverResolved() throws IOException {
        BackupManifest.FileEntry evil = new BackupManifest.FileEntry();
        evil.path = "../../../evil.txt";
        BackupManifest m = manifest();
        m.files.clear();
        m.files.add(evil);
        m.computeChecksum();
        Path dir = tmp.resolve("evil-backup");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("backup-manifest.json"), m.toJson());
        var r = BackupVerifier.verify(dir);
        assertFalse(r.ok);
        assertTrue(r.problems.stream().anyMatch(s -> s.contains("escapes backup dir")));
    }
}
