package com.bigbangcraft.expeditions.env;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EnvironmentConfigTest {

    private static final String TOKEN = "abc123def456";

    @Test
    void emptyPropsDefaultToStaging() {
        EnvironmentConfig c = EnvironmentConfig.resolve(Map.of(), null, TOKEN);
        assertEquals(EnvironmentProfile.STAGING, c.profile());
        assertTrue(c.notices().isEmpty());
    }

    @Test
    void explicitStagingResolves() {
        EnvironmentConfig c = EnvironmentConfig.resolve(
                Map.of(EnvironmentConfig.KEY_ENVIRONMENT, "staging"), null, TOKEN);
        assertEquals(EnvironmentProfile.STAGING, c.profile());
    }

    @Test
    void blankEnvironmentFallsBackWithNotice() {
        Map<String, String> props = new HashMap<>();
        props.put(EnvironmentConfig.KEY_ENVIRONMENT, "   ");
        EnvironmentConfig c = EnvironmentConfig.resolve(props, null, TOKEN);
        assertEquals(EnvironmentProfile.STAGING, c.profile());
        assertEquals(1, c.notices().size());
    }

    @Test
    void unknownValueFailsClosedToStaging() {
        for (String weird : List.of("prod", "yes", "true", "staging-prod")) {
            EnvironmentConfig c = EnvironmentConfig.resolve(
                    Map.of(EnvironmentConfig.KEY_ENVIRONMENT, weird), TOKEN, TOKEN);
            assertEquals(EnvironmentProfile.STAGING, c.profile(), () -> "raw=" + weird);
            assertFalse(c.notices().isEmpty());
        }
    }

    @Test
    void dryRunNeedsNoAcknowledgment() {
        for (String v : List.of("production-dry-run", "production_dry_run", "dry-run")) {
            EnvironmentConfig c = EnvironmentConfig.resolve(
                    Map.of(EnvironmentConfig.KEY_ENVIRONMENT, v), null, null);
            assertEquals(EnvironmentProfile.PRODUCTION_DRY_RUN, c.profile(), () -> "v=" + v);
            assertTrue(c.notices().isEmpty());
        }
    }

    @Test
    void productionWithoutAckRefusesActivation() {
        EnvironmentConfig c = EnvironmentConfig.resolve(
                Map.of(EnvironmentConfig.KEY_ENVIRONMENT, "production"), null, TOKEN);
        assertEquals(EnvironmentProfile.STAGING, c.profile());
        assertTrue(c.notices().get(0).contains("refusing activation"));
    }

    @Test
    void productionWithWrongAckRefusesActivation() {
        EnvironmentConfig c = EnvironmentConfig.resolve(
                Map.of(EnvironmentConfig.KEY_ENVIRONMENT, "production"),
                "deadbeef0000\n", TOKEN);
        assertEquals(EnvironmentProfile.STAGING, c.profile());
        assertTrue(c.notices().get(0).contains("<mismatch>"));
    }

    @Test
    void productionWithMatchingAckActivates() {
        EnvironmentConfig c = EnvironmentConfig.resolve(
                Map.of(EnvironmentConfig.KEY_ENVIRONMENT, "production"), TOKEN + "\n", TOKEN);
        assertEquals(EnvironmentProfile.PRODUCTION, c.profile());
        assertTrue(c.notices().isEmpty());
    }

    @Test
    void productionWithoutExpectedTokenCannotActivate() {
        // fingerprint unavailable -> cannot verify -> never production
        EnvironmentConfig c = EnvironmentConfig.resolve(
                Map.of(EnvironmentConfig.KEY_ENVIRONMENT, "production"), TOKEN, "");
        assertEquals(EnvironmentProfile.STAGING, c.profile());
    }

    @Test
    void destructiveAllowedOnlyInProduction() {
        assertFalse(EnvironmentProfile.STAGING.destructiveAllowed());
        assertFalse(EnvironmentProfile.PRODUCTION_DRY_RUN.destructiveAllowed());
        assertTrue(EnvironmentProfile.PRODUCTION.destructiveAllowed());
        assertTrue(EnvironmentProfile.PRODUCTION_DRY_RUN.realPipeline());
        assertFalse(EnvironmentProfile.STAGING.realPipeline());
    }
}
