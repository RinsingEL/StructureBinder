package com.user.terra_script.world.city.execution;

import com.user.terra_script.world.StructureInjector;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;

public final class BuildPlacementService {
    private final StructurePlacementGateway gateway;

    public BuildPlacementService(StructurePlacementGateway gateway) {
        this.gateway = gateway;
    }

    public StructureInjector.PlacementOutcome place(BuildExecutionContext context) {
        if (context == null || context.task() == null) {
            return StructureInjector.PlacementOutcome.failed(null);
        }
        return gateway.placeStructure(
                context.world(),
                context.task().template_id,
                new BlockPos(context.task().x, context.task().y, context.task().z),
                toRotation(context.task().rotation),
                true
        );
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
}
