package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Applies one owner-chunk LandUse fragment as a small rollback-capable transaction. */
public final class CityLandUseChunkExecutor {

    public ExecutionResult execute(CityLandUseChunkCompiler.ChunkFragment fragment,
                                   ExecutionWorld world,
                                   GenerationEligibility eligibility) {
        Objects.requireNonNull(fragment, "fragment");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(eligibility, "eligibility");
        if (eligibility != GenerationEligibility.FIRST_WORLDGEN_FEATURES) {
            return ExecutionResult.ineligible(fragment, "CITY_LAND_USE_OLD_CHUNK_NOT_BACKFILLED");
        }

        List<PreparedMutation> prepared = new ArrayList<>();
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
        for (CityLandUseChunkCompiler.SurfaceOperation operation : fragment.surfaceOperations()) {
            ColumnKey key = new ColumnKey(operation.x(), operation.z());
            ColumnSample column = terrainView.sample(operation.x(), operation.z());
            if (!column.naturalSurface()) {
                naturalSurfaceSkipped++;
                continue;
            }
            CityLandUseMicroGrader.FillDecision fill = fillByColumn.get(key);
            int targetSurfaceY = fill == null ? column.surfaceY() : fill.targetY();
            if (fill != null) {
                for (int y = column.surfaceY() + 1; y < targetSurfaceY; y++) {
                    PreparedMutation mutation = prepare(world, operation.areaId(), OperationPhase.MICRO_FILL,
                            operation.x(), y, operation.z(), fragment.microFillBlockId(), true);
                    if (mutation.failureReason() != null) {
                        return ExecutionResult.failed(fragment, mutation.failureReason(), prepared.size(),
                                naturalSurfaceSkipped, occupiedBoundarySkipped, 0, true);
                    }
                    prepared.add(mutation);
                }
            }
            PreparedMutation mutation = prepare(world, operation.areaId(), OperationPhase.SURFACE,
                    operation.x(), targetSurfaceY, operation.z(), operation.blockId(), fill != null);
            if (mutation.failureReason() != null) {
                return ExecutionResult.failed(fragment, mutation.failureReason(), prepared.size(),
                        naturalSurfaceSkipped, occupiedBoundarySkipped, 0, true);
            }
            prepared.add(mutation);
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
                return ExecutionResult.failed(fragment, mutation.failureReason(), prepared.size(),
                        naturalSurfaceSkipped, occupiedBoundarySkipped, 0, true);
            }
            prepared.add(mutation);
        }

        List<PreparedMutation> applied = new ArrayList<>();
        for (PreparedMutation mutation : prepared) {
            if (!world.setBlock(mutation.x(), mutation.y(), mutation.z(), mutation.blockId())) {
                boolean rolledBack = rollback(world, applied);
                return ExecutionResult.failed(fragment, "CITY_LAND_USE_BLOCK_WRITE_FAILED",
                        prepared.size(), naturalSurfaceSkipped, occupiedBoundarySkipped,
                        applied.size(), rolledBack);
            }
            applied.add(mutation);
        }
        return ExecutionResult.applied(fragment, prepared.size(), naturalSurfaceSkipped,
                occupiedBoundarySkipped);
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
                    phase == OperationPhase.BOUNDARY
                            ? "CITY_LAND_USE_BOUNDARY_TARGET_OCCUPIED"
                            : "CITY_LAND_USE_MICRO_FILL_TARGET_OCCUPIED");
        }
        return PreparedMutation.ready(areaId, phase, x, y, z, blockId, target.snapshot());
    }

    private static boolean rollback(ExecutionWorld world, List<PreparedMutation> applied) {
        boolean complete = true;
        List<PreparedMutation> reverse = new ArrayList<>(applied);
        Collections.reverse(reverse);
        for (PreparedMutation mutation : reverse) {
            complete &= world.restoreBlock(mutation.x(), mutation.y(), mutation.z(), mutation.snapshot());
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
        BOUNDARY
    }

    public interface ExecutionWorld {
        ColumnSample sampleColumn(int worldX, int worldZ);

        boolean isKnownBlock(String blockId);

        boolean ensureCanWrite(int worldX, int y, int worldZ);

        TargetState inspect(int worldX, int y, int worldZ);

        boolean setBlock(int worldX, int y, int worldZ, String blockId);

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
                    fragment.cityId(), fragment.planHash(), fragment.chunkX(), fragment.chunkZ(),
                    applied, applied, naturalSkipped, boundarySkipped, true);
        }

        private static ExecutionResult failed(CityLandUseChunkCompiler.ChunkFragment fragment,
                                              String reason,
                                              int prepared,
                                              int naturalSkipped,
                                              int boundarySkipped,
                                              int applied,
                                              boolean rollbackComplete) {
            return new ExecutionResult(Status.FAILED, reason, fragment.cityId(), fragment.planHash(),
                    fragment.chunkX(), fragment.chunkZ(), prepared, applied, naturalSkipped,
                    boundarySkipped, rollbackComplete);
        }

        private static ExecutionResult ineligible(CityLandUseChunkCompiler.ChunkFragment fragment,
                                                  String reason) {
            return new ExecutionResult(Status.INELIGIBLE, reason, fragment.cityId(), fragment.planHash(),
                    fragment.chunkX(), fragment.chunkZ(), 0, 0, 0, 0, true);
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
    }

    private record ColumnKey(int x, int z) {
    }

    public static final class WorldGenExecutionWorld implements ExecutionWorld {
        private final WorldGenLevel level;

        public WorldGenExecutionWorld(WorldGenLevel level) {
            this.level = Objects.requireNonNull(level, "level");
        }

        @Override
        public ColumnSample sampleColumn(int worldX, int worldZ) {
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ) - 1;
            BlockState state = level.getBlockState(new BlockPos(worldX, y, worldZ));
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
            return level.ensureCanWrite(new BlockPos(worldX, y, worldZ));
        }

        @Override
        public TargetState inspect(int worldX, int y, int worldZ) {
            BlockState state = level.getBlockState(new BlockPos(worldX, y, worldZ));
            return new TargetState(state, state.isAir() || state.canBeReplaced());
        }

        @Override
        public boolean setBlock(int worldX, int y, int worldZ, String blockId) {
            ResourceLocation key = ResourceLocation.tryParse(blockId);
            if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) {
                return false;
            }
            BlockPos pos = new BlockPos(worldX, y, worldZ);
            BlockState expected = Block.updateFromNeighbourShapes(
                    BuiltInRegistries.BLOCK.get(key).defaultBlockState(), level, pos);
            level.setBlock(pos, expected, Block.UPDATE_ALL);
            return level.getBlockState(pos).equals(expected);
        }

        @Override
        public boolean restoreBlock(int worldX, int y, int worldZ, Object snapshot) {
            if (!(snapshot instanceof BlockState expected)) {
                return false;
            }
            BlockPos pos = new BlockPos(worldX, y, worldZ);
            level.setBlock(pos, expected, Block.UPDATE_ALL);
            return level.getBlockState(pos).equals(expected);
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
