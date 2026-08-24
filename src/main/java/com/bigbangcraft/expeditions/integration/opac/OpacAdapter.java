package com.bigbangcraft.expeditions.integration.opac;

import com.bigbangcraft.expeditions.sector.SectorBounds;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Small isolated adapter for Open Parties and Claims.
 * Reflective to avoid hard compile dependency — pack may not have OPAC on dev env.
 * Fail-closed: any exception/null → unavailable → probe must REFUSE.
 */
public final class OpacAdapter {
    private static final Logger LOG = LogManager.getLogger("BigBangExpeditions/OPAC");
    private static final String MANAGER_CLASS = "xaero.pac.common.server.claims.ServerClaimsManager";
    private static final String DIMENSION_SUFFIX = "xaero.pac.common.server.claims.IServerDimensionClaimsManager";
    private static final String CLAIM_CLASS = "xaero.pac.common.claims.player.IPlayerChunkClaimAPI";

    private OpacAdapter() {}

    public static boolean isOpacPresent() {
        try {
            Class.forName(MANAGER_CLASS);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static ClaimInspectionResult inspectClaims(MinecraftServer server, ServerLevel level, SectorBounds bounds) {
        if (server == null) return ClaimInspectionResult.unavailable("server null");
        if (level == null) return ClaimInspectionResult.unavailable("dimension unavailable: " + bounds.dimension());
        if (!isOpacPresent()) return ClaimInspectionResult.unavailable("OPAC not present");

        try {
            Class<?> managerClass = Class.forName(MANAGER_CLASS);
            // ServerClaimsManager.get(MinecraftServer)
            Method get = null;
            for (Method m : managerClass.getMethods()) {
                if (m.getName().equals("get") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == MinecraftServer.class) {
                    get = m;
                    break;
                }
            }
            if (get == null) return ClaimInspectionResult.unavailable("OPAC API get(MinecraftServer) not found");

            Object manager = get.invoke(null, server);
            if (manager == null) return ClaimInspectionResult.unavailable("ServerClaimsManager.get returned null (not loaded)");

            // manager.getDimension(ResourceLocation)
            Method getDimension = null;
            for (Method m : manager.getClass().getMethods()) {
                if (m.getName().equals("getDimension") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == ResourceLocation.class) {
                    getDimension = m;
                    break;
                }
            }
            if (getDimension == null) return ClaimInspectionResult.unavailable("getDimension not found");

            ResourceLocation dimId = level.dimension().location();
            Object dimManager = getDimension.invoke(manager, dimId);
            if (dimManager == null) return ClaimInspectionResult.unavailable("dimension claims manager null for " + dimId);

            // Iterate chunks in bounds: dimManager.getClaim(int x, int z) or get(int,int)
            // Try common signatures
            Method getClaim = findGetClaim(dimManager.getClass());
            if (getClaim == null) return ClaimInspectionResult.unavailable("getClaim(int,int) not found on dimension manager");

            int intersecting = 0;
            int forceloads = 0;
            List<String> hitSamples = new ArrayList<>();

            for (int cx = bounds.minChunkX(); cx <= bounds.maxChunkX(); cx++) {
                for (int cz = bounds.minChunkZ(); cz <= bounds.maxChunkZ(); cz++) {
                    try {
                        Object claim = getClaim.invoke(dimManager, cx, cz);
                        if (claim != null) {
                            // claim may be IPlayerChunkClaimAPI with playerId
                            // treat non-null as intersecting (party/personal both count)
                            intersecting++;
                            if (hitSamples.size() < 5) {
                                hitSamples.add("claim at " + cx + "," + cz + " -> " + claim.getClass().getSimpleName());
                            }
                        }
                    } catch (Exception ex) {
                        LOG.warn("OPAC getClaim failed at {},{}: {}", cx, cz, ex.toString());
                        return ClaimInspectionResult.unavailable("OPAC getClaim exception at " + cx + "," + cz + ": " + ex.getMessage());
                    }
                    // Forceload check if available
                    Method getForceload = findForceload(dimManager.getClass());
                    if (getForceload != null) {
                        try {
                            Object fl = getForceload.invoke(dimManager, cx, cz);
                            if (fl != null && Boolean.TRUE.equals(fl)) forceloads++;
                            else if (fl != null && !fl.equals(Boolean.FALSE) && !(fl instanceof Boolean)) forceloads++; // non-boolean marker
                        } catch (Exception ignore) {}
                    }
                }
            }

            ClaimInspectionResult r = ClaimInspectionResult.available(intersecting, forceloads, hitSamples);
            return r;
        } catch (Exception e) {
            LOG.error("OPAC inspection failed: {}", e.toString(), e);
            return ClaimInspectionResult.unavailable("exception: " + e.getMessage());
        }
    }

    private static Method findGetClaim(Class<?> c) {
        for (Method m : c.getMethods()) {
            if (m.getName().equals("getClaim") || m.getName().equals("get") || m.getName().equals("getClaimAt")) {
                if (m.getParameterCount() == 2 && m.getParameterTypes()[0] == int.class && m.getParameterTypes()[1] == int.class) {
                    return m;
                }
            }
        }
        // also try "getClaim" with generic
        for (Method m : c.getMethods()) {
            if (m.getParameterCount() == 2) {
                Class<?> p0 = m.getParameterTypes()[0];
                Class<?> p1 = m.getParameterTypes()[1];
                if (p0 == int.class && p1 == int.class && m.getName().toLowerCase().contains("claim")) return m;
            }
        }
        return null;
    }

    private static Method findForceload(Class<?> c) {
        for (Method m : c.getMethods()) {
            String n = m.getName().toLowerCase();
            if ((n.contains("force") || n.contains("forceload")) && m.getParameterCount() == 2) return m;
        }
        return null;
    }
}
