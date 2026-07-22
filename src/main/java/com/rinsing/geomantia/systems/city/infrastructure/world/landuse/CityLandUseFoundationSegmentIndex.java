package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan;
import com.rinsing.geomantia.systems.city.application.terrain.CityContinuousTerrainRunPlanner;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable activation-time chunk index for frozen LandUse foundation segments. */
final class CityLandUseFoundationSegmentIndex {
    private static final CityLandUseFoundationSegmentIndex EMPTY =
            new CityLandUseFoundationSegmentIndex(Map.of());

    private final Map<ChunkKey, List<CityContinuousTerrainRunPlanner.FoundationSegment>> byChunk;

    private CityLandUseFoundationSegmentIndex(
            Map<ChunkKey, List<CityContinuousTerrainRunPlanner.FoundationSegment>> byChunk) {
        this.byChunk = Map.copyOf(byChunk);
    }

    static CityLandUseFoundationSegmentIndex empty() {
        return EMPTY;
    }

    static CityLandUseFoundationSegmentIndex prepare(CityLandUseSurfacePrintPlan plan) {
        Objects.requireNonNull(plan, "plan");
        Map<ChunkKey, Set<CityContinuousTerrainRunPlanner.FoundationSegment>> mutable =
                new LinkedHashMap<>();
        for (CityLandUseSurfacePrintPlan.AreaPrint area : plan.areas()) {
            if (!(area.recipe() instanceof CityLandUseSurfacePrintPlan.CultivateLinedRecipe recipe)) {
                continue;
            }
            for (CityContinuousTerrainRunPlanner.FoundationSegment segment : recipe.foundationSegments()) {
                int expansion = Math.addExact(segment.halfWidth(), segment.shoulderBlocks());
                int minChunkX = Math.floorDiv(
                        Math.subtractExact(Math.min(segment.x0(), segment.x1()), expansion), 16);
                int maxChunkX = Math.floorDiv(
                        Math.addExact(Math.max(segment.x0(), segment.x1()), expansion), 16);
                int minChunkZ = Math.floorDiv(
                        Math.subtractExact(Math.min(segment.z0(), segment.z1()), expansion), 16);
                int maxChunkZ = Math.floorDiv(
                        Math.addExact(Math.max(segment.z0(), segment.z1()), expansion), 16);
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                        mutable.computeIfAbsent(new ChunkKey(chunkX, chunkZ), ignored -> new LinkedHashSet<>())
                                .add(segment);
                    }
                }
            }
        }
        Map<ChunkKey, List<CityContinuousTerrainRunPlanner.FoundationSegment>> frozen =
                new LinkedHashMap<>();
        mutable.forEach((key, segments) -> frozen.put(key, List.copyOf(segments)));
        return frozen.isEmpty() ? EMPTY : new CityLandUseFoundationSegmentIndex(frozen);
    }

    List<CityContinuousTerrainRunPlanner.FoundationSegment> forChunk(int chunkX, int chunkZ) {
        return byChunk.getOrDefault(new ChunkKey(chunkX, chunkZ), List.of());
    }

    private record ChunkKey(int x, int z) {
    }
}
