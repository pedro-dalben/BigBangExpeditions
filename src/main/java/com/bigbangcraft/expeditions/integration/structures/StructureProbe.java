package com.bigbangcraft.expeditions.integration.structures;

import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Event-driven structure discovery (Goal 05 requirements 5/6).
 *
 * <p>Cost model: reads ONLY the structure-reference table of a chunk the
 * player is already loading. No forced chunk loads, no periodic world scans.
 * Called once per newly-discovered chunk, so total cost scales with genuine
 * exploration.
 *
 * <p>Known limitation (goal-05 assessment §4): Lost Cities building templates
 * may not register as vanilla structures; when this probe yields nothing the
 * telemetry layer raises STRUCTURE_SIGNAL_ABSENT and the depletion engine
 * treats the structure component as UNKNOWN rather than pretending knowledge.
 *
 * <p>A "placement" is one section-position reference of one structure type —
 * inherently deduplicated by the reference set itself.
 */
public final class StructureProbe {
    private StructureProbe() {}

    public static final class Sighting {
        public final String structureId;
        public final long packedSection;

        Sighting(String id, long packed) {
            this.structureId = id;
            this.packedSection = packed;
        }
    }

    /** @return sightings for this chunk; empty list when none/anything fails. */
    public static List<Sighting> probe(ServerLevel level, ChunkPos pos) {
        List<Sighting> out = new ArrayList<>();
        try {
            ChunkAccess chunk = level.getChunk(pos.x, pos.z);
            Map<Structure, it.unimi.dsi.fastutil.longs.LongSet> refs = chunk.getAllReferences();
            if (refs == null || refs.isEmpty()) return out;
            var registry = level.registryAccess()
                    .registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
            for (Map.Entry<Structure, it.unimi.dsi.fastutil.longs.LongSet> e : refs.entrySet()) {
                var key = registry.getKey(e.getKey());
                if (key == null) continue;
                String id = key.toString();
                var it = e.getValue().iterator();
                while (it.hasNext()) {
                    long packed = it.nextLong();
                    SectionPos sp = SectionPos.of(packed);
                    StructureStart start = level.structureManager().getStartForStructure(
                            sp, e.getKey(), chunk);
                    if (start != null && start != StructureStart.INVALID_START) {
                        out.add(new Sighting(id, packed));
                    }
                }
            }
        } catch (Exception ignored) {
            // probe failure must never break gameplay or tick flow — absence
            // of evidence is handled upstream as an unknown signal
        }
        return out;
    }
}
