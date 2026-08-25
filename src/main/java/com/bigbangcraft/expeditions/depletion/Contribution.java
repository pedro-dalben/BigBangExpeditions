package com.bigbangcraft.expeditions.depletion;

/**
 * One scored component of the depletion evaluation (Goal 05 requirement 11).
 *
 * <p>Every contribution carries its observed fact, the policy threshold it was
 * judged against, and the points it earned — an administrator must be able to
 * reconstruct the conclusion WITHOUT reading logs.
 */
public final class Contribution {
    public enum Status { KNOWN, UNKNOWN, DISABLED }

    public final String component;
    public final Status status;
    /** Human-readable observed value ("62%", "none recorded", "signal absent"). */
    public final String observed;
    /** Human-readable policy threshold applied. */
    public final String threshold;
    /** Raw component score 0..100; 0 when UNKNOWN/DISABLED. */
    public final double rawScore;
    /** Weight assigned by policy; weight is redistributed away from non-KNOWN parts. */
    public final double weight;
    /** Points earned = rawScore/100 * effectiveWeight. */
    public final double points;
    public final String note;

    public Contribution(String component, Status status, String observed, String threshold,
                        double rawScore, double weight, double points, String note) {
        this.component = component;
        this.status = status;
        this.observed = observed;
        this.threshold = threshold;
        this.rawScore = rawScore;
        this.weight = weight;
        this.points = points;
        this.note = note == null ? "" : note;
    }

    static Contribution unknown(String component, double weight, String observed, String note) {
        return new Contribution(component, Status.UNKNOWN, observed, "-", 0, weight, 0, note);
    }

    static Contribution disabled(String component, String note) {
        return new Contribution(component, Status.DISABLED, "disabled", "-", 0, 0, 0, note);
    }

    public String render() {
        return String.format("%-12s %5.1f/%4.1f  %-9s observed=%s threshold=%s%s",
                component, points, weight, status, observed,
                threshold, note.isEmpty() ? "" : " (" + note + ")");
    }
}
