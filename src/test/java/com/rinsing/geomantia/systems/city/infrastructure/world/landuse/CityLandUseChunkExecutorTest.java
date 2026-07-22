package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import com.rinsing.geomantia.systems.city.infrastructure.world.CityNbtPrefabBatchPlacer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
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
        assertEquals(List.of("1,64,0=old", "0,64,0=old"), world.restores);
    }

    @Test
    void restoresCurrentMutationWhenWriterMutatesThenReturnsFalse() {
        FakeWorld world = new FakeWorld();
        world.mutateThenReturnFalseIndex = 2;

        CityLandUseChunkExecutor.ExecutionResult result = executor.execute(fragment(), world,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES);

        assertEquals(CityLandUseChunkExecutor.Status.FAILED, result.status());
        assertTrue(result.rollbackComplete());
        assertEquals(List.of("0,64,0=minecraft:stone_bricks", "1,64,0=minecraft:stone_bricks"),
                world.writes);
        assertEquals(List.of("1,64,0=old", "0,64,0=old"), world.restores);
    }

    @Test
    void restoresCurrentMutationWhenWriterMutatesThenThrows() {
        FakeWorld world = new FakeWorld();
        world.mutateThenThrowIndex = 2;

        CityLandUseChunkExecutor.ExecutionResult result = executor.execute(fragment(), world,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES);

        assertEquals(CityLandUseChunkExecutor.Status.FAILED, result.status());
        assertTrue(result.rollbackComplete());
        assertEquals(List.of("0,64,0=minecraft:stone_bricks", "1,64,0=minecraft:stone_bricks"),
                world.writes);
        assertEquals(List.of("1,64,0=old", "0,64,0=old"), world.restores);
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
    void appliesSurfacePrintOverlayAfterReplacingNaturalSurface() {
        CityLandUseChunkCompiler.ChunkFragment fragment = new CityLandUseChunkCompiler.ChunkFragment(
                CityLandUseChunkCompiler.RESULT_SCHEMA, "city", "hash", "palette", 0, 0,
                1, 0, 0, 0, null, List.of(),
                List.of(new CityLandUseChunkCompiler.SurfaceOperation("area", "farm", 0, 0,
                                "minecraft:farmland", 0, false, 0),
                        new CityLandUseChunkCompiler.SurfaceOperation("area", "farm", 0, 0,
                                "minecraft:wheat", 1, true, 1)),
                List.of());
        FakeWorld world = new FakeWorld();

        CityLandUseChunkExecutor.ExecutionResult result = executor.execute(fragment, world,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES);

        assertEquals(CityLandUseChunkExecutor.Status.APPLIED, result.status());
        assertEquals(List.of("0,64,0=minecraft:farmland", "0,65,0=minecraft:wheat"), world.writes);
        assertEquals(0, result.naturalSurfaceSkippedCount());
    }

    @Test
    void refusesSurfacePrintOverlayWhenTargetIsOccupied() {
        CityLandUseChunkCompiler.ChunkFragment fragment = new CityLandUseChunkCompiler.ChunkFragment(
                CityLandUseChunkCompiler.RESULT_SCHEMA, "city", "hash", "palette", 0, 0,
                1, 0, 0, 0, null, List.of(),
                List.of(new CityLandUseChunkCompiler.SurfaceOperation("area", "farm", 0, 0,
                        "minecraft:wheat", 1, true, 0)), List.of());
        FakeWorld world = new FakeWorld();
        world.replaceable.put("0,65,0", false);

        CityLandUseChunkExecutor.ExecutionResult result = executor.execute(fragment, world,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES);

        assertEquals(CityLandUseChunkExecutor.Status.FAILED, result.status());
        assertEquals("CITY_LAND_USE_SURFACE_PRINT_TARGET_OCCUPIED", result.reasonCode());
        assertTrue(world.writes.isEmpty());
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

    @Test
    void preflightsEveryTargetThenWritesBasePrefabCropBoundaryInOrder() {
        List<String> events = new ArrayList<>();
        FakeWorld blockWorld = new FakeWorld(events);
        FakePrefabWorld prefabWorld = new FakePrefabWorld(events);

        CityLandUseChunkExecutor.ExecutionResult result = executor.execute(
                transactionalFragment(), prefabBatch(), blockWorld, prefabWorld,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES);

        assertEquals(CityLandUseChunkExecutor.Status.APPLIED, result.status());
        assertEquals(1, result.preparedPrefabPlacementCount());
        assertEquals(1, result.appliedPrefabPlacementCount());
        int firstWrite = firstIndexContaining(events, "-write:");
        assertTrue(firstWrite > 0);
        assertTrue(events.subList(firstWrite, events.size()).stream()
                .noneMatch(value -> value.contains("-check:") || value.contains("-snapshot:")));
        assertEquals(List.of(
                        "block-write:minecraft:farmland",
                        "prefab-write:channel",
                        "block-write:minecraft:wheat",
                        "block-write:minecraft:oak_fence"),
                events.stream().filter(value -> value.contains("-write:")).toList());
    }

    @Test
    void cropFailureRollsBackSuccessfulPrefabBeforeBase() {
        List<String> events = new ArrayList<>();
        FakeWorld blockWorld = new FakeWorld(events);
        blockWorld.failBlockId = "minecraft:wheat";
        FakePrefabWorld prefabWorld = new FakePrefabWorld(events);

        CityLandUseChunkExecutor.ExecutionResult result = executor.execute(
                transactionalFragment(), prefabBatch(), blockWorld, prefabWorld,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES);

        assertEquals(CityLandUseChunkExecutor.Status.FAILED, result.status());
        assertTrue(result.rollbackComplete());
        assertEquals(1, result.appliedPrefabPlacementCount());
        assertOrdered(events,
                "block-write:minecraft:farmland",
                "prefab-write:channel",
                "block-write-failed:minecraft:wheat",
                "prefab-restore:5,64,5",
                "block-restore:0,64,0");
    }

    @Test
    void prefabFailureRollsBackItsTargetThenAppliedBase() {
        List<String> events = new ArrayList<>();
        FakeWorld blockWorld = new FakeWorld(events);
        FakePrefabWorld prefabWorld = new FakePrefabWorld(events);
        prefabWorld.failPlacement = true;

        CityLandUseChunkExecutor.ExecutionResult result = executor.execute(
                transactionalFragment(), prefabBatch(), blockWorld, prefabWorld,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES);

        assertEquals(CityLandUseChunkExecutor.Status.FAILED, result.status());
        assertEquals("CITY_NBT_PREFAB_PLACE_FAILED", result.reasonCode());
        assertTrue(result.rollbackComplete());
        assertOrdered(events,
                "block-write:minecraft:farmland",
                "prefab-write-failed:channel",
                "prefab-restore:5,64,5",
                "block-restore:0,64,0");
        assertTrue(blockWorld.writes.stream().noneMatch(value -> value.contains("minecraft:wheat")));
    }

    @Test
    void legacyExecuteEqualsExplicitEmptyPrefabBatch() {
        FakeWorld legacyWorld = new FakeWorld();
        FakeWorld explicitWorld = new FakeWorld();
        CityLandUseChunkCompiler.ChunkFragment fragment = fragment();
        CityNbtPrefabBatchPlacer.BatchRequest empty = new CityNbtPrefabBatchPlacer.BatchRequest(
                "empty", new BoundingBox(0, 0, 0, 15, 255, 15), List.of());

        CityLandUseChunkExecutor.ExecutionResult legacy = executor.execute(fragment, legacyWorld,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES);
        CityLandUseChunkExecutor.ExecutionResult explicit = executor.execute(
                fragment, empty, explicitWorld, new FakePrefabWorld(new ArrayList<>()),
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES);

        assertEquals(legacy, explicit);
        assertEquals(legacyWorld.writes, explicitWorld.writes);
        assertEquals(0, legacy.preparedPrefabPlacementCount());
        assertEquals(0, legacy.appliedPrefabPlacementCount());
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

    private static CityLandUseChunkCompiler.ChunkFragment transactionalFragment() {
        return new CityLandUseChunkCompiler.ChunkFragment(CityLandUseChunkCompiler.RESULT_SCHEMA,
                "city", "hash", "palette", 0, 0, 2, 0, 0, 0,
                null, List.of(),
                // Deliberately place CROP first; executor owns stage ordering.
                List.of(new CityLandUseChunkCompiler.SurfaceOperation("area", "farm", 0, 0,
                                "minecraft:wheat", 1, true,
                                CityLandUseChunkCompiler.SurfaceStage.CROP, 1),
                        new CityLandUseChunkCompiler.SurfaceOperation("area", "farm", 0, 0,
                                "minecraft:farmland", 0, false,
                                CityLandUseChunkCompiler.SurfaceStage.BASE, 0)),
                List.of(new CityLandUseChunkCompiler.BoundaryOperation("area", "farm", 1, 0,
                        "minecraft:oak_fence")));
    }

    private static CityNbtPrefabBatchPlacer.BatchRequest prefabBatch() {
        CityNbtPrefabBatchPlacer.PrefabPlacement placement =
                new CityNbtPrefabBatchPlacer.PrefabPlacement(
                        "channel", "geomantia:channel", "sha256:channel", oneBlockTemplate(),
                        new BlockPos(5, 64, 5), 0, false);
        return new CityNbtPrefabBatchPlacer.BatchRequest(
                "channel-batch", new BoundingBox(0, 0, 0, 15, 255, 15), List.of(placement));
    }

    private static CompoundTag oneBlockTemplate() {
        CompoundTag root = new CompoundTag();
        root.put("size", ints(1, 1, 1));
        CompoundTag state = new CompoundTag();
        state.putString("Name", "minecraft:water");
        ListTag palette = new ListTag();
        palette.add(state);
        root.put("palette", palette);
        CompoundTag block = new CompoundTag();
        block.put("pos", ints(0, 0, 0));
        block.putInt("state", 0);
        ListTag blocks = new ListTag();
        blocks.add(block);
        root.put("blocks", blocks);
        root.put("entities", new ListTag());
        return root;
    }

    private static ListTag ints(int x, int y, int z) {
        ListTag result = new ListTag();
        result.add(IntTag.valueOf(x));
        result.add(IntTag.valueOf(y));
        result.add(IntTag.valueOf(z));
        return result;
    }

    private static int firstIndexContaining(List<String> values, String part) {
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index).contains(part)) return index;
        }
        return -1;
    }

    private static void assertOrdered(List<String> events, String... expected) {
        int previous = -1;
        for (String value : expected) {
            int current = events.indexOf(value);
            assertTrue(current > previous, () -> value + " not ordered in " + events);
            previous = current;
        }
    }

    private static final class FakeWorld implements CityLandUseChunkExecutor.ExecutionWorld {
        private final Map<String, Boolean> natural = new HashMap<>();
        private final Map<String, Boolean> replaceable = new HashMap<>();
        private final Map<String, CityLandUseChunkExecutor.ColumnSample> columns = new HashMap<>();
        private final List<String> writes = new ArrayList<>();
        private final List<String> restores = new ArrayList<>();
        private final List<String> events;
        private int failWriteIndex = -1;
        private int mutateThenReturnFalseIndex = -1;
        private int mutateThenThrowIndex = -1;
        private int writeAttemptCount;
        private String failBlockId;
        private int sampleCount;

        private FakeWorld() {
            this(new ArrayList<>());
        }

        private FakeWorld(List<String> events) {
            this.events = events;
        }

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
            events.add("block-check:" + worldX + "," + y + "," + worldZ);
            return true;
        }

        @Override
        public CityLandUseChunkExecutor.TargetState inspect(int worldX, int y, int worldZ) {
            String key = worldX + "," + y + "," + worldZ;
            events.add("block-snapshot:" + key);
            return new CityLandUseChunkExecutor.TargetState("old", replaceable.getOrDefault(key, true));
        }

        @Override
        public boolean setBlock(int worldX, int y, int worldZ, String blockId) {
            int attempt = ++writeAttemptCount;
            if (attempt == failWriteIndex || blockId.equals(failBlockId)) {
                events.add("block-write-failed:" + blockId);
                return false;
            }
            events.add("block-write:" + blockId);
            writes.add(worldX + "," + y + "," + worldZ + "=" + blockId);
            if (attempt == mutateThenReturnFalseIndex) return false;
            if (attempt == mutateThenThrowIndex) {
                throw new IllegalStateException("write failed after mutation");
            }
            return true;
        }

        @Override
        public boolean restoreBlock(int worldX, int y, int worldZ, Object snapshot) {
            events.add("block-restore:" + worldX + "," + y + "," + worldZ);
            restores.add(worldX + "," + y + "," + worldZ + "=" + snapshot);
            return true;
        }
    }

    private static final class FakePrefabWorld implements CityNbtPrefabBatchPlacer.PlacementWorld {
        private final List<String> events;
        private boolean failPlacement;

        private FakePrefabWorld(List<String> events) {
            this.events = events;
        }

        @Override
        public boolean ensureCanWrite(BlockPos pos) {
            events.add("prefab-check:" + position(pos));
            return true;
        }

        @Override
        public Object snapshot(BlockPos pos) {
            events.add("prefab-snapshot:" + position(pos));
            return "prefab-old";
        }

        @Override
        public boolean restore(BlockPos pos, Object snapshot) {
            events.add("prefab-restore:" + position(pos));
            return true;
        }

        @Override
        public boolean placeTemplate(CompoundTag templateNbt,
                                     BlockPos anchor,
                                     Rotation rotation,
                                     long seed,
                                     boolean ignoreTemplateAir,
                                     BoundingBox ownerBounds) {
            events.add((failPlacement ? "prefab-write-failed:" : "prefab-write:") + "channel");
            return !failPlacement;
        }

        private static String position(BlockPos pos) {
            return pos.getX() + "," + pos.getY() + "," + pos.getZ();
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
