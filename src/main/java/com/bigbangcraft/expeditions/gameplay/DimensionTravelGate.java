package com.bigbangcraft.expeditions.gameplay;

import com.bigbangcraft.expeditions.core.RuntimeServices;
import com.bigbangcraft.expeditions.integration.lostcities.LostCitiesAdapter;
import com.bigbangcraft.expeditions.lifecycle.EntryDecision;
import com.bigbangcraft.expeditions.lifecycle.LifecycleState;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.EntityTravelToDimensionEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/**
 * Goal 04 access gate for ALTERNATE travel routes.
 *
 * `/expedition enter` is not the only way a player can end up inside the
 * expedition dimension: nether-portal round trips started inside the zone,
 * Wormhole portals and any other dimension-travel mechanic funnel through
 * {@link EntityTravelToDimensionEvent}. Every such arrival is refused unless
 * the lifecycle is explicitly OPEN — the same decision the command applies.
 *
 * Admin routes (`/execute in …`, operator teleport) run with elevated
 * permission and remain available by design.
 */
public final class DimensionTravelGate {
    private static final Logger LOG = LogManager.getLogger("BigBangExpeditions/TravelGate");

    /** Last refusal per player, used to avoid audit-log spam from portal ticks. */
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> LAST_REFUSAL =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long REFUSAL_QUIET_MS = 5_000;

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onTravel(EntityTravelToDimensionEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer player)) return;
        if (!LostCitiesAdapter.expeditionDimensionId().equals(e.getDimension())) return;

        LifecycleState state;
        try {
            state = RuntimeServices.get(player.getServer()).lifecycle().current().status;
        } catch (IOException ex) {
            // fail-closed: unreadable lifecycle blocks arrival like any closed state
            refuse(player, "unreadable lifecycle", "fail-closed");
            e.setCanceled(true);
            return;
        }

        var decision = EntryDecision.check(state);
        if (decision.allowed) return;

        refuse(player, state.name(), decision.reason);
        e.setCanceled(true);
    }

    private static void refuse(ServerPlayer player, String stateName, String reason) {
        String name = player.getName().getString();
        long now = System.currentTimeMillis();
        Long last = LAST_REFUSAL.get(name);
        if (last == null || now - last >= REFUSAL_QUIET_MS) {
            LAST_REFUSAL.put(name, now);
            player.sendSystemMessage(Component.literal(
                    com.bigbangcraft.expeditions.i18n.Translations.t("bbe.entry.blocked.closing")));
            RuntimeServices.get(player.getServer()).auditRefusal("TRAVEL_GATE", name,
                    "dimension arrival refused while " + reason);
            LOG.info("[travel-gate] {} refused arrival ({})", name, stateName);
        }
    }
}
