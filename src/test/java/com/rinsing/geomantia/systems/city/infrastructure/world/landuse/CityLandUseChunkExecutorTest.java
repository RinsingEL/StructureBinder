package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

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

    private static CityLandUseChunkCompiler.ChunkFragment fragment() {
        return new CityLandUseChunkCompiler.ChunkFragment(CityLandUseChunkCompiler.RESULT_SCHEMA,
                "city", "hash", "palette", 0, 0, 4, 0, 0, 0,
                List.of(new CityLandUseChunkCompiler.SurfaceOperation("area", "plaza", 0, 0,
                                "minecraft:stone_bricks"),
                        new CityLandUseChunkCompiler.SurfaceOperation("area", "plaza", 1, 0,
                                "minecraft:stone_bricks")),
                List.of(new CityLandUseChunkCompiler.BoundaryOperation("area", "plaza", 2, 0,
                                "minecraft:oak_fence"),
                        new CityLandUseChunkCompiler.BoundaryOperation("area", "plaza", 3, 0,
                                "minecraft:oak_fence")));
    }

    private static final class FakeWorld implements CityLandUseChunkExecutor.ExecutionWorld {
        private final Map<String, Boolean> natural = new HashMap<>();
        private final Map<String, Boolean> replaceable = new HashMap<>();
        private final List<String> writes = new ArrayList<>();
        private final List<String> restores = new ArrayList<>();
        private int failWriteIndex = -1;
        private int sampleCount;

        @Override
        public CityLandUseChunkExecutor.ColumnSample sampleColumn(int worldX, int worldZ) {
            sampleCount++;
            return new CityLandUseChunkExecutor.ColumnSample(64, "minecraft:grass_block",
                    natural.getOrDefault(worldX + "," + worldZ, true));
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
}
