package com.bigbangcraft.expeditions.command;

import com.bigbangcraft.expeditions.sector.SectorRecord;
import com.bigbangcraft.expeditions.sector.SectorRegistry;
import com.bigbangcraft.expeditions.sector.SectorState;
import com.bigbangcraft.expeditions.sector.SectorTopology;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * /expedition sector list | status <id> | create <id> <rx> <rz> [size] |
 *                            lock <id> | open <id>
 *
 * Lifecycle management only — no reset execution here (offline executor only).
 * The registry file lives at <server>/bigbangexpeditions/sectors.json.
 */
public final class SectorCommand {
    private static final String DIM = "bigbangexpeditions:expedition";

    private SectorCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("expedition")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("sector")
                        .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                        .then(Commands.literal("status")
                                .then(idArg(ctx -> status(ctx.getSource(), id(ctx)))))
                        .then(Commands.literal("create")
                                .then(Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .then(Commands.argument("regionX", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                                .then(Commands.argument("regionZ", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                                        .executes(ctx -> create(ctx.getSource(),
                                                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "id"),
                                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "regionX"),
                                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "regionZ"),
                                                                1))
                                                        .then(Commands.argument("sizeRegions", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 4))
                                                                .executes(ctx -> create(ctx.getSource(),
                                                                        com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "id"),
                                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "regionX"),
                                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "regionZ"),
                                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "sizeRegions"))))))))
                        .then(Commands.literal("lock")
                                .then(idArg(ctx -> transition(ctx.getSource(), id(ctx), SectorState.LOCKED))))
                        .then(Commands.literal("open")
                                .then(idArg(ctx -> transition(ctx.getSource(), id(ctx), SectorState.OPEN))))
                        .then(Commands.literal("reset-plan")
                                .then(idArg(ctx -> resetPlan(ctx.getSource(), id(ctx)))))
                        .then(Commands.literal("begin-validation")
                                .then(idArg(ctx -> transition(ctx.getSource(), id(ctx), SectorState.VALIDATING))))
                        .then(Commands.literal("attach-baseline")
                                .then(Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .then(Commands.argument("baselineId", com.mojang.brigadier.arguments.StringArgumentType.string())
                                                .executes(ctx -> attachBaseline(ctx.getSource(),
                                                        com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "id"),
                                                         com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "baselineId"))))))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> idArg(
            com.mojang.brigadier.Command<CommandSourceStack> exec) {
        return Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(exec);
    }

    private static String id(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        return com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "id");
    }

    public static Path registryFile(MinecraftServer server) {
        return server.getServerDirectory().toPath().resolve("bigbangexpeditions/sectors.json");
    }

    public static SectorRegistry registry(MinecraftServer server) {
        return new SectorRegistry(registryFile(server));
    }

    private static int list(CommandSourceStack src) {
        List<SectorRecord> all = registry(src.getServer()).list();
        src.sendSuccess(() -> Component.literal("=== Sectors (" + all.size() + ") ==="), false);
        for (SectorRecord r : all) {
            final String line = String.format("%s [%s] %s chunks(%d..%d, %d..%d) resets=%d%s",
                    r.id, r.status, r.dimension,
                    r.minChunkX, r.maxChunkX, r.minChunkZ, r.maxChunkZ,
                    r.resetCount,
                    r.failureReason.isEmpty() ? "" : " FAIL:" + r.failureReason);
            src.sendSuccess(() -> Component.literal(line), false);
        }
        return all.size();
    }

    private static int status(CommandSourceStack src, String id) {
        Optional<SectorRecord> rec = registry(src.getServer()).get(id);
        if (rec.isEmpty()) {
            src.sendFailure(Component.literal("REFUSED: unknown sector '" + id + "'"));
            return 0;
        }
        SectorRecord r = rec.get();
        src.sendSuccess(() -> Component.literal("=== Sector " + r.id + " ==="), false);
        send(src, "status: " + r.status);
        send(src, "dimension: " + r.dimension);
        send(src, String.format("bounds: chunks %d..%d, %d..%d (regions %d..%d, %d..%d)",
                r.minChunkX, r.maxChunkX, r.minChunkZ, r.maxChunkZ,
                Math.floorDiv(r.minChunkX, 32), Math.floorDiv(r.maxChunkX, 32),
                Math.floorDiv(r.minChunkZ, 32), Math.floorDiv(r.maxChunkZ, 32)));
        send(src, "created/updated: " + r.createdAtEpochMs + " / " + r.updatedAtEpochMs);
        send(src, "lastOpened: " + r.lastOpenedAtEpochMs + " lastReset: " + r.lastResetAtEpochMs);
        send(src, "resetCount: " + r.resetCount);
        send(src, "lastBaselineId: " + (r.lastBaselineId.isEmpty() ? "<none>" : r.lastBaselineId));
        send(src, "lastValidationResult: " + (r.lastValidationResult.isEmpty() ? "<none>" : r.lastValidationResult));
        if (!r.failureReason.isEmpty()) send(src, "failureReason: " + r.failureReason);
        return 1;
    }

    private static int create(CommandSourceStack src, String id, int rx, int rz, int sizeRegions) {
        SectorTopology.Size size;
        try {
            size = SectorTopology.Size.values()[Math.max(0, Integer.numberOfTrailingZeros(sizeRegions))];
        } catch (Exception e) {
            src.sendFailure(Component.literal("REFUSED: size must be 1, 2 or 4 regions"));
            return 0;
        }
        String[] err = new String[1];
        var bounds = SectorTopology.build(id, new net.minecraft.resources.ResourceLocation(DIM.split(":")[0], DIM.split(":")[1]), size, rx, rz, err);
        if (!err[0].isEmpty()) {
            src.sendFailure(Component.literal("REFUSED: " + err[0]));
            return 0;
        }
        SectorRegistry reg = registry(src.getServer());
        Optional<String> problem = reg.create(id, DIM,
                bounds.minChunkX(), bounds.minChunkZ(), bounds.maxChunkX(), bounds.maxChunkZ(),
                System.currentTimeMillis());
        if (problem.isPresent()) {
            src.sendFailure(Component.literal("REFUSED: " + problem.get()));
            return 0;
        }
        try {
            reg.save();
        } catch (Exception e) {
            src.sendFailure(Component.literal("registry save failed: " + e.getMessage()));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(String.format(
                "Created sector %s %s at region (%d,%d) size %dx%d region(s): chunks %d..%d, %d..%d",
                id, size, rx, rz, size.regionsPerSide, size.regionsPerSide,
                bounds.minChunkX(), bounds.maxChunkX(), bounds.minChunkZ(), bounds.maxChunkZ())), false);
        return 1;
    }

    private static int transition(CommandSourceStack src, String id, SectorState target) {
        SectorRegistry reg = registry(src.getServer());
        Optional<String> problem = reg.transition(id, target, System.currentTimeMillis());
        if (problem.isPresent()) {
            // explain refusal with current state
            Optional<SectorRecord> cur = reg.get(id);
            String ctxMsg = cur.map(r -> "current=" + r.status).orElse("unknown sector");
            src.sendFailure(Component.literal("REFUSED: " + problem.get() + " [" + ctxMsg + "]"));
            return 0;
        }
        try {
            reg.save();
        } catch (Exception e) {
            src.sendFailure(Component.literal("registry save failed: " + e.getMessage()));
            return 0;
        }
        final String msg = id + " -> " + target;
        src.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int attachBaseline(CommandSourceStack src, String id, String baselineId) {
        SectorRegistry reg = registry(src.getServer());
        if (reg.get(id).isEmpty()) {
            src.sendFailure(Component.literal("REFUSED: unknown sector '" + id + "'"));
            return 0;
        }
        reg.setBaseline(id, baselineId, System.currentTimeMillis());
        try { reg.save(); } catch (Exception e) {
            src.sendFailure(Component.literal("registry save failed: " + e.getMessage()));
            return 0;
        }
        final String msg = "baseline '" + baselineId + "' attached to sector " + id;
        src.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int resetPlan(CommandSourceStack src, String id) {
        MinecraftServer server = src.getServer();
        SectorRegistry reg = registry(server);
        Optional<SectorRecord> rec = reg.get(id);
        if (rec.isEmpty()) {
            src.sendFailure(Component.literal("REFUSED: unknown sector '" + id + "'"));
            return 0;
        }
        var outcome = com.bigbangcraft.expeditions.reset.ResetPlanService.createPlan(
                server, rec.get(), src.getTextName());
        if (!outcome.ok()) {
            src.sendFailure(Component.literal("RESET REFUSED — plan not created for " + id));
            for (String reason : outcome.refusals) {
                src.sendFailure(Component.literal("- " + reason));
            }
            return 0;
        }
        // manifest written; move sector into RESET_PLANNED
        Optional<String> t = reg.transition(id, SectorState.RESET_PLANNED, System.currentTimeMillis());
        if (t.isPresent()) {
            src.sendSuccess(() -> Component.literal("Manifest written but state unchanged: " + t.get()), false);
            return 1;
        }
        try { reg.save(); } catch (Exception e) {
            src.sendFailure(Component.literal("registry save failed: " + e.getMessage()));
            return 0;
        }
        Path file = outcome.file;
        src.sendSuccess(() -> Component.literal("reset-plan created: " + file), false);
        src.sendSuccess(() -> Component.literal("Next: stop server, run scripts/staging/execute-reset.sh with this plan."), false);
        return 1;
    }

    private static void send(CommandSourceStack src, String line) {
        src.sendSuccess(() -> Component.literal(line), false);
    }
}
