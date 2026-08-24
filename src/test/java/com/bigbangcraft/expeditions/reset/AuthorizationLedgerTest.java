package com.bigbangcraft.expeditions.reset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AuthorizationLedgerTest {

    @TempDir
    Path tmp;

    private Path file() {
        return tmp.resolve("ledger/authorization-ledger.json");
    }

    @Test
    void issuedThenConsumedExactlyOnce() throws IOException {
        AuthorizationLedger l = new AuthorizationLedger(file());
        assertTrue(l.recordIssued("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", 3, "admin", 1L).isEmpty());

        assertTrue(l.consume("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "executor", 2L).isEmpty());
        var second = l.consume("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "executor", 3L);
        assertTrue(second.isPresent(), "second consumption must fail");
        assertTrue(second.get().contains("not consumable"));
    }

    @Test
    void unknownAuthCannotBeConsumed() throws IOException {
        AuthorizationLedger l = new AuthorizationLedger(file());
        assertTrue(l.consume("11111111-2222-3333-4444-555555555555", "x", 1L).isPresent());
    }

    @Test
    void duplicateIssueRefused() throws IOException {
        String id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        AuthorizationLedger l = new AuthorizationLedger(file());
        assertTrue(l.recordIssued(id, 0, "a", 1L).isEmpty());
        assertTrue(l.recordIssued(id, 0, "b", 2L).isPresent());
    }

    @Test
    void revokeBlocksConsumption() throws IOException {
        String id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        AuthorizationLedger l = new AuthorizationLedger(file());
        l.recordIssued(id, 5, "admin", 1L);
        assertTrue(l.revoke(id, "admin", 2L).isEmpty());
        assertTrue(l.consume(id, "executor", 3L).isPresent());

        var e = l.get(id);
        assertEquals(AuthorizationLedger.Status.REVOKED, e.status);
    }

    @Test
    void consumedAuthCannotBeRevoked() throws IOException {
        String id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        AuthorizationLedger l = new AuthorizationLedger(file());
        l.recordIssued(id, 5, "admin", 1L);
        l.consume(id, "executor", 2L);
        assertTrue(l.revoke(id, "admin", 3L).isPresent());
    }

    @Test
    void persistsAcrossInstances() throws IOException {
        String id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeef0";
        new AuthorizationLedger(file()).recordIssued(id, 7, "admin", 1L);

        AuthorizationLedger reopened = new AuthorizationLedger(file());
        var e = reopened.get(id);
        assertNotNull(e);
        assertEquals(7, e.generationAtIssue);
        assertEquals(AuthorizationLedger.Status.ISSUED, e.status);

        assertTrue(reopened.consume(id, "executor", 9L).isEmpty());

        AuthorizationLedger again = new AuthorizationLedger(file());
        assertTrue(again.consume(id, "executor", 10L).isPresent());
    }

    @Test
    void corruptLedgerFailsClosed() throws IOException {
        Files.writeString(tmp.resolve("corrupt.json"), "{{{{");
        assertThrows(Exception.class, () -> new AuthorizationLedger(tmp.resolve("corrupt.json")).get("x"));
    }
}
