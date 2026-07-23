package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import com.rinsing.geomantia.systems.city.infrastructure.world.CityWorldgenBlockObservationRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.state.BlockState;
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
    private static final List<Direction> HORIZONTAL_DIRECTIONS = List.of(
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);

    public ExecutionResult execute(CityLandUseChunkCompiler.ChunkFragment fragment,
                                   ExecutionWorld world,
                                   GenerationEligibility eligibility) {
        Objects.requireNonNull(fragment, "fragment");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(eligibility, "eligibility");
        if (eligibility != GenerationEligibility.FIRST_WORLDGEN_FEATURES) {
            return ExecutionResult.ineligible(fragment, "CITY_LAND_USE_OLD_CHUNK_NOT_BACKFILLED");
        }

        List<PreparedMutation> basePrepared = new ArrayList<>();
        List<PreparedMutation> cropPrepared = new ArrayList<>();
        List<PreparedMutation> boundaryPrepared = new ArrayList<>();
        int naturalSurfaceSkipped = 0;
        int occupiedBoundarySkipped = 0;
        Map<ColumnKey, ColumnSample> terrain = new HashMap<>();
        CityLandUseMicroGrader.TerrainView terrainView = (x, z) ->
                terrain.computeIfAbsent(new ColumnKey(x, z), ignored -> requiredColumn(world, x, z));
        Map<ColumnKey, CityLandUseMicroGrader.FillDecision> fillByColumn = new HashMap<>();
        for (CityLandUseMicroGrader.FillDecision decision
                : CityLandUseMicroGrader.plan(fragment, terrainView)) {
            fillByColumn.put(new ColumnKey(decision.x(), decision.z()), decision);
        }
        Map<ColumnKey, Integer> plannedSurfaceY = new HashMap<>();
        Set<ColumnKey> countedNaturalSkips = new HashSet<>();
        Set<ColumnKey> preparedFillColumns = new HashSet<>();
        for (CityLandUseChunkCompiler.SurfaceOperation operation : fragment.surfaceOperations()) {
            ColumnKey key = new ColumnKey(operation.x(), operation.z());
            ColumnSample column = terrainView.sample(operation.x(), operation.z());
            if (!column.naturalSurface()) {
                if (countedNaturalSkips.add(key)) {
                    naturalSurfaceSkipped++;
                }
                continue;
            }
            CityLandUseMicroGrader.FillDecision fill = fillByColumn.get(key);
            int targetSurfaceY = fill == null ? column.surfaceY() : fill.targetY();
            if (fill != null && preparedFillColumns.add(key)) {
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
            OperationPhase phase = operation.stage() == CityLandUseChunkCompiler.SurfaceStage.BASE
                    ? OperationPhase.SURFACE : OperationPhase.SURFACE_OVERLAY;
            PreparedMutation mutation = prepare(world, operation.areaId(), phase,
                    operation.x(), targetSurfaceY + operation.surfaceOffset(), operation.z(), operation.blockId(),
                    operation.requireReplaceableTarget() || fill != null && operation.surfaceOffset() == 0);
            if (mutation.failureReason() != null) {
                return ExecutionResult.failed(fragment, mutation.failureReason(),
                        preparedCount(basePrepared, cropPrepared, boundaryPrepared), 0,
                        naturalSurfaceSkipped, occupiedBoundarySkipped, true);
            }
            (operation.stage() == CityLandUseChunkCompiler.SurfaceStage.BASE
                    ? basePrepared : cropPrepared).add(mutation);
            plannedSurfaceY.put(key, targetSurfaceY);
        }

        for (CityLandUseChunkCompiler.BoundaryOperation operation : fragment.boundaryOperations()) {
            ColumnKey key = new ColumnKey(operation.x(), operation.z());
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
        if (!apply(world, boundaryPrepared, boundaryApplied)) {
            boolean rolledBack = rollback(world, boundaryApplied);
            rolledBack &= rollback(world, cropApplied);
            rolledBack &= rollback(world, baseApplied);
            return ExecutionResult.failed(fragment, "CITY_LAND_USE_BLOCK_WRITE_FAILED",
                    preparedBlockCount,
                    baseApplied.size() + cropApplied.size() + boundaryApplied.size(),
                    naturalSurfaceSkipped, occupiedBoundarySkipped, rolledBack);
        }
        return ExecutionResult.applied(fragment, preparedBlockCount,
                naturalSurfaceSkipped, occupiedBoundarySkipped);
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
                return false;
            }
            // A failed writer may already have mutated the target before reporting failure.
            applied.add(mutation.withSnapshot(writeSnapshot));
            boolean written;
            try {
                written = world.setBlock(mutation.x(), mutation.y(), mutation.z(), mutation.blockId());
            } catch (RuntimeException ex) {
                written = false;
            }
            try {
                world.endWrite(writeSnapshot);
            } catch (RuntimeException ex) {
                written = false;
            }
            if (!written) return false;
        }
        return true;
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
                        default -> "CITY_LAND_USE_MICRO_FILL_TARGET_OCCUPIED";
                    });
        }
        return PreparedMutation.ready(areaId, phase, x, y, z, blockId, target.snapshot());
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
        ALREADY_GENERATED
    }

    public enum OperationPhase {
        MICRO_FILL,
        SURFACE,
        SURFACE_OVERLAY,
        BOUNDARY
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
                                  boolean rollbackComplete) {
        private static ExecutionResult applied(CityLandUseChunkCompiler.ChunkFragment fragment,
                                               int applied,
                                               int naturalSkipped,
                                               int boundarySkipped) {
            return new ExecutionResult(Status.APPLIED, "CITY_LAND_USE_OWNER_APPLIED",
                    fragment.cityId(), fragment.planHash(), fragment.paletteHash(),
                    fragment.chunkX(), fragment.chunkZ(), applied, applied,
                    naturalSkipped, boundarySkipped, true);
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
                    naturalSkipped, boundarySkipped, rollbackComplete);
        }

        private static ExecutionResult ineligible(CityLandUseChunkCompiler.ChunkFragment fragment,
                                                  String reason) {
            return new ExecutionResult(Status.INELIGIBLE, reason, fragment.cityId(), fragment.planHash(),
                    fragment.paletteHash(), fragment.chunkX(), fragment.chunkZ(), 0, 0, 0, 0, true);
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
                                    String failureReason) {
        private static PreparedMutation ready(String areaId, OperationPhase phase,
                                              int x, int y, int z, String blockId, Object snapshot) {
            return new PreparedMutation(areaId, phase, x, y, z, blockId, snapshot, null);
        }

        private static PreparedMutation failed(String areaId, OperationPhase phase,
                                               int x, int y, int z, String blockId, String reason) {
            return new PreparedMutation(areaId, phase, x, y, z, blockId, null, reason);
        }

        private PreparedMutation withSnapshot(Object writeSnapshot) {
            return new PreparedMutation(areaId, phase, x, y, z, blockId, writeSnapshot, failureReason);
        }
    }

    private record ColumnKey(int x, int z) {
    }

    // WorldGenRegion ignores neighbor-update flags, so connecting blocks need explicit reconciliation.
    static <S> boolean writeBlockState(BlockStateWriteAccess<S> world, BlockPos pos, S requested) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(requested, "requested");

        Map<BlockPos, S> snapshots = new LinkedHashMap<>();
        snapshots.put(pos, world.getBlockState(pos));
        for (Direction direction : HORIZONTAL_DIRECTIONS) {
            BlockPos neighborPos = pos.relative(direction);
            snapshots.put(neighborPos, world.getBlockState(neighborPos));
        }

        S resolved = world.updateFromNeighbourShapes(requested, pos);
        if (!world.setBlock(pos, resolved, Block.UPDATE_ALL)) {
            return false;
        }
        if (!reconcileHorizontalConnections(world, pos)) {
            restoreSnapshots(world, snapshots);
            return false;
        }

        S current = world.getBlockState(pos);
        S reconciled = world.updateFromNeighbourShapes(current, pos);
        if (!current.equals(reconciled)
                && !world.setBlock(pos, reconciled, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE)) {
            restoreSnapshots(world, snapshots);
            return false;
        }
        if (!world.getBlockState(pos).equals(reconciled)) {
            restoreSnapshots(world, snapshots);
            return false;
        }
        return true;
    }

    private static <S> boolean reconcileHorizontalConnections(BlockStateWriteAccess<S> world, BlockPos pos) {
        for (Direction direction : HORIZONTAL_DIRECTIONS) {
            BlockPos neighborPos = pos.relative(direction);
            S neighbor = world.getBlockState(neighborPos);
            if (!world.isHorizontalConnectionBlock(neighbor)) {
                continue;
            }
            S reconciled = world.updateFromNeighbourShapes(neighbor, neighborPos);
            if (neighbor.equals(reconciled)) {
                continue;
            }
            if (!world.ensureCanWrite(neighborPos)
                    || !world.setBlock(neighborPos, reconciled,
                    Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE)) {
                return false;
            }
        }
        return true;
    }

    private static <S> void restoreSnapshots(BlockStateWriteAccess<S> world, Map<BlockPos, S> snapshots) {
        List<Map.Entry<BlockPos, S>> reverse = new ArrayList<>(snapshots.entrySet());
        Collections.reverse(reverse);
        for (Map.Entry<BlockPos, S> entry : reverse) {
            world.setBlock(entry.getKey(), entry.getValue(),
                    Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
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
            return new TargetState(new WorldSnapshot(state, null), state.isAir() || state.canBeReplaced());
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
            boolean written = writeBlockState(this, pos, BuiltInRegistries.BLOCK.get(key).defaultBlockState());
            if (written) {
                watchObservedNeighborhood(pos);
            }
            return written;
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
            boolean restored = writeBlockState(this, pos, expected.blockState());
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
            return state.getBlock() instanceof CrossCollisionBlock;
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
