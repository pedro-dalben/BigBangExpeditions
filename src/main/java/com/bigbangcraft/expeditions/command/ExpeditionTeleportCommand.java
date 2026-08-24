package com.bigbangcraft.expeditions.command;

import com.bigbangcraft.expeditions.integration.lostcities.LostCitiesAdapter;
import com.bigbangcraft.expeditions.teleport.ReturnPosition;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;

/**
 * /expedition enter|leave — operator-only staging teleports (Goal 02 Phase 5).
 * No homes, no warps: exists purely to test expedition gameplay.
 *
 * Safety rules:
 * - enter refuses when the expedition dimension is unavailable or LC profile missing.
 * - leave stores the return position at enter time; corrupt/missing data falls
 *   back to overworld spawn — a player must never be trapped.
 */
public final class ExpeditionTeleportCommand {
    private static final Logger LOG = LogManager.getLogger("BigBangExpeditions/Teleport");

    private ExpeditionTeleportCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("expedition")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("enter").executes(ctx -> enter(ctx.getSource()))
                        .then(Commands.argument("x", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                .then(Commands.argument("z", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                        .executes(ctx -> enterAt(ctx.getSource(),
                                                new BlockPos(
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "x"),
                                                        0,
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "z")))))))
                .then(Commands.literal("leave").executes(ctx -> leave(ctx.getSource()))));
    }

    private static int enter(CommandSourceStack src) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        // default target: same x/z as the player's current position
        return doEnter(src, player, BlockPos.containing(player.getX(), 0, player.getZ()));
    }

    private static int enterAt(CommandSourceStack src, BlockPos target) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        return doEnter(src, player, target);
    }

    private static int doEnter(CommandSourceStack src, ServerPlayer player, BlockPos target) {
        // lifecycle gate: entry only while explicitly OPEN
        var services = com.bigbangcraft.expeditions.core.RuntimeServices.get(src.getServer());
        com.bigbangcraft.expeditions.lifecycle.LifecycleState state;
        try {
            state = services.lifecycle().current().status;
        } catch (Exception e) {
            src.sendFailure(Component.literal(
                    "REFUSED: expedition lifecycle unreadable — entry blocked (fail-closed)"));
            return 0;
        }
        var decision = com.bigbangcraft.expeditions.lifecycle.EntryDecision.check(state);
        if (!decision.allowed) {
            services.auditRefusal("EXPEDITION_ENTER", player.getName().getString(), decision.reason);
            src.sendFailure(Component.literal("REFUSED: " + decision.reason));
            return 0;
        }

        ServerLevel expedition = src.getServer().getLevel(LostCitiesAdapter.expeditionDimensionKey());
        if (expedition == null) {
            src.sendFailure(Component.literal(
                    "REFUSED: expedition dimension not available — cannot teleport (player would be trapped)"));
            return 0;
        }
        if (!LostCitiesAdapter.isAvailable()) {
            src.sendFailure(Component.literal("REFUSED: Lost Cities absent — expedition content not verified"));
            return 0;
        }
        if (player.level().dimension() == LostCitiesAdapter.expeditionDimensionKey()) {
            src.sendFailure(Component.literal("Already inside the expedition dimension."));
            return 0;
        }

        storeReturn(player);
        com.bigbangcraft.expeditions.lifecycle.EvacuationService.markInside(player);
        ServerLevel from = (ServerLevel) player.level();
        double x = target.getX() + 0.5;
        double z = target.getZ() + 0.5;
        int y = expedition.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                target.getX(), target.getZ());
        player.teleportTo(expedition, x, y + 1.0, z, player.getYRot(), player.getXRot());
        src.sendSuccess(() -> Component.literal(String.format(
                "Entered expedition at [%.0f, %d, %.0f]. Use /expedition leave to return.", x, y + 1, z)), false);
        LOG.info("[enter] {} {} -> expedition@{},{}", player.getName().getString(), from.dimension().location(), x, z);
        return 1;
    }

    private static int leave(CommandSourceStack src) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        if (player.level().dimension() != LostCitiesAdapter.expeditionDimensionKey()) {
            src.sendFailure(Component.literal("Not inside the expedition dimension."));
            return 0;
        }
        Optional<ReturnPosition> ret = readReturn(player);
        ServerLevel overworld = src.getServer().overworld();

        if (ret.isPresent()) {
            ServerLevel target = src.getServer().getLevel(
                    ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                            new ResourceLocation(ret.get().dimension)));
            if (target != null) {
                ReturnPosition rp = ret.get();
                player.teleportTo(target, rp.x, rp.y, rp.z, rp.yaw, rp.pitch);
                clearReturn(player);
                com.bigbangcraft.expeditions.lifecycle.EvacuationService.markOutside(player);
                src.sendSuccess(() -> Component.literal("Returned to " + rp), false);
                LOG.info("[leave] {} -> {}", player.getName().getString(), rp);
                return 1;
            }
            src.sendSuccess(() -> Component.literal(
                    "Stored return dimension '" + ret.get().dimension + "' no longer exists; using overworld spawn."), false);
        }
        // fallback: never trap the player
        BlockPos spawn = overworld.getSharedSpawnPos();
        player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0f, 0f);
        clearReturn(player);
        com.bigbangcraft.expeditions.lifecycle.EvacuationService.markOutside(player);
        src.sendSuccess(() -> Component.literal("Returned to overworld spawn (fallback)."), false);
        return 1;
    }

    private static void storeReturn(ServerPlayer player) {
        CompoundTag data = player.getPersistentData();
        data.putString(ReturnPosition.key(), new ReturnPosition(
                player.level().dimension().location().toString(),
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot()).serialize());
    }

    private static Optional<ReturnPosition> readReturn(ServerPlayer player) {
        CompoundTag data = player.getPersistentData();
        return data.contains(ReturnPosition.key())
                ? ReturnPosition.deserialize(data.getString(ReturnPosition.key()))
                : Optional.empty();
    }

    private static void clearReturn(ServerPlayer player) {
        player.getPersistentData().remove(ReturnPosition.key());
    }
}
