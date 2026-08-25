package com.bigbangcraft.expeditions.automation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AutomationConfigTest {

    @Test
    void defaultsAreSafe(@TempDir Path dir) {
        AutomationConfig c = AutomationConfig.load(dir.resolve("absent.properties"));
        assertEquals("MANUAL", c.automationMode());
        assertEquals(AutomationConfig.DEFAULT_FLUSH_SECONDS, c.flushIntervalSeconds());
        assertEquals(AutomationConfig.DEFAULT_SAMPLE_SECONDS, c.sampleIntervalSeconds());
        assertTrue(c.notices().isEmpty());
    }

    @Test
    void validFileApplied(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("automation.properties");
        Files.writeString(f, """
                # goal-05 policy
                telemetry.flushSeconds=45
                telemetry.sampleSeconds=3
                automation.mode=ADVISORY
                """);
        AutomationConfig c = AutomationConfig.load(f);
        assertEquals(45, c.flushIntervalSeconds());
        assertEquals(3, c.sampleIntervalSeconds());
        assertEquals("ADVISORY", c.automationMode());
        assertTrue(c.notices().isEmpty());
    }

    @Test
    void invalidValuesFallBackWithNotice(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("automation.properties");
        Files.writeString(f, """
                telemetry.flushSeconds=99999
                telemetry.sampleSeconds=0
                telemetry.structureSignalGraceChunks=bogus
                """);
        AutomationConfig c = AutomationConfig.load(f);
        assertEquals(AutomationConfig.DEFAULT_FLUSH_SECONDS, c.flushIntervalSeconds());
        assertEquals(AutomationConfig.DEFAULT_SAMPLE_SECONDS, c.sampleIntervalSeconds());
        assertEquals(AutomationConfig.DEFAULT_STRUCTURE_SIGNAL_GRACE_CHUNKS, c.structureSignalGraceChunks());
        assertEquals(3, c.notices().size());
    }

    @Test
    void unknownAutomationModeForcedManualFailClosed(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("automation.properties");
        Files.writeString(f, "automation.mode=AUTOMATIC_RENEWAL_NUKE\n");
        assertEquals("MANUAL", AutomationConfig.load(f).automationMode());

        Files.writeString(f, "automation.mode=automatic_closure\n");
        assertEquals("AUTOMATIC_CLOSURE", AutomationConfig.load(f).automationMode());
    }

    @Test
    void unreadableFileFailsSafeToDefaults(@TempDir Path dir) {
        // simulate unreadable by pointing at a directory instead of a file
        AutomationConfig c = AutomationConfig.load(dir);
        assertTrue(c.notices().size() <= 1);
        assertEquals("MANUAL", c.automationMode());
        assertEquals(AutomationConfig.DEFAULT_FLUSH_SECONDS, c.flushIntervalSeconds());
    }

    @Test
    void snapshotCoversAllSections() {
        Map<String, String> snap = AutomationConfig.defaults().snapshot();
        assertTrue(snap.containsKey("telemetry.flushSeconds"));
        assertTrue(snap.containsKey("automation.mode"));
        assertEquals("MANUAL", snap.get("automation.mode"));
    }
}
