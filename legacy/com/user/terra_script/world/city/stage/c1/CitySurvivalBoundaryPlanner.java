package com.user.terra_script.world.city.stage.c1;

import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class CitySurvivalBoundaryPlanner {
    private CitySurvivalBoundaryPlanner() {}

    public interface ChunkAccess {
        boolean isWithinSovereignty(long chunkKey, String territoryId);

        String cityIdAt(long chunkKey);

        default double terrainRisk(int chunkX, int chunkZ) {
            return 0.0;
        }

        default boolean waterLike(int chunkX, int chunkZ) {
            return false;
        }

        default boolean roughLike(int chunkX, int chunkZ) {
            return false;
        }
    }

    public static final class Request {
        public String cityId;
        public String territoryId;
        public int centerX;
        public int centerZ;
        public int targetChunkCount;
        public int searchRadiusChunks;
        public String existingCityId;
        public String density;
    }

    public static final class Result {
        public String cityId;
        public String territoryId;
        public String status;
        public int attempt = 1;
        public int requestedCenterChunkX;
        public int requestedCenterChunkZ;
        public int anchorChunkX;
        public int anchorChunkZ;
        public int targetChunkCount;
        public boolean reanchored;
        public boolean fallbackUsed;
        public final List<ChunkChoice> choices = new ArrayList<>();
        public final List<String> riskTags = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public final List<String> blockingErrors = new ArrayList<>();

        public Set<Long> claimedChunkKeys() {
            Set<Long> keys = new HashSet<>();
            for (ChunkChoice choice : choices) {
                keys.add(ChunkPos.asLong(choice.chunkX, choice.chunkZ));
            }
            return keys;
        }
    }

    public static final class ChunkChoice {
        public int chunkX;
        public int chunkZ;
        public double heat;
        public double distance;
        public double terrainRisk;
        public String layerType;
        public int layerIndex;
        public boolean waterRisk;
        public boolean roughRisk;
    }

    private static final class Candidate {
        int chunkX;
        int chunkZ;
        double distance;
        double heat;
        double terrainRisk;
        boolean waterRisk;
        boolean roughRisk;
    }

    public static Result plan(Request request, ChunkAccess access) {
        Result result = new Result();
        if (request == null || access == null) {
            result.status = "fail_safe";
            result.blockingErrors.add("invalid_request");
            result.fallbackUsed = true;
            return result;
        }

        result.cityId = request.cityId;
        result.territoryId = request.territoryId;
        result.targetChunkCount = normalizeTarget(request.targetChunkCount, request.density);
        result.requestedCenterChunkX = request.centerX >> 4;
        result.requestedCenterChunkZ = request.centerZ >> 4;

        if (isBlank(request.territoryId)) {
            result.status = "fail_safe";
            result.blockingErrors.add("missing_territory_id");
            result.fallbackUsed = true;
            return result;
        }

        int radius = request.searchRadiusChunks > 0
                ? request.searchRadiusChunks
                : Math.max(10, (int) Math.ceil(Math.sqrt(result.targetChunkCount)) + 10);

        List<Candidate> candidates = collectCandidates(request, access, result, radius);
        if (candidates.isEmpty()) {
            result.status = "fail_safe";
            result.blockingErrors.add("no_owned_non_overlapping_chunks");
            result.fallbackUsed = true;
            result.anchorChunkX = result.requestedCenterChunkX;
            result.anchorChunkZ = result.requestedCenterChunkZ;
            return result;
        }

        Candidate anchor = candidates.stream()
                .max(Comparator.comparingDouble(c -> c.heat))
                .orElse(candidates.get(0));
        result.anchorChunkX = anchor.chunkX;
        result.anchorChunkZ = anchor.chunkZ;
        result.reanchored = anchor.chunkX != result.requestedCenterChunkX || anchor.chunkZ != result.requestedCenterChunkZ;
        if (result.reanchored) {
            result.warnings.add("center_reanchored_to_owned_chunk");
        }

        recomputeHeatFromAnchor(candidates, anchor);
        candidates.sort(Comparator.comparingDouble((Candidate c) -> c.heat).reversed());

        int take = Math.min(result.targetChunkCount, candidates.size());
        if (take < result.targetChunkCount) {
            result.warnings.add("target_reduced_by_available_owned_chunks");
            result.fallbackUsed = true;
        }

        Map<String, Boolean> riskSeen = new HashMap<>();
        for (int index = 0; index < take; index++) {
            Candidate candidate = candidates.get(index);
            ChunkChoice choice = new ChunkChoice();
            choice.chunkX = candidate.chunkX;
            choice.chunkZ = candidate.chunkZ;
            choice.heat = candidate.heat;
            choice.distance = candidate.distance;
            choice.terrainRisk = candidate.terrainRisk;
            choice.waterRisk = candidate.waterRisk;
            choice.roughRisk = candidate.roughRisk;
            assignLayer(choice, index, take);
            result.choices.add(choice);
            if (choice.waterRisk && riskSeen.putIfAbsent("water_city_candidate", true) == null) {
                result.riskTags.add("water_city_candidate");
            }
            if (choice.roughRisk && riskSeen.putIfAbsent("requires_terrain_adaptation", true) == null) {
                result.riskTags.add("requires_terrain_adaptation");
            }
        }

        if (result.choices.isEmpty()) {
            result.status = "fail_safe";
            result.blockingErrors.add("empty_boundary_after_selection");
            result.fallbackUsed = true;
        } else {
            result.status = result.fallbackUsed ? "fallback" : "pass";
        }
        return result;
    }

    private static List<Candidate> collectCandidates(Request request, ChunkAccess access, Result result, int radius) {
        List<Candidate> candidates = new ArrayList<>();
        int centerChunkX = request.centerX >> 4;
        int centerChunkZ = request.centerZ >> 4;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;
                long key = ChunkPos.asLong(chunkX, chunkZ);
                if (!access.isWithinSovereignty(key, request.territoryId)) continue;
                String cityAt = access.cityIdAt(key);
                if (!isBlank(cityAt) && !cityAt.equals(request.existingCityId)) continue;
                Candidate candidate = new Candidate();
                candidate.chunkX = chunkX;
                candidate.chunkZ = chunkZ;
                candidate.distance = Math.sqrt(dx * dx + dz * dz);
                candidate.terrainRisk = clamp01(access.terrainRisk(chunkX, chunkZ));
                candidate.waterRisk = access.waterLike(chunkX, chunkZ);
                candidate.roughRisk = access.roughLike(chunkX, chunkZ);
                candidate.heat = baseHeat(candidate.distance, candidate.terrainRisk);
                candidates.add(candidate);
            }
        }
        if (!access.isWithinSovereignty(ChunkPos.asLong(centerChunkX, centerChunkZ), request.territoryId)) {
            result.warnings.add("requested_center_outside_sovereignty");
        }
        return candidates;
    }

    private static void recomputeHeatFromAnchor(Collection<Candidate> candidates, Candidate anchor) {
        for (Candidate candidate : candidates) {
            int dx = candidate.chunkX - anchor.chunkX;
            int dz = candidate.chunkZ - anchor.chunkZ;
            candidate.distance = Math.sqrt(dx * dx + dz * dz);
            candidate.heat = baseHeat(candidate.distance, candidate.terrainRisk);
        }
    }

    private static double baseHeat(double distance, double terrainRisk) {
        return 1.0 / (1.0 + distance) - terrainRisk * 0.08;
    }

    private static void assignLayer(ChunkChoice choice, int index, int total) {
        double ratio = total <= 1 ? 0.0 : index / (double) total;
        if (ratio < 0.22) {
            choice.layerIndex = 0;
            choice.layerType = "CORE";
        } else if (ratio < 0.72) {
            choice.layerIndex = 1;
            choice.layerType = "URBAN";
        } else {
            choice.layerIndex = 2;
            choice.layerType = "BUFFER";
        }
    }

    private static int normalizeTarget(int target, String density) {
        if (target > 0) return target;
        String normalized = density == null ? "" : density.toLowerCase(Locale.ROOT);
        if (normalized.contains("high")) return 180;
        if (normalized.contains("low")) return 80;
        return 120;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }
}
