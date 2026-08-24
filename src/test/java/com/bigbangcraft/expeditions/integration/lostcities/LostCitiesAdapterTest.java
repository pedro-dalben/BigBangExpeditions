package com.bigbangcraft.expeditions.integration.lostcities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lost Cities adapter contract tests. The plain JUnit env has no Lost Cities
 * on the classpath, so isAvailable() must be false and everything downstream
 * must fail closed.
 */
class LostCitiesAdapterTest {

    @Test
    void unavailableInPlainEnv() {
        assertFalse(LostCitiesAdapter.isAvailable(), "LC must not be resolvable in test env");
    }

    @Test
    void profileEmptyWhenUnavailable() {
        // String-based path: must return empty without constructing MC registry keys
        assertTrue(LostCitiesAdapter.getProfileById(LostCitiesAdapter.expeditionDimensionId()).isEmpty());
    }

    @Test
    void nullLevelFailsClosed() {
        assertFalse(LostCitiesAdapter.isLostCitiesWorld(null));
        List<String> failures = LostCitiesAdapter.validateExpectedProfile(null, "deceasedcraft_onlycities");
        assertFalse(failures.isEmpty(), "null level must refuse");
    }

    @Test
    void fingerprintDeterministicForRealFile(@TempDir Path tmp) throws Exception {
        Path profiles = tmp.resolve("profiles");
        Files.createDirectories(profiles);
        Files.writeString(profiles.resolve("p1.json"), "{\"cityChance\":1.0}\n");
        Optional<String> f1 = LostCitiesAdapter.getProfileFingerprint(tmp, "p1");
        Optional<String> f2 = LostCitiesAdapter.getProfileFingerprint(tmp, "p1");
        assertTrue(f1.isPresent());
        assertEquals(f1.get(), f2.get());
        assertEquals(64, f1.get().length());
        assertDoesNotThrow(() -> java.security.MessageDigest.getInstance("SHA-256")
                .digest(java.nio.ByteBuffer.allocate(0).array()));
    }

    @Test
    void fingerprintChangesWithContent(@TempDir Path tmp) throws Exception {
        Path profiles = tmp.resolve("profiles");
        Files.createDirectories(profiles);
        Files.writeString(profiles.resolve("pa.json"), "a");
        Files.writeString(profiles.resolve("pb.json"), "b");
        assertNotEquals(
            LostCitiesAdapter.getProfileFingerprint(tmp, "pa").orElseThrow(),
            LostCitiesAdapter.getProfileFingerprint(tmp, "pb").orElseThrow());
    }

    @Test
    void fingerprintEmptyForMissingOrUnsafeNames(@TempDir Path tmp) {
        assertTrue(LostCitiesAdapter.getProfileFingerprint(tmp, "does_not_exist").isEmpty());
        assertTrue(LostCitiesAdapter.getProfileFingerprint(tmp, "../evil").isEmpty(),
            "path traversal in profile name must yield empty, never a resolved path");
        assertTrue(LostCitiesAdapter.getProfileFingerprint(tmp, "").isEmpty());
        assertTrue(LostCitiesAdapter.getProfileFingerprint(null, "x").isEmpty());
    }

    @Test
    void expeditionDimensionIds() {
        // ResourceLocation only — safe without MC bootstrap
        assertEquals("bigbangexpeditions:expedition", LostCitiesAdapter.expeditionDimensionId().toString());
        assertEquals("bigbangexpeditions", LostCitiesAdapter.expeditionDimensionId().getNamespace());
        assertEquals("expedition", LostCitiesAdapter.expeditionDimensionId().getPath());
    }

    @Test
    void expectedProfileConstantMatchesPackEvidence() {
        // Evidence: config/lostcities/profiles/deceasedcraft_onlycities.json exists in pack,
        // cityChance=1.0, worldStyle deceasedcraft:modern (Goal 02 Phase 3 investigation).
        assertEquals("deceasedcraft_onlycities", LostCitiesAdapter.EXPECTED_EXPEDITION_PROFILE);
    }
}
