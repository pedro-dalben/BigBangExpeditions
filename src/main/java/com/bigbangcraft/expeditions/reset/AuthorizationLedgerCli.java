package com.bigbangcraft.expeditions.reset;

import java.nio.file.Path;

/**
 * Offline ledger operations for the executor.
 *
 * Usage: AuthorizationLedgerCli <ledgerFile> <authId> consume|revoke
 * Prints "LEDGER_OK <status>" (exit 0) or "LEDGER_REFUSED:<reason>" (exit 42).
 */
public final class AuthorizationLedgerCli {

    private AuthorizationLedgerCli() {}

    public static void main(String[] args) {
        if (args.length != 3) {
            System.out.println("LEDGER_REFUSED:usage <ledgerFile> <authId> consume|revoke");
            System.exit(42);
        }
        try {
            AuthorizationLedger l = new AuthorizationLedger(Path.of(args[0]));
            var err = switch (args[2]) {
                case "consume" -> l.consume(args[1], "offline-executor", System.currentTimeMillis());
                case "revoke" -> l.revoke(args[1], "offline-executor", System.currentTimeMillis());
                default -> java.util.Optional.of("unknown op " + args[2]);
            };
            if (err.isPresent()) {
                System.out.println("LEDGER_REFUSED:" + err.get());
                System.exit(42);
            }
            System.out.println("LEDGER_OK " + args[2]);
        } catch (Exception e) {
            System.out.println("LEDGER_REFUSED:" + e.getMessage());
            System.exit(42);
        }
    }
}
