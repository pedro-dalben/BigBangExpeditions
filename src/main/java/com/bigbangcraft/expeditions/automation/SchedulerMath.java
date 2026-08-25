package com.bigbangcraft.expeditions.automation;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Pure scheduler math for maintenance windows and evaluation cadence
 * (Goal 05 requirements 20/21). Wall-clock semantics, explicit timezone —
 * the server's local zone is the administrator-facing reference.
 *
 * <p>Window semantics: [start,end) within the day; start==end means the window
 * is DISABLED (any time allowed); overnight wrap (start>end) is supported.
 */
public final class SchedulerMath {
    private SchedulerMath() {}

    public static final long MINUTE_MS = 60_000L;

    /** Minutes since midnight for a validated HH:MM string. */
    public static int parseHHMM(String hhmm) {
        String[] parts = hhmm.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    public static boolean inWindow(long nowMs, int startMinute, int endMinute, ZoneId zone) {
        if (startMinute == endMinute) return true; // disabled constraint => any time
        LocalDateTime t = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone);
        int m = t.getHour() * 60 + t.getMinute();
        if (startMinute < endMinute) return m >= startMinute && m < endMinute;
        return m >= startMinute || m < endMinute; // overnight wrap
    }

    /** Next instant at which the window opens (>= nowMs). For disabled windows returns nowMs. */
    public static long nextWindowStart(long nowMs, int startMinute, int endMinute, ZoneId zone) {
        if (startMinute == endMinute) return nowMs;
        LocalDateTime t = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone);
        LocalDateTime candidate = t.toLocalDate().atStartOfDay(zone).toLocalDateTime()
                .plusMinutes(startMinute);
        long cand = candidate.atZone(zone).toInstant().toEpochMilli();
        if (!inWindow(nowMs, startMinute, endMinute, zone) && cand <= nowMs) {
            cand = candidate.plusDays(1).atZone(zone).toInstant().toEpochMilli();
        }
        while (cand <= nowMs && !inWindow(cand, startMinute, endMinute, zone)) {
            cand += 24L * 3600_000L;
        }
        return Math.max(cand, nowMs);
    }

    /**
     * Missed-schedule catch-up decision (requirement 22/40): deterministic.
     * A deadline missed entirely while offline resolves to "run at next legal
     * opportunity" — never retroactively, never repeatedly.
     */
    public static boolean dueForEvaluation(long lastEvaluatedAtMs, long intervalMinutes, long nowMs) {
        long interval = Math.max(1, intervalMinutes) * MINUTE_MS;
        return lastEvaluatedAtMs <= 0 || (nowMs - lastEvaluatedAtMs) >= interval;
    }

    /** Bounded shadow log trimming helper. */
    public static <T> List<T> cap(List<T> list, int cap) {
        while (list.size() > cap) list.remove(0);
        return list;
    }
}
