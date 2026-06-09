package com.user.terra_script.world.city.execution;

import com.user.terra_script.world.StructureInjector;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;

final class DefaultStructurePlacementGateway implements StructurePlacementGateway {
    @Override
    public StructureInjector.PlacementBounds placementBounds(BuildWorldAccess world, String structureId, BlockPos origin, Rotation rotation) {
        return world != null && world.level() != null
                ? StructureInjector.placementBounds(world.level(), structureId, origin, rotation)
                : null;
    }

    @Override
    public StructureInjector.PlacementOutcome placeStructure(BuildWorldAccess world, String structureId, BlockPos origin, Rotation rotation, boolean clearJigsawBlocks) {
        if (world == null || world.level() == null) {
            return StructureInjector.PlacementOutcome.failed(null);
        }
        return StructureInjector.placeStructureDetailed(world.level(), structureId, origin, rotation, clearJigsawBlocks);
    }
}
