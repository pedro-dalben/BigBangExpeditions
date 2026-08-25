package com.bigbangcraft.expeditions.depletion;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic, fully-explained depletion evaluation outcome.
 */
public final class DepletionResult {
    public enum Health { HEALTHY, ACTIVE, DECLINING, DEPLETED, UNKNOWN }

    public final int generation;
    public final double score;                 // 0..100 among known weight
    public final Health health;
    public final boolean recommendClosure;
    public final List<String> blockers;        // why automation must NOT act
    public final List<Contribution> contributions;
    public final double knownWeight;
    public final long ageDaysX10;              // age in days * 10 for display
    public final String sustainedSummary;      // e.g. "2/3 sustained evaluations"

    public DepletionResult(int generation, double score, Health health, boolean recommendClosure,
                           List<String> blockers, List<Contribution> contributions,
                           double knownWeight, long ageDaysX10, String sustainedSummary) {
        this.generation = generation;
        this.score = score;
        this.health = health;
        this.recommendClosure = recommendClosure;
        this.blockers = List.copyOf(blockers);
        this.contributions = List.copyOf(contributions);
        this.knownWeight = knownWeight;
        this.ageDaysX10 = ageDaysX10;
        this.sustainedSummary = sustainedSummary == null ? "" : sustainedSummary;
    }

    /** Human-readable explanation block (requirement 11/48). */
    public List<String> explain() {
        List<String> lines = new ArrayList<>();
        lines.add(String.format("Expedition health: %s (score %.1f / threshold applied separately)",
                health, score));
        lines.add(String.format("Age: %.1f days", ageDaysX10 / 10.0));
        lines.add("Components:");
        for (Contribution c : contributions) {
            lines.add("  " + c.render());
        }
        if (!sustainedSummary.isEmpty()) {
            lines.add("Sustained condition: " + sustainedSummary);
        }
        if (blockers.isEmpty()) {
            lines.add(recommendClosure
                    ? "RECOMMENDATION: expedition renewal recommended"
                    : "RECOMMENDATION: continue operating");
        } else {
            lines.add("RECOMMENDATION: BLOCKED — " + String.join("; ", blockers));
        }
        return lines;
    }
}
