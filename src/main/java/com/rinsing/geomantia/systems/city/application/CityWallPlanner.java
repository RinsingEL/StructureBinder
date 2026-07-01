package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class CityWallPlanner {
    public static final int DEFAULT_WALL_MARGIN_BLOCKS = 24;
    public static final int DEFAULT_SEGMENT_LENGTH_BLOCKS = 15;
    public static final int DEFAULT_GATE_WIDTH_BLOCKS = 7;
    public static final int DEFAULT_ROAD_PROTECTION_MARGIN_BLOCKS = 2;
    private static final int WALL_HALF_THICKNESS_BLOCKS = 2;

    public JsonObject plan(JsonObject placedLedger, int wallMarginBlocks, int segmentLengthBlocks,
                           int gateWidthBlocks) {
        int margin = wallMarginBlocks <= 0 ? DEFAULT_WALL_MARGIN_BLOCKS : wallMarginBlocks;
        int segmentLength = segmentLengthBlocks <= 0 ? DEFAULT_SEGMENT_LENGTH_BLOCKS : segmentLengthBlocks;
        int gateWidth = gateWidthBlocks <= 0 ? DEFAULT_GATE_WIDTH_BLOCKS : gateWidthBlocks;
        BlockBounds cityBounds = unionPlaced(placedLedger);
        BlockBounds wallBounds = expand(snap(cityBounds, segmentLength), margin);

        JsonObject plan = new JsonObject();
        plan.addProperty("schemaVersion", "city_wall_plan.v0.1");
        plan.addProperty("cityId", stringValue(placedLedger, "cityId", "unknown_city"));
        plan.addProperty("boundaryMode", "temporary_rectilinear_actual_footprint_union");
        plan.addProperty("wallMarginBlocks", margin);
        plan.addProperty("segmentLengthBlocks", segmentLength);
        plan.addProperty("gateWidthBlocks", gateWidth);
        plan.add("sourceActualFootprintUnion", boundsJson(cityBounds));
        plan.add("wallBounds", boundsJson(wallBounds));
        plan.add("wallSegments", segments(wallBounds, segmentLength, gateWidth));
        plan.add("templateLibrary", CityWallTemplateLibrary.libraryJson());
        return plan;
    }

    public JsonObject planV2(JsonObject placedLedger,
                             JsonObject wallReservationPlan,
                             JsonObject actualRoadMask,
                             int gateWidthBlocks,
                             int roadProtectionMarginBlocks,
                             int maxFoundationDepthBlocks,
                             int maxSegmentHeightDeltaBlocks) {
        int gateWidth = gateWidthBlocks <= 0 ? 9 : gateWidthBlocks;
        int roadMargin = roadProtectionMarginBlocks <= 0
                ? DEFAULT_ROAD_PROTECTION_MARGIN_BLOCKS : roadProtectionMarginBlocks;
        JsonObject plan = new JsonObject();
        plan.addProperty("schemaVersion", "city_wall_plan.v0.2");
        plan.addProperty("cityId", stringValue(placedLedger, "cityId", "unknown_city"));
        plan.addProperty("boundaryMode", "d3_patch_boundary_wall_corridor");
        plan.addProperty("wallPlanningMode", "reservation_then_road_gate_cut");
        plan.addProperty("roadMaskSource", "actual_world_blocks");
        plan.addProperty("gateWidthBlocks", gateWidth);
        plan.addProperty("roadProtectionMarginBlocks", roadMargin);
        plan.addProperty("maxFoundationDepthBlocks", maxFoundationDepthBlocks <= 0 ? 8 : maxFoundationDepthBlocks);
        plan.addProperty("maxSegmentHeightDeltaBlocks", maxSegmentHeightDeltaBlocks <= 0 ? 7 : maxSegmentHeightDeltaBlocks);
        plan.add("wallReservationSource", wallReservationPlan == null ? new JsonObject() : wallReservationPlan.deepCopy());
        plan.add("actualRoadMask", actualRoadMask == null ? emptyRoadMask(plan.get("cityId").getAsString()) : actualRoadMask.deepCopy());
        plan.add("sourcePlacedStructureLedger", placedLedger == null ? new JsonObject() : placedLedger.deepCopy());
        plan.add("sourceActualFootprintUnion", boundsJson(unionPlaced(placedLedger)));
        BlockBounds wallBounds = wallReservationPlan != null && wallReservationPlan.has("wallBounds")
                ? bounds(wallReservationPlan.getAsJsonObject("wallBounds"))
                : expand(unionPlaced(placedLedger), DEFAULT_WALL_MARGIN_BLOCKS);
        plan.add("wallBounds", boundsJson(wallBounds));

        JsonArray roadIntersections = new JsonArray();
        JsonArray gates = new JsonArray();
        JsonArray segments = new JsonArray();
        JsonArray roadMasks = array(plan.getAsJsonObject("actualRoadMask"), "roadMask");
        int index = 0;
        for (JsonElement elem : array(wallReservationPlan, "wallCenterline")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject centerline = elem.getAsJsonObject();
            BlockBounds bounds = bounds(centerline.getAsJsonObject("blockBounds"));
            List<BlockBounds> hits = roadHits(bounds, roadMasks, roadMargin);
            if (!hits.isEmpty()) {
                BlockBounds gateBounds = gateBounds(bounds, hits, gateWidth, roadMargin);
                String gateId = "road_gate_" + gates.size();
                JsonObject intersection = new JsonObject();
                intersection.addProperty("intersectionId", "road_wall_intersection_" + roadIntersections.size());
                intersection.addProperty("wallSegmentId", stringValue(centerline, "segmentId", "wall_centerline_" + index));
                intersection.addProperty("reasonCode", "ROAD_WALL_INTERSECTION");
                intersection.add("blockBounds", boundsJson(gateBounds));
                roadIntersections.add(intersection);
                JsonObject gate = segment(gateId, "gate_gap", "wall_gap_gate_7",
                        gateBounds.minX(), gateBounds.minZ(), gateBounds.maxX(), gateBounds.maxZ());
                gate.addProperty("reasonCode", "WALL_GATE_FROM_ROAD");
                gate.addProperty("gateWidthBlocks", Math.max(gateBounds.widthBlocks(), gateBounds.heightBlocks()));
                gates.add(gate.deepCopy());
                segments.add(gate);
                addFlankingTowers(segments, gateId, gateBounds);
            } else if (nearRoad(bounds, roadMasks, roadMargin + 2)) {
                JsonObject skipped = segment("wall_road_too_close_" + index, "skipped_wall_segment", "wall_straight_15",
                        bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ());
                skipped.addProperty("reasonCode", "WALL_ROAD_TOO_CLOSE_SKIP");
                segments.add(skipped);
            } else {
                JsonObject wall = segment("wall_segment_" + index, "wall_segment", "wall_straight_15",
                        bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ());
                wall.addProperty("reasonCode", "PATCH_BOUNDARY_WALL_SEGMENT");
                segments.add(wall);
            }
            index++;
        }
        if (gates.isEmpty()) {
            addFallbackGate(plan, wallReservationPlan, segments, gates);
        }
        plan.add("wallRoadIntersections", roadIntersections);
        plan.add("generatedGates", gates);
        plan.add("wallSegments", segments);
        plan.add("templateLibrary", CityWallTemplateLibrary.libraryJson());
        JsonObject terrain = new JsonObject();
        terrain.addProperty("foundationMode", "step_to_surface");
        terrain.addProperty("slopeMode", "skip_or_embed");
        terrain.addProperty("roadProtection", true);
        plan.add("terrainFitPolicy", terrain);
        return plan;
    }

    public Path writeArtifacts(JsonObject plan, Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        Path planPath = outputDirectory.resolve("city_wall_plan.json");
        Files.writeString(planPath, CityJson.GSON.toJson(plan));
        CityWallTemplateLibrary.writeTemplates(outputDirectory.resolve("city_wall_templates"));
        return planPath;
    }

    private static JsonArray segments(BlockBounds bounds, int segmentLength, int gateWidth) {
        JsonArray out = new JsonArray();
        int gateCenterX = bounds.center().x();
        addHorizontal(out, "north", bounds.minZ(), bounds.minX(), bounds.maxX(), gateCenterX, gateWidth,
                segmentLength);
        addVertical(out, "east", bounds.maxX(), bounds.minZ(), bounds.maxZ(), Integer.MIN_VALUE, gateWidth,
                segmentLength);
        addHorizontal(out, "south", bounds.maxZ(), bounds.maxX(), bounds.minX(), Integer.MIN_VALUE, gateWidth,
                segmentLength);
        addVertical(out, "west", bounds.minX(), bounds.maxZ(), bounds.minZ(), Integer.MIN_VALUE, gateWidth,
                segmentLength);
        addTower(out, "corner_north_west", bounds.minX(), bounds.minZ());
        addTower(out, "corner_north_east", bounds.maxX(), bounds.minZ());
        addTower(out, "corner_south_east", bounds.maxX(), bounds.maxZ());
        addTower(out, "corner_south_west", bounds.minX(), bounds.maxZ());
        addTower(out, "gate_west_tower", gateCenterX - gateWidth / 2 - 3, bounds.minZ());
        addTower(out, "gate_east_tower", gateCenterX + gateWidth / 2 + 3, bounds.minZ());
        return out;
    }

    private static void addFlankingTowers(JsonArray segments, String gateId, BlockBounds gateBounds) {
        boolean horizontal = gateBounds.widthBlocks() >= gateBounds.heightBlocks();
        if (horizontal) {
            addTower(segments, gateId + "_tower_w", gateBounds.minX() - 3, gateBounds.center().z());
            addTower(segments, gateId + "_tower_e", gateBounds.maxX() + 3, gateBounds.center().z());
        } else {
            addTower(segments, gateId + "_tower_n", gateBounds.center().x(), gateBounds.minZ() - 3);
            addTower(segments, gateId + "_tower_s", gateBounds.center().x(), gateBounds.maxZ() + 3);
        }
    }

    private static void addFallbackGate(JsonObject plan, JsonObject reservation, JsonArray segments, JsonArray gates) {
        JsonArray candidates = array(reservation, "gateCandidateZones");
        if (candidates.isEmpty()) {
            plan.addProperty("gateFallbackReasonCode", "ROAD_MASK_EMPTY_NO_GATE_CANDIDATE");
            return;
        }
        JsonObject candidate = candidates.get(0).getAsJsonObject();
        BlockBounds source = bounds(candidate.getAsJsonObject("blockBounds"));
        BlockBounds gateBounds = new BlockBounds(
                source.center().x() - 4, source.center().z() - 4,
                source.center().x() + 4, source.center().z() + 4);
        JsonObject gate = segment("fallback_gate_0", "gate_gap", "wall_gap_gate_7",
                gateBounds.minX(), gateBounds.minZ(), gateBounds.maxX(), gateBounds.maxZ());
        gate.addProperty("reasonCode", "ROAD_MASK_EMPTY_GATE_FALLBACK");
        gates.add(gate.deepCopy());
        segments.add(gate);
        addFlankingTowers(segments, "fallback_gate_0", gateBounds);
        plan.addProperty("gateFallbackReasonCode", "ROAD_MASK_EMPTY_GATE_FALLBACK");
    }

    private static List<BlockBounds> roadHits(BlockBounds segment, JsonArray roadMasks, int roadMargin) {
        List<BlockBounds> hits = new java.util.ArrayList<>();
        for (JsonElement elem : roadMasks) {
            if (!elem.isJsonObject()) {
                continue;
            }
            BlockBounds road = expand(bounds(elem.getAsJsonObject().getAsJsonObject("blockBounds")), roadMargin);
            if (segment.overlaps(road)) {
                hits.add(road);
            }
        }
        return hits;
    }

    private static boolean nearRoad(BlockBounds segment, JsonArray roadMasks, int margin) {
        BlockBounds expanded = expand(segment, margin);
        for (JsonElement elem : roadMasks) {
            if (elem.isJsonObject()
                    && expanded.overlaps(bounds(elem.getAsJsonObject().getAsJsonObject("blockBounds")))) {
                return true;
            }
        }
        return false;
    }

    private static BlockBounds gateBounds(BlockBounds segment, List<BlockBounds> hits, int gateWidth, int roadMargin) {
        BlockBounds union = hits.get(0);
        for (BlockBounds hit : hits) {
            union = new BlockBounds(
                    Math.min(union.minX(), hit.minX()),
                    Math.min(union.minZ(), hit.minZ()),
                    Math.max(union.maxX(), hit.maxX()),
                    Math.max(union.maxZ(), hit.maxZ()));
        }
        boolean horizontal = segment.widthBlocks() >= segment.heightBlocks();
        int half = Math.max(gateWidth / 2, roadMargin + 3);
        if (horizontal) {
            int cx = clamp(union.center().x(), segment.minX(), segment.maxX());
            return new BlockBounds(cx - half, segment.minZ(), cx + half, segment.maxZ());
        }
        int cz = clamp(union.center().z(), segment.minZ(), segment.maxZ());
        return new BlockBounds(segment.minX(), cz - half, segment.maxX(), cz + half);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void addHorizontal(JsonArray out, String side, int z, int startX, int endX,
                                      int gateCenterX, int gateWidth, int segmentLength) {
        int step = startX <= endX ? segmentLength : -segmentLength;
        for (int x = startX; step > 0 ? x <= endX : x >= endX; x += step) {
            int x2 = step > 0 ? Math.min(endX, x + segmentLength - 1) : Math.max(endX, x - segmentLength + 1);
            if (gateCenterX != Integer.MIN_VALUE && overlapsGate(Math.min(x, x2), Math.max(x, x2),
                    gateCenterX, gateWidth)) {
                addGap(out, side + "_gate_gap", Math.min(x, x2), z - WALL_HALF_THICKNESS_BLOCKS,
                        Math.max(x, x2), z + WALL_HALF_THICKNESS_BLOCKS);
            } else {
                addWall(out, side + "_" + out.size(), "wall_straight_15", Math.min(x, x2),
                        z - WALL_HALF_THICKNESS_BLOCKS, Math.max(x, x2), z + WALL_HALF_THICKNESS_BLOCKS);
            }
        }
    }

    private static void addVertical(JsonArray out, String side, int x, int startZ, int endZ,
                                    int gateCenterZ, int gateWidth, int segmentLength) {
        int step = startZ <= endZ ? segmentLength : -segmentLength;
        for (int z = startZ; step > 0 ? z <= endZ : z >= endZ; z += step) {
            int z2 = step > 0 ? Math.min(endZ, z + segmentLength - 1) : Math.max(endZ, z - segmentLength + 1);
            if (gateCenterZ != Integer.MIN_VALUE && overlapsGate(Math.min(z, z2), Math.max(z, z2),
                    gateCenterZ, gateWidth)) {
                addGap(out, side + "_gate_gap", x - WALL_HALF_THICKNESS_BLOCKS, Math.min(z, z2),
                        x + WALL_HALF_THICKNESS_BLOCKS, Math.max(z, z2));
            } else {
                addWall(out, side + "_" + out.size(), "wall_straight_15",
                        x - WALL_HALF_THICKNESS_BLOCKS, Math.min(z, z2),
                        x + WALL_HALF_THICKNESS_BLOCKS, Math.max(z, z2));
            }
        }
    }

    private static boolean overlapsGate(int min, int max, int center, int width) {
        int gateMin = center - width / 2;
        int gateMax = center + width / 2;
        return min <= gateMax && max >= gateMin;
    }

    private static void addWall(JsonArray out, String segmentId, String templateId,
                                int minX, int minZ, int maxX, int maxZ) {
        JsonObject obj = segment(segmentId, "wall_segment", templateId, minX, minZ, maxX, maxZ);
        out.add(obj);
    }

    private static void addGap(JsonArray out, String segmentId, int minX, int minZ, int maxX, int maxZ) {
        JsonObject obj = segment(segmentId, "gate_gap", "wall_gap_gate_7", minX, minZ, maxX, maxZ);
        out.add(obj);
    }

    private static void addTower(JsonArray out, String segmentId, int x, int z) {
        JsonObject obj = segment(segmentId, "tower", "wall_tower_small", x - 2, z - 2, x + 2, z + 2);
        out.add(obj);
    }

    private static JsonObject segment(String id, String type, String templateId,
                                      int minX, int minZ, int maxX, int maxZ) {
        JsonObject obj = new JsonObject();
        obj.addProperty("segmentId", id);
        obj.addProperty("segmentType", type);
        obj.addProperty("templateId", templateId);
        obj.add("blockBounds", boundsJson(new BlockBounds(Math.min(minX, maxX), Math.min(minZ, maxZ),
                Math.max(minX, maxX), Math.max(minZ, maxZ))));
        return obj;
    }

    private static BlockBounds unionPlaced(JsonObject ledger) {
        BlockBounds union = null;
        JsonArray placed = ledger != null && ledger.has("placedStructures")
                && ledger.get("placedStructures").isJsonArray()
                ? ledger.getAsJsonArray("placedStructures")
                : new JsonArray();
        for (JsonElement elem : placed) {
            if (!elem.isJsonObject() || !elem.getAsJsonObject().has("actualFootprint")) {
                continue;
            }
            BlockBounds bounds = bounds(elem.getAsJsonObject().getAsJsonObject("actualFootprint"));
            union = union == null ? bounds : new BlockBounds(
                    Math.min(union.minX(), bounds.minX()),
                    Math.min(union.minZ(), bounds.minZ()),
                    Math.max(union.maxX(), bounds.maxX()),
                    Math.max(union.maxZ(), bounds.maxZ()));
        }
        return union == null ? new BlockBounds(-32, -32, 32, 32) : union;
    }

    private static BlockBounds expand(BlockBounds bounds, int margin) {
        return new BlockBounds(bounds.minX() - margin, bounds.minZ() - margin,
                bounds.maxX() + margin, bounds.maxZ() + margin);
    }

    private static JsonObject emptyRoadMask(String cityId) {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", "city_actual_road_mask.v0.2");
        obj.addProperty("cityId", cityId);
        obj.addProperty("status", "empty");
        obj.addProperty("reasonCode", "ROAD_MASK_EMPTY_GATE_FALLBACK");
        obj.add("roadMask", new JsonArray());
        return obj;
    }

    private static JsonArray array(JsonObject obj, String key) {
        return obj != null && obj.has(key) && obj.get(key).isJsonArray()
                ? obj.getAsJsonArray(key)
                : new JsonArray();
    }

    private static BlockBounds snap(BlockBounds bounds, int step) {
        return new BlockBounds(
                Math.floorDiv(bounds.minX(), step) * step,
                Math.floorDiv(bounds.minZ(), step) * step,
                Math.floorDiv(bounds.maxX() + step - 1, step) * step - 1,
                Math.floorDiv(bounds.maxZ() + step - 1, step) * step - 1);
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

    private static int intValue(JsonObject obj, String key, int fallback) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : fallback;
    }

    private static String stringValue(JsonObject obj, String key, String fallback) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : fallback;
    }
}
