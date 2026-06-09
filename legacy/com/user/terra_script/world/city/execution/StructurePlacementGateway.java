package com.user.terra_script.world.city.execution;

import com.user.terra_script.world.StructureInjector;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;

public interface StructurePlacementGateway {
    StructureInjector.PlacementBounds placementBounds(BuildWorldAccess world, String structureId, BlockPos origin, Rotation rotation);

    StructureInjector.PlacementOutcome placeStructure(BuildWorldAccess world, String structureId, BlockPos origin, Rotation rotation, boolean clearJigsawBlocks);
}
