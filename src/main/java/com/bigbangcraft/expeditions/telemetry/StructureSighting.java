package com.bigbangcraft.expeditions.telemetry;

import java.util.HashSet;
import java.util.Set;

/**
 * Sightings of one structure type during a generation.
 *
 * <p>A "section" is a structure-start placement (packed section-position).
 * Seeing the same start twice is a duplicate and must not double-count; the
 * set enforces idempotency. The set saturates at {@code SECTION_CAP} — further
 * distinct placements bump {@code sectionOverflow} instead of growing memory.
 */
public final class StructureSighting {
    public static final int SECTION_CAP = 4096;

    public String structureId;
    public long firstSeenEpochMs;
    public Set<Long> sections = new HashSet<>();
    public long sectionOverflow;

    public StructureSighting() {}

    public StructureSighting(String structureId, long firstSeenEpochMs) {
        this.structureId = structureId;
        this.firstSeenEpochMs = firstSeenEpochMs;
    }

    /** @return true when this placement was new (a real discovery). */
    public boolean recordSection(long packedSection, long nowEpochMs) {
        if (sections.contains(packedSection)) return false;
        if (sections.size() < SECTION_CAP) {
            sections.add(packedSection);
            if (firstSeenEpochMs <= 0) firstSeenEpochMs = nowEpochMs;
            return true;
        }
        sectionOverflow = Saturation.inc(sectionOverflow);
        return true;
    }

    public long distinctSections() {
        return sections.size() + sectionOverflow;
    }
}
