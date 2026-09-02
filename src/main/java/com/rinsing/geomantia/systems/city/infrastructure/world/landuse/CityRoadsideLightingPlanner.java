package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Derives deterministic main-road lamps directly from frozen road cells.
 * This is a road-owner facility: no separate planning stage or content catalog is involved.
 */
final class CityRoadsideLightingPlanner {
    private static final int SPACING = 32;
    private static final int FIRST_SIDE_PHASE = 8;
    private static final int SECOND_SIDE_PHASE = 24;
    private static final int END_CLEARANCE = 5;

    private CityRoadsideLightingPlanner() {
    }

    static List<Lamp> plan(CityLandUseChunkCompiler.ChunkFragment fragment) {
        Map<String, List<CityLandUseChunkCompiler.FeatureOperation>> roads = new LinkedHashMap<>();
        for (CityLandUseChunkCompiler.FeatureOperation operation : fragment.gradingFeatureOperations()) {
            if (isMainRoadSegment(operation)) {
                roads.computeIfAbsent(operation.sourceId(), ignored -> new ArrayList<>()).add(operation);
            }
        }
        List<Lamp> result = new ArrayList<>();
        for (Map.Entry<String, List<CityLandUseChunkCompiler.FeatureOperation>> entry : roads.entrySet()) {
            result.addAll(planRoad(entry.getKey(), entry.getValue(), fragment.chunkX(), fragment.chunkZ()));
        }
        result.sort(Comparator.comparing(Lamp::lampId));
        return List.copyOf(result);
    }

    private static boolean isMainRoadSegment(CityLandUseChunkCompiler.FeatureOperation operation) {
        return operation.sourceId().startsWith("city_main_road_")
                && operation.sourceId().contains("::segment_")
                && (operation.kind() == CityLandUseSurfacePrintPlan.FeatureKind.ROAD_SLAB
                || operation.kind() == CityLandUseSurfacePrintPlan.FeatureKind.ROAD_STAIR);
    }

    private static List<Lamp> planRoad(String sourceId,
                                       List<CityLandUseChunkCompiler.FeatureOperation> cells,
                                       int ownerChunkX,
                                       int ownerChunkZ) {
        int minX = cells.stream().mapToInt(CityLandUseChunkCompiler.FeatureOperation::x).min().orElseThrow();
        int maxX = cells.stream().mapToInt(CityLandUseChunkCompiler.FeatureOperation::x).max().orElseThrow();
        int minZ = cells.stream().mapToInt(CityLandUseChunkCompiler.FeatureOperation::z).min().orElseThrow();
        int maxZ = cells.stream().mapToInt(CityLandUseChunkCompiler.FeatureOperation::z).max().orElseThrow();
        boolean alongX = maxX - minX >= maxZ - minZ;
        Map<Integer, CrossSection> sections = new HashMap<>();
        for (CityLandUseChunkCompiler.FeatureOperation cell : cells) {
            int longitudinal = alongX ? cell.x() : cell.z();
            int cross = alongX ? cell.z() : cell.x();
            sections.merge(longitudinal, new CrossSection(cross, cross), CrossSection::merge);
        }

        List<Lamp> result = new ArrayList<>();
        for (Map.Entry<Integer, CrossSection> entry : sections.entrySet()) {
            int station = entry.getKey();
            int phase = Math.floorMod(station, SPACING);
            if (phase != FIRST_SIDE_PHASE && phase != SECOND_SIDE_PHASE
                    || !sections.containsKey(station - END_CLEARANCE)
                    || !sections.containsKey(station + END_CLEARANCE)) {
                continue;
            }
            CrossSection section = entry.getValue();
            boolean negativeSide = phase == FIRST_SIDE_PHASE;
            Lamp lamp = alongX
                    ? horizontalLamp(sourceId, station, section, negativeSide)
                    : verticalLamp(sourceId, station, section, negativeSide);
            if (lamp.blocks().stream().allMatch(block ->
                    ownerChunk(block.x()) == ownerChunkX && ownerChunk(block.z()) == ownerChunkZ)) {
                result.add(lamp);
            }
        }
        return result;
    }

    private static Lamp horizontalLamp(String sourceId, int x, CrossSection section, boolean north) {
        int datumZ = north ? section.min() : section.max();
        int armZ = datumZ + (north ? 1 : -1);
        String id = sourceId + "::lamp_" + x + "_" + datumZ;
        return lamp(id, x, datumZ, x, datumZ, x, armZ);
    }

    private static Lamp verticalLamp(String sourceId, int z, CrossSection section, boolean west) {
        int datumX = west ? section.min() : section.max();
        int armX = datumX + (west ? 1 : -1);
        String id = sourceId + "::lamp_" + datumX + "_" + z;
        return lamp(id, datumX, z, datumX, z, armX, z);
    }

    private static Lamp lamp(String id, int datumX, int datumZ, int postX, int postZ,
                             int armX, int armZ) {
        List<BlockDecision> blocks = new ArrayList<>();
        for (int offset = 1; offset <= 5; offset++) {
            blocks.add(new BlockDecision(id, datumX, datumZ, postX, postZ,
                    offset, "minecraft:dark_oak_fence"));
        }
        blocks.add(new BlockDecision(id, datumX, datumZ, armX, armZ,
                5, "minecraft:dark_oak_fence"));
        blocks.add(new BlockDecision(id, datumX, datumZ, armX, armZ,
                4, "minecraft:chain"));
        blocks.add(new BlockDecision(id, datumX, datumZ, armX, armZ,
                3, "minecraft:lantern"));
        return new Lamp(id, List.copyOf(blocks));
    }

    private static int ownerChunk(int blockCoordinate) {
        return Math.floorDiv(blockCoordinate, 16);
    }

    record Lamp(String lampId, List<BlockDecision> blocks) {
    }

    record BlockDecision(String lampId, int datumX, int datumZ, int x, int z,
                         int verticalOffset, String blockId) {
    }

    private record CrossSection(int min, int max) {
        private CrossSection merge(CrossSection other) {
            return new CrossSection(Math.min(min, other.min), Math.max(max, other.max));
        }
    }
}
