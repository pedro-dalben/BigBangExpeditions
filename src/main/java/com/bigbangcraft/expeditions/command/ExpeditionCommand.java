package com.bigbangcraft.expeditions.command;

import com.bigbangcraft.expeditions.diagnostics.DoctorReport;
import com.bigbangcraft.expeditions.diagnostics.DoctorService;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class ExpeditionCommand {
    private static final Logger LOG = LogManager.getLogger("BigBangExpeditions/Command");

    private ExpeditionCommand() {}

    /** Operator diagnostics subtree attached to the single /expedition root (Goal 04). */
    public static void addTo(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("doctor").requires(s -> s.hasPermission(2)).executes(ctx -> doctor(ctx.getSource())))
                .then(Commands.literal("world").requires(s -> s.hasPermission(2)).executes(ctx -> world(ctx.getSource())));
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

}
