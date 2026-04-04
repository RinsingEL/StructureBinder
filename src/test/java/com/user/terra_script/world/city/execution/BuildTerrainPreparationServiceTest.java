package com.user.terra_script.world.city.execution;

import com.user.terra_script.world.StructureInjector;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildTerrainPreparationServiceTest {
    @Test
    void prepareClearsSoftObstaclesAndEmbeddedBlocksButKeepsProtectedBlocks() {
        BuildExecutionTestSupport.FakeWorldAccess world = new BuildExecutionTestSupport.FakeWorldAccess();
        world.blocks.put(new BlockPos(10, 64, 20), BuildBlockSnapshot.of("test:oak_log", false, false, "trunk"));
        world.blocks.put(new BlockPos(10, 65, 20), BuildBlockSnapshot.of("test:oak_leaves", false, false, "foliage"));
        world.blocks.put(new BlockPos(10, 66, 20), BuildBlockSnapshot.of("test:dandelion", false, false, "replaceable"));
        world.blocks.put(new BlockPos(11, 64, 20), BuildBlockSnapshot.of("test:stone", false, false, null));
        world.blocks.put(new BlockPos(11, 65, 20), BuildBlockSnapshot.of("test:bedrock", false, true, null));

        BuildExecutionContext context = new BuildExecutionContext(
                world,
                BuildExecutionTestSupport.queue(),
                BuildExecutionTestSupport.task("city_demo|ba_1|node_1"),
                BuildTaskDebugLogger.NO_OP,
                null
        );

        TerrainPreparationResult result = new BuildTerrainPreparationService().prepare(
                context,
                StructureInjector.PlacementBounds.of(10, 64, 20, 12, 67, 21)
        );

        assertTrue(world.blockSnapshot(new BlockPos(10, 64, 20)).air());
        assertTrue(world.blockSnapshot(new BlockPos(10, 65, 20)).air());
        assertTrue(world.blockSnapshot(new BlockPos(10, 66, 20)).air());
        assertTrue(world.blockSnapshot(new BlockPos(11, 64, 20)).air());
        assertEquals("test:bedrock", world.blockSnapshot(new BlockPos(11, 65, 20)).blockId());
        assertEquals(3, result.softObstacleClear().totalClearedBlocks());
        assertEquals(1, result.embeddedExcavate().totalClearedBlocks());
        assertEquals(4, result.totalClearedBlocks());
    }

    @Test
    void terrainStatsExposeSamplesAndCountsForDebugLogging() {
        TerrainClearStats stats = new TerrainClearStats("soft_obstacle_clear");
        stats.record(new BlockPos(1, 2, 3), "test:oak_log", "trunk");
        stats.record(new BlockPos(2, 3, 4), "test:oak_log", "trunk");

        var json = stats.toJson();

        assertEquals(2, json.get("total_cleared_blocks").getAsInt());
        assertEquals(2, json.getAsJsonObject("reason_counts").get("trunk").getAsInt());
        assertEquals(1, json.getAsJsonArray("top_block_counts").size());
        assertEquals(2, json.getAsJsonObject("sample_positions_by_reason").getAsJsonArray("trunk").size());
    }
}
