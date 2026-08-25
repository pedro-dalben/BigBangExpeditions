package com.bigbangcraft.expeditions.command;

import com.bigbangcraft.expeditions.automation.AutomationConfig;
import com.bigbangcraft.expeditions.automation.AutomationService;
import com.bigbangcraft.expeditions.automation.SchedulerMath;
import com.bigbangcraft.expeditions.core.BbeLayout;
import com.bigbangcraft.expeditions.telemetry.CycleArchive;
import com.bigbangcraft.expeditions.telemetry.CycleArchiveStore;
import com.bigbangcraft.expeditions.telemetry.TelemetrySnapshot;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.time.Instant;

/**
 * Goal 05 administration surface: /expedition automation …
 *
 * Read-only situational awareness (status/explain/history/shadow/dryrun) at
 * permission 2; every mutating override (pause/resume/postpone/cancel/
 * approve/reload/clock-clear/evaluate) at permission 3 and audited by the
 * service layer. Nothing here bypasses lifecycle gating or reset safety.
 */
public final class AutomationCommand {

    private AutomationCommand() {}

    public static void addTo(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("automation").requires(s -> s.hasPermission(2))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("explain").executes(ctx -> explain(ctx.getSource())))
                .then(Commands.literal("history").executes(ctx -> history(ctx.getSource())))
                .then(Commands.literal("shadow").executes(ctx -> shadow(ctx.getSource())))
                .then(Commands.literal("dryrun").executes(ctx -> dryrun(ctx.getSource())))
                .then(Commands.literal("evaluate").requires(s -> s.hasPermission(3))
                        .executes(ctx -> evaluate(ctx.getSource())))
                .then(Commands.literal("pause").requires(s -> s.hasPermission(3))
                        .then(Commands.argument("reason", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                .executes(ctx -> pause(ctx.getSource(),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "reason")))))
                .then(Commands.literal("resume").requires(s -> s.hasPermission(3))
                        .executes(ctx -> resume(ctx.getSource())))
                .then(Commands.literal("postpone").requires(s -> s.hasPermission(3))
                        .then(Commands.argument("days", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 365))
                                .executes(ctx -> postpone(ctx.getSource(),
                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "days")))))
                .then(Commands.literal("cancel").requires(s -> s.hasPermission(3))
                        .executes(ctx -> cancel(ctx.getSource())))
                .then(Commands.literal("approve").requires(s -> s.hasPermission(3))
                        .executes(ctx -> approve(ctx.getSource())))
                .then(Commands.literal("reload").requires(s -> s.hasPermission(3))
                        .executes(ctx -> reload(ctx.getSource())))
                .then(Commands.literal("clock-clear").requires(s -> s.hasPermission(3))
                        .executes(ctx -> clockClear(ctx.getSource())))
                .then(Commands.literal("seed-sim").requires(s -> s.hasPermission(3))
                        .then(Commands.argument("chunks", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                                .then(Commands.argument("opens", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                                        .then(Commands.argument("deaths", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                                                .then(Commands.argument("structures", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                                                        .executes(ctx -> seedSim(ctx.getSource(),
                                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "chunks"),
                                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "opens"),
                                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "deaths"),
                                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "structures")))))))));
    }

    // ---------------------------------------------------------------- read

    private static int status(CommandSourceStack src) {
        AutomationService svc = AutomationService.get();
        if (svc == null || AutomationService.config() == null) {
            src.sendFailure(Component.literal("automation unavailable this session"));
            return 0;
        }
        AutomationConfig c = AutomationService.config();
        var st = svc.stateView();
        long now = System.currentTimeMillis();
        line(src, "mode: " + c.automationMode()
                + (st.paused ? "  [PAUSED: " + st.pauseReason + "]" : ""));
        line(src, String.format("policy fingerprint: %s   evaluate every %d min", svc.policyFingerprint(),
                c.evaluateMinutes()));
        int ws = SchedulerMath.parseHHMM(c.windowStart());
        int we = SchedulerMath.parseHHMM(c.windowEnd());
        line(src, "maintenance window: " + c.windowStart() + "-" + c.windowEnd()
                + (ws == we ? " (any time)" : " in-window=" + SchedulerMath.inWindow(now, ws, we, java.time.ZoneId.systemDefault())));
        if (st.clockAnomaly) line(src, "CLOCK ANOMALY recorded — automatic closure suspended (clock-clear to acknowledge)");
        if (st.postponedUntilMs > now) {
            line(src, "postponed until " + Instant.ofEpochMilli(st.postponedUntilMs));
        }
        line(src, String.format("last evaluation: %s (%d min ago)", st.lastEvaluatedAtMs == 0 ? "never"
                        : Instant.ofEpochMilli(st.lastEvaluatedAtMs),
                st.lastEvaluatedAtMs == 0 ? -1 : (now - st.lastEvaluatedAtMs) / 60000));
        line(src, "health streak: " + st.consecutiveHits + " consecutive depleted-evaluations"
                + " | last broadcast health: " + st.lastBroadcastHealth);
        if (st.pending != null) {
            var p = st.pending;
            line(src, String.format("PENDING %s closure gen=%d score=%.1f created=%s expires=%s fp=%s",
                    p.trigger, p.generation, p.score, Instant.ofEpochMilli(p.createdAtMs),
                    Instant.ofEpochMilli(p.expiresAtMs), p.policyFingerprint));
            for (String rsn : p.reasons) line(src, "  reason: " + rsn);
        } else {
            line(src, "pending decision: none");
        }
        return 1;
    }

    private static int explain(CommandSourceStack src) {
        AutomationService svc = AutomationService.get();
        if (svc == null) {
            src.sendFailure(Component.literal("automation unavailable this session"));
            return 0;
        }
        var out = svc.evaluateNow("command-explain", System.currentTimeMillis());
        if (out == null || out.result() == null) {
            src.sendFailure(Component.literal("evaluation failed — see server log"));
            return 0;
        }
        for (String l : out.result().explain()) line(src, l);
        return 1;
    }

    private static int dryrun(CommandSourceStack src) {
        AutomationService svc = AutomationService.get();
        if (svc == null || AutomationService.config() == null) {
            src.sendFailure(Component.literal("automation unavailable this session"));
            return 0;
        }
        var out = svc.evaluateNow("command-dryrun", System.currentTimeMillis());
        if (out == null || out.result() == null) {
            src.sendFailure(Component.literal("evaluation failed — see server log"));
            return 0;
        }
        String mode = out.mode();
        boolean wouldCloseNow = false;
        String why = "";
        switch (mode) {
            case "MANUAL" -> why = "mode MANUAL never acts";
            case "ADVISORY" -> why = "mode ADVISORY only recommends";
            case "SCHEDULED_WITH_APPROVAL" -> wouldCloseNow = out.hasPending();
            case "AUTOMATIC_CLOSURE" -> {
                var c = AutomationService.config();
                int ws = SchedulerMath.parseHHMM(c.windowStart());
                int we = SchedulerMath.parseHHMM(c.windowEnd());
                boolean inWindow = SchedulerMath.inWindow(System.currentTimeMillis(), ws, we,
                        java.time.ZoneId.systemDefault());
                if (!inWindow) why = "outside maintenance window";
                else if (out.clockAnomaly()) why = "clock anomaly suspends automatic actions";
                else if (out.postponedUntilMs() > System.currentTimeMillis())
                    why = "postponed by operator";
                else wouldCloseNow = out.hasPending();
            }
            default -> why = "unknown mode treated as MANUAL";
        }
        line(src, "DRY-RUN verdict for mode " + mode + ": "
                + (wouldCloseNow ? "WOULD BEGIN TIMED CLOSURE (via closing pipeline)" : "no action")
                + (why.isEmpty() ? "" : " — " + why));
        line(src, String.format("evaluation: health=%s score=%.1f sustained=%s",
                out.result().health, out.result().score, out.result().sustainedSummary));
        return 1;
    }

    private static int history(CommandSourceStack src) {
        CycleArchive archive = new CycleArchiveStore(
                BbeLayout.cycleArchiveFile(src.getServer())).loadTolerant();
        if (archive.summaries.isEmpty()) {
            src.sendSuccess(() -> Component.literal("no completed expedition cycles recorded yet"), false);
            return 0;
        }
        line(src, String.format("completed cycles: %d (bounded archive cap %d)",
                archive.summaries.size(), CycleArchive.CAP));
        int from = Math.max(0, archive.summaries.size() - 10); // recent tail
        for (var s : archive.summaries.subList(from, archive.summaries.size())) {
            long hours = s.durationMs / 3600000;
            line(src, String.format("gen %d — %dd %dh — %s (%s) — explorers %d — chunks %d — deaths %d — reset=%s validation=%s",
                    s.generation, hours / 24, hours % 24, s.closureReason.isEmpty() ? "?" : s.closureReason,
                    s.closureActor.isEmpty() ? "?" : s.closureActor,
                    s.distinctExplorers, s.distinctChunks, s.deathsTotal,
                    s.resetResult, s.validationResult));
        }
        return 1;
    }

    private static int shadow(CommandSourceStack src) {
        AutomationService svc = AutomationService.get();
        if (svc == null) {
            src.sendFailure(Component.literal("automation unavailable this session"));
            return 0;
        }
        var sh = svc.stateView().shadow;
        if (sh.isEmpty()) {
            src.sendSuccess(() -> Component.literal("shadow log empty (evaluations record WOULD-HAVE decisions here)"), false);
            return 0;
        }
        line(src, String.format("shadow entries: %d (cap %d)", sh.size(),
                com.bigbangcraft.expeditions.automation.AutomationState.SHADOW_CAP));
        int from = Math.max(0, sh.size() - 15);
        for (int i = from; i < sh.size(); i++) {
            var e = sh.get(i);
            line(src, String.format("%s gen=%d %.1f %s WOULD_HAVE_RECOMMENDED=%b %s",
                    Instant.ofEpochMilli(e.atMs), e.generation, e.score, e.health,
                    e.wouldRecommend, e.blockers.isEmpty() ? "" : "[" + e.blockers + "]"));
        }
        long would = sh.stream().filter(x -> x.wouldRecommend).count();
        line(src, String.format("total would-recommend in window: %d/%d", would, sh.size()));
        return 1;
    }

    // ---------------------------------------------------------------- write

    private static int evaluate(CommandSourceStack src) {
        AutomationService svc = AutomationService.get();
        if (svc == null) {
            src.sendFailure(Component.literal("automation unavailable this session"));
            return 0;
        }
        var out = svc.evaluateNow("command-forced", System.currentTimeMillis());
        if (out == null || out.result() == null) {
            src.sendFailure(Component.literal("evaluation failed — see server log"));
            return 0;
        }
        line(src, String.format("forced evaluation stored: health=%s score=%.1f",
                out.result().health, out.result().score));
        return 1;
    }

    private static int pause(CommandSourceStack src, String reason) {
        return done(src, AutomationService.get() == null ? "automation unavailable"
                : AutomationService.get().pause(reason.isBlank() ? "operator request" : reason), "paused");
    }

    private static int resume(CommandSourceStack src) {
        return done(src, AutomationService.get() == null ? "automation unavailable"
                : AutomationService.get().resume(sourceName(src)), "resumed");
    }

    private static int postpone(CommandSourceStack src, int days) {
        return done(src, AutomationService.get() == null ? "automation unavailable"
                : AutomationService.get().postpone(days, sourceName(src)), "postponed " + days + "d");
    }

    private static int cancel(CommandSourceStack src) {
        return done(src, AutomationService.get() == null ? "automation unavailable"
                : AutomationService.get().cancelPending(sourceName(src)), "cancelled");
    }

    private static int approve(CommandSourceStack src) {
        return done(src, AutomationService.get() == null ? "automation unavailable"
                : AutomationService.get().approve(sourceName(src)), "approved");
    }

    private static int reload(CommandSourceStack src) {
        return done(src, AutomationService.get() == null ? "automation unavailable"
                : AutomationService.get().reload(sourceName(src)), null);
    }

    private static int clockClear(CommandSourceStack src) {
        return done(src, AutomationService.get() == null ? "automation unavailable"
                : AutomationService.get().clearClockAnomaly(sourceName(src)), "clock anomaly cleared");
    }

    /** STAGING-ONLY: seed synthetic activity for live automation campaigns. */
    private static int seedSim(CommandSourceStack src, int chunks, int opens, int deaths, int structures) {
        AutomationService svc = AutomationService.get();
        if (svc == null) {
            src.sendFailure(Component.literal("automation unavailable"));
            return 0;
        }
        String err = svc.seedSyntheticActivity(chunks, opens, deaths, structures);
        return done(src, err,
                String.format("seeded gen activity: chunks=%d opens=%d deaths=%d structures=%d",
                        chunks, opens, deaths, structures));
    }

    // ---------------------------------------------------------------- utils

    private static int done(CommandSourceStack src, String err, String okText) {
        if (err != null) {
            final String msg = err;
            src.sendFailure(Component.literal(msg));
            return 0;
        }
        if (okText != null) src.sendSuccess(() -> Component.literal(okText), true);
        return 1;
    }

    private static void line(CommandSourceStack src, String text) {
        src.sendSuccess(() -> Component.literal(text), false);
    }

    private static String sourceName(CommandSourceStack src) {
        return src.getTextName();
    }
}
