package com.user.terra_script.world.city.execution;

import com.user.terra_script.world.StructureInjector;
import net.minecraft.core.BlockPos;

public final class BuildTerrainPreparationService {
    public TerrainPreparationResult prepare(BuildExecutionContext context, StructureInjector.PlacementBounds bounds) {
        TerrainPreparationResult result = new TerrainPreparationResult();
        result.setBounds(bounds);
        if (context == null || context.world() == null || bounds == null) {
            return result;
        }

        for (int x = bounds.minX; x < bounds.maxXExclusive; x++) {
            for (int y = bounds.minY; y < bounds.maxYExclusive; y++) {
                for (int z = bounds.minZ; z < bounds.maxZExclusive; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
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
                    BuildBlockSnapshot snapshot = context.world().blockSnapshot(pos);
                    if (snapshot == null || snapshot.air() || snapshot.protectedBlock()) continue;
                    context.world().clearBlock(pos);
                    result.embeddedExcavate().record(pos, snapshot.blockId(), "embedded_solid");
                }
            }
        }

        return result;
    }
}
