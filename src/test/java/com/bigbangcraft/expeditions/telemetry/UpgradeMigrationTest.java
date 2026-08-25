package com.bigbangcraft.expeditions.telemetry;

import com.bigbangcraft.expeditions.automation.AutomationConfig;
import com.bigbangcraft.expeditions.automation.AutomationState;
import com.bigbangcraft.expeditions.automation.AutomationStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Goal 05 upgrade compatibility (requirements 42/47/64/65): a Goal 04 world
 * upgrading onto Goal 05 must boot conservative and untouched; unknown future
 * schemas must fail safe rather than be guessed into operation.
 */
class UpgradeMigrationTest {
    private static final int GEN = 7;

    @TempDir Path dir;

    @Test
    void goal04World_hasNoTelemetryFiles_freshStoreUsable() throws IOException {
        Files.createDirectories(dir); // empty bigbangexpeditions dir
        var r = new TelemetryStore(dir.resolve("telemetry")).load(GEN);
        assertEquals(TelemetryStore.Status.MISSING, r.status);
        assertTrue(r.usable());
        assertEquals(0, r.record.entriesTotal);
        assertEquals(GEN, r.record.generation);
    }

    @Test
    void goal05DefaultModeIsManual_afterUpgrade() {
        AutomationConfig c = AutomationConfig.load(dir.resolve("absent.properties"));
        assertEquals("MANUAL", c.automationMode(), "upgrade must not begin automation");
    }

    @Test
    void legacyTelemetryWithoutSchemaVersionLoadsAndNormalizes() throws IOException {
        Path tdir = dir.resolve("telemetry");
        Files.createDirectories(tdir);
        // a hypothetical pre-schema draft: no schemaVersion field at all
        Files.writeString(tdir.resolve("gen-" + GEN + ".json"),
                "{\"generation\":" + GEN + ",\"entriesTotal\":3}");
        var r = new TelemetryStore(tdir).load(GEN);
        assertEquals(TelemetryStore.Status.AVAILABLE, r.status);
        assertEquals(1, r.record.schemaVersion); // migrated marker on load
        assertEquals(3, r.record.entriesTotal);
    }

    @Test
    void rollbackScenario_futureSchemaNeverOperational() throws IOException {
        Path tdir = dir.resolve("telemetry");
        Files.createDirectories(tdir);
        Files.writeString(tdir.resolve("gen-" + GEN + ".json"),
                "{\"schemaVersion\":99,\"generation\":" + GEN + "}");
        var r = new TelemetryStore(tdir).load(GEN);
        assertFalse(r.usable(), "downgrade/rollback artifact must block, not operate");
        assertTrue(Files.exists(tdir.resolve("gen-" + GEN + ".json")), "bytes preserved for operator");
    }

    @Test
    void rollbackScenario_automationStateFutureSchemaRefused() throws IOException {
        Path f = dir.resolve("automation-state.json");
        Files.writeString(f, "{\"schemaVersion\":42,\"paused\":false}");
        var r = new AutomationStateStore(f).load();
        assertFalse(r.ok());
        assertNull(r.state()); // caller boots fail-safe paused
    }

    @Test
    void freshInstall_automationStateDefaultsUnpaused() {
        var r = new AutomationStateStore(dir.resolve("automation-state.json")).load();
        assertTrue(r.ok());
        assertFalse(r.state().paused);
        assertEquals(AutomationState.SCHEMA_VERSION, r.state().schemaVersion);
    }
}
