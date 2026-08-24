package com.bigbangcraft.expeditions.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Verifies a backup directory against its {@link BackupManifest}:
 * manifest checksum, file presence, per-file sha-256 and sizes.
 * Any deviation fails verification — a partially-copied or tampered backup
 * can never pass as a rollback point.
 */
public final class BackupVerifier {

    public static final class Result {
        public final boolean ok;
        public final List<String> problems;

        private Result(boolean ok, List<String> problems) {
            this.ok = ok;
            this.problems = List.copyOf(problems);
        }

        static Result fail(String problem) {
            return fail(java.util.List.of(problem));
        }

        static Result fail(List<String> problems) {
            return new Result(false, problems);
        }
    }

    private BackupVerifier() {}

    public static String sha256File(Path p) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(Files.readAllBytes(p)));
        } catch (IOException io) {
            throw io;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    /**
     * @param backupDir directory containing backup-manifest.json and the files it lists
     */
    public static Result verify(Path backupDir) throws IOException {
        Path mf = backupDir.resolve("backup-manifest.json");
        if (!Files.isRegularFile(mf)) return Result.fail("manifest missing: " + mf);
        BackupManifest m = BackupManifest.fromJson(Files.readString(mf));
        if (m == null) return Result.fail("manifest unreadable");
        if (!m.checksumValid()) return Result.fail("manifest checksum invalid");
        if (m.formatVersion != BackupManifest.FORMAT_VERSION) return Result.fail("unsupported format " + m.formatVersion);
        if (m.files.isEmpty()) return Result.fail("backup claims zero files");

        List<String> problems = new ArrayList<>();
        long total = 0;
        for (BackupManifest.FileEntry f : m.files) {
            Path p = backupDir.resolve(f.path).normalize();
            if (!p.normalize().startsWith(backupDir.normalize())) {
                problems.add("path escapes backup dir: " + f.path);
                continue;
            }
            if (!Files.isRegularFile(p)) {
                problems.add("missing: " + f.path);
                continue;
            }
            long size = Files.size(p);
            if (size != f.bytes) {
                problems.add("size mismatch: " + f.path + " (" + size + " != " + f.bytes + ")");
                continue;
            }
            String hash = sha256File(p);
            if (!hash.equals(f.sha256)) {
                problems.add("hash mismatch: " + f.path);
                continue;
            }
            total += size;
        }
        if (!problems.isEmpty()) {
            return Result.fail(problems);
        }
        if (total != m.totalBytes) {
            return Result.fail("total bytes mismatch (" + total + " != " + m.totalBytes + ")");
        }
        return new Result(true, List.of());
    }
}
