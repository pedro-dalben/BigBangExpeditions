package com.bigbangcraft.expeditions.gameplay;

import com.bigbangcraft.expeditions.audit.AuditEvent;
import com.bigbangcraft.expeditions.core.RuntimeServices;
import com.bigbangcraft.expeditions.i18n.Translations;
import com.bigbangcraft.expeditions.integration.lostcities.LostCitiesAdapter;
import com.bigbangcraft.expeditions.lifecycle.EntryDecision;
import com.bigbangcraft.expeditions.lifecycle.EvacuationService;
import com.bigbangcraft.expeditions.lifecycle.LifecycleState;
import com.bigbangcraft.expeditions.lifecycle.PlayerStateMapper;
import com.bigbangcraft.expeditions.safety.RateLimiter;
import com.bigbangcraft.expeditions.teleport.ReturnLocationPolicy;
import com.bigbangcraft.expeditions.teleport.ReturnPosition;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Optional;

/**
 * Goal 04 player-facing access service: the ONLY legitimate route into and out
 * of the expedition dimension for ordinary players.
 *
 * Guarantees:
 * - entry is refused unless the lifecycle is explicitly OPEN (fail-closed on
 *   unreadable lifecycle);
 * - leaving never traps a player: stored return positions are validated by
 *   {@link ReturnLocationPolicy}, otherwise the overworld spawn fallback runs;
 * - every accepted entry/leave and every refusal is audited;
 * - command spam cannot amplify disk IO (rate limit).
 */
public final class ExpeditionAccessService {
    private static final Logger LOG = LogManager.getLogger("BigBangExpeditions/Access");

    /** Max player-triggered transitions per window per player. */
    private static final RateLimiter TRANSITIONS = new RateLimiter(6, 10_000);

    private ExpeditionAccessService() {}

    // ------------------------------------------------------------------ enter

    public enum EnterOutcome { ENTERED, REFUSED_STATE, REFUSED_UNREADABLE, REFUSED_RATE,
        REFUSED_DIMENSION_MISSING, REFUSED_LC_ABSENT, ALREADY_INSIDE }

    public record EnterResult(EnterOutcome outcome, LifecycleState blockingState) {
        public boolean entered() {
            return outcome == EnterOutcome.ENTERED;
        }
    }

    public static EnterResult enter(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        RuntimeServices services = RuntimeServices.get(server);
        long now = System.currentTimeMillis();
        String name = player.getName().getString();

        if (!TRANSITIONS.tryAcquire("enter:" + name, now)) {
            send(player, "bbe.error.generic");
            services.auditRefusal("EXPEDITION_ENTER", name, "rate limited");
            return new EnterResult(EnterOutcome.REFUSED_RATE, null);
        }

        LifecycleState state;
        try {
            state = services.lifecycle().current().status;
        } catch (IOException e) {
            send(player, "bbe.entry.blocked.unreadable");
            services.auditRefusal("EXPEDITION_ENTER", name, "lifecycle unreadable");
            return new EnterResult(EnterOutcome.REFUSED_UNREADABLE, null);
        }

        var decision = EntryDecision.check(state);
        if (!decision.allowed) {
            refuseWithState(player, state);
            services.auditRefusal("EXPEDITION_ENTER", name, decision.reason);
            return new EnterResult(EnterOutcome.REFUSED_STATE, state);
        }

        ServerLevel expedition = server.getLevel(LostCitiesAdapter.expeditionDimensionKey());
        if (expedition == null) {
            send(player, "bbe.entry.blocked.unavailable");
            services.auditRefusal("EXPEDITION_ENTER", name, "dimension not loaded");
            return new EnterResult(EnterOutcome.REFUSED_DIMENSION_MISSING, state);
        }
        if (!LostCitiesAdapter.isAvailable()) {
            send(player, "bbe.entry.blocked.unavailable");
            services.auditRefusal("EXPEDITION_ENTER", name, "Lost Cities absent");
            return new EnterResult(EnterOutcome.REFUSED_LC_ABSENT, state);
        }
        if (player.level().dimension() == LostCitiesAdapter.expeditionDimensionKey()) {
            send(player, "bbe.entry.already_inside");
            return new EnterResult(EnterOutcome.ALREADY_INSIDE, state);
        }

        storeReturn(player);
        EvacuationService.markInside(player);

        int x = (int) Math.floor(player.getX());
        int z = (int) Math.floor(player.getZ());
        int y = expedition.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        com.bigbangcraft.expeditions.player.SessionRecovery.markTransferStart(player);
        player.teleportTo(expedition, x + 0.5, y + 1.0, z + 0.5, player.getYRot(), player.getXRot());
        com.bigbangcraft.expeditions.player.SessionRecovery.markTransferDone(player);
        com.bigbangcraft.expeditions.player.SessionRecovery.stampGeneration(player, services);

        send(player, "bbe.entry.success", x, y + 1, z);
        send(player, "bbe.entry.return_hint");
        send(player, "bbe.entry.warning.build");
        send(player, "bbe.entry.warning.death");
        send(player, "bbe.entry.warning.claim");

        try {
            services.audit().record(AuditEvent.of("PLAYER_ENTERED", name)
                    .outcome("OK")
                    .detail("generation", String.valueOf(services.lifecycle().current().generation))
                    .detail("target", x + "," + (y + 1) + "," + z));
        } catch (IOException e) {
            LOG.warn("entry audit failed: {}", e.toString());
        }
        LOG.info("[enter] {} -> expedition@{},{}", name, x, z);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
                new com.bigbangcraft.expeditions.event.BbeEvents.PlayerEnteredExpedition(
                        player, currentGenerationSafe(services)));
        return new EnterResult(EnterOutcome.ENTERED, state);
    }

    // ------------------------------------------------------------------ leave

    public enum LeaveOutcome { LEFT, LEFT_FALLBACK_SPAWN, NOT_INSIDE, REFUSED_RATE }

    public static LeaveOutcome leave(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        RuntimeServices services = RuntimeServices.get(server);
        String name = player.getName().getString();

        if (!TRANSITIONS.tryAcquire("leave:" + name, System.currentTimeMillis())) {
            send(player, "bbe.error.generic");
            return LeaveOutcome.REFUSED_RATE;
        }
        if (player.level().dimension() != LostCitiesAdapter.expeditionDimensionKey()) {
            send(player, "bbe.leave.not_inside");
            return LeaveOutcome.NOT_INSIDE;
        }

        Optional<ReturnPosition> stored = readReturn(player);
        ReturnPosition rp = stored.orElse(null);
        ServerLevel target = resolveDimension(server, rp);
        var evaluation = ReturnLocationPolicy.evaluate(stored, target != null,
                target == null ? -64 : target.getMinBuildHeight(),
                target == null ? 319 : target.getMaxBuildHeight());

        clearReturnData(player);
        EvacuationService.markOutside(player);

        if (evaluation.accepted() && target != null && rp != null) {
            com.bigbangcraft.expeditions.player.SessionRecovery.markTransferStart(player);
            player.teleportTo(target, rp.x, rp.y, rp.z, rp.yaw, rp.pitch);
            com.bigbangcraft.expeditions.player.SessionRecovery.markTransferDone(player);
            send(player, "bbe.leave.success", rp.toString());
            auditLeft(services, name, "OK");
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
                    new com.bigbangcraft.expeditions.event.BbeEvents.PlayerLeftExpedition(player));
            return LeaveOutcome.LEFT;
        }

        com.bigbangcraft.expeditions.player.SessionRecovery.markTransferStart(player);
        teleportToFallbackSpawn(server, player);
        com.bigbangcraft.expeditions.player.SessionRecovery.markTransferDone(player);
        switch (evaluation.fallbackReason()) {
            case "stale_dimension" -> send(player, "bbe.leave.stale_dimension");
            default -> send(player, "bbe.leave.fallback_spawn");
        }
        auditLeft(services, name, "FALLBACK:" + evaluation.fallbackReason());
        return LeaveOutcome.LEFT_FALLBACK_SPAWN;
    }

    // ------------------------------------------------- shared recovery pieces

    /** Shared fallback: central shelter of the persistent world. */
    public static void teleportToFallbackSpawn(MinecraftServer server, ServerPlayer player) {
        ServerLevel overworld = server.overworld();
        BlockPos spawn = overworld.getSharedSpawnPos();
        int y = overworld.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                spawn.getX(), spawn.getZ());
        player.teleportTo(overworld, spawn.getX() + 0.5, Math.max(spawn.getY(), y) + 1.0,
                spawn.getZ() + 0.5, 0f, 0f);
    }

    public static ServerLevel resolveDimension(MinecraftServer server, ReturnPosition rp) {
        if (rp == null) return null;
        try {
            ResourceKey<Level> key = ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION,
                    new ResourceLocation(rp.dimension));
            return server.getLevel(key);
        } catch (Exception e) {
            return null;
        }
    }

    public static void storeReturn(ServerPlayer player) {
        player.getPersistentData().putString(ReturnPosition.key(), new ReturnPosition(
                player.level().dimension().location().toString(),
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot()).serialize());
    }

    public static Optional<ReturnPosition> readReturn(ServerPlayer player) {
        var data = player.getPersistentData();
        return data.contains(ReturnPosition.key())
                ? ReturnPosition.deserialize(data.getString(ReturnPosition.key()))
                : Optional.empty();
    }

    public static void clearReturnData(ServerPlayer player) {
        player.getPersistentData().remove(ReturnPosition.key());
        EvacuationService.markOutside(player);
    }

    // ---------------------------------------------------------------- helpers

    private static void refuseWithState(ServerPlayer player, LifecycleState state) {
        PlayerStateMapper.Category cat = PlayerStateMapper.categorize(state);
        switch (cat) {
            case CLOSING -> send(player, "bbe.entry.blocked.closing");
            case UNAVAILABLE -> {
                if (state == LifecycleState.RECOVERY_REQUIRED || state == LifecycleState.FAILED
                        || state.destructiveWindow()
                        || state == LifecycleState.PREFLIGHT || state == LifecycleState.BACKUP
                        || state == LifecycleState.VALIDATING) {
                    send(player, "bbe.entry.blocked.unavailable");
                } else {
                    send(player, "bbe.entry.blocked.state",
                            Translations.t(PlayerStateMapper.phraseKey(state)));
                }
            }
            default -> send(player, "bbe.entry.blocked.state",
                    Translations.t(PlayerStateMapper.phraseKey(state)));
        }
    }

    private static void auditLeft(RuntimeServices services, String name, String outcome) {
        try {
            services.audit().record(AuditEvent.of("PLAYER_LEFT", name).outcome(outcome));
        } catch (Exception e) {
            LOG.warn("leave audit failed: {}", e.toString());
        }
    }

    private static int currentGenerationSafe(RuntimeServices services) {
        try {
            return services.lifecycle().current().generation;
        } catch (Exception e) {
            return -1;
        }
    }

    public static void send(ServerPlayer player, String key, Object... args) {
        player.sendSystemMessage(Component.literal(Translations.t(key, args)));
    }
}
