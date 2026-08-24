package com.bigbangcraft.expeditions.safety;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Aggregated preflight outcome. All validators run — failures never hide
 * later failures. Any ERROR means RESET REFUSED.
 */
public final class ResetPreflightResult {
    private final List<ValidationIssue> issues = new ArrayList<>();

    public void add(ValidationIssue issue) {
        issues.add(issue);
    }

    public void error(String code, String message) {
        add(ValidationIssue.error(code, message));
    }

    public void warn(String code, String message) {
        add(ValidationIssue.warn(code, message));
    }

    public boolean passed() {
        return issues.stream().noneMatch(i -> i.severity == ValidationIssue.Severity.ERROR);
    }

    public List<ValidationIssue> issues() {
        return Collections.unmodifiableList(issues);
    }

    public List<String> refusalReasons() {
        List<String> out = new ArrayList<>();
        for (ValidationIssue i : issues) {
            if (i.severity == ValidationIssue.Severity.ERROR) {
                out.add(i.code + ": " + i.message);
            }
        }
        return out;
    }
}
