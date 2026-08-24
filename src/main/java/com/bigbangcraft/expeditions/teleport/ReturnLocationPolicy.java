package com.bigbangcraft.expeditions.teleport;

import java.util.Optional;

/**
 * Pure policy for validating a stored return position before reuse (Goal 04).
 *
 * A returning player must never be teleported into a void, above build height,
 * below the world, or into a dimension that no longer exists. What CAN be
 * checked purely: finiteness (already enforced by deserialization), vertical
 * bounds against the target world, and presence of a stored destination at
 * all. Block-level safety (suffocation) needs live world access and is applied
 * by the adapter on top of this decision.
 */
public final class ReturnLocationPolicy {

    public enum Verdict { ACCEPT, FALLBACK }

    private ReturnLocationPolicy() {}

    /**
     * Decides whether {@code rp} may be used directly.
     *
     * @param rp              stored return position (may be empty = never stored)
     * @param dimensionExists does the stored dimension resolve on this server?
     * @param minY            target world min build height
     * @param maxY            target world max build height
     * @return ACCEPT with the position to use, or FALLBACK with reason
     */
    public static Result evaluate(Optional<ReturnPosition> rp,
                                  boolean dimensionExists,
                                  int minY, int maxY) {
        if (rp.isEmpty()) {
            return new Result(Verdict.FALLBACK, "no_stored_position");
        }
        ReturnPosition p = rp.get();
        if (!dimensionExists) {
            return new Result(Verdict.FALLBACK, "stale_dimension");
        }
        // allow one block of tolerance for surface spawns recorded on slabs etc.
        if (p.y < minY - 1 || p.y > maxY + 1) {
            return new Result(Verdict.FALLBACK, "out_of_bounds");
        }
        return new Result(Verdict.ACCEPT, "");
    }

    public record Result(Verdict verdict, String fallbackReason) {
        public boolean accepted() {
            return verdict == Verdict.ACCEPT;
        }
    }
}
