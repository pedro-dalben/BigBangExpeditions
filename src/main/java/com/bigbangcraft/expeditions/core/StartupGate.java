package com.bigbangcraft.expeditions.core;

import com.bigbangcraft.expeditions.audit.AuditEvent;
import com.bigbangcraft.expeditions.lifecycle.LifecycleRecord;
import com.bigbangcraft.expeditions.lifecycle.LifecycleService;
import com.bigbangcraft.expeditions.lifecycle.LifecycleState;
import com.bigbangcraft.expeditions.lifecycle.StartupRecovery;
import com.bigbangcraft.expeditions.reset.OperationJournal;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Runs at every server start: cross-checks the persisted lifecycle against the
 * operation journal and fails closed into RECOVERY_REQUIRED on any
 * inconsistency. The system never assumes "everything is fine".
 */
public final class StartupGate {
    private static final Logger LOG = LogManager.getLogger("BigBangExpeditions/StartupGate");

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent e) {
        RuntimeServices services = RuntimeServices.get(e.getServer());
        try {
            LifecycleRecord r = services.lifecycle().current();
            OperationJournal journal = new OperationJournal(BbeLayout.journalDir(e.getServer()));
            OperationJournal.OpSummary s = journal.summarizeLatest();
            StartupRecovery.JournalSummary js = s == null
                    ? StartupRecovery.JournalSummary.NONE
                    : new StartupRecovery.JournalSummary(s.hasActiveOp(), s.lastCompletedPhase(), s.deletionReached());

            StartupRecovery.Finding f = StartupRecovery.evaluate(r, js);
            if (f.recoveryRequired()) {
                String reason = f.reason() + ": " + f.detail();
                LOG.error("EXPEDITION RECOVERY REQUIRED — {}", reason);
                var err = services.lifecycle().transition(LifecycleState.RECOVERY_REQUIRED,
                        "startup-gate", reason);
                services.audit().record(AuditEvent.of("STARTUP_RECOVERY", "startup-gate")
                        .states(r.status.name(), LifecycleState.RECOVERY_REQUIRED.name())
                        .outcome(err.isPresent() ? "REFUSED" : "OK").reason(reason));
            } else if (r.status == LifecycleState.BOOTING || r.status == LifecycleState.RESETTING) {
                // destructive phase completed cleanly before restart: continue to validation
                var err = services.lifecycle().transition(
                        r.status == LifecycleState.BOOTING ? LifecycleState.VALIDATING : LifecycleState.BOOTING,
                        "startup-gate", "resuming post-reset flow");
                if (err.isPresent()) LOG.warn("resume transition refused: {}", err.get());
                else services.audit().record(AuditEvent.of("STARTUP_RESUME", "startup-gate")
                        .states(r.status.name(), r.status == LifecycleState.BOOTING
                                ? LifecycleState.VALIDATING.name() : LifecycleState.BOOTING.name())
                        .outcome("OK"));
            } else {
                LOG.info("Startup gate: lifecycle {} consistent with journal", r.status);
            }
        } catch (Exception ex) {
            // unreadable lifecycle or journal: fail closed
            LOG.error("Startup gate could not verify lifecycle — forcing RECOVERY_REQUIRED", ex);
            try {
                services.lifecycle().transition(LifecycleState.RECOVERY_REQUIRED, "startup-gate",
                        "lifecycle/journal unreadable: " + ex.getMessage());
            } catch (Exception ignored) {
                LOG.error("CRITICAL: lifecycle record is corrupt and RECOVERY_REQUIRED could not be persisted — "
                        + "the expedition MUST be treated as unsafe until repaired manually");
            }
        }
    }
}
