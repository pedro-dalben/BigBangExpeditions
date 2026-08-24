package com.bigbangcraft.expeditions.core;

import com.bigbangcraft.expeditions.audit.AuditLog;
import com.bigbangcraft.expeditions.lifecycle.LifecycleService;
import com.bigbangcraft.expeditions.lifecycle.LifecycleStore;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lazily constructed shared services for one server process.
 * Construction failures propagate loudly — a half-initialized safety stack
 * must never masquerade as a working one.
 */
public final class RuntimeServices {
    private static final AtomicReference<RuntimeServices> INSTANCE = new AtomicReference<>();

    private final LifecycleService lifecycle;
    private final AuditLog audit;

    private RuntimeServices(MinecraftServer server) {
        this.audit = new AuditLog(
                BbeLayout.auditDir(server).resolve("audit.jsonl"),
                8L * 1024 * 1024, // ~8 MB per file
                10);
        this.lifecycle = new LifecycleService(new LifecycleStore(BbeLayout.lifecycleFile(server)));
    }

    public static RuntimeServices get(MinecraftServer server) {
        return INSTANCE.updateAndGet(cur -> cur != null ? cur : new RuntimeServices(server));
    }

    /** Test hook / server-stop cleanup. */
    public static void reset() {
        INSTANCE.set(null);
    }

    public LifecycleService lifecycle() {
        return lifecycle;
    }

    public AuditLog audit() {
        return audit;
    }

    /** Convenience: audit a refusal with best-effort persistence. */
    public void auditRefusal(String event, String actor, String reason) {
        try {
            audit.append(com.bigbangcraft.expeditions.audit.AuditEvent.of(event, actor)
                    .outcome("REFUSED").reason(reason));
        } catch (IOException ignored) {
            // logging already failed once; do not mask the original refusal
        }
    }
}
