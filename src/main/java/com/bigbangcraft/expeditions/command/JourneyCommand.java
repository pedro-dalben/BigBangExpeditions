package com.bigbangcraft.expeditions.command;

import com.bigbangcraft.expeditions.core.BbeLayout;
import com.bigbangcraft.expeditions.core.RuntimeServices;
import com.bigbangcraft.expeditions.gameplay.ExpeditionAccessService;
import com.bigbangcraft.expeditions.i18n.Translations;
import com.bigbangcraft.expeditions.integration.lostcities.LostCitiesAdapter;
import com.bigbangcraft.expeditions.lifecycle.LifecycleRecord;
import com.bigbangcraft.expeditions.lifecycle.PlayerStateMapper;
import com.bigbangcraft.expeditions.sector.SectorLocator;
import com.bigbangcraft.expeditions.sector.SectorRegistry;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;

/**
 * Goal 04 player journey: status / enter / leave / where.
 *
 * Permission 0 — every survivor. This is the ONLY player-facing surface; all
 * operational/diagnostic vocabulary stays behind operator permission levels
 * (2/3) and is never exposed here.
 */
public final class JourneyCommand {

    private JourneyCommand() {}

    /** Player-facing subtree attached to the single /expedition root. */
    public static void addTo(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("enter")
                        .executes(ctx -> enter(ctx.getSource().getPlayerOrException())))
                .then(Commands.literal("leave")
                        .executes(ctx -> leave(ctx.getSource().getPlayerOrException())))
                .then(Commands.literal("where")
                        .executes(ctx -> where(ctx.getSource().getPlayerOrException())));
    }

    private static int status(CommandSourceStack src) {
        try {
            LifecycleRecord r = RuntimeServices.get(src.getServer()).lifecycle().current();
            String phrase = Translations.t(PlayerStateMapper.phraseKey(r.status));
            src.sendSuccess(() -> Component.literal(
                    Translations.t("bbe.status.line", phrase)), false);
            src.sendSuccess(() -> Component.literal(
                    Translations.t("bbe.status.generation", r.generation)), false);
            if (r.status != com.bigbangcraft.expeditions.lifecycle.LifecycleState.OPEN) {
                src.sendSuccess(() -> Component.literal(
                        Translations.t("bbe.entry.blocked.unavailable")), false);
            }
            return 1;
        } catch (IOException e) {
            src.sendFailure(Component.literal(Translations.t("bbe.error.lifecycle_unreadable")));
            return 0;
        }
    }

    private static int enter(ServerPlayer player) {
        ExpeditionAccessService.enter(player);
        return 1;
    }

    private static int leave(ServerPlayer player) {
        ExpeditionAccessService.leave(player);
        return 1;
    }

    private static int where(ServerPlayer player) {
        if (player.level().dimension() != LostCitiesAdapter.expeditionDimensionKey()) {
            ExpeditionAccessService.send(player, "bbe.where.not_inside");
            return 0;
        }
        int chunkX = player.chunkPosition().x;
        int chunkZ = player.chunkPosition().z;
        SectorRegistry registry = new SectorRegistry(BbeLayout.sectorsFile(player.getServer()));
        String district = SectorLocator.locate(registry.list(),
                LostCitiesAdapter.expeditionDimensionId().toString(), chunkX, chunkZ)
                .map(r -> r.displayName == null || r.displayName.isBlank() ? r.id : r.displayName)
                .orElseGet(() -> Translations.t("bbe.where.wilderness"));
        ExpeditionAccessService.send(player, "bbe.where.format",
                district,
                (int) Math.floor(player.getX()),
                (int) Math.floor(player.getY()),
                (int) Math.floor(player.getZ()));
        return 1;
    }
}
