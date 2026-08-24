package com.bigbangcraft.expeditions.gameplay;

import com.bigbangcraft.expeditions.i18n.Translations;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Server-wide announcement adapter (Goal 04).
 * Chat + action bar + optional sound. Titles deliberately avoided: the pack's
 * HUD mods (Xaero, status effects) already compete for screen space.
 */
public final class Broadcast {
    private static final Logger LOG = LogManager.getLogger("BigBangExpeditions/Broadcast");

    private Broadcast() {}

    public static void announce(MinecraftServer server, GameplayConfig config,
                                String key, Object... args) {
        if (config != null && !config.announcementsEnabled()) return;
        Component line = Component.literal(Translations.t(key, args));
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(line);
            p.displayClientMessage(line, true); // action bar mirror
        }
    }

    public static void playAlarm(MinecraftServer server, GameplayConfig config, SoundEvent sound) {
        if (config == null || !config.soundEnabled() || sound == null) return;
        try {
            var level = server.overworld();
            var spawn = level.getSharedSpawnPos();
            // positional at world spawn reaches everyone nearby; also ping each player directly
            level.playSound(null, spawn, sound, SoundSource.MASTER, 0.8f, 1.0f);
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                p.level().playSound(null, p.blockPosition(), sound, SoundSource.MASTER, 0.6f, 1.0f);
            }
        } catch (Exception e) {
            LOG.warn("alarm playback failed: {}", e.toString());
        }
    }
}
