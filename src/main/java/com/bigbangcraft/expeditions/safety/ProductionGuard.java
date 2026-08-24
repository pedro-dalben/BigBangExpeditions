package com.bigbangcraft.expeditions.safety;

import java.util.Map;

/**
 * Production guard: destructive reset is DISABLED unless an explicit,
 * file-backed configuration enables it. Goal 02 never enables it.
 *
 * Config source: <server>/config/bigbangexpeditions/safety.properties
 *   allowDestructiveReset = false   (default, and the Goal 02 value)
 *   stagingSentinelDir     = ...    (path that must contain .bigbangexpeditions-staging)
 */
public final class ProductionGuard {
    public static final String DEFAULT_SENTINEL_NAME = ".bigbangexpeditions-staging";

    private final boolean allowDestructiveReset;
    private final boolean production;

    public ProductionGuard(boolean allowDestructiveReset, boolean production) {
        this.allowDestructiveReset = allowDestructiveReset;
        this.production = production;
    }

    public static ProductionGuard goal02Defaults() {
        // PRODUCTION RESET = DISABLED for this entire goal
        return new ProductionGuard(false, false);
    }

    public boolean destructiveAllowed() {
        return allowDestructiveReset;
    }

    public boolean isProduction() {
        return production;
    }

    /** Convenience: refuse text used across scripts and commands. */
    public static String refusal(String reason) {
        return "RESET REFUSED — " + reason;
    }

    /** Validates a parsed config map; unknown keys ignored. */
    public static ProductionGuard fromConfig(Map<String, String> cfg) {
        boolean allow = "true".equalsIgnoreCase(cfg.getOrDefault("allowDestructiveReset", "false").trim());
        boolean prod = "true".equalsIgnoreCase(cfg.getOrDefault("production", "false").trim());
        if (prod && allow) {
            throw new IllegalStateException(
                    "refusing config: production=true together with allowDestructiveReset=true");
        }
        return new ProductionGuard(allow, prod);
    }
}
