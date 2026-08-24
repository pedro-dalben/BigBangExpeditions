package com.bigbangcraft.expeditions.command;

import com.bigbangcraft.expeditions.diagnostics.DoctorReport;
import com.bigbangcraft.expeditions.diagnostics.DoctorService;
import com.bigbangcraft.expeditions.sector.SectorBounds;
import com.bigbangcraft.expeditions.sector.SectorProbeResult;
import com.bigbangcraft.expeditions.validation.BaselineData;
import com.bigbangcraft.expeditions.validation.BaselineService;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ExpeditionCommand {
    private static final Logger LOG = LogManager.getLogger("BigBangExpeditions/Command");

    private ExpeditionCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("expedition")
                .requires(s -> s.hasPermission(2)) // operator only
                .then(Commands.literal("doctor").executes(ctx -> doctor(ctx.getSource())))
                .then(Commands.literal("world").executes(ctx -> world(ctx.getSource())))
                .then(Commands.literal("sector")
                        .then(Commands.literal("probe")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .then(Commands.argument("dimension", ResourceLocationArgument.id())
                                                .then(Commands.argument("minX", IntegerArgumentType.integer())
                                                        .then(Commands.argument("minZ", IntegerArgumentType.integer())
                                                                .then(Commands.argument("maxX", IntegerArgumentType.integer())
                                                                        .then(Commands.argument("maxZ", IntegerArgumentType.integer())
                                                                                .executes(ctx -> probe(ctx.getSource(),
                                                                                        StringArgumentType.getString(ctx, "id"),
                                                                                        ResourceLocationArgument.getId(ctx, "dimension"),
                                                                                        IntegerArgumentType.getInteger(ctx, "minX"),
                                                                                        IntegerArgumentType.getInteger(ctx, "minZ"),
                                                                                        IntegerArgumentType.getInteger(ctx, "maxX"),
                                                                                        IntegerArgumentType.getInteger(ctx, "maxZ"))))))))))
                        .then(Commands.literal("baseline")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .then(Commands.argument("dimension", ResourceLocationArgument.id())
                                                .then(Commands.argument("minX", IntegerArgumentType.integer())
                                                        .then(Commands.argument("minZ", IntegerArgumentType.integer())
                                                                .then(Commands.argument("maxX", IntegerArgumentType.integer())
                                                                        .then(Commands.argument("maxZ", IntegerArgumentType.integer())
                                                                                .executes(ctx -> baseline(ctx.getSource(),
                                                                                        StringArgumentType.getString(ctx, "id"),
                                                                                        ResourceLocationArgument.getId(ctx, "dimension"),
                                                                                        IntegerArgumentType.getInteger(ctx, "minX"),
                                                                                        IntegerArgumentType.getInteger(ctx, "minZ"),
                                                                                        IntegerArgumentType.getInteger(ctx, "maxX"),
                                                                                        IntegerArgumentType.getInteger(ctx, "maxZ"))))))))))
                        .then(Commands.literal("compare")
                                .then(Commands.argument("beforeFile", StringArgumentType.string())
                                        .then(Commands.argument("id", StringArgumentType.word())
                                                .then(Commands.argument("dimension", ResourceLocationArgument.id())
                                                        .then(Commands.argument("minX", IntegerArgumentType.integer())
                                                                .then(Commands.argument("minZ", IntegerArgumentType.integer())
                                                                        .then(Commands.argument("maxX", IntegerArgumentType.integer())
                                                                                .then(Commands.argument("maxZ", IntegerArgumentType.integer())
                                                                                        .executes(ctx -> compare(ctx.getSource(),
                                                                                                StringArgumentType.getString(ctx, "beforeFile"),
                                                                                                StringArgumentType.getString(ctx, "id"),
                                                                                                ResourceLocationArgument.getId(ctx, "dimension"),
                                                                                                IntegerArgumentType.getInteger(ctx, "minX"),
                                                                                                IntegerArgumentType.getInteger(ctx, "minZ"),
                                                                                                IntegerArgumentType.getInteger(ctx, "maxX"),
                                                                                                IntegerArgumentType.getInteger(ctx, "maxZ"))))))))))))
        );
    }

    private static int doctor(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        ServerLevel level = src.getLevel();
        try {
            DoctorReport r = DoctorService.build(server, level);
            src.sendSuccess(() -> Component.literal("=== Expedition Doctor ==="), false);
            src.sendSuccess(() -> Component.literal("MC: " + r.minecraftVersion + " Forge: " + r.forgeVersion + " Mod: " + r.modVersion), false);
            src.sendSuccess(() -> Component.literal("Dimension: " + r.dimension), false);
            src.sendSuccess(() -> Component.literal("LostCities: " + (r.lostCitiesPresent ? r.lostCitiesVersion : "NOT present") + " profile: " + r.lostCitiesProfile), false);
            src.sendSuccess(() -> Component.literal("OPAC: " + (r.opacPresent ? r.opacVersion : "NOT present")), false);
            src.sendSuccess(() -> Component.literal("Lootr: " + (r.lootrPresent ? r.lootrVersion + " [" + r.lootrEnabled + "]" : "NOT present")), false);
            src.sendSuccess(() -> Component.literal("FTB Teams: " + (r.ftbTeamsPresent ? "yes" : "no") + " Hordes: " + (r.hordesPresent ? r.hordesVersion : "no") + " Create:" + (r.createPresent ? "yes" : "no") + " IE:" + (r.iePresent ? "yes" : "no") + " RS:" + (r.rsPresent ? "yes" : "no") + " SC:" + (r.securityCraftPresent ? "yes" : "no")), false);
            src.sendSuccess(() -> Component.literal("Seed: " + r.worldSeedStatus), false);
            for (String w : r.warnings) src.sendSuccess(() -> Component.literal("WARN: " + w), false);
            LOG.info("[doctor] dim={} lostCities={} opac={} lootr={} seed={} warnings={}", r.dimension, r.lostCitiesPresent, r.opacPresent, r.lootrEnabled, r.worldSeedStatus, r.warnings);
            return 1;
        } catch (Exception e) {
            src.sendFailure(Component.literal("doctor failed: " + e.getMessage()));
            LOG.error("doctor failed", e);
            return 0;
        }
    }

    private static int world(CommandSourceStack src) {
        ServerLevel level = src.getLevel();
        src.sendSuccess(() -> Component.literal("Level: " + level.dimension().location() + " seedHash=" + Long.toHexString(level.getSeed()) + " min=" + level.getMinBuildHeight() + " max=" + level.getMaxBuildHeight()), false);
        return 1;
    }

    private static int probe(CommandSourceStack src, String id, ResourceLocation dim, int minX, int minZ, int maxX, int maxZ) {
        MinecraftServer server = src.getServer();
        SectorBounds bounds = new SectorBounds(id, dim, minX, minZ, maxX, maxZ);
        String err = bounds.validate();
        if (err != null) {
            src.sendFailure(Component.literal("REFUSED: " + err));
            return 0;
        }
        ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dim));
        SectorProbeResult r = BaselineService.probe(server, level, bounds);
        // pretty report
        src.sendSuccess(() -> Component.literal("=== Probe " + bounds + " ==="), false);
        src.sendSuccess(() -> Component.literal("Verdict: " + r.verdict()), false);
        src.sendSuccess(() -> Component.literal("Chunks: " + r.chunkCount + " loaded=" + r.loadedChunks), false);
        src.sendSuccess(() -> Component.literal("Players inside: " + r.playersInside + (r.playerNames.isEmpty() ? "" : " " + r.playerNames)), false);
        src.sendSuccess(() -> Component.literal("OPAC: " + r.opacStatus + (r.opacAvailable ? "" : " (UNAVAILABLE)") ), false);
        src.sendSuccess(() -> Component.literal("BEs: " + r.blockEntityCount + " containers=" + r.containerCount + " spawners=" + r.spawnerCount + " entities=" + r.entityCount), false);
        src.sendSuccess(() -> Component.literal(String.format("Create=%d IE=%d RS=%d SC=%d", r.createCount, r.immersiveCount, r.refinedStorageCount, r.securityCraftCount)), false);
        if (r.blockEntitiesByNamespace != null && !r.blockEntitiesByNamespace.isEmpty())
            src.sendSuccess(() -> Component.literal("BE by ns: " + r.blockEntitiesByNamespace), false);
        for (String w : r.warnings()) src.sendSuccess(() -> Component.literal("WARN: " + w), false);
        for (String reason : r.reasons()) src.sendSuccess(() -> Component.literal("REFUSED: " + reason), false);
        String verdictMsg = switch (r.verdict()) {
            case PASS -> "PASS — no blocking issues detected (still read-only)";
            case WARN -> "WARN — sector has content that would be deleted; would REFUSE regen";
            case REFUSED -> "REFUSED — must not regen";
        };
        src.sendSuccess(() -> Component.literal(verdictMsg), false);
        LOG.info("[probe] {} verdict={}", bounds, r.verdict());
        return 1;
    }

    private static int baseline(CommandSourceStack src, String id, ResourceLocation dim, int minX, int minZ, int maxX, int maxZ) {
        MinecraftServer server = src.getServer();
        SectorBounds bounds = new SectorBounds(id, dim, minX, minZ, maxX, maxZ);
        String err = bounds.validate();
        if (err != null) { src.sendFailure(Component.literal("REFUSED: " + err)); return 0; }
        ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dim));
        SectorProbeResult probe = BaselineService.probe(server, level, bounds);
        // baseline is still read-only, but we export probe data
        BaselineData data = BaselineService.toBaseline(server, level, probe);
        try {
            Path p = BaselineService.writeBaseline(server, data);
            src.sendSuccess(() -> Component.literal("Baseline written: " + p + " verdict=" + probe.verdict()), false);
            src.sendSuccess(() -> Component.literal(BaselineService.compare(data, data).substring(0, Math.min(500, BaselineService.compare(data, data).length()))), false);
            LOG.info("[baseline] {} -> {}", bounds, p);
            return 1;
        } catch (Exception e) {
            src.sendFailure(Component.literal("baseline failed: " + e.getMessage()));
            LOG.error("baseline failed", e);
            return 0;
        }
    }

    private static int compare(CommandSourceStack src, String beforeFile, String id, ResourceLocation dim, int minX, int minZ, int maxX, int maxZ) {
        MinecraftServer server = src.getServer();
        Path beforePath = server.getServerDirectory().toPath().resolve("bigbangexpeditions/baselines").resolve(beforeFile);
        if (!Files.exists(beforePath)) {
            // also try absolute
            beforePath = Path.of(beforeFile);
        }
        if (!Files.exists(beforePath)) {
            src.sendFailure(Component.literal("beforeFile not found: " + beforeFile + " (look in bigbangexpeditions/baselines/)"));
            return 0;
        }
        try {
            BaselineData before = BaselineService.readBaseline(beforePath);
            SectorBounds bounds = new SectorBounds(id, dim, minX, minZ, maxX, maxZ);
            ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dim));
            SectorProbeResult probe = BaselineService.probe(server, level, bounds);
            BaselineData after = BaselineService.toBaseline(server, level, probe);
            String diff = BaselineService.compare(before, after);
            src.sendSuccess(() -> Component.literal(diff), false);
            LOG.info("[compare] {} vs {}", beforePath, bounds);
            return 1;
        } catch (Exception e) {
            src.sendFailure(Component.literal("compare failed: " + e.getMessage()));
            LOG.error("compare failed", e);
            return 0;
        }
    }
}
