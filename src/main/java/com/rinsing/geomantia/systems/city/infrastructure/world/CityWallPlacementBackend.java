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
        return execute(level, wallPlan, false, 1);
    }

    public JsonObject execute(ServerLevel level, JsonObject wallPlan, boolean debugScan, int debugScanStepBlocks) {
        JsonObject report = new JsonObject();
        boolean v3 = "city_wall_plan.v0.3".equals(stringValue(wallPlan, "schemaVersion", ""));
        report.addProperty("schemaVersion", v3 ? "city_wall_placement_report.v0.3" : "city_wall_placement_report.v0.1");
        report.addProperty("cityId", stringValue(wallPlan, "cityId", "unknown_city"));
        report.addProperty("backend", "vanilla_setblock");
        report.addProperty("debugScan", debugScan);
        report.addProperty("debugScanStepBlocks", Math.max(1, debugScanStepBlocks));
        JsonArray results = new JsonArray();
        JsonArray unitResults = new JsonArray();
        JsonObject terrainDebug = debugReport("city_wall_terrain_debug_scan.v0.1", report.get("cityId").getAsString());
        JsonObject maskDebug = debugReport("city_wall_mask_conflict_report.v0.1", report.get("cityId").getAsString());
        JsonObject gapDebug = debugReport("city_wall_gap_debug_report.v0.1", report.get("cityId").getAsString());
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
            SegmentResult result = v3
                    ? placeSegmentV3(level, segment, context, debugScan, terrainDebug, maskDebug, gapDebug, unitResults)
                    : placeSegment(level, segment, context);
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
        if (v3) {
            report.add("placementUnitResults", unitResults);
        }
        if (debugScan) {
            report.add("wallTerrainDebugScan", terrainDebug);
            report.add("wallMaskConflictReport", maskDebug);
            report.add("wallGapDebugReport", gapDebug);
        }
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

    private SegmentResult placeSegmentV3(ServerLevel level, JsonObject segment, PlacementContext context,
                                         boolean debugScan, JsonObject terrainDebug, JsonObject maskDebug,
                                         JsonObject gapDebug, JsonArray unitResults) {
        String type = stringValue(segment, "segmentType", "");
        if ("gate_gap".equals(type)) {
            addGap(gapDebug, segment, "WALL_GATE_GAP", "Gate gap is intentionally empty.");
            return new SegmentResult("skipped", "WALL_GATE_GAP", 0,
                    "Temporary gate gap is intentionally left empty.");
        }
        if ("skipped_wall_segment".equals(type)) {
            String reason = stringValue(segment, "reasonCode", "WALL_SEGMENT_SKIPPED");
            addGap(gapDebug, segment, reason, "Wall segment intentionally skipped by planner.");
            return new SegmentResult("skipped", reason, 0, "Wall segment intentionally skipped by planner.");
        }
        BlockBounds bounds = bounds(segment.getAsJsonObject("blockBounds"));
        int changed = 0;
        int placedUnits = 0;
        int skippedUnits = 0;
        for (BlockBounds unit : splitUnits(bounds, context.terrainFitUnitLengthBlocks())) {
            UnitResult unitResult = placeUnit(level, segment, unit, context, debugScan, terrainDebug, maskDebug);
            JsonObject unitJson = unitResult.asJson(stringValue(segment, "segmentId", ""));
            unitResults.add(unitJson);
            if ("executed".equals(unitResult.status())) {
                placedUnits++;
                changed += unitResult.changedBlocks();
            } else {
                skippedUnits++;
                addGap(gapDebug, segment, unitResult.reasonCode(), unitResult.message(), unit);
            }
        }
        if (placedUnits > 0) {
            return new SegmentResult("executed", skippedUnits > 0 ? "WALL_SEGMENT_PARTIALLY_PLACED" : "WALL_SEGMENT_PLACED",
                    changed, "Placed " + placedUnits + " terrain-fit wall units; skipped " + skippedUnits + ".");
        }
        return new SegmentResult("skipped", "WALL_UNIT_SKIPPED_TERRAIN", 0,
                "All terrain-fit wall units were skipped.");
    }

    private UnitResult placeUnit(ServerLevel level, JsonObject segment, BlockBounds unit,
                                 PlacementContext context, boolean debugScan,
                                 JsonObject terrainDebug, JsonObject maskDebug) {
        int protectedCells = protectedCellCount(unit, context);
        if (protectedCells > 0 && protectedCells >= unit.widthBlocks() * unit.heightBlocks()) {
            addMaskConflict(maskDebug, segment, unit, "WALL_UNIT_SKIPPED_MASK", protectedCells);
            return new UnitResult("skipped", "WALL_UNIT_SKIPPED_MASK", "SKIPPED_PROTECTED_MASK", 0,
                    "All unit cells are protected by road/structure mask.", unit, 0, 0, protectedCells);
        }
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        java.util.Map<String, Integer> surfaceSamples = debugScan ? new java.util.LinkedHashMap<>() : java.util.Map.of();
        for (int x = unit.minX(); x <= unit.maxX(); x++) {
            for (int z = unit.minZ(); z <= unit.maxZ(); z++) {
                int y = surfaceY(level, x, z);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                if (debugScan) {
                    surfaceSamples.put(x + "," + z, y);
                }
            }
        }
        int maxDelta = context.maxSegmentHeightDeltaBlocks() <= 0
                ? MAX_SEGMENT_HEIGHT_DELTA : context.maxSegmentHeightDeltaBlocks();
        if (maxY - minY > maxDelta) {
            if (debugScan) {
                addTerrainSamples(terrainDebug, segment, unit, context, surfaceSamples, -1);
            }
            return new UnitResult("skipped", "WALL_UNIT_SKIPPED_TERRAIN", "SKIPPED_TOO_STEEP", 0,
                    "Unit terrain delta " + (maxY - minY) + " exceeds " + maxDelta + ".",
                    unit, minY, maxY, protectedCells);
        }
        int baseY = maxY + 1;
        if (debugScan) {
            addTerrainSamples(terrainDebug, segment, unit, context, surfaceSamples, baseY);
        }
        int changed = "tower".equals(stringValue(segment, "segmentType", ""))
                ? placeTower(level, unit, baseY, context)
                : placeWall(level, unit, baseY, context);
        String mode = maxY == minY ? "FLAT_PLACED"
                : (baseY - minY > 1 ? "FOUNDATION_FILLED" : "STEPPED_PLACED");
        return new UnitResult("executed", "WALL_UNIT_PLACED", mode, changed,
                "Placed terrain-fit wall unit.", unit, minY, maxY, protectedCells);
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

    private static java.util.List<BlockBounds> splitUnits(BlockBounds bounds, int unitLength) {
        int length = Math.max(1, unitLength);
        java.util.List<BlockBounds> out = new java.util.ArrayList<>();
        boolean horizontal = bounds.widthBlocks() >= bounds.heightBlocks();
        if (horizontal) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x += length) {
                out.add(new BlockBounds(x, bounds.minZ(), Math.min(bounds.maxX(), x + length - 1), bounds.maxZ()));
            }
        } else {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z += length) {
                out.add(new BlockBounds(bounds.minX(), z, bounds.maxX(), Math.min(bounds.maxZ(), z + length - 1)));
            }
        }
        return out;
    }

    private static int protectedCellCount(BlockBounds bounds, PlacementContext context) {
        int count = 0;
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                if (context.protects(x, z) || context.protectsFootprint(x, z)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static JsonObject debugReport(String schema, String cityId) {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", schema);
        obj.addProperty("cityId", cityId);
        obj.add("samples", new JsonArray());
        obj.add("conflicts", new JsonArray());
        obj.add("gaps", new JsonArray());
        return obj;
    }

    private static void addTerrainSample(JsonObject terrainDebug, int x, int z, int surfaceY, int baseY,
                                         JsonObject segment, BlockBounds unit, PlacementContext context) {
        JsonObject sample = new JsonObject();
        sample.addProperty("segmentId", stringValue(segment, "segmentId", ""));
        sample.addProperty("x", x);
        sample.addProperty("z", z);
        sample.addProperty("surfaceY", surfaceY);
        sample.addProperty("baseY", baseY);
        sample.addProperty("foundationDepthRequired", baseY < 0 ? -1 : Math.max(0, baseY - surfaceY));
        sample.addProperty("maxFoundationDepthBlocks", context.maxFoundationDepthBlocks());
        sample.addProperty("protectedByRoad", context.protects(x, z));
        sample.addProperty("protectedByStructure", context.protectsFootprint(x, z));
        sample.add("unitBounds", boundsJson(unit));
        terrainDebug.getAsJsonArray("samples").add(sample);
    }

    private static void addTerrainSamples(JsonObject terrainDebug, JsonObject segment, BlockBounds unit,
                                          PlacementContext context, java.util.Map<String, Integer> surfaceSamples,
                                          int baseY) {
        for (java.util.Map.Entry<String, Integer> entry : surfaceSamples.entrySet()) {
            String[] parts = entry.getKey().split(",", 2);
            addTerrainSample(terrainDebug, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    entry.getValue(), baseY, segment, unit, context);
        }
    }

    private static void addMaskConflict(JsonObject maskDebug, JsonObject segment, BlockBounds unit,
                                        String reasonCode, int protectedCells) {
        JsonObject conflict = new JsonObject();
        conflict.addProperty("segmentId", stringValue(segment, "segmentId", ""));
        conflict.addProperty("reasonCode", reasonCode);
        conflict.addProperty("protectedCellCount", protectedCells);
        conflict.add("blockBounds", boundsJson(unit));
        conflict.add("manualInspectTp", manualInspectTp(unit));
        maskDebug.getAsJsonArray("conflicts").add(conflict);
    }

    private static void addGap(JsonObject gapDebug, JsonObject segment, String reasonCode, String message) {
        addGap(gapDebug, segment, reasonCode, message, bounds(segment.getAsJsonObject("blockBounds")));
    }

    private static void addGap(JsonObject gapDebug, JsonObject segment, String reasonCode,
                               String message, BlockBounds bounds) {
        JsonObject gap = new JsonObject();
        gap.addProperty("gapId", "wall_gap_" + gapDebug.getAsJsonArray("gaps").size());
        gap.addProperty("segmentId", stringValue(segment, "segmentId", ""));
        gap.addProperty("primaryReason", reasonCode);
        gap.addProperty("message", message);
        gap.add("gapBounds", boundsJson(bounds));
        gap.add("manualInspectTp", manualInspectTp(bounds));
        gapDebug.getAsJsonArray("gaps").add(gap);
    }

    private static JsonObject manualInspectTp(BlockBounds bounds) {
        JsonObject obj = new JsonObject();
        obj.addProperty("x", bounds.center().x());
        obj.addProperty("y", 140);
        obj.addProperty("z", bounds.center().z());
        obj.addProperty("command", "/tp " + bounds.center().x() + " 140 " + bounds.center().z());
        return obj;
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

    private record UnitResult(String status, String reasonCode, String terrainFitMode, int changedBlocks,
                              String message, BlockBounds bounds, int minSurfaceY, int maxSurfaceY,
                              int protectedCellCount) {
        JsonObject asJson(String segmentId) {
            JsonObject obj = new JsonObject();
            obj.addProperty("segmentId", segmentId);
            obj.addProperty("status", status);
            obj.addProperty("reasonCode", reasonCode);
            obj.addProperty("terrainFitMode", terrainFitMode);
            obj.addProperty("changedBlocks", changedBlocks);
            obj.addProperty("message", message);
            obj.addProperty("minSurfaceY", minSurfaceY);
            obj.addProperty("maxSurfaceY", maxSurfaceY);
            obj.addProperty("terrainDelta", maxSurfaceY - minSurfaceY);
            obj.addProperty("protectedCellCount", protectedCellCount);
            obj.add("blockBounds", boundsJson(bounds));
            obj.add("manualInspectTp", manualInspectTp(bounds));
            return obj;
        }
    }

    private record PlacementContext(java.util.List<BlockBounds> roadMasks,
                                    java.util.List<BlockBounds> footprints,
                                    int maxFoundationDepthBlocks,
                                    int maxSegmentHeightDeltaBlocks,
                                    int terrainFitUnitLengthBlocks) {
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
                    intValue(wallPlan, "maxSegmentHeightDeltaBlocks", MAX_SEGMENT_HEIGHT_DELTA),
                    Math.max(1, intValue(wallPlan, "terrainFitUnitLengthBlocks", 5)));
        }

        boolean protects(int x, int z) {
            return roadMasks.stream().anyMatch(bounds -> bounds.contains(x, z));
        }

        boolean protectsFootprint(int x, int z) {
            return footprints.stream().anyMatch(bounds -> bounds.contains(x, z));
        }

        boolean overlapsRoad(BlockBounds bounds) {
            return roadMasks.stream().anyMatch(road -> road.overlaps(bounds));
        }

        boolean overlapsFootprint(BlockBounds bounds) {
            return footprints.stream().anyMatch(footprint -> footprint.overlaps(bounds));
        }

    }
}
