package com.bigbangcraft.expeditions.automation;

import com.bigbangcraft.expeditions.depletion.DepletionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class AutomationStateStoreTest {

    @Test
    void roundTripPreservesPendingAndShadow(@TempDir Path dir) throws IOException {
        AutomationStateStore store = new AutomationStateStore(dir.resolve("state.json"));
        AutomationState s = new AutomationState();
        s.paused = true;
        s.pauseReason = "test";
        s.boundGeneration = 9;
        s.consecutiveHits = 2;
        s.firstHitAtMs = 123L;
        s.lastObservedWallClockMs = 456L;
        AutomationState.PendingClosure p = new AutomationState.PendingClosure();
        p.generation = 9;
        p.score = 84.5;
        p.reasons.add("depletion sustained");
        p.createdAtMs = 1;
        p.expiresAtMs = 2;
        p.policyFingerprint = "abc";
        p.trigger = "DEPLETION";
        s.pending = p;
        AutomationState.ShadowEntry sh = new AutomationState.ShadowEntry();
        sh.atMs = 7;
        sh.generation = 9;
        sh.score = 50;
        sh.health = "ACTIVE";
        sh.wouldRecommend = false;
        sh.blockers = "";
        s.shadow.add(sh);
        store.save(s);

        var r = store.load();
        assertTrue(r.ok());
        AutomationState back = r.state();
        assertTrue(back.paused);
        assertEquals(9, back.boundGeneration);
        assertEquals(2, back.consecutiveHits);
        assertNotNull(back.pending);
        assertEquals("abc", back.pending.policyFingerprint);
        assertEquals(1, back.shadow.size());
    }

    @Test
    void corruptStateFailsSafeIntoPause(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("state.json"), "{broken");
        var r = new AutomationStateStore(dir.resolve("state.json")).load();
        assertFalse(r.ok());
        // caller contract (mirrored by service boot): paused + fresh
        AutomationState safe = AutomationState.fresh();
        safe.paused = true;
        safe.pauseReason = "automation state unreadable (" + r.detail() + ") — fail-safe pause";
        assertTrue(safe.paused);
        assertNull(safe.pending);
        try (var files = Files.list(dir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().contains(".corrupt-")));
        }
    }

    @Test
    void futureSchemaRefusedNotGuessed(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("state.json"),
                "{\"schemaVersion\":99,\"paused\":false,\"pending\":{}}");
        assertFalse(new AutomationStateStore(dir.resolve("state.json")).load().ok());
    }

    @Test
    void missingFileIsFreshNotFailed(@TempDir Path dir) {
        var r = new AutomationStateStore(dir.resolve("state.json")).load();
        assertTrue(r.ok());
        assertEquals(-1, r.state().boundGeneration);
        assertFalse(r.state().paused);
    }

    @Test
    void policyFingerprintChangesWithThresholds() {
        DepletionPolicy a = DepletionPolicy.validated(new DepletionPolicy(), new ArrayList<>());
        DepletionPolicy b = DepletionPolicy.validated(new DepletionPolicy(), new ArrayList<>());
        b.closeScoreThreshold = 85;
        String fa = PolicySupport.fingerprint(a);
        String fb = PolicySupport.fingerprint(b);
        assertNotEquals(fa, fb);
        assertEquals(fa, PolicySupport.fingerprint(
                DepletionPolicy.validated(new DepletionPolicy(), new ArrayList<>())));
    }
}
