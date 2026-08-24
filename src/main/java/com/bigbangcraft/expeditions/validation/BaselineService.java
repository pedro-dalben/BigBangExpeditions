package com.bigbangcraft.expeditions.validation;

import com.bigbangcraft.expeditions.diagnostics.DoctorService;
import com.bigbangcraft.expeditions.diagnostics.DoctorReport;
import com.bigbangcraft.expeditions.integration.opac.ClaimInspectionResult;
import com.bigbangcraft.expeditions.integration.opac.OpacAdapter;
import com.bigbangcraft.expeditions.sector.SectorBounds;
import com.bigbangcraft.expeditions.sector.SectorProbeResult;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public final class BaselineService {
    private static final Logger LOG = LogManager.getLogger("BigBangExpeditions/Baseline");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private BaselineService() {}

    public static SectorProbeResult probe(MinecraftServer server, ServerLevel level, SectorBounds bounds) {
        SectorProbeResult r = new SectorProbeResult(bounds);
        String err = bounds.validate();
        if (err != null) {
            r.refuse("invalid bounds: " + err);
            return r;
        }
        if (level == null) {
            r.refuse("dimension unavailable: " + bounds.dimension());
            return r;
        }

        // players inside (AABB covering sector blocks)
        try {
            AABB aabb = new AABB(bounds.minBlockX(), level.getMinBuildHeight(), bounds.minBlockZ(),
                    bounds.maxBlockX() + 1, level.getMaxBuildHeight(), bounds.maxBlockZ() + 1);
            List<ServerPlayer> players = level.getEntitiesOfClass(ServerPlayer.class, aabb, p -> true);
            r.playersInside = players.size();
            r.playerNames = players.stream().map(p -> p.getGameProfile().getName()).collect(Collectors.toList());
            if (r.playersInside > 0) r.warn("players inside sector: " + String.join(",", r.playerNames));
        } catch (Exception e) {
            r.warn("player scan failed: " + e.getMessage());
        }

        // OPAC
        ClaimInspectionResult claimRes = OpacAdapter.inspectClaims(server, level, bounds);
        r.opacAvailable = claimRes.isAvailable();
        if (!claimRes.isAvailable()) {
            r.opacStatus = "REFUSED: " + claimRes.unavailableReason();
            r.refuse("OPAC unavailable: " + claimRes.unavailableReason());
        } else {
            r.opacIntersectingChunks = claimRes.intersectingChunks();
            r.opacForceloads = claimRes.forceloadChunks();
            r.opacStatus = claimRes.intersects() ? "intersects: " + r.opacIntersectingChunks + " chunks" : "no claims";
            if (claimRes.intersects()) r.refuse("OPAC claims intersect sector: " + r.opacIntersectingChunks + " chunks");
            if (claimRes.hasForceloads()) r.warn("forceloads in sector: " + r.opacForceloads);
        }
        r.opacIntersectingChunks = claimRes.intersectingChunks();
        r.opacForceloads = claimRes.forceloadChunks();

        // chunk / BE scan — only loaded chunks to stay read-only and avoid mass loading
        int loaded = 0;
        int beCount = 0;
        int containerCount = 0;
        int spawnerCount = 0;
        Map<String, Integer> byType = new HashMap<>();
        Map<String, Integer> byNs = new HashMap<>();
        int create = 0, ie = 0, rs = 0, sc = 0;
        List<String> unknownNs = new ArrayList<>();
        Set<String> knownNs = Set.of("minecraft", "create", "immersiveengineering", "refinedstorage", "securitycraft", "lootr");

        for (int cx = bounds.minChunkX(); cx <= bounds.maxChunkX(); cx++) {
            for (int cz = bounds.minChunkZ(); cz <= bounds.maxChunkZ(); cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                LevelChunk chunk = level.getChunk(cx, cz);
                if (chunk == null) continue;
                loaded++;
                Map<BlockPos, BlockEntity> bes = chunk.getBlockEntities();
                for (Map.Entry<BlockPos, BlockEntity> ent : bes.entrySet()) {
                    BlockEntity be = ent.getValue();
                    if (be == null) continue;
                    beCount++;
                    ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
                    String type = key != null ? key.toString() : be.getType().toString();
                    String ns = key != null ? key.getNamespace() : "unknown";
                    byType.merge(type, 1, Integer::sum);
                    byNs.merge(ns, 1, Integer::sum);
                    if (!knownNs.contains(ns) && !unknownNs.contains(ns)) unknownNs.add(ns);

                    String t = type.toLowerCase();
                    if (t.contains("create:")) create++;
                    if (t.contains("immersiveengineering:")) ie++;
                    if (t.contains("refinedstorage:")) rs++;
                    if (t.contains("securitycraft:")) sc++;

                    // container / spawner detection via type string
                    if (t.contains("chest") || t.contains("barrel") || t.contains("shulker") || t.contains("container") || t.contains("chest") || be instanceof net.minecraft.world.Container) containerCount++;
                    if (t.contains("mob_spawner") || t.contains("spawner")) spawnerCount++;
                    // also check block for spawner
                    // Be conservative: check NBT type
                }
                // also count spawner blocks via block scan not done (would be heavy). BE count suffices for FAIL detection
            }
        }
        r.loadedChunks = loaded;
        r.blockEntityCount = beCount;
        r.containerCount = containerCount;
        r.spawnerCount = spawnerCount;
        r.blockEntitiesByType = byType;
        r.blockEntitiesByNamespace = byNs;
        r.unknownNamespaces = unknownNs;
        r.createCount = create;
        r.immersiveCount = ie;
        r.refinedStorageCount = rs;
        r.securityCraftCount = sc;

        // entities
        try {
            AABB aabbEnt = new AABB(bounds.minBlockX(), -64, bounds.minBlockZ(), bounds.maxBlockX() + 1, 320, bounds.maxBlockZ() + 1);
            List<Entity> ents = level.getEntities((Entity) null, aabbEnt, e -> true);
            r.entityCount = ents.size();
        } catch (Exception e) {
            r.warn("entity scan failed: " + e.getMessage());
        }

        if (unknownNs.size() > 0) r.warn("unknown BE namespaces: " + String.join(",", unknownNs));
        if (create > 0) r.warn("Create BEs in sector: " + create);
        if (ie > 0) r.warn("IE BEs: " + ie);
        if (rs > 0) r.warn("RS BEs: " + rs + " (SavedData graph may survive region delete)");
        if (sc > 0) r.warn("SecurityCraft BEs: " + sc);
        if (beCount > 0) r.warn("block entities present — regen would delete player builds");
        if (r.entityCount > 0) r.warn("entities in sector: " + r.entityCount);

        return r;
    }

    public static BaselineData toBaseline(MinecraftServer server, ServerLevel level, SectorProbeResult probe) {
        BaselineData d = BaselineData.from(probe.bounds());
        SectorBounds b = probe.bounds();
        d.blockEntityCount = probe.blockEntityCount;
        d.containerCount = probe.containerCount;
        d.spawnerCount = probe.spawnerCount;
        d.entityCount = probe.entityCount;
        d.loadedChunks = probe.loadedChunks;
        d.playersInside = probe.playersInside;
        d.blockEntitiesByType = probe.blockEntitiesByType != null ? new TreeMap<>(probe.blockEntitiesByType) : new TreeMap<>();
        d.blockEntitiesByNamespace = probe.blockEntitiesByNamespace != null ? new TreeMap<>(probe.blockEntitiesByNamespace) : new TreeMap<>();
        d.entitiesByType = new TreeMap<>();
        d.opacStatus = probe.opacStatus;
        d.opacIntersecting = probe.opacIntersectingChunks;
        d.opacForceloads = probe.opacForceloads;
        d.opacAvailable = probe.opacAvailable;
        d.warnings = new ArrayList<>(probe.warnings());
        d.unknownNamespaces = new ArrayList<>(probe.unknownNamespaces);
        try {
            DoctorReport dr = DoctorService.build(server, level);
            d.lostCitiesProfile = dr.lostCitiesProfile;
            if (level != null) d.worldSeedHash = Long.toHexString(level.getSeed());
            else d.worldSeedHash = "unavailable";
        } catch (Exception e) {
            d.lostCitiesProfile = "unknown";
            d.worldSeedHash = "unknown";
        }
        return d;
    }

    public static Path writeBaseline(MinecraftServer server, BaselineData data) throws IOException {
        Path dir = server.getServerDirectory().toPath().resolve("bigbangexpeditions/baselines");
        Files.createDirectories(dir);
        String safeId = data.id.replaceAll("[^a-z0-9_\\-]", "_");
        String file = String.format("%s_%s_%d.json", safeId, data.dimension.replace(':', '_'), data.timestampEpochMs);
        Path p = dir.resolve(file);
        String json = GSON.toJson(data);
        Files.writeString(p, json);
        LOG.info("Baseline written to {}", p);
        return p;
    }

    public static BaselineData readBaseline(Path p) throws IOException {
        String json = Files.readString(p);
        return GSON.fromJson(json, BaselineData.class);
    }

    public static String compare(BaselineData before, BaselineData after) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Baseline Compare ===\n");
        sb.append("before: ").append(before.id).append(" @ ").append(before.timestampIso).append("\n");
        sb.append("after:  ").append(after.id).append(" @ ").append(after.timestampIso).append("\n");
        sb.append(String.format("bounds %s %d,%d -> %d,%d (chunks %d)\n", before.dimension, before.minChunkX, before.minChunkZ, before.maxChunkX, before.maxChunkZ, before.chunkCount));
        diff(sb, "blockEntityCount", before.blockEntityCount, after.blockEntityCount);
        diff(sb, "containerCount", before.containerCount, after.containerCount);
        diff(sb, "spawnerCount", before.spawnerCount, after.spawnerCount);
        diff(sb, "entityCount", before.entityCount, after.entityCount);
        diff(sb, "loadedChunks", before.loadedChunks, after.loadedChunks);
        diff(sb, "playersInside", before.playersInside, after.playersInside);
        diff(sb, "opacIntersecting", before.opacIntersecting, after.opacIntersecting);
        diffMaps(sb, "BE by type", before.blockEntitiesByType, after.blockEntitiesByType);
        diffMaps(sb, "BE by ns", before.blockEntitiesByNamespace, after.blockEntitiesByNamespace);
        if (!Objects.equals(before.opacStatus, after.opacStatus)) sb.append(String.format("opacStatus: '%s' -> '%s'\n", before.opacStatus, after.opacStatus));
        if (!Objects.equals(before.worldSeedHash, after.worldSeedHash)) sb.append(String.format("worldSeedHash: %s -> %s\n", before.worldSeedHash, after.worldSeedHash));
        if (before.warnings != null || after.warnings != null) {
            sb.append("warnings before: ").append(before.warnings).append("\n");
            sb.append("warnings after: ").append(after.warnings).append("\n");
        }
        return sb.toString();
    }

    private static void diff(StringBuilder sb, String name, int a, int b) {
        if (a != b) sb.append(String.format("%s: %d -> %d (delta %+d)\n", name, a, b, b - a));
        else sb.append(String.format("%s: %d (unchanged)\n", name, a));
    }

    private static void diffMaps(StringBuilder sb, String name, Map<String,Integer> a, Map<String,Integer> b) {
        if (a == null) a = Map.of();
        if (b == null) b = Map.of();
        Set<String> keys = new TreeSet<>();
        keys.addAll(a.keySet());
        keys.addAll(b.keySet());
        boolean changed = false;
        for (String k : keys) {
            int av = a.getOrDefault(k, 0);
            int bv = b.getOrDefault(k, 0);
            if (av != bv) {
                if (!changed) sb.append(name).append(" changes:\n");
                sb.append(String.format("  %s: %d -> %d\n", k, av, bv));
                changed = true;
            }
        }
        if (!changed) sb.append(name).append(": unchanged\n");
    }
}
