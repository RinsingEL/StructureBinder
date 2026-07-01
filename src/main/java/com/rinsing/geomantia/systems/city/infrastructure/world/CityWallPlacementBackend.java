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

public final class CityWallPlacementBackend {
    private static final int MAX_SEGMENT_HEIGHT_DELTA = 5;

    public JsonObject execute(ServerLevel level, JsonObject wallPlan) {
        JsonObject report = new JsonObject();
        report.addProperty("schemaVersion", "city_wall_placement_report.v0.1");
        report.addProperty("cityId", stringValue(wallPlan, "cityId", "unknown_city"));
        report.addProperty("backend", "vanilla_setblock");
        JsonArray results = new JsonArray();
        int executed = 0;
        int skipped = 0;
        int changed = 0;
        if (level == null) {
            report.addProperty("ok", false);
            report.addProperty("reasonCode", "CITY_WALL_LEVEL_UNAVAILABLE");
            report.addProperty("message", "ServerLevel is required for city_execute_city_walls.");
            report.add("segmentResults", results);
            return report;
        }
        PlacementContext context = PlacementContext.from(wallPlan);
        for (JsonElement elem : array(wallPlan, "wallSegments")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject segment = elem.getAsJsonObject();
            SegmentResult result = placeSegment(level, segment, context);
            if ("executed".equals(result.status())) {
                executed++;
                changed += result.changedBlocks();
            } else {
                skipped++;
            }
            results.add(result.asJson(segment));
        }
        report.addProperty("ok", true);
        report.addProperty("executedSegments", executed);
        report.addProperty("skippedSegments", skipped);
        report.addProperty("changedBlocks", changed);
        report.addProperty("roadProtectedBlockCount", context.roadMasks().size());
        report.add("segmentResults", results);
        return report;
    }

    private SegmentResult placeSegment(ServerLevel level, JsonObject segment, PlacementContext context) {
        if ("gate_gap".equals(stringValue(segment, "segmentType", ""))) {
            return new SegmentResult("skipped", "WALL_GATE_GAP", 0,
                    "Temporary gate gap is intentionally left empty.");
        }
        if ("skipped_wall_segment".equals(stringValue(segment, "segmentType", ""))) {
            return new SegmentResult("skipped", stringValue(segment, "reasonCode", "WALL_SEGMENT_SKIPPED"), 0,
                    "Wall segment intentionally skipped by planner.");
        }
        BlockBounds bounds = bounds(segment.getAsJsonObject("blockBounds"));
        if (context.overlapsRoad(bounds)) {
            return new SegmentResult("skipped", "WALL_ROAD_PROTECTED", 0,
                    "Wall segment overlaps protected actual road mask.");
        }
        if (context.overlapsFootprint(bounds)) {
            return new SegmentResult("skipped", "WALL_STRUCTURE_FOOTPRINT_PROTECTED", 0,
                    "Wall segment overlaps placed structure actual footprint.");
        }
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                int y = surfaceY(level, x, z);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
        int maxDelta = context.maxSegmentHeightDeltaBlocks() <= 0
                ? MAX_SEGMENT_HEIGHT_DELTA : context.maxSegmentHeightDeltaBlocks();
        if (maxY - minY > maxDelta) {
            return new SegmentResult("skipped", "WALL_TERRAIN_TOO_STEEP", 0,
                    "Segment terrain delta " + (maxY - minY) + " exceeds " + maxDelta + ".");
        }
        int baseY = maxY + 1;
        int changed = "tower".equals(stringValue(segment, "segmentType", ""))
                ? placeTower(level, bounds, baseY, context)
                : placeWall(level, bounds, baseY, context);
        return new SegmentResult("executed", "WALL_SEGMENT_PLACED", changed,
                "Placed temporary stone wall segment.");
    }

    private int placeWall(ServerLevel level, BlockBounds bounds, int baseY, PlacementContext context) {
        int changed = 0;
        boolean horizontal = bounds.widthBlocks() >= bounds.heightBlocks();
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                if (context.protects(x, z)) {
                    continue;
                }
                int across = horizontal ? z - bounds.minZ() : x - bounds.minX();
                if (across < 0 || across > 4) {
                    continue;
                }
                changed += placeFoundation(level, x, z, baseY, context);
                for (int y = 0; y < 9; y++) {
                    BlockState state = wallState(y, across);
                    if (state != null && set(level, x, baseY + y, z, state)) {
                        changed++;
                    }
                }
                if ((horizontal ? x : z) % 2 == 0 && (across == 0 || across == 4)
                        && set(level, x, baseY + 9, z, Blocks.STONE_BRICK_WALL.defaultBlockState())) {
                    changed++;
                }
            }
        }
        return changed;
    }

    private int placeTower(ServerLevel level, BlockBounds bounds, int baseY, PlacementContext context) {
        int changed = 0;
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                if (context.protects(x, z)) {
                    continue;
                }
                boolean edge = x == bounds.minX() || x == bounds.maxX() || z == bounds.minZ() || z == bounds.maxZ();
                changed += placeFoundation(level, x, z, baseY, context);
                for (int y = 0; y < 12; y++) {
                    if (!edge && y < 10) {
                        continue;
                    }
                    BlockState state = y == 0 ? Blocks.DEEPSLATE_BRICKS.defaultBlockState()
                            : y >= 10 ? Blocks.STONE_BRICK_WALL.defaultBlockState()
                            : Blocks.STONE_BRICKS.defaultBlockState();
                    if (set(level, x, baseY + y, z, state)) {
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    private int placeFoundation(ServerLevel level, int x, int z, int baseY, PlacementContext context) {
        int changed = 0;
        int surface = surfaceY(level, x, z);
        int depth = Math.min(context.maxFoundationDepthBlocks(), Math.max(0, baseY - surface));
        for (int y = 1; y <= depth; y++) {
            if (set(level, x, baseY - y, z, Blocks.DEEPSLATE_BRICKS.defaultBlockState())) {
                changed++;
            }
        }
        return changed;
    }

    private BlockState wallState(int y, int across) {
        if (y == 0) {
            return Blocks.DEEPSLATE_BRICKS.defaultBlockState();
        }
        if (y <= 2 && (across == 0 || across == 4)) {
            return Blocks.COBBLESTONE.defaultBlockState();
        }
        if (y >= 8 && (across == 0 || across == 4)) {
            return Blocks.STONE_BRICK_WALL.defaultBlockState();
        }
        if (y >= 7 && (across == 1 || across == 3)) {
            return Blocks.STONE_BRICK_SLAB.defaultBlockState();
        }
        if (across == 2 || y <= 6) {
            return Blocks.STONE_BRICKS.defaultBlockState();
        }
        return null;
    }

    private boolean set(ServerLevel level, int x, int y, int z, BlockState state) {
        return level.setBlock(new BlockPos(x, y, z), state, 3);
    }

    private int surfaceY(ServerLevel level, int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
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

    private static String stringValue(JsonObject obj, String key, String fallback) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : fallback;
    }

    private static int intValue(JsonObject obj, String key, int fallback) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : fallback;
    }

    private record SegmentResult(String status, String reasonCode, int changedBlocks, String message) {
        JsonObject asJson(JsonObject segment) {
            JsonObject obj = new JsonObject();
            obj.addProperty("segmentId", stringValue(segment, "segmentId", ""));
            obj.addProperty("segmentType", stringValue(segment, "segmentType", ""));
            obj.addProperty("status", status);
            obj.addProperty("reasonCode", reasonCode);
            obj.addProperty("changedBlocks", changedBlocks);
            obj.addProperty("message", message);
            obj.add("blockBounds", segment.getAsJsonObject("blockBounds").deepCopy());
            return obj;
        }
    }

    private record PlacementContext(java.util.List<BlockBounds> roadMasks,
                                    java.util.List<BlockBounds> footprints,
                                    int maxFoundationDepthBlocks,
                                    int maxSegmentHeightDeltaBlocks) {
        static PlacementContext from(JsonObject wallPlan) {
            java.util.List<BlockBounds> roads = new java.util.ArrayList<>();
            JsonObject roadMask = wallPlan != null && wallPlan.has("actualRoadMask")
                    && wallPlan.get("actualRoadMask").isJsonObject()
                    ? wallPlan.getAsJsonObject("actualRoadMask") : new JsonObject();
            for (JsonElement elem : array(roadMask, "roadMask")) {
                if (elem.isJsonObject()) {
                    roads.add(bounds(elem.getAsJsonObject().getAsJsonObject("blockBounds")));
                }
            }
            java.util.List<BlockBounds> footprints = new java.util.ArrayList<>();
            JsonObject reservation = wallPlan != null && wallPlan.has("wallReservationSource")
                    && wallPlan.get("wallReservationSource").isJsonObject()
                    ? wallPlan.getAsJsonObject("wallReservationSource") : new JsonObject();
            JsonObject ledger = wallPlan != null && wallPlan.has("sourcePlacedStructureLedger")
                    && wallPlan.get("sourcePlacedStructureLedger").isJsonObject()
                    ? wallPlan.getAsJsonObject("sourcePlacedStructureLedger") : new JsonObject();
            for (JsonElement elem : array(ledger, "placedStructures")) {
                if (elem.isJsonObject() && elem.getAsJsonObject().has("actualFootprint")) {
                    footprints.add(bounds(elem.getAsJsonObject().getAsJsonObject("actualFootprint")));
                }
            }
            if (footprints.isEmpty() && wallPlan != null && wallPlan.has("sourceActualFootprintUnion")
                    && wallPlan.get("sourceActualFootprintUnion").isJsonObject()) {
                footprints.add(bounds(wallPlan.getAsJsonObject("sourceActualFootprintUnion")));
            }
            return new PlacementContext(roads, footprints,
                    intValue(wallPlan, "maxFoundationDepthBlocks", 8),
                    intValue(wallPlan, "maxSegmentHeightDeltaBlocks", MAX_SEGMENT_HEIGHT_DELTA));
        }

        boolean protects(int x, int z) {
            return roadMasks.stream().anyMatch(bounds -> bounds.contains(x, z));
        }

        boolean overlapsRoad(BlockBounds bounds) {
            return roadMasks.stream().anyMatch(road -> road.overlaps(bounds));
        }

        boolean overlapsFootprint(BlockBounds bounds) {
            return footprints.stream().anyMatch(footprint -> footprint.overlaps(bounds));
        }
    }
}
