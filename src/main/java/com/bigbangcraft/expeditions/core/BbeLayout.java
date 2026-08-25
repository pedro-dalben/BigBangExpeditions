package com.bigbangcraft.expeditions.core;

import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;

/**
 * Single source of truth for on-disk layout. Everything BBE persists outside
 * the world directory so regeneration can never destroy operational evidence.
 */
public final class BbeLayout {
    private BbeLayout() {}

    public static Path root(MinecraftServer server) {
        return server.getServerDirectory().toPath().resolve("bigbangexpeditions");
    }

    public static Path sectorsFile(MinecraftServer server) {
        return root(server).resolve("sectors.json");
    }

    public static Path lifecycleFile(MinecraftServer server) {
        return root(server).resolve("lifecycle.json");
    }

    public static Path journalDir(MinecraftServer server) {
        return root(server).resolve("journal");
    }

    public static Path auditDir(MinecraftServer server) {
        return root(server).resolve("audit");
    }

    public static Path plansDir(MinecraftServer server) {
        return root(server).resolve("reset-plans");
    }

    public static Path authLedgerFile(MinecraftServer server) {
        return root(server).resolve("authorization-ledger.json");
    }

    public static Path locksDir(MinecraftServer server) {
        return root(server).resolve("locks");
    }

    public static Path baselinesDir(MinecraftServer server) {
        return root(server).resolve("baselines");
    }

    public static Path backupsRoot(MinecraftServer server) {
        return root(server).resolve("backups");
    }

    public static Path telemetryDir(MinecraftServer server) {
        return root(server).resolve("telemetry");
    }

    public static Path cycleArchiveFile(MinecraftServer server) {
        return root(server).resolve("cycle-history.json");
    }

    public static Path automationStateFile(MinecraftServer server) {
        return root(server).resolve("automation-state.json");
    }

    public static Path configDir(MinecraftServer server) {
        return server.getServerDirectory().toPath().resolve("config").resolve("bigbangexpeditions");
    }
}
