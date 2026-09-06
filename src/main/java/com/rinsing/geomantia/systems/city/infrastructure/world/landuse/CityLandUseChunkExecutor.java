package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityWorldgenBlockObservationRegistry;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Applies one owner-chunk LandUse fragment as a small rollback-capable transaction. */
public final class CityLandUseChunkExecutor {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<Direction> HORIZONTAL_DIRECTIONS = List.of(
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);

    public ExecutionResult execute(CityLandUseChunkCompiler.ChunkFragment fragment,
                                   ExecutionWorld world,
                                   GenerationEligibility eligibility) {
        Objects.requireNonNull(fragment, "fragment");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(eligibility, "eligibility");
        if (eligibility != GenerationEligibility.FIRST_WORLDGEN_FEATURES
                && eligibility != GenerationEligibility.CONTROLLED_D7_BACKFILL) {
            return ExecutionResult.ineligible(fragment, "CITY_LAND_USE_OLD_CHUNK_NOT_BACKFILLED");
        }

        List<PreparedMutation> basePrepared = new ArrayList<>();
        List<PreparedMutation> cropPrepared = new ArrayList<>();
        List<PreparedMutation> boundaryPrepared = new ArrayList<>();
        int naturalSurfaceSkipped = 0;
        int occupiedBoundarySkipped = 0;
        int terraceRailingPrepared = 0;
        int terraceGreeneryPrepared = 0;
        int terraceEdgeOccupiedSkipped = 0;
        Map<ColumnKey, ColumnSample> terrain = new HashMap<>();
        CityLandUseMicroGrader.TerrainView terrainView = (x, z) ->
                terrain.computeIfAbsent(new ColumnKey(x, z), ignored -> requiredColumn(world, x, z));
        Map<ColumnKey, CityLandUseMicroGrader.FillDecision> fillByColumn = new HashMap<>();
        for (CityLandUseMicroGrader.FillDecision decision
                : CityLandUseMicroGrader.plan(fragment, terrainView)) {
            fillByColumn.put(new ColumnKey(decision.x(), decision.z()), decision);
        }
        CityLandUseMicroGrader.FoundationPlan foundationPlan =
                CityLandUseMicroGrader.planFoundationPlatform(fragment, terrainView);
        CityLandUseMicroGrader.AccessOutcome unresolvedAccess = foundationPlan.accessOutcomes().stream()
                .filter(outcome -> outcome.status() == CityLandUseMicroGrader.AccessStatus.FAILED)
                .findFirst().orElse(null);
        if (unresolvedAccess != null) {
            return ExecutionResult.failed(fragment, unresolvedAccess.reasonCode(), 0, 0,
                    0, 0, true, foundationPlan);
        }
        Map<ColumnKey, CityLandUseMicroGrader.FoundationDecision> foundationByColumn = new HashMap<>();
        for (CityLandUseMicroGrader.FoundationDecision decision : foundationPlan.decisions()) {
            foundationByColumn.put(new ColumnKey(decision.x(), decision.z()), decision);
        }
        Map<ColumnKey, CityLandUseMicroGrader.StairDecision> platformStairByColumn = new HashMap<>();
        for (CityLandUseMicroGrader.StairDecision stair : foundationPlan.stairs()) {
            platformStairByColumn.put(new ColumnKey(stair.x(), stair.z()), stair);
        }
        Map<ColumnKey, Integer> plannedSurfaceY = new HashMap<>();
        Set<ColumnKey> countedNaturalSkips = new HashSet<>();
        Set<ColumnKey> preparedFillColumns = new HashSet<>();
        Set<ColumnKey> preparedCutColumns = new HashSet<>();
        Set<ColumnKey> preservedFoundationColumns = new HashSet<>();
        for (CityLandUseChunkCompiler.SurfaceOperation operation : fragment.surfaceOperations()) {
            ColumnKey key = new ColumnKey(operation.x(), operation.z());
            ColumnSample column = terrainView.sample(operation.x(), operation.z());
            CityLandUseMicroGrader.FoundationDecision foundation = foundationByColumn.get(key);
            if (foundation != null && foundation.mode() == CityLandUseMicroGrader.FoundationMode.PRESERVE) {
                preservedFoundationColumns.add(key);
                if (countedNaturalSkips.add(key)) naturalSurfaceSkipped++;
                continue;
            }
            boolean foundationLiquidFill = foundation != null
                    && foundation.mode() == CityLandUseMicroGrader.FoundationMode.FILL
                    && ("minecraft:water".equals(column.surfaceBlockId())
                    || "minecraft:lava".equals(column.surfaceBlockId()));
            if (!column.naturalSurface() && !foundationLiquidFill) {
                if (countedNaturalSkips.add(key)) {
                    naturalSurfaceSkipped++;
                }
                continue;
            }
            CityLandUseMicroGrader.FillDecision fill = fillByColumn.get(key);
            CityLandUseMicroGrader.StairDecision platformStair = platformStairByColumn.get(key);
            int targetSurfaceY = platformStair != null ? platformStair.targetY()
                    : foundation != null ? foundation.targetY()
                    : fill == null ? column.surfaceY() : fill.targetY();
            boolean shouldFill = foundation != null
                    ? foundation.mode() == CityLandUseMicroGrader.FoundationMode.FILL : fill != null;
            if (shouldFill && preparedFillColumns.add(key)) {
                for (int y = column.surfaceY() + 1; y < targetSurfaceY; y++) {
                    PreparedMutation mutation = prepare(world, operation.areaId(), OperationPhase.MICRO_FILL,
                            operation.x(), y, operation.z(), fragment.microFillBlockId(), true);
                    if (mutation.failureReason() != null) {
                        return ExecutionResult.failed(fragment, mutation.failureReason(),
                                preparedCount(basePrepared, cropPrepared, boundaryPrepared), 0,
                                naturalSurfaceSkipped, occupiedBoundarySkipped, true);
                    }
                    basePrepared.add(mutation);
                }
            }
            if (foundation != null
                    && foundation.mode() == CityLandUseMicroGrader.FoundationMode.CUT
                    && preparedCutColumns.add(key)) {
                for (int y = targetSurfaceY + 1; y <= column.surfaceY(); y++) {
                    PreparedMutation mutation = prepare(world, operation.areaId(), OperationPhase.MICRO_CUT,
                            operation.x(), y, operation.z(), "minecraft:air", false);
                    if (mutation.failureReason() != null) {
                        return ExecutionResult.failed(fragment, mutation.failureReason(),
                                preparedCount(basePrepared, cropPrepared, boundaryPrepared), 0,
                                naturalSurfaceSkipped, occupiedBoundarySkipped, true);
                    }
                    basePrepared.add(mutation);
                }
            }
            OperationPhase phase = switch (operation.stage()) {
                case BASE -> OperationPhase.SURFACE;
                case CHANNEL_OVERLAY -> OperationPhase.SURFACE_OVERLAY;
                case CROP -> OperationPhase.CROP;
            };
            PreparedMutation mutation = prepare(world, operation.areaId(), phase,
                    operation.x(), targetSurfaceY + operation.surfaceOffset(), operation.z(), operation.blockId(),
                    operation.requireReplaceableTarget() || shouldFill && operation.surfaceOffset() == 0);
            if (mutation.failureReason() != null) {
                return ExecutionResult.failed(fragment, mutation.failureReason(),
                        preparedCount(basePrepared, cropPrepared, boundaryPrepared), 0,
                        naturalSurfaceSkipped, occupiedBoundarySkipped, true);
            }
            (operation.stage() == CityLandUseChunkCompiler.SurfaceStage.BASE
                    ? basePrepared : cropPrepared).add(mutation);
            plannedSurfaceY.put(key, targetSurfaceY);
        }

        for (CityLandUseMicroGrader.RetainingWallDecision wall : foundationPlan.retainingWalls()) {
            PreparedMutation mutation = prepare(world, wall.areaId(), OperationPhase.RETAINING_WALL,
                    wall.x(), wall.y(), wall.z(), wall.blockId(), false);
            if (mutation.failureReason() != null) {
                return ExecutionResult.failed(fragment, mutation.failureReason(),
                        preparedCount(basePrepared, cropPrepared, boundaryPrepared), 0,
                        naturalSurfaceSkipped, occupiedBoundarySkipped, true);
            }
            basePrepared.add(mutation);
        }

        Set<ColumnKey> explicitBoundaryColumns = fragment.boundaryOperations().stream()
                .map(operation -> new ColumnKey(operation.x(), operation.z()))
                .collect(java.util.stream.Collectors.toSet());
        for (CityLandUseMicroGrader.TerraceEdgeDecision edge : foundationPlan.terraceEdges()) {
            if (explicitBoundaryColumns.contains(new ColumnKey(edge.x(), edge.z()))) continue;
            boolean clearedByBaseMutation = baseMutationClearsTarget(basePrepared,
                    edge.x(), edge.y(), edge.z());
            PreparedMutation mutation = prepare(world, edge.areaId(), OperationPhase.BOUNDARY,
                    edge.x(), edge.y(), edge.z(), edge.blockId(), !clearedByBaseMutation);
            if (mutation.failureReason() != null) {
                if ("CITY_LAND_USE_BOUNDARY_TARGET_OCCUPIED".equals(mutation.failureReason())) {
                    occupiedBoundarySkipped++;
                    terraceEdgeOccupiedSkipped++;
                    continue;
                }
                return ExecutionResult.failed(fragment, mutation.failureReason(),
                        preparedCount(basePrepared, cropPrepared, boundaryPrepared), 0,
                        naturalSurfaceSkipped, occupiedBoundarySkipped, true);
            }
            boundaryPrepared.add(mutation);
            if (edge.kind() == CityLandUseMicroGrader.TerraceEdgeKind.RAILING) {
                terraceRailingPrepared++;
            } else {
                terraceGreeneryPrepared++;
            }
        }

        for (CityLandUseChunkCompiler.BoundaryOperation operation : fragment.boundaryOperations()) {
            ColumnKey key = new ColumnKey(operation.x(), operation.z());
            if (preservedFoundationColumns.contains(key)) continue;
            ColumnSample column = terrainView.sample(operation.x(), operation.z());
            int surfaceY = plannedSurfaceY.getOrDefault(key, column.surfaceY());
            PreparedMutation mutation = prepare(world, operation.areaId(), OperationPhase.BOUNDARY,
                    operation.x(), surfaceY + 1, operation.z(), operation.blockId(), true);
            if (mutation.failureReason() != null) {
                if ("CITY_LAND_USE_BOUNDARY_TARGET_OCCUPIED".equals(mutation.failureReason())) {
                    occupiedBoundarySkipped++;
                    continue;
                }
                return ExecutionResult.failed(fragment, mutation.failureReason(),
                        preparedCount(basePrepared, cropPrepared, boundaryPrepared), 0,
                        naturalSurfaceSkipped, occupiedBoundarySkipped, true);
            }
            boundaryPrepared.add(mutation);
        }

        Set<CityLandUseChunkCompiler.FeatureOperation> bridgePierRails =
                bridgePierRails(fragment.featureOperations());
        Set<ColumnKey> materializedPlatformStairs = new HashSet<>();
        for (CityLandUseChunkCompiler.FeatureOperation operation : fragment.featureOperations()) {
            ColumnKey key = new ColumnKey(operation.x(), operation.z());
            ColumnSample column = terrainView.sample(operation.x(), operation.z());
            CityLandUseMicroGrader.FoundationDecision foundation = foundationByColumn.get(key);
            CityLandUseMicroGrader.FillDecision fill = fillByColumn.get(key);
            CityLandUseMicroGrader.StairDecision platformStair = platformStairByColumn.get(key);
            int surfaceY = plannedSurfaceY.getOrDefault(key, platformStair != null ? platformStair.targetY()
                    : foundation != null ? foundation.targetY()
                    : fill == null ? column.surfaceY() : fill.targetY());
            if (bridgePierRails.contains(operation)) {
                for (int y = surfaceY - 1, depth = 0; depth < 64; y--, depth++) {
                    if (!world.inspect(operation.x(), y, operation.z()).replaceable()) break;
                    PreparedMutation pier = prepare(world, operation.sourceId(), OperationPhase.FEATURE,
                            operation.x(), y, operation.z(), "minecraft:stone_bricks", true);
                    if (pier.failureReason() != null) {
                        return ExecutionResult.failed(fragment, pier.failureReason(),
                                preparedCount(basePrepared, cropPrepared, boundaryPrepared), 0,
                                naturalSurfaceSkipped, occupiedBoundarySkipped, true);
                    }
                    basePrepared.add(pier);
                }
            }
            CityLandUseChunkCompiler.FeatureOperation effectiveOperation = platformStair != null
                    && (operation.kind() == CityLandUseSurfacePrintPlan.FeatureKind.ROAD_SLAB
                    || operation.kind() == CityLandUseSurfacePrintPlan.FeatureKind.ROAD_STAIR)
                    ? platformStairOperation(platformStair) : operation;
            if (effectiveOperation != operation) materializedPlatformStairs.add(key);
            int featureY = surfaceY + effectiveOperation.surfaceOffset();
            PreparedMutation mutation = prepareFeature(world, effectiveOperation, featureY,
                    baseMutationClearsTarget(basePrepared,
                            effectiveOperation.x(), featureY, effectiveOperation.z()));
            if (mutation.failureReason() != null) {
                return ExecutionResult.failed(fragment, mutation.failureReason(),
                        preparedCount(basePrepared, cropPrepared, boundaryPrepared), 0,
                        naturalSurfaceSkipped, occupiedBoundarySkipped, true);
            }
            (operation.surfaceOffset() == 0 ? basePrepared : cropPrepared).add(mutation);
            if (operation.surfaceOffset() == 0) plannedSurfaceY.put(key, surfaceY);
        }

        for (CityRoadsideLightingPlanner.Lamp lamp : CityRoadsideLightingPlanner.plan(fragment)) {
            List<PreparedMutation> lampPrepared = new ArrayList<>();
            boolean obstructed = false;
            for (CityRoadsideLightingPlanner.BlockDecision block : lamp.blocks()) {
                ColumnKey datumKey = new ColumnKey(block.datumX(), block.datumZ());
                ColumnSample datumColumn = terrainView.sample(block.datumX(), block.datumZ());
                CityLandUseMicroGrader.FoundationDecision foundation = foundationByColumn.get(datumKey);
                CityLandUseMicroGrader.FillDecision fill = fillByColumn.get(datumKey);
                CityLandUseMicroGrader.StairDecision stair = platformStairByColumn.get(datumKey);
                int roadY = plannedSurfaceY.getOrDefault(datumKey, stair != null ? stair.targetY()
                        : foundation != null ? foundation.targetY()
                        : fill == null ? datumColumn.surfaceY() : fill.targetY());
                CityLandUseChunkCompiler.FeatureOperation operation =
                        new CityLandUseChunkCompiler.FeatureOperation(block.lampId(), block.x(), block.z(),
                                block.blockId(), block.verticalOffset(),
                                CityLandUseSurfacePrintPlan.FeatureKind.ROAD_LAMP,
                                CityLandUseSurfacePrintPlan.HorizontalFacing.NONE);
                PreparedMutation mutation = prepareFeature(world, operation,
                        roadY + block.verticalOffset(), false);
                if (mutation.failureReason() != null) {
                    obstructed = true;
                    break;
                }
                lampPrepared.add(mutation);
            }
            if (!obstructed) cropPrepared.addAll(lampPrepared);
        }

        Set<ColumnKey> platformStairColumns = foundationPlan.stairs().stream()
                .map(stair -> new ColumnKey(stair.x(), stair.z()))
                .collect(java.util.stream.Collectors.toSet());
        for (CityLandUseMicroGrader.AccessPathDecision path : foundationPlan.accessPaths()) {
            ColumnKey key = new ColumnKey(path.x(), path.z());
            if (platformStairColumns.contains(key)) continue;
            ColumnSample column = terrainView.sample(path.x(), path.z());
            CityLandUseMicroGrader.FoundationDecision foundation = foundationByColumn.get(key);
            int surfaceY = plannedSurfaceY.getOrDefault(key,
                    foundation == null ? column.surfaceY() : foundation.targetY());
            CityLandUseChunkCompiler.FeatureOperation operation =
                    new CityLandUseChunkCompiler.FeatureOperation("access::" + path.demandId(),
                            path.x(), path.z(), path.blockId(), 0,
                            CityLandUseSurfacePrintPlan.FeatureKind.ROAD_SLAB,
                            CityLandUseSurfacePrintPlan.HorizontalFacing.NONE);
            PreparedMutation mutation = prepareFeature(world, operation, surfaceY,
                    baseMutationClearsTarget(basePrepared, path.x(), surfaceY, path.z()));
            if (mutation.failureReason() != null) {
                return ExecutionResult.failed(fragment, mutation.failureReason(),
                        preparedCount(basePrepared, cropPrepared, boundaryPrepared), 0,
                        naturalSurfaceSkipped, occupiedBoundarySkipped, true);
            }
            basePrepared.add(mutation);
            plannedSurfaceY.put(key, surfaceY);
        }

        for (CityLandUseMicroGrader.StairDecision stair : foundationPlan.stairs()) {
            ColumnKey key = new ColumnKey(stair.x(), stair.z());
            if (materializedPlatformStairs.contains(key)) continue;
            PreparedMutation mutation = prepareFeature(world, platformStairOperation(stair), stair.targetY(),
                    baseMutationClearsTarget(basePrepared, stair.x(), stair.targetY(), stair.z()));
            if (mutation.failureReason() != null) {
                return ExecutionResult.failed(fragment, mutation.failureReason(),
                        preparedCount(basePrepared, cropPrepared, boundaryPrepared), 0,
                        naturalSurfaceSkipped, occupiedBoundarySkipped, true);
            }
            basePrepared.add(mutation);
            plannedSurfaceY.put(key, stair.targetY());
        }

        int preparedBlockCount = preparedCount(basePrepared, cropPrepared, boundaryPrepared);

        List<PreparedMutation> baseApplied = new ArrayList<>();
        if (!apply(world, basePrepared, baseApplied)) {
            boolean rolledBack = rollback(world, baseApplied);
            return ExecutionResult.failed(fragment, "CITY_LAND_USE_BLOCK_WRITE_FAILED",
                    preparedBlockCount, baseApplied.size(), naturalSurfaceSkipped,
                    occupiedBoundarySkipped, rolledBack);
        }

        List<PreparedMutation> cropApplied = new ArrayList<>();
        if (!apply(world, cropPrepared, cropApplied)) {
            boolean rolledBack = rollback(world, cropApplied);
            rolledBack &= rollback(world, baseApplied);
            return ExecutionResult.failed(fragment, "CITY_LAND_USE_BLOCK_WRITE_FAILED",
                    preparedBlockCount, baseApplied.size() + cropApplied.size(), naturalSurfaceSkipped,
                    occupiedBoundarySkipped, rolledBack);
        }

        List<PreparedMutation> boundaryApplied = new ArrayList<>();
        List<BlockPosition> connectionPositions = new ArrayList<>();
        for (List<PreparedMutation> batch : List.of(basePrepared, cropPrepared, boundaryPrepared)) {
            for (PreparedMutation mutation : batch) {
                connectionPositions.add(new BlockPosition(mutation.x(), mutation.y(), mutation.z()));
            }
        }
        BoundaryApplyResult boundaryResult = applyBoundary(world, boundaryPrepared, boundaryApplied,
                connectionPositions);
        if (!boundaryResult.success()) {
            boolean rolledBack = rollback(world, boundaryApplied);
            rolledBack &= rollback(world, cropApplied);
            rolledBack &= rollback(world, baseApplied);
            rolledBack &= boundaryResult.rollbackComplete();
            return ExecutionResult.failed(fragment, boundaryResult.reasonCode(),
                    preparedBlockCount,
                    baseApplied.size() + cropApplied.size() + boundaryApplied.size(),
                    naturalSurfaceSkipped, occupiedBoundarySkipped, rolledBack);
        }
        return ExecutionResult.applied(fragment, preparedBlockCount,
                new PhaseCounts(basePrepared.size(), baseApplied.size(), cropPrepared.size(),
                        cropApplied.size(), boundaryPrepared.size(), boundaryApplied.size()),
                naturalSurfaceSkipped, occupiedBoundarySkipped, foundationPlan,
                terraceRailingPrepared, terraceGreeneryPrepared, terraceEdgeOccupiedSkipped);
    }

    /** Selects paired rail columns at a stable seven-block cadence as in-water bridge piers. */
    private static Set<CityLandUseChunkCompiler.FeatureOperation> bridgePierRails(
            List<CityLandUseChunkCompiler.FeatureOperation> operations) {
        Map<String, List<CityLandUseChunkCompiler.FeatureOperation>> railsByBridge = new LinkedHashMap<>();
        for (CityLandUseChunkCompiler.FeatureOperation operation : operations) {
            if (operation.kind() == CityLandUseSurfacePrintPlan.FeatureKind.BRIDGE_RAIL) {
                railsByBridge.computeIfAbsent(operation.sourceId(), ignored -> new ArrayList<>()).add(operation);
            }
        }
        Set<CityLandUseChunkCompiler.FeatureOperation> selected = new HashSet<>();
        for (Map.Entry<String, List<CityLandUseChunkCompiler.FeatureOperation>> entry : railsByBridge.entrySet()) {
            List<CityLandUseChunkCompiler.FeatureOperation> rails = entry.getValue();
            int minX = rails.stream().mapToInt(CityLandUseChunkCompiler.FeatureOperation::x).min().orElse(0);
            int maxX = rails.stream().mapToInt(CityLandUseChunkCompiler.FeatureOperation::x).max().orElse(0);
            int minZ = rails.stream().mapToInt(CityLandUseChunkCompiler.FeatureOperation::z).min().orElse(0);
            int maxZ = rails.stream().mapToInt(CityLandUseChunkCompiler.FeatureOperation::z).max().orElse(0);
            boolean horizontal = maxX - minX >= maxZ - minZ;
            int phase = Math.floorMod(entry.getKey().hashCode(), 7);
            int selectedBefore = selected.size();
            for (CityLandUseChunkCompiler.FeatureOperation rail : rails) {
                int coordinate = horizontal ? rail.x() : rail.z();
                if (Math.floorMod(coordinate, 7) == phase) selected.add(rail);
            }
            if (selected.size() == selectedBefore) {
                int middle = horizontal ? (minX + maxX) / 2 : (minZ + maxZ) / 2;
                rails.stream().filter(rail -> (horizontal ? rail.x() : rail.z()) == middle)
                        .forEach(selected::add);
            }
        }
        return Set.copyOf(selected);
    }

    private static boolean apply(ExecutionWorld world,
                                 List<PreparedMutation> prepared,
                                 List<PreparedMutation> applied) {
        for (PreparedMutation mutation : prepared) {
            Object writeSnapshot;
            try {
                writeSnapshot = Objects.requireNonNull(world.beginWrite(
                        mutation.x(), mutation.y(), mutation.z(), mutation.snapshot()));
            } catch (RuntimeException ex) {
                LOGGER.warn("City LandUse begin write failed: mutation={}", mutation, ex);
                return false;
            }
            // A failed writer may already have mutated the target before reporting failure.
            applied.add(mutation.withSnapshot(writeSnapshot));
            boolean written;
            try {
                written = mutation.featureKind() == null
                        ? world.setBlock(mutation.x(), mutation.y(), mutation.z(), mutation.blockId())
                        : world.setFeatureBlock(mutation.x(), mutation.y(), mutation.z(), mutation.blockId(),
                        mutation.featureKind(), mutation.facing());
            } catch (RuntimeException ex) {
                LOGGER.warn("City LandUse write threw: mutation={}", mutation, ex);
                written = false;
            }
            try {
                world.endWrite(writeSnapshot);
            } catch (RuntimeException ex) {
                LOGGER.warn("City LandUse end write failed: mutation={}", mutation, ex);
                written = false;
            }
            if (!written) {
                LOGGER.warn("City LandUse write rejected: phase={}, area={}, pos={},{},{}, block={}, feature={}",
                        mutation.phase(), mutation.areaId(), mutation.x(), mutation.y(), mutation.z(),
                        mutation.blockId(), mutation.featureKind());
                return false;
            }
        }
        return true;
    }

    private static BoundaryApplyResult applyBoundary(ExecutionWorld world,
                                                     List<PreparedMutation> prepared,
                                                     List<PreparedMutation> applied,
                                                     List<BlockPosition> connectionPositions) {
        for (PreparedMutation mutation : prepared) {
            Object writeSnapshot;
            try {
                writeSnapshot = Objects.requireNonNull(world.beginWrite(
                        mutation.x(), mutation.y(), mutation.z(), mutation.snapshot()));
            } catch (RuntimeException ex) {
                return BoundaryApplyResult.writeFailed();
            }
            applied.add(mutation.withSnapshot(writeSnapshot));
            boolean written;
            try {
                written = world.setBoundaryBlockRaw(
                        mutation.x(), mutation.y(), mutation.z(), mutation.blockId());
            } catch (RuntimeException ex) {
                written = false;
            }
            try {
                world.endWrite(writeSnapshot);
            } catch (RuntimeException ex) {
                written = false;
            }
            if (!written) return BoundaryApplyResult.writeFailed();
        }
        try {
            // Lamp poles, terrace rails and their neighbours need the same batch reconciliation as boundaries.
            BoundaryFinalizeResult result = world.finalizeBoundaryConnections(connectionPositions);
            return result.success()
                    ? BoundaryApplyResult.applied()
                    : BoundaryApplyResult.finalizeFailed(result.rollbackComplete());
        } catch (RuntimeException ex) {
            return BoundaryApplyResult.finalizeFailed(false);
        }
    }

    private static int preparedCount(List<PreparedMutation> base,
                                     List<PreparedMutation> crop,
                                     List<PreparedMutation> boundary) {
        return base.size() + crop.size() + boundary.size();
    }

    private static ColumnSample requiredColumn(ExecutionWorld world, int x, int z) {
        ColumnSample sample = world.sampleColumn(x, z);
        if (sample == null) {
            throw new IllegalArgumentException("CITY_LAND_USE_TERRAIN_SAMPLE_MISSING: " + x + "," + z);
        }
        return sample;
    }

    private static PreparedMutation prepare(ExecutionWorld world,
                                            String areaId,
                                            OperationPhase phase,
                                            int x,
                                            int y,
                                            int z,
                                            String blockId,
                                            boolean requireReplaceable) {
        if (!world.isKnownBlock(blockId)) {
            return PreparedMutation.failed(areaId, phase, x, y, z, blockId,
                    "CITY_LAND_USE_BLOCK_ID_UNKNOWN");
        }
        if (!world.ensureCanWrite(x, y, z)) {
            return PreparedMutation.failed(areaId, phase, x, y, z, blockId,
                    "CITY_LAND_USE_TARGET_NOT_WRITABLE");
        }
        TargetState target = world.inspect(x, y, z);
        if (target == null || target.snapshot() == null) {
            return PreparedMutation.failed(areaId, phase, x, y, z, blockId,
                    "CITY_LAND_USE_TARGET_STATE_UNAVAILABLE");
        }
        if (requireReplaceable && !target.replaceable()) {
            return PreparedMutation.failed(areaId, phase, x, y, z, blockId,
                    switch (phase) {
                        case BOUNDARY -> "CITY_LAND_USE_BOUNDARY_TARGET_OCCUPIED";
                        case SURFACE_OVERLAY -> "CITY_LAND_USE_SURFACE_PRINT_TARGET_OCCUPIED";
                        case MICRO_FILL -> "CITY_LAND_USE_MICRO_FILL_TARGET_OCCUPIED";
                        case SURFACE -> "CITY_LAND_USE_SURFACE_TARGET_OCCUPIED";
                        case CROP -> "CITY_LAND_USE_CROP_TARGET_OCCUPIED";
                        case FEATURE -> "CITY_LAND_USE_FEATURE_TARGET_OCCUPIED";
                        case MICRO_CUT, RETAINING_WALL -> "CITY_LAND_USE_TARGET_OCCUPIED";
                    });
        }
        return PreparedMutation.ready(areaId, phase, x, y, z, blockId, target.snapshot());
    }

    private static PreparedMutation prepareFeature(
            ExecutionWorld world,
            CityLandUseChunkCompiler.FeatureOperation operation,
            int y,
            boolean clearedByBaseMutation) {
        PreparedMutation prepared = prepare(world, operation.sourceId(), OperationPhase.FEATURE,
                operation.x(), y, operation.z(), operation.blockId(),
                operation.surfaceOffset() > 0 && !clearedByBaseMutation);
        return prepared.withFeature(operation.kind(), operation.facing());
    }

    /**
     * Feature preflight runs before any mutation is applied. Treat a currently occupied target as available
     * only when the final earlier base mutation at that exact position is this transaction's explicit cut-to-air.
     */
    private static boolean baseMutationClearsTarget(List<PreparedMutation> basePrepared,
                                                    int x,
                                                    int y,
                                                    int z) {
        PreparedMutation last = null;
        for (PreparedMutation mutation : basePrepared) {
            if (mutation.x() == x && mutation.y() == y && mutation.z() == z) {
                last = mutation;
            }
        }
        return last != null
                && last.phase() == OperationPhase.MICRO_CUT
                && "minecraft:air".equals(last.blockId());
    }

    private static CityLandUseChunkCompiler.FeatureOperation platformStairOperation(
            CityLandUseMicroGrader.StairDecision stair) {
        return new CityLandUseChunkCompiler.FeatureOperation(stair.sourceId(), stair.x(), stair.z(),
                stair.blockId(), 0, CityLandUseSurfacePrintPlan.FeatureKind.ROAD_STAIR, stair.facing());
    }

    private static boolean rollback(ExecutionWorld world, List<PreparedMutation> applied) {
        boolean complete = true;
        List<PreparedMutation> reverse = new ArrayList<>(applied);
        Collections.reverse(reverse);
        for (PreparedMutation mutation : reverse) {
            try {
                complete &= world.restoreBlock(mutation.x(), mutation.y(), mutation.z(), mutation.snapshot());
            } catch (RuntimeException ex) {
                complete = false;
            }
        }
        return complete;
    }

    public enum GenerationEligibility {
        FIRST_WORLDGEN_FEATURES,
        CONTROLLED_D7_BACKFILL,
        ALREADY_GENERATED
    }

    public enum OperationPhase {
        MICRO_FILL,
        MICRO_CUT,
        RETAINING_WALL,
        SURFACE,
        SURFACE_OVERLAY,
        CROP,
        BOUNDARY,
        FEATURE
    }

    public interface ExecutionWorld {
        ColumnSample sampleColumn(int worldX, int worldZ);

        boolean isKnownBlock(String blockId);

        boolean ensureCanWrite(int worldX, int y, int worldZ);

        TargetState inspect(int worldX, int y, int worldZ);

        default Object beginWrite(int worldX, int y, int worldZ, Object snapshot) {
            return snapshot;
        }

        boolean setBlock(int worldX, int y, int worldZ, String blockId);

        default boolean setFeatureBlock(int worldX, int y, int worldZ, String blockId,
                                        CityLandUseSurfacePrintPlan.FeatureKind kind,
                                        CityLandUseSurfacePrintPlan.HorizontalFacing facing) {
            return setBlock(worldX, y, worldZ, blockId);
        }

        default boolean setBoundaryBlockRaw(int worldX, int y, int worldZ, String blockId) {
            return setBlock(worldX, y, worldZ, blockId);
        }

        default BoundaryFinalizeResult finalizeBoundaryConnections(List<BlockPosition> positions) {
            return BoundaryFinalizeResult.succeeded();
        }

        default void endWrite(Object snapshot) {
        }

        boolean restoreBlock(int worldX, int y, int worldZ, Object snapshot);
    }

    public record ColumnSample(int surfaceY, String surfaceBlockId, boolean naturalSurface) {
        public ColumnSample {
            Objects.requireNonNull(surfaceBlockId, "surfaceBlockId");
        }
    }

    public record TargetState(Object snapshot, boolean replaceable) {
        public TargetState {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    public record BlockPosition(int x, int y, int z) {
        private BlockPos toBlockPos() {
            return new BlockPos(x, y, z);
        }
    }

    public record BoundaryFinalizeResult(boolean success, boolean rollbackComplete) {
        private static BoundaryFinalizeResult succeeded() {
            return new BoundaryFinalizeResult(true, true);
        }

        private static BoundaryFinalizeResult failed(boolean rollbackComplete) {
            return new BoundaryFinalizeResult(false, rollbackComplete);
        }
    }

    public record ExecutionResult(Status status,
                                  String reasonCode,
                                  String cityId,
                                  String planHash,
                                  String paletteHash,
                                  int chunkX,
                                  int chunkZ,
                                  int preparedOperationCount,
                                  int appliedOperationCount,
                                  int naturalSurfaceSkippedCount,
                                  int occupiedBoundarySkippedCount,
                                  boolean rollbackComplete,
                                  FoundationDiagnostics foundationDiagnostics,
                                  PhaseCounts phaseCounts) {
        private static ExecutionResult applied(CityLandUseChunkCompiler.ChunkFragment fragment,
                                               int applied,
                                               PhaseCounts phaseCounts,
                                               int naturalSkipped,
                                               int boundarySkipped,
                                               CityLandUseMicroGrader.FoundationPlan foundationPlan,
                                               int terraceRailingPrepared,
                                               int terraceGreeneryPrepared,
                                               int terraceEdgeOccupiedSkipped) {
            return new ExecutionResult(Status.APPLIED, "CITY_LAND_USE_OWNER_APPLIED",
                    fragment.cityId(), fragment.planHash(), fragment.paletteHash(),
                    fragment.chunkX(), fragment.chunkZ(), applied, applied,
                    naturalSkipped, boundarySkipped, true,
                    FoundationDiagnostics.from(fragment, foundationPlan,
                            terraceRailingPrepared, terraceGreeneryPrepared,
                            terraceEdgeOccupiedSkipped), phaseCounts);
        }

        private static ExecutionResult failed(CityLandUseChunkCompiler.ChunkFragment fragment,
                                              String reason,
                                              int prepared,
                                              int applied,
                                              int naturalSkipped,
                                              int boundarySkipped,
                                              boolean rollbackComplete) {
            return new ExecutionResult(Status.FAILED, reason, fragment.cityId(), fragment.planHash(),
                    fragment.paletteHash(), fragment.chunkX(), fragment.chunkZ(), prepared, applied,
                    naturalSkipped, boundarySkipped, rollbackComplete, FoundationDiagnostics.empty(), null);
        }

        private static ExecutionResult failed(CityLandUseChunkCompiler.ChunkFragment fragment,
                                              String reason,
                                              int prepared,
                                              int applied,
                                              int naturalSkipped,
                                              int boundarySkipped,
                                              boolean rollbackComplete,
                                              CityLandUseMicroGrader.FoundationPlan foundationPlan) {
            return new ExecutionResult(Status.FAILED, reason, fragment.cityId(), fragment.planHash(),
                    fragment.paletteHash(), fragment.chunkX(), fragment.chunkZ(), prepared, applied,
                    naturalSkipped, boundarySkipped, rollbackComplete,
                    FoundationDiagnostics.from(fragment, foundationPlan), null);
        }

        private static ExecutionResult ineligible(CityLandUseChunkCompiler.ChunkFragment fragment,
                                                  String reason) {
            return new ExecutionResult(Status.INELIGIBLE, reason, fragment.cityId(), fragment.planHash(),
                    fragment.paletteHash(), fragment.chunkX(), fragment.chunkZ(), 0, 0, 0, 0, true,
                    FoundationDiagnostics.empty(), null);
        }
    }

    /** Actual executor batches, never reconstructed from the unfiltered plan after world mutation. */
    public record PhaseCounts(int preparedBase, int appliedBase, int preparedCrop, int appliedCrop,
                              int preparedBoundary, int appliedBoundary) { }

    public record FoundationDiagnostics(
            int purposeAnchorCount,
            int accessDemandCount,
            int accessPathCellCount,
            int stairCellCount,
            int terraceEdgePlannedCount,
            int terraceEdgePreparedCount,
            int terraceRailingPreparedCount,
            int terraceGreeneryPreparedCount,
            int terraceEdgeOccupiedSkippedCount,
            List<CityLandUseMicroGrader.PlatformAdjustment> platformAdjustments,
            List<CityLandUseMicroGrader.AccessOutcome> accessOutcomes) {
        public FoundationDiagnostics {
            platformAdjustments = List.copyOf(platformAdjustments);
            accessOutcomes = List.copyOf(accessOutcomes);
        }

        private static FoundationDiagnostics from(
                CityLandUseChunkCompiler.ChunkFragment fragment,
                CityLandUseMicroGrader.FoundationPlan plan) {
            return from(fragment, plan, 0, 0, 0);
        }

        private static FoundationDiagnostics from(
                CityLandUseChunkCompiler.ChunkFragment fragment,
                CityLandUseMicroGrader.FoundationPlan plan,
                int terraceRailingPrepared,
                int terraceGreeneryPrepared,
                int terraceEdgeOccupiedSkipped) {
            return new FoundationDiagnostics(fragment.platformPurposeAnchors().size(),
                    fragment.platformAccessDemands().size(), plan.accessPaths().size(),
                    plan.stairs().size(), plan.terraceEdges().size(),
                    terraceRailingPrepared + terraceGreeneryPrepared,
                    terraceRailingPrepared, terraceGreeneryPrepared,
                    terraceEdgeOccupiedSkipped, plan.platformAdjustments(), plan.accessOutcomes());
        }

        static FoundationDiagnostics empty() {
            return new FoundationDiagnostics(0, 0, 0, 0,
                    0, 0, 0, 0, 0, List.of(), List.of());
        }
    }

    public enum Status {
        APPLIED,
        FAILED,
        INELIGIBLE
    }

    private record PreparedMutation(String areaId,
                                    OperationPhase phase,
                                    int x,
                                    int y,
                                    int z,
                                    String blockId,
                                    Object snapshot,
                                    String failureReason,
                                    CityLandUseSurfacePrintPlan.FeatureKind featureKind,
                                    CityLandUseSurfacePrintPlan.HorizontalFacing facing) {
        private static PreparedMutation ready(String areaId, OperationPhase phase,
                                              int x, int y, int z, String blockId, Object snapshot) {
            return new PreparedMutation(areaId, phase, x, y, z, blockId, snapshot, null, null,
                    CityLandUseSurfacePrintPlan.HorizontalFacing.NONE);
        }

        private static PreparedMutation failed(String areaId, OperationPhase phase,
                                               int x, int y, int z, String blockId, String reason) {
            return new PreparedMutation(areaId, phase, x, y, z, blockId, null, reason, null,
                    CityLandUseSurfacePrintPlan.HorizontalFacing.NONE);
        }

        private PreparedMutation withSnapshot(Object writeSnapshot) {
            return new PreparedMutation(areaId, phase, x, y, z, blockId, writeSnapshot, failureReason,
                    featureKind, facing);
        }

        private PreparedMutation withFeature(CityLandUseSurfacePrintPlan.FeatureKind kind,
                                             CityLandUseSurfacePrintPlan.HorizontalFacing direction) {
            return new PreparedMutation(areaId, phase, x, y, z, blockId, snapshot, failureReason,
                    kind, direction);
        }
    }

    private record ColumnKey(int x, int z) {
    }

    private record BoundaryApplyResult(boolean success, String reasonCode, boolean rollbackComplete) {
        private static BoundaryApplyResult applied() {
            return new BoundaryApplyResult(true, "CITY_LAND_USE_OWNER_APPLIED", true);
        }

        private static BoundaryApplyResult writeFailed() {
            return new BoundaryApplyResult(false, "CITY_LAND_USE_BLOCK_WRITE_FAILED", true);
        }

        private static BoundaryApplyResult finalizeFailed(boolean rollbackComplete) {
            return new BoundaryApplyResult(false, "CITY_LAND_USE_BOUNDARY_FINALIZE_FAILED", rollbackComplete);
        }
    }

    static <S> boolean writeExactBlockState(BlockStateWriteAccess<S> world,
                                            BlockPos pos,
                                            S requested,
                                            int flags) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(requested, "requested");
        if (!world.ensureCanWrite(pos)) return false;
        // Level.setBlock reports false for an unchanged state, including an already-restored snapshot.
        if (requested.equals(world.getBlockState(pos))) return true;
        boolean changed = world.setBlock(pos, requested, flags);
        S actual = world.getBlockState(pos);
        boolean written = requested.equals(actual);
        if (!written) LOGGER.warn("City LandUse exact write mismatch: pos={}, requested={}, actual={}, changed={}",
                pos, requested, actual, changed);
        return written;
    }

    static BlockState featureBlockState(BlockState requested,
                                         CityLandUseSurfacePrintPlan.FeatureKind kind,
                                         CityLandUseSurfacePrintPlan.HorizontalFacing facing) {
        if (kind == CityLandUseSurfacePrintPlan.FeatureKind.ROAD_SLAB
                && requested.hasProperty(BlockStateProperties.SLAB_TYPE)) {
            requested = requested.setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE);
        }
        if (kind == CityLandUseSurfacePrintPlan.FeatureKind.BRIDGE_DECK
                && requested.hasProperty(BlockStateProperties.SLAB_TYPE)) {
            requested = requested.setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM);
        }
        if (kind == CityLandUseSurfacePrintPlan.FeatureKind.ROAD_STAIR) {
            if (requested.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                requested = requested.setValue(BlockStateProperties.HORIZONTAL_FACING,
                        switch (facing) {
                            case NORTH -> Direction.NORTH;
                            case EAST -> Direction.EAST;
                            case SOUTH -> Direction.SOUTH;
                            case WEST -> Direction.WEST;
                            case NONE -> throw new IllegalArgumentException(
                                    "CITY_LAND_USE_ROAD_STAIR_FACING_REQUIRED");
                        });
            }
            if (requested.hasProperty(BlockStateProperties.HALF)) {
                requested = requested.setValue(BlockStateProperties.HALF, Half.BOTTOM);
            }
            if (requested.hasProperty(BlockStateProperties.STAIRS_SHAPE)) {
                requested = requested.setValue(BlockStateProperties.STAIRS_SHAPE, StairsShape.STRAIGHT);
            }
        }
        if (kind == CityLandUseSurfacePrintPlan.FeatureKind.ROAD_LAMP
                && requested.hasProperty(BlockStateProperties.HANGING)) {
            requested = requested.setValue(BlockStateProperties.HANGING, true);
        }
        if (requested.hasProperty(BlockStateProperties.WATERLOGGED)) {
            requested = requested.setValue(BlockStateProperties.WATERLOGGED, false);
        }
        return requested;
    }

    static BlockState boundaryBlockState(BlockState requested) {
        if (requested.hasProperty(LeavesBlock.PERSISTENT)) {
            requested = requested.setValue(LeavesBlock.PERSISTENT, true);
        }
        if (requested.hasProperty(BlockStateProperties.WATERLOGGED)) {
            requested = requested.setValue(BlockStateProperties.WATERLOGGED, false);
        }
        return requested;
    }

    // WorldGenRegion ignores neighbor-update flags, so all boundary identities must exist before shape finalization.
    static <S> BoundaryFinalizeResult finalizeHorizontalConnections(BlockStateWriteAccess<S> world,
                                                                     List<BlockPos> boundaryPositions) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(boundaryPositions, "boundaryPositions");
        Set<BlockPos> candidates = new HashSet<>();
        for (BlockPos position : boundaryPositions) {
            candidates.add(position.immutable());
            for (Direction direction : HORIZONTAL_DIRECTIONS) {
                candidates.add(position.relative(direction).immutable());
            }
        }
        List<BlockPos> stableCandidates = candidates.stream()
                .sorted((left, right) -> {
                    int z = Integer.compare(left.getZ(), right.getZ());
                    if (z != 0) return z;
                    int x = Integer.compare(left.getX(), right.getX());
                    if (x != 0) return x;
                    return Integer.compare(left.getY(), right.getY());
                })
                .toList();
        Map<BlockPos, S> snapshots = new LinkedHashMap<>();
        stableCandidates.forEach(pos -> snapshots.put(pos, world.getBlockState(pos)));

        Map<BlockPos, S> desired = new LinkedHashMap<>();
        for (BlockPos pos : stableCandidates) {
            S current = world.getBlockState(pos);
            if (!world.isHorizontalConnectionBlock(current)) continue;
            S reconciled = world.updateFromNeighbourShapes(current, pos);
            desired.put(pos, reconciled);
            if (!current.equals(reconciled) && !world.ensureCanWrite(pos)) {
                return BoundaryFinalizeResult.failed(true);
            }
        }
        for (Map.Entry<BlockPos, S> entry : desired.entrySet()) {
            if (!snapshots.get(entry.getKey()).equals(entry.getValue())
                    && !writeExactBlockState(world, entry.getKey(), entry.getValue(),
                    Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE)) {
                return BoundaryFinalizeResult.failed(restoreSnapshots(world, snapshots));
            }
        }
        for (BlockPos pos : stableCandidates) {
            S current = world.getBlockState(pos);
            if (world.isHorizontalConnectionBlock(current)
                    && !current.equals(world.updateFromNeighbourShapes(current, pos))) {
                return BoundaryFinalizeResult.failed(restoreSnapshots(world, snapshots));
            }
        }
        return BoundaryFinalizeResult.succeeded();
    }

    private static <S> boolean restoreSnapshots(BlockStateWriteAccess<S> world, Map<BlockPos, S> snapshots) {
        boolean complete = true;
        List<Map.Entry<BlockPos, S>> reverse = new ArrayList<>(snapshots.entrySet());
        Collections.reverse(reverse);
        for (Map.Entry<BlockPos, S> entry : reverse) {
            complete &= writeExactBlockState(world, entry.getKey(), entry.getValue(),
                    Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        return complete;
    }

    interface BlockStateWriteAccess<S> {
        S getBlockState(BlockPos pos);

        S updateFromNeighbourShapes(S state, BlockPos pos);

        boolean isHorizontalConnectionBlock(S state);

        boolean ensureCanWrite(BlockPos pos);

        boolean setBlock(BlockPos pos, S state, int flags);
    }

    public static final class WorldGenExecutionWorld
            implements ExecutionWorld, BlockStateWriteAccess<BlockState> {
        private final WorldGenLevel level;
        private CityWorldgenBlockObservationRegistry.BlockObservationRollbackToken activeRollbackToken;

        public WorldGenExecutionWorld(WorldGenLevel level) {
            this.level = Objects.requireNonNull(level, "level");
        }

        @Override
        public ColumnSample sampleColumn(int worldX, int worldZ) {
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ) - 1;
            BlockState state = getBlockState(new BlockPos(worldX, y, worldZ));
            ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            return new ColumnSample(y, key.toString(), isNaturalSurface(state));
        }

        @Override
        public boolean isKnownBlock(String blockId) {
            ResourceLocation key = ResourceLocation.tryParse(blockId);
            return key != null && BuiltInRegistries.BLOCK.containsKey(key);
        }

        @Override
        public boolean ensureCanWrite(int worldX, int y, int worldZ) {
            return ensureCanWrite(new BlockPos(worldX, y, worldZ));
        }

        @Override
        public TargetState inspect(int worldX, int y, int worldZ) {
            BlockPos pos = new BlockPos(worldX, y, worldZ);
            BlockState state = getBlockState(pos);
            return new TargetState(new WorldSnapshot(state, null), isLandUseReplaceable(state));
        }

        /**
         * The terrain heightmap deliberately ignores leaves. A canopy crossing into a confirmed LandUse
         * surface must therefore be replaceable too, otherwise grading samples the ground below it and then
         * rejects the same column when the platform reaches the canopy. Logs and constructed solid blocks
         * remain occupied targets.
         */
        static boolean isLandUseReplaceable(BlockState state) {
            return isLandUseReplaceable(state.isAir(), state.canBeReplaced(),
                    state.getBlock() instanceof LeavesBlock || state.is(BlockTags.LEAVES));
        }

        static boolean isLandUseReplaceable(boolean air, boolean replaceable, boolean leaves) {
            return air || replaceable || leaves;
        }

        @Override
        public Object beginWrite(int worldX, int y, int worldZ, Object snapshot) {
            if (!(snapshot instanceof WorldSnapshot worldSnapshot)) {
                return snapshot;
            }
            if (activeRollbackToken != null) {
                throw new IllegalStateException("CITY_LAND_USE_OBSERVATION_MUTATION_ALREADY_ACTIVE");
            }
            activeRollbackToken = CityWorldgenBlockObservationRegistry.newRollbackToken();
            return new WorldSnapshot(worldSnapshot.blockState(), activeRollbackToken);
        }

        @Override
        public boolean setBlock(int worldX, int y, int worldZ, String blockId) {
            ResourceLocation key = ResourceLocation.tryParse(blockId);
            if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) {
                return false;
            }
            BlockPos pos = new BlockPos(worldX, y, worldZ);
            BlockState requested = BuiltInRegistries.BLOCK.get(key).defaultBlockState();
            boolean written = isHorizontalConnectionBlock(requested)
                    ? writeConnectionCompatibleBlockState(pos, requested)
                    : writeExactBlockState(this, pos, requested, Block.UPDATE_ALL);
            if (written) {
                watchObservedNeighborhood(pos);
            }
            return written;
        }

        @Override
        public boolean setFeatureBlock(int worldX, int y, int worldZ, String blockId,
                                       CityLandUseSurfacePrintPlan.FeatureKind kind,
                                       CityLandUseSurfacePrintPlan.HorizontalFacing facing) {
            if (kind != CityLandUseSurfacePrintPlan.FeatureKind.ROAD_SLAB
                    && kind != CityLandUseSurfacePrintPlan.FeatureKind.BRIDGE_DECK
                    && kind != CityLandUseSurfacePrintPlan.FeatureKind.ROAD_STAIR
                    && kind != CityLandUseSurfacePrintPlan.FeatureKind.ROAD_LAMP) {
                return setBlock(worldX, y, worldZ, blockId);
            }
            ResourceLocation key = ResourceLocation.tryParse(blockId);
            if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) return false;
            BlockPos pos = new BlockPos(worldX, y, worldZ);
            BlockState requested = featureBlockState(
                    BuiltInRegistries.BLOCK.get(key).defaultBlockState(), kind, facing);
            boolean written = isHorizontalConnectionBlock(requested)
                    ? writeConnectionCompatibleBlockState(pos, requested)
                    : writeExactBlockState(this, pos, requested, Block.UPDATE_ALL);
            if (written) watchObservedNeighborhood(pos);
            return written;
        }

        private boolean writeConnectionCompatibleBlockState(BlockPos pos, BlockState requested) {
            // WorldGenRegion does not apply neighbour updates. Commit the requested identity first;
            // reconcile all connection shapes together after every layer exists, just like boundary fences.
            return writeExactBlockState(this, pos, requested, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }

        @Override
        public boolean setBoundaryBlockRaw(int worldX, int y, int worldZ, String blockId) {
            ResourceLocation key = ResourceLocation.tryParse(blockId);
            if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) {
                return false;
            }
            BlockPos pos = new BlockPos(worldX, y, worldZ);
            boolean written = writeExactBlockState(this, pos, boundaryBlockState(
                            BuiltInRegistries.BLOCK.get(key).defaultBlockState()),
                    Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            if (written) watchObservedNeighborhood(pos);
            return written;
        }

        @Override
        public BoundaryFinalizeResult finalizeBoundaryConnections(List<BlockPosition> positions) {
            List<BlockPos> blockPositions = positions.stream().map(BlockPosition::toBlockPos).toList();
            if (activeRollbackToken != null) {
                throw new IllegalStateException("CITY_LAND_USE_OBSERVATION_MUTATION_ALREADY_ACTIVE");
            }
            CityWorldgenBlockObservationRegistry.BlockObservationRollbackToken finalizeRollbackToken =
                    CityWorldgenBlockObservationRegistry.newRollbackToken();
            activeRollbackToken = finalizeRollbackToken;
            try {
                BoundaryFinalizeResult finalized = finalizeHorizontalConnections(this, blockPositions);
                blockPositions.forEach(this::watchObservedNeighborhood);
                if (!finalized.success()) {
                    CityWorldgenBlockObservationRegistry.rollbackToken(finalizeRollbackToken);
                }
                return finalized;
            } catch (RuntimeException | Error failure) {
                CityWorldgenBlockObservationRegistry.rollbackToken(finalizeRollbackToken);
                throw failure;
            } finally {
                activeRollbackToken = null;
            }
        }

        @Override
        public void endWrite(Object snapshot) {
            activeRollbackToken = null;
        }

        @Override
        public boolean restoreBlock(int worldX, int y, int worldZ, Object snapshot) {
            if (!(snapshot instanceof WorldSnapshot expected)) {
                return false;
            }
            BlockPos pos = new BlockPos(worldX, y, worldZ);
            boolean restored = writeExactBlockState(this, pos, expected.blockState(),
                    Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            if (restored) {
                CityWorldgenBlockObservationRegistry.rollbackToken(expected.rollbackToken());
            }
            return restored;
        }

        private void watchObservedNeighborhood(BlockPos center) {
            watchObservedBlock(center, "land_use_direct");
            for (Direction direction : HORIZONTAL_DIRECTIONS) {
                watchObservedBlock(center.relative(direction), "land_use_neighbor_reconcile");
            }
        }

        private void watchObservedBlock(BlockPos pos, String source) {
            BlockState state = getBlockState(pos);
            CityWorldgenBlockObservationRegistry.watchBlockState(pos, state, source, activeRollbackToken);
        }

        private record WorldSnapshot(
                BlockState blockState,
                CityWorldgenBlockObservationRegistry.BlockObservationRollbackToken rollbackToken) {
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return level.getBlockState(pos);
        }

        @Override
        public BlockState updateFromNeighbourShapes(BlockState state, BlockPos pos) {
            return Block.updateFromNeighbourShapes(state, level, pos);
        }

        @Override
        public boolean isHorizontalConnectionBlock(BlockState state) {
            return state.getBlock() instanceof CrossCollisionBlock
                    || state.getBlock() instanceof WallBlock;
        }

        @Override
        public boolean ensureCanWrite(BlockPos pos) {
            return level.ensureCanWrite(pos);
        }

        @Override
        public boolean setBlock(BlockPos pos, BlockState state, int flags) {
            return level.setBlock(pos, state, flags);
        }

        static boolean isNaturalSurface(BlockState state) {
            return state.is(BlockTags.DIRT)
                    || state.is(BlockTags.SAND)
                    || state.is(BlockTags.BASE_STONE_OVERWORLD)
                    || state.is(BlockTags.BASE_STONE_NETHER)
                    || state.is(Blocks.GRAVEL)
                    || state.is(Blocks.CLAY)
                    || state.is(Blocks.MUD)
                    || state.is(Blocks.SNOW_BLOCK)
                    || state.is(Blocks.ICE)
                    || state.is(Blocks.PACKED_ICE)
                    || state.is(Blocks.BLUE_ICE);
        }
    }
}
