package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityLandUseChunkExecutorTest {
    private final CityLandUseChunkExecutor executor = new CityLandUseChunkExecutor();

    @Test
    void appliesBaseThenOverlayThenBoundary() {
        FakeWorld world = new FakeWorld();

        CityLandUseChunkExecutor.ExecutionResult result = executor.execute(stagedFragment(), world,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES);

        assertEquals(CityLandUseChunkExecutor.Status.APPLIED, result.status());
        assertEquals(List.of(
                "0,64,0=minecraft:farmland",
                "0,65,0=minecraft:wheat",
                "1,65,0=minecraft:oak_fence"), world.writes);
        assertEquals(3, result.preparedOperationCount());
        assertEquals(3, result.appliedOperationCount());
    }

    @Test
    void skipsNonNaturalSurfaceAndOccupiedBoundaryIndependently() {
        FakeWorld world = new FakeWorld();
        world.columns.put("0,0", new CityLandUseChunkExecutor.ColumnSample(
                64, "minecraft:stone_bricks", false));
        world.replaceable.put("1,65,0", false);

        CityLandUseChunkExecutor.ExecutionResult result = executor.execute(stagedFragment(), world,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES);

        assertEquals(CityLandUseChunkExecutor.Status.APPLIED, result.status());
        assertEquals(1, result.naturalSurfaceSkippedCount());
        assertEquals(1, result.occupiedBoundarySkippedCount());
        assertTrue(world.writes.isEmpty());
    }

    @Test
    void rollsBackBaseAndCurrentOverlayWhenOverlayWriteFailsAfterMutation() {
        FakeWorld world = new FakeWorld();
        world.mutateThenFailWriteIndex = 2;

        CityLandUseChunkExecutor.ExecutionResult result = executor.execute(stagedFragment(), world,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES);

        assertEquals(CityLandUseChunkExecutor.Status.FAILED, result.status());
        assertEquals("CITY_LAND_USE_BLOCK_WRITE_FAILED", result.reasonCode());
        assertTrue(result.rollbackComplete());
        assertEquals(List.of("0,65,0=old", "0,64,0=old"), world.restores);
        assertTrue(world.writes.stream().noneMatch(value -> value.contains("oak_fence")));
    }

    @Test
    void preflightFailureDoesNotWriteAnyPreparedMutation() {
        FakeWorld world = new FakeWorld();
        world.known.put("minecraft:wheat", false);

        CityLandUseChunkExecutor.ExecutionResult result = executor.execute(stagedFragment(), world,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES);

        assertEquals(CityLandUseChunkExecutor.Status.FAILED, result.status());
        assertEquals("CITY_LAND_USE_BLOCK_ID_UNKNOWN", result.reasonCode());
        assertTrue(world.writes.isEmpty());
        assertTrue(world.restores.isEmpty());
    }

    @Test
    void refusesAlreadyGeneratedChunkWithoutSampling() {
        FakeWorld world = new FakeWorld();

        CityLandUseChunkExecutor.ExecutionResult result = executor.execute(stagedFragment(), world,
                CityLandUseChunkExecutor.GenerationEligibility.ALREADY_GENERATED);

        assertEquals(CityLandUseChunkExecutor.Status.INELIGIBLE, result.status());
        assertEquals("CITY_LAND_USE_OLD_CHUNK_NOT_BACKFILLED", result.reasonCode());
        assertEquals(0, world.sampleCount);
        assertTrue(world.writes.isEmpty());
    }

    @Test
    void reconcilesFenceConnectionsWhenWorldgenIgnoresUpdateFlags() {
        WorldgenLikeBlockStateWorld world = new WorldgenLikeBlockStateWorld();
        BlockPos west = new BlockPos(15, 65, 0);
        BlockPos east = west.east();

        assertTrue(CityLandUseChunkExecutor.writeBlockState(world, west, TestBlockState.FENCE));
        assertFalse(world.getBlockState(west).east());

        assertTrue(CityLandUseChunkExecutor.writeBlockState(world, east, TestBlockState.FENCE));

        assertTrue(world.getBlockState(west).east());
        assertTrue(world.getBlockState(east).west());
    }

    @Test
    void restoresPrimaryAndNeighborStatesWhenConnectionRefreshFails() {
        WorldgenLikeBlockStateWorld world = new WorldgenLikeBlockStateWorld();
        BlockPos west = new BlockPos(15, 65, 0);
        BlockPos east = west.east();
        assertTrue(CityLandUseChunkExecutor.writeBlockState(world, west, TestBlockState.FENCE));
        world.failNextWriteAt = west;

        assertFalse(CityLandUseChunkExecutor.writeBlockState(world, east, TestBlockState.FENCE));

        assertEquals(TestBlockState.AIR, world.getBlockState(east));
        assertFalse(world.getBlockState(west).east());
    }

    @Test
    void restoresPrimaryStateWhenConnectionNeighborIsOutsideWriteRadius() {
        WorldgenLikeBlockStateWorld world = new WorldgenLikeBlockStateWorld();
        BlockPos west = new BlockPos(15, 65, 0);
        BlockPos east = west.east();
        assertTrue(CityLandUseChunkExecutor.writeBlockState(world, west, TestBlockState.FENCE));
        world.unwritable = west;

        assertFalse(CityLandUseChunkExecutor.writeBlockState(world, east, TestBlockState.FENCE));

        assertEquals(TestBlockState.AIR, world.getBlockState(east));
        assertFalse(world.getBlockState(west).east());
    }

    private static CityLandUseChunkCompiler.ChunkFragment stagedFragment() {
        return new CityLandUseChunkCompiler.ChunkFragment(CityLandUseChunkCompiler.RESULT_SCHEMA,
                "city", "area-hash", "palette-hash", 0, 0, 2, 0, 0, 0,
                null, List.of(),
                List.of(
                        new CityLandUseChunkCompiler.SurfaceOperation("area", "farm", 0, 0,
                                "minecraft:wheat", 1, true, CityLandUseChunkCompiler.SurfaceStage.CROP, 1),
                        new CityLandUseChunkCompiler.SurfaceOperation("area", "farm", 0, 0,
                                "minecraft:farmland", 0, false, CityLandUseChunkCompiler.SurfaceStage.BASE, 0)),
                List.of(new CityLandUseChunkCompiler.BoundaryOperation(
                        "area", "farm", 1, 0, "minecraft:oak_fence")));
    }

    private static final class FakeWorld implements CityLandUseChunkExecutor.ExecutionWorld {
        private final Map<String, CityLandUseChunkExecutor.ColumnSample> columns = new HashMap<>();
        private final Map<String, Boolean> known = new HashMap<>();
        private final Map<String, Boolean> replaceable = new HashMap<>();
        private final List<String> writes = new ArrayList<>();
        private final List<String> restores = new ArrayList<>();
        private int sampleCount;
        private int writeCount;
        private int mutateThenFailWriteIndex = -1;

        @Override
        public CityLandUseChunkExecutor.ColumnSample sampleColumn(int worldX, int worldZ) {
            sampleCount++;
            return columns.getOrDefault(worldX + "," + worldZ,
                    new CityLandUseChunkExecutor.ColumnSample(64, "minecraft:dirt", true));
        }

        @Override
        public boolean isKnownBlock(String blockId) {
            return known.getOrDefault(blockId, true);
        }

        @Override
        public boolean ensureCanWrite(int worldX, int y, int worldZ) {
            return true;
        }

        @Override
        public CityLandUseChunkExecutor.TargetState inspect(int worldX, int y, int worldZ) {
            return new CityLandUseChunkExecutor.TargetState("old",
                    replaceable.getOrDefault(worldX + "," + y + "," + worldZ, true));
        }

        @Override
        public boolean setBlock(int worldX, int y, int worldZ, String blockId) {
            writeCount++;
            writes.add(worldX + "," + y + "," + worldZ + "=" + blockId);
            return writeCount != mutateThenFailWriteIndex;
        }

        @Override
        public boolean restoreBlock(int worldX, int y, int worldZ, Object snapshot) {
            restores.add(worldX + "," + y + "," + worldZ + "=" + snapshot);
            return true;
        }
    }

    private static final class WorldgenLikeBlockStateWorld
            implements CityLandUseChunkExecutor.BlockStateWriteAccess<TestBlockState> {
        private final Map<BlockPos, TestBlockState> states = new HashMap<>();
        private BlockPos failNextWriteAt;
        private BlockPos unwritable;

        @Override
        public TestBlockState getBlockState(BlockPos pos) {
            return states.getOrDefault(pos, TestBlockState.AIR);
        }

        @Override
        public TestBlockState updateFromNeighbourShapes(TestBlockState state, BlockPos pos) {
            if (!state.fence()) {
                return state;
            }
            return new TestBlockState(true,
                    getBlockState(pos.north()).fence(), getBlockState(pos.east()).fence(),
                    getBlockState(pos.south()).fence(), getBlockState(pos.west()).fence());
        }

        @Override
        public boolean isHorizontalConnectionBlock(TestBlockState state) {
            return state.fence();
        }

        @Override
        public boolean ensureCanWrite(BlockPos pos) {
            return !pos.equals(unwritable);
        }

        @Override
        public boolean setBlock(BlockPos pos, TestBlockState state, int flags) {
            if (pos.equals(failNextWriteAt)) {
                failNextWriteAt = null;
                return false;
            }
            states.put(pos.immutable(), state);
            return true;
        }
    }

    private record TestBlockState(boolean fence, boolean north, boolean east, boolean south, boolean west) {
        private static final TestBlockState AIR = new TestBlockState(false, false, false, false, false);
        private static final TestBlockState FENCE = new TestBlockState(true, false, false, false, false);
    }
}
