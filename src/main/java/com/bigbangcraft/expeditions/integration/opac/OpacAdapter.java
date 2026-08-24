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
 * Adapter for Open Parties and Claims (OPAC) using its PUBLIC server API:
 * xaero.pac.common.server.api.OpenPACServerAPI#get(MinecraftServer)
 *   .getServerClaimsManager() -> IServerClaimsManagerAPI
 *     .get(ResourceLocation dim, int chunkX, int chunkZ) -> IPlayerChunkClaimAPI | null
 *     .isClaimable(ResourceLocation) -> boolean (dimension-level claim permission)
 *
 * Verified against open-parties-and-claims-forge-1.20.1-0.25.8.jar (Goal 02 Phase 6).
 * Reflective to avoid hard compile dependency. Fail-closed: any failure ->
 * unavailable -> callers must REFUSE.
 */
public final class OpacAdapter {
    private static final Logger LOG = LogManager.getLogger("BigBangExpeditions/OPAC");
    private static final String API_CLASS = "xaero.pac.common.server.api.OpenPACServerAPI";

    private OpacAdapter() {}

    /** True if OPAC classes are loadable AND the public server API resolves. */
    public static boolean isOpacPresent() {
        try {
            Class.forName(API_CLASS);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Resolves IServerClaimsManagerAPI for a live server; empty when unavailable. */
    private static Object resolveClaimsManager(MinecraftServer server) {
        if (!isOpacPresent()) return null;
        try {
            Class<?> apiClass = Class.forName(API_CLASS);
            Method get = apiClass.getMethod("get", MinecraftServer.class);
            Object api = get.invoke(null, server);
            if (api == null) return null;
            Method scm = apiClass.getMethod("getServerClaimsManager");
            return scm.invoke(api);
        } catch (Throwable t) {
            LOG.warn("OPAC API resolution failed: {}", t.toString());
            return null;
        }
    }

    /**
     * True when OPAC allows claims AT ALL in the given dimension
     * (IServerClaimsManagerAPI#isClaimable). Empty = cannot determine.
     */
    public static Boolean isDimensionClaimable(MinecraftServer server, ResourceLocation dimensionId) {
        Object manager = resolveClaimsManager(server);
        if (manager == null) return null;
        try {
            Method m = manager.getClass().getMethod("isClaimable", ResourceLocation.class);
            Object r = m.invoke(manager, dimensionId);
            return (Boolean) r;
        } catch (NoSuchMethodException nsme) {
            // older/newer builds without the API — treat as undeterminable
            return null;
        } catch (Throwable t) {
            LOG.warn("OPAC isClaimable failed: {}", t.toString());
            return null;
        }
    }

    /** Claim lookup on one chunk via public API; null-safe. False on any error. */
    private static boolean chunkClaimed(Object managerApi, ResourceLocation dim, int cx, int cz) {
        try {
            Method m = managerApi.getClass().getMethod("get", ResourceLocation.class, int.class, int.class);
            Object claim = m.invoke(managerApi, dim, cx, cz);
            return claim != null;
        } catch (Throwable t) {
            throw new IllegalStateException("OPAC chunk lookup failed at " + cx + "," + cz + ": " + t, t);
        }
    }

    private static boolean chunkForceloadable(Object managerApi, ResourceLocation dim, int cx, int cz) {
        try {
            Method m = managerApi.getClass().getMethod("get", ResourceLocation.class, int.class, int.class);
            Object claim = m.invoke(managerApi, dim, cx, cz);
            if (claim == null) return false;
            Method fl = claim.getClass().getMethod("isForceloadable");
            Object r = fl.invoke(claim);
            return (Boolean) r;
        } catch (Throwable t) {
            throw new IllegalStateException("OPAC forceload lookup failed at " + cx + "," + cz + ": " + t, t);
        }
    }

    public static ClaimInspectionResult inspectClaims(MinecraftServer server, ServerLevel level, SectorBounds bounds) {
        if (server == null) return ClaimInspectionResult.unavailable("server null");
        if (level == null) return ClaimInspectionResult.unavailable("dimension unavailable: " + bounds.dimension());
        if (!isOpacPresent()) return ClaimInspectionResult.unavailable("OPAC not present");

        Object manager = resolveClaimsManager(server);
        if (manager == null) return ClaimInspectionResult.unavailable("claims manager unavailable");

        int intersecting = 0;
        int forceloads = 0;
        List<String> hitSamples = new ArrayList<>();
        ResourceLocation dimId = level.dimension().location();

        try {
            for (int cx = bounds.minChunkX(); cx <= bounds.maxChunkX(); cx++) {
                for (int cz = bounds.minChunkZ(); cz <= bounds.maxChunkZ(); cz++) {
                    if (chunkClaimed(manager, dimId, cx, cz)) {
                        intersecting++;
                        if (hitSamples.size() < 5) hitSamples.add("claim at " + cx + "," + cz);
                    }
                    if (chunkForceloadable(manager, dimId, cx, cz)) forceloads++;
                }
            }
        } catch (Throwable t) {
            LOG.error("OPAC inspection failed: {}", t.toString());
            return ClaimInspectionResult.unavailable("exception: " + t.getMessage());
        }

        return ClaimInspectionResult.available(intersecting, forceloads, hitSamples);
    }
}
