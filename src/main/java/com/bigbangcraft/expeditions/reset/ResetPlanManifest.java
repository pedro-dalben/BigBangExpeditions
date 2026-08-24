package com.bigbangcraft.expeditions.reset;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;

/**
 * Immutable-by-convention reset plan manifest (Goal 02 Phase 14).
 *
 * Deterministic JSON -> sha-256 checksum. Filesystem paths are NOT stored —
 * only region coordinates; the offline executor re-derives paths through
 * PathConfinement at run time.
 */
public final class ResetPlanManifest {
    public String planId;              // uuid
    public String sectorId;
    public String dimension;           // must be bigbangexpeditions:expedition
    public int minChunkX, minChunkZ, maxChunkX, maxChunkZ;
    public List<String> expectedRegionFiles = new ArrayList<>(); // r.X.Z.mca names
    public String baselineId;
    public int sectorResetCountAtPlanTime;
    public String profileFingerprint;  // LC profile sha-256
    public String worldSeedHash;
    public long createdAtEpochMs;
    public String createdBy;
    /** sha-256 of the canonical serialization WITHOUT the checksum field. */
    public String manifestChecksum;

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    /** Canonical form used for checksums: sorted keys via TreeMap round-trip. */
    public String toDeterministicJson() {
        ResetPlanManifest copy = GSON.fromJson(GSON.toJsonTree(this), ResetPlanManifest.class);
        copy.manifestChecksum = null;
        // Gson preserves declaration order; enforce determinism by explicit field order
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"planId\":\"").append(copy.planId).append('"');
        sb.append(",\"sectorId\":\"").append(copy.sectorId).append('"');
        sb.append(",\"dimension\":\"").append(copy.dimension).append('"');
        sb.append(",\"minChunkX\":").append(copy.minChunkX);
        sb.append(",\"minChunkZ\":").append(copy.minChunkZ);
        sb.append(",\"maxChunkX\":").append(copy.maxChunkX);
        sb.append(",\"maxChunkZ\":").append(copy.maxChunkZ);
        sb.append(",\"expectedRegionFiles\":").append(GSON.toJson(new TreeMap<>(listToMap(copy.expectedRegionFiles))));
        sb.append(",\"baselineId\":\"").append(copy.baselineId).append('"');
        sb.append(",\"sectorResetCountAtPlanTime\":").append(copy.sectorResetCountAtPlanTime);
        sb.append(",\"profileFingerprint\":\"").append(copy.profileFingerprint).append('"');
        sb.append(",\"worldSeedHash\":\"").append(copy.worldSeedHash).append('"');
        sb.append(",\"createdAtEpochMs\":").append(copy.createdAtEpochMs);
        sb.append(",\"createdBy\":\"").append(copy.createdBy == null ? "" : copy.createdBy).append('"');
        sb.append('}');
        return sb.toString();
    }

    private static java.util.Map<Integer, String> listToMap(List<String> l) {
        java.util.Map<Integer, String> m = new TreeMap<>();
        if (l != null) for (int i = 0; i < l.size(); i++) m.put(i, l.get(i));
        return m;
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

    public static ResetPlanManifest fromJson(String json) {
        return GSON.fromJson(json, ResetPlanManifest.class);
    }

    public String toJson() {
        return GSON.toJson(this);
    }
}
