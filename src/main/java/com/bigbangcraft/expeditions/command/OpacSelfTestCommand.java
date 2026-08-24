package com.bigbangcraft.expeditions.command;

import com.bigbangcraft.expeditions.integration.lostcities.LostCitiesAdapter;
import com.bigbangcraft.expeditions.integration.opac.OpacAdapter;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * /expedition opac selftest [chunkX chunkZ]
 *
 * Attempts a REAL API-level personal claim inside the expedition dimension
 * using a deterministic test UUID, then verifies it did not stick.
 * This is the Phase 6 negative-path evidence generator: OPAC must refuse.
 * Any unexpected success is reported as FAILURE and immediately unclaimed.
 */
public final class OpacSelfTestCommand {
    private static final Logger LOG = LogManager.getLogger("BigBangExpeditions/OpacSelfTest");
    /** Deterministic, obviously-fake UUID so real players can never collide. */
    private static final UUID TEST_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b8");

    private OpacSelfTestCommand() {}

    public static void addTo(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("opac").requires(s -> s.hasPermission(2))
                        .then(Commands.literal("selftest")
                                .executes(ctx -> run(ctx.getSource(), 0, 0))
                                .then(Commands.argument("chunkX", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                        .then(Commands.argument("chunkZ", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                                .executes(ctx -> run(ctx.getSource(),
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "chunkX"),
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "chunkZ")))))));
    }

    private static int run(CommandSourceStack src, int chunkX, int chunkZ) {
        MinecraftServer server = src.getServer();
        ResourceLocation dim = LostCitiesAdapter.expeditionDimensionId();
        List<String> failures = new ArrayList<>();

        if (!OpacAdapter.isOpacPresent()) {
            src.sendFailure(Component.literal("REFUSED: OPAC not present"));
            return 0;
        }
        Object manager = resolve(server);
        if (manager == null) {
            src.sendFailure(Component.literal("REFUSED: OPAC claims manager unavailable"));
            return 0;
        }

        // 1. dimension-level gate
        Boolean claimable = OpacAdapter.isDimensionClaimable(server, dim);
        src.sendSuccess(() -> Component.literal("isClaimable(" + dim + ") = " + claimable), false);
        if (claimable == null) failures.add("isClaimable undeterminable");
        else if (claimable) failures.add("dimension is CLAIMABLE — config isolation missing");

        // 2. validated claim attempt via tryToClaim (the path players trigger)
        //    Signature: tryToClaim(dim, uuid, chunkX, chunkZ, y, maxY, maxChunks, assumeTop)
        Object result = null;
        String type = null;
        try {
            Method m = manager.getClass().getMethod("tryToClaim", ResourceLocation.class, UUID.class,
                    int.class, int.class, int.class, int.class, int.class, boolean.class);
            result = m.invoke(manager, dim, TEST_UUID, chunkX, chunkZ, 319, 319, 1, false);
            if (result != null) {
                Method getType = result.getClass().getMethod("getResultType");
                Object t = getType.invoke(result);
                type = t == null ? "null" : t.toString();
            }
        } catch (Throwable t) {
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
            failures.add("tryToClaim reflection failed: " + cause);
        }
        final String fType = type;
        src.sendSuccess(() -> Component.literal("tryToClaim -> " + (fType == null ? "<no result>" : fType)), false);
        if (fType == null || fType.contains("SUCCESSFUL_CLAIM")) {
            failures.add("validated claim attempt NOT refused (" + fType + ") — isolation BROKEN");
            try {
                Method unclaim = findMethod(manager.getClass(), "unclaim",
                        new Class<?>[]{ResourceLocation.class, int.class, int.class});
                if (unclaim != null) unclaim.invoke(manager, dim, chunkX, chunkZ);
            } catch (Throwable ignore) {}
        } else {
            src.sendSuccess(() -> Component.literal("CLAIM REFUSED — Expedition territory is non-persistent."), false);
        }

        // 2b. raw admin claim() bypass check — documents that OPAC's unvalidated
        //     internal claim() ignores the dimension gate. Players cannot reach
        //     this path (commands go through tryToClaim), but other mods could.
        boolean adminBypassWorked = false;
        try {
            Method claim = findMethod(manager.getClass(), "claim",
                    new Class<?>[]{ResourceLocation.class, UUID.class, int.class, int.class, int.class, boolean.class});
            if (claim != null) {
                Object r = claim.invoke(manager, dim, TEST_UUID, chunkX, chunkZ, 319, true);
                adminBypassWorked = r != null;
                Method unclaim = findMethod(manager.getClass(), "unclaim",
                        new Class<?>[]{ResourceLocation.class, int.class, int.class});
                if (adminBypassWorked && unclaim != null) unclaim.invoke(manager, dim, chunkX, chunkZ);
            }
        } catch (Throwable ignore) {}
        final boolean fBypass = adminBypassWorked;
        src.sendSuccess(() -> Component.literal("raw claim() admin path bypasses gate: " + fBypass
                + (fBypass ? " (documented OPAC internals risk)" : "")), false);

        // 3. verify nothing persisted
        boolean persisted = chunkClaimed(manager, dim, chunkX, chunkZ);
        src.sendSuccess(() -> Component.literal("post-attempt lookup claimed=" + persisted), false);
        if (persisted) failures.add("claim state persisted after attempt at " + chunkX + "," + chunkZ);

        if (failures.isEmpty()) {
            src.sendSuccess(() -> Component.literal("OPAC SELFTEST PASS — expedition claims refused"), false);
            LOG.info("[opac-selftest] PASS dim={} chunk={}", dim, chunkX + "," + chunkZ);
            return 1;
        }
        for (String f : failures) src.sendFailure(Component.literal("FAIL: " + f));
        LOG.error("[opac-selftest] FAILED: {}", failures);
        return 0;
    }

    private static Object resolve(MinecraftServer server) {
        try {
            Class<?> apiClass = Class.forName("xaero.pac.common.server.api.OpenPACServerAPI");
            Method get = apiClass.getMethod("get", MinecraftServer.class);
            Object api = get.invoke(null, server);
            return apiClass.getMethod("getServerClaimsManager").invoke(api);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean chunkClaimed(Object managerApi, ResourceLocation dim, int cx, int cz) {
        try {
            Method m = managerApi.getClass().getMethod("get", ResourceLocation.class, int.class, int.class);
            return m.invoke(managerApi, dim, cx, cz) != null;
        } catch (Throwable t) {
            return true; // fail closed
        }
    }

    private static Method findMethod(Class<?> c, String name, Class<?>[] paramTypes) {
        try {
            return c.getMethod(name, paramTypes);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
