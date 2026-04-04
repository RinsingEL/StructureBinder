package com.user.terra_script.world.city.execution;

import com.google.gson.JsonObject;
import com.user.terra_script.event.ServerTickTracker;
import com.user.terra_script.world.StructureInjector;
import com.user.terra_script.world.city.stage.c9.CityC9BuildQueue;

public final class BuildExecutionPipeline {
    private final BuildChunkGate chunkGate;
    private final BuildRuntimeValidator runtimeValidator;
    private final BuildTerrainPreparationService terrainPreparationService;
    private final BuildPlacementService placementService;

    public BuildExecutionPipeline(
            BuildChunkGate chunkGate,
            BuildRuntimeValidator runtimeValidator,
            BuildTerrainPreparationService terrainPreparationService,
            BuildPlacementService placementService
    ) {
        this.chunkGate = chunkGate;
        this.runtimeValidator = runtimeValidator;
        this.terrainPreparationService = terrainPreparationService;
        this.placementService = placementService;
    }

    public static BuildExecutionPipeline createDefault() {
        StructurePlacementGateway gateway = new DefaultStructurePlacementGateway();
        return new BuildExecutionPipeline(
                new BuildChunkGate(),
                new BuildRuntimeValidator(gateway),
                new BuildTerrainPreparationService(),
                new BuildPlacementService(gateway)
        );
    }

    public TaskExecutionResult execute(BuildExecutionContext context) {
        if (context == null || context.task() == null) {
            return TaskExecutionResult.skipped("missing_execution_context");
        }

        BuildChunkGate.GateDecision gateDecision = chunkGate.evaluate(context);
        if (!gateDecision.shouldProceed()) {
            JsonObject details = new JsonObject();
            details.addProperty("stage", "precheck");
            details.addProperty("reason", gateDecision.reasonCode());
            context.logger().progress("C9 build task skipped by precheck.", details);
            return TaskExecutionResult.skipped(gateDecision.reasonCode());
        }

        CityC9BuildQueue.BuildTask task = context.task();
        task.status = CityC9BuildQueue.Status.BUILDING.name();
        task.updated_at_tick = ServerTickTracker.currentTick();
        context.persist();

        BuildRuntimeValidator.ValidationResult validation = runtimeValidator.validate(context);
        if (!validation.ok()) {
            return fail(context, validation.errorCode(), null);
        }

        TerrainPreparationResult terrain = terrainPreparationService.prepare(context, validation.bounds());
        logTerrainStage(context, terrain.softObstacleClear());
        logTerrainStage(context, terrain.embeddedExcavate());

        StructureInjector.PlacementOutcome placementOutcome = placementService.place(context);
        if (placementOutcome.bounds != null) {
            terrain.setBounds(placementOutcome.bounds);
        }
        JsonObject placementDetails = new JsonObject();
        placementDetails.addProperty("stage", "place_structure");
        placementDetails.addProperty("placed", placementOutcome.placed);
        placementDetails.addProperty("cleared_jigsaw_blocks", placementOutcome.clearedJigsawBlocks);
        if (placementOutcome.bounds != null) {
            placementDetails.add("placement_bounds", terrain.toJson().get("placement_bounds"));
        }
        context.logger().progress("C9 placement stage completed.", placementDetails);
        if (placementOutcome.clearedJigsawSamples != null) {
            for (net.minecraft.core.BlockPos sample : placementOutcome.clearedJigsawSamples) {
                terrain.postCleanup().record(sample, "minecraft:jigsaw", "jigsaw");
            }
        }
        int extraJigsaws = placementOutcome.clearedJigsawBlocks - terrain.postCleanup().totalClearedBlocks();
        for (int i = 0; i < extraJigsaws; i++) {
            terrain.postCleanup().record(null, "minecraft:jigsaw", "jigsaw");
        }
        logTerrainStage(context, terrain.postCleanup());

        if (!placementOutcome.placed) {
            return fail(context, "structure_place_failed", terrain);
        }

        task.status = CityC9BuildQueue.Status.DONE.name();
        task.last_error = null;
        task.last_error_message = null;
        task.updated_at_tick = ServerTickTracker.currentTick();
        context.persist();

        JsonObject details = terrain.toJson();
        details.addProperty("stage", "post_validate");
        details.addProperty("result", "done");
        details.addProperty("placed", true);
        details.addProperty("final_status", task.status);
        context.logger().completed("C9 build task completed.", details);
        return TaskExecutionResult.completed(terrain);
    }

    private TaskExecutionResult fail(BuildExecutionContext context, String errorCode, TerrainPreparationResult terrain) {
        FailureDecision decision = FailureDecision.fromError(context.task(), errorCode);
        decision.apply(context.task());
        context.persist();

        JsonObject details = terrain != null ? terrain.toJson() : new JsonObject();
        details.addProperty("stage", "failure_policy");
        details.addProperty("error_code", decision.errorCode());
        details.addProperty("final_status", context.task().status);
        details.addProperty("retry_count", context.task().retry_count);
        details.addProperty("error_message", decision.localizedMessage());
        if (decision.outcome() == TaskExecutionResult.Outcome.BLOCKED) {
            context.logger().failed("C9 build task blocked.", details);
            return TaskExecutionResult.blocked(decision.errorCode(), terrain);
        }
        context.logger().progress("C9 build task scheduled for retry.", details);
        return TaskExecutionResult.retried(decision.errorCode(), terrain);
    }

    private static void logTerrainStage(BuildExecutionContext context, TerrainClearStats stats) {
        JsonObject details = stats.toJson();
        details.addProperty("result", "ok");
        context.logger().progress("C9 terrain preparation stage completed.", details);
    }
}
