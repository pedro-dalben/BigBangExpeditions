package com.bigbangcraft.expeditions.command;

import com.bigbangcraft.expeditions.audit.AuditEvent;
import com.bigbangcraft.expeditions.core.RuntimeServices;
import com.bigbangcraft.expeditions.lifecycle.EntryDecision;
import com.bigbangcraft.expeditions.lifecycle.EvacuationService;
import com.bigbangcraft.expeditions.lifecycle.LifecycleRecord;
import com.bigbangcraft.expeditions.lifecycle.LifecycleState;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Path;

/**
 * /expedition lifecycle — production dimension lifecycle control.
 *
 * Permission split (Goal 03):
 *   level 2: read-only inspection (status)
 *   level 3: lifecycle-affecting operations (close/abort/open/validate/recover)
 *
 * Destructive execution itself remains offline-only (scripts + authorization
 * artifacts); this command never destroys anything.
 */
public final class LifecycleCommand {
    private static final String DIM = "bigbangexpeditions:expedition";

    private LifecycleCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("expedition")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("lifecycle")
                        .then(Commands.literal("status")
                                .executes(ctx -> status(ctx.getSource())))
                        .then(Commands.literal("close")
                                .requires(s -> s.hasPermission(3))
                                .executes(ctx -> close(ctx.getSource())))
                        .then(Commands.literal("abort-close")
                                .requires(s -> s.hasPermission(3))
                                .executes(ctx -> abortClose(ctx.getSource())))
                        .then(Commands.literal("begin-validation")
                                .requires(s -> s.hasPermission(3))
                                .executes(ctx -> beginValidation(ctx.getSource())))
                        .then(Commands.literal("record-validation")
                                .requires(s -> s.hasPermission(3))
                                .then(Commands.argument("result", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .executes(ctx -> recordValidation(ctx.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "result")))))
                        .then(Commands.literal("open")
                                .requires(s -> s.hasPermission(3))
                                .executes(ctx -> open(ctx.getSource())))
                        .then(Commands.literal("recover")
                                .requires(s -> s.hasPermission(3))
                                .then(Commands.argument("reason", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                        .executes(ctx -> recover(ctx.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "reason")))))
                        .then(Commands.literal("dryrun")
                                .executes(ctx -> dryRun(ctx.getSource())))
                        .then(Commands.literal("issue-authorization")
                                .requires(s -> s.hasPermission(3))
                                .executes(ctx -> issueAuthorization(ctx.getSource())))
                        .then(Commands.literal("health")
                                .executes(ctx -> health(ctx.getSource())))));
    }

    private record Src(CommandSourceStack src) {
        String actor() {
            return src.getTextName();
        }
    }

    private static RuntimeServices svc(CommandSourceStack src) {
        return RuntimeServices.get(src.getServer());
    }

    private static int status(CommandSourceStack src) {
        try {
            LifecycleRecord r = svc(src).lifecycle().current();
            src.sendSuccess(() -> Component.literal("=== Expedition lifecycle ==="), false);
            send(src, "status: " + r.status + (r.status.playersMayEnter()
                    ? " (players may enter)" : " (entry BLOCKED)"));
            send(src, "generation: " + r.generation);
            send(src, "activeAuthorization: " + (r.activeAuthId.isEmpty() ? "<none>" : r.activeAuthId));
            send(src, "lastValidationResult: " + (r.lastValidationResult.isEmpty() ? "<none>" : r.lastValidationResult));
            if (!r.failureReason.isEmpty()) send(src, "failureReason: " + r.failureReason);
            if (!r.lastChangeReason.isEmpty()) send(src, "lastChange: " + r.lastChangeReason);
            send(src, String.format("opened: %d  lastReset: %d",
                    r.lastOpenedAtEpochMs, r.lastResetAtEpochMs));
            int recent = Math.min(5, r.recent.size());
            for (int i = r.recent.size() - recent; i < r.recent.size(); i++) {
                LifecycleRecord.TransitionEvent e = r.recent.get(i);
                send(src, String.format("recent: %s %s->%s by=%s %s", e.atEpochMs, e.from, e.to,
                        e.by.isEmpty() ? "-" : e.by, e.reason));
            }
            return 1;
        } catch (IOException e) {
            src.sendFailure(Component.literal("lifecycle UNREADABLE — fail-closed: " + e.getMessage()));
            return 0;
        }
    }

    /** Best-effort sector-registry mirror of the dimension lifecycle. */
    private static void syncSector(CommandSourceStack src, LifecycleState lifecycleState) {
        try {
            var view = com.bigbangcraft.expeditions.reset.ProductionResetFlow.sectorView(src.getServer());
            if (view.first() == null) return;
            com.bigbangcraft.expeditions.sector.SectorState target =
                    lifecycleState == LifecycleState.LOCKED
                            ? com.bigbangcraft.expeditions.sector.SectorState.LOCKED
                            : com.bigbangcraft.expeditions.sector.SectorState.OPEN;
            if (view.first().status == target) return;
            var err = view.registry().transition(view.first().id, target, System.currentTimeMillis());
            if (err.isEmpty()) view.registry().save();
        } catch (Exception e) {
            LOG.warn("sector sync failed (non-fatal): {}", e.toString());
        }
    }

    /** OPEN → CLOSING → EVACUATING → LOCKED as one deliberate operation. */
    private static int close(CommandSourceStack src) {
        String actor = src.getTextName();
        RuntimeServices services = svc(src);
        long start = System.currentTimeMillis();
        try {
            var err1 = services.lifecycle().transition(LifecycleState.CLOSING, actor, "close requested");
            if (err1.isPresent()) { refuse(src, services, "LIFECYCLE_CLOSE", err1.get()); return 0; }

            int evacuated = EvacuationService.evacuateAll(src.getServer(), services, actor);
            services.lifecycle().transition(LifecycleState.EVACUATING, actor,
                    "evacuated " + evacuated + " player(s)");

            // nobody may remain inside before LOCKED
            var level = src.getServer().getLevel(
                    com.bigbangcraft.expeditions.integration.lostcities.LostCitiesAdapter.expeditionDimensionKey());
            int stillInside = EvacuationService.playersInside(level).size();
            if (stillInside > 0) {
                String reason = stillInside + " player(s) still inside after evacuation";
                services.lifecycle().transition(LifecycleState.FAILED, actor, reason);
                services.auditRefusal("LIFECYCLE_CLOSE", actor, reason);
                src.sendFailure(Component.literal("CLOSE ABORTED: " + reason));
                return 0;
            }

            var err4 = services.lifecycle().transition(LifecycleState.LOCKED, actor, "dimension locked");
            if (err4.isPresent()) { refuse(src, services, "LIFECYCLE_CLOSE", err4.get()); return 0; }

            // keep the staging sector view aligned with the production lifecycle
            syncSector(src, LifecycleState.LOCKED);

            services.audit().record(AuditEvent.of("LIFECYCLE_CLOSE", actor)
                    .states(LifecycleState.OPEN.name(), LifecycleState.LOCKED.name())
                    .outcome("OK").duration(System.currentTimeMillis() - start)
                    .detail("evacuated", "" + evacuated).detail("dimension", DIM));
            src.sendSuccess(() -> Component.literal(
                    "Expedition closed. Evacuated " + evacuated + " player(s). Status: LOCKED."), false);
            return 1;
        } catch (IOException e) {
            src.sendFailure(Component.literal("close failed (persist error): " + e.getMessage()));
            return 0;
        }
    }

    private static int abortClose(CommandSourceStack src) {
        String actor = src.getTextName();
        try {
            LifecycleRecord cur = svc(src).lifecycle().current();
            var err = svc(src).lifecycle().transition(LifecycleState.OPEN, actor, "closure aborted");
            if (err.isPresent()) { refuse(src, svc(src), "LIFECYCLE_ABORT", err.get()); return 0; }
            svc(src).audit().record(AuditEvent.of("LIFECYCLE_ABORT_CLOSE", actor)
                    .states(cur.status.name(), LifecycleState.OPEN.name()).outcome("OK"));
            syncSector(src, LifecycleState.OPEN);
            src.sendSuccess(() -> Component.literal("Closure aborted — expedition reopened."), false);
            return 1;
        } catch (IOException e) {
            src.sendFailure(Component.literal("abort failed: " + e.getMessage()));
            return 0;
        }
    }

    private static int beginValidation(CommandSourceStack src) {
        String actor = src.getTextName();
        try {
            var err = svc(src).lifecycle().transition(LifecycleState.VALIDATING, actor, "post-reset validation started");
            if (err.isPresent()) { refuse(src, svc(src), "LIFECYCLE_VALIDATE", err.get()); return 0; }
            src.sendSuccess(() -> Component.literal("VALIDATING — run baseline compare, then record PASS/FAIL via record-validation."), false);
            return 1;
        } catch (IOException e) {
            src.sendFailure(Component.literal("begin-validation failed: " + e.getMessage()));
            return 0;
        }
    }

    /** Records PASS/FAIL while VALIDATING. FAIL forces the FAILED path. */
    private static int recordValidation(CommandSourceStack src, String resultRaw) {
        String actor = src.getTextName();
        String result = resultRaw == null ? "" : resultRaw.trim().toUpperCase();
        if (!result.equals("PASS") && !result.equals("FAIL")) {
            src.sendFailure(Component.literal("REFUSED: result must be PASS or FAIL"));
            return 0;
        }
        try {
            var err = svc(src).lifecycle().recordValidationResult(result, actor);
            if (err.isPresent()) { refuse(src, svc(src), "VALIDATION_RECORD", err.get()); return 0; }
            if (result.equals("FAIL")) {
                var f = svc(src).lifecycle().transition(LifecycleState.FAILED, actor, "validation FAIL recorded");
                if (f.isPresent()) { refuse(src, svc(src), "VALIDATION_RECORD", f.get()); return 0; }
            }
            svc(src).audit().record(AuditEvent.of("VALIDATION_RECORDED", actor)
                    .outcome(result));
            send(src, "validation recorded: " + result
                    + (result.equals("PASS") ? " — /expedition lifecycle open is now possible" : " — expedition FAILED"));
            return 1;
        } catch (IOException e) {
            src.sendFailure(Component.literal("record-validation failed: " + e.getMessage()));
            return 0;
        }
    }

    /** VALIDATING → OPEN; hard-gated on recorded PASS. */
    private static int open(CommandSourceStack src) {
        String actor = src.getTextName();
        try {
            var rec = svc(src).lifecycle().current();
            if (!"PASS".equals(rec.lastValidationResult) && rec.status == LifecycleState.VALIDATING) {
                String msg = "validation gate: record a PASS first (current=" +
                        (rec.lastValidationResult.isEmpty() ? "<none>" : rec.lastValidationResult) + ")";
                refuse(src, svc(src), "LIFECYCLE_OPEN", msg);
                src.sendFailure(Component.literal(msg));
                return 0;
            }
            var err = svc(src).lifecycle().transition(LifecycleState.OPEN, actor, "validated reopen");
            if (err.isPresent()) { refuse(src, svc(src), "LIFECYCLE_OPEN", err.get()); return 0; }
            int generation = svc(src).lifecycle().current().generation;
            svc(src).audit().record(AuditEvent.of("LIFECYCLE_OPEN", actor)
                    .states(rec.status.name(), LifecycleState.OPEN.name()).outcome("OK"));
            syncSector(src, LifecycleState.OPEN);
            src.sendSuccess(() -> Component.literal("Expedition is OPEN. Generation " + generation + "."), false);
            return 1;
        } catch (IOException e) {
            src.sendFailure(Component.literal("open failed: " + e.getMessage()));
            return 0;
        }
    }

    /** Explicit operator recovery from FAILED/RECOVERY_REQUIRED to LOCKED. */
    private static int recover(CommandSourceStack src, String reason) {
        String actor = src.getTextName();
        try {
            LifecycleRecord cur = svc(src).lifecycle().current();
            if (cur.status != LifecycleState.FAILED && cur.status != LifecycleState.RECOVERY_REQUIRED) {
                String msg = "recover only applies to FAILED/RECOVERY_REQUIRED (current: " + cur.status + ")";
                refuse(src, svc(src), "LIFECYCLE_RECOVER", msg);
                src.sendFailure(Component.literal(msg));
                return 0;
            }
            var err = svc(src).lifecycle().transition(LifecycleState.LOCKED, actor, "RECOVERY: " + reason);
            if (err.isPresent()) { refuse(src, svc(src), "LIFECYCLE_RECOVER", err.get()); return 0; }
            svc(src).audit().record(AuditEvent.of("LIFECYCLE_RECOVER", actor)
                    .states(cur.status.name(), LifecycleState.LOCKED.name())
                    .outcome("OK").reason(reason));
            src.sendSuccess(() -> Component.literal("Recovery acknowledged — status LOCKED. Re-run pipeline deliberately."), false);
            return 1;
        } catch (IOException e) {
            src.sendFailure(Component.literal("recover failed: " + e.getMessage()));
            return 0;
        }
    }

    private static void refuse(CommandSourceStack src, RuntimeServices services, String event, String reason) {
        services.auditRefusal(event, src.getTextName(), reason);
        src.sendFailure(Component.literal("REFUSED: " + reason));
    }

    private static void send(CommandSourceStack src, String line) {
        src.sendSuccess(() -> Component.literal(line), false);
    }

    // ------------------------------------------------------------------ dry-run

    /** Full production decision pipeline with destruction stubbed. Read-only. */
    private static int dryRun(CommandSourceStack src) {
        long start = System.currentTimeMillis();
        try {
            var in = com.bigbangcraft.expeditions.reset.ProductionResetFlow.collectInputs(src.getServer());
            var probe = com.bigbangcraft.expeditions.reset.ProductionResetFlow.diskProbe(src.getServer());
            boolean lockFree = !new com.bigbangcraft.expeditions.reset.ResetLock(
                    com.bigbangcraft.expeditions.core.BbeLayout.locksDir(src.getServer()).resolve("reset.lock"))
                    .isLocked(System.currentTimeMillis());
            var report = com.bigbangcraft.expeditions.reset.DryRunEngine.run(in, probe, lockFree);

            send(src, "=== Expedition DRY-RUN (" + report.verdict + ") ===");
            for (var s : report.steps) {
                send(src, String.format("[%s] %s — %s", s.status, s.name, s.detail));
            }
            for (String w : report.warnings) send(src, "WARN: " + w);
            if (report.wouldIssueArtifact != null) {
                send(src, "would-issue: " + report.wouldIssueArtifact.authId
                        + " (simulated only — nothing persisted)");
            }
            svc(src).audit().record(AuditEvent.of("DRY_RUN", src.getTextName())
                    .outcome(report.verdict.name())
                    .duration(System.currentTimeMillis() - start));
            return report.verdict == com.bigbangcraft.expeditions.reset.DryRunEngine.Verdict.WOULD_RESET ? 1 : 0;
        } catch (Exception e) {
            LOG.error("dry-run failed", e);
            src.sendFailure(Component.literal("dry-run failed: " + e.getMessage()));
            return 0;
        }
    }

    private static final org.apache.logging.log4j.Logger LOG =
            org.apache.logging.log4j.LogManager.getLogger("BigBangExpeditions/Lifecycle");

    /** LOCKED -> PREFLIGHT -> RESET_READY with a persisted authorization artifact. */
    private static int issueAuthorization(CommandSourceStack src) {
        String actor = src.getTextName();
        RuntimeServices services = svc(src);
        try {
            var in = com.bigbangcraft.expeditions.reset.ProductionResetFlow.collectInputs(src.getServer());
            var outcome = com.bigbangcraft.expeditions.reset.AuthorizationService.issue(in);

            if (!outcome.ok()) {
                for (String rsn : outcome.refusals) send(src, "REFUSED: " + rsn);
                refuse(src, services, "AUTH_ISSUE", String.join("; ", outcome.refusals));
                return 0;
            }
            var auth = outcome.artifact;
            Path dir = com.bigbangcraft.expeditions.core.BbeLayout.root(src.getServer()).resolve("authorizations");
            java.nio.file.Files.createDirectories(dir);
            Path file = dir.resolve(auth.authId + ".json");
            java.nio.file.Files.writeString(file, auth.toJson());

            var ledger = new com.bigbangcraft.expeditions.reset.AuthorizationLedger(
                    com.bigbangcraft.expeditions.core.BbeLayout.authLedgerFile(src.getServer()));
            var lerr = ledger.recordIssued(auth.authId, auth.generationAtIssue, in.sector.id, actor,
                    System.currentTimeMillis());
            if (lerr.isPresent()) { refuse(src, services, "AUTH_ISSUE", lerr.get()); return 0; }
            var serr = com.bigbangcraft.expeditions.reset.AuthorizationService.supersedePriorIssued(
                    ledger, in.sector.id, auth.authId, actor, System.currentTimeMillis());
            if (serr.isPresent()) { refuse(src, services, "AUTH_ISSUE", serr.get()); return 0; }

            // export current fingerprint so the offline CLI can re-verify equality
            com.bigbangcraft.expeditions.reset.QualificationStore.exportCurrent(
                    com.bigbangcraft.expeditions.core.BbeLayout.configDir(src.getServer()),
                    in.currentFingerprint);

            services.lifecycle().setActiveAuth(auth.authId, actor);
            var t1 = services.lifecycle().transition(LifecycleState.PREFLIGHT, actor, "authorization issued");
            if (t1.isEmpty()) {
                services.lifecycle().transition(LifecycleState.RESET_READY, actor,
                        "authorized; backup+reset run offline");
            }

            for (String w : outcome.warnings) send(src, "WARN: " + w);
            services.audit().record(AuditEvent.of("AUTH_ISSUED", actor)
                    .subject(auth.authId).outcome("OK")
                    .detail("scope", auth.scope)
                    .detail("expiresAtEpochMs", "" + auth.expiresAtEpochMs));
            send(src, "Authorization issued: " + file);
            send(src, "Expires: " + auth.expiresAtEpochMs + "  Scope: " + auth.scope
                    + "  Generation: " + auth.generationAtIssue);
            send(src, "Next: stop server, run scripts/production/execute-reset.sh " + auth.authId);
            return 1;
        } catch (Exception e) {
            LOG.error("issue-authorization failed", e);
            src.sendFailure(Component.literal("issue failed: " + e.getMessage()));
            return 0;
        }
    }

    /** Aggregated operational health answer. */
    private static int health(CommandSourceStack src) {
        try {
            RuntimeServices services = svc(src);
            LifecycleRecord r = services.lifecycle().current();
            send(src, "=== Expedition health ===");
            send(src, "playersMayEnter: " + (r.status.playersMayEnter() ? "YES" : "NO (" + r.status + ")"));
            send(src, "lifecycle: " + r.status + " generation=" + r.generation);

            boolean lockHeld = new com.bigbangcraft.expeditions.reset.ResetLock(
                    com.bigbangcraft.expeditions.core.BbeLayout.locksDir(src.getServer()).resolve("reset.lock"))
                    .isLocked(System.currentTimeMillis());
            send(src, "resetLock: " + (lockHeld ? "HELD" : "free"));

            var journal = new com.bigbangcraft.expeditions.reset.OperationJournal(
                    com.bigbangcraft.expeditions.core.BbeLayout.journalDir(src.getServer()));
            var op = journal.summarizeLatest();
            send(src, "lastOperation: " + (op == null ? "<none>" :
                    op.authId() + " active=" + op.hasActiveOp() + " lastPhase=" + op.lastCompletedPhase()));

            var ledger = new com.bigbangcraft.expeditions.reset.AuthorizationLedger(
                    com.bigbangcraft.expeditions.core.BbeLayout.authLedgerFile(src.getServer()));
            int issued = ledger.all().values().stream()
                    .filter(e -> e.status == com.bigbangcraft.expeditions.reset.AuthorizationLedger.Status.ISSUED)
                    .toList().size();
            send(src, "pendingAuthorizations: " + issued);
            send(src, "lastValidationResult: " + (r.lastValidationResult.isEmpty() ? "<none>" : r.lastValidationResult));
            send(src, "rollbackAvailable: " + rollbackAvailable(src));
            return 1;
        } catch (Exception e) {
            src.sendFailure(Component.literal("health failed: " + e.getMessage()));
            return 0;
        }
    }

    private static String rollbackAvailable(CommandSourceStack src) throws IOException {
        Path backups = com.bigbangcraft.expeditions.core.BbeLayout.root(src.getServer()).resolve("backups");
        if (!java.nio.file.Files.isDirectory(backups)) return "no backups";
        try (var list = java.nio.file.Files.list(backups)) {
            long valid = list.filter(java.nio.file.Files::isDirectory).count();
            return valid == 0 ? "no backups" : valid + " backup(s) on disk";
        }
    }
}
