package com.user.terra_script.world.city.execution;

import com.user.terra_script.world.StructureInjector;
import com.user.terra_script.world.city.stage.c9.CityC9BuildQueue;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildExecutionPipelineTest {
    @Test
    void pipelineSkipsWhenChunkIsNotLoaded() {
        BuildExecutionTestSupport.FakeWorldAccess world = new BuildExecutionTestSupport.FakeWorldAccess();
        world.chunkLoaded = false;
        CityC9BuildQueue.BuildTask task = BuildExecutionTestSupport.task("city_demo|ba_1|node_1");
        BuildExecutionTestSupport.RecordingLogger logger = new BuildExecutionTestSupport.RecordingLogger();

        TaskExecutionResult result = BuildExecutionPipeline.createDefault().execute(
                new BuildExecutionContext(world, BuildExecutionTestSupport.queue(task), task, logger, null)
        );

        assertEquals(TaskExecutionResult.Outcome.SKIPPED, result.outcome());
        assertEquals(CityC9BuildQueue.Status.PLANNED.name(), task.status);
        assertEquals("chunk_not_loaded", logger.entries.get(0).details().get("reason").getAsString());
    }

    @Test
    void pipelineRetriesWhenParentIsNotDone() {
        BuildExecutionTestSupport.FakePlacementGateway gateway = new BuildExecutionTestSupport.FakePlacementGateway();
        BuildExecutionPipeline pipeline = new BuildExecutionPipeline(
                new BuildChunkGate(),
                new BuildRuntimeValidator(gateway),
                new BuildTerrainPreparationService(),
                new BuildPlacementService(gateway)
        );
        BuildExecutionTestSupport.FakeWorldAccess world = new BuildExecutionTestSupport.FakeWorldAccess();
        CityC9BuildQueue.BuildTask parent = BuildExecutionTestSupport.task("city_demo|ba_1|parent");
        parent.node_id = "parent";
        parent.status = CityC9BuildQueue.Status.READY.name();
        CityC9BuildQueue.BuildTask child = BuildExecutionTestSupport.task("city_demo|ba_1|child");
        child.node_id = "child";
        child.parent_node_id = "parent";

        TaskExecutionResult result = pipeline.execute(new BuildExecutionContext(
                world,
                BuildExecutionTestSupport.queue(parent, child),
                child,
                new BuildExecutionTestSupport.RecordingLogger(),
                null
        ));

        assertEquals(TaskExecutionResult.Outcome.RETRIED, result.outcome());
        assertEquals("waiting_for_parent", child.last_error);
        assertEquals(CityC9BuildQueue.Status.READY.name(), child.status);
    }

    @Test
    void pipelineDoesNotTreatDoneParentAsRuntimeCollision() {
        BuildExecutionTestSupport.FakePlacementGateway gateway = new BuildExecutionTestSupport.FakePlacementGateway();
        BuildExecutionPipeline pipeline = new BuildExecutionPipeline(
                new BuildChunkGate(),
                new BuildRuntimeValidator(gateway),
                new BuildTerrainPreparationService(),
                new BuildPlacementService(gateway)
        );
        BuildExecutionTestSupport.FakeWorldAccess world = new BuildExecutionTestSupport.FakeWorldAccess();
        CityC9BuildQueue.BuildTask parent = BuildExecutionTestSupport.task("city_demo|ba_1|parent");
        parent.node_id = "parent";
        parent.status = CityC9BuildQueue.Status.DONE.name();
        CityC9BuildQueue.BuildTask child = BuildExecutionTestSupport.task("city_demo|ba_1|child");
        child.node_id = "child";
        child.parent_node_id = "parent";
        child.x = parent.x;
        child.y = parent.y;
        child.z = parent.z;

        TaskExecutionResult result = pipeline.execute(new BuildExecutionContext(
                world,
                BuildExecutionTestSupport.queue(parent, child),
                child,
                new BuildExecutionTestSupport.RecordingLogger(),
                null
        ));

        assertEquals(TaskExecutionResult.Outcome.COMPLETED, result.outcome());
        assertEquals(CityC9BuildQueue.Status.DONE.name(), child.status);
    }

    @Test
    void pipelineBlocksWhenDoneTaskCollidesAtRuntime() {
        BuildExecutionTestSupport.FakePlacementGateway gateway = new BuildExecutionTestSupport.FakePlacementGateway();
        BuildExecutionPipeline pipeline = new BuildExecutionPipeline(
                new BuildChunkGate(),
                new BuildRuntimeValidator(gateway),
                new BuildTerrainPreparationService(),
                new BuildPlacementService(gateway)
        );
        BuildExecutionTestSupport.FakeWorldAccess world = new BuildExecutionTestSupport.FakeWorldAccess();
        CityC9BuildQueue.BuildTask existing = BuildExecutionTestSupport.task("city_demo|ba_1|existing");
        existing.node_id = "existing";
        existing.status = CityC9BuildQueue.Status.DONE.name();
        CityC9BuildQueue.BuildTask task = BuildExecutionTestSupport.task("city_demo|ba_1|candidate");
        task.retry_count = CityC9BuildQueue.MAX_RETRIES - 1;

        TaskExecutionResult result = pipeline.execute(new BuildExecutionContext(
                world,
                BuildExecutionTestSupport.queue(existing, task),
                task,
                new BuildExecutionTestSupport.RecordingLogger(),
                null
        ));

        assertEquals(TaskExecutionResult.Outcome.BLOCKED, result.outcome());
        assertEquals("runtime_footprint_collision", task.last_error);
        assertEquals(CityC9BuildQueue.Status.BLOCKED.name(), task.status);
    }

    @Test
    void pipelineMarksTaskDoneAndLogsTerrainStagesOnSuccess() {
        BuildExecutionTestSupport.FakePlacementGateway gateway = new BuildExecutionTestSupport.FakePlacementGateway();
        gateway.outcome = StructureInjector.PlacementOutcome.of(
                true,
                gateway.bounds,
                2,
                List.of(new BlockPos(10, 64, 20), new BlockPos(11, 64, 20))
        );
        BuildExecutionPipeline pipeline = new BuildExecutionPipeline(
                new BuildChunkGate(),
                new BuildRuntimeValidator(gateway),
                new BuildTerrainPreparationService(),
                new BuildPlacementService(gateway)
        );
        BuildExecutionTestSupport.FakeWorldAccess world = new BuildExecutionTestSupport.FakeWorldAccess();
        world.blocks.put(new BlockPos(10, 64, 20), BuildBlockSnapshot.of("test:oak_log", false, false, "trunk"));
        world.blocks.put(new BlockPos(10, 65, 20), BuildBlockSnapshot.of("test:stone", false, false, null));
        CityC9BuildQueue.BuildTask task = BuildExecutionTestSupport.task("city_demo|ba_1|node_1");
        BuildExecutionTestSupport.RecordingLogger logger = new BuildExecutionTestSupport.RecordingLogger();

        TaskExecutionResult result = pipeline.execute(new BuildExecutionContext(
                world,
                BuildExecutionTestSupport.queue(task),
                task,
                logger,
                null
        ));

        assertEquals(TaskExecutionResult.Outcome.COMPLETED, result.outcome());
        assertEquals(CityC9BuildQueue.Status.DONE.name(), task.status);
        assertTrue(logger.entries.stream().anyMatch(entry -> "soft_obstacle_clear".equals(entry.details().get("stage").getAsString())));
        assertTrue(logger.entries.stream().anyMatch(entry -> "embedded_excavate".equals(entry.details().get("stage").getAsString())));
        assertTrue(logger.entries.stream().anyMatch(entry -> "post_cleanup".equals(entry.details().get("stage").getAsString())));
        assertTrue(logger.entries.stream().anyMatch(entry -> "place_structure".equals(entry.details().get("stage").getAsString())));
        assertTrue(logger.entries.stream().anyMatch(entry -> "completed".equals(entry.level())));
    }

    @Test
    void pipelinePreservesClearedSiteWhenPlacementFails() {
        BuildExecutionTestSupport.FakePlacementGateway gateway = new BuildExecutionTestSupport.FakePlacementGateway();
        gateway.outcome = StructureInjector.PlacementOutcome.of(false, gateway.bounds, 0, List.of());
        BuildExecutionPipeline pipeline = new BuildExecutionPipeline(
                new BuildChunkGate(),
                new BuildRuntimeValidator(gateway),
                new BuildTerrainPreparationService(),
                new BuildPlacementService(gateway)
        );
        BuildExecutionTestSupport.FakeWorldAccess world = new BuildExecutionTestSupport.FakeWorldAccess();
        world.blocks.put(new BlockPos(10, 64, 20), BuildBlockSnapshot.of("test:oak_log", false, false, "trunk"));
        CityC9BuildQueue.BuildTask task = BuildExecutionTestSupport.task("city_demo|ba_1|node_1");

        TaskExecutionResult result = pipeline.execute(new BuildExecutionContext(
                world,
                BuildExecutionTestSupport.queue(task),
                task,
                new BuildExecutionTestSupport.RecordingLogger(),
                null
        ));

        assertEquals(TaskExecutionResult.Outcome.RETRIED, result.outcome());
        assertTrue(world.blockSnapshot(new BlockPos(10, 64, 20)).air());
        assertEquals("structure_place_failed", task.last_error);
    }
}
