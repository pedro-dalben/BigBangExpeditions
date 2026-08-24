package com.bigbangcraft.expeditions.gameplay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GameplayConfigTest {

    @Test
    void missingFileYieldsDefaults(@TempDir Path dir) {
        GameplayConfig c = GameplayConfig.load(dir.resolve("absent.properties"));
        assertEquals(List.of(15, 5, 1), c.closingWarningOffsetsMinutes());
        assertEquals(15, c.closingDurationMinutes());
        assertTrue(c.announcementsEnabled());
    }

    @Test
    void validValuesAreApplied(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("gameplay.properties");
        Files.writeString(f, """
                # comment
                closingDurationMinutes = 30
                closingWarningOffsetsMinutes = 20, 10, 5, 1
                announcementsEnabled = false
                soundEnabled = false
                openingAnnouncementEnabled = false
                """);
        GameplayConfig c = GameplayConfig.load(f);
        assertEquals(30, c.closingDurationMinutes());
        assertEquals(List.of(20, 10, 5, 1), c.closingWarningOffsetsMinutes());
        assertFalse(c.announcementsEnabled());
        assertFalse(c.soundEnabled());
        assertFalse(c.openingAnnouncementEnabled());
    }

    @Test
    void offsetsSortedDescendingAndFilteredByDuration(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("gameplay.properties");
        Files.writeString(f, "closingDurationMinutes=10\nclosingWarningOffsetsMinutes=15,5,2\n");
        GameplayConfig c = GameplayConfig.load(f);
        // offset beyond duration can never fire -> filtered out of the effective set
        assertEquals(List.of(5, 2), c.effectiveWarningOffsets());
    }

    @Test
    void invalidValuesFallBackWithNotices(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("gameplay.properties");
        Files.writeString(f, "closingDurationMinutes=-3\nclosingWarningOffsetsMinutes=abc\nannouncementsEnabled=maybe\n");
        GameplayConfig c = GameplayConfig.load(f);
        assertEquals(15, c.closingDurationMinutes());
        assertEquals(List.of(15, 5, 1), c.closingWarningOffsetsMinutes());
        assertTrue(c.announcementsEnabled());
        assertFalse(c.notices().isEmpty(), "fallbacks must be visible to operators");
    }
}
