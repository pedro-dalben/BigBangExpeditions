package com.bigbangcraft.expeditions.backup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Retention policy for reset backups.
 *
 * Rules:
 * - The newest VERIFIED backup is NEVER deletable (sole rollback point is sacred).
 * - Unverified/corrupted backups beyond the keep-count are deletable.
 * - Deletion candidates are the OLDEST entries once more than {@code keepCount}
 *   verified backups exist.
 */
public final class BackupRetentionPolicy {

    public record Summary(String backupId, boolean verified, long createdAtEpochMs, boolean consumed) {}

    private final int keepVerified;

    public BackupRetentionPolicy(int keepVerified) {
        if (keepVerified < 1) throw new IllegalArgumentException("must keep at least one verified backup");
        this.keepVerified = keepVerified;
    }

    /** Backup ids that may be deleted under this policy. */
    public List<String> deletable(List<Summary> backups) {
        List<String> out = new ArrayList<>();
        List<Summary> sorted = new ArrayList<>(backups);
        sorted.sort(Comparator.comparingLong(Summary::createdAtEpochMs)); // oldest first

        List<Summary> verified = sorted.stream().filter(Summary::verified).toList();
        if (!verified.isEmpty()) {
            Summary newestValid = verified.get(verified.size() - 1);
            // never delete the newest verified backup
            for (Summary s : sorted) {
                if (s == newestValid) continue;
                if (!s.verified()) {
                    out.add(s.backupId());
                    continue;
                }
            }
            int extra = verified.size() - keepVerified;
            if (extra > 0) {
                for (int i = 0; i < verified.size() && extra > 0; i++) {
                    Summary s = verified.get(i);
                    if (s == newestValid) continue;
                    if (!out.contains(s.backupId())) {
                        out.add(s.backupId());
                        extra--;
                    }
                }
            }
        } else {
            // no verified backup at all: only unverified junk may be cleaned
            for (Summary s : sorted) {
                if (!s.verified()) out.add(s.backupId());
            }
        }
        return out;
    }
}
