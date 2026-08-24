package com.bigbangcraft.expeditions.gameplay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure closing-schedule math (Goal 04).
 *
 * The operator issues ONE close order; survivors get escalating warnings at
 * configured offsets before extraction. Emission is idempotent across restarts
 * because the last announced threshold is persisted with the lifecycle record.
 */
public final class ClosingSchedule {

    private ClosingSchedule() {}

    /**
     * Which warning thresholds must be emitted NOW.
     *
     * @param offsetsMinutes descending list of warning offsets (e.g. [15,5,1])
     * @param deadlineEpochMs closing deadline
     * @param nowEpochMs      current time
     * @param smallestAnnounced lowest threshold already announced (-1 = none yet);
     *                          thresholds above it never repeat
     * @return thresholds to announce now, descending order
     */
    public static List<Integer> dueWarnings(List<Integer> offsetsMinutes,
                                            long deadlineEpochMs,
                                            long nowEpochMs,
                                            int smallestAnnounced) {
        List<Integer> due = new ArrayList<>();
        if (deadlineEpochMs <= 0) return due;
        long remainingMs = deadlineEpochMs - nowEpochMs;
        List<Integer> sorted = new ArrayList<>(offsetsMinutes);
        Collections.sort(sorted, Collections.reverseOrder());
        for (int t : sorted) {
            long thresholdMs = t * 60_000L;
            boolean reached = remainingMs <= thresholdMs;
            boolean notYetAnnounced = smallestAnnounced == -1 || t < smallestAnnounced;
            if (reached && notYetAnnounced) {
                due.add(t);
            }
        }
        return due;
    }

    /** Smallest announced threshold after emitting {@code justEmitted}. */
    public static int advance(int smallestAnnounced, List<Integer> justEmitted) {
        int min = smallestAnnounced == -1 ? Integer.MAX_VALUE : smallestAnnounced;
        for (int t : justEmitted) min = Math.min(min, t);
        return min == Integer.MAX_VALUE ? smallestAnnounced : min;
    }

    /** True when extraction should run this tick. */
    public static boolean extractionDue(long deadlineEpochMs, long nowEpochMs) {
        return deadlineEpochMs > 0 && nowEpochMs >= deadlineEpochMs;
    }
}
