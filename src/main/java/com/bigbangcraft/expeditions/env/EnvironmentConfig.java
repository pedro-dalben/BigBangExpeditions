package com.bigbangcraft.expeditions.env;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Resolves the active {@link EnvironmentProfile} from configuration.
 *
 * Resolution rules (fail-closed, default-off):
 * <ul>
 *   <li>Missing/blank config resolves to STAGING.</li>
 *   <li>Unknown values resolve to STAGING with a notice — never guess upward.</li>
 *   <li>PRODUCTION additionally requires an acknowledgment token equal to the
 *       current install fingerprint's short hash, supplied by the operator-created
 *       file {@code config/bigbangexpeditions/production.enabled}. Two independent
 *       signals (config value + ack file matching THIS installation) are required;
 *       a single flipped boolean can never activate destruction.</li>
 *   <li>PRODUCTION_DRY_RUN needs no acknowledgment: it cannot destroy anything.</li>
 * </ul>
 */
public final class EnvironmentConfig {
    public static final String KEY_ENVIRONMENT = "environment";
    public static final String DEFAULT_RAW = "staging";

    private final EnvironmentProfile profile;
    private final List<String> notices;

    private EnvironmentConfig(EnvironmentProfile profile, List<String> notices) {
        this.profile = profile;
        this.notices = notices;
    }

    public EnvironmentProfile profile() {
        return profile;
    }

    public List<String> notices() {
        return notices;
    }

    /**
     * @param props            parsed environment.properties (may be empty/null)
     * @param ackFileContent   trimmed content of production.enabled (null when absent)
     * @param expectedAckToken short hash of the CURRENT install fingerprint
     */
    public static EnvironmentConfig resolve(Map<String, String> props,
                                            String ackFileContent,
                                            String expectedAckToken) {
        List<String> notices = new ArrayList<>();
        String raw = props == null ? null : props.get(KEY_ENVIRONMENT);
        if (raw == null || raw.isBlank()) {
            if (raw != null) { // present but blank
                notices.add("environment key blank — using STAGING");
            }
            raw = DEFAULT_RAW;
        }
        String v = raw.trim().toLowerCase();
        switch (v) {
            case "staging":
                return new EnvironmentConfig(EnvironmentProfile.STAGING, notices);
            case "production-dry-run", "production_dry_run", "dry-run":
                return new EnvironmentConfig(EnvironmentProfile.PRODUCTION_DRY_RUN, notices);
            case "production":
                String ack = ackFileContent == null ? "" : ackFileContent.trim();
                if (expectedAckToken == null || expectedAckToken.isBlank()) {
                    notices.add("PRODUCTION requested but no install fingerprint available to verify "
                            + "acknowledgment against — refusing activation, using STAGING");
                    return new EnvironmentConfig(EnvironmentProfile.STAGING, notices);
                }
                if (!ack.equals(expectedAckToken)) {
                    notices.add("PRODUCTION requested but production.enabled acknowledgment does not match "
                            + "this installation (" + (ack.isEmpty() ? "<empty>" : "<mismatch>") + " != " + expectedAckToken
                            + ") — refusing activation, using STAGING");
                    return new EnvironmentConfig(EnvironmentProfile.STAGING, notices);
                }
                return new EnvironmentConfig(EnvironmentProfile.PRODUCTION, notices);
            default:
                notices.add("unknown environment '" + raw + "' — fail-closed to STAGING");
                return new EnvironmentConfig(EnvironmentProfile.STAGING, notices);
        }
    }
}
