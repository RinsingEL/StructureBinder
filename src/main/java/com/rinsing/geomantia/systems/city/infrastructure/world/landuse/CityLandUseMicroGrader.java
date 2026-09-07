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
        Map<Cell, Integer> roadTargets = new HashMap<>(roadPlatformTargets(fragment, model.desiredTargets(), terrain));
        Set<Cell> frozenCells = new HashSet<>();
        for (var cell : fragment.gradingMaskCells()) if (cell.foundation() && cell.targetY() != null)
            frozenCells.add(new Cell(cell.x(),cell.z()));
        roadTargets.keySet().removeAll(frozenCells);
        platformTargets.putAll(roadTargets);
        fragment.gradingFeatureOperations().stream().filter(operation -> operation.targetSurfaceY() != null)
                .forEach(operation -> {
                    Cell cell = new Cell(operation.x(), operation.z());
                    if (foundationAreaByCell.containsKey(cell)) platformTargets.put(cell, operation.targetSurfaceY());
                });
        List<StairDecision> roadStairs = new ArrayList<>(platformStairs(fragment,
                foundationAreaByCell, platformTargets));
        roadStairs.addAll(junctionStairs(fragment, foundationAreaByCell, platformTargets));
        roadStairs = roadStairs.stream().collect(java.util.stream.Collectors.toMap(
                stair -> new Cell(stair.x(), stair.z()), stair -> stair,
                (left, right) -> left, LinkedHashMap::new)).values().stream()
                .sorted(Comparator.comparingInt(StairDecision::z)
                        .thenComparingInt(StairDecision::x)
                        .thenComparing(StairDecision::sourceId)).toList();
        AccessPlan accessPlan = platformAccess(fragment, foundationAreaByCell,
                platformTargets, roadStairs);
        List<StairDecision> stairs = new ArrayList<>(roadStairs);
        stairs.addAll(accessPlan.stairs());
        stairs = stairs.stream().collect(java.util.stream.Collectors.toMap(
                stair -> new Cell(stair.x(), stair.z()), stair -> stair,
                (left, right) -> left, LinkedHashMap::new)).values().stream()
                .sorted(Comparator.comparingInt(StairDecision::z)
                        .thenComparingInt(StairDecision::x)
                        .thenComparing(StairDecision::sourceId)).toList();
        stairs.forEach(stair -> platformTargets.put(new Cell(stair.x(), stair.z()), stair.targetY()));

        List<FoundationDecision> decisions = new ArrayList<>();
        Set<Cell> outputCells = new HashSet<>();
        for (CityLandUseChunkCompiler.SurfaceOperation operation : fragment.surfaceOperations()) {
            if (operation.surfaceOffset() != 0) continue;
            Cell center = new Cell(operation.x(), operation.z());
            if (!operation.areaId().equals(foundationAreaByCell.get(center))) continue;
            outputCells.add(center);
            CityLandUseChunkExecutor.ColumnSample sample = required(terrain, center);
            if (model.droppedCells().contains(center)) {
                decisions.add(new FoundationDecision(operation.areaId(), operation.x(), operation.z(),
                        sample.surfaceY(), sample.surfaceY(), FoundationMode.PRESERVE));
                continue;
            }
            if (liquid(sample)) {
                Integer targetY = platformTargets.get(center);
                decisions.add(new FoundationDecision(operation.areaId(), operation.x(), operation.z(),
                        sample.surfaceY(), targetY != null && targetY > sample.surfaceY()
                        ? targetY : sample.surfaceY(),
                        targetY != null && targetY > sample.surfaceY()
                                ? designedRealization(sample.surfaceY(), targetY) : FoundationMode.PRESERVE));
                continue;
            }
            if (!sample.naturalSurface()) continue;

            int targetY = platformTargets.getOrDefault(center, sample.surfaceY());
            int delta = targetY - sample.surfaceY();
            FoundationMode mode;
            if (frozenCells.contains(center)) {
                mode = designedRealization(sample.surfaceY(), targetY);
            } else if (delta > 0 && delta <= FOUNDATION_MAX_FILL_DEPTH_BLOCKS) {
                mode = FoundationMode.FILL;
            } else if (delta < 0 && -delta <= FOUNDATION_MAX_CUT_DEPTH_BLOCKS) {
                mode = FoundationMode.CUT;
            } else if (delta != 0) {
                mode = FoundationMode.PRESERVE;
            } else {
                if (!frozenCells.contains(center)) continue;
                mode = FoundationMode.FILL;
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
        List<TerraceEdgeDecision> terraceEdges = terraceEdges(fragment, outputCells,
                foundationAreaByCell, platformTargets, terrain, stairs, accessPlan.paths());
        return new FoundationPlan(List.copyOf(decisions), retainingWalls, terraceEdges, stairs,
                accessPlan.paths(), accessPlan.outcomes(), model.platformAdjustments());
    }

    static OptionalInt resolveStructureDatum(CityLandUseChunkCompiler.ChunkFragment fragment,
                                              TerrainView terrain,
                                              BlockBounds footprint) {
        return prepareStructureDatum(fragment, terrain).resolve(footprint);
    }

    static StructureDatumResolver prepareStructureDatum(CityLandUseChunkCompiler.ChunkFragment fragment,
                                                         TerrainView terrain) {
        Objects.requireNonNull(fragment, "fragment");
        Objects.requireNonNull(terrain, "terrain");
        PlatformModel model = platformModel(fragment, terrain);
        return new StructureDatumResolver(model.areaByCell(), model.platformTargets());
    }

    static final class StructureDatumResolver {
        private final Map<Cell, String> areaByCell;
        private final Map<Cell, Integer> platformTargets;

        private StructureDatumResolver(Map<Cell, String> areaByCell,
                                       Map<Cell, Integer> platformTargets) {
            this.areaByCell = areaByCell;
            this.platformTargets = platformTargets;
        }

        OptionalInt resolve(BlockBounds footprint) {
            Objects.requireNonNull(footprint, "footprint");
            for (int radius = 1; radius <= REFERENCE_RADIUS_BLOCKS; radius++) {
                int candidateRadius = radius;
                List<Integer> candidates = areaByCell.entrySet().stream()
                        .filter(entry -> outsideDistance(entry.getKey(), footprint) == candidateRadius)
                        .map(Map.Entry::getKey)
                        .map(platformTargets::get)
                        .filter(Objects::nonNull)
                        .toList();
                if (!candidates.isEmpty()) {
                    return OptionalInt.of(dominantHeight(candidates) + 1);
                }
            }
            return OptionalInt.empty();
        }
    }

    private static PlatformModel platformModel(CityLandUseChunkCompiler.ChunkFragment fragment,
                                               TerrainView terrain) {
        Map<Cell, String> foundationAreaByCell = new HashMap<>();
        for (CityLandUseChunkCompiler.GradingMaskCell cell : fragment.gradingMaskCells()) {
            if (cell.foundation()) {
                foundationAreaByCell.putIfAbsent(new Cell(cell.x(), cell.z()), cell.areaId());
            }
        }
        if (foundationAreaByCell.isEmpty()) {
            return new PlatformModel(Map.of(), Map.of(), Map.of(), Set.of(), List.of());
        }

        Map<Cell,Integer> frozen = new HashMap<>();
        for (var cell : fragment.gradingMaskCells()) if (cell.foundation() && cell.targetY() != null)
            frozen.put(new Cell(cell.x(),cell.z()),cell.targetY());
        if (!frozen.isEmpty()) {
            if (frozen.size() != foundationAreaByCell.size())
                throw new IllegalArgumentException("CITY_FOUNDATION_FROZEN_HEIGHT_INCOMPLETE");
            return new PlatformModel(Map.copyOf(foundationAreaByCell), Map.copyOf(frozen), Map.copyOf(frozen),
                    Set.of(), List.of());
        }

        Map<Cell, Integer> desiredTargets = new HashMap<>();
        foundationAreaByCell.forEach((cell, areaId) -> {
            CityLandUseChunkExecutor.ColumnSample sample = required(terrain, cell);
            if (!liquid(sample)) {
                desiredTargets.put(cell, localDominantHeight(cell, areaId, foundationAreaByCell, terrain));
            }
        });
        PlatformResolution resolution = platformTargets(foundationAreaByCell, desiredTargets,
                fragment.platformPurposeAnchors());
        Map<Cell, Integer> platformTargets = new HashMap<>(resolution.targets());
        closeSmallDryPlatformHoles(foundationAreaByCell, platformTargets);
        closeSmallLiquidHoles(foundationAreaByCell, platformTargets, terrain);
        return new PlatformModel(Map.copyOf(foundationAreaByCell), Map.copyOf(platformTargets),
                Map.copyOf(desiredTargets), resolution.droppedCells(), resolution.adjustments());
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

    /**
     * Roads keep one locally sampled, quantized height per longitudinal owner section instead of
     * inheriting owner-local platform-component merges. The surrounding city still uses the merged
     * platform model, while road interiors remain flat and only owner-section boundaries can step.
     */
    private static Map<Cell, Integer> roadPlatformTargets(
            CityLandUseChunkCompiler.ChunkFragment fragment,
            Map<Cell, Integer> desiredTargets,
            TerrainView terrain) {
        Map<String, List<CityLandUseChunkCompiler.FeatureOperation>> roadsBySource = new LinkedHashMap<>();
        for (CityLandUseChunkCompiler.FeatureOperation operation : fragment.gradingFeatureOperations()) {
            if (operation.kind() == CityLandUseSurfacePrintPlan.FeatureKind.ROAD_SLAB
                    && operation.surfaceOffset() == 0) {
                roadsBySource.computeIfAbsent(operation.sourceId(), ignored -> new ArrayList<>())
                        .add(operation);
            }
        }
        Map<Cell, Integer> result = new HashMap<>();
        roadsBySource.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (entry.getValue().stream().anyMatch(operation -> operation.targetSurfaceY() != null)) return;
            Axis axis = roadAxis(fragment, entry.getKey(), entry.getValue());
            Map<Integer, List<CityLandUseChunkCompiler.FeatureOperation>> ownerSections = new LinkedHashMap<>();
            entry.getValue().stream().sorted(Comparator
                            .comparingInt(CityLandUseChunkCompiler.FeatureOperation::z)
                            .thenComparingInt(CityLandUseChunkCompiler.FeatureOperation::x))
                    .forEach(operation -> ownerSections.computeIfAbsent(Math.floorDiv(
                            axis == Axis.HORIZONTAL ? operation.x() : operation.z(), 16),
                            ignored -> new ArrayList<>()).add(operation));
            if (ownerSections.size() < 2) return;
            Map<Integer, Integer> sectionTargets = new LinkedHashMap<>();
            ownerSections.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(section -> {
                List<Integer> heights = section.getValue().stream().map(operation -> {
                    Cell cell = new Cell(operation.x(), operation.z());
                    int desired = desiredTargets.getOrDefault(cell, required(terrain, cell).surfaceY());
                    return quantizedPlatformHeight(desired);
                }).toList();
                sectionTargets.put(section.getKey(), dominantHeight(heights));
            });
            Set<Integer> transitioningSections = new HashSet<>();
            List<Integer> orderedSections = sectionTargets.keySet().stream().sorted().toList();
            for (int index = 1; index < orderedSections.size(); index++) {
                int previous = orderedSections.get(index - 1);
                int current = orderedSections.get(index);
                if (current == previous + 1
                        && !sectionTargets.get(previous).equals(sectionTargets.get(current))) {
                    transitioningSections.add(previous);
                    transitioningSections.add(current);
                }
            }
            transitioningSections.forEach(section -> ownerSections.get(section).forEach(operation ->
                    result.put(new Cell(operation.x(), operation.z()), sectionTargets.get(section))));
        });
        return Map.copyOf(result);
    }

    private static Axis roadAxis(CityLandUseChunkCompiler.ChunkFragment fragment,
                                 String sourceId,
                                 List<CityLandUseChunkCompiler.FeatureOperation> roadCells) {
        for (CityLandUseChunkCompiler.FeatureOperation operation : fragment.gradingFeatureOperations()) {
            if (!sourceId.equals(operation.sourceId())
                    || operation.kind() != CityLandUseSurfacePrintPlan.FeatureKind.ROAD_STAIR) continue;
            if (operation.facing() == CityLandUseSurfacePrintPlan.HorizontalFacing.NORTH
                    || operation.facing() == CityLandUseSurfacePrintPlan.HorizontalFacing.SOUTH) {
                return Axis.HORIZONTAL;
            }
            if (operation.facing() == CityLandUseSurfacePrintPlan.HorizontalFacing.EAST
                    || operation.facing() == CityLandUseSurfacePrintPlan.HorizontalFacing.WEST) {
                return Axis.VERTICAL;
            }
        }
        int minX = roadCells.stream().mapToInt(CityLandUseChunkCompiler.FeatureOperation::x).min().orElse(0);
        int maxX = roadCells.stream().mapToInt(CityLandUseChunkCompiler.FeatureOperation::x).max().orElse(0);
        int minZ = roadCells.stream().mapToInt(CityLandUseChunkCompiler.FeatureOperation::z).min().orElse(0);
        int maxZ = roadCells.stream().mapToInt(CityLandUseChunkCompiler.FeatureOperation::z).max().orElse(0);
        return maxX - minX >= maxZ - minZ ? Axis.HORIZONTAL : Axis.VERTICAL;
    }

    private static PlatformResolution platformTargets(
            Map<Cell, String> areaByCell,
            Map<Cell, Integer> desiredTargets,
            List<CityLandUseChunkCompiler.PlatformPurposeAnchor> purposeAnchors) {
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
        Set<Cell> droppedCells = new HashSet<>();
        List<PlatformAdjustment> adjustments = new ArrayList<>();
        mergeInvalidPlatforms(areaByCell, result, purposeAnchors, droppedCells, adjustments);
        return new PlatformResolution(Map.copyOf(result), Set.copyOf(droppedCells), adjustments);
    }

    private static int quantizedPlatformHeight(int desiredHeight) {
        int halfStep = PLATFORM_LEVEL_STEP_BLOCKS / 2;
        return Math.floorDiv(desiredHeight + halfStep, PLATFORM_LEVEL_STEP_BLOCKS)
                * PLATFORM_LEVEL_STEP_BLOCKS;
    }

    private static void mergeInvalidPlatforms(
            Map<Cell, String> areaByCell,
            Map<Cell, Integer> targets,
            List<CityLandUseChunkCompiler.PlatformPurposeAnchor> purposeAnchors,
            Set<Cell> droppedCells,
            List<PlatformAdjustment> adjustments) {
        boolean legacyAreaOnly = purposeAnchors.isEmpty();
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
                List<CityLandUseChunkCompiler.PlatformPurposeAnchor> componentPurposes =
                        purposeAnchors.stream().filter(anchor -> areaId.equals(anchor.areaId())
                                        && component.stream().anyMatch(cell ->
                                        outsideDistance(cell, anchor.bounds()) <= 1)).toList();
                List<String> purposeIds = componentPurposes.stream()
                        .map(CityLandUseChunkCompiler.PlatformPurposeAnchor::purposeId).sorted().toList();
                boolean hasPurpose = legacyAreaOnly || !purposeIds.isEmpty();
                // Capacity for a stair comes from actual grading cells, not excluded building
                // footprints. Keep an isolated purposeful pad below; merge small adjacent ledges.
                if (component.size() >= MIN_INDEPENDENT_PLATFORM_AREA_BLOCKS && hasPurpose) {
                    continue;
                }

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
                if (mergedY == null) {
                    if (legacyAreaOnly || hasPurpose) continue;
                    droppedCells.addAll(component);
                    adjustments.add(new PlatformAdjustment(areaId, component.size(), targetY, null,
                            PlatformAdjustmentStatus.WITHDRAWN,
                            "CITY_LAND_USE_PLATFORM_NO_SAFE_MERGE_WITHDRAWN", purposeIds));
                    component.forEach(targets::remove);
                    changed = true;
                    continue;
                }
                adjustments.add(new PlatformAdjustment(areaId, component.size(), targetY, mergedY,
                        PlatformAdjustmentStatus.MERGED,
                        hasPurpose ? "CITY_LAND_USE_PLATFORM_CAPACITY_TOO_SMALL"
                                : "CITY_LAND_USE_PLATFORM_PURPOSE_MISSING",
                        purposeIds));
                component.forEach(cell -> targets.put(cell, mergedY));
                changed = true;
            }
        } while (changed);
        if (!legacyAreaOnly) {
            Set<Cell> remaining = new HashSet<>(targets.keySet());
            while (!remaining.isEmpty()) {
                Cell first = stableFirst(remaining);
                String areaId = areaByCell.get(first);
                int targetY = targets.get(first);
                Set<Cell> component = sameTargetComponent(first, areaId, targetY,
                        areaByCell, targets, remaining);
                List<String> purposeIds = purposeAnchors.stream().filter(anchor ->
                                areaId.equals(anchor.areaId()) && component.stream()
                                        .anyMatch(cell -> outsideDistance(cell, anchor.bounds()) <= 1))
                        .map(CityLandUseChunkCompiler.PlatformPurposeAnchor::purposeId).sorted().toList();
                adjustments.add(new PlatformAdjustment(areaId, component.size(), targetY, targetY,
                        PlatformAdjustmentStatus.RETAINED,
                        "CITY_LAND_USE_PLATFORM_PURPOSE_RETAINED", purposeIds));
            }
        }
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

    /** Covers turns and intersections where adjacent road cells belong to different frozen bands. */
    private static List<StairDecision> junctionStairs(
            CityLandUseChunkCompiler.ChunkFragment fragment,
            Map<Cell, String> areaByCell,
            Map<Cell, Integer> platformTargets) {
        Map<Cell, CityLandUseChunkCompiler.FeatureOperation> roadByCell = new HashMap<>();
        for (CityLandUseChunkCompiler.FeatureOperation operation : fragment.gradingFeatureOperations()) {
            if (operation.kind() == CityLandUseSurfacePrintPlan.FeatureKind.ROAD_SLAB
                    && operation.surfaceOffset() == 0) {
                roadByCell.putIfAbsent(new Cell(operation.x(), operation.z()), operation);
            }
        }
        if (roadByCell.isEmpty()) return List.of();

        Set<Cell> roadCells = roadByCell.keySet();
        Map<TransitionKey, List<Cell>> transitions = new LinkedHashMap<>();
        roadCells.stream().sorted(Comparator.comparingInt(Cell::z).thenComparingInt(Cell::x))
                .forEach(cell -> {
                    CityLandUseChunkCompiler.FeatureOperation current = roadByCell.get(cell);
                    for (int[] direction : new int[][]{{1, 0}, {0, 1}}) {
                        Cell next = new Cell(cell.x() + direction[0], cell.z() + direction[1]);
                        CityLandUseChunkCompiler.FeatureOperation neighbour = roadByCell.get(next);
                        if (neighbour == null || current.sourceId().equals(neighbour.sourceId())) continue;
                        Integer currentY = platformTargets.get(cell);
                        Integer nextY = platformTargets.get(next);
                        if (currentY == null || nextY == null
                                || Math.abs(currentY - nextY) < PLATFORM_LEVEL_STEP_BLOCKS) continue;
                        boolean currentLow = currentY < nextY;
                        Cell low = currentLow ? cell : next;
                        CityLandUseChunkCompiler.FeatureOperation lowOperation = currentLow ? current : neighbour;
                        int highDx = currentLow ? direction[0] : -direction[0];
                        int highDz = currentLow ? direction[1] : -direction[1];
                        Axis axis = direction[0] != 0 ? Axis.HORIZONTAL : Axis.VERTICAL;
                        int boundary = axis == Axis.HORIZONTAL ? cell.x() : cell.z();
                        TransitionKey key = new TransitionKey(lowOperation.sourceId(), axis, boundary,
                                Math.min(currentY, nextY), Math.max(currentY, nextY), highDx, highDz);
                        transitions.computeIfAbsent(key, ignored -> new ArrayList<>()).add(low);
                    }
                });

        Map<Cell, StairDecision> result = new LinkedHashMap<>();
        transitions.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(transition -> {
            TransitionKey key = transition.getKey();
            CityLandUseChunkCompiler.FeatureOperation material = roadByCell.get(transition.getValue().get(0));
            String stairBlock = material == null ? "" : derivedStairBlock(material.blockId());
            if (stairBlock.isBlank()) return;
            List<Cell> lowCells = transition.getValue().stream().distinct()
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
                        Cell stairCell = new Cell(low.x() - key.highDx() * step,
                                low.z() - key.highDz() * step);
                        putStair(result, new StairDecision(key.sourceId(), stairCell.x(), stairCell.z(),
                                key.highY() - 1 - step, stairBlock,
                                facing(key.highDx(), key.highDz()), StairMode.DIRECT));
                    }
                }
            } else {
                addSplitStairs(result, key.sourceId(), stairBlock, key, lowCells,
                        areaByCell, platformTargets);
            }
        });

        Set<Cell> ownedSurfaceCells = fragment.surfaceOperations().stream()
                .filter(operation -> operation.surfaceOffset() == 0)
                .map(operation -> new Cell(operation.x(), operation.z()))
                .collect(java.util.stream.Collectors.toSet());
        return result.values().stream().filter(stair ->
                        ownedSurfaceCells.contains(new Cell(stair.x(), stair.z())))
                .sorted(Comparator.comparingInt(StairDecision::z)
                        .thenComparingInt(StairDecision::x)
                        .thenComparing(StairDecision::sourceId))
                .toList();
    }

    private static AccessPlan platformAccess(
            CityLandUseChunkCompiler.ChunkFragment fragment,
            Map<Cell, String> areaByCell,
            Map<Cell, Integer> platformTargets,
            List<StairDecision> roadStairs) {
        if (fragment.platformAccessDemands().isEmpty()) {
            return new AccessPlan(List.of(), List.of(), List.of());
        }
        Set<Cell> ownedSurfaceCells = fragment.surfaceOperations().stream()
                .filter(operation -> operation.surfaceOffset() == 0)
                .map(operation -> new Cell(operation.x(), operation.z()))
                .collect(java.util.stream.Collectors.toSet());
        String pathBlock = fragment.gradingFeatureOperations().stream()
                .filter(operation -> operation.kind() == CityLandUseSurfacePrintPlan.FeatureKind.ROAD_SLAB)
                .map(CityLandUseChunkCompiler.FeatureOperation::blockId)
                .findFirst().orElse("minecraft:polished_andesite_slab");
        String stairBlock = derivedStairBlock(pathBlock);
        if (stairBlock.isBlank()) stairBlock = "minecraft:polished_andesite_stairs";

        Map<Cell, StairDecision> stairs = new LinkedHashMap<>();
        Map<Cell, AccessPathDecision> paths = new LinkedHashMap<>();
        List<AccessOutcome> outcomes = new ArrayList<>();
        List<Set<Cell>> activelyServedPlatforms = new ArrayList<>();
        List<CityLandUseChunkCompiler.PlatformAccessDemand> demands =
                fragment.platformAccessDemands().stream()
                        .sorted(Comparator.comparing(
                                CityLandUseChunkCompiler.PlatformAccessDemand::demandId)).toList();
        for (CityLandUseChunkCompiler.PlatformAccessDemand demand : demands) {
            Cell entrance = new Cell(demand.entrance().x(), demand.entrance().z());
            Cell platformCell = platformTargets.keySet().stream()
                    .filter(cell -> demand.areaId().equals(areaByCell.get(cell)))
                    .min(Comparator.comparingInt((Cell cell) -> manhattan(cell, entrance))
                            .thenComparingInt(Cell::z).thenComparingInt(Cell::x))
                    .orElse(null);
            if (platformCell == null || manhattan(platformCell, entrance) > REFERENCE_RADIUS_BLOCKS + 1) {
                if (insideOwnerChunk(fragment, entrance)) {
                    // Gate slots also describe buildings outside the artificial foundation mask.
                    // No nearby platform means no platform-to-platform stair operation applies;
                    // this is not a claim that the natural entrance has been rebuilt or verified.
                    outcomes.add(new AccessOutcome(demand.demandId(), AccessStatus.NO_ARTIFICIAL_PLATFORM,
                            "CITY_LAND_USE_ACCESS_NATURAL_TERRAIN_UNMODIFIED"));
                }
                continue;
            }
            int platformY = platformTargets.get(platformCell);
            Set<Cell> component = sameTargetComponent(platformCell, demand.areaId(), platformY,
                    areaByCell, platformTargets, new HashSet<>(platformTargets.keySet()));
            Set<Cell> alreadyServed = activelyServedPlatforms.stream()
                    .filter(component::equals).findFirst().orElse(null);
            if (alreadyServed != null) {
                Cell join = paths.keySet().stream().filter(component::contains)
                        .min(Comparator.comparingInt((Cell cell) -> manhattan(platformCell, cell))
                                .thenComparingInt(Cell::z).thenComparingInt(Cell::x))
                        .orElse(platformCell);
                addAccessPath(paths, demand, pathBlock,
                        shortestPath(platformCell, join, component), ownedSurfaceCells, stairs.keySet());
                outcomes.add(new AccessOutcome(demand.demandId(), AccessStatus.SHARED_PLATFORM_ACCESS,
                        "CITY_LAND_USE_ACCESS_SHARED_PLATFORM_MAIN_ENTRANCE"));
                continue;
            }
            boolean existingAccess = roadStairs.stream().anyMatch(stair -> component.stream()
                    .anyMatch(cell -> manhattan(cell, new Cell(stair.x(), stair.z())) <= 1));
            if (existingAccess) {
                outcomes.add(new AccessOutcome(demand.demandId(), AccessStatus.EXISTING_ROAD_STAIR,
                        "CITY_LAND_USE_ACCESS_EXISTING_ROAD_STAIR"));
                continue;
            }

            List<AccessBoundary> boundaries = new ArrayList<>();
            for (Cell cell : component) {
                for (int[] direction : CARDINAL_OFFSETS) {
                    Cell neighbour = offset(cell, direction);
                    Integer neighbourY = platformTargets.get(neighbour);
                    if (!demand.areaId().equals(areaByCell.get(neighbour)) || neighbourY == null
                            || Math.abs(neighbourY - platformY) < PLATFORM_LEVEL_STEP_BLOCKS) continue;
                    Cell low = platformY < neighbourY ? cell : neighbour;
                    int highDx = platformY < neighbourY ? direction[0] : -direction[0];
                    int highDz = platformY < neighbourY ? direction[1] : -direction[1];
                    boundaries.add(new AccessBoundary(cell, low, Math.min(platformY, neighbourY),
                            Math.max(platformY, neighbourY), highDx, highDz));
                }
            }
            boundaries.sort(Comparator.comparingInt((AccessBoundary boundary) ->
                            manhattan(platformCell, boundary.occupiedSide()))
                    .thenComparingInt(boundary -> boundary.highY() - boundary.lowY())
                    .thenComparingInt(boundary -> boundary.occupiedSide().z())
                    .thenComparingInt(boundary -> boundary.occupiedSide().x()));
            if (boundaries.isEmpty()) {
                outcomes.add(new AccessOutcome(demand.demandId(), AccessStatus.LEVEL_ACCESS,
                        "CITY_LAND_USE_ACCESS_NO_LEVEL_CHANGE"));
                addAccessPath(paths, demand, pathBlock, shortestPath(platformCell, platformCell,
                        component), ownedSurfaceCells, Set.of());
                continue;
            }

            boolean built = false;
            for (AccessBoundary boundary : boundaries) {
                int delta = boundary.highY() - boundary.lowY();
                TransitionKey key = new TransitionKey("access::" + demand.demandId(),
                        boundary.highDx() != 0 ? Axis.HORIZONTAL : Axis.VERTICAL, 0,
                        boundary.lowY(), boundary.highY(), boundary.highDx(), boundary.highDz());
                Map<Cell, StairDecision> candidate = new LinkedHashMap<>();
                if (directRunAvailable(boundary.low(), key, delta, areaByCell, platformTargets)) {
                    for (int step = 0; step < delta; step++) {
                        Cell cell = new Cell(boundary.low().x() - key.highDx() * step,
                                boundary.low().z() - key.highDz() * step);
                        putStair(candidate, new StairDecision(key.sourceId(), cell.x(), cell.z(),
                                key.highY() - 1 - step, stairBlock,
                                facing(key.highDx(), key.highDz()), StairMode.ACCESS_DIRECT));
                    }
                } else {
                    addSplitStairs(candidate, key.sourceId(), stairBlock, key,
                            List.of(boundary.low()), areaByCell, platformTargets, StairMode.ACCESS_SPLIT);
                }
                if (candidate.isEmpty()) {
                    addCutThroughStairs(candidate, key, boundary.low(), stairBlock, areaByCell, platformTargets);
                }
                if (candidate.isEmpty()) continue;
                Set<Cell> stairCells = candidate.keySet();
                List<Cell> path = shortestPath(platformCell, boundary.occupiedSide(), component);
                if (path.isEmpty()) continue;
                candidate.forEach((cell, stair) -> {
                    if (ownedSurfaceCells.contains(cell)) stairs.putIfAbsent(cell, stair);
                });
                addAccessPath(paths, demand, pathBlock, path, ownedSurfaceCells, stairCells);
                outcomes.add(new AccessOutcome(demand.demandId(), AccessStatus.ACTIVE_STAIR,
                        candidate.values().iterator().next().mode().name()));
                activelyServedPlatforms.add(Set.copyOf(component));
                built = true;
                break;
            }
            if (!built && insideOwnerChunk(fragment, entrance)) {
                outcomes.add(new AccessOutcome(demand.demandId(), AccessStatus.FAILED,
                        "CITY_LAND_USE_ACCESS_STAIR_UNRESOLVED"));
            }
        }
        return new AccessPlan(List.copyOf(stairs.values()), List.copyOf(paths.values()), outcomes);
    }

    private static boolean insideOwnerChunk(CityLandUseChunkCompiler.ChunkFragment fragment, Cell cell) {
        int minX = fragment.chunkX() * 16;
        int minZ = fragment.chunkZ() * 16;
        return cell.x() >= minX && cell.x() <= minX + 15
                && cell.z() >= minZ && cell.z() <= minZ + 15;
    }

    private static int manhattan(Cell left, Cell right) {
        return Math.abs(left.x() - right.x()) + Math.abs(left.z() - right.z());
    }

    private static void addAccessPath(Map<Cell, AccessPathDecision> result,
                                      CityLandUseChunkCompiler.PlatformAccessDemand demand,
                                      String blockId,
                                      List<Cell> path,
                                      Set<Cell> ownedSurfaceCells,
                                      Set<Cell> stairCells) {
        for (Cell cell : path) {
            if (ownedSurfaceCells.contains(cell) && !stairCells.contains(cell)) {
                result.putIfAbsent(cell, new AccessPathDecision(demand.demandId(),
                        cell.x(), cell.z(), blockId));
            }
        }
    }

    private static List<Cell> shortestPath(Cell start, Cell goal, Set<Cell> domain) {
        if (!domain.contains(start) || !domain.contains(goal)) return List.of();
        ArrayDeque<Cell> pending = new ArrayDeque<>();
        Map<Cell, Cell> previous = new HashMap<>();
        pending.add(start);
        previous.put(start, null);
        while (!pending.isEmpty()) {
            Cell cell = pending.removeFirst();
            if (cell.equals(goal)) break;
            for (int[] direction : CARDINAL_OFFSETS) {
                Cell next = offset(cell, direction);
                if (domain.contains(next) && !previous.containsKey(next)) {
                    previous.put(next, cell);
                    pending.addLast(next);
                }
            }
        }
        if (!previous.containsKey(goal)) return List.of();
        List<Cell> path = new ArrayList<>();
        for (Cell cell = goal; cell != null; cell = previous.get(cell)) path.add(cell);
        java.util.Collections.reverse(path);
        return path;
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

    private static boolean directRunAvailable(Cell low,
                                              TransitionKey key,
                                              int delta,
                                              Map<Cell, String> areaByCell,
                                              Map<Cell, Integer> platformTargets) {
        String areaId = areaByCell.get(low);
        for (int step = 0; step < delta; step++) {
            Cell cell = new Cell(low.x() - key.highDx() * step,
                    low.z() - key.highDz() * step);
            if (!areaId.equals(areaByCell.get(cell))
                    || !Integer.valueOf(key.lowY()).equals(platformTargets.get(cell))) return false;
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
        addSplitStairs(result, sourceId, stairBlock, key, lowCells,
                areaByCell, platformTargets, StairMode.SPLIT);
    }

    private static void addSplitStairs(Map<Cell, StairDecision> result,
                                       String sourceId,
                                       String stairBlock,
                                       TransitionKey key,
                                       List<Cell> lowCells,
                                       Map<Cell, String> areaByCell,
                                       Map<Cell, Integer> platformTargets,
                                       StairMode stairMode) {
        int delta = key.highY() - key.lowY();
        Cell lowCenter = lowCells.get(lowCells.size() / 2);
        String areaId = areaByCell.get(lowCenter);
        if (areaId == null) return;
        int lateralX = -key.highDz();
        int lateralZ = key.highDx();
        if (!Integer.valueOf(key.lowY()).equals(platformTargets.get(lowCenter))) return;
        List<Integer> availableSides = new ArrayList<>();
        for (int side : new int[]{1, -1}) {
            boolean available = true;
            for (int step = 1; step < delta; step++) {
                Cell cell = new Cell(lowCenter.x() + side * lateralX * step,
                        lowCenter.z() + side * lateralZ * step);
                if (!areaId.equals(areaByCell.get(cell))
                        || !Integer.valueOf(key.lowY()).equals(platformTargets.get(cell))) {
                    available = false;
                    break;
                }
            }
            if (available) availableSides.add(side);
        }
        if (availableSides.isEmpty()) return;

        putStair(result, new StairDecision(sourceId, lowCenter.x(), lowCenter.z(),
                key.highY() - 1, stairBlock, facing(key.highDx(), key.highDz()), stairMode));
        for (int step = 1; step < delta; step++) {
            int targetY = key.highY() - 1 - step;
            for (int side : availableSides) {
                Cell cell = new Cell(lowCenter.x() + side * lateralX * step,
                        lowCenter.z() + side * lateralZ * step);
                putStair(result, new StairDecision(sourceId, cell.x(), cell.z(), targetY,
                        stairBlock, facing(-side * lateralX, -side * lateralZ), stairMode));
            }
        }
    }

    private static void addCutThroughStairs(Map<Cell, StairDecision> result, TransitionKey key,
                                           Cell low, String block, Map<Cell, String> areas,
                                           Map<Cell, Integer> targets) {
        String area = areas.get(low);
        int delta = key.highY() - key.lowY();
        for (int cut = 1; cut < Math.min(delta, FOUNDATION_MAX_CUT_DEPTH_BLOCKS); cut++) {
            Cell highMouth = new Cell(low.x() + key.highDx() * (cut + 1),
                    low.z() + key.highDz() * (cut + 1));
            Cell lowMouth = new Cell(low.x() - key.highDx() * (delta - cut),
                    low.z() - key.highDz() * (delta - cut));
            if (!area.equals(areas.get(highMouth)) || !area.equals(areas.get(lowMouth))
                    || !Integer.valueOf(key.highY()).equals(targets.get(highMouth))
                    || !Integer.valueOf(key.lowY()).equals(targets.get(lowMouth))) continue;
            Map<Cell, StairDecision> candidate = new LinkedHashMap<>();
            for (int step = 0; step < delta; step++) {
                Cell cell = new Cell(low.x() + key.highDx() * (cut - step),
                        low.z() + key.highDz() * (cut - step));
                Integer existing = targets.get(cell);
                int y = key.highY() - 1 - step;
                if (!area.equals(areas.get(cell)) || existing == null
                        || existing - y > FOUNDATION_MAX_CUT_DEPTH_BLOCKS
                        || y - existing > FOUNDATION_MAX_FILL_DEPTH_BLOCKS) { candidate.clear(); break; }
                candidate.put(cell, new StairDecision(key.sourceId(), cell.x(), cell.z(), y, block,
                        facing(key.highDx(), key.highDz()), StairMode.ACCESS_CUT));
            }
            if (!candidate.isEmpty()) { result.putAll(candidate); return; }
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

    private static List<TerraceEdgeDecision> terraceEdges(
            CityLandUseChunkCompiler.ChunkFragment fragment,
            Set<Cell> outputCells,
            Map<Cell, String> areaByCell,
            Map<Cell, Integer> platformTargets,
            TerrainView terrain,
            List<StairDecision> stairs,
            List<AccessPathDecision> accessPaths) {
        Set<Cell> protectedCells = new HashSet<>();
        stairs.forEach(stair -> protectEdgeOpening(protectedCells, new Cell(stair.x(), stair.z())));
        accessPaths.forEach(path -> protectEdgeOpening(protectedCells, new Cell(path.x(), path.z())));
        fragment.platformAccessDemands().forEach(demand -> protectEdgeOpening(protectedCells,
                new Cell(demand.entrance().x(), demand.entrance().z())));

        Set<Cell> occupiedCells = new HashSet<>();
        fragment.platformPurposeAnchors().stream()
                .filter(anchor -> anchor.purpose() == CityLandUseChunkCompiler.PlatformPurpose.BUILDING)
                .forEach(anchor -> {
                    for (int z = anchor.bounds().minZ(); z <= anchor.bounds().maxZ(); z++) {
                        for (int x = anchor.bounds().minX(); x <= anchor.bounds().maxX(); x++) {
                            occupiedCells.add(new Cell(x, z));
                        }
                    }
                });
        fragment.surfaceOperations().stream()
                .filter(operation -> operation.surfaceOffset() > 0)
                .map(operation -> new Cell(operation.x(), operation.z()))
                .forEach(occupiedCells::add);
        fragment.gradingFeatureOperations().stream()
                .filter(operation -> operation.surfaceOffset() > 0)
                .map(operation -> new Cell(operation.x(), operation.z()))
                .forEach(occupiedCells::add);
        fragment.boundaryOperations().stream()
                .map(operation -> new Cell(operation.x(), operation.z()))
                .forEach(occupiedCells::add);

        Set<Cell> roadCells = fragment.gradingFeatureOperations().stream()
                .filter(operation -> operation.kind() == CityLandUseSurfacePrintPlan.FeatureKind.ROAD_SLAB)
                .map(operation -> new Cell(operation.x(), operation.z()))
                .collect(java.util.stream.Collectors.toSet());
        List<TerraceEdgeDecision> result = new ArrayList<>();
        outputCells.stream().sorted(Comparator.comparingInt(Cell::z).thenComparingInt(Cell::x))
                .forEach(cell -> {
                    if (protectedCells.contains(cell) || occupiedCells.contains(cell)) return;
                    Integer targetY = platformTargets.get(cell);
                    if (targetY == null) return;
                    String areaId = areaByCell.get(cell);
                    int[] outward = null;
                    int greatestDrop = 0;
                    int lowerNeighbourCount = 0;
                    for (int[] direction : CARDINAL_OFFSETS) {
                        Cell neighbour = offset(cell, direction);
                        int neighbourY = areaId.equals(areaByCell.get(neighbour))
                                ? platformTargets.getOrDefault(neighbour, required(terrain, neighbour).surfaceY())
                                : required(terrain, neighbour).surfaceY();
                        int drop = targetY - neighbourY;
                        if (drop < 2) continue;
                        lowerNeighbourCount++;
                        if (outward == null || drop > greatestDrop) {
                            outward = direction;
                            greatestDrop = drop;
                        }
                    }
                    if (outward == null) return;
                    TerraceEdgeKind kind = roadCells.contains(cell) || lowerNeighbourCount > 1
                            ? TerraceEdgeKind.RAILING
                            : terraceEdgeKind(areaId, targetY, cell, outward);
                    String blockId = kind == TerraceEdgeKind.GREENERY
                            ? "minecraft:flowering_azalea_leaves"
                            : "minecraft:stone_brick_wall";
                    result.add(new TerraceEdgeDecision(areaId, cell.x(), targetY + 1, cell.z(),
                            blockId, kind));
                });
        return List.copyOf(result);
    }

    private static void protectEdgeOpening(Set<Cell> protectedCells, Cell center) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                protectedCells.add(new Cell(center.x() + dx, center.z() + dz));
            }
        }
    }

    private static TerraceEdgeKind terraceEdgeKind(String areaId, int targetY, Cell cell, int[] outward) {
        int tangent = outward[0] != 0 ? cell.z() : cell.x();
        int phase = Math.floorMod(Objects.hash(areaId, targetY, outward[0], outward[1]), 8);
        return Math.floorMod(tangent - phase, 8) < 2
                ? TerraceEdgeKind.GREENERY : TerraceEdgeKind.RAILING;
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
                          List<TerraceEdgeDecision> terraceEdges,
                          List<StairDecision> stairs,
                          List<AccessPathDecision> accessPaths,
                          List<AccessOutcome> accessOutcomes,
                          List<PlatformAdjustment> platformAdjustments) {
        FoundationPlan {
            decisions = List.copyOf(decisions);
            retainingWalls = List.copyOf(retainingWalls);
            terraceEdges = List.copyOf(terraceEdges);
            stairs = List.copyOf(stairs);
            accessPaths = List.copyOf(accessPaths);
            accessOutcomes = List.copyOf(accessOutcomes);
            platformAdjustments = List.copyOf(platformAdjustments);
        }

        FoundationPlan(List<FoundationDecision> decisions,
                       List<RetainingWallDecision> retainingWalls,
                       List<StairDecision> stairs) {
            this(decisions, retainingWalls, List.of(), stairs, List.of(), List.of(), List.of());
        }
    }

    record PlatformAdjustment(String areaId,
                              int cellCount,
                              int fromY,
                              Integer targetY,
                              PlatformAdjustmentStatus status,
                              String reasonCode,
                              List<String> purposeIds) {
        PlatformAdjustment {
            Objects.requireNonNull(areaId, "areaId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(reasonCode, "reasonCode");
            purposeIds = List.copyOf(purposeIds);
        }
    }

    record AccessPathDecision(String demandId, int x, int z, String blockId) {
        AccessPathDecision {
            Objects.requireNonNull(demandId, "demandId");
            Objects.requireNonNull(blockId, "blockId");
        }
    }

    record AccessOutcome(String demandId, AccessStatus status, String reasonCode) {
        AccessOutcome {
            Objects.requireNonNull(demandId, "demandId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(reasonCode, "reasonCode");
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

    record TerraceEdgeDecision(String areaId,
                               int x,
                               int y,
                               int z,
                               String blockId,
                               TerraceEdgeKind kind) {
        TerraceEdgeDecision {
            Objects.requireNonNull(areaId, "areaId");
            Objects.requireNonNull(blockId, "blockId");
            Objects.requireNonNull(kind, "kind");
        }
    }

    enum TerraceEdgeKind {
        RAILING,
        GREENERY
    }

    /** Realization of a committed surface, never a second terrain-admission decision. */
    static FoundationMode designedRealization(int surfaceY, int targetY) {
        if (targetY < surfaceY) return FoundationMode.CUT;
        return (long) targetY - surfaceY > FOUNDATION_MAX_FILL_DEPTH_BLOCKS
                ? FoundationMode.DECK : FoundationMode.FILL;
    }

    enum FoundationMode {
        FILL,
        DECK,
        CUT,
        PRESERVE
    }

    enum StairMode {
        DIRECT,
        SPLIT,
        ACCESS_DIRECT,
        ACCESS_SPLIT,
        ACCESS_CUT
    }

    enum AccessStatus {
        NO_ARTIFICIAL_PLATFORM,
        LEVEL_ACCESS,
        EXISTING_ROAD_STAIR,
        ACTIVE_STAIR,
        SHARED_PLATFORM_ACCESS,
        FAILED
    }

    enum PlatformAdjustmentStatus {
        RETAINED,
        MERGED,
        WITHDRAWN
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
                                 Map<Cell, Integer> platformTargets,
                                 Map<Cell, Integer> desiredTargets,
                                 Set<Cell> droppedCells,
                                 List<PlatformAdjustment> platformAdjustments) {
    }

    private record PlatformResolution(Map<Cell, Integer> targets,
                                      Set<Cell> droppedCells,
                                      List<PlatformAdjustment> adjustments) {
    }

    private record AccessBoundary(Cell occupiedSide,
                                  Cell low,
                                  int lowY,
                                  int highY,
                                  int highDx,
                                  int highDz) {
    }

    private record AccessPlan(List<StairDecision> stairs,
                              List<AccessPathDecision> paths,
                              List<AccessOutcome> outcomes) {
        AccessPlan {
            stairs = List.copyOf(stairs);
            paths = List.copyOf(paths);
            outcomes = List.copyOf(outcomes);
        }
    }

    private record Cell(int x, int z) {
    }
}
