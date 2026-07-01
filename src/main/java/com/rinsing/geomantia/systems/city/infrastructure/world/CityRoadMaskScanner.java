package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.LinkedHashSet;
import java.util.Set;

public final class CityRoadMaskScanner {
    public static final String SCHEMA = "city_actual_road_mask.v0.2";
    public static final int DEFAULT_ROAD_SCAN_MARGIN_BLOCKS = 8;

    public JsonObject scan(ServerLevel level, JsonObject wallReservationPlan, int roadScanMarginBlocks) {
        int margin = roadScanMarginBlocks <= 0 ? DEFAULT_ROAD_SCAN_MARGIN_BLOCKS : roadScanMarginBlocks;
        JsonObject mask = new JsonObject();
        mask.addProperty("schemaVersion", SCHEMA);
        mask.addProperty("roadMaskSource", "actual_world_blocks");
        mask.addProperty("roadScanMarginBlocks", margin);
        mask.addProperty("cityId", stringValue(wallReservationPlan, "cityId", "unknown_city"));
        JsonArray roadMask = new JsonArray();
        mask.add("roadMask", roadMask);
        if (level == null) {
            mask.addProperty("status", "unavailable");
            mask.addProperty("reasonCode", "CITY_WALL_LEVEL_UNAVAILABLE");
            mask.addProperty("roadBlockCount", 0);
            return mask;
        }
        Set<Long> seen = new LinkedHashSet<>();
        for (JsonElement elem : array(wallReservationPlan, "wallCorridorMask")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            BlockBounds bounds = expand(bounds(elem.getAsJsonObject().getAsJsonObject("blockBounds")), margin);
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                    if (y < level.getMinBuildHeight()) {
                        continue;
                    }
                    BlockState state = level.getBlockState(new BlockPos(x, y, z));
                    if (roadLike(state) && seen.add(pack(x, z))) {
                        JsonObject road = new JsonObject();
                        road.addProperty("maskId", "actual_road_" + roadMask.size());
                        road.addProperty("maskType", "actual_road_block");
                        road.addProperty("blockId", state.getBlock().builtInRegistryHolder().key().location().toString());
                        road.add("blockBounds", boundsJson(new BlockBounds(x, z, x, z)));
                        roadMask.add(road);
                    }
                }
            }
        }
        mask.addProperty("status", roadMask.size() > 0 ? "observed" : "empty");
        mask.addProperty("reasonCode", roadMask.size() > 0 ? "ACTUAL_ROAD_MASK_OBSERVED" : "ROAD_MASK_EMPTY_GATE_FALLBACK");
        mask.addProperty("roadBlockCount", roadMask.size());
        return compact(mask);
    }

    private static JsonObject compact(JsonObject mask) {
        JsonArray points = array(mask, "roadMask");
        JsonArray compact = new JsonArray();
        for (JsonElement elem : points) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject source = elem.getAsJsonObject();
            JsonObject obj = new JsonObject();
            obj.addProperty("maskId", source.get("maskId").getAsString());
            obj.addProperty("maskType", "actual_road");
            obj.addProperty("blockId", stringValue(source, "blockId", ""));
            obj.add("blockBounds", source.getAsJsonObject("blockBounds").deepCopy());
            compact.add(obj);
        }
        mask.add("roadMask", compact);
        return mask;
    }

    private static boolean roadLike(BlockState state) {
        return state.is(Blocks.DIRT_PATH)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.MOSSY_COBBLESTONE)
                || state.is(Blocks.PACKED_MUD)
                || state.is(Blocks.MUD_BRICKS)
                || state.is(Blocks.ANDESITE)
                || state.is(Blocks.POLISHED_ANDESITE)
                || state.is(Blocks.STONE_BRICKS);
    }

    private static BlockBounds expand(BlockBounds bounds, int margin) {
        return new BlockBounds(bounds.minX() - margin, bounds.minZ() - margin,
                bounds.maxX() + margin, bounds.maxZ() + margin);
    }

    private static long pack(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private static JsonArray array(JsonObject obj, String key) {
        return obj != null && obj.has(key) && obj.get(key).isJsonArray()
                ? obj.getAsJsonArray(key)
                : new JsonArray();
    }

    private static BlockBounds bounds(JsonObject obj) {
        return new BlockBounds(intValue(obj, "minX", 0), intValue(obj, "minZ", 0),
                intValue(obj, "maxX", 0), intValue(obj, "maxZ", 0));
    }

    private static JsonObject boundsJson(BlockBounds bounds) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minX", bounds.minX());
        obj.addProperty("minZ", bounds.minZ());
        obj.addProperty("maxX", bounds.maxX());
        obj.addProperty("maxZ", bounds.maxZ());
        return obj;
    }

    private static String stringValue(JsonObject obj, String key, String fallback) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : fallback;
    }

    private static int intValue(JsonObject obj, String key, int fallback) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : fallback;
    }
}
