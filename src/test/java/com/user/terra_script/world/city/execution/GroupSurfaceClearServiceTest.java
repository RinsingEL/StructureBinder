package com.user.terra_script.world.city.execution;

import com.user.terra_script.world.StructureInjector;
import com.user.terra_script.world.city.stage.c8.CityC8Stages;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupSurfaceClearServiceTest {
    @Test
    void clearOnlyTouchesPolygonColumns() {
        BuildExecutionTestSupport.FakeWorldAccess world = new BuildExecutionTestSupport.FakeWorldAccess();
        world.blocks.put(new BlockPos(10, 64, 20), BuildBlockSnapshot.of("minecraft:oak_log", false, false, "trunk"));
        world.blocks.put(new BlockPos(11, 64, 20), BuildBlockSnapshot.of("minecraft:stone", false, false, null));
        world.blocks.put(new BlockPos(12, 64, 20), BuildBlockSnapshot.of("minecraft:oak_log", false, false, "trunk"));

        GroupSurfaceClearService service = new GroupSurfaceClearService();
        GroupSurfaceClearService.ClearResult result = service.clear(
                world,
                geometry(10, 20, 11, 20),
                64,
                68,
                true,
                List.of()
        );

        assertEquals(2, result.area_block_count);
        assertEquals(2, result.scanned_column_count);
        assertEquals(2, result.terrain.totalClearedBlocks());
        assertTrue(world.blockSnapshot(new BlockPos(10, 64, 20)).air());
        assertTrue(world.blockSnapshot(new BlockPos(11, 64, 20)).air());
        assertEquals("minecraft:oak_log", world.blockSnapshot(new BlockPos(12, 64, 20)).blockId());
    }

    @Test
    void clearRespectsExcludedBoundsAndProtectedBlocks() {
        BuildExecutionTestSupport.FakeWorldAccess world = new BuildExecutionTestSupport.FakeWorldAccess();
        world.blocks.put(new BlockPos(10, 64, 20), BuildBlockSnapshot.of("minecraft:oak_log", false, false, "trunk"));
        world.blocks.put(new BlockPos(11, 64, 20), BuildBlockSnapshot.of("minecraft:bedrock", false, true, null));

        GroupSurfaceClearService service = new GroupSurfaceClearService();
        GroupSurfaceClearService.ClearResult result = service.clear(
                world,
                geometry(10, 20, 11, 20),
                64,
                68,
                true,
                List.of(StructureInjector.PlacementBounds.of(10, 64, 20, 11, 65, 21))
        );

        assertEquals(1, result.excluded_bounds_count);
        assertEquals(0, result.terrain.softObstacleClear().totalClearedBlocks());
        assertEquals(0, result.terrain.embeddedExcavate().totalClearedBlocks());
        assertEquals("minecraft:oak_log", world.blockSnapshot(new BlockPos(10, 64, 20)).blockId());
        assertEquals("minecraft:bedrock", world.blockSnapshot(new BlockPos(11, 64, 20)).blockId());
    }

    private static CityC8Stages.AreaGeometry geometry(int... xzPairs) {
        CityC8Stages.AreaGeometry geometry = new CityC8Stages.AreaGeometry();
        geometry.valid = true;
        geometry.build_area_id = "ba_test";
        geometry.build_area_numeric_id = 1;
        geometry.block_set = new LinkedHashSet<>();
        geometry.block_keys.clear();
        geometry.min_x = Integer.MAX_VALUE;
        geometry.min_z = Integer.MAX_VALUE;
        geometry.max_x = Integer.MIN_VALUE;
        geometry.max_z = Integer.MIN_VALUE;
        for (int i = 0; i < xzPairs.length; i += 2) {
            int x = xzPairs[i];
            int z = xzPairs[i + 1];
            long key = packBlock(x, z);
            geometry.block_set.add(key);
            geometry.block_keys.add(key);
            geometry.min_x = Math.min(geometry.min_x, x);
            geometry.min_z = Math.min(geometry.min_z, z);
            geometry.max_x = Math.max(geometry.max_x, x);
            geometry.max_z = Math.max(geometry.max_z, z);
        }
        return geometry;
    }

    private static long packBlock(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }
}
