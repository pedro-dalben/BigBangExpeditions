package com.bigbangcraft.expeditions.integration.opac;

import java.util.ArrayList;
import java.util.List;

/**
 * Fail-closed result. If available==false, caller must REFUSE.
 */
public final class ClaimInspectionResult {
    public enum Status { AVAILABLE, UNAVAILABLE }

    private final Status status;
    private final String unavailableReason;
    private final int intersectingChunks;
    private final int forceloadChunks;
    private final List<String> details = new ArrayList<>();

    private ClaimInspectionResult(Status status, String reason, int intersecting, int forceload) {
        this.status = status;
        this.unavailableReason = reason;
        this.intersectingChunks = intersecting;
        this.forceloadChunks = forceload;
    }

    public static ClaimInspectionResult available(int intersecting, int forceload, List<String> details) {
        ClaimInspectionResult r = new ClaimInspectionResult(Status.AVAILABLE, null, intersecting, forceload);
        if (details != null) r.details.addAll(details);
        return r;
    }

    public static ClaimInspectionResult unavailable(String reason) {
        return new ClaimInspectionResult(Status.UNAVAILABLE, reason, 0, 0);
    }

    public boolean isAvailable() { return status == Status.AVAILABLE; }
    public Status status() { return status; }
    public String unavailableReason() { return unavailableReason; }
    public int intersectingChunks() { return intersectingChunks; }
    public int forceloadChunks() { return forceloadChunks; }
    public List<String> details() { return details; }
    public boolean intersects() { return intersectingChunks > 0; }
    public boolean hasForceloads() { return forceloadChunks > 0; }
}
