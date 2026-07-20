package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Plans fill-only grading for small enclosed depressions inside one PAVE area. */
final class CityLandUseMicroGrader {
    static final int REFERENCE_RADIUS_BLOCKS = 3;
    static final int MAX_FILL_DEPTH_BLOCKS = 3;
    static final int MAX_COMPONENT_AREA_BLOCKS = 16;
    static final int MAX_COMPONENT_SPAN_BLOCKS = 4;
    static final int MASK_HALO_BLOCKS = MAX_COMPONENT_AREA_BLOCKS;

    private static final int[][] CARDINAL_OFFSETS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    private CityLandUseMicroGrader() {
    }

    static List<FillDecision> plan(CityLandUseChunkCompiler.ChunkFragment fragment, TerrainView terrain) {
        Objects.requireNonNull(fragment, "fragment");
        Objects.requireNonNull(terrain, "terrain");
        if (fragment.microFillBlockId() == null || fragment.gradingMaskCells().isEmpty()) {
            return List.of();
        }
        Map<Cell, String> areaByCell = new HashMap<>();
        for (CityLandUseChunkCompiler.GradingMaskCell cell : fragment.gradingMaskCells()) {
            areaByCell.putIfAbsent(new Cell(cell.x(), cell.z()), cell.areaId());
        }

        List<FillDecision> decisions = new ArrayList<>();
        for (CityLandUseChunkCompiler.SurfaceOperation operation : fragment.surfaceOperations()) {
            Cell center = new Cell(operation.x(), operation.z());
            if (!operation.areaId().equals(areaByCell.get(center))) {
                continue;
            }
            CityLandUseChunkExecutor.ColumnSample centerSample = required(terrain, center);
            if (!centerSample.naturalSurface() || liquid(centerSample)) {
                continue;
            }
            Integer targetY = referenceHeight(center, terrain);
            if (targetY == null) {
                continue;
            }
            int fillDepth = targetY - centerSample.surfaceY();
            if (fillDepth <= 0 || fillDepth > MAX_FILL_DEPTH_BLOCKS
                    || !isSmallEnclosedDepression(center, operation.areaId(), targetY, areaByCell, terrain)) {
                continue;
            }
            decisions.add(new FillDecision(operation.areaId(), operation.x(), operation.z(),
                    centerSample.surfaceY(), targetY));
        }
        decisions.sort(Comparator.comparingInt(FillDecision::z)
                .thenComparingInt(FillDecision::x)
                .thenComparing(FillDecision::areaId));
        return List.copyOf(decisions);
    }

    private static Integer referenceHeight(Cell center, TerrainView terrain) {
        List<Integer> heights = new ArrayList<>();
        for (int z = center.z() - REFERENCE_RADIUS_BLOCKS;
             z <= center.z() + REFERENCE_RADIUS_BLOCKS; z++) {
            for (int x = center.x() - REFERENCE_RADIUS_BLOCKS;
                 x <= center.x() + REFERENCE_RADIUS_BLOCKS; x++) {
                CityLandUseChunkExecutor.ColumnSample sample = required(terrain, new Cell(x, z));
                if (liquid(sample)) {
                    return null;
                }
                heights.add(sample.surfaceY());
            }
        }
        heights.sort(Integer::compareTo);
        return heights.get(heights.size() / 2);
    }

    private static boolean isSmallEnclosedDepression(Cell center,
                                                     String areaId,
                                                     int targetY,
                                                     Map<Cell, String> areaByCell,
                                                     TerrainView terrain) {
        ArrayDeque<Cell> pending = new ArrayDeque<>();
        Set<Cell> component = new HashSet<>();
        pending.add(center);
        int minX = center.x();
        int maxX = center.x();
        int minZ = center.z();
        int maxZ = center.z();
        while (!pending.isEmpty()) {
            Cell cell = pending.removeFirst();
            CityLandUseChunkExecutor.ColumnSample sample = required(terrain, cell);
            if (sample.surfaceY() >= targetY || !component.add(cell)) {
                continue;
            }
            if (liquid(sample) || !sample.naturalSurface() || !areaId.equals(areaByCell.get(cell))) {
                return false;
            }
            minX = Math.min(minX, cell.x());
            maxX = Math.max(maxX, cell.x());
            minZ = Math.min(minZ, cell.z());
            maxZ = Math.max(maxZ, cell.z());
            if (component.size() > MAX_COMPONENT_AREA_BLOCKS
                    || maxX - minX + 1 > MAX_COMPONENT_SPAN_BLOCKS
                    || maxZ - minZ + 1 > MAX_COMPONENT_SPAN_BLOCKS) {
                return false;
            }
            for (int[] offset : CARDINAL_OFFSETS) {
                Cell neighbour = new Cell(cell.x() + offset[0], cell.z() + offset[1]);
                if (!component.contains(neighbour)) {
                    pending.addLast(neighbour);
                }
            }
        }
        return !component.isEmpty();
    }

    private static CityLandUseChunkExecutor.ColumnSample required(TerrainView terrain, Cell cell) {
        CityLandUseChunkExecutor.ColumnSample sample = terrain.sample(cell.x(), cell.z());
        if (sample == null) {
            throw new IllegalArgumentException("CITY_LAND_USE_TERRAIN_SAMPLE_MISSING: "
                    + cell.x() + "," + cell.z());
        }
        return sample;
    }

    private static boolean liquid(CityLandUseChunkExecutor.ColumnSample sample) {
        return "minecraft:water".equals(sample.surfaceBlockId())
                || "minecraft:lava".equals(sample.surfaceBlockId());
    }

    @FunctionalInterface
    interface TerrainView {
        CityLandUseChunkExecutor.ColumnSample sample(int worldX, int worldZ);
    }

    record FillDecision(String areaId, int x, int z, int surfaceY, int targetY) {
        FillDecision {
            Objects.requireNonNull(areaId, "areaId");
            if (targetY <= surfaceY || targetY - surfaceY > MAX_FILL_DEPTH_BLOCKS) {
                throw new IllegalArgumentException("CITY_LAND_USE_MICRO_FILL_DEPTH_INVALID");
            }
        }
    }

    private record Cell(int x, int z) {
    }
}
