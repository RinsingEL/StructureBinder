package com.user.terra_script.world.city.stage.c9;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.user.terra_script.world.city.stage.c6.CityC6Stages;
import com.user.terra_script.world.city.stage.c8.CityC8Stages;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CityC9Stages {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final String C9_PLACEMENT_FILE = "C9_Placement.json";
    public static final String C9_DECORATION_FILE = "C9_DecorationPlan.json";

    private CityC9Stages() {}

    public static class C9Result {
        public C9Placement placement;
        public C9Decoration decoration;
    }

    public static class C9Placement {
        public String step = "C9";
        public boolean ok = true;
        public String city_id;
        public long generated_at_epoch_ms;
        public boolean apply_blocks;
        public int max_blocks;
        public int changed_blocks_total;
        public int processed_areas;
        public List<PlacementItem> items = new ArrayList<>();
    }

    public static class PlacementItem {
        public String build_area_id;
        public int build_area_numeric_id;
        public String foundation_type;
        public int base_y;
        public int scanned_blocks;
        public int changed_blocks;
    }

    public static class C9Decoration {
        public String step = "C9";
        public boolean ok = true;
        public String city_id;
        public long generated_at_epoch_ms;
        public List<DecorationItem> items = new ArrayList<>();
    }

    public static class DecorationItem {
        public String build_area_id;
        public String strategy;
        public String note;
    }

    public static C9Result generate(
            String cityId,
            ServerLevel level,
            CityC6Stages.C6Summary c6Summary,
            Map<Long, Integer> c6Index,
            CityC8Stages.C8Plan c8Plan,
            boolean applyBlocks,
            int maxBlocks
    ) {
        C9Result out = new C9Result();
        out.placement = new C9Placement();
        out.decoration = new C9Decoration();
        out.placement.city_id = cityId;
        out.decoration.city_id = cityId;
        out.placement.generated_at_epoch_ms = System.currentTimeMillis();
        out.decoration.generated_at_epoch_ms = out.placement.generated_at_epoch_ms;
        out.placement.apply_blocks = applyBlocks;
        out.placement.max_blocks = Math.max(1, maxBlocks);

        if (c6Summary == null || c6Index == null || c6Index.isEmpty() || c8Plan == null || c8Plan.foundations == null) {
            out.placement.ok = false;
            out.decoration.ok = false;
            return out;
        }
        if (applyBlocks && level == null) {
            out.placement.ok = false;
            out.decoration.ok = false;
            return out;
        }

        Map<Integer, CityC8Stages.FoundationItem> foundationByArea = new HashMap<>();
        for (CityC8Stages.FoundationItem item : c8Plan.foundations) {
            if (item == null) continue;
            foundationByArea.put(item.build_area_numeric_id, item);
        }

        Map<Integer, Set<Long>> blocksByArea = new HashMap<>();
        for (Map.Entry<Long, Integer> e : c6Index.entrySet()) {
            if (e == null || e.getKey() == null || e.getValue() == null) continue;
            blocksByArea.computeIfAbsent(e.getValue(), k -> new HashSet<>()).add(e.getKey());
        }

        List<CityC6Stages.BuildAreaSummary> areas = c6Summary.areas != null ? c6Summary.areas : Collections.emptyList();
        areas.sort(Comparator.comparing(a -> a.build_area_id));

        int changedTotal = 0;
        int remainingBudget = Math.max(1, out.placement.max_blocks);
        for (CityC6Stages.BuildAreaSummary area : areas) {
            if (area == null) continue;
            CityC8Stages.FoundationItem foundation = foundationByArea.get(area.build_area_numeric_id);
            if (foundation == null) continue;

            Set<Long> areaBlocks = blocksByArea.getOrDefault(area.build_area_numeric_id, Collections.emptySet());
            if (areaBlocks.isEmpty()) continue;

            PlacementItem p = new PlacementItem();
            p.build_area_id = area.build_area_id;
            p.build_area_numeric_id = area.build_area_numeric_id;
            p.foundation_type = foundation.foundation_type;
            p.base_y = foundation.base_y;
            p.scanned_blocks = areaBlocks.size();

            int changed = 0;
            if (applyBlocks) {
                changed = placeArea(level, areaBlocks, foundation, remainingBudget);
                remainingBudget = Math.max(0, remainingBudget - changed);
            }
            p.changed_blocks = changed;
            changedTotal += changed;
            out.placement.items.add(p);

            DecorationItem d = new DecorationItem();
            d.build_area_id = area.build_area_id;
            d.strategy = inferDecorStrategy(foundation.foundation_type);
            d.note = "Generated from C8 foundation type";
            out.decoration.items.add(d);

            if (applyBlocks && remainingBudget <= 0) {
                break;
            }
        }

        out.placement.processed_areas = out.placement.items.size();
        out.placement.changed_blocks_total = changedTotal;
        return out;
    }

    public static void save(Path cityDir, C9Result result) throws Exception {
        if (cityDir == null || result == null) return;
        Files.createDirectories(cityDir);
        if (result.placement != null) {
            Files.writeString(cityDir.resolve(C9_PLACEMENT_FILE), GSON.toJson(result.placement), StandardCharsets.UTF_8);
        }
        if (result.decoration != null) {
            Files.writeString(cityDir.resolve(C9_DECORATION_FILE), GSON.toJson(result.decoration), StandardCharsets.UTF_8);
        }
    }

    public static C9Placement loadPlacement(Path cityDir) throws Exception {
        Path file = cityDir.resolve(C9_PLACEMENT_FILE);
        if (!Files.exists(file)) return null;
        return GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), C9Placement.class);
    }

    public static C9Decoration loadDecoration(Path cityDir) throws Exception {
        Path file = cityDir.resolve(C9_DECORATION_FILE);
        if (!Files.exists(file)) return null;
        return GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), C9Decoration.class);
    }

    private static int placeArea(
            ServerLevel level,
            Set<Long> areaBlocks,
            CityC8Stages.FoundationItem foundation,
            int budget
    ) {
        if (level == null || areaBlocks == null || areaBlocks.isEmpty() || budget <= 0) return 0;
        int changed = 0;
        int targetY = Math.max(level.getMinBuildHeight() + 1, foundation.base_y);
        Set<Long> set = areaBlocks;

        for (long key : areaBlocks) {
            if (changed >= budget) break;
            int x = unpackX(key);
            int z = unpackZ(key);
            BlockPos floorPos = new BlockPos(x, targetY - 1, z);
            BlockPos topPos = new BlockPos(x, targetY, z);

            if ("TERRACE".equals(foundation.foundation_type)) {
                int ring = (Math.abs(x + z) % 5);
                BlockPos terracePos = new BlockPos(x, targetY + (ring / 2), z);
                if (!level.getBlockState(terracePos).is(Blocks.STONE_BRICKS)) {
                    level.setBlock(terracePos, Blocks.STONE_BRICKS.defaultBlockState(), 3);
                    changed++;
                }
                if (changed >= budget) break;
                continue;
            }

            if (!level.getBlockState(floorPos).is(Blocks.STONE)) {
                level.setBlock(floorPos, Blocks.STONE.defaultBlockState(), 3);
                changed++;
                if (changed >= budget) break;
            }
            if (!level.getBlockState(topPos).is(Blocks.STONE_BRICKS)) {
                level.setBlock(topPos, Blocks.STONE_BRICKS.defaultBlockState(), 3);
                changed++;
                if (changed >= budget) break;
            }

            if ("PLATFORM_WITH_RETAINING_WALL".equals(foundation.foundation_type) && isBoundary(set, x, z)) {
                BlockPos wallPos = new BlockPos(x, targetY + 1, z);
                if (!level.getBlockState(wallPos).is(Blocks.COBBLESTONE_WALL)) {
                    level.setBlock(wallPos, Blocks.COBBLESTONE_WALL.defaultBlockState(), 3);
                    changed++;
                }
            }
        }
        return changed;
    }

    private static boolean isBoundary(Set<Long> set, int x, int z) {
        int[][] dirs = new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            if (!set.contains(packBlock(x + d[0], z + d[1]))) return true;
        }
        return false;
    }

    private static String inferDecorStrategy(String foundationType) {
        if ("TERRACE".equals(foundationType)) return "SLOPE_GARDEN";
        if ("PLATFORM_WITH_RETAINING_WALL".equals(foundationType)) return "WALL_TORCHES";
        if ("PLATFORM".equals(foundationType)) return "PLAZA_LANTERNS";
        return "NATURAL_SCATTER";
    }

    private static int unpackX(long key) {
        return (int) (key >> 32);
    }

    private static int unpackZ(long key) {
        return (int) key;
    }

    private static long packBlock(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }
}

