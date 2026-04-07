package com.user.terra_script.world.city.execution;

import com.user.terra_script.world.StructureInjector;
import com.user.terra_script.world.city.stage.c8.CityC8Stages;
import com.user.terra_script.world.city.stage.c8.CityVanillaJigsawAdapterService;
import com.user.terra_script.world.city.stage.c9.CityC9BuildQueue;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SolvedPlacementExecutionServiceTest {
    @Test
    void executeSynthesizesDoneParentAndPreservesChildJigsaws() {
        SolvedPlacementExecutionService service = new SolvedPlacementExecutionService(new BuildExecutionTestSupport.FakePlacementGateway());

        BuildExecutionTestSupport.FakeWorldAccess world = new BuildExecutionTestSupport.FakeWorldAccess();
        world.blocks.put(new BlockPos(10, 64, 20), BuildBlockSnapshot.of("test:oak_log", false, false, "trunk"));

        CityC8Stages.FoundationItem foundation = new CityC8Stages.FoundationItem();
        foundation.base_y = 64;
        foundation.group_id = "g_market_06";
        foundation.build_area_id = "ba_1";
        foundation.build_area_numeric_id = 1;

        CityC8Stages.PlacementNode parent = new CityC8Stages.PlacementNode();
        parent.node_id = "parent";
        parent.template_id = "test:parent";
        parent.x = 6;
        parent.y = 64;
        parent.z = 20;
        parent.rotation = 0;

        CityC8Stages.PlacementNode child = new CityC8Stages.PlacementNode();
        child.node_id = "child";
        child.parent_node_id = "parent";
        child.template_id = "test:child";
        child.x = 10;
        child.y = 64;
        child.z = 20;
        child.rotation = 0;

        CityC9BuildQueue.BuildTask task = SolvedPlacementExecutionService.buildTask("city_demo", "g_market_06", foundation, child);
        CityVanillaJigsawAdapterService.VanillaPlacementDescriptor descriptor = new CityVanillaJigsawAdapterService.VanillaPlacementDescriptor();
        descriptor.bounds = StructureInjector.PlacementBounds.of(10, 64, 20, 12, 67, 22);
        descriptor.start_pos = new BlockPos(10, 64, 20);
        final boolean[] keepJigsaws = {false};
        descriptor.piece_placer = (access, startPos, keep) -> {
            keepJigsaws[0] = keep;
            return true;
        };

        SolvedPlacementExecutionService.ExecutionResult result = service.execute(
                world,
                new CityC9BuildQueue.BuildQueue(),
                task,
                parent,
                descriptor,
                new BuildExecutionTestSupport.RecordingLogger()
        );

        assertEquals(TaskExecutionResult.Outcome.COMPLETED, result.result.outcome());
        assertEquals(CityC9BuildQueue.Status.DONE.name(), result.task.status);
        assertTrue(keepJigsaws[0]);
        assertTrue(world.blockSnapshot(new BlockPos(10, 64, 20)).air());
    }

    @Test
    void executeReturnsRuntimeFailureWithoutMasqueradingAsSolveFailure() {
        SolvedPlacementExecutionService service = new SolvedPlacementExecutionService(new BuildExecutionTestSupport.FakePlacementGateway());

        CityC8Stages.FoundationItem foundation = new CityC8Stages.FoundationItem();
        foundation.base_y = 64;
        foundation.group_id = "g_market_06";
        foundation.build_area_id = "ba_1";
        foundation.build_area_numeric_id = 1;

        CityC8Stages.PlacementNode child = new CityC8Stages.PlacementNode();
        child.node_id = "child_fail";
        child.template_id = "test:child";
        child.x = 10;
        child.y = 64;
        child.z = 20;
        child.rotation = 0;

        CityC9BuildQueue.BuildTask task = SolvedPlacementExecutionService.buildTask("city_demo", "g_market_06", foundation, child);
        CityVanillaJigsawAdapterService.VanillaPlacementDescriptor descriptor = new CityVanillaJigsawAdapterService.VanillaPlacementDescriptor();
        descriptor.bounds = StructureInjector.PlacementBounds.of(10, 64, 20, 12, 67, 22);
        descriptor.start_pos = new BlockPos(10, 64, 20);
        descriptor.piece_placer = (access, startPos, keep) -> false;

        SolvedPlacementExecutionService.ExecutionResult result = service.execute(
                new BuildExecutionTestSupport.FakeWorldAccess(),
                new CityC9BuildQueue.BuildQueue(),
                task,
                null,
                descriptor,
                null
        );

        assertEquals(TaskExecutionResult.Outcome.RETRIED, result.result.outcome());
        assertEquals("structure_place_failed", result.task.last_error);
    }
}
