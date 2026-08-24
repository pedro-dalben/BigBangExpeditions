package com.bigbangcraft.expeditions.env;

/**
 * Operational environments for Goal 03+. Destructive reset behavior is enabled
 * ONLY in {@link #PRODUCTION}. Every other profile must behave identically to
 * production up to the point of destruction and then refuse or simulate.
 *
 * Default is always STAGING: absence of configuration can never activate
 * destructive behavior.
 */
public enum EnvironmentProfile {
    STAGING,
    PRODUCTION_DRY_RUN,
    PRODUCTION;

    /** True when filesystem-destructive operations may actually run. */
    public boolean destructiveAllowed() {
        return this == PRODUCTION;
    }

    /** True when the full real decision pipeline should execute (dry-run included). */
    public boolean realPipeline() {
        return this != STAGING;
    }
}
