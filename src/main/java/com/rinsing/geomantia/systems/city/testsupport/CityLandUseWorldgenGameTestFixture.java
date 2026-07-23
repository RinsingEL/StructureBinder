package com.rinsing.geomantia.systems.city.testsupport;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

/** Supplies natural substrate only for the explicitly enabled cross-chunk Forge GameTest. */
public final class CityLandUseWorldgenGameTestFixture {
    private static volatile Fixture active;

    private CityLandUseWorldgenGameTestFixture() {
    }

    public static void enable(int minX, int maxX, int z) {
        active = new Fixture(minX, maxX, z);
    }

    public static void disable() {
        active = null;
    }

    public static void prepare(WorldGenLevel level, ChunkPos owner) {
        Fixture fixture = active;
        if (fixture == null || Math.floorDiv(fixture.z(), 16) != owner.z) return;
        int ownerMinX = owner.getMinBlockX();
        int ownerMaxX = owner.getMaxBlockX();
        for (int x = Math.max(fixture.minX(), ownerMinX);
             x <= Math.min(fixture.maxX(), ownerMaxX); x++) {
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, fixture.z()) - 1;
            level.setBlock(new BlockPos(x, y, fixture.z()), Blocks.DIRT.defaultBlockState(),
                    Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
    }

    private record Fixture(int minX, int maxX, int z) {
    }
}
