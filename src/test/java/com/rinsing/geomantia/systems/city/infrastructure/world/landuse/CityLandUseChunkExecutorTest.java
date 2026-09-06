package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan;
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
    void withdrawnFoundationReportsZeroBoundaryAndCropBatchesInsteadOfCountingTheDraft() {
        List<CityLandUseChunkCompiler.GradingMaskCell> mask = new ArrayList<>();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            mask.add(new CityLandUseChunkCompiler.GradingMaskCell("area", x, z, true));
        var fragment = new CityLandUseChunkCompiler.ChunkFragment(
                CityLandUseChunkCompiler.RESULT_SCHEMA, "city", "hash", "palette", 0, 0,
                1, 0, 0, 0, "minecraft:dirt", mask,
                List.of(new CityLandUseChunkCompiler.SurfaceOperation("area", "plaza", 8, 8, "minecraft:stone_bricks")),
                List.of(new CityLandUseChunkCompiler.BoundaryOperation("area", "FENCE", 8, 8, "minecraft:oak_fence")),
                List.of(), List.of(), List.of(new CityLandUseChunkCompiler.PlatformPurposeAnchor("area", "remote",
                CityLandUseChunkCompiler.PlatformPurpose.BUILDING,
                new com.rinsing.geomantia.systems.city.domain.model.BlockBounds(100, 100, 101, 101))), List.of());
        var result = executor.execute(fragment, new FakeWorld(),
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES);
        assertEquals(CityLandUseChunkExecutor.Status.APPLIED, result.status());
        assertEquals(0, result.appliedOperationCount());
        assertEquals(new CityLandUseChunkExecutor.PhaseCounts(0, 0, 0, 0, 0, 0), result.phaseCounts());
        assertEquals(1, result.naturalSurfaceSkippedCount());
        assertEquals(0, result.occupiedBoundarySkippedCount());
        // The former registry formula was 0 total - 1 planned boundary = -1 BASE (fatal).
        assertEquals(-1, result.preparedOperationCount() - fragment.boundaryOperations().size());
    }

    @Test
    void landUseSurfaceMayReplaceCanopyButNotLogsOrConstructedBlocks() {
        assertTrue(CityLandUseChunkExecutor.WorldGenExecutionWorld.isLandUseReplaceable(
                false, false, true));
        assertFalse(CityLandUseChunkExecutor.WorldGenExecutionWorld.isLandUseReplaceable(
                false, false, false));
    }

    @Test
    void featureOperationsPreserveRoadShapeAndGreenPlantLayer() {
        CityLandUseChunkCompiler.ChunkFragment fragment = new CityLandUseChunkCompiler.ChunkFragment(
                CityLandUseChunkCompiler.RESULT_SCHEMA, "city", "area-hash", "palette-hash",
                0, 0, 3, 0, 0, 0, null, List.of(), List.of(), List.of(), List.of(
                new CityLandUseChunkCompiler.FeatureOperation("road", 0, 0,
                        "minecraft:stone_brick_slab", 0,
                        CityLandUseSurfacePrintPlan.FeatureKind.ROAD_SLAB,
                        CityLandUseSurfacePrintPlan.HorizontalFacing.NONE),
                new CityLandUseChunkCompiler.FeatureOperation("road", 1, 0,
                        "minecraft:stone_brick_stairs", 0,
                        CityLandUseSurfacePrintPlan.FeatureKind.ROAD_STAIR,
                        CityLandUseSurfacePrintPlan.HorizontalFacing.NORTH),
                new CityLandUseChunkCompiler.FeatureOperation("green", 2, 0,
                        "minecraft:poppy", 1,
                        CityLandUseSurfacePrintPlan.FeatureKind.GREEN_PLANT,
                        CityLandUseSurfacePrintPlan.HorizontalFacing.NONE)));
        FakeWorld world = new FakeWorld();

        CityLandUseChunkExecutor.ExecutionResult result = executor.execute(fragment, world,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES);

        assertEquals(CityLandUseChunkExecutor.Status.APPLIED, result.status());
        assertEquals(2, result.phaseCounts().appliedBase());
        assertEquals(1, result.phaseCounts().appliedCrop());
        assertEquals(0, result.phaseCounts().appliedBoundary());
        assertTrue(world.featureWrites.contains("0,64,0=ROAD_SLAB:NONE"));
        assertTrue(world.featureWrites.contains("1,64,0=ROAD_STAIR:NORTH"));
        assertTrue(world.featureWrites.contains("2,65,0=GREEN_PLANT:NONE"));
    }

    @Test
    void replacesRoadSlabsWithAscendingPlatformStairsAtTerrainStep() {
        List<CityLandUseChunkCompiler.GradingMaskCell> mask = new ArrayList<>();
        for (int z = -8; z <= 24; z++) {
            for (int x = -8; x <= 24; x++) {
                mask.add(new CityLandUseChunkCompiler.GradingMaskCell("area", x, z, true));
            }
        }
        List<CityLandUseChunkCompiler.SurfaceOperation> surfaces = new ArrayList<>();
        for (int z = 0; z <= 15; z++) {
            for (int x = 0; x <= 15; x++) {
                surfaces.add(new CityLandUseChunkCompiler.SurfaceOperation(
                        "area", "plaza", x, z, "minecraft:stone_bricks"));
            }
        }
        List<CityLandUseChunkCompiler.FeatureOperation> roads = new ArrayList<>();
        for (int x = 0; x <= 15; x++) {
            roads.add(new CityLandUseChunkCompiler.FeatureOperation("road", x, 8,
                    "minecraft:stone_brick_slab", 0,
                    CityLandUseSurfacePrintPlan.FeatureKind.ROAD_SLAB,
                    CityLandUseSurfacePrintPlan.HorizontalFacing.NONE));
        }
        roads.add(new CityLandUseChunkCompiler.FeatureOperation("road", 0, 7,
                "minecraft:stone_brick_stairs", 0,
                CityLandUseSurfacePrintPlan.FeatureKind.ROAD_STAIR,
                CityLandUseSurfacePrintPlan.HorizontalFacing.NORTH));
        CityLandUseChunkCompiler.ChunkFragment fragment = new CityLandUseChunkCompiler.ChunkFragment(
                CityLandUseChunkCompiler.RESULT_SCHEMA, "city", "area-hash", "palette-hash",
                0, 0, surfaces.size(), 0, 0, 0, "minecraft:dirt",
                mask, surfaces, List.of(), roads);
        FakeWorld world = new FakeWorld();
        for (int z = -8; z <= 24; z++) {
            for (int x = 8; x <= 24; x++) {
                world.columns.put(x + "," + z,
                        new CityLandUseChunkExecutor.ColumnSample(68, "minecraft:dirt", true));
            }
        }

        CityLandUseChunkExecutor.ExecutionResult result = executor.execute(fragment, world,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES);

        assertEquals(CityLandUseChunkExecutor.Status.APPLIED, result.status());
        assertTrue(world.featureWrites.contains("4,64,8=ROAD_STAIR:EAST"));
        assertTrue(world.featureWrites.contains("5,65,8=ROAD_STAIR:EAST"));
        assertTrue(world.featureWrites.contains("6,66,8=ROAD_STAIR:EAST"));
        assertTrue(world.featureWrites.contains("7,67,8=ROAD_STAIR:EAST"));
        assertTrue(world.featureWrites.contains("8,68,8=ROAD_SLAB:NONE"));
        assertTrue(world.writes.stream().anyMatch(write ->
                write.startsWith("8,69,") && write.endsWith("=minecraft:stone_brick_wall")));
        assertTrue(world.writes.stream().anyMatch(write ->
                write.startsWith("8,69,") && write.endsWith("=minecraft:flowering_azalea_leaves")));
        assertTrue(world.writes.stream().noneMatch(write -> write.startsWith("8,69,7=")
                || write.startsWith("8,69,8=") || write.startsWith("8,69,9=")));
    }

    @Test
    void bridgeRailsCreatePairedStonePiersDownToSolidBed() {
        List<CityLandUseChunkCompiler.FeatureOperation> features = new ArrayList<>();
        for (int x = 0; x <= 8; x++) {
            for (int z : List.of(0, 4)) {
                features.add(new CityLandUseChunkCompiler.FeatureOperation("bridge-a", x, z,
                        "minecraft:spruce_fence", 1,
                        CityLandUseSurfacePrintPlan.FeatureKind.BRIDGE_RAIL,
                        CityLandUseSurfacePrintPlan.HorizontalFacing.NONE));
            }
        }
        CityLandUseChunkCompiler.ChunkFragment fragment = new CityLandUseChunkCompiler.ChunkFragment(
                CityLandUseChunkCompiler.RESULT_SCHEMA, "city", "area-hash", "palette-hash",
                0, 0, features.size(), 0, 0, 0, null, List.of(), List.of(), List.of(), features);
        FakeWorld world = new FakeWorld();
        for (int x = 0; x <= 8; x++) {
            for (int z : List.of(0, 4)) {
                world.columns.put(x + "," + z,
                        new CityLandUseChunkExecutor.ColumnSample(64, "minecraft:water", true));
                world.replaceable.put(x + ",62," + z, false);
            }
        }

        CityLandUseChunkExecutor.ExecutionResult result = executor.execute(fragment, world,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES);

        assertEquals(CityLandUseChunkExecutor.Status.APPLIED, result.status());
        List<String> piers = world.writes.stream()
                .filter(write -> write.endsWith("=minecraft:stone_bricks"))
                .toList();
        assertFalse(piers.isEmpty());
        assertTrue(piers.stream().anyMatch(write -> write.contains(",63,0=")));
        assertTrue(piers.stream().anyMatch(write -> write.contains(",63,4=")));
    }

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
    void explicitD7BackfillCanApplyTheFrozenChunkTransaction() {
        FakeWorld world = new FakeWorld();

        CityLandUseChunkExecutor.ExecutionResult result = executor.execute(stagedFragment(), world,
                CityLandUseChunkExecutor.GenerationEligibility.CONTROLLED_D7_BACKFILL);

        assertEquals(CityLandUseChunkExecutor.Status.APPLIED, result.status());
        assertFalse(world.writes.isEmpty());
    }

    @Test
    void featurePreflightAcceptsTerrainThatTheSameTransactionCutsFirst() {
        List<CityLandUseChunkCompiler.GradingMaskCell> mask = new ArrayList<>();
        for (int z = -3; z <= 3; z++) {
            for (int x = -3; x <= 3; x++) {
                mask.add(new CityLandUseChunkCompiler.GradingMaskCell("area", x, z, true));
            }
        }
        CityLandUseChunkCompiler.ChunkFragment fragment = new CityLandUseChunkCompiler.ChunkFragment(
                CityLandUseChunkCompiler.RESULT_SCHEMA, "city", "area-hash", "palette-hash",
                0, 0, 1, 0, 0, 0, "minecraft:dirt", mask,
                List.of(new CityLandUseChunkCompiler.SurfaceOperation(
                        "area", "plaza", 0, 0, "minecraft:stone_bricks")),
                List.of(),
                List.of(new CityLandUseChunkCompiler.FeatureOperation("green", 0, 0,
                        "minecraft:poppy", 1,
                        CityLandUseSurfacePrintPlan.FeatureKind.GREEN_PLANT,
                        CityLandUseSurfacePrintPlan.HorizontalFacing.NONE)));
        FakeWorld world = new FakeWorld();
        world.columns.put("0,0", new CityLandUseChunkExecutor.ColumnSample(
                65, "minecraft:grass_block", true));
        world.replaceable.put("0,65,0", false);

        CityLandUseChunkExecutor.ExecutionResult result = executor.execute(fragment, world,
                CityLandUseChunkExecutor.GenerationEligibility.CONTROLLED_D7_BACKFILL);

        assertEquals(CityLandUseChunkExecutor.Status.APPLIED, result.status());
        assertEquals(List.of(
                "0,65,0=minecraft:air",
                "0,64,0=minecraft:stone_bricks",
                "0,65,0=minecraft:poppy"), world.writes);
    }

    @Test
    void featurePreflightReportsItsOwnOccupiedPhaseWhenNoCutWillClearIt() {
        CityLandUseChunkCompiler.ChunkFragment fragment = new CityLandUseChunkCompiler.ChunkFragment(
                CityLandUseChunkCompiler.RESULT_SCHEMA, "city", "area-hash", "palette-hash",
                0, 0, 1, 0, 0, 0, null, List.of(), List.of(), List.of(),
                List.of(new CityLandUseChunkCompiler.FeatureOperation("green", 0, 0,
                        "minecraft:poppy", 1,
                        CityLandUseSurfacePrintPlan.FeatureKind.GREEN_PLANT,
                        CityLandUseSurfacePrintPlan.HorizontalFacing.NONE)));
        FakeWorld world = new FakeWorld();
        world.replaceable.put("0,65,0", false);

        CityLandUseChunkExecutor.ExecutionResult result = executor.execute(fragment, world,
                CityLandUseChunkExecutor.GenerationEligibility.CONTROLLED_D7_BACKFILL);

        assertEquals(CityLandUseChunkExecutor.Status.FAILED, result.status());
        assertEquals("CITY_LAND_USE_FEATURE_TARGET_OCCUPIED", result.reasonCode());
        assertTrue(world.writes.isEmpty());
    }

    @Test
    void writesNonConnectionStateExactlyWithoutNeighbourShapePreResolution() {
        WorldgenLikeBlockStateWorld world = new WorldgenLikeBlockStateWorld();
        BlockPos crop = new BlockPos(15, 65, 0);

        assertTrue(CityLandUseChunkExecutor.writeExactBlockState(
                world, crop, TestBlockState.WHEAT, 0));

        assertEquals(TestBlockState.WHEAT, world.getBlockState(crop));
        assertEquals(0, world.neighbourShapeUpdateCount);
    }

    @Test
    void rejectsWriteReportedSuccessfulWhenActualStateDiffersFromRequest() {
        WorldgenLikeBlockStateWorld world = new WorldgenLikeBlockStateWorld();
        BlockPos crop = new BlockPos(15, 65, 0);
        world.replaceNextWriteWithAir = true;

        assertFalse(CityLandUseChunkExecutor.writeExactBlockState(
                world, crop, TestBlockState.WHEAT, 0));

        assertEquals(TestBlockState.AIR, world.getBlockState(crop));
    }

    @Test
    void finalizesLampFeatureConnectionsEvenWithoutBoundaryOperations() {
        CityLandUseChunkCompiler.ChunkFragment fragment = new CityLandUseChunkCompiler.ChunkFragment(
                CityLandUseChunkCompiler.RESULT_SCHEMA, "city", "area-hash", "palette-hash",
                0, 0, 1, 0, 0, 0, null, List.of(), List.of(), List.of(), List.of(
                new CityLandUseChunkCompiler.FeatureOperation("lamp", 15, 8,
                        "minecraft:dark_oak_fence", 2,
                        CityLandUseSurfacePrintPlan.FeatureKind.ROAD_LAMP,
                        CityLandUseSurfacePrintPlan.HorizontalFacing.NONE)));
        FakeWorld world = new FakeWorld();
        assertEquals(CityLandUseChunkExecutor.Status.APPLIED, executor.execute(fragment, world,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES).status());
        assertTrue(world.finalized.contains(new CityLandUseChunkExecutor.BlockPosition(15, 66, 8)));
    }

    @Test
    void acceptsAlreadyAppliedStateWithoutTreatingNoChangeAsFailure() {
        WorldgenLikeBlockStateWorld world = new WorldgenLikeBlockStateWorld();
        BlockPos pos = new BlockPos(15, 65, 0);
        world.states.put(pos, TestBlockState.WHEAT);
        world.failNextWriteAt = pos;
        assertTrue(CityLandUseChunkExecutor.writeExactBlockState(world, pos, TestBlockState.WHEAT, 0));
        assertEquals(pos, world.failNextWriteAt, "No redundant write or neighbour updates");
        assertTrue(CityLandUseChunkExecutor.writeExactBlockState(world, pos.above(), TestBlockState.AIR, 0));
    }

    @Test
    void rejectsUnwritableOrUnchangedWrongState() {
        WorldgenLikeBlockStateWorld world = new WorldgenLikeBlockStateWorld();
        BlockPos pos = new BlockPos(15, 65, 0);
        world.unwritable = pos;
        assertFalse(CityLandUseChunkExecutor.writeExactBlockState(world, pos, TestBlockState.AIR, 0));
        world.unwritable = null;
        world.failNextWriteAt = pos;
        assertFalse(CityLandUseChunkExecutor.writeExactBlockState(world, pos, TestBlockState.WHEAT, 0));
    }

    @Test
    void finalizesWholeFenceBatchAfterAllRawPlacements() {
        WorldgenLikeBlockStateWorld world = new WorldgenLikeBlockStateWorld();
        List<BlockPos> line = new ArrayList<>();
        for (int x = 8; x < 28; x++) {
            BlockPos pos = new BlockPos(x, 65, 0);
            line.add(pos);
            assertTrue(CityLandUseChunkExecutor.writeExactBlockState(world, pos, TestBlockState.FENCE, 0));
        }

        assertTrue(CityLandUseChunkExecutor.finalizeHorizontalConnections(world, line).success());

        for (int index = 0; index < line.size() - 1; index++) {
            assertTrue(world.getBlockState(line.get(index)).east());
            assertTrue(world.getBlockState(line.get(index + 1)).west());
        }
    }

    @Test
    void laterOwnerFinalizationReconcilesEarlierOwnerAcrossChunkSeam() {
        WorldgenLikeBlockStateWorld world = new WorldgenLikeBlockStateWorld();
        BlockPos west = new BlockPos(15, 65, 0);
        BlockPos east = west.east();
        assertTrue(CityLandUseChunkExecutor.writeExactBlockState(world, west, TestBlockState.FENCE, 0));
        assertTrue(CityLandUseChunkExecutor.finalizeHorizontalConnections(world, List.of(west)).success());
        assertFalse(world.getBlockState(west).east());

        assertTrue(CityLandUseChunkExecutor.writeExactBlockState(world, east, TestBlockState.FENCE, 0));
        assertTrue(CityLandUseChunkExecutor.finalizeHorizontalConnections(world, List.of(east)).success());

        assertTrue(world.getBlockState(west).east());
        assertTrue(world.getBlockState(east).west());
    }

    @Test
    void restoresConnectionStatesWhenBatchFinalizationFails() {
        WorldgenLikeBlockStateWorld world = new WorldgenLikeBlockStateWorld();
        BlockPos west = new BlockPos(15, 65, 0);
        BlockPos east = west.east();
        assertTrue(CityLandUseChunkExecutor.writeExactBlockState(world, west, TestBlockState.FENCE, 0));
        assertTrue(CityLandUseChunkExecutor.writeExactBlockState(world, east, TestBlockState.FENCE, 0));
        world.unwritable = west;

        assertFalse(CityLandUseChunkExecutor.finalizeHorizontalConnections(world, List.of(west, east)).success());

        assertEquals(TestBlockState.FENCE, world.getBlockState(east));
        assertFalse(world.getBlockState(west).east());
        assertFalse(world.getBlockState(east).west());
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
        private final List<String> featureWrites = new ArrayList<>();
        private final List<String> restores = new ArrayList<>();
        private final List<CityLandUseChunkExecutor.BlockPosition> finalized = new ArrayList<>();
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
        public boolean setFeatureBlock(int worldX, int y, int worldZ, String blockId,
                                       CityLandUseSurfacePrintPlan.FeatureKind kind,
                                       CityLandUseSurfacePrintPlan.HorizontalFacing facing) {
            featureWrites.add(worldX + "," + y + "," + worldZ + "=" + kind + ':' + facing);
            return setBlock(worldX, y, worldZ, blockId);
        }

        @Override
        public boolean restoreBlock(int worldX, int y, int worldZ, Object snapshot) {
            restores.add(worldX + "," + y + "," + worldZ + "=" + snapshot);
            return true;
        }

        @Override
        public CityLandUseChunkExecutor.BoundaryFinalizeResult finalizeBoundaryConnections(
                List<CityLandUseChunkExecutor.BlockPosition> positions) {
            finalized.addAll(positions);
            return new CityLandUseChunkExecutor.BoundaryFinalizeResult(true, true);
        }
    }

    private static final class WorldgenLikeBlockStateWorld
            implements CityLandUseChunkExecutor.BlockStateWriteAccess<TestBlockState> {
        private final Map<BlockPos, TestBlockState> states = new HashMap<>();
        private BlockPos failNextWriteAt;
        private BlockPos unwritable;
        private boolean replaceNextWriteWithAir;
        private int neighbourShapeUpdateCount;

        @Override
        public TestBlockState getBlockState(BlockPos pos) {
            return states.getOrDefault(pos, TestBlockState.AIR);
        }

        @Override
        public TestBlockState updateFromNeighbourShapes(TestBlockState state, BlockPos pos) {
            neighbourShapeUpdateCount++;
            if (state.equals(TestBlockState.WHEAT)) {
                return TestBlockState.AIR;
            }
            if (!state.fence()) {
                return state;
            }
            return new TestBlockState("fence",
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
            if (replaceNextWriteWithAir) {
                replaceNextWriteWithAir = false;
                states.put(pos.immutable(), TestBlockState.AIR);
                return true;
            }
            states.put(pos.immutable(), state);
            return true;
        }
    }

    private record TestBlockState(String blockId,
                                  boolean north,
                                  boolean east,
                                  boolean south,
                                  boolean west) {
        private static final TestBlockState AIR = new TestBlockState("air", false, false, false, false);
        private static final TestBlockState WHEAT = new TestBlockState("wheat", false, false, false, false);
        private static final TestBlockState FENCE = new TestBlockState("fence", false, false, false, false);

        private boolean fence() {
            return "fence".equals(blockId);
        }
    }
}
