package com.bigbangcraft.expeditions.command;

import com.bigbangcraft.expeditions.integration.lostcities.LostCitiesAdapter;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * /expedition dimension status [dimId]
 * Read-only diagnostics for the expedition dimension (Goal 02 Phase 3).
 */
public final class DimensionStatusCommand {
    private static final Logger LOG = LogManager.getLogger("BigBangExpeditions/DimensionStatus");

    private DimensionStatusCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("expedition")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("dimension")
                        .then(Commands.literal("status")
                                .executes(ctx -> status(ctx.getSource(), LostCitiesAdapter.expeditionDimensionId()))
                                .then(Commands.argument("dimId", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                        .executes(ctx -> status(ctx.getSource(),
                                                net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "dimId")))))));
    }

    private static int status(CommandSourceStack src, ResourceLocation dimId) {
        MinecraftServer server = src.getServer();
        List<String> warnings = new ArrayList<>();

        ServerLevel level = server.getLevel(
                net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimId));

        src.sendSuccess(() -> Component.literal("=== Expedition dimension status ==="), false);
        src.sendSuccess(() -> Component.literal("dimension: " + dimId + (level != null ? " [AVAILABLE]" : " [UNAVAILABLE]")), false);

        if (level == null) {
            warnings.add("dimension not loaded — datapack JSON missing or world not regenerated since install");
            emit(src, warnings);
            return 0;
        }

        // dimension type
        String typeId = level.dimensionTypeRegistration().unwrapKey()
                .map(k -> k.location().toString()).orElse("unknown");
        final String fTypeId = typeId;
        src.sendSuccess(() -> Component.literal("dimension_type: " + fTypeId), false);

        // seed
        try {
            long seed = level.getSeed();
            src.sendSuccess(() -> Component.literal("seed hash: " + Long.toHexString(seed)), false);
            long overworldSeed = server.overworld().getSeed();
            if (seed != overworldSeed) {
                warnings.add(String.format("seed mismatch vs overworld (%s vs %s) — LC city layout may diverge from expectations",
                        Long.toHexString(seed), Long.toHexString(overworldSeed)));
            }
        } catch (Exception e) {
            warnings.add("seed unavailable: " + e.getMessage());
        }

        // Lost Cities
        boolean lcAvailable = LostCitiesAdapter.isAvailable();
        Optional<String> profile = LostCitiesAdapter.getProfile(level);
        if (!lcAvailable) {
            warnings.add("Lost Cities mod NOT available — no city generation possible");
        } else if (profile.isEmpty()) {
            warnings.add("no Lost Cities profile configured for this dimension — city generation INACTIVE");
        } else {
            final String p = profile.get();
            src.sendSuccess(() -> Component.literal("Lost Cities: ACTIVE profile=" + p), false);
            if (!p.equals(LostCitiesAdapter.EXPECTED_EXPEDITION_PROFILE)) {
                warnings.add("profile '" + p + "' differs from expected '"
                        + LostCitiesAdapter.EXPECTED_EXPEDITION_PROFILE + "'");
            }
            Path configDir = server.getServerDirectory().toPath().resolve("config/lostcities");
            Optional<String> fp = LostCitiesAdapter.getProfileFingerprint(configDir, p);
            src.sendSuccess(() -> Component.literal("profile fingerprint: "
                    + fp.orElse("<unavailable — profile file missing/unreadable>") ), false);
            if (fp.isEmpty()) {
                warnings.add("profile fingerprint unavailable for '" + p + "' (expected config/lostcities/profiles/" + p + ".json)");
            }
        }

        // OPAC
        boolean opac = com.bigbangcraft.expeditions.integration.opac.OpacAdapter.isOpacPresent();
        src.sendSuccess(() -> Component.literal("OPAC: " + (opac ? "present" : "NOT present")), false);
        if (!opac) {
            warnings.add("OPAC not present — claim prohibition cannot be verified");
        } else {
            Boolean claimable = com.bigbangcraft.expeditions.integration.opac.OpacAdapter
                    .isDimensionClaimable(server, dimId);
            if (claimable == null) {
                warnings.add("OPAC isClaimable(" + dimId + ") could not be determined — claims isolation UNVERIFIED");
            } else if (claimable) {
                warnings.add("OPAC reports expedition dimension CLAIMABLE — isolation NOT enforced!");
            } else {
                src.sendSuccess(() -> Component.literal("OPAC claims in dimension: PROHIBITED (unclaimable)"), false);
            }
        }

        // world folder path (safely derivable)
        Path worldFolder = server.getServerDirectory().toPath()
                .resolve("world/dimensions")
                .resolve(dimId.getNamespace()).resolve(dimId.getPath());
        src.sendSuccess(() -> Component.literal("world folder: " + worldFolder
                + (Files.isDirectory(worldFolder.resolve("region")) ? " [generated]" : " [no region data yet]")), false);

        emit(src, warnings);
        LOG.info("[dimension-status] dim={} type={} lc={} profile={}", dimId, typeId, lcAvailable, profile.orElse("-"));
        return 1;
    }

    private static void emit(CommandSourceStack src, List<String> warnings) {
        for (String w : warnings) {
            src.sendSuccess(() -> Component.literal("WARN: " + w), false);
        }
        src.sendSuccess(() -> Component.literal(warnings.isEmpty()
                ? "No warnings." : warnings.size() + " warning(s)."), false);
    }
}
