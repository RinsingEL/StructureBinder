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

/** Plans local execution-time grading inside PAVE areas. */
final class CityLandUseMicroGrader {
    static final int REFERENCE_RADIUS_BLOCKS = 3;
    static final int MAX_FILL_DEPTH_BLOCKS = 3;
    static final int MAX_COMPONENT_AREA_BLOCKS = 16;
    static final int MAX_COMPONENT_SPAN_BLOCKS = 4;
    static final int MASK_HALO_BLOCKS = MAX_COMPONENT_AREA_BLOCKS;
    static final int FOUNDATION_MAX_FILL_DEPTH_BLOCKS = 48;
    static final int FOUNDATION_MAX_CUT_DEPTH_BLOCKS = 12;

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
            if (!cell.foundation()) {
                areaByCell.putIfAbsent(new Cell(cell.x(), cell.z()), cell.areaId());
            }
        }

        List<FillDecision> decisions = new ArrayList<>();
        for (CityLandUseChunkCompiler.SurfaceOperation operation : fragment.surfaceOperations()) {
            if (operation.surfaceOffset() != 0) {
                continue;
            }
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

    static List<FoundationDecision> planFoundation(CityLandUseChunkCompiler.ChunkFragment fragment,
                                                   TerrainView terrain) {
        return planFoundationPlatform(fragment, terrain).decisions();
    }

    static FoundationPlan planFoundationPlatform(CityLandUseChunkCompiler.ChunkFragment fragment,
                                                 TerrainView terrain) {
        Objects.requireNonNull(fragment, "fragment");
        Objects.requireNonNull(terrain, "terrain");
        Map<Cell, String> foundationAreaByCell = new HashMap<>();
        for (CityLandUseChunkCompiler.GradingMaskCell cell : fragment.gradingMaskCells()) {
            if (cell.foundation()) {
                foundationAreaByCell.putIfAbsent(new Cell(cell.x(), cell.z()), cell.areaId());
            }
        }
        if (foundationAreaByCell.isEmpty()) return new FoundationPlan(List.of(), List.of());

        Map<Cell, Integer> desiredTargets = new HashMap<>();
        foundationAreaByCell.forEach((cell, areaId) -> {
            CityLandUseChunkExecutor.ColumnSample sample = required(terrain, cell);
            if (!liquid(sample)) {
                desiredTargets.put(cell, localDominantHeight(cell, areaId, foundationAreaByCell, terrain));
            }
        });
        Map<Cell, Integer> platformTargets = platformTargets(foundationAreaByCell, desiredTargets);

        List<FoundationDecision> decisions = new ArrayList<>();
        Set<Cell> outputCells = new HashSet<>();
        for (CityLandUseChunkCompiler.SurfaceOperation operation : fragment.surfaceOperations()) {
            if (operation.surfaceOffset() != 0) continue;
            Cell center = new Cell(operation.x(), operation.z());
            if (!operation.areaId().equals(foundationAreaByCell.get(center))) continue;
            outputCells.add(center);
            CityLandUseChunkExecutor.ColumnSample sample = required(terrain, center);
            if (liquid(sample)) {
                decisions.add(new FoundationDecision(operation.areaId(), operation.x(), operation.z(),
                        sample.surfaceY(), sample.surfaceY(), FoundationMode.PRESERVE));
                continue;
            }
            if (!sample.naturalSurface()) continue;

            int targetY = platformTargets.getOrDefault(center, sample.surfaceY());
            int delta = targetY - sample.surfaceY();
            FoundationMode mode;
            if (delta > 0 && delta <= FOUNDATION_MAX_FILL_DEPTH_BLOCKS) {
                mode = FoundationMode.FILL;
            } else if (delta < 0 && -delta <= FOUNDATION_MAX_CUT_DEPTH_BLOCKS) {
                mode = FoundationMode.CUT;
            } else if (delta != 0) {
                mode = FoundationMode.PRESERVE;
            } else {
                continue;
            }
            decisions.add(new FoundationDecision(operation.areaId(), operation.x(), operation.z(),
                    sample.surfaceY(), mode == FoundationMode.PRESERVE ? sample.surfaceY() : targetY,
                    mode));
        }
        decisions.sort(Comparator.comparingInt(FoundationDecision::z)
                .thenComparingInt(FoundationDecision::x)
                .thenComparing(FoundationDecision::areaId));
        List<RetainingWallDecision> retainingWalls = retainingWalls(outputCells, foundationAreaByCell,
                platformTargets, terrain);
        return new FoundationPlan(List.copyOf(decisions), retainingWalls);
    }

    private static int localDominantHeight(Cell center,
                                           String areaId,
                                           Map<Cell, String> foundationAreaByCell,
                                           TerrainView terrain) {
        List<Integer> heights = new ArrayList<>();
        for (int z = center.z() - REFERENCE_RADIUS_BLOCKS;
             z <= center.z() + REFERENCE_RADIUS_BLOCKS; z++) {
            for (int x = center.x() - REFERENCE_RADIUS_BLOCKS;
                 x <= center.x() + REFERENCE_RADIUS_BLOCKS; x++) {
                Cell cell = new Cell(x, z);
                if (!areaId.equals(foundationAreaByCell.get(cell))) continue;
                CityLandUseChunkExecutor.ColumnSample sample = required(terrain, cell);
                if (!liquid(sample) && sample.naturalSurface()) heights.add(sample.surfaceY());
            }
        }
        if (heights.isEmpty()) return required(terrain, center).surfaceY();
        return dominantHeight(heights);
    }

    private static Map<Cell, Integer> platformTargets(Map<Cell, String> areaByCell,
                                                       Map<Cell, Integer> desiredTargets) {
        Map<Cell, Integer> result = new HashMap<>();
        Set<Cell> remaining = new HashSet<>(desiredTargets.keySet());
        while (!remaining.isEmpty()) {
            Cell first = remaining.stream().min(Comparator.comparingInt(Cell::z)
                    .thenComparingInt(Cell::x)).orElseThrow();
            String areaId = areaByCell.get(first);
            ArrayDeque<Cell> pending = new ArrayDeque<>();
            List<Cell> component = new ArrayList<>();
            pending.add(first);
            remaining.remove(first);
            while (!pending.isEmpty()) {
                Cell cell = pending.removeFirst();
                component.add(cell);
                int target = desiredTargets.get(cell);
                for (int[] offset : CARDINAL_OFFSETS) {
                    Cell neighbour = new Cell(cell.x() + offset[0], cell.z() + offset[1]);
                    Integer neighbourTarget = desiredTargets.get(neighbour);
                    if (neighbourTarget != null && areaId.equals(areaByCell.get(neighbour))
                            && Math.abs(target - neighbourTarget) <= 1 && remaining.remove(neighbour)) {
                        pending.addLast(neighbour);
                    }
                }
            }
            int platformY = dominantHeight(component.stream().map(desiredTargets::get).toList());
            component.forEach(cell -> result.put(cell, platformY));
        }
        return Map.copyOf(result);
    }

    private static int dominantHeight(List<Integer> values) {
        List<Integer> sorted = values.stream().sorted().toList();
        int median = sorted.get(sorted.size() / 2);
        Map<Integer, Integer> counts = new HashMap<>();
        sorted.forEach(value -> counts.merge(value, 1, Integer::sum));
        return counts.entrySet().stream().sorted(Comparator
                .<Map.Entry<Integer, Integer>>comparingInt(Map.Entry::getValue).reversed()
                .thenComparingInt(entry -> Math.abs(entry.getKey() - median))
                .thenComparingInt(Map.Entry::getKey)).findFirst().orElseThrow().getKey();
    }

    private static List<RetainingWallDecision> retainingWalls(Set<Cell> outputCells,
                                                               Map<Cell, String> areaByCell,
                                                               Map<Cell, Integer> platformTargets,
                                                               TerrainView terrain) {
        Map<String, RetainingWallDecision> result = new HashMap<>();
        for (Cell cell : outputCells) {
            Integer targetY = platformTargets.get(cell);
            if (targetY == null) continue;
            String areaId = areaByCell.get(cell);
            for (int[] offset : CARDINAL_OFFSETS) {
                Cell neighbour = new Cell(cell.x() + offset[0], cell.z() + offset[1]);
                int neighbourY = areaId.equals(areaByCell.get(neighbour))
                        ? platformTargets.getOrDefault(neighbour, required(terrain, neighbour).surfaceY())
                        : required(terrain, neighbour).surfaceY();
                if (targetY - neighbourY < 2) continue;
                for (int y = neighbourY + 1; y < targetY; y++) {
                    RetainingWallDecision wall = new RetainingWallDecision(areaId, cell.x(), y, cell.z(),
                            "minecraft:stone_bricks");
                    result.put(cell.x() + ":" + y + ":" + cell.z(), wall);
                }
            }
        }
        return result.values().stream().sorted(Comparator.comparingInt(RetainingWallDecision::z)
                .thenComparingInt(RetainingWallDecision::x)
                .thenComparingInt(RetainingWallDecision::y)).toList();
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

    record FoundationDecision(String areaId,
                              int x,
                              int z,
                              int surfaceY,
                              int targetY,
                              FoundationMode mode) {
        FoundationDecision {
            Objects.requireNonNull(areaId, "areaId");
            Objects.requireNonNull(mode, "mode");
        }
    }

    record FoundationPlan(List<FoundationDecision> decisions,
                          List<RetainingWallDecision> retainingWalls) {
        FoundationPlan {
            decisions = List.copyOf(decisions);
            retainingWalls = List.copyOf(retainingWalls);
        }
    }

    record RetainingWallDecision(String areaId, int x, int y, int z, String blockId) {
        RetainingWallDecision {
            Objects.requireNonNull(areaId, "areaId");
            Objects.requireNonNull(blockId, "blockId");
        }
    }

    enum FoundationMode {
        FILL,
        CUT,
        PRESERVE
    }

    private record Cell(int x, int z) {
    }
}
