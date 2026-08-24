package com.bigbangcraft.expeditions;

import com.bigbangcraft.expeditions.command.DimensionStatusCommand;
import com.bigbangcraft.expeditions.command.ExpeditionCommand;
import com.bigbangcraft.expeditions.command.JourneyCommand;
import com.bigbangcraft.expeditions.command.LifecycleCommand;
import com.bigbangcraft.expeditions.command.OpacSelfTestCommand;
import com.bigbangcraft.expeditions.command.SectorCommand;
import com.bigbangcraft.expeditions.core.RuntimeServices;
import com.bigbangcraft.expeditions.core.StartupGate;
import com.bigbangcraft.expeditions.gameplay.DimensionTravelGate;
import com.bigbangcraft.expeditions.lifecycle.EvacuationService;
import net.minecraft.commands.Commands;
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
        MinecraftForge.EVENT_BUS.register(new StartupGate());
        MinecraftForge.EVENT_BUS.register(DimensionTravelGate.class);
        LOG.info("BigBangExpeditions init — lifecycle-aware expedition management");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent e) {
        // Goal 04: exactly ONE /expedition root — every subtree attaches here so
        // permission requirements are explicit per branch (player 0, operator 2/3).
        var root = Commands.literal("expedition");
        JourneyCommand.addTo(root);          // perm 0: status/enter/leave/where
        ExpeditionCommand.addTo(root);       // perm 2: doctor/world
        DimensionStatusCommand.addTo(root);  // perm 2: dimension status
        OpacSelfTestCommand.addTo(root);     // perm 2: opac selftest
        SectorCommand.addTo(root);           // perm 2: sector registry ops
        LifecycleCommand.addTo(root);        // perm 2/3: production lifecycle
        e.getDispatcher().register(root);
        LOG.info("Registered /expedition command tree");
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
