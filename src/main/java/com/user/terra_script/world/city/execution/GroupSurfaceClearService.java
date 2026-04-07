package com.user.terra_script.world.city.execution;

import com.google.gson.JsonObject;
import com.user.terra_script.world.StructureInjector;
import com.user.terra_script.world.city.stage.c8.CityC8Stages;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class GroupSurfaceClearService {
    public ClearResult clear(
            BuildWorldAccess world,
            CityC8Stages.AreaGeometry geometry,
            int minY,
            int maxY,
            boolean clearSolids,
            List<StructureInjector.PlacementBounds> excludedBounds
    ) {
        ClearResult result = new ClearResult();
        result.build_area_id = geometry != null ? geometry.build_area_id : null;
        result.build_area_numeric_id = geometry != null ? geometry.build_area_numeric_id : 0;
        result.min_y = minY;
        result.max_y = Math.max(minY, maxY);
        result.clear_solids = clearSolids;
        result.excluded_bounds_count = excludedBounds != null ? excludedBounds.size() : 0;
        if (geometry != null && geometry.valid) {
            result.clear_bounds = StructureInjector.PlacementBounds.of(
                    geometry.min_x,
                    result.min_y,
                    geometry.min_z,
                    geometry.max_x + 1,
                    result.max_y + 1,
                    geometry.max_z + 1
            );
            result.terrain.setBounds(result.clear_bounds);
        }

        if (world == null || geometry == null || !geometry.valid) {
            return result;
        }

        Set<Long> blocks = geometry.block_set != null && !geometry.block_set.isEmpty()
                ? geometry.block_set
                : new LinkedHashSet<>(geometry.block_keys != null ? geometry.block_keys : List.of());
        for (Long key : blocks) {
            if (key == null) continue;
            int x = unpackX(key);
            int z = unpackZ(key);
            result.area_block_count++;
            int columnTop = columnTopY(world, x, z, result.min_y, result.max_y);
            if (columnTop < result.min_y) continue;
            result.scanned_column_count++;

            clearSoft(world, x, z, result.min_y, columnTop, excludedBounds, result.terrain.softObstacleClear());
            if (clearSolids) {
                clearSolid(world, x, z, result.min_y, columnTop, excludedBounds, result.terrain.embeddedExcavate());
            }
        }

        return result;
    }

    private static void clearSoft(
            BuildWorldAccess world,
            int x,
            int z,
            int minY,
            int maxY,
            List<StructureInjector.PlacementBounds> excludedBounds,
            TerrainClearStats stats
    ) {
        for (int y = maxY; y >= minY; y--) {
            if (isExcluded(x, y, z, excludedBounds)) continue;
            BlockPos pos = new BlockPos(x, y, z);
            BuildBlockSnapshot snapshot = world.blockSnapshot(pos);
            if (snapshot == null || !snapshot.softClearable()) continue;
            world.clearBlock(pos);
            stats.record(pos, snapshot.blockId(), snapshot.softClearReason());
        }
    }

    private static void clearSolid(
            BuildWorldAccess world,
            int x,
            int z,
            int minY,
            int maxY,
            List<StructureInjector.PlacementBounds> excludedBounds,
            TerrainClearStats stats
    ) {
        for (int y = maxY; y >= minY; y--) {
            if (isExcluded(x, y, z, excludedBounds)) continue;
            BlockPos pos = new BlockPos(x, y, z);
            BuildBlockSnapshot snapshot = world.blockSnapshot(pos);
            if (snapshot == null || snapshot.air() || snapshot.protectedBlock()) continue;
            world.clearBlock(pos);
            stats.record(pos, snapshot.blockId(), "embedded_solid");
        }
    }

    private static boolean isExcluded(int x, int y, int z, List<StructureInjector.PlacementBounds> excludedBounds) {
        if (excludedBounds == null || excludedBounds.isEmpty()) return false;
        for (StructureInjector.PlacementBounds bounds : excludedBounds) {
            if (bounds == null) continue;
            if (x >= bounds.minX && x < bounds.maxXExclusive
                    && y >= bounds.minY && y < bounds.maxYExclusive
                    && z >= bounds.minZ && z < bounds.maxZExclusive) {
                return true;
            }
        }
        return false;
    }

    private static int columnTopY(BuildWorldAccess world, int x, int z, int minY, int maxY) {
        if (world == null || world.level() == null) return maxY;
        int surface = world.level().getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        return Math.min(maxY, Math.max(minY, surface));
    }

    private static int unpackX(long key) {
        return (int) (key >> 32);
    }

    private static int unpackZ(long key) {
        return (int) key;
    }

    public static final class ClearResult {
        public String build_area_id;
        public int build_area_numeric_id;
        public int area_block_count;
        public int scanned_column_count;
        public int min_y;
        public int max_y;
        public boolean clear_solids;
        public int excluded_bounds_count;
        public StructureInjector.PlacementBounds clear_bounds;
        public final TerrainPreparationResult terrain = new TerrainPreparationResult();

        public JsonObject toJson() {
            JsonObject out = new JsonObject();
            out.addProperty("build_area_id", build_area_id);
            out.addProperty("build_area_numeric_id", build_area_numeric_id);
            out.addProperty("area_block_count", area_block_count);
            out.addProperty("scanned_column_count", scanned_column_count);
            out.addProperty("min_y", min_y);
            out.addProperty("max_y", max_y);
            out.addProperty("clear_solids", clear_solids);
            out.addProperty("excluded_bounds_count", excluded_bounds_count);
            out.add("terrain_preparation", terrain.toJson());
            return out;
        }
    }
}
