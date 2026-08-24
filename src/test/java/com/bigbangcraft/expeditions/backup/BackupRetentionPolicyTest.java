package com.bigbangcraft.expeditions.backup;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BackupRetentionPolicyTest {

    private BackupRetentionPolicy.Summary summary(String id, boolean verified, long at) {
        return new BackupRetentionPolicy.Summary(id, verified, at, false);
    }

    @Test
    void newestVerifiedNeverDeleted() {
        var policy = new BackupRetentionPolicy(1);
        List<String> out = policy.deletable(List.of(
                summary("old", true, 1),
                summary("new", true, 2)));
        assertEquals(List.of("old"), out);
        assertFalse(out.contains("new"), "sole/newest rollback point is sacred");
    }

    @Test
    void keepCountRespected() {
        var policy = new BackupRetentionPolicy(2);
        List<String> out = policy.deletable(List.of(
                summary("a", true, 1),
                summary("b", true, 2),
                summary("c", true, 3)));
        assertEquals(List.of("a"), out);
        assertFalse(out.contains("c"));
        assertFalse(out.contains("b"));
    }

    @Test
    void unverifiedJunkAlwaysDeletableExceptWhenItIsTheOnlyOneAndNewestValid() {
        var policy = new BackupRetentionPolicy(3);
        List<String> out = policy.deletable(List.of(
                summary("junk", false, 5),
                summary("valid", true, 4)));
        assertTrue(out.contains("junk"));
        assertFalse(out.contains("valid"));
    }

    @Test
    void noVerifiedBackupsMeansOnlyJunkCleaned() {
        var policy = new BackupRetentionPolicy(1);
        List<String> out = policy.deletable(List.of(
                summary("j1", false, 1),
                summary("j2", false, 2)));
        assertEquals(java.util.Set.of("j1", "j2"), new java.util.HashSet<>(out));
    }

    @Test
    void singleVerifiedBackupNeverDeleted() {
        var policy = new BackupRetentionPolicy(1);
        List<String> out = policy.deletable(List.of(summary("only", true, 1)));
        assertTrue(out.isEmpty());
    }

    @Test
    void keepAtLeastOneIsEnforcedByConstructor() {
        assertThrows(IllegalArgumentException.class, () -> new BackupRetentionPolicy(0));
    }

    @Test
    void mixedAgesDeleteOldestFirst() {
        var policy = new BackupRetentionPolicy(2);
        List<String> out = policy.deletable(List.of(
                summary("v1", true, 10),
                summary("v2", true, 20),
                summary("v3", true, 30),
                summary("v4", true, 40)));
        assertEquals(List.of("v1", "v2"), out);
    }
}
