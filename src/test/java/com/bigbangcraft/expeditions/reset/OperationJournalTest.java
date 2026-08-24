package com.bigbangcraft.expeditions.reset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class OperationJournalTest {

    @TempDir
    Path tmp;

    private OperationJournal journal() {
        return new OperationJournal(tmp.resolve("journal"));
    }

    @Test
    void lazyCreationAndPhaseOrdering() throws IOException {
        String id = "aaaaaaaa-bbbb-cccc-dddd-000000000001";
        assertNull(journal().load(id));
        assertFalse(journal().hasPhase(id, OperationJournal.PHASE_BACKUP_DONE));

        journal().recordCompleted(id, OperationJournal.PHASE_AUTH_VERIFIED, 1);
        journal().recordCompleted(id, OperationJournal.PHASE_BACKUP_START, 2);
        journal().recordCompleted(id, OperationJournal.PHASE_BACKUP_DONE, 3);

        var j = journal().load(id);
        assertNotNull(j);
        assertEquals(3, j.phases.size());
        assertEquals(OperationJournal.PHASE_BACKUP_DONE, j.phases.get(2).name);
        assertTrue(journal().hasPhase(id, OperationJournal.PHASE_AUTH_VERIFIED));
    }

    @Test
    void summarizeLatestPicksMostRecent() throws IOException {
        journal().recordCompleted("aaaaaaaa-bbbb-cccc-dddd-00000000000a", OperationJournal.PHASE_FINALIZED, 1);
        journal().recordCompleted("aaaaaaaa-bbbb-cccc-dddd-00000000000b", OperationJournal.PHASE_DELETION_INTENT, 2);

        var s = journal().summarizeLatest();
        assertNotNull(s);
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-00000000000b", s.authId());
        assertTrue(s.hasActiveOp());
        assertEquals(OperationJournal.PHASE_DELETION_INTENT, s.lastCompletedPhase());

        // finalize it -> no longer active
        journal().recordCompleted("aaaaaaaa-bbbb-cccc-dddd-00000000000b", OperationJournal.PHASE_DELETION_DONE, 3);
        journal().recordCompleted("aaaaaaaa-bbbb-cccc-dddd-00000000000b", OperationJournal.PHASE_LIFECYCLE_RESETTING, 4);
        journal().recordCompleted("aaaaaaaa-bbbb-cccc-dddd-00000000000b", OperationJournal.PHASE_FINALIZED, 5);
        var s2 = journal().summarizeLatest();
        assertFalse(s2.hasActiveOp());
        assertEquals(OperationJournal.PHASE_LIFECYCLE_RESETTING, s2.lastCompletedPhase(),
                "lastCompletedPhase is the final operational marker (FINALIZED excluded)");
    }

    @Test
    void unreadableJournalReportsActiveUnknown() throws IOException {
        Files.createDirectories(tmp.resolve("journal"));
        Files.writeString(tmp.resolve("journal/garbage.op.json"), "{{{");
        // authId never validated for summarize (reads directory); garbage tolerated as active-op evidence
        var s = journal().summarizeLatest();
        assertNotNull(s);
        assertTrue(s.hasActiveOp());
    }

    @Test
    void illegalAuthIdRejectedForWrites() {
        assertThrows(IllegalArgumentException.class,
                () -> journal().recordCompleted("../evil", "PHASE", 1));
        assertThrows(IllegalArgumentException.class,
                () -> journal().recordCompleted(null, "PHASE", 1));
    }

    @Test
    void removeCleansUp() throws IOException {
        String id = "aaaaaaaa-bbbb-cccc-dddd-0000000000ff";
        journal().recordCompleted(id, OperationJournal.PHASE_FINALIZED, 1);
        assertTrue(journal().load(id) != null);
        journal().remove(id);
        assertNull(journal().load(id));
    }
}
