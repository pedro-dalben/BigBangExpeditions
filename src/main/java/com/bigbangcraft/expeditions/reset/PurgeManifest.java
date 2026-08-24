package com.bigbangcraft.expeditions.reset;

import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pure player-additions delta between baseline census and live scan (Goal 04).
 *
 * Under whole-dimension regeneration with temporary-territory gameplay
 * (building policy A), extra block entities are EXPECTED — they are the camp a
 * survivor built. Destruction is still never accidental: the exact delta must
 * be quantified and explicitly acknowledged by the operator before an
 * authorization artifact can exist. The manifest hash binds that acknowledgment
 * to these exact counts; any world change invalidates it.
 */
public final class PurgeManifest {

    /** Sorted type -> extra count (only types exceeding baseline). */
    private final TreeMap<String, Integer> extras;
    private final String sha256;

    private PurgeManifest(TreeMap<String, Integer> extras, String sha256) {
        this.extras = extras;
        this.sha256 = sha256;
    }

    public static PurgeManifest of(Map<String, Integer> baselineByType,
                                   Map<String, Integer> liveByType) {
        TreeMap<String, Integer> extras = new TreeMap<>();
        if (liveByType != null) {
            for (Map.Entry<String, Integer> e : liveByType.entrySet()) {
                int base = baselineByType.getOrDefault(e.getKey(), 0);
                int live = e.getValue() == null ? 0 : e.getValue();
                if (live > base) extras.put(e.getKey(), live - base);
            }
        }
        StringBuilder canonical = new StringBuilder();
        extras.forEach((k, v) -> canonical.append(k).append('=').append(v).append(';'));
        return new PurgeManifest(extras, sha256Hex(canonical.toString()));
    }

    public Map<String, Integer> extras() {
        return new TreeMap<>(extras);
    }

    public int totalExtra() {
        return extras.values().stream().mapToInt(Integer::intValue).sum();
    }

    public boolean isEmpty() {
        return extras.isEmpty();
    }

    /** Binds an operator acknowledgment to these exact counts. */
    public String hash() {
        return sha256;
    }

    /** Compact human-readable summary for refusals/audit lines. */
    public String summarize(int maxTypes) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (Map.Entry<String, Integer> e : extras.entrySet()) {
            if (i++ >= maxTypes) {
                sb.append(" +").append(extras.size() - maxTypes).append(" more types");
                break;
            }
            if (i > 1) sb.append(", ");
            sb.append(shortName(e.getKey())).append("(+").append(e.getValue()).append(')');
        }
        if (sb.length() == 0) sb.append("none");
        return sb.toString();
    }

    private static String shortName(String type) {
        int idx = type.indexOf(':');
        return idx >= 0 ? type.substring(idx + 1) : type;
    }

    static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("sha-256 unavailable", e);
        }
    }
}
