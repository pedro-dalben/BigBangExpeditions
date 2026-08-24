package com.bigbangcraft.expeditions;

import com.bigbangcraft.expeditions.command.DimensionStatusCommand;
import com.bigbangcraft.expeditions.command.ExpeditionCommand;
import com.bigbangcraft.expeditions.command.ExpeditionTeleportCommand;
import com.bigbangcraft.expeditions.command.LifecycleCommand;
import com.bigbangcraft.expeditions.command.OpacSelfTestCommand;
import com.bigbangcraft.expeditions.command.SectorCommand;
import com.bigbangcraft.expeditions.core.RuntimeServices;
import com.bigbangcraft.expeditions.lifecycle.EvacuationService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod("bigbangexpeditions")
public class BigBangExpeditions {
    public static final String MODID = "bigbangexpeditions";
    private static final Logger LOG = LogManager.getLogger(MODID);

    public BigBangExpeditions() {
        MinecraftForge.EVENT_BUS.register(this);
        LOG.info("BigBangExpeditions init — lifecycle-aware expedition management");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent e) {
        ExpeditionCommand.register(e.getDispatcher());
        DimensionStatusCommand.register(e.getDispatcher());
        ExpeditionTeleportCommand.register(e.getDispatcher());
        OpacSelfTestCommand.register(e.getDispatcher());
        SectorCommand.register(e.getDispatcher());
        LifecycleCommand.register(e.getDispatcher());
        LOG.info("Registered /expedition commands");
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer player) {
            EvacuationService.onJoin(player, RuntimeServices.get(player.getServer()));
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent e) {
        RuntimeServices.reset();
    }
}
