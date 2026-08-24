package com.bigbangcraft.expeditions.reset;

import java.nio.file.Path;

/**
 * Thin CLI used by the offline executor to append journal phase markers with
 * the SAME atomic implementation the server-side recovery reads.
 *
 * Usage: OperationJournalCli <journalDir> <authId> <phaseName>
 * Prints "JOURNAL_OK <phase>" (exit 0) or "JOURNAL_REFUSED:<reason>" (exit 41).
 */
public final class OperationJournalCli {

    private OperationJournalCli() {}

    public static void main(String[] args) {
        if (args.length != 3) {
            System.out.println("JOURNAL_REFUSED:usage <journalDir> <authId> <phase>");
            System.exit(41);
        }
        try {
            OperationJournal j = new OperationJournal(Path.of(args[0]));
            j.recordCompleted(args[1], args[2], System.currentTimeMillis());
            System.out.println("JOURNAL_OK " + args[2]);
        } catch (Exception e) {
            System.out.println("JOURNAL_REFUSED:" + e.getMessage());
            System.exit(41);
        }
    }
}
