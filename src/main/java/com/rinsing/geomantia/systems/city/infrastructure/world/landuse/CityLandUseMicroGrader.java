package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
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
    static final int PLATFORM_LEVEL_STEP_BLOCKS = 4;
    static final int MIN_INDEPENDENT_PLATFORM_AREA_BLOCKS = 64;

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
        PlatformModel model = platformModel(fragment, terrain);
        Map<Cell, String> foundationAreaByCell = model.areaByCell();
        if (foundationAreaByCell.isEmpty()) return new FoundationPlan(List.of(), List.of(), List.of());
        Map<Cell, Integer> platformTargets = new HashMap<>(model.platformTargets());
        List<StairDecision> stairs = platformStairs(fragment, foundationAreaByCell,
                platformTargets);
        stairs.forEach(stair -> platformTargets.put(new Cell(stair.x(), stair.z()), stair.targetY()));

        List<FoundationDecision> decisions = new ArrayList<>();
        Set<Cell> outputCells = new HashSet<>();
        for (CityLandUseChunkCompiler.SurfaceOperation operation : fragment.surfaceOperations()) {
            if (operation.surfaceOffset() != 0) continue;
            Cell center = new Cell(operation.x(), operation.z());
            if (!operation.areaId().equals(foundationAreaByCell.get(center))) continue;
            outputCells.add(center);
            CityLandUseChunkExecutor.ColumnSample sample = required(terrain, center);
            if (liquid(sample)) {
                Integer targetY = platformTargets.get(center);
                decisions.add(new FoundationDecision(operation.areaId(), operation.x(), operation.z(),
                        sample.surfaceY(), targetY != null && targetY > sample.surfaceY()
                        ? targetY : sample.surfaceY(),
                        targetY != null && targetY > sample.surfaceY()
                                ? FoundationMode.FILL : FoundationMode.PRESERVE));
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
        return new FoundationPlan(List.copyOf(decisions), retainingWalls, stairs);
    }

    static OptionalInt resolveStructureDatum(CityLandUseChunkCompiler.ChunkFragment fragment,
                                             TerrainView terrain,
                                             BlockBounds footprint) {
        Objects.requireNonNull(footprint, "footprint");
        PlatformModel model = platformModel(fragment, terrain);
        Map<Cell, String> areaByCell = model.areaByCell();
        for (int radius = 1; radius <= REFERENCE_RADIUS_BLOCKS; radius++) {
            int candidateRadius = radius;
            List<Integer> candidates = areaByCell.entrySet().stream()
                    .filter(entry -> outsideDistance(entry.getKey(), footprint) == candidateRadius)
                    .map(Map.Entry::getKey)
                    .map(model.platformTargets()::get)
                    .filter(Objects::nonNull)
                    .toList();
            if (!candidates.isEmpty()) {
                return OptionalInt.of(dominantHeight(candidates) + 1);
            }
        }
        return OptionalInt.empty();
    }

    private static PlatformModel platformModel(CityLandUseChunkCompiler.ChunkFragment fragment,
                                               TerrainView terrain) {
        Map<Cell, String> foundationAreaByCell = new HashMap<>();
        for (CityLandUseChunkCompiler.GradingMaskCell cell : fragment.gradingMaskCells()) {
            if (cell.foundation()) {
                foundationAreaByCell.putIfAbsent(new Cell(cell.x(), cell.z()), cell.areaId());
            }
        }
        if (foundationAreaByCell.isEmpty()) return new PlatformModel(Map.of(), Map.of());

        Map<Cell, Integer> desiredTargets = new HashMap<>();
        foundationAreaByCell.forEach((cell, areaId) -> {
            CityLandUseChunkExecutor.ColumnSample sample = required(terrain, cell);
            if (!liquid(sample)) {
                desiredTargets.put(cell, localDominantHeight(cell, areaId, foundationAreaByCell, terrain));
            }
        });
        Map<Cell, Integer> platformTargets = new HashMap<>(
                platformTargets(foundationAreaByCell, desiredTargets));
        closeSmallDryPlatformHoles(foundationAreaByCell, platformTargets);
        closeSmallLiquidHoles(foundationAreaByCell, platformTargets, terrain);
        return new PlatformModel(Map.copyOf(foundationAreaByCell), Map.copyOf(platformTargets));
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
                int target = quantizedPlatformHeight(desiredTargets.get(cell));
                for (int[] offset : CARDINAL_OFFSETS) {
                    Cell neighbour = new Cell(cell.x() + offset[0], cell.z() + offset[1]);
                    Integer neighbourTarget = desiredTargets.get(neighbour);
                    if (neighbourTarget != null && areaId.equals(areaByCell.get(neighbour))
                            && target == quantizedPlatformHeight(neighbourTarget)
                            && remaining.remove(neighbour)) {
                        pending.addLast(neighbour);
                    }
                }
            }
            int platformY = dominantHeight(component.stream()
                    .map(desiredTargets::get)
                    .map(CityLandUseMicroGrader::quantizedPlatformHeight)
                    .toList());
            component.forEach(cell -> result.put(cell, platformY));
        }
        mergeSubthresholdPlatforms(areaByCell, result);
        return Map.copyOf(result);
    }

    private static int quantizedPlatformHeight(int desiredHeight) {
        int halfStep = PLATFORM_LEVEL_STEP_BLOCKS / 2;
        return Math.floorDiv(desiredHeight + halfStep, PLATFORM_LEVEL_STEP_BLOCKS)
                * PLATFORM_LEVEL_STEP_BLOCKS;
    }

    private static void mergeSubthresholdPlatforms(Map<Cell, String> areaByCell,
                                                   Map<Cell, Integer> targets) {
        boolean changed;
        do {
            changed = false;
            Set<Cell> remaining = new HashSet<>(targets.keySet());
            while (!remaining.isEmpty()) {
                Cell first = stableFirst(remaining);
                String areaId = areaByCell.get(first);
                int targetY = targets.get(first);
                Set<Cell> component = sameTargetComponent(first, areaId, targetY,
                        areaByCell, targets, remaining);
                if (component.size() >= MIN_INDEPENDENT_PLATFORM_AREA_BLOCKS) continue;

                Map<Integer, Integer> sharedEdgesByTarget = new HashMap<>();
                for (Cell cell : component) {
                    for (int[] direction : CARDINAL_OFFSETS) {
                        Cell neighbour = offset(cell, direction);
                        if (component.contains(neighbour)
                                || !areaId.equals(areaByCell.get(neighbour))) continue;
                        Integer neighbourY = targets.get(neighbour);
                        if (neighbourY != null && neighbourY != targetY) {
                            sharedEdgesByTarget.merge(neighbourY, 1, Integer::sum);
                        }
                    }
                }
                Integer mergedY = sharedEdgesByTarget.entrySet().stream().sorted(Comparator
                                .<Map.Entry<Integer, Integer>>comparingInt(Map.Entry::getValue).reversed()
                                .thenComparingInt(entry -> Math.abs(entry.getKey() - targetY))
                                .thenComparingInt(Map.Entry::getKey))
                        .map(Map.Entry::getKey).findFirst().orElse(null);
                if (mergedY == null) continue;
                component.forEach(cell -> targets.put(cell, mergedY));
                changed = true;
            }
        } while (changed);
    }

    private static void closeSmallDryPlatformHoles(Map<Cell, String> areaByCell,
                                                    Map<Cell, Integer> targets) {
        Set<Cell> remaining = new HashSet<>(targets.keySet());
        while (!remaining.isEmpty()) {
            Cell first = stableFirst(remaining);
            String areaId = areaByCell.get(first);
            int componentY = targets.get(first);
            Set<Cell> component = sameTargetComponent(first, areaId, componentY,
                    areaByCell, targets, remaining);
            if (!smallComponent(component)) continue;

            List<Integer> boundaryTargets = new ArrayList<>();
            boolean enclosed = true;
            for (Cell cell : component) {
                for (int[] offset : CARDINAL_OFFSETS) {
                    Cell neighbour = offset(cell, offset);
                    if (component.contains(neighbour)) continue;
                    if (!areaId.equals(areaByCell.get(neighbour))) {
                        enclosed = false;
                        break;
                    }
                    Integer neighbourY = targets.get(neighbour);
                    if (neighbourY == null) {
                        enclosed = false;
                        break;
                    }
                    if (neighbourY != componentY) boundaryTargets.add(neighbourY);
                }
                if (!enclosed) break;
            }
            if (!enclosed || boundaryTargets.isEmpty()
                    || boundaryTargets.stream().anyMatch(height -> height < componentY + 2)) {
                continue;
            }
            int closureY = dominantHeight(boundaryTargets);
            component.forEach(cell -> targets.put(cell, closureY));
        }
    }

    private static void closeSmallLiquidHoles(Map<Cell, String> areaByCell,
                                              Map<Cell, Integer> targets,
                                              TerrainView terrain) {
        Set<Cell> remaining = areaByCell.keySet().stream()
                .filter(cell -> liquid(required(terrain, cell)))
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        while (!remaining.isEmpty()) {
            Cell first = stableFirst(remaining);
            String areaId = areaByCell.get(first);
            Set<Cell> component = liquidComponent(first, areaId, areaByCell, terrain, remaining);
            if (!smallComponent(component)) continue;

            List<Integer> boundaryTargets = new ArrayList<>();
            boolean enclosed = true;
            for (Cell cell : component) {
                for (int[] offset : CARDINAL_OFFSETS) {
                    Cell neighbour = offset(cell, offset);
                    if (component.contains(neighbour)) continue;
                    if (!areaId.equals(areaByCell.get(neighbour))) {
                        enclosed = false;
                        break;
                    }
                    Integer neighbourY = targets.get(neighbour);
                    if (neighbourY == null) {
                        enclosed = false;
                        break;
                    }
                    boundaryTargets.add(neighbourY);
                }
                if (!enclosed) break;
            }
            if (!enclosed || boundaryTargets.isEmpty()) continue;
            int min = boundaryTargets.stream().mapToInt(Integer::intValue).min().orElseThrow();
            int max = boundaryTargets.stream().mapToInt(Integer::intValue).max().orElseThrow();
            if (max - min > 1) continue;
            int closureY = dominantHeight(boundaryTargets);
            component.forEach(cell -> targets.put(cell, closureY));
        }
    }

    private static Set<Cell> sameTargetComponent(Cell first,
                                                 String areaId,
                                                 int targetY,
                                                 Map<Cell, String> areaByCell,
                                                 Map<Cell, Integer> targets,
                                                 Set<Cell> remaining) {
        Set<Cell> component = new HashSet<>();
        ArrayDeque<Cell> pending = new ArrayDeque<>();
        pending.add(first);
        remaining.remove(first);
        while (!pending.isEmpty()) {
            Cell cell = pending.removeFirst();
            component.add(cell);
            for (int[] direction : CARDINAL_OFFSETS) {
                Cell neighbour = offset(cell, direction);
                if (areaId.equals(areaByCell.get(neighbour))
                        && Integer.valueOf(targetY).equals(targets.get(neighbour))
                        && remaining.remove(neighbour)) {
                    pending.addLast(neighbour);
                }
            }
        }
        return component;
    }

    private static Set<Cell> liquidComponent(Cell first,
                                             String areaId,
                                             Map<Cell, String> areaByCell,
                                             TerrainView terrain,
                                             Set<Cell> remaining) {
        Set<Cell> component = new HashSet<>();
        ArrayDeque<Cell> pending = new ArrayDeque<>();
        pending.add(first);
        remaining.remove(first);
        while (!pending.isEmpty()) {
            Cell cell = pending.removeFirst();
            component.add(cell);
            for (int[] direction : CARDINAL_OFFSETS) {
                Cell neighbour = offset(cell, direction);
                if (areaId.equals(areaByCell.get(neighbour))
                        && remaining.contains(neighbour)
                        && liquid(required(terrain, neighbour))) {
                    remaining.remove(neighbour);
                    pending.addLast(neighbour);
                }
            }
        }
        return component;
    }

    private static boolean smallComponent(Set<Cell> component) {
        if (component.isEmpty() || component.size() > MAX_COMPONENT_AREA_BLOCKS) return false;
        int minX = component.stream().mapToInt(Cell::x).min().orElseThrow();
        int maxX = component.stream().mapToInt(Cell::x).max().orElseThrow();
        int minZ = component.stream().mapToInt(Cell::z).min().orElseThrow();
        int maxZ = component.stream().mapToInt(Cell::z).max().orElseThrow();
        return maxX - minX + 1 <= MAX_COMPONENT_SPAN_BLOCKS
                && maxZ - minZ + 1 <= MAX_COMPONENT_SPAN_BLOCKS;
    }

    private static Cell stableFirst(Set<Cell> cells) {
        return cells.stream().min(Comparator.comparingInt(Cell::z)
                .thenComparingInt(Cell::x)).orElseThrow();
    }

    private static Cell offset(Cell cell, int[] direction) {
        return new Cell(cell.x() + direction[0], cell.z() + direction[1]);
    }

    private static int outsideDistance(Cell cell, BlockBounds bounds) {
        if (cell.x() >= bounds.minX() && cell.x() <= bounds.maxX()
                && cell.z() >= bounds.minZ() && cell.z() <= bounds.maxZ()) {
            return 0;
        }
        int dx = cell.x() < bounds.minX() ? bounds.minX() - cell.x()
                : cell.x() > bounds.maxX() ? cell.x() - bounds.maxX() : 0;
        int dz = cell.z() < bounds.minZ() ? bounds.minZ() - cell.z()
                : cell.z() > bounds.maxZ() ? cell.z() - bounds.maxZ() : 0;
        return dx + dz;
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

    private static List<StairDecision> platformStairs(
            CityLandUseChunkCompiler.ChunkFragment fragment,
            Map<Cell, String> areaByCell,
            Map<Cell, Integer> platformTargets) {
        Map<String, List<CityLandUseChunkCompiler.FeatureOperation>> roadsBySource = new LinkedHashMap<>();
        Map<String, String> stairBlocks = new HashMap<>();
        for (CityLandUseChunkCompiler.FeatureOperation operation : fragment.gradingFeatureOperations()) {
            if (operation.kind() == CityLandUseSurfacePrintPlan.FeatureKind.ROAD_SLAB
                    && operation.surfaceOffset() == 0) {
                roadsBySource.computeIfAbsent(operation.sourceId(), ignored -> new ArrayList<>())
                        .add(operation);
            } else if (operation.kind() == CityLandUseSurfacePrintPlan.FeatureKind.ROAD_STAIR
                    && operation.surfaceOffset() == 0) {
                stairBlocks.putIfAbsent(operation.sourceId(), operation.blockId());
            }
        }
        Set<Cell> ownedSurfaceCells = fragment.surfaceOperations().stream()
                .filter(operation -> operation.surfaceOffset() == 0)
                .map(operation -> new Cell(operation.x(), operation.z()))
                .collect(java.util.stream.Collectors.toSet());
        Map<Cell, StairDecision> result = new LinkedHashMap<>();
        roadsBySource.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String sourceId = entry.getKey();
            List<CityLandUseChunkCompiler.FeatureOperation> operations = entry.getValue();
            String stairBlock = stairBlocks.getOrDefault(sourceId,
                    derivedStairBlock(operations.get(0).blockId()));
            if (stairBlock.isBlank()) return;

            Set<Cell> roadCells = operations.stream()
                    .map(operation -> new Cell(operation.x(), operation.z()))
                    .collect(java.util.stream.Collectors.toSet());
            Map<TransitionKey, List<Cell>> transitions = new LinkedHashMap<>();
            roadCells.stream().sorted(Comparator.comparingInt(Cell::z).thenComparingInt(Cell::x))
                    .forEach(cell -> {
                        for (int[] direction : new int[][]{{1, 0}, {0, 1}}) {
                            int stepX = direction[0];
                            int stepZ = direction[1];
                            Cell next = new Cell(cell.x() + stepX, cell.z() + stepZ);
                            if (!roadCells.contains(next)) continue;
                            Integer currentY = platformTargets.get(cell);
                            Integer nextY = platformTargets.get(next);
                            if (currentY == null || nextY == null
                                    || Math.abs(currentY - nextY) < PLATFORM_LEVEL_STEP_BLOCKS) continue;
                            Cell low = currentY < nextY ? cell : next;
                            int highDx = currentY < nextY ? stepX : -stepX;
                            int highDz = currentY < nextY ? stepZ : -stepZ;
                            int lowY = Math.min(currentY, nextY);
                            int highY = Math.max(currentY, nextY);
                            Axis axis = stepX != 0 ? Axis.HORIZONTAL : Axis.VERTICAL;
                            int boundary = axis == Axis.HORIZONTAL ? cell.x() : cell.z();
                            TransitionKey key = new TransitionKey(sourceId, axis, boundary,
                                    lowY, highY, highDx, highDz);
                            transitions.computeIfAbsent(key, ignored -> new ArrayList<>()).add(low);
                        }
                    });

            transitions.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(transition -> {
                TransitionKey key = transition.getKey();
                List<Cell> lowCells = transition.getValue().stream()
                        .sorted(key.axis() == Axis.HORIZONTAL
                                ? Comparator.comparingInt(Cell::z).thenComparingInt(Cell::x)
                                : Comparator.comparingInt(Cell::x).thenComparingInt(Cell::z))
                        .toList();
                int delta = key.highY() - key.lowY();
                boolean direct = lowCells.stream().allMatch(low -> directRunAvailable(low, key,
                        delta, roadCells, platformTargets));
                if (direct) {
                    for (Cell low : lowCells) {
                        for (int step = 0; step < delta; step++) {
                            Cell cell = new Cell(low.x() - key.highDx() * step,
                                    low.z() - key.highDz() * step);
                            putStair(result, new StairDecision(sourceId, cell.x(), cell.z(),
                                    key.highY() - 1 - step, stairBlock,
                                    facing(key.highDx(), key.highDz()), StairMode.DIRECT));
                        }
                    }
                    return;
                }
                addSplitStairs(result, sourceId, stairBlock, key, lowCells,
                        areaByCell, platformTargets);
            });
        });
        return result.values().stream()
                .filter(stair -> ownedSurfaceCells.contains(new Cell(stair.x(), stair.z())))
                .sorted(Comparator.comparingInt(StairDecision::z)
                        .thenComparingInt(StairDecision::x)
                        .thenComparing(StairDecision::sourceId))
                .toList();
    }

    private static boolean directRunAvailable(Cell low,
                                              TransitionKey key,
                                              int delta,
                                              Set<Cell> roadCells,
                                              Map<Cell, Integer> platformTargets) {
        for (int step = 0; step < delta; step++) {
            Cell cell = new Cell(low.x() - key.highDx() * step,
                    low.z() - key.highDz() * step);
            if (!roadCells.contains(cell)
                    || !Integer.valueOf(key.lowY()).equals(platformTargets.get(cell))) {
                return false;
            }
        }
        return true;
    }

    private static void addSplitStairs(Map<Cell, StairDecision> result,
                                       String sourceId,
                                       String stairBlock,
                                       TransitionKey key,
                                       List<Cell> lowCells,
                                       Map<Cell, String> areaByCell,
                                       Map<Cell, Integer> platformTargets) {
        int delta = key.highY() - key.lowY();
        Cell lowCenter = lowCells.get(lowCells.size() / 2);
        String areaId = areaByCell.get(lowCenter);
        if (areaId == null) return;
        int lateralX = -key.highDz();
        int lateralZ = key.highDx();
        List<Cell> branchCells = new ArrayList<>();
        branchCells.add(lowCenter);
        for (int step = 1; step < delta; step++) {
            branchCells.add(new Cell(lowCenter.x() + lateralX * step,
                    lowCenter.z() + lateralZ * step));
            branchCells.add(new Cell(lowCenter.x() - lateralX * step,
                    lowCenter.z() - lateralZ * step));
        }
        boolean available = branchCells.stream().allMatch(cell ->
                areaId.equals(areaByCell.get(cell))
                        && Integer.valueOf(key.lowY()).equals(platformTargets.get(cell)));
        if (!available) return;

        putStair(result, new StairDecision(sourceId, lowCenter.x(), lowCenter.z(),
                key.highY() - 1, stairBlock, facing(key.highDx(), key.highDz()), StairMode.SPLIT));
        for (int step = 1; step < delta; step++) {
            int targetY = key.highY() - 1 - step;
            Cell positive = new Cell(lowCenter.x() + lateralX * step,
                    lowCenter.z() + lateralZ * step);
            Cell negative = new Cell(lowCenter.x() - lateralX * step,
                    lowCenter.z() - lateralZ * step);
            putStair(result, new StairDecision(sourceId, positive.x(), positive.z(), targetY,
                    stairBlock, facing(-lateralX, -lateralZ), StairMode.SPLIT));
            putStair(result, new StairDecision(sourceId, negative.x(), negative.z(), targetY,
                    stairBlock, facing(lateralX, lateralZ), StairMode.SPLIT));
        }
    }

    private static void putStair(Map<Cell, StairDecision> result, StairDecision decision) {
        result.putIfAbsent(new Cell(decision.x(), decision.z()), decision);
    }

    private static String derivedStairBlock(String roadBlock) {
        return roadBlock.endsWith("_slab")
                ? roadBlock.substring(0, roadBlock.length() - "_slab".length()) + "_stairs" : "";
    }

    private static CityLandUseSurfacePrintPlan.HorizontalFacing facing(int dx, int dz) {
        if (dx == 1 && dz == 0) return CityLandUseSurfacePrintPlan.HorizontalFacing.EAST;
        if (dx == -1 && dz == 0) return CityLandUseSurfacePrintPlan.HorizontalFacing.WEST;
        if (dx == 0 && dz == 1) return CityLandUseSurfacePrintPlan.HorizontalFacing.SOUTH;
        if (dx == 0 && dz == -1) return CityLandUseSurfacePrintPlan.HorizontalFacing.NORTH;
        throw new IllegalArgumentException("CITY_LAND_USE_STAIR_DIRECTION_INVALID");
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
                          List<RetainingWallDecision> retainingWalls,
                          List<StairDecision> stairs) {
        FoundationPlan {
            decisions = List.copyOf(decisions);
            retainingWalls = List.copyOf(retainingWalls);
            stairs = List.copyOf(stairs);
        }
    }

    record StairDecision(String sourceId,
                         int x,
                         int z,
                         int targetY,
                         String blockId,
                         CityLandUseSurfacePrintPlan.HorizontalFacing facing,
                         StairMode mode) {
        StairDecision {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(blockId, "blockId");
            Objects.requireNonNull(facing, "facing");
            Objects.requireNonNull(mode, "mode");
            if (facing == CityLandUseSurfacePrintPlan.HorizontalFacing.NONE) {
                throw new IllegalArgumentException("CITY_LAND_USE_PLATFORM_STAIR_FACING_REQUIRED");
            }
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

    enum StairMode {
        DIRECT,
        SPLIT
    }

    private enum Axis {
        HORIZONTAL,
        VERTICAL
    }

    private record TransitionKey(String sourceId,
                                 Axis axis,
                                 int boundary,
                                 int lowY,
                                 int highY,
                                 int highDx,
                                 int highDz) implements Comparable<TransitionKey> {
        @Override
        public int compareTo(TransitionKey other) {
            int compared = sourceId.compareTo(other.sourceId);
            if (compared != 0) return compared;
            compared = axis.compareTo(other.axis);
            if (compared != 0) return compared;
            compared = Integer.compare(boundary, other.boundary);
            if (compared != 0) return compared;
            compared = Integer.compare(lowY, other.lowY);
            if (compared != 0) return compared;
            compared = Integer.compare(highY, other.highY);
            if (compared != 0) return compared;
            compared = Integer.compare(highDx, other.highDx);
            return compared != 0 ? compared : Integer.compare(highDz, other.highDz);
        }
    }

    private record PlatformModel(Map<Cell, String> areaByCell,
                                 Map<Cell, Integer> platformTargets) {
    }

    private record Cell(int x, int z) {
    }
}
