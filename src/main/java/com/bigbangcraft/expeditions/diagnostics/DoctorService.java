package com.bigbangcraft.expeditions.diagnostics;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;

public final class DoctorService {
    private static final Logger LOG = LogManager.getLogger("BigBangExpeditions/Doctor");

    private DoctorService() {}

    public static DoctorReport build(MinecraftServer server, ServerLevel level) {
        DoctorReport r = new DoctorReport();
        r.minecraftVersion = server.getServerVersion();
        try {
            r.forgeVersion = ModList.get().getModContainerById("forge").map(c -> c.getModInfo().getVersion().toString()).orElse("unknown");
        } catch (Exception e) { r.forgeVersion = "unknown"; }
        r.modVersion = ModList.get().getModContainerById("bigbangexpeditions").map(c -> c.getModInfo().getVersion().toString()).orElse("dev");
        r.dimension = level != null ? level.dimension().location().toString() : "unknown";

        r.lostCitiesPresent = isPresent("lostcities");
        r.lostCitiesVersion = versionOf("lostcities");
        r.opacPresent = isPresent("openpartiesandclaims");
        r.opacVersion = versionOf("openpartiesandclaims");
        r.lootrPresent = isPresent("lootr");
        r.lootrVersion = versionOf("lootr");
        r.ftbTeamsPresent = isPresent("ftbteams");
        r.hordesPresent = isPresent("hordes");
        r.hordesVersion = versionOf("hordes");
        r.createPresent = isPresent("create");
        r.iePresent = isPresent("immersiveengineering");
        r.rsPresent = isPresent("refinedstorage");
        r.securityCraftPresent = isPresent("securitycraft");

        // LostCities profile: try to read common config if possible via reflection
        r.lostCitiesProfile = probeLostCitiesProfile();

        // Lootr enabled/disabled: read config file if accessible
        r.lootrEnabled = probeLootrEnabled(server);

        // world seed availability
        if (level != null) {
            try {
                long seed = level.getSeed();
                r.worldSeedStatus = "available (hash " + Long.toHexString(seed) + ")";
            } catch (Exception e) {
                r.worldSeedStatus = "unavailable: " + e.getMessage();
                r.warn("world seed unavailable");
            }
        } else {
            r.worldSeedStatus = "no level";
        }

        if (!r.lostCitiesPresent) r.warn("Lost Cities not detected — city generation checks will be WARN");
        if (!r.opacPresent) r.warn("OPAC not detected — claim inspection will REFUSE (fail-closed)");
        if (r.lootrPresent && "unknown".equals(r.lootrEnabled)) r.warn("Lootr enabled state unknown — assume vanilla loot duplication risk");
        if (!r.hordesPresent) r.warn("Hordes not present");

        LOG.info("[Doctor] {}", r);
        return r;
    }

    private static boolean isPresent(String id) {
        try { return ModList.get().isLoaded(id); } catch (Exception e) { return false; }
    }

    private static String versionOf(String id) {
        try {
            Optional<? extends IModInfo> m = ModList.get().getModContainerById(id).map(c -> c.getModInfo());
            return m.map(i -> i.getVersion().toString()).orElse("unknown");
        } catch (Exception e) { return "unknown"; }
    }

    private static String probeLostCitiesProfile() {
        // Try to load LostCities profile via reflection if available
        try {
            // config lives in lostcities: look for class mcjty.lostcities.config.Configuration or similar
            // Instead, read serverconfig file if exists? Fallback to unknown
            return "unknown (check config/lostcities/common.toml:dimensionsWithProfiles or defaultconfigs/lostcities-server.toml:selectedProfile)";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String probeLootrEnabled(MinecraftServer server) {
        try {
            // Lootr stores disable flag in config/lootr-common.toml
            // Forge config spec: no direct API without hard dep. Try to read file from server dir
            java.nio.file.Path p = server.getServerDirectory().toPath().resolve("config/lootr-common.toml");
            if (!java.nio.file.Files.exists(p)) p = java.nio.file.Paths.get("config/lootr-common.toml");
            if (java.nio.file.Files.exists(p)) {
                String c = java.nio.file.Files.readString(p);
                if (c.contains("disable = true")) return "disabled (config disable=true)";
                if (c.contains("disable = false")) return "enabled";
            }
        } catch (Exception ignored) {}
        return "unknown";
    }
}
