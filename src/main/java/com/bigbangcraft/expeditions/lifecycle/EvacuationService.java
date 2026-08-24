package com.bigbangcraft.expeditions.lifecycle;

import com.bigbangcraft.expeditions.audit.AuditEvent;
import com.bigbangcraft.expeditions.core.RuntimeServices;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Executes {@link EvacuationPlan}s against the live server.
 *
 * Guarantees:
 * - Nobody remains inside after execution returns success.
 * - Evicted players land at overworld spawn — never a position inside the
 *   dimension that may be regenerated.
 * - Every eviction is audited.
 */
public final class EvacuationService {
    /** Persistent-data marker proving a player was inside the expedition dimension. */
    public static final String INSIDE_MARKER = "bigbangexpeditions_inside";

    private EvacuationService() {}

    public static List<String> playersInside(ServerLevel expedition) {
        List<String> names = new ArrayList<>();
        if (expedition == null) return names;
        List<ServerPlayer> players = expedition.getEntitiesOfClass(ServerPlayer.class, new AABB(
                -30_000_000, expedition.getMinBuildHeight(), -30_000_000,
                30_000_000, expedition.getMaxBuildHeight(), 30_000_000), p -> true);
        for (ServerPlayer p : players) {
            GameProfile prof = p.getGameProfile();
            names.add(prof == null ? p.getStringUUID() : prof.getName());
        }
        return names;
    }

    /** Teleports every player inside to overworld spawn and clears markers. */
    public static int evacuateAll(MinecraftServer server, RuntimeServices services, String actor) throws IOException {
        ServerLevel expedition = server.getLevel(
                com.bigbangcraft.expeditions.integration.lostcities.LostCitiesAdapter.expeditionDimensionKey());
        List<String> inside = playersInside(expedition);
        List<Action2> actions = EvacuationPlan.plan(inside, staleMarkers(server)).stream()
                .map(a -> new Action2(a.type(), a.playerName()))
                .toList();

        ServerLevel overworld = server.overworld();
        int count = 0;
        for (Action2 action : actions) {
            ServerPlayer p = findPlayer(server, action.playerName());
            if (action.type() == EvacuationPlan.ActionType.TELEPORT_OUT && p != null) {
                BlockPos spawn = overworld.getSharedSpawnPos();
                p.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0f, 0f);
                p.sendSystemMessage(Component.literal(
                        "Expedition closed for maintenance — you were moved to overworld spawn."));
            }
            if (p != null) {
                p.getPersistentData().remove(INSIDE_MARKER);
                p.getPersistentData().remove(com.bigbangcraft.expeditions.teleport.ReturnPosition.key());
            }
            count++;
            services.audit().record(AuditEvent.of("PLAYER_EVACUATED", actor)
                    .subject(action.playerName())
                    .outcome(action.type() == EvacuationPlan.ActionType.TELEPORT_OUT ? "OK" : "EVICT_ON_JOIN")
                    .detail("mode", action.type().name()));
        }
        return count;
    }

    public static void markInside(ServerPlayer player) {
        player.getPersistentData().putBoolean(INSIDE_MARKER, true);
    }

    public static void markOutside(ServerPlayer player) {
        player.getPersistentData().remove(INSIDE_MARKER);
    }

    public static boolean hasStaleMarker(ServerPlayer player) {
        return player.getPersistentData().getBoolean(INSIDE_MARKER);
    }

    /** Handles join-after-disconnect-inside: evict unless lifecycle is OPEN again. */
    public static void onJoin(ServerPlayer player, RuntimeServices services) {
        try {
            if (!hasStaleMarker(player)) return;
            var state = services.lifecycle().current().status;
            if (state == LifecycleState.OPEN) {
                // reopened since disconnect; allow re-entry but refresh marker semantics
                return;
            }
            ServerLevel overworld = player.getServer().overworld();
            BlockPos spawn = overworld.getSharedSpawnPos();
            player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0f, 0f);
            markOutside(player);
            player.getPersistentData().remove(com.bigbangcraft.expeditions.teleport.ReturnPosition.key());
            player.sendSystemMessage(Component.literal(
                    "You disconnected inside the expedition during maintenance — moved to overworld spawn."));
            services.audit().record(AuditEvent.of("PLAYER_EVACUATED", "join-handler")
                    .subject(player.getName().getString()).outcome("EVICT_ON_JOIN"));
        } catch (Exception e) {
            // fail safe: never crash login; the player simply stays put this tick
        }
    }

    private record Action2(EvacuationPlan.ActionType type, String playerName) {}

    private static List<String> staleMarkers(MinecraftServer server) {
        // markers are per-player NBT; offline players surface via onJoin instead
        return List.of();
    }

    private static ServerPlayer findPlayer(MinecraftServer server, String name) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.getGameProfile() != null && p.getGameProfile().getName().equals(name)) return p;
        }
        return null;
    }
}
