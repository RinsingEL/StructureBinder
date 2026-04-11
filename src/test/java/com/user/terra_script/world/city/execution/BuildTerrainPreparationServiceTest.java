package com.user.terra_script.world.city.execution;

import com.user.terra_script.world.StructureInjector;
import com.user.terra_script.world.city.stage.c9.CityC9BuildQueue;
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

    @Test
    void prepareDoesNotExcavateDoneParentBounds() {
        BuildExecutionTestSupport.FakeWorldAccess world = new BuildExecutionTestSupport.FakeWorldAccess();
        BlockPos parentPos = new BlockPos(10, 64, 20);
        BlockPos candidatePos = new BlockPos(11, 64, 20);
        world.blocks.put(parentPos, BuildBlockSnapshot.of("test:stone_bricks", false, false, null));
        world.blocks.put(candidatePos, BuildBlockSnapshot.of("test:dirt", false, false, null));

        CityC9BuildQueue.BuildTask parent = BuildExecutionTestSupport.task("city_demo|ba_1|parent");
        parent.node_id = "parent";
        parent.status = CityC9BuildQueue.Status.DONE.name();
        parent.template_id = "";
        parent.x = 10;
        parent.y = 64;
        parent.z = 20;
        parent.placement_node = new com.user.terra_script.world.city.stage.c8.CityC8Stages.PlacementNode();
        parent.placement_node.footprint_min_x = 10;
        parent.placement_node.footprint_min_z = 20;
        parent.placement_node.footprint_max_x = 10;
        parent.placement_node.footprint_max_z = 20;

        CityC9BuildQueue.BuildTask child = BuildExecutionTestSupport.task("city_demo|ba_1|child");
        child.node_id = "child";
        child.x = 10;
        child.y = 64;
        child.z = 20;

        BuildExecutionContext context = new BuildExecutionContext(
                world,
                BuildExecutionTestSupport.queue(parent, child),
                child,
                BuildTaskDebugLogger.NO_OP,
                null
        );

        TerrainPreparationResult result = new BuildTerrainPreparationService().prepare(
                context,
                StructureInjector.PlacementBounds.of(10, 64, 20, 12, 65, 21)
        );

        assertEquals("test:stone_bricks", world.blockSnapshot(parentPos).blockId());
        assertTrue(world.blockSnapshot(candidatePos).air());
        assertEquals(1, result.embeddedExcavate().totalClearedBlocks());
    }
}
