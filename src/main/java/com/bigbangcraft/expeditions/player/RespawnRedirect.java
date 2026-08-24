package com.bigbangcraft.expeditions.player;

import com.bigbangcraft.expeditions.core.RuntimeServices;
import com.bigbangcraft.expeditions.gameplay.ExpeditionAccessService;
import com.bigbangcraft.expeditions.integration.lostcities.LostCitiesAdapter;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerSetSpawnEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Goal 04 death & respawn policy.
 *
 * Hardcore philosophy preserved: dying inside the expedition loses your gear
 * exactly like anywhere else (Corpse mod handles the body). What is NOT
 * allowed is turning disposable territory into a respawn anchor:
 *
 * 1. {@link PlayerSetSpawnEvent} — setting spawn via bed/sleeping bag inside
 *    the expedition is cancelled (sleeping still skips the night).
 * 2. {@link PlayerRespawnEvent} — safety net: any respawn that lands inside
 *    the expedition (legacy spawn points, /setspawn variants) is redirected
 *    to the persistent world immediately, including during maintenance.
 */
public final class RespawnRedirect {
    private static final Logger LOG = LogManager.getLogger("BigBangExpeditions/Respawn");

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onSetSpawn(PlayerSetSpawnEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer player)) return;
        ResourceKey<Level> target = e.getSpawnLevel();
        if (target == null || !LostCitiesAdapter.expeditionDimensionKey().equals(target)) return;
        e.setCanceled(true);
        ExpeditionAccessService.send(player, "bbe.death.respawn_redirected");
        LOG.info("[respawn] spawn-in-expedition denied for {}", player.getName().getString());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().dimension() != LostCitiesAdapter.expeditionDimensionKey()) return;

        // Stale anchor or admin-spawned position inside expedition: recover.
        var services = RuntimeServices.get(player.getServer());
        SessionRecovery.recoverAfterDeath(player, services);
    }
}
