package com.bigbangcraft.expeditions.player;

import com.bigbangcraft.expeditions.audit.AuditEvent;
import com.bigbangcraft.expeditions.core.RuntimeServices;
import com.bigbangcraft.expeditions.gameplay.ExpeditionAccessService;
import com.bigbangcraft.expeditions.integration.lostcities.LostCitiesAdapter;
import com.bigbangcraft.expeditions.lifecycle.EvacuationService;
import com.bigbangcraft.expeditions.lifecycle.LifecycleState;
import com.bigbangcraft.expeditions.lifecycle.PlayerStateMapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * Minecraft adapter for the login/logout/death recovery matrix (Goal 04).
 *
 * Persistent data keys (player PersistentData, survives restarts):
 * - {@link #GEN_KEY} generation at entry time
 * - {@link #TRANSFER_KEY} set while a managed transfer is in flight
 */
public final class SessionRecovery {
    private static final Logger LOG = LogManager.getLogger("BigBangExpeditions/Session");

    public static final String GEN_KEY = "bigbangexpeditions_gen";
    public static final String TRANSFER_KEY = "bigbangexpeditions_transfer";

    private SessionRecovery() {}

    // -------------------------------------------------------------- join path

    public static void onJoin(ServerPlayer player) {
        try {
            RuntimeServices services = RuntimeServices.get(player.getServer());
            LifecycleState state = services.lifecycle().current().status;
            var category = PlayerStateMapper.categorize(state);

            boolean wasInside = EvacuationService.hasStaleMarker(player);
            int loggedOutGen = player.getPersistentData().contains(GEN_KEY)
                    ? player.getPersistentData().getInt(GEN_KEY) : -1;
            int currentGen = services.lifecycle().current().generation;
            boolean transfer = player.getPersistentData().contains(TRANSFER_KEY);

            var action = LoginRecoveryDecision.decide(wasInside, loggedOutGen, currentGen,
                    category, transfer);
            apply(player, services, state, action);
        } catch (Exception e) {
            // never crash login — but leave a loud trace
            LOG.error("join recovery failed for {}: {}", player.getName().getString(), e.toString());
        }
    }

    private static void apply(ServerPlayer player, RuntimeServices services,
                              LifecycleState state, LoginRecoveryDecision.Action action) {
        String name = player.getName().getString();
        switch (action) {
            case NONE -> { /* ordinary login */ }
            case RESTORE_IN_PLACE -> {
                // same generation + OPEN: legitimate resume; refresh gen stamp
                stampGeneration(player, services);
            }
            case RECOVER_NEW_ZONE -> {
                recoverToPersistentWorld(player, services, "bbe.login.recovered.new_zone",
                        "RECOVER_NEW_ZONE", "gen mismatch");
            }
            case EVICT_MAINTENANCE -> {
                recoverToPersistentWorld(player, services, "bbe.login.evicted.maintenance",
                        "EVICT_ON_JOIN", "state=" + state);
            }
            case RESOLVE_TRANSFER -> {
                player.getPersistentData().remove(TRANSFER_KEY);
                recoverToPersistentWorld(player, services, "bbe.login.transfer_interrupted",
                        "TRANSFER_RESOLVED", "interrupted transfer");
            }
        }
    }

    /** Shared recovery landing: stored return position if valid, else central shelter. */
    static void recoverToPersistentWorld(ServerPlayer player, RuntimeServices services,
                                         String messageKey, String auditOutcome,
                                         String detail) {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
                new com.bigbangcraft.expeditions.event.BbeEvents.PlayerEvacuated(
                        player, player.getName().getString(),
                        "RESPAWN_REDIRECT".equals(auditOutcome) ? "RESPAWN_REDIRECT" : "RECOVERED"));
        String name = player.getName().getString();
        var stored = ExpeditionAccessService.readReturn(player);
        var targetLevel = ExpeditionAccessService.resolveDimension(
                player.getServer(), stored.orElse(null));

        boolean landedAtReturn = false;
        if (stored.isPresent() && targetLevel != null) {
            var rp = stored.get();
            if (!LostCitiesAdapter.expeditionDimensionId().toString().equals(rp.dimension)
                    && rp.y >= targetLevel.getMinBuildHeight() - 1
                    && rp.y <= targetLevel.getMaxBuildHeight() + 1) {
                player.teleportTo(targetLevel, rp.x, rp.y, rp.z, rp.yaw, rp.pitch);
                landedAtReturn = true;
            }
        }
        if (!landedAtReturn) {
            ExpeditionAccessService.teleportToFallbackSpawn(player.getServer(), player);
        }

        ExpeditionAccessService.clearReturnData(player);
        ExpeditionAccessService.send(player, messageKey);
        LOG.info("[recovery] {} action={} landedAtReturn={} {}", name, auditOutcome, landedAtReturn, detail);
        try {
            services.audit().record(AuditEvent.of("PLAYER_RECOVERED", name)
                    .outcome(auditOutcome)
                    .detail("landedAtReturn", String.valueOf(landedAtReturn))
                    .detail("detail", detail));
        } catch (Exception e) {
            LOG.warn("recovery audit failed: {}", e.toString());
        }
    }

    // ------------------------------------------------------------- enter path

    /** Stamps the lifecycle generation at entry time. Called by the access service. */
    public static void stampGeneration(ServerPlayer player, RuntimeServices services) {
        try {
            int gen = services.lifecycle().current().generation;
            player.getPersistentData().putInt(GEN_KEY, gen);
        } catch (Exception e) {
            // fail-closed: unknown generation forces recovery on next join
            player.getPersistentData().putInt(GEN_KEY, -1);
        }
    }

    // ----------------------------------------------------------- transfer flag

    public static void markTransferStart(ServerPlayer player) {
        player.getPersistentData().putLong(TRANSFER_KEY, System.currentTimeMillis());
    }

    public static void markTransferDone(ServerPlayer player) {
        player.getPersistentData().remove(TRANSFER_KEY);
    }

    /**
     * Post-death redirect: never let a respawn stand inside expedition territory.
     * Landing rule identical to join recovery (return point → central shelter).
     */
    public static void recoverAfterDeath(ServerPlayer player, RuntimeServices services) {
        recoverToPersistentWorld(player, services,
                "bbe.death.respawn_redirected", "RESPAWN_REDIRECTED", "post-respawn redirect");
    }
}
