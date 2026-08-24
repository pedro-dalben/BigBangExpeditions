package com.bigbangcraft.expeditions.command;

import com.bigbangcraft.expeditions.core.BbeLayout;
import com.bigbangcraft.expeditions.core.RuntimeServices;
import com.bigbangcraft.expeditions.gameplay.GameplayConfig;
import com.bigbangcraft.expeditions.integration.lostcities.LostCitiesAdapter;
import com.bigbangcraft.expeditions.lifecycle.LifecycleRecord;
import com.bigbangcraft.expeditions.lifecycle.LifecycleState;
import com.bigbangcraft.expeditions.lifecycle.PlayerStateMapper;
import com.bigbangcraft.expeditions.sector.SectorLocator;
import com.bigbangcraft.expeditions.sector.SectorRegistry;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Goal 04 gameplay administration: /expedition ops …
 *
 * Read-only situational awareness at permission 2; player-affecting actions
 * (evacuate) at permission 3. NOTHING here mutates the lifecycle or resets —
 * production safety stays exactly where Goal 03 put it.
 */
public final class OpsCommand {

    private OpsCommand() {}

    public static void addTo(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("ops").requires(s -> s.hasPermission(2))
                .then(Commands.literal("players").executes(ctx -> players(ctx.getSource())))
                .then(Commands.literal("countdown").executes(ctx -> countdown(ctx.getSource())))
                .then(Commands.literal("config").executes(ctx -> config(ctx.getSource())))
                .then(Commands.literal("evacuate")
                        .requires(s -> s.hasPermission(3))
                        .then(Commands.argument("player",
                                        net.minecraft.commands.arguments.EntityArgument.player())
                                .executes(ctx -> evacuateOne(ctx.getSource(),
                                        net.minecraft.commands.arguments.EntityArgument.getPlayer(ctx, "player"))))));
    }

    private record Row(String name, String district, int x, int y, int z) {}

    private static int players(CommandSourceStack src) {
        var level = src.getServer().getLevel(LostCitiesAdapter.expeditionDimensionKey());
        List<String> inside = com.bigbangcraft.expeditions.lifecycle.EvacuationService.playersInside(level);
        if (inside.isEmpty()) {
            src.sendSuccess(() -> Component.literal(
                    com.bigbangcraft.expeditions.i18n.Translations.t("bbe.admin.none_inside")), false);
            return 0;
        }
        Map<String, Integer> distribution = new LinkedHashMap<>();
        try {
            LifecycleRecord rec = RuntimeServices.get(src.getServer()).lifecycle().current();
            src.sendSuccess(() -> Component.literal("state: " + PlayerStateMapper.phraseKey(rec.status)
                    + " (technical: " + rec.status + ")"), false);
        } catch (IOException ignored) {
        }
        SectorRegistry registry = new SectorRegistry(BbeLayout.sectorsFile(src.getServer()));
        String dim = LostCitiesAdapter.expeditionDimensionId().toString();
        for (ServerPlayer p : playersIn(level)) {
            String district = SectorLocator.locate(registry.list(), dim,
                            p.chunkPosition().x, p.chunkPosition().z)
                    .map(r -> r.displayName == null || r.displayName.isBlank() ? r.id : r.displayName)
                    .orElseGet(() -> "?");
            distribution.merge(district, 1, Integer::sum);
            final String line = String.format("- %s @ [%d, %d, %d] district=%s",
                    p.getName().getString(),
                    (int) p.getX(), (int) p.getY(), (int) p.getZ(), district);
            src.sendSuccess(() -> Component.literal(line), false);
        }
        src.sendSuccess(() -> Component.literal(
                com.bigbangcraft.expeditions.i18n.Translations.t("bbe.admin.players_inside",
                        inside.size())), false);
        src.sendSuccess(() -> Component.literal("distribution: " + distribution), false);
        return inside.size();
    }

    private static int countdown(CommandSourceStack src) {
        try {
            LifecycleRecord r = RuntimeServices.get(src.getServer()).lifecycle().current();
            if (r.status != LifecycleState.CLOSING || r.closingDeadlineEpochMs <= 0) {
                src.sendSuccess(() -> Component.literal("No closing scheduled (state: " + r.status + ")."), false);
                return 0;
            }
            long remaining = r.closingDeadlineEpochMs - System.currentTimeMillis();
            src.sendSuccess(() -> Component.literal(String.format(
                    "Extraction in %d min %d sec (%s).",
                    remaining / 60000, (remaining % 60000) / 1000, r.closingDeadlineEpochMs)), false);
            return 1;
        } catch (IOException e) {
            src.sendFailure(Component.literal("lifecycle unreadable: " + e.getMessage()));
            return 0;
        }
    }

    private static int config(CommandSourceStack src) {
        GameplayConfig c = GameplayConfig.load(
                com.bigbangcraft.expeditions.core.BbeLayout.configDir(src.getServer()).resolve("gameplay.properties"));
        for (var e : c.snapshot().entrySet()) {
            final String line = e.getKey() + " = " + e.getValue();
            src.sendSuccess(() -> Component.literal(line), false);
        }
        for (String n : c.notices()) src.sendFailure(Component.literal("NOTICE: " + n));
        return 1;
    }

    private static int evacuateOne(CommandSourceStack src, ServerPlayer target) {
        if (target.level().dimension() != LostCitiesAdapter.expeditionDimensionKey()) {
            src.sendFailure(Component.literal(target.getName().getString()
                    + " is not inside the expedition."));
            return 0;
        }
        MinecraftServer server = src.getServer();
        var services = RuntimeServices.get(server);
        com.bigbangcraft.expeditions.player.SessionRecovery.markTransferStart(target);
        com.bigbangcraft.expeditions.gameplay.ExpeditionAccessService.teleportToFallbackSpawn(server, target);
        com.bigbangcraft.expeditions.player.SessionRecovery.markTransferDone(target);
        com.bigbangcraft.expeditions.gameplay.ExpeditionAccessService.clearReturnData(target);
        com.bigbangcraft.expeditions.gameplay.ExpeditionAccessService.send(target,
                "bbe.login.evicted.maintenance");
        try {
            services.audit().record(com.bigbangcraft.expeditions.audit.AuditEvent
                    .of("PLAYER_EVACUATED", src.getTextName())
                    .subject(target.getName().getString()).outcome("OK").detail("mode", "ADMIN_EVACUATE"));
        } catch (Exception ignored) {
        }
        src.sendSuccess(() -> Component.literal(
                target.getName().getString() + " extracted to the persistent world."), false);
        return 1;
    }

    private static List<ServerPlayer> playersIn(net.minecraft.server.level.ServerLevel level) {
        return level == null ? List.of() : level.players();
    }
}
