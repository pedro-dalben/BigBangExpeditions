package com.bigbangcraft.expeditions.reset;

import com.bigbangcraft.expeditions.env.InstallFingerprint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Offline verification entry point for the production executor.
 *
 * The executor invokes this class (inside the mod jar) BEFORE touching the
 * filesystem. It re-validates everything that can be validated without a
 * running server: structure, checksum, expiry, scope, ledger state, and
 * equality between the artifact's install fingerprint and the fingerprint
 * exported by the server during preflight.
 *
 * Output contract for scripts:
 *   exit 0  + "AUTH_OK <authId>"            — authorized
 *   exit 40 + "AUTH_REFUSED:<reason>"       — never authorized
 */
public final class VerifyAuthCli {

    public record Result(boolean ok, String message) {}

    private VerifyAuthCli() {}

    /**
     * @param bbeRoot        server/bigbangexpeditions directory
     * @param authId         authorization to verify
     * @param nowEpochMs     wall clock
     * @param fingerprintFile JSON export of the current InstallFingerprint
     *                        (written by the in-game issue flow); nullable to skip
     */
    public static Result verify(Path bbeRoot, String authId, long nowEpochMs,
                                Path fingerprintFile, Path ledgerFile) {
        try {
            if (authId == null || !authId.matches("[0-9a-fA-F\\-]{36}")) {
                return new Result(false, "MALFORMED_AUTH_ID");
            }
            Path file = bbeRoot.resolve("authorizations").resolve(authId + ".json");
            if (!Files.isRegularFile(file)) return new Result(false, "AUTH_NOT_FOUND");
            ResetAuthorization a = ResetAuthorization.fromJson(Files.readString(file));
            if (a == null) return new Result(false, "AUTH_UNREADABLE");
            if (!a.checksumValid()) return new Result(false, "CHECKSUM_INVALID");

            // ledger first: an unknown/consumed/revoked artifact is dead
            AuthorizationLedger ledger = new AuthorizationLedger(ledgerFile);
            var entry = ledger.get(authId);
            if (entry == null) return new Result(false, "LEDGER_UNKNOWN");
            if (entry.status != AuthorizationLedger.Status.ISSUED) {
                return new Result(false, "LEDGER_" + entry.status.name());
            }

            String structural = a.validateStructure(nowEpochMs);
            if (structural != null) return new Result(false, structural);

            if (fingerprintFile != null) {
                if (!Files.isRegularFile(fingerprintFile)) return new Result(false, "CURRENT_FINGERPRINT_MISSING");
                InstallFingerprint current = QualificationStore.loadCurrentExported(Files.readString(fingerprintFile));
                if (current == null) return new Result(false, "CURRENT_FINGERPRINT_UNREADABLE");
                if (!a.installFingerprint.sha256().equals(current.sha256())) {
                    return new Result(false, "FINGERPRINT_MISMATCH");
                }
            }
            return new Result(true, "AUTH_OK " + authId
                    + " scope=" + a.scope
                    + " generation=" + a.generationAtIssue);
        } catch (Exception e) {
            return new Result(false, "VERIFY_ERROR:" + e.getClass().getSimpleName());
        }
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("AUTH_REFUSED:usage: VerifyAuthCli <bbeRoot> <authId> [current-fingerprint.json] [ledger.json]");
            System.exit(40);
        }
        Path root = Path.of(args[0]);
        String authId = args[1];
        Path fpFile = args.length >= 3 ? Path.of(args[2]) : null;
        Path ledger = args.length >= 4 ? Path.of(args[3]) : root.resolve("authorization-ledger.json");
        Result r = verify(root, authId, System.currentTimeMillis(), fpFile, ledger);
        System.out.println(r.ok ? r.message : "AUTH_REFUSED:" + r.message);
        System.exit(r.ok ? 0 : 40);
    }
}
