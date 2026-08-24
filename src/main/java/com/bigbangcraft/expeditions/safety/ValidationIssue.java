package com.bigbangcraft.expeditions.safety;

/** One structured finding of the preflight pipeline. */
public final class ValidationIssue {
    public enum Severity { ERROR, WARN }

    public final Severity severity;
    public final String code;
    public final String message;

    public ValidationIssue(Severity severity, String code, String message) {
        this.severity = severity;
        this.code = code;
        this.message = message;
    }

    public static ValidationIssue error(String code, String message) {
        return new ValidationIssue(Severity.ERROR, code, message);
    }

    public static ValidationIssue warn(String code, String message) {
        return new ValidationIssue(Severity.WARN, code, message);
    }

    @Override
    public String toString() {
        return "[" + severity + "] " + code + ": " + message;
    }
}
