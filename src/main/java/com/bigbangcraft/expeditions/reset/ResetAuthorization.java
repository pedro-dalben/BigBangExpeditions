package com.bigbangcraft.expeditions.reset;

import com.bigbangcraft.expeditions.env.InstallFingerprint;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;

/**
 * Authorization artifact v2 — the ONLY path to destructive execution.
 *
 * An artifact is issued once, bound to ONE installation fingerprint and ONE
 * lifecycle generation, expires after a configurable TTL, is consumed exactly
 * once (ledger), and carries its own sha-256 over the canonical serialization.
 * Filesystem paths are never stored; targets are re-derived from coordinates
 * through PathConfinement at execution time.
 */
public final class ResetAuthorization {
    public static final int SCHEMA_VERSION = 2;
    public static final String SCOPE_SECTOR = "SECTOR";
    public static final String SCOPE_DIMENSION = "DIMENSION";

    public int schemaVersion = SCHEMA_VERSION;
    public String authId;                // uuid
    public String scope = SCOPE_DIMENSION;
    public String sectorId = "";         // SECTOR scope only
    public String dimension;             // must be bigbangexpeditions:expedition

    // sector scope bounds (ignored for DIMENSION scope)
    public int minChunkX, minChunkZ, maxChunkX, maxChunkZ;
    public List<String> expectedRegionFiles = new ArrayList<>();

    public String baselineId;
    public int generationAtIssue;
    public InstallFingerprint installFingerprint;
    public long createdAtEpochMs;
    public long expiresAtEpochMs;
    public String createdBy;
    public String authChecksum;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace('"', '\"');
    }

    /** Canonical JSON — mirrors nothing else; single source of truth for checksums. */
    public String toDeterministicJson() {
        StringBuilder files = new StringBuilder("[");
        List<String> sorted = new ArrayList<>(expectedRegionFiles);
        java.util.Collections.sort(sorted);
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) files.append(',');
            files.append('"').append(esc(sorted.get(i))).append('"');
        }
        files.append(']');
        return "{"
                + "\"schemaVersion\":" + schemaVersion
                + ",\"authId\":\"" + esc(authId) + '"'
                + ",\"scope\":\"" + esc(scope) + '"'
                + ",\"sectorId\":\"" + esc(sectorId) + '"'
                + ",\"dimension\":\"" + esc(dimension) + '"'
                + ",\"minChunkX\":" + minChunkX
                + ",\"minChunkZ\":" + minChunkZ
                + ",\"maxChunkX\":" + maxChunkX
                + ",\"maxChunkZ\":" + maxChunkZ
                + ",\"expectedRegionFiles\":" + files
                + ",\"baselineId\":\"" + esc(baselineId) + '"'
                + ",\"generationAtIssue\":" + generationAtIssue
                + ",\"installFingerprintSha256\":\"" + esc(installFingerprint == null ? "" : installFingerprint.sha256()) + '"'
                + ",\"createdAtEpochMs\":" + createdAtEpochMs
                + ",\"expiresAtEpochMs\":" + expiresAtEpochMs
                + ",\"createdBy\":\"" + esc(createdBy) + '"'
                + "}";
    }

    public void computeChecksum() {
        this.authChecksum = null;
        this.authChecksum = ResetAuthorization.sha256(toDeterministicJson());
    }

    public boolean checksumValid() {
        if (authChecksum == null) return false;
        String saved = authChecksum;
        try {
            return saved.equals(sha256(toDeterministicJson()));
        } finally {
            this.authChecksum = saved;
        }
    }

    /** Null when valid, else machine-readable refusal code. */
    public String validateStructure(long nowEpochMs) {
        if (schemaVersion != SCHEMA_VERSION) return "SCHEMA_UNSUPPORTED";
        if (authId == null || !authId.matches("[0-9a-fA-F\\-]{36}")) return "MALFORMED_AUTH_ID";
        if (!SCOPE_SECTOR.equals(scope) && !SCOPE_DIMENSION.equals(scope)) return "UNKNOWN_SCOPE";
        if (!"bigbangexpeditions:expedition".equals(dimension)) return "DIMENSION_NOT_ALLOWED";
        if (installFingerprint == null || installFingerprint.sha256().isBlank()) return "FINGERPRINT_MISSING";
        if (createdAtEpochMs <= 0 || expiresAtEpochMs <= createdAtEpochMs) return "LIFETIME_INVALID";
        if (nowEpochMs > expiresAtEpochMs) return "AUTH_EXPIRED";
        if (generationAtIssue < 0) return "GENERATION_INVALID";
        if (SCOPE_SECTOR.equals(scope)) {
            if (sectorId == null || sectorId.isBlank()) return "SECTOR_ID_MISSING";
            if (expectedRegionFiles.isEmpty()) return "NO_TARGETS";
        }
        return null;
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

    public static ResetAuthorization fromJson(String json) {
        ResetAuthorization a = GSON.fromJson(json, ResetAuthorization.class);
        if (a != null && a.expectedRegionFiles == null) a.expectedRegionFiles = new ArrayList<>();
        return a;
    }

    /** Sorted view for deterministic comparisons. */
    public List<String> sortedTargets() {
        List<String> out = new ArrayList<>(expectedRegionFiles);
        java.util.Collections.sort(out);
        return out;
    }
}
