package com.bigbangcraft.expeditions.integration.lostcities;

import com.bigbangcraft.expeditions.BigBangExpeditions;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;

/**
 * Registers bigbangexpeditions:expedition -> deceasedcraft_onlycities with the
 * Lost Cities runtime (Config.registerLostCityDimension) at server start.
 *
 * Belt-and-braces with the staging config entry in dimensionsWithProfiles.
 * Fail-closed: any reflection failure is logged and leaves behavior unchanged;
 * /expedition dimension status will then report the missing profile instead of
 * assuming city generation works.
 */
@Mod.EventBusSubscriber(modid = BigBangExpeditions.MODID)
public final class LostCitiesRegistration {
    private static final Logger LOG = LogManager.getLogger("BigBangExpeditions/LostCities");
    private static final String CONFIG_CLASS = "mcjty.lostcities.setup.Config";

    private LostCitiesRegistration() {}

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        MinecraftServer server = event.getServer();
        boolean ok = register(server);
        if (ok) {
            LOG.info("Registered expedition dimension with Lost Cities: {} -> {}",
                    LostCitiesAdapter.expeditionDimensionId(), LostCitiesAdapter.EXPECTED_EXPEDITION_PROFILE);
        } else {
            LOG.warn("Lost Cities registration skipped/unavailable — expedition dimension may lack city generation; check /expedition dimension status");
        }
    }

    static boolean register(MinecraftServer server) {
        if (!LostCitiesAdapter.isAvailable()) return false;
        try {
            Class<?> c = Class.forName(CONFIG_CLASS);
            Method m = c.getMethod("registerLostCityDimension", ResourceKey.class, String.class);
            m.setAccessible(true);
            m.invoke(null, LostCitiesAdapter.expeditionDimensionKey(), LostCitiesAdapter.EXPECTED_EXPEDITION_PROFILE);
            return true;
        } catch (Throwable t) {
            LOG.error("registerLostCityDimension failed: {}", t.toString());
            return false;
        }
    }
}
