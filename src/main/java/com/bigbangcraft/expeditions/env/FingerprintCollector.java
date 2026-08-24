package com.bigbangcraft.expeditions.env;

import com.bigbangcraft.expeditions.integration.lostcities.LostCitiesAdapter;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds the {@link InstallFingerprint} from the live runtime.
 * Any component that cannot be resolved is recorded as "?" — the drift policy
 * treats blank/missing values as REFUSE, so an unresolvable environment can
 * never authorize destructive work.
 */
public final class FingerprintCollector {
    /** Mods whose versions participate in the fingerprint. */
    public static final Map<String, String> TRACKED_MODS = new LinkedHashMap<>(Map.of(
            "lostcities", "Lost Cities (worldgen-critical)",
            "openpartiesandclaims", "OPAC (claim isolation)",
            "lootr", "Lootr (loot persistence)",
            "hordes", "Hordes (world population)"));

    public static final String CONFIG_LOOT_POLICY = "config/bigbangexpeditions/loot-policy.json";

    private FingerprintCollector() {}

    public static InstallFingerprint collect(MinecraftServer server) {
        InstallFingerprint f = new InstallFingerprint();
        f.bbeVersion = versionOf("bigbangexpeditions");
        f.minecraftVersion = server.getServerVersion() == null ? "?" : server.getServerVersion();
        f.forgeVersion = versionOf("forge");
        TreeMap<String, String> mods = new TreeMap<>();
        for (String id : TRACKED_MODS.keySet()) {
            mods.put(id, versionOf(id));
        }
        f.modVersions = mods;
        f.dimensionId = LostCitiesAdapter.expeditionDimensionId().toString();

        // LC profile via adapter (reflective); empty -> "?" (drift refuses)
        String profile = "?";
        try {
            var level = server.getLevel(LostCitiesAdapter.expeditionDimensionKey());
            if (level != null) {
                profile = LostCitiesAdapter.getProfile(level).orElse("?");
            }
        } catch (Exception ignored) {
            // headless/unit context
        }
        f.lostCitiesProfile = profile;
        Path lcConfig = server.getServerDirectory().toPath().resolve("config/lostcities");
        f.lostCitiesProfileSha256 = "?".equals(profile)
                ? "?"
                : LostCitiesAdapter.getProfileFingerprint(lcConfig, profile).orElse("?");

        try {
            f.worldSeedHash = Long.toHexString(server.overworld().getSeed());
        } catch (Exception e) {
            f.worldSeedHash = "?";
        }

        TreeMap<String, String> cfg = new TreeMap<>();
        cfg.put("loot-policy.json", sha256File(server.getServerDirectory().toPath()
                .resolve(CONFIG_LOOT_POLICY.replace("config/", "config/"))));
        f.configSha256 = cfg;
        return f;
    }

    private static String versionOf(String modId) {
        try {
            return ModList.get().getModContainerById(modId)
                    .map(c -> c.getModInfo())
                    .map(IModInfo::getVersion)
                    .map(Object::toString)
                    .orElse("?");
        } catch (Throwable t) {
            return "?";
        }
    }

    static String sha256File(Path p) {
        try {
            if (!Files.isRegularFile(p)) return "?";
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(Files.readAllBytes(p)));
        } catch (Exception e) {
            return "?";
        }
    }
}
