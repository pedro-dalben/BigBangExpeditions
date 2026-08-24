package com.bigbangcraft.expeditions.backup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Machine-verifiable description of a reset backup.
 *
 * The manifest travels WITH the backup and proves what it contains:
 * every file's sha-256 and size, plus the environment context the backup was
 * taken under (versions, authorization checksum, lifecycle generation).
 */
public final class BackupManifest {
    public static final int FORMAT_VERSION = 1;

    public int formatVersion = FORMAT_VERSION;
    public String backupId;              // == authorization id
    public String authorizationSha256;
    public int lifecycleGeneration;
    public String bbeVersion;
    public String minecraftVersion;
    public String forgeVersion;
    public long createdAtEpochMs;
    public List<FileEntry> files = new ArrayList<>();
    public long totalBytes;
    public String manifestChecksum;

    public static final class FileEntry {
        public String path;      // relative path inside the backup directory ('/' separators)
        public String sha256;
        public long bytes;
    }

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace('"', '\"');
    }

    public String toDeterministicJson() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"formatVersion\":").append(formatVersion);
        sb.append(",\"backupId\":\"").append(esc(backupId)).append('"');
        sb.append(",\"authorizationSha256\":\"").append(esc(authorizationSha256)).append('"');
        sb.append(",\"lifecycleGeneration\":").append(lifecycleGeneration);
        sb.append(",\"bbeVersion\":\"").append(esc(bbeVersion)).append('"');
        sb.append(",\"minecraftVersion\":\"").append(esc(minecraftVersion)).append('"');
        sb.append(",\"forgeVersion\":\"").append(esc(forgeVersion)).append('"');
        sb.append(",\"createdAtEpochMs\":").append(createdAtEpochMs);
        sb.append(",\"totalBytes\":").append(totalBytes);
        sb.append(",\"files\":[");
        List<FileEntry> sorted = new ArrayList<>(files);
        sorted.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.path, b.path));
        for (int i = 0; i < sorted.size(); i++) {
            FileEntry f = sorted.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"path\":\"").append(esc(f.path))
                    .append("\",\"sha256\":\"").append(esc(f.sha256))
                    .append("\",\"bytes\":").append(f.bytes).append('}');
        }
        return sb.append("]}").toString();
    }

    public void computeChecksum() {
        this.manifestChecksum = null;
        this.manifestChecksum = sha256(toDeterministicJson());
    }

    public boolean checksumValid() {
        if (manifestChecksum == null) return false;
        String saved = manifestChecksum;
        try {
            return saved.equals(sha256(toDeterministicJson()));
        } finally {
            this.manifestChecksum = saved;
        }
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public static BackupManifest fromJson(String json) {
        BackupManifest m = GSON.fromJson(json, BackupManifest.class);
        if (m != null && m.files == null) m.files = new ArrayList<>();
        return m;
    }
}
