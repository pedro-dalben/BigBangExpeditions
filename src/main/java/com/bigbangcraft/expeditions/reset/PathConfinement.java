package com.bigbangcraft.expeditions.reset;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Derives every filesystem path used by the reset pipeline from VALIDATED
 * inputs only (dimension id + region coordinates + fixed server layout).
 * Raw user paths never enter the destructive path.
 *
 * Layout (dedicated dimension):
 *   <world>/dimensions/<ns>/<path>/region/r.X.Z.mca
 *   <world>/dimensions/<ns>/<path>/entities/r.X.Z.mca
 *   <world>/dimensions/<ns>/<path>/poi/r.X.Z.mca
 */
public final class PathConfinement {
    public static final Pattern REGION_FILE = Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.mca$");

    private PathConfinement() {}

    /** Region-file base name for region coords; rejects overflow garbage. */
    public static String regionFileName(int rx, int rz) {
        return "r." + rx + "." + rz + ".mca";
    }

    /** True when name matches r.<int>.<int>.mca exactly. */
    public static boolean isRegionFileName(String name) {
        return name != null && REGION_FILE.matcher(name).matches();
    }

    /**
     * Confines candidateDir under root: no traversal, no absolute escape.
     * Returns null when the candidate is unsafe.
     */
    public static Path confine(Path root, String... segments) {
        if (root == null || !root.isAbsolute()) return null;
        Path cur = root;
        for (String s : segments) {
            if (s == null || s.isEmpty()) return null;
            if (s.contains("..") || s.contains("/") || s.contains("\\") || s.contains(":")) {
                if (!s.matches("[A-Za-z0-9_.\\-]+")) return null;
            }
            cur = cur.resolve(s);
            if (!cur.normalize().startsWith(root)) return null;
        }
        Path norm = cur.normalize();
        return norm.startsWith(root) ? norm : null;
    }

    /**
     * Directory of the expedition dimension inside a world folder.
     * Only ever built from the hardcoded, validated dimension id.
     */
    public static Path expeditionDimensionDir(Path worldDir) {
        // bigbangexpeditions:expedition -> dimensions/bigbangexpeditions/expedition
        return confine(worldDir, "dimensions", "bigbangexpeditions", "expedition");
    }
}
