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
    private static final String POLICY_V31 = "v3.1";

    public JsonObject execute(ServerLevel level, JsonObject wallPlan) {
        return execute(level, wallPlan, false, 1);
    }

    public JsonObject execute(ServerLevel level, JsonObject wallPlan, boolean debugScan, int debugScanStepBlocks) {
        JsonObject report = new JsonObject();
        boolean v3 = "city_wall_plan.v0.3".equals(stringValue(wallPlan, "schemaVersion", ""));
        boolean v4 = "city_wall_plan.v0.4".equals(stringValue(wallPlan, "schemaVersion", ""));
        report.addProperty("schemaVersion", v4 ? "city_wall_placement_report.v0.4"
                : v3 ? "city_wall_placement_report.v0.3" : "city_wall_placement_report.v0.1");
        report.addProperty("cityId", stringValue(wallPlan, "cityId", "unknown_city"));
        report.addProperty("backend", "vanilla_setblock");
        report.addProperty("debugScan", debugScan);
        report.addProperty("debugScanStepBlocks", Math.max(1, debugScanStepBlocks));
        JsonArray results = new JsonArray();
        JsonArray unitResults = new JsonArray();
        JsonArray nodeResults = new JsonArray();
        JsonArray connectorResults = new JsonArray();
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
        report.addProperty("terrainPolicyVersion", context.wallTerrainPolicy());
        if (v4) {
            for (JsonElement elem : array(wallPlan, "wallNodes")) {
                if (!elem.isJsonObject()) {
                    continue;
                }
                JsonObject node = elem.getAsJsonObject();
                JsonObject segment = pseudoSegment(node, "nodeId", v4NodeSegmentType(node));
                SegmentResult result = placeWallNodeV4(level, segment, context, debugScan, terrainDebug, gapDebug);
                if ("executed".equals(result.status())) {
                    executed++;
                    changed += result.changedBlocks();
                } else {
                    skipped++;
                }
                nodeResults.add(result.asJson(segment));
            }
            for (JsonElement elem : array(wallPlan, "wallUnits")) {
                if (!elem.isJsonObject()) {
                    continue;
                }
                JsonObject unit = elem.getAsJsonObject();
                UnitResult result = placeGraphUnitV4(level, unit, context, debugScan, terrainDebug, maskDebug, gapDebug);
                JsonObject unitJson = result.asJson(stringValue(unit, "unitId", ""));
                unitJson.addProperty("unitId", stringValue(unit, "unitId", ""));
                unitJson.addProperty("unitType", stringValue(unit, "unitType", ""));
                unitJson.addProperty("plannedTargetY", intValue(unit, "targetY", 0));
                unitJson.addProperty("plannedHeightMode", stringValue(unit, "heightMode", ""));
                unitJson.addProperty("wallAxis", stringValue(unit, "wallAxis", ""));
                unitResults.add(unitJson);
                if ("executed".equals(result.status())) {
                    executed++;
                    changed += result.changedBlocks();
                } else {
                    skipped++;
                }
            }
            for (JsonElement elem : array(wallPlan, "nodeConnectorUnits")) {
                if (!elem.isJsonObject()) {
                    continue;
                }
                JsonObject connector = elem.getAsJsonObject();
                UnitResult result = placeConnectorUnitV4(level, connector, context, debugScan,
                        terrainDebug, maskDebug, gapDebug);
                JsonObject connectorJson = result.asJson(stringValue(connector, "connectorId", ""));
                connectorJson.addProperty("connectorId", stringValue(connector, "connectorId", ""));
                connectorJson.addProperty("nodeId", stringValue(connector, "nodeId", ""));
                connectorJson.addProperty("connectorStatus", stringValue(connector, "connectorStatus", ""));
                connectorJson.addProperty("connectorMode", stringValue(connector, "connectorMode", ""));
                connectorResults.add(connectorJson);
                if ("executed".equals(result.status())) {
                    executed++;
                    changed += result.changedBlocks();
                } else {
                    skipped++;
                }
            }
        } else {
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
        }
        report.addProperty("ok", true);
        report.addProperty("executedSegments", executed);
        report.addProperty("skippedSegments", skipped);
        report.addProperty("changedBlocks", changed);
        report.addProperty("roadProtectedBlockCount", context.roadMasks().size());
        report.add("segmentResults", results);
        if (v3 || v4) {
            report.add("placementUnitResults", unitResults);
        }
        if (v4) {
            report.add("wallNodeResults", nodeResults);
            report.add("wallUnitResults", unitResults.deepCopy());
            report.add("connectorResults", connectorResults);
        }
        if (debugScan) {
            report.add("wallTerrainDebugScan", terrainDebug);
            report.add("wallMaskConflictReport", maskDebug);
            report.add("wallGapDebugReport", gapDebug);
        }
        return report;
    }

    private SegmentResult placeWallNodeV4(ServerLevel level, JsonObject segment, PlacementContext context,
                                          boolean debugScan, JsonObject terrainDebug, JsonObject gapDebug) {
        String type = stringValue(segment, "segmentType", "");
        if ("natural_boundary_endpoint".equals(type)) {
            addGap(gapDebug, segment, "NATURAL_BOUNDARY_ENDPOINT",
                    "Natural boundary endpoint node is informational.");
            return new SegmentResult("skipped", "NATURAL_BOUNDARY_ENDPOINT", 0,
                    "Natural boundary endpoint node is informational.");
        }
        if ("junction".equals(type) || "beacon_tower".equals(type)) {
            addGap(gapDebug, segment, "V4_GRAPH_NODE_NO_INDEPENDENT_PLACEMENT",
                    "V4 graph connector node is represented by adjacent wall and connector units.");
            return new SegmentResult("skipped", "V4_GRAPH_NODE_NO_INDEPENDENT_PLACEMENT", 0,
                    "V4 graph connector node is represented by adjacent wall and connector units.");
        }
        if ("gatehouse".equals(type)) {
            SegmentResult result = placeGatehouseSegment(level, segment, context, debugScan, terrainDebug);
            if ("skipped".equals(result.status())) {
                addGap(gapDebug, segment, result.reasonCode(), result.message());
            }
            return result;
        }
        return placeSegment(level, segment, context);
    }

    private UnitResult placeGraphUnitV4(ServerLevel level, JsonObject unit, PlacementContext context,
                                       boolean debugScan, JsonObject terrainDebug,
                                       JsonObject maskDebug, JsonObject gapDebug) {
        String unitType = stringValue(unit, "unitType", "");
        String reason = stringValue(unit, "reasonCode", "V4_WALL_UNIT");
        BlockBounds bounds = bounds(unit.getAsJsonObject("blockBounds"));
        if (!booleanValue(unit, "placementAllowed", true)
                || "natural_boundary_gap".equals(unitType)
                || "skipped_wall_unit".equals(unitType)) {
            addGap(gapDebug, pseudoSegment(unit, "unitId", unitType), reason,
                    "Graph unit intentionally skipped by v4 planner.", bounds);
            return new UnitResult("skipped", reason, unitType, 0,
                    "Graph unit intentionally skipped by v4 planner.", bounds, 0, 0, 0,
                    context.wallTerrainPolicy(), "LOW", new JsonArray(), new JsonObject());
        }
        JsonObject segment = pseudoSegment(unit, "unitId", "wall_segment");
        Axis axis = axisForSegment(segment, bounds);
        return placeUnit(level, segment, bounds, axis, context, debugScan, terrainDebug, maskDebug);
    }

    private UnitResult placeConnectorUnitV4(ServerLevel level, JsonObject connector, PlacementContext context,
                                           boolean debugScan, JsonObject terrainDebug,
                                           JsonObject maskDebug, JsonObject gapDebug) {
        String status = stringValue(connector, "connectorStatus", "");
        BlockBounds bounds = bounds(connector.getAsJsonObject("blockBounds"));
        if ("skipped".equals(status) || "blocked".equals(status)) {
            String reason = "blocked".equals(status) ? "NODE_CONNECTOR_BLOCKED" : "NODE_CONNECTOR_SKIPPED";
            addGap(gapDebug, pseudoSegment(connector, "connectorId", "node_connector"), reason,
                    "Node connector intentionally skipped by v4 graph.", bounds);
            return new UnitResult("skipped", reason, status, 0,
                    "Node connector intentionally skipped by v4 graph.", bounds, 0, 0, 0,
                    context.wallTerrainPolicy(), "LOW", new JsonArray(), new JsonObject());
        }
        JsonObject segment = pseudoSegment(connector, "connectorId", "wall_segment");
        Axis axis = axisForSegment(segment, bounds);
        UnitResult result = placeUnit(level, segment, bounds, axis, context, debugScan, terrainDebug, maskDebug);
        if ("executed".equals(result.status()) && "stepped".equals(status)) {
            int baseY = Math.max(result.maxSurfaceY() + 1, intValue(connector, "targetY", result.maxSurfaceY() + 1));
            int changed = placeConnectorCap(level, bounds, baseY, context);
            return new UnitResult("executed", result.reasonCode(), "STAIR_CONNECTOR_PLACED",
                    result.changedBlocks() + changed, "Placed stepped node connector.",
                    bounds, result.minSurfaceY(), result.maxSurfaceY(), result.protectedCellCount(),
                    result.terrainPolicyVersion(), result.terrainDeltaBand(), result.stepSlices(),
                    result.mountainProbe());
        }
        return result;
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
                : placeWall(level, bounds, baseY, context, axisForSegment(segment, bounds));
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
        if ("gatehouse".equals(type)) {
            SegmentResult result = placeGatehouseSegment(level, segment, context, debugScan, terrainDebug);
            if ("skipped".equals(result.status())) {
                addGap(gapDebug, segment, result.reasonCode(), result.message());
            }
            return result;
        }
        if ("natural_boundary".equals(type)) {
            addGap(gapDebug, segment, stringValue(segment, "reasonCode", "NATURAL_BOUNDARY_NO_WALL"),
                    "Natural boundary intentionally skips continuous wall.");
            return new SegmentResult("skipped", stringValue(segment, "reasonCode", "NATURAL_BOUNDARY_NO_WALL"), 0,
                    "Natural boundary intentionally skips continuous wall.");
        }
        if ("skipped_wall_segment".equals(type)) {
            String reason = stringValue(segment, "reasonCode", "WALL_SEGMENT_SKIPPED");
            addGap(gapDebug, segment, reason, "Wall segment intentionally skipped by planner.");
            return new SegmentResult("skipped", reason, 0, "Wall segment intentionally skipped by planner.");
        }
        BlockBounds bounds = bounds(segment.getAsJsonObject("blockBounds"));
        Axis axis = axisForSegment(segment, bounds);
        int changed = 0;
        int placedUnits = 0;
        int skippedUnits = 0;
        for (BlockBounds unit : splitUnits(bounds, context.terrainFitUnitLengthBlocks(), axis)) {
            UnitResult unitResult = placeUnit(level, segment, unit, axis, context, debugScan, terrainDebug, maskDebug);
            JsonObject unitJson = unitResult.asJson(stringValue(segment, "segmentId", ""));
            unitJson.addProperty("wallAxis", axis.name());
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

    private UnitResult placeUnit(ServerLevel level, JsonObject segment, BlockBounds unit, Axis axis,
                                 PlacementContext context, boolean debugScan,
                                 JsonObject terrainDebug, JsonObject maskDebug) {
        int protectedCells = protectedCellCount(unit, context);
        if (protectedCells > 0 && protectedCells >= unit.widthBlocks() * unit.heightBlocks()) {
            addMaskConflict(maskDebug, segment, unit, "WALL_UNIT_SKIPPED_MASK", protectedCells);
            return new UnitResult("skipped", "WALL_UNIT_SKIPPED_MASK", "SKIPPED_PROTECTED_MASK", 0,
                    "All unit cells are protected by road/structure mask.", unit, 0, 0, protectedCells,
                    context.wallTerrainPolicy(), "LOW", new JsonArray(), new JsonObject());
        }
        TerrainSample terrain = sampleTerrain(level, unit, debugScan);
        int minY = terrain.minY();
        int maxY = terrain.maxY();
        int delta = Math.max(0, maxY - minY);
        int maxDelta = context.maxSegmentHeightDeltaBlocks() <= 0
                ? MAX_SEGMENT_HEIGHT_DELTA : context.maxSegmentHeightDeltaBlocks();
        if (!context.isV31Policy() && delta > maxDelta) {
            if (debugScan) {
                addTerrainSamples(terrainDebug, segment, unit, context, terrain.surfaceSamples(), -1,
                        "SKIPPED_TOO_STEEP");
            }
            return new UnitResult("skipped", "WALL_UNIT_SKIPPED_TERRAIN", "SKIPPED_TOO_STEEP", 0,
                    "Unit terrain delta " + delta + " exceeds " + maxDelta + ".",
                    unit, minY, maxY, protectedCells, context.wallTerrainPolicy(),
                    terrainDeltaBand(delta, context), new JsonArray(), new JsonObject());
        }
        if (context.isV31Policy() && delta > context.flatMaxDeltaBlocks()) {
            if (delta <= context.steppedMaxDeltaBlocks()) {
                return placeSteppedWallUnit(level, segment, unit, context, debugScan, terrainDebug,
                        protectedCells, terrain, axis);
            }
            MountainProbe probe = probeMountain(level, unit, axis, context, terrain);
            if (probe.embeddable()) {
                return placeEmbeddedSlopeUnit(level, segment, unit, context, debugScan, terrainDebug,
                        protectedCells, terrain, probe, axis);
            }
            return markNaturalCliffBoundary(level, segment, unit, context, debugScan, terrainDebug,
                    protectedCells, terrain, probe);
        }
        int baseY = maxY + 1;
        if (debugScan) {
            addTerrainSamples(terrainDebug, segment, unit, context, terrain.surfaceSamples(), baseY,
                    "FLAT_OR_FOUNDATION");
        }
        int changed = "tower".equals(stringValue(segment, "segmentType", ""))
                ? placeTower(level, unit, baseY, context)
                : placeWall(level, unit, baseY, context, axis);
        String mode = maxY == minY ? "FLAT_PLACED"
                : (baseY - minY > 1 ? "FOUNDATION_FILLED" : "STEPPED_PLACED");
        return new UnitResult("executed", "WALL_UNIT_PLACED", mode, changed,
                "Placed terrain-fit wall unit.", unit, minY, maxY, protectedCells, context.wallTerrainPolicy(),
                terrainDeltaBand(delta, context), new JsonArray(), new JsonObject());
    }

    private UnitResult placeSteppedWallUnit(ServerLevel level, JsonObject segment, BlockBounds unit,
                                            PlacementContext context, boolean debugScan,
                                            JsonObject terrainDebug, int protectedCells,
                                            TerrainSample terrain, Axis axis) {
        JsonArray stepSlices = new JsonArray();
        int changed = 0;
        int sliceLength = Math.max(2, Math.min(5, Math.max(1, context.terrainFitUnitLengthBlocks() / 2)));
        for (BlockBounds slice : splitUnits(unit, sliceLength, axis)) {
            TerrainSample sliceTerrain = sampleTerrain(level, slice, false);
            int baseY = sliceTerrain.maxY() + 1;
            changed += "tower".equals(stringValue(segment, "segmentType", ""))
                    ? placeTower(level, slice, baseY, context)
                    : placeWall(level, slice, baseY, context, axis);
            JsonObject sliceObj = new JsonObject();
            sliceObj.add("bounds", boundsJson(slice));
            sliceObj.addProperty("baseY", baseY);
            sliceObj.addProperty("minSurfaceY", sliceTerrain.minY());
            sliceObj.addProperty("maxSurfaceY", sliceTerrain.maxY());
            sliceObj.addProperty("terrainDelta", Math.max(0, sliceTerrain.maxY() - sliceTerrain.minY()));
            stepSlices.add(sliceObj);
        }
        if (debugScan) {
            addTerrainSamples(terrainDebug, segment, unit, context, terrain.surfaceSamples(),
                    terrain.maxY() + 1, "STEPPED_WALL_PLACED");
        }
        return new UnitResult("executed", "WALL_UNIT_PLACED", "STEPPED_WALL_PLACED", changed,
                "Placed stepped wall unit for mid-delta terrain.", unit, terrain.minY(), terrain.maxY(),
                protectedCells, context.wallTerrainPolicy(), terrainDeltaBand(terrain.delta(), context),
                stepSlices, new JsonObject());
    }

    private UnitResult placeEmbeddedSlopeUnit(ServerLevel level, JsonObject segment, BlockBounds unit,
                                              PlacementContext context, boolean debugScan,
                                              JsonObject terrainDebug, int protectedCells,
                                              TerrainSample terrain, MountainProbe probe, Axis axis) {
        int changed = 0;
        BlockBounds cap = towerBounds(unit.center().x(), unit.center().z(), 5);
        int capBaseY = surfaceY(level, cap.center().x(), cap.center().z()) + 1;
        if (context.embeddedSlopeTower()) {
            changed += placeTower(level, cap, capBaseY, context);
        } else {
            changed += placeStoneCap(level, cap, capBaseY, context);
        }
        changed += placeEmbeddedStoneCap(level, unit, probe, capBaseY, context, axis);
        if (debugScan) {
            addTerrainSamples(terrainDebug, segment, unit, context, terrain.surfaceSamples(), capBaseY,
                    "EMBEDDED_IN_SLOPE");
        }
        return new UnitResult("executed", "WALL_EMBEDDED_IN_SLOPE", "EMBEDDED_IN_SLOPE", changed,
                "High-delta wall unit embedded into adjacent mountain slope.", unit, terrain.minY(), terrain.maxY(),
                protectedCells, context.wallTerrainPolicy(), terrainDeltaBand(terrain.delta(), context),
                new JsonArray(), probe.asJson());
    }

    private UnitResult markNaturalCliffBoundary(ServerLevel level, JsonObject segment, BlockBounds unit,
                                                PlacementContext context, boolean debugScan,
                                                JsonObject terrainDebug, int protectedCells,
                                                TerrainSample terrain, MountainProbe probe) {
        BlockBounds marker = towerBounds(unit.center().x(), unit.center().z(), 5);
        int baseY = surfaceY(level, marker.center().x(), marker.center().z()) + 1;
        int changed = context.embeddedSlopeTower() ? placeStoneCap(level, marker, baseY, context) : 0;
        if (debugScan) {
            addTerrainSamples(terrainDebug, segment, unit, context, terrain.surfaceSamples(), baseY,
                    "NATURAL_CLIFF_BOUNDARY");
        }
        return new UnitResult(changed > 0 ? "executed" : "skipped",
                changed > 0 ? "NATURAL_CLIFF_BOUNDARY" : "WALL_UNIT_SKIPPED_UNSUITABLE",
                changed > 0 ? "NATURAL_CLIFF_BOUNDARY" : "SKIPPED_UNSUITABLE",
                changed,
                changed > 0
                        ? "Marked high-delta terrain as natural cliff boundary with a stone marker."
                        : "High-delta terrain was unsuitable for wall or natural boundary marker.",
                unit, terrain.minY(), terrain.maxY(), protectedCells, context.wallTerrainPolicy(),
                terrainDeltaBand(terrain.delta(), context), new JsonArray(), probe.asJson());
    }

    private SegmentResult placeGatehouseSegment(ServerLevel level, JsonObject segment, PlacementContext context,
                                                boolean debugScan, JsonObject terrainDebug) {
        BlockBounds bounds = bounds(segment.getAsJsonObject("blockBounds"));
        Axis axis = axisForSegment(segment, bounds);
        TerrainSample terrain = sampleTerrain(level, bounds, debugScan);
        int baseY = terrain.maxY() + 1;
        if (debugScan) {
            addTerrainSamples(terrainDebug, segment, bounds, context, terrain.surfaceSamples(), baseY,
                    "GATEHOUSE_PLACED");
        }
        int changed = placeGatehouse(level, bounds, baseY, context, axis);
        return new SegmentResult("executed", "GATEHOUSE_PLACED", changed,
                "Placed independent gatehouse template with full inner/outer road opening.");
    }

    private int placeGatehouse(ServerLevel level, BlockBounds bounds, int baseY, PlacementContext context, Axis axis) {
        int changed = 0;
        int minAlong = axis == Axis.X ? bounds.minX() : bounds.minZ();
        int maxAlong = axis == Axis.X ? bounds.maxX() : bounds.maxZ();
        int minAcross = axis == Axis.X ? bounds.minZ() : bounds.minX();
        int maxAcross = axis == Axis.X ? bounds.maxZ() : bounds.maxX();
        int centerAlong = (minAlong + maxAlong) / 2;
        int halfOpening = Math.max(2, Math.min(4, (maxAlong - minAlong + 1) / 4));
        for (int along = minAlong; along <= maxAlong; along++) {
            for (int across = minAcross; across <= maxAcross; across++) {
                int x = axis == Axis.X ? along : across;
                int z = axis == Axis.X ? across : along;
                boolean opening = Math.abs(along - centerAlong) <= halfOpening;
                boolean sidePier = !opening && (along <= minAlong + 2 || along >= maxAlong - 2);
                boolean sideWall = !opening && (along <= centerAlong - halfOpening - 1 || along >= centerAlong + halfOpening + 1);
                if (opening) {
                    clearGateColumn(level, x, z, baseY, 5);
                    continue;
                }
                changed += placeFoundation(level, x, z, baseY, context);
                for (int y = 0; y < 9; y++) {
                    BlockState state = sidePier
                            ? (y >= 7 ? Blocks.STONE_BRICK_WALL.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState())
                            : sideWall && (y <= 5 || y >= 7)
                            ? Blocks.STONE_BRICKS.defaultBlockState()
                            : null;
                    if (state != null && set(level, x, baseY + y, z, state)) {
                        changed++;
                    }
                }
            }
        }
        for (int along = centerAlong - halfOpening - 1; along <= centerAlong + halfOpening + 1; along++) {
            for (int across = minAcross; across <= maxAcross; across++) {
                int x = axis == Axis.X ? along : across;
                int z = axis == Axis.X ? across : along;
                if (set(level, x, baseY + 5, z, Blocks.OAK_LOG.defaultBlockState())) {
                    changed++;
                }
                if (set(level, x, baseY + 6, z, Blocks.OAK_PLANKS.defaultBlockState())) {
                    changed++;
                }
            }
        }
        return changed;
    }

    private void clearGateColumn(ServerLevel level, int x, int z, int baseY, int height) {
        for (int y = 0; y <= height; y++) {
            level.setBlock(new BlockPos(x, baseY + y, z), Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private int placeConnectorCap(ServerLevel level, BlockBounds bounds, int baseY, PlacementContext context) {
        int changed = 0;
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                if (context.protectsAny(x, z)) {
                    continue;
                }
                changed += placeFoundation(level, x, z, baseY, context);
                if (set(level, x, baseY, z, Blocks.STONE_BRICKS.defaultBlockState())) {
                    changed++;
                }
                if (set(level, x, baseY + 1, z, Blocks.STONE_BRICK_STAIRS.defaultBlockState())) {
                    changed++;
                }
                boolean edge = x == bounds.minX() || x == bounds.maxX()
                        || z == bounds.minZ() || z == bounds.maxZ();
                if (edge && set(level, x, baseY + 2, z, Blocks.STONE_BRICK_WALL.defaultBlockState())) {
                    changed++;
                }
            }
        }
        return changed;
    }

    private int placeWall(ServerLevel level, BlockBounds bounds, int baseY, PlacementContext context, Axis axis) {
        int changed = 0;
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                if (context.protectsAny(x, z)) {
                    continue;
                }
                int across = axis == Axis.X ? z - bounds.minZ() : x - bounds.minX();
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
                if ((axis == Axis.X ? x : z) % 2 == 0 && (across == 0 || across == 4)
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
                if (context.protectsAny(x, z)) {
                    continue;
                }
                boolean edge = x == bounds.minX() || x == bounds.maxX() || z == bounds.minZ() || z == bounds.maxZ();
                boolean doorway = z == bounds.minZ() && x == bounds.center().x();
                changed += placeFoundation(level, x, z, baseY, context);
                for (int y = 0; y < 12; y++) {
                    BlockState state = null;
                    if (doorway && y >= 1 && y <= 3) {
                        state = Blocks.AIR.defaultBlockState();
                    } else if (edge) {
                        state = y == 0 ? Blocks.DEEPSLATE_BRICKS.defaultBlockState()
                                : y >= 10 ? Blocks.STONE_BRICK_WALL.defaultBlockState()
                                : Blocks.STONE_BRICKS.defaultBlockState();
                    } else if (y == 0 || y == 9) {
                        state = Blocks.STONE_BRICKS.defaultBlockState();
                    }
                    if (state != null && set(level, x, baseY + y, z, state)) {
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    private int placeStoneCap(ServerLevel level, BlockBounds bounds, int baseY, PlacementContext context) {
        int changed = 0;
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                if (context.protectsAny(x, z)) {
                    continue;
                }
                changed += placeFoundation(level, x, z, baseY, context);
                boolean edge = x == bounds.minX() || x == bounds.maxX() || z == bounds.minZ() || z == bounds.maxZ();
                for (int y = 0; y < 5; y++) {
                    BlockState state = y >= 4 && edge
                            ? Blocks.STONE_BRICK_WALL.defaultBlockState()
                            : Blocks.STONE_BRICKS.defaultBlockState();
                    if (set(level, x, baseY + y, z, state)) {
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    private int placeEmbeddedStoneCap(ServerLevel level, BlockBounds unit, MountainProbe probe,
                                      int baseY, PlacementContext context, Axis axis) {
        int changed = 0;
        int side = probe.highSide();
        int maxEmbed = Math.min(4, context.mountainProbeDistanceBlocks());
        if (axis == Axis.X) {
            for (int x = unit.minX(); x <= unit.maxX(); x++) {
                for (int d = 0; d < maxEmbed; d++) {
                    int z = side < 0 ? unit.minZ() - d : unit.maxZ() + d;
                    changed += placeEmbeddedColumn(level, x, z, baseY, context);
                }
            }
        } else {
            for (int z = unit.minZ(); z <= unit.maxZ(); z++) {
                for (int d = 0; d < maxEmbed; d++) {
                    int x = side < 0 ? unit.minX() - d : unit.maxX() + d;
                    changed += placeEmbeddedColumn(level, x, z, baseY, context);
                }
            }
        }
        return changed;
    }

    private int placeEmbeddedColumn(ServerLevel level, int x, int z, int baseY, PlacementContext context) {
        if (context.protectsAny(x, z)) {
            return 0;
        }
        int changed = 0;
        int surface = surfaceY(level, x, z);
        int fromY = Math.min(surface, baseY);
        int toY = Math.min(Math.max(surface + 3, baseY + 3), baseY + 8);
        for (int y = fromY; y <= toY; y++) {
            if (set(level, x, y, z, Blocks.STONE_BRICKS.defaultBlockState())) {
                changed++;
            }
        }
        return changed;
    }

    private int placeFoundation(ServerLevel level, int x, int z, int baseY, PlacementContext context) {
        if (context.protectsAny(x, z)) {
            return 0;
        }
        int changed = 0;
        int surface = surfaceY(level, x, z);
        int depth = Math.min(context.effectiveFoundationDepthBlocks(), Math.max(0, baseY - surface));
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
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int minY = level.getMinBuildHeight();
        int skipped = 0;
        while (y > minY && skipped < 32
                && isTemporaryWallBlock(level.getBlockState(new BlockPos(x, y - 1, z)))) {
            y--;
            skipped++;
        }
        return y;
    }

    private boolean isTemporaryWallBlock(BlockState state) {
        return state.is(Blocks.STONE_BRICKS)
                || state.is(Blocks.DEEPSLATE_BRICKS)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.STONE_BRICK_WALL)
                || state.is(Blocks.STONE_BRICK_SLAB)
                || state.is(Blocks.STONE_BRICK_STAIRS)
                || state.is(Blocks.OAK_LOG)
                || state.is(Blocks.OAK_PLANKS);
    }

    private TerrainSample sampleTerrain(ServerLevel level, BlockBounds bounds, boolean keepSamples) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        java.util.Map<String, Integer> samples = keepSamples ? new java.util.LinkedHashMap<>() : java.util.Map.of();
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                int y = surfaceY(level, x, z);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                if (keepSamples) {
                    samples.put(x + "," + z, y);
                }
            }
        }
        if (minY == Integer.MAX_VALUE) {
            minY = 0;
            maxY = 0;
        }
        return new TerrainSample(minY, maxY, samples);
    }

    private MountainProbe probeMountain(ServerLevel level, BlockBounds unit, Axis axis, PlacementContext context,
                                        TerrainSample terrain) {
        SideProbe negative = probeSide(level, unit, axis, context, -1);
        SideProbe positive = probeSide(level, unit, axis, context, 1);
        SideProbe high = negative.averageY() >= positive.averageY() ? negative : positive;
        SideProbe low = high == negative ? positive : negative;
        double heightDiff = high.averageY() - low.averageY();
        boolean embeddable = terrain.delta() >= context.naturalBoundaryMinDeltaBlocks()
                && heightDiff >= Math.max(6, context.flatMaxDeltaBlocks())
                && high.solidRatio() >= 0.65D;
        return new MountainProbe(high.side(), negative.averageY(), positive.averageY(),
                negative.solidRatio(), positive.solidRatio(), heightDiff, embeddable,
                embeddable ? "adjacent_solid_high_slope" : "no_continuous_mountain_side");
    }

    private SideProbe probeSide(ServerLevel level, BlockBounds unit, Axis axis, PlacementContext context, int side) {
        int samples = 0;
        int solid = 0;
        long sumY = 0;
        if (axis == Axis.X) {
            for (int x = unit.minX(); x <= unit.maxX(); x++) {
                for (int d = 1; d <= context.mountainProbeDistanceBlocks(); d++) {
                    int z = side < 0 ? unit.minZ() - d : unit.maxZ() + d;
                    int y = surfaceY(level, x, z);
                    samples++;
                    sumY += y;
                    if (isSolidSurface(level, x, y, z)) {
                        solid++;
                    }
                }
            }
        } else {
            for (int z = unit.minZ(); z <= unit.maxZ(); z++) {
                for (int d = 1; d <= context.mountainProbeDistanceBlocks(); d++) {
                    int x = side < 0 ? unit.minX() - d : unit.maxX() + d;
                    int y = surfaceY(level, x, z);
                    samples++;
                    sumY += y;
                    if (isSolidSurface(level, x, y, z)) {
                        solid++;
                    }
                }
            }
        }
        return new SideProbe(side,
                samples == 0 ? 0.0D : (double) sumY / samples,
                samples == 0 ? 0.0D : (double) solid / samples,
                samples);
    }

    private boolean isSolidSurface(ServerLevel level, int x, int surfaceY, int z) {
        int y = Math.max(level.getMinBuildHeight(), surfaceY - 1);
        return !level.getBlockState(new BlockPos(x, y, z)).isAir();
    }

    private static BlockBounds towerBounds(int centerX, int centerZ, int size) {
        int radius = Math.max(1, size / 2);
        return new BlockBounds(centerX - radius, centerZ - radius, centerX + radius, centerZ + radius);
    }

    private static String terrainDeltaBand(int delta, PlacementContext context) {
        if (delta <= context.flatMaxDeltaBlocks()) {
            return "LOW";
        }
        return delta <= context.steppedMaxDeltaBlocks() ? "MID" : "HIGH";
    }

    private static java.util.List<BlockBounds> splitUnits(BlockBounds bounds, int unitLength, Axis axis) {
        int length = Math.max(1, unitLength);
        java.util.List<BlockBounds> out = new java.util.ArrayList<>();
        if (axis == Axis.X) {
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

    private static Axis axisForSegment(JsonObject segment, BlockBounds bounds) {
        String axis = stringValue(segment, "wallAxis", "");
        if ("X".equalsIgnoreCase(axis)) {
            return Axis.X;
        }
        if ("Z".equalsIgnoreCase(axis)) {
            return Axis.Z;
        }
        return bounds.widthBlocks() >= bounds.heightBlocks() ? Axis.X : Axis.Z;
    }

    private static String v4NodeSegmentType(JsonObject node) {
        String nodeType = stringValue(node, "nodeType", "");
        if ("gatehouse".equals(nodeType)) {
            return "gatehouse";
        }
        if ("natural_boundary_endpoint".equals(nodeType)) {
            return "natural_boundary_endpoint";
        }
        if ("junction".equals(nodeType)) {
            return "junction";
        }
        if ("beacon_tower".equals(nodeType)) {
            return "beacon_tower";
        }
        return "tower";
    }

    private static JsonObject pseudoSegment(JsonObject source, String idKey, String segmentType) {
        JsonObject segment = new JsonObject();
        segment.addProperty("segmentId", stringValue(source, idKey, stringValue(source, "segmentId", "")));
        segment.addProperty("segmentType", segmentType);
        segment.addProperty("templateId", stringValue(source, "templateId", ""));
        if (source.has("wallAxis") && !source.get("wallAxis").isJsonNull()) {
            segment.addProperty("wallAxis", source.get("wallAxis").getAsString());
        }
        if (source.has("blockBounds") && source.get("blockBounds").isJsonObject()) {
            segment.add("blockBounds", source.getAsJsonObject("blockBounds").deepCopy());
        }
        return segment;
    }

    enum Axis {
        X,
        Z
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
                                         JsonObject segment, BlockBounds unit, PlacementContext context,
                                         String policyDecision) {
        JsonObject sample = new JsonObject();
        sample.addProperty("segmentId", stringValue(segment, "segmentId", ""));
        sample.addProperty("x", x);
        sample.addProperty("z", z);
        sample.addProperty("surfaceY", surfaceY);
        sample.addProperty("baseY", baseY);
        sample.addProperty("foundationDepthRequired", baseY < 0 ? -1 : Math.max(0, baseY - surfaceY));
        sample.addProperty("maxFoundationDepthBlocks", context.effectiveFoundationDepthBlocks());
        sample.addProperty("terrainPolicyVersion", context.wallTerrainPolicy());
        sample.addProperty("policyDecision", policyDecision);
        sample.addProperty("protectedByRoad", context.protects(x, z));
        sample.addProperty("protectedByStructure", context.protectsFootprint(x, z));
        sample.add("unitBounds", boundsJson(unit));
        terrainDebug.getAsJsonArray("samples").add(sample);
    }

    private static void addTerrainSamples(JsonObject terrainDebug, JsonObject segment, BlockBounds unit,
                                          PlacementContext context, java.util.Map<String, Integer> surfaceSamples,
                                          int baseY, String policyDecision) {
        for (java.util.Map.Entry<String, Integer> entry : surfaceSamples.entrySet()) {
            String[] parts = entry.getKey().split(",", 2);
            addTerrainSample(terrainDebug, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    entry.getValue(), baseY, segment, unit, context, policyDecision);
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

    private static boolean booleanValue(JsonObject obj, String key, boolean fallback) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsBoolean() : fallback;
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
                              int protectedCellCount, String terrainPolicyVersion, String terrainDeltaBand,
                              JsonArray stepSlices, JsonObject mountainProbe) {
        JsonObject asJson(String segmentId) {
            JsonObject obj = new JsonObject();
            obj.addProperty("segmentId", segmentId);
            obj.addProperty("status", status);
            obj.addProperty("reasonCode", reasonCode);
            obj.addProperty("terrainPolicyVersion", terrainPolicyVersion);
            obj.addProperty("terrainDeltaBand", terrainDeltaBand);
            obj.addProperty("terrainFitMode", terrainFitMode);
            obj.addProperty("changedBlocks", changedBlocks);
            obj.addProperty("message", message);
            obj.addProperty("minSurfaceY", minSurfaceY);
            obj.addProperty("maxSurfaceY", maxSurfaceY);
            obj.addProperty("terrainDelta", maxSurfaceY - minSurfaceY);
            obj.addProperty("protectedCellCount", protectedCellCount);
            obj.add("blockBounds", boundsJson(bounds));
            obj.add("manualInspectTp", manualInspectTp(bounds));
            obj.add("stepSlices", stepSlices == null ? new JsonArray() : stepSlices.deepCopy());
            if (mountainProbe != null && !mountainProbe.entrySet().isEmpty()) {
                obj.add("mountainProbe", mountainProbe.deepCopy());
            }
            return obj;
        }
    }

    private record PlacementContext(java.util.List<BlockBounds> roadMasks,
                                    java.util.List<BlockBounds> footprints,
                                    int maxFoundationDepthBlocks,
                                    int maxSegmentHeightDeltaBlocks,
                                    int terrainFitUnitLengthBlocks,
                                    String wallTerrainPolicy,
                                    int flatMaxDeltaBlocks,
                                    int steppedMaxDeltaBlocks,
                                    int mountainProbeDistanceBlocks,
                                    int naturalBoundaryMinDeltaBlocks,
                                    boolean embeddedSlopeTower) {
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
            JsonObject terrainPolicy = wallPlan != null && wallPlan.has("terrainFitPolicy")
                    && wallPlan.get("terrainFitPolicy").isJsonObject()
                    ? wallPlan.getAsJsonObject("terrainFitPolicy") : new JsonObject();
            String policy = stringValue(terrainPolicy, "policyVersion",
                    stringValue(wallPlan, "wallTerrainPolicy", "v3"));
            int flatMax = intValue(terrainPolicy, "flatMaxDeltaBlocks", 7);
            int steppedMax = Math.max(flatMax, intValue(terrainPolicy, "steppedMaxDeltaBlocks", 16));
            return new PlacementContext(roads, footprints,
                    intValue(wallPlan, "maxFoundationDepthBlocks", 8),
                    intValue(wallPlan, "maxSegmentHeightDeltaBlocks", MAX_SEGMENT_HEIGHT_DELTA),
                    Math.max(1, intValue(wallPlan, "terrainFitUnitLengthBlocks",
                            intValue(wallPlan, "wallUnitLengthBlocks", 5))),
                    POLICY_V31.equalsIgnoreCase(policy == null ? "" : policy.trim()) ? POLICY_V31 : "v3",
                    flatMax,
                    steppedMax,
                    Math.max(1, intValue(terrainPolicy, "mountainProbeDistanceBlocks", 6)),
                    Math.max(steppedMax + 1, intValue(terrainPolicy, "naturalBoundaryMinDeltaBlocks", 17)),
                    booleanValue(terrainPolicy, "embeddedSlopeTower", true));
        }

        boolean isV31Policy() {
            return POLICY_V31.equals(wallTerrainPolicy);
        }

        int effectiveFoundationDepthBlocks() {
            return isV31Policy()
                    ? Math.max(maxFoundationDepthBlocks, steppedMaxDeltaBlocks)
                    : maxFoundationDepthBlocks;
        }

        boolean protects(int x, int z) {
            return roadMasks.stream().anyMatch(bounds -> bounds.contains(x, z));
        }

        boolean protectsFootprint(int x, int z) {
            return footprints.stream().anyMatch(bounds -> bounds.contains(x, z));
        }

        boolean protectsAny(int x, int z) {
            return protects(x, z) || protectsFootprint(x, z);
        }

        boolean overlapsRoad(BlockBounds bounds) {
            return roadMasks.stream().anyMatch(road -> road.overlaps(bounds));
        }

        boolean overlapsFootprint(BlockBounds bounds) {
            return footprints.stream().anyMatch(footprint -> footprint.overlaps(bounds));
        }

    }

    private record TerrainSample(int minY, int maxY, java.util.Map<String, Integer> surfaceSamples) {
        int delta() {
            return Math.max(0, maxY - minY);
        }
    }

    private record SideProbe(int side, double averageY, double solidRatio, int sampleCount) {
    }

    private record MountainProbe(int highSide,
                                 double negativeAverageY,
                                 double positiveAverageY,
                                 double negativeSolidRatio,
                                 double positiveSolidRatio,
                                 double heightDiff,
                                 boolean embeddable,
                                 String decisionReason) {
        JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("highSide", highSide < 0 ? "negative" : "positive");
            obj.addProperty("negativeAverageY", negativeAverageY);
            obj.addProperty("positiveAverageY", positiveAverageY);
            obj.addProperty("negativeSolidRatio", negativeSolidRatio);
            obj.addProperty("positiveSolidRatio", positiveSolidRatio);
            obj.addProperty("heightDiff", heightDiff);
            obj.addProperty("embeddable", embeddable);
            obj.addProperty("decisionReason", decisionReason);
            return obj;
        }
    }
}
