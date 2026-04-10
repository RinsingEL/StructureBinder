package com.user.terra_script.world.city.execution;

import com.user.terra_script.world.StructureInjector;
import com.user.terra_script.world.city.stage.StructurePlacementContract;
import com.user.terra_script.world.city.stage.c8.CityC8Stages;
import com.user.terra_script.world.city.stage.c8.CityVanillaJigsawAdapterService;
import com.user.terra_script.world.city.stage.c9.CityC9BuildQueue;

import java.util.ArrayList;
import java.util.List;

public final class SolvedPlacementExecutionService {
    private final StructurePlacementGateway fallbackGateway;

    public SolvedPlacementExecutionService(StructurePlacementGateway fallbackGateway) {
        this.fallbackGateway = fallbackGateway != null ? fallbackGateway : new DefaultStructurePlacementGateway();
    }

    public static SolvedPlacementExecutionService createDefault() {
        return new SolvedPlacementExecutionService(new DefaultStructurePlacementGateway());
    }

    public ExecutionResult execute(
            BuildWorldAccess world,
            CityC9BuildQueue.BuildQueue existingQueue,
            CityC9BuildQueue.BuildTask task,
            CityC8Stages.PlacementNode parentPlacement,
            CityVanillaJigsawAdapterService.VanillaPlacementDescriptor descriptor,
            BuildTaskDebugLogger logger
    ) {
        CityC9BuildQueue.BuildQueue transientQueue = transientQueue(existingQueue, task, parentPlacement);
        StructurePlacementGateway gateway = new SolvedPlacementGateway(task, descriptor, fallbackGateway);
        BuildExecutionPipeline pipeline = new BuildExecutionPipeline(
                new BuildChunkGate(),
                new BuildRuntimeValidator(gateway),
                new BuildTerrainPreparationService(),
                new BuildPlacementService(gateway)
        );
        TaskExecutionResult result = pipeline.execute(new BuildExecutionContext(
                world,
                transientQueue,
                task,
                logger != null ? logger : BuildTaskDebugLogger.NO_OP,
                null
        ));
        return new ExecutionResult(transientQueue, task, result);
    }

    public static CityC9BuildQueue.BuildTask buildTask(
            String cityId,
            String groupId,
            CityC8Stages.FoundationItem foundation,
            CityC8Stages.PlacementNode placement
    ) {
        CityC9BuildQueue.BuildTask task = new CityC9BuildQueue.BuildTask();
        task.city_id = cityId;
        task.group_id = groupId != null ? groupId : (foundation != null ? foundation.group_id : null);
        task.build_area_id = foundation != null ? foundation.build_area_id : null;
        task.build_area_numeric_id = foundation != null ? foundation.build_area_numeric_id : 0;
        task.node_id = placement != null && placement.node_id != null && !placement.node_id.isBlank()
                ? placement.node_id
                : "solver_node";
        task.task_id = CityC9BuildQueue.taskId(task.city_id, task.build_area_id, task.node_id);
        task.template_id = placement != null ? placement.template_id : null;
        task.x = placement != null ? placement.x : 0;
        task.y = resolvePlacementOriginY(foundation, placement);
        task.z = placement != null ? placement.z : 0;
        task.rotation = placement != null ? placement.rotation : 0;
        task.chunk_x = Math.floorDiv(task.x, 16);
        task.chunk_z = Math.floorDiv(task.z, 16);
        task.priority = placement != null && placement.build_order != null ? Math.max(0, 1000 - placement.build_order) : 100;
        task.status = CityC9BuildQueue.Status.READY.name();
        task.build_order = placement != null ? placement.build_order : null;
        task.parent_node_id = placement != null ? placement.parent_node_id : null;
        task.terminalized = placement != null && placement.terminalized;
        task.placement_node = placement;
        return task;
    }

    private static CityC9BuildQueue.BuildQueue transientQueue(
            CityC9BuildQueue.BuildQueue existingQueue,
            CityC9BuildQueue.BuildTask task,
            CityC8Stages.PlacementNode parentPlacement
    ) {
        CityC9BuildQueue.BuildQueue queue = new CityC9BuildQueue.BuildQueue();
        queue.city_id = task != null ? task.city_id : (existingQueue != null ? existingQueue.city_id : null);
        queue.generated_at_epoch_ms = existingQueue != null ? existingQueue.generated_at_epoch_ms : System.currentTimeMillis();
        queue.updated_at_epoch_ms = System.currentTimeMillis();
        if (existingQueue != null && existingQueue.tasks != null) {
            queue.tasks = new ArrayList<>(existingQueue.tasks);
        }
        if (task != null) {
            queue.tasks.removeIf(existing -> existing != null && task.task_id != null && task.task_id.equals(existing.task_id));
            queue.tasks.add(task);
            ensureParentTask(queue, task, parentPlacement);
        }
        return queue;
    }

    private static void ensureParentTask(
            CityC9BuildQueue.BuildQueue queue,
            CityC9BuildQueue.BuildTask task,
            CityC8Stages.PlacementNode parentPlacement
    ) {
        if (queue == null || task == null || task.parent_node_id == null || task.parent_node_id.isBlank() || parentPlacement == null) {
            return;
        }
        String parentTaskId = CityC9BuildQueue.taskId(task.city_id, task.build_area_id, task.parent_node_id);
        for (CityC9BuildQueue.BuildTask existing : queue.tasks) {
            if (existing != null && parentTaskId.equals(existing.task_id)) {
                return;
            }
        }
        CityC9BuildQueue.BuildTask parentTask = new CityC9BuildQueue.BuildTask();
        parentTask.task_id = parentTaskId;
        parentTask.city_id = task.city_id;
        parentTask.group_id = task.group_id;
        parentTask.build_area_id = task.build_area_id;
        parentTask.build_area_numeric_id = task.build_area_numeric_id;
        parentTask.node_id = task.parent_node_id;
        parentTask.template_id = parentPlacement.template_id;
        parentTask.x = parentPlacement.x;
        parentTask.y = resolvePlacementOriginY(null, parentPlacement);
        if (parentTask.y == 0) {
            parentTask.y = task.y;
        }
        parentTask.z = parentPlacement.z;
        parentTask.rotation = parentPlacement.rotation;
        parentTask.chunk_x = Math.floorDiv(parentTask.x, 16);
        parentTask.chunk_z = Math.floorDiv(parentTask.z, 16);
        parentTask.priority = Math.max(task.priority + 1, 100);
        parentTask.status = CityC9BuildQueue.Status.DONE.name();
        parentTask.build_order = parentPlacement.build_order;
        parentTask.parent_node_id = parentPlacement.parent_node_id;
        parentTask.terminalized = parentPlacement.terminalized;
        parentTask.placement_node = parentPlacement;
        queue.tasks.add(parentTask);
    }

    private record SolvedPlacementGateway(
            CityC9BuildQueue.BuildTask targetTask,
            CityVanillaJigsawAdapterService.VanillaPlacementDescriptor descriptor,
            StructurePlacementGateway fallbackGateway
    ) implements StructurePlacementGateway {
        @Override
        public StructureInjector.PlacementBounds placementBounds(BuildWorldAccess world, String structureId, net.minecraft.core.BlockPos origin, net.minecraft.world.level.block.Rotation rotation) {
            if (descriptor != null && descriptor.bounds != null && targetTask != null && sameTask(origin, rotation, structureId)) {
                return descriptor.bounds;
            }
            return fallbackGateway != null ? fallbackGateway.placementBounds(world, structureId, origin, rotation) : null;
        }

        @Override
        public StructureInjector.PlacementOutcome placeStructure(BuildWorldAccess world, String structureId, net.minecraft.core.BlockPos origin, net.minecraft.world.level.block.Rotation rotation, boolean clearJigsawBlocks) {
            if (descriptor != null && descriptor.piece_placer != null && targetTask != null && sameTask(origin, rotation, structureId)) {
                boolean placed = descriptor.piece_placer.place(world, descriptor.start_pos, true);
                TerrainClearStats postCleanup = placed && clearJigsawBlocks && world != null && world.level() != null
                        ? StructureInjector.finalizePlacedJigsaws(world.level(), descriptor.bounds)
                        : new TerrainClearStats("post_cleanup");
                return placed
                        ? StructureInjector.PlacementOutcome.of(true, descriptor.bounds, postCleanup)
                        : StructureInjector.PlacementOutcome.failed(descriptor.bounds);
            }
            return fallbackGateway != null
                    ? fallbackGateway.placeStructure(world, structureId, origin, rotation, clearJigsawBlocks)
                    : StructureInjector.PlacementOutcome.failed(null);
        }

        private boolean sameTask(net.minecraft.core.BlockPos origin, net.minecraft.world.level.block.Rotation rotation, String structureId) {
            if (targetTask == null || origin == null) return false;
            if (!safe(targetTask.template_id).equals(safe(structureId))) return false;
            if (targetTask.x != origin.getX() || targetTask.y != origin.getY() || targetTask.z != origin.getZ()) return false;
            int expectedRotation = switch (rotation) {
                case CLOCKWISE_90 -> 90;
                case CLOCKWISE_180 -> 180;
                case COUNTERCLOCKWISE_90 -> 270;
                default -> 0;
            };
            return targetTask.rotation == expectedRotation;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static int resolvePlacementOriginY(
            CityC8Stages.FoundationItem foundation,
            CityC8Stages.PlacementNode placement
    ) {
        int baseY = foundation != null ? foundation.base_y : 0;
        if (placement == null) {
            return baseY;
        }
        if (placement.y != 0 && placement.y != baseY) {
            return placement.y;
        }
        return StructurePlacementContract.resolveSurfaceAlignedOriginY(placement.template_id, baseY);
    }

    public static final class ExecutionResult {
        public final CityC9BuildQueue.BuildQueue queue;
        public final CityC9BuildQueue.BuildTask task;
        public final TaskExecutionResult result;

        private ExecutionResult(
                CityC9BuildQueue.BuildQueue queue,
                CityC9BuildQueue.BuildTask task,
                TaskExecutionResult result
        ) {
            this.queue = queue;
            this.task = task;
            this.result = result;
        }
    }
}
