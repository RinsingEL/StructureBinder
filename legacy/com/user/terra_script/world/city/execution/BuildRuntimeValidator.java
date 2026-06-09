package com.user.terra_script.world.city.execution;

import com.user.terra_script.world.StructureInjector;
import com.user.terra_script.world.city.stage.c9.CityC9BuildQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;

import java.util.Objects;

public final class BuildRuntimeValidator {
    private final StructurePlacementGateway gateway;

    public BuildRuntimeValidator(StructurePlacementGateway gateway) {
        this.gateway = gateway;
    }

    public ValidationResult validate(BuildExecutionContext context) {
        if (context == null || context.task() == null) {
            return ValidationResult.failed("missing_execution_context");
        }
        CityC9BuildQueue.BuildTask task = context.task();
        CityC9BuildQueue.BuildQueue queue = context.cityQueue();
        String parentTaskId = null;
        if (task.parent_node_id != null && !task.parent_node_id.isBlank()) {
            parentTaskId = CityC9BuildQueue.taskId(task.city_id, task.build_area_id, task.parent_node_id);
            CityC9BuildQueue.BuildTask parent = findTask(queue, parentTaskId);
            if (parent == null) return ValidationResult.failed("missing_parent_task");
            if (!CityC9BuildQueue.Status.DONE.name().equals(CityC9BuildQueue.Status.normalize(parent.status))) {
                return ValidationResult.failed("waiting_for_parent");
            }
        }
        Rotation rotation = toRotation(task.rotation);
        StructureInjector.PlacementBounds candidateBounds = gateway.placementBounds(
                context.world(),
                task.template_id,
                new BlockPos(task.x, task.y, task.z),
                rotation
        );
        if (candidateBounds == null) {
            return ValidationResult.failed("placement_bounds_unavailable");
        }
        if (queue != null && queue.tasks != null) {
            for (CityC9BuildQueue.BuildTask other : queue.tasks) {
                if (other == null || other == task) continue;
                if (!Objects.equals(task.build_area_id, other.build_area_id)) continue;
                if (!CityC9BuildQueue.Status.DONE.name().equals(CityC9BuildQueue.Status.normalize(other.status))) continue;
                if (isParentTask(task, parentTaskId, other)) continue;
                StructureInjector.PlacementBounds otherBounds = gateway.placementBounds(
                        context.world(),
                        other.template_id,
                        new BlockPos(other.x, other.y, other.z),
                        toRotation(other.rotation)
                );
                if (otherBounds != null && candidateBounds.intersects(otherBounds)) {
                    return ValidationResult.failed("runtime_footprint_collision");
                }
            }
        }
        return ValidationResult.success(candidateBounds);
    }

    private static boolean isParentTask(
            CityC9BuildQueue.BuildTask task,
            String parentTaskId,
            CityC9BuildQueue.BuildTask other
    ) {
        if (task == null || other == null) return false;
        if (task.parent_node_id != null && !task.parent_node_id.isBlank()
                && task.parent_node_id.equals(other.node_id)) {
            return true;
        }
        return parentTaskId != null && !parentTaskId.isBlank() && parentTaskId.equals(other.task_id);
    }

    private static CityC9BuildQueue.BuildTask findTask(CityC9BuildQueue.BuildQueue queue, String taskId) {
        if (queue == null || queue.tasks == null || taskId == null || taskId.isBlank()) return null;
        for (CityC9BuildQueue.BuildTask task : queue.tasks) {
            if (task != null && taskId.equals(task.task_id)) return task;
        }
        return null;
    }

    private static Rotation toRotation(int degrees) {
        int normalized = ((degrees % 360) + 360) % 360;
        return switch (normalized) {
            case 90 -> Rotation.CLOCKWISE_90;
            case 180 -> Rotation.CLOCKWISE_180;
            case 270 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    public static final class ValidationResult {
        private final boolean ok;
        private final String errorCode;
        private final StructureInjector.PlacementBounds bounds;

        private ValidationResult(boolean ok, String errorCode, StructureInjector.PlacementBounds bounds) {
            this.ok = ok;
            this.errorCode = errorCode;
            this.bounds = bounds;
        }

        public static ValidationResult success(StructureInjector.PlacementBounds bounds) {
            return new ValidationResult(true, null, bounds);
        }

        public static ValidationResult failed(String errorCode) {
            return new ValidationResult(false, errorCode, null);
        }

        public boolean ok() {
            return ok;
        }

        public String errorCode() {
            return errorCode;
        }

        public StructureInjector.PlacementBounds bounds() {
            return bounds;
        }
    }
}
