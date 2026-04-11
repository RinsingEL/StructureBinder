package com.user.terra_script.world.city.execution;

import com.user.terra_script.world.StructureInjector;
import com.user.terra_script.world.city.stage.c8.CityC8Stages;
import com.user.terra_script.world.city.stage.c9.CityC9BuildQueue;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class BuildTerrainPreparationService {
    public TerrainPreparationResult prepare(BuildExecutionContext context, StructureInjector.PlacementBounds bounds) {
        TerrainPreparationResult result = new TerrainPreparationResult();
        result.setBounds(bounds);
        if (context == null || context.world() == null || bounds == null) {
            return result;
        }
        List<StructureInjector.PlacementBounds> protectedBounds = protectedBounds(context);

        for (int x = bounds.minX; x < bounds.maxXExclusive; x++) {
            for (int y = bounds.minY; y < bounds.maxYExclusive; y++) {
                for (int z = bounds.minZ; z < bounds.maxZExclusive; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (insideProtectedBounds(pos, protectedBounds)) continue;
                    BuildBlockSnapshot snapshot = context.world().blockSnapshot(pos);
                    if (snapshot == null || !snapshot.softClearable()) continue;
                    context.world().clearBlock(pos);
                    result.softObstacleClear().record(pos, snapshot.blockId(), snapshot.softClearReason());
                }
            }
        }

        for (int x = bounds.minX; x < bounds.maxXExclusive; x++) {
            for (int y = bounds.minY; y < bounds.maxYExclusive; y++) {
                for (int z = bounds.minZ; z < bounds.maxZExclusive; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (insideProtectedBounds(pos, protectedBounds)) continue;
                    BuildBlockSnapshot snapshot = context.world().blockSnapshot(pos);
                    if (snapshot == null || snapshot.air() || snapshot.protectedBlock()) continue;
                    context.world().clearBlock(pos);
                    result.embeddedExcavate().record(pos, snapshot.blockId(), "embedded_solid");
                }
            }
        }

        return result;
    }

    private static List<StructureInjector.PlacementBounds> protectedBounds(BuildExecutionContext context) {
        List<StructureInjector.PlacementBounds> out = new ArrayList<>();
        if (context == null || context.cityQueue() == null || context.cityQueue().tasks == null || context.task() == null) {
            return out;
        }
        CityC9BuildQueue.BuildTask current = context.task();
        for (CityC9BuildQueue.BuildTask task : context.cityQueue().tasks) {
            if (task == null || task == current) continue;
            if (task.task_id != null && task.task_id.equals(current.task_id)) continue;
            String status = CityC9BuildQueue.Status.normalize(task.status);
            if (!CityC9BuildQueue.Status.DONE.name().equals(status) && !CityC9BuildQueue.Status.BUILDING.name().equals(status)) {
                continue;
            }
            if (!Objects.equals(task.build_area_id, current.build_area_id)) continue;
            StructureInjector.PlacementBounds taskBounds = resolveTaskBounds(context, task);
            if (taskBounds != null) {
                out.add(taskBounds);
            }
        }
        return out;
    }

    private static StructureInjector.PlacementBounds resolveTaskBounds(BuildExecutionContext context, CityC9BuildQueue.BuildTask task) {
        if (context == null || task == null) return null;
        if (context.world() != null && context.world().level() != null
                && task.template_id != null && !task.template_id.isBlank()) {
            StructureInjector.PlacementBounds runtimeBounds = StructureInjector.placementBounds(
                    context.world().level(),
                    task.template_id,
                    new BlockPos(task.x, task.y, task.z),
                    rotationFromDegrees(task.rotation)
            );
            if (runtimeBounds != null) {
                return runtimeBounds;
            }
        }
        CityC8Stages.PlacementNode placement = task.placement_node;
        if (placement != null
                && placement.footprint_min_x != null
                && placement.footprint_min_z != null
                && placement.footprint_max_x != null
                && placement.footprint_max_z != null) {
            return StructureInjector.PlacementBounds.of(
                    placement.footprint_min_x,
                    task.y,
                    placement.footprint_min_z,
                    placement.footprint_max_x + 1,
                    task.y + 1,
                    placement.footprint_max_z + 1
            );
        }
        return null;
    }

    private static net.minecraft.world.level.block.Rotation rotationFromDegrees(int rotationDegrees) {
        return switch (Math.floorMod(rotationDegrees, 360)) {
            case 90 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_90;
            case 180 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_180;
            case 270 -> net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90;
            default -> net.minecraft.world.level.block.Rotation.NONE;
        };
    }

    private static boolean insideProtectedBounds(BlockPos pos, List<StructureInjector.PlacementBounds> protectedBounds) {
        if (pos == null || protectedBounds == null || protectedBounds.isEmpty()) return false;
        for (StructureInjector.PlacementBounds bounds : protectedBounds) {
            if (bounds == null) continue;
            if (pos.getX() >= bounds.minX && pos.getX() < bounds.maxXExclusive
                    && pos.getY() >= bounds.minY && pos.getY() < bounds.maxYExclusive
                    && pos.getZ() >= bounds.minZ && pos.getZ() < bounds.maxZExclusive) {
                return true;
            }
        }
        return false;
    }
}
