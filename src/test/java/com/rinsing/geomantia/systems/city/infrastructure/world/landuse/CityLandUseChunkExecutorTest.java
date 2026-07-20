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
    void appliesSurfaceBeforeBoundaryAndClipsUnsafeTargets() {
        CityLandUseChunkCompiler.ChunkFragment fragment = fragment();
        FakeWorld world = new FakeWorld();
        world.natural.put("1,0", false);
        world.replaceable.put("3,65,0", false);

        CityLandUseChunkExecutor.ExecutionResult result = executor.execute(fragment, world,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES);

        assertEquals(CityLandUseChunkExecutor.Status.APPLIED, result.status());
        assertEquals(1, result.naturalSurfaceSkippedCount());
        assertEquals(1, result.occupiedBoundarySkippedCount());
        assertEquals(List.of("0,64,0=minecraft:stone_bricks", "2,65,0=minecraft:oak_fence"), world.writes);
    }

    @Test
    void rollsBackEarlierWritesWhenOneMutationFails() {
        FakeWorld world = new FakeWorld();
        world.failWriteIndex = 2;

        CityLandUseChunkExecutor.ExecutionResult result = executor.execute(fragment(), world,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES);

        assertEquals(CityLandUseChunkExecutor.Status.FAILED, result.status());
        assertTrue(result.rollbackComplete());
        assertEquals(List.of("0,64,0=old"), world.restores);
    }

    @Test
    void refusesAlreadyGeneratedChunkWithoutSamplingOrWriting() {
        FakeWorld world = new FakeWorld();

        CityLandUseChunkExecutor.ExecutionResult result = executor.execute(fragment(), world,
                CityLandUseChunkExecutor.GenerationEligibility.ALREADY_GENERATED);

        assertEquals(CityLandUseChunkExecutor.Status.INELIGIBLE, result.status());
        assertEquals("CITY_LAND_USE_OLD_CHUNK_NOT_BACKFILLED", result.reasonCode());
        assertTrue(world.writes.isEmpty());
        assertEquals(0, world.sampleCount);
    }

    @Test
    void fillsSmallPaveDepressionBeforeRaisedSurfaceAndBoundary() {
        FakeWorld world = new FakeWorld();
        world.columns.put("8,8", new CityLandUseChunkExecutor.ColumnSample(
                62, "minecraft:dirt", true));

        CityLandUseChunkExecutor.ExecutionResult result = executor.execute(microFillFragment(), world,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES);

        assertEquals(CityLandUseChunkExecutor.Status.APPLIED, result.status());
        assertEquals(List.of("8,63,8=minecraft:dirt", "8,64,8=minecraft:stone_bricks",
                "8,65,8=minecraft:oak_fence"), world.writes);
    }

    @Test
    void reconcilesFenceConnectionsWhenWorldgenIgnoresUpdateFlags() {
        WorldgenLikeBlockStateWorld world = new WorldgenLikeBlockStateWorld();
        BlockPos west = new BlockPos(15, 65, 0);
        BlockPos east = west.east();

        assertTrue(CityLandUseChunkExecutor.writeBlockState(
                world, west, TestBlockState.FENCE));
        assertFalse(world.getBlockState(west).east());

        assertTrue(CityLandUseChunkExecutor.writeBlockState(
                world, east, TestBlockState.FENCE));

        assertTrue(world.getBlockState(west).east());
        assertTrue(world.getBlockState(east).west());
    }

    @Test
    void restoresPrimaryAndNeighborStatesWhenConnectionRefreshFails() {
        WorldgenLikeBlockStateWorld world = new WorldgenLikeBlockStateWorld();
        BlockPos west = new BlockPos(15, 65, 0);
        BlockPos east = west.east();
        assertTrue(CityLandUseChunkExecutor.writeBlockState(
                world, west, TestBlockState.FENCE));
        world.failNextWriteAt = west;

        assertFalse(CityLandUseChunkExecutor.writeBlockState(
                world, east, TestBlockState.FENCE));

        assertEquals(TestBlockState.AIR, world.getBlockState(east));
        assertFalse(world.getBlockState(west).east());
    }

    @Test
    void restoresPrimaryStateWhenConnectionNeighborIsOutsideWriteRadius() {
        WorldgenLikeBlockStateWorld world = new WorldgenLikeBlockStateWorld();
        BlockPos west = new BlockPos(15, 65, 0);
        BlockPos east = west.east();
        assertTrue(CityLandUseChunkExecutor.writeBlockState(
                world, west, TestBlockState.FENCE));
        world.unwritable = west;

        assertFalse(CityLandUseChunkExecutor.writeBlockState(
                world, east, TestBlockState.FENCE));

        assertEquals(TestBlockState.AIR, world.getBlockState(east));
        assertFalse(world.getBlockState(west).east());
    }

    private static CityLandUseChunkCompiler.ChunkFragment fragment() {
        return new CityLandUseChunkCompiler.ChunkFragment(CityLandUseChunkCompiler.RESULT_SCHEMA,
                "city", "hash", "palette", 0, 0, 4, 0, 0, 0,
                null, List.of(),
                List.of(new CityLandUseChunkCompiler.SurfaceOperation("area", "plaza", 0, 0,
                                "minecraft:stone_bricks"),
                        new CityLandUseChunkCompiler.SurfaceOperation("area", "plaza", 1, 0,
                                "minecraft:stone_bricks")),
                List.of(new CityLandUseChunkCompiler.BoundaryOperation("area", "plaza", 2, 0,
                                "minecraft:oak_fence"),
                        new CityLandUseChunkCompiler.BoundaryOperation("area", "plaza", 3, 0,
                                "minecraft:oak_fence")));
    }

    private static CityLandUseChunkCompiler.ChunkFragment microFillFragment() {
        List<CityLandUseChunkCompiler.GradingMaskCell> mask = new ArrayList<>();
        for (int z = 0; z <= 15; z++) {
            for (int x = 0; x <= 15; x++) {
                mask.add(new CityLandUseChunkCompiler.GradingMaskCell("area", x, z));
            }
        }
        return new CityLandUseChunkCompiler.ChunkFragment(CityLandUseChunkCompiler.RESULT_SCHEMA,
                "city", "hash", "palette", 0, 0, 1, 0, 0, 0,
                "minecraft:dirt", mask,
                List.of(new CityLandUseChunkCompiler.SurfaceOperation("area", "plaza", 8, 8,
                        "minecraft:stone_bricks")),
                List.of(new CityLandUseChunkCompiler.BoundaryOperation("area", "plaza", 8, 8,
                        "minecraft:oak_fence")));
    }

    private static final class FakeWorld implements CityLandUseChunkExecutor.ExecutionWorld {
        private final Map<String, Boolean> natural = new HashMap<>();
        private final Map<String, Boolean> replaceable = new HashMap<>();
        private final Map<String, CityLandUseChunkExecutor.ColumnSample> columns = new HashMap<>();
        private final List<String> writes = new ArrayList<>();
        private final List<String> restores = new ArrayList<>();
        private int failWriteIndex = -1;
        private int sampleCount;

        @Override
        public CityLandUseChunkExecutor.ColumnSample sampleColumn(int worldX, int worldZ) {
            sampleCount++;
            return columns.getOrDefault(worldX + "," + worldZ,
                    new CityLandUseChunkExecutor.ColumnSample(64, "minecraft:grass_block",
                            natural.getOrDefault(worldX + "," + worldZ, true)));
        }

        @Override
        public boolean isKnownBlock(String blockId) {
            return blockId.startsWith("minecraft:");
        }

        @Override
        public boolean ensureCanWrite(int worldX, int y, int worldZ) {
            return true;
        }

        @Override
        public CityLandUseChunkExecutor.TargetState inspect(int worldX, int y, int worldZ) {
            String key = worldX + "," + y + "," + worldZ;
            return new CityLandUseChunkExecutor.TargetState("old", replaceable.getOrDefault(key, true));
        }

        @Override
        public boolean setBlock(int worldX, int y, int worldZ, String blockId) {
            if (writes.size() + 1 == failWriteIndex) return false;
            writes.add(worldX + "," + y + "," + worldZ + "=" + blockId);
            return true;
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
