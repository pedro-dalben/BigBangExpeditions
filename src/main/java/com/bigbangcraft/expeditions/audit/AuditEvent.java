package com.bigbangcraft.expeditions.audit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One durable operational evidence record. Serialized as a single JSON line
 * in the append-only audit log. Player-sensitive data must NOT be placed in
 * {@code detail} beyond operator names/ids that performed actions.
 */
public final class AuditEvent {
    public long tsEpochMs;
    /** e.g. LIFECYCLE_TRANSITION, RESET_PLAN_CREATED, AUTH_CONSUMED, BACKUP_VERIFIED, ROLLBACK, LOCK_ACQUIRED */
    public String event;
    public String actor;
    public String action;
    public String subject;
    public String fromState;
    public String toState;
    /** OK | REFUSED | ERROR */
    public String outcome;
    public String reason;
    public long durationMs = -1;
    public Map<String, String> detail = new LinkedHashMap<>();

    public AuditEvent() {}

    public static AuditEvent of(String event, String actor) {
        AuditEvent e = new AuditEvent();
        e.tsEpochMs = System.currentTimeMillis();
        e.event = event;
        e.actor = actor == null ? "" : actor;
        return e;
    }

    public AuditEvent action(String v) { this.action = v; return this; }
    public AuditEvent subject(String v) { this.subject = v; return this; }
    public AuditEvent states(String from, String to) {
        this.fromState = from == null ? "" : from;
        this.toState = to == null ? "" : to;
        return this;
    }
    public AuditEvent outcome(String v) { this.outcome = v; return this; }
    public AuditEvent reason(String v) { this.reason = v == null ? "" : v; return this; }
    public AuditEvent duration(long ms) { this.durationMs = ms; return this; }
    public AuditEvent detail(String k, String v) { this.detail.put(k, v == null ? "" : v); return this; }
}
