package com.bigbangcraft.expeditions.env;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * Identifies the server installation an authorization artifact belongs to.
 *
 * Deliberately tracks a CRITICAL subset of the environment (not all mods):
 * worldgen/persistence/safety-critical components whose change invalidates
 * prior qualification evidence. Everything else is covered by the drift
 * policy's WARN tier rather than by hashing the whole modpack.
 *
 * Serialization is deterministic (sorted maps, fixed field order) so the
 * sha-256 is stable across processes and languages.
 */
public final class InstallFingerprint {
    public String bbeVersion;
    public String minecraftVersion;
    public String forgeVersion;
    /** critical mod id -> version (sorted) */
    public Map<String, String> modVersions = new TreeMap<>();
    public String dimensionId;
    public String lostCitiesProfile;
    /** sha-256 of the resolved LC profile file */
    public String lostCitiesProfileSha256;
    public String worldSeedHash;
    /** config file label -> sha-256 (sorted): loot-policy.json etc. */
    public Map<String, String> configSha256 = new TreeMap<>();

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace('"', '\"');
    }

    private static String mapJson(Map<String, String> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : new TreeMap<>(m).entrySet()) {
            if (!first) sb.append(',');
            sb.append('"').append(esc(e.getKey())).append("\":\"").append(esc(e.getValue())).append('"');
            first = false;
        }
        return sb.append('}').toString();
    }

    /** Canonical deterministic JSON (no timestamps — fingerprints must be stable). */
    public String toDeterministicJson() {
        return "{"
                + "\"bbeVersion\":\"" + esc(bbeVersion) + '"'
                + ",\"minecraftVersion\":\"" + esc(minecraftVersion) + '"'
                + ",\"forgeVersion\":\"" + esc(forgeVersion) + '"'
                + ",\"modVersions\":" + mapJson(modVersions)
                + ",\"dimensionId\":\"" + esc(dimensionId) + '"'
                + ",\"lostCitiesProfile\":\"" + esc(lostCitiesProfile) + '"'
                + ",\"lostCitiesProfileSha256\":\"" + esc(lostCitiesProfileSha256) + '"'
                + ",\"worldSeedHash\":\"" + esc(worldSeedHash) + '"'
                + ",\"configSha256\":" + mapJson(configSha256)
                + '}';
    }

    public String sha256() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(toDeterministicJson().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Stable short token used as the production acknowledgment value. */
    public String shortHash() {
        return sha256().substring(0, 12);
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public static InstallFingerprint fromJson(String json) {
        InstallFingerprint f = GSON.fromJson(json, InstallFingerprint.class);
        if (f == null) throw new IllegalArgumentException("null fingerprint json");
        // normalize maps so re-serialization matches canonical form
        f.modVersions = f.modVersions == null ? new TreeMap<>() : new TreeMap<>(f.modVersions);
        f.configSha256 = f.configSha256 == null ? new TreeMap<>() : new TreeMap<>(f.configSha256);
        return f;
    }

    public InstallFingerprint copy() {
        return fromJson(toJson());
    }
}
