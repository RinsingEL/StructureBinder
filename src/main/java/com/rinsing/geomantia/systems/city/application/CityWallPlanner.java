package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import java.util.List;

public final class CityWallPlanner {
    public static final int DEFAULT_WALL_MARGIN_BLOCKS = 24;
    public static final int DEFAULT_FLAT_MAX_DELTA_BLOCKS = 7;
    public static final int DEFAULT_STEPPED_MAX_DELTA_BLOCKS = 16;
    public static final int DEFAULT_NATURAL_BOUNDARY_MIN_DELTA_BLOCKS = 17;
    public static final int DEFAULT_WALL_UNIT_LENGTH_BLOCKS = 8;
    public static final int DEFAULT_NOMINAL_WALL_HEIGHT_BLOCKS = 10;
    public static final int DEFAULT_WATER_RUN_MIN_BLOCKS = 32;
    public static final double DEFAULT_WATER_FLUID_RATIO_MIN = 0.8D;
    public static final int DEFAULT_SEGMENT_MAX_DELTA_BLOCKS = DEFAULT_FLAT_MAX_DELTA_BLOCKS;
    public static final int DEFAULT_STEPPED_TRANSITION_MAX_DELTA_BLOCKS = DEFAULT_STEPPED_MAX_DELTA_BLOCKS;
    private static final int WALL_HALF_THICKNESS_BLOCKS = 2;

    public JsonObject plan(JsonObject placedLedger,
                             JsonObject wallReservationPlan,
                             JsonObject actualRoadMask,
                             Options options) {
        Options opts = options == null ? Options.defaults() : options;
        if (wallReservationPlan == null
                || !CityWallReservationPlanner.SCHEMA.equals(stringValue(wallReservationPlan, "schema", ""))) {
            throw new IllegalArgumentException("WALL_REQUIRES_D5_RESERVATION: Run city_plan_d5 first.");
        }
        JsonArray line = array(wallReservationPlan, "wallLine");
        if (line.isEmpty()) {
            line = array(wallReservationPlan, "wallCenterline");
        }
        if (line.isEmpty()) {
            throw new IllegalArgumentException("WALL_LINE_UNAVAILABLE: D5 wall reservation has no wallLine.");
        }

        BlockBounds coverage = wallReservationPlan.has("wallCoverageBounds")
                && wallReservationPlan.get("wallCoverageBounds").isJsonObject()
                ? bounds(wallReservationPlan.getAsJsonObject("wallCoverageBounds"))
                : wallReservationPlan.has("wallBounds") && wallReservationPlan.get("wallBounds").isJsonObject()
                ? expand(bounds(wallReservationPlan.getAsJsonObject("wallBounds")), DEFAULT_WALL_UNIT_LENGTH_BLOCKS)
                : expand(unionPlaced(placedLedger), DEFAULT_WALL_MARGIN_BLOCKS);
        JsonArray footprintViolations = wallLockedFootprintViolations(placedLedger, coverage);
        if (!footprintViolations.isEmpty()) {
            throw new IllegalArgumentException("D5_LOCKED_FOOTPRINT_OUTSIDE_RESERVATION: D7 actual/locked footprint exceeds D5 wall coverage.");
        }

        int unitLength = opts.normalizedWallUnitLengthBlocks();
        JsonArray gateSlots = array(wallReservationPlan, "gateSlots");
        JsonArray generatedGates = new JsonArray();
        JsonArray wallUnits = new JsonArray();
        int unitIndex = 0;
        for (JsonElement elem : line) {
            if (!elem.isJsonObject() || !elem.getAsJsonObject().has("blockBounds")) {
                continue;
            }
            JsonObject sourceLine = elem.getAsJsonObject();
            BlockBounds sourceBounds = bounds(sourceLine.getAsJsonObject("blockBounds"));
            if (sourceLine.has("from") && sourceLine.has("to")) {
                JsonObject from=sourceLine.getAsJsonObject("from"), to=sourceLine.getAsJsonObject("to");
                int ax=intValue(from,"x",0), az=intValue(from,"z",0), bx=intValue(to,"x",0), bz=intValue(to,"z",0);
                if(ax!=bx && az!=bz) throw new IllegalArgumentException("WALL_ORTHOGONAL_LINE_REQUIRED");
                sourceBounds=new BlockBounds(Math.min(ax,bx),Math.min(az,bz),Math.max(ax,bx),Math.max(az,bz));
            }
            String axis = wallAxis(sourceBounds);
            BlockBounds placementBounds = wallPlacementBounds(sourceBounds, axis);
            for (BlockBounds unitBounds : splitBounds(placementBounds, unitLength, axis)) {
                JsonObject unit = new JsonObject();
                unit.addProperty("unitId", "wall_wall_unit_" + unitIndex++);
                unit.addProperty("sourceLineId", stringValue(sourceLine, "lineId",
                        stringValue(sourceLine, "segmentId", "")));
                unit.addProperty("sideHint", stringValue(sourceLine, "sideHint", ""));
                unit.addProperty("wallAxis", axis);
                unit.addProperty("heightMode", "frozen_module_walkway_datum");
                unit.addProperty("targetY", 0);
                unit.add("blockBounds", boundsJson(unitBounds));
                JsonObject gate = overlappingGate(unitBounds, gateSlots);
                if (gate != null) {
                    unit.addProperty("unitType", "gate_gap");
                    unit.addProperty("templateId", "road_gate");
                    unit.addProperty("placementAllowed", false);
                    unit.addProperty("reasonCode", "D5_GATE_SLOT_OPENING");
                    unit.addProperty("gateSlotId", stringValue(gate, "gateSlotId", ""));
                    addGeneratedGateFromSlot(generatedGates, gate, unitBounds);
                } else {
                    unit.addProperty("unitType", "wall_segment");
                    unit.addProperty("templateId", "wall_straight");
                    unit.addProperty("placementAllowed", true);
                    unit.addProperty("reasonCode", "D5_WALL_LINE_UNIT");
                }
                wallUnits.add(unit);
            }
        }

        JsonArray wallNodes = new JsonArray();
        int nodeIndex = 0;
        for (JsonElement elem : array(wallReservationPlan, "wallNodeSlots")) {
            if (!elem.isJsonObject() || !elem.getAsJsonObject().has("blockBounds")) {
                continue;
            }
            JsonObject slot = elem.getAsJsonObject();
            BlockBounds slotBounds = bounds(slot.getAsJsonObject("blockBounds"));
            JsonObject point = slot.has("block") ? slot.getAsJsonObject("block") : new JsonObject();
            int nodeX = intValue(point,"x",slotBounds.center().x()), nodeZ = intValue(point,"z",slotBounds.center().z());
            BlockBounds bounds = new BlockBounds(nodeX-3,nodeZ-6,nodeX+3,nodeZ+3);
            JsonObject node = graphNode("wall_wall_node_" + nodeIndex++,
                    "guard_tower",
                    nodeX, nodeZ, bounds, 0, 0,
                    "frozen_module_walkway_datum");
            node.addProperty("sourceNodeSlotId", stringValue(slot, "nodeSlotId", ""));
            node.addProperty("reasonCode", stringValue(slot, "reasonCode", "D5_WALL_NODE_SLOT"));
            node.addProperty("wallAxis", wallAxisForNode(bounds, line));
            wallNodes.add(node);
        }

        JsonObject plan = new JsonObject();
        plan.addProperty("schema", "city_wall_plan");
        plan.addProperty("moduleSet", "guard_tower");
        plan.addProperty("cityId", stringValue(placedLedger, "cityId", "unknown_city"));
        plan.addProperty("boundaryMode", "d5_final_wall_line");
        plan.addProperty("wallBoundaryMode", "d5_final_wall_line");
        plan.addProperty("wallPlanningMode", "district_modules_with_frozen_surface_profile");
        plan.addProperty("wallContourMode", "disabled_wall_no_reline_after_d5");
        plan.addProperty("roadMaskSource", "actual_world_blocks_for_placement_preflight");
        plan.addProperty("wallUnitLengthBlocks", unitLength);
        plan.addProperty("nominalWallHeightBlocks", opts.normalizedNominalWallHeightBlocks());
        plan.addProperty("waterRunMinBlocks", opts.normalizedWaterRunMinBlocks());
        plan.addProperty("waterFluidRatioMin", opts.normalizedWaterFluidRatioMin());
        plan.addProperty("heightSegmentMaxDeltaBlocks", opts.normalizedHeightSegmentMaxDeltaBlocks());
        plan.addProperty("heightSteppedTransitionMaxDeltaBlocks",
                opts.normalizedHeightSteppedTransitionMaxDeltaBlocks());
        plan.addProperty("naturalBoundaryMinDeltaBlocks", opts.normalizedNaturalBoundaryMinDeltaBlocks());
        plan.addProperty("maxFoundationDepthBlocks", 64);
        plan.addProperty("maxSegmentHeightDeltaBlocks",
                Math.max(0, opts.normalizedNaturalBoundaryMinDeltaBlocks() - 1));
        plan.addProperty("gateFailurePolicy", "reject_conflicts_before_world_mutation");
        plan.addProperty("towerFailurePolicy", "reject_conflicts_before_world_mutation");
        plan.add("wallReservationSource", wallReservationPlan.deepCopy());
        plan.add("actualRoadMask", actualRoadMask == null
                ? emptyRoadMask(plan.get("cityId").getAsString()) : actualRoadMask.deepCopy());
        plan.add("sourcePlacedStructureLedger", placedLedger == null ? new JsonObject() : placedLedger.deepCopy());
        plan.add("sourceActualFootprintUnion", boundsJson(unionPlaced(placedLedger)));
        plan.add("wallCoverageBounds", boundsJson(coverage));
        if (wallReservationPlan.has("wallBounds") && wallReservationPlan.get("wallBounds").isJsonObject()) {
            plan.add("wallBounds", wallReservationPlan.getAsJsonObject("wallBounds").deepCopy());
        }
        plan.add("wallLine", line.deepCopy());
        plan.add("generatedGates", generatedGates);
        plan.add("wallNodes", wallNodes);
        plan.add("wallUnits", wallUnits);
        plan.add("nodeConnectorUnits", new JsonArray());
        plan.add("wallSegments", new JsonArray());
        plan.add("templateLibrary", CityWallTemplateCatalog.libraryJson());

        JsonObject surface = new JsonObject();
        surface.addProperty("cacheSchema", "city_surface_cache");
        surface.addProperty("storageFormat", ".dat");
        surface.addProperty("sampleGranularityBlocks", 1);
        surface.addProperty("requiredFields", "surfaceY/topBlock/fluid/biome/temperature/flags");
        surface.addProperty("backfillStage", "d7_or_city_plan_city_walls_before_wall_execute");
        plan.add("surfaceCachePolicy", surface);

        JsonObject terrain = new JsonObject();
        terrain.addProperty("policyVersion", "wall");
        terrain.addProperty("heightStrategy", "common_module_walkway_datum");
        terrain.addProperty("heightSegmentMaxDeltaBlocks", opts.normalizedHeightSegmentMaxDeltaBlocks());
        terrain.addProperty("heightSteppedTransitionMaxDeltaBlocks",
                opts.normalizedHeightSteppedTransitionMaxDeltaBlocks());
        terrain.addProperty("naturalBoundaryMinDeltaBlocks", opts.normalizedNaturalBoundaryMinDeltaBlocks());
        terrain.addProperty("transitionPolicy", "reject_unconnectable_height_before_placement");
        terrain.addProperty("pitPolicy", "fill_horizontal_floor_inside_corridor");
        terrain.addProperty("raisedGroundPolicy", "connect_wall_into_existing_ground");
        terrain.addProperty("waterPolicy", "reject_water_boundary_for_redesign");
        terrain.addProperty("cliffPolicy", "reject_cliff_boundary_for_redesign");
        terrain.addProperty("debugScanSupported", true);
        plan.add("terrainFitPolicy", terrain);

        JsonObject validation = new JsonObject();
        validation.addProperty("status", "valid");
        validation.addProperty("d5PlaneAuthority", true);
        validation.addProperty("noRelineAfterD5", true);
        validation.addProperty("patchBoundaryUsedAsFinalWallLine", false);
        validation.addProperty("wallNodeCount", wallNodes.size());
        validation.addProperty("wallUnitCount", wallUnits.size());
        validation.addProperty("generatedGateCount", generatedGates.size());
        validation.addProperty("lockedFootprintViolationCount", footprintViolations.size());
        validation.add("lockedFootprintViolations", footprintViolations);
        plan.add("wallGraphValidation", validation);
        return plan;
    }

    private static JsonArray wallLockedFootprintViolations(JsonObject ledger, BlockBounds coverage) {
        JsonArray out = new JsonArray();
        for (JsonElement elem : array(ledger, "placedStructures")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject structure = elem.getAsJsonObject();
            JsonObject source = structure.has("lockedActualFootprint")
                    && structure.get("lockedActualFootprint").isJsonObject()
                    ? structure.getAsJsonObject("lockedActualFootprint")
                    : structure.has("actualFootprint") && structure.get("actualFootprint").isJsonObject()
                    ? structure.getAsJsonObject("actualFootprint")
                    : null;
            if (source == null) {
                continue;
            }
            BlockBounds footprint = bounds(source);
            if (containsBounds(coverage, footprint)) {
                continue;
            }
            JsonObject violation = new JsonObject();
            violation.addProperty("anchorId", stringValue(structure, "anchorId", ""));
            violation.addProperty("templateId", stringValue(structure, "templateId", ""));
            violation.addProperty("reasonCode", "D5_LOCKED_FOOTPRINT_OUTSIDE_RESERVATION");
            violation.add("footprint", boundsJson(footprint));
            violation.add("coverageBounds", boundsJson(coverage));
            out.add(violation);
        }
        return out;
    }

    private static BlockBounds wallPlacementBounds(BlockBounds source, String axis) {
        if ("X".equals(axis)) {
            int centerZ = source.center().z();
            return new BlockBounds(source.minX(), centerZ - WALL_HALF_THICKNESS_BLOCKS,
                    source.maxX(), centerZ + WALL_HALF_THICKNESS_BLOCKS);
        }
        int centerX = source.center().x();
        return new BlockBounds(centerX - WALL_HALF_THICKNESS_BLOCKS, source.minZ(),
                centerX + WALL_HALF_THICKNESS_BLOCKS, source.maxZ());
    }

    private static java.util.List<BlockBounds> splitBounds(BlockBounds bounds, int unitLength, String axis) {
        int length = Math.max(1, unitLength);
        java.util.List<BlockBounds> out = new java.util.ArrayList<>();
        if ("X".equals(axis)) {
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

    private static JsonObject overlappingGate(BlockBounds unitBounds, JsonArray gateSlots) {
        for (JsonElement elem : gateSlots) {
            if (!elem.isJsonObject() || !elem.getAsJsonObject().has("blockBounds")) {
                continue;
            }
            JsonObject gate = elem.getAsJsonObject();
            if (unitBounds.overlaps(bounds(gate.getAsJsonObject("blockBounds")))) {
                return gate;
            }
        }
        return null;
    }

    private static void addGeneratedGateFromSlot(JsonArray generatedGates, JsonObject gate, BlockBounds unitBounds) {
        String gateSlotId = stringValue(gate, "gateSlotId", "d5_wall_gate_slot");
        for (JsonElement elem : generatedGates) {
            if (elem.isJsonObject()
                    && gateSlotId.equals(stringValue(elem.getAsJsonObject(), "gateSlotId", ""))) {
                return;
            }
        }
        JsonObject generated = new JsonObject();
        generated.addProperty("gateId", "wall_gate_" + generatedGates.size());
        generated.addProperty("gateSlotId", gateSlotId);
        generated.addProperty("nodeType", "gate_opening");
        generated.addProperty("reasonCode", "D5_GATE_SLOT_OPENING");
        generated.addProperty("failurePolicy", "reject_conflicts_before_world_mutation");
        generated.addProperty("gateWidthBlocks", Math.max(unitBounds.widthBlocks(), unitBounds.heightBlocks()));
        generated.add("blockBounds", gate.getAsJsonObject("blockBounds").deepCopy());
        generatedGates.add(generated);
    }

    private static String wallAxis(BlockBounds bounds) {
        return bounds.widthBlocks() >= bounds.heightBlocks() ? "X" : "Z";
    }

    private static String wallAxisForNode(BlockBounds nodeBounds, JsonArray wallLine) {
        String fallback = wallAxis(nodeBounds);
        int bestDistance = Integer.MAX_VALUE;
        String bestAxis = fallback;
        for (JsonElement elem : wallLine) {
            if (!elem.isJsonObject() || !elem.getAsJsonObject().has("blockBounds")) {
                continue;
            }
            BlockBounds lineBounds = bounds(elem.getAsJsonObject().getAsJsonObject("blockBounds"));
            int distance = distanceBetweenBounds(nodeBounds, lineBounds);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestAxis = wallAxis(lineBounds);
            }
        }
        return bestAxis;
    }

    private static int distanceBetweenBounds(BlockBounds a, BlockBounds b) {
        int dx = a.maxX() < b.minX()
                ? b.minX() - a.maxX()
                : b.maxX() < a.minX() ? a.minX() - b.maxX() : 0;
        int dz = a.maxZ() < b.minZ()
                ? b.minZ() - a.maxZ()
                : b.maxZ() < a.minZ() ? a.minZ() - b.maxZ() : 0;
        return dx + dz;
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

    private static BlockBounds union(BlockBounds a, BlockBounds b) {
        return new BlockBounds(
                Math.min(a.minX(), b.minX()),
                Math.min(a.minZ(), b.minZ()),
                Math.max(a.maxX(), b.maxX()),
                Math.max(a.maxZ(), b.maxZ()));
    }

    private static JsonObject emptyRoadMask(String cityId) {
        JsonObject obj = new JsonObject();
        obj.addProperty("schema", "city_actual_road_mask");
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

    private static JsonObject graphNode(String nodeId, String nodeType, int x, int z, BlockBounds blockBounds,
                                     int surfaceMedianY, int targetY, String heightMode) {
        JsonObject node = new JsonObject();
        node.addProperty("nodeId", nodeId);
        node.addProperty("nodeType", nodeType);
        node.addProperty("x", x);
        node.addProperty("z", z);
        node.add("blockBounds", boundsJson(blockBounds));
        node.addProperty("surfaceMedianY", surfaceMedianY);
        node.addProperty("targetY", targetY);
        node.addProperty("heightMode", heightMode);
        node.addProperty("templateId", "guard_tower");
        node.addProperty("placementRole", "structural_node");
        return node;
    }

    private static boolean containsBounds(BlockBounds outer, BlockBounds inner) {
        return outer.contains(inner.minX(), inner.minZ())
                && outer.contains(inner.maxX(), inner.maxZ());
    }

    public record Options(int wallUnitLengthBlocks,
                            int nominalWallHeightBlocks,
                            int waterRunMinBlocks,
                            double waterFluidRatioMin,
                            int heightSegmentMaxDeltaBlocks,
                            int heightSteppedTransitionMaxDeltaBlocks,
                            int naturalBoundaryMinDeltaBlocks) {
        public static Options defaults() {
            return new Options(DEFAULT_WALL_UNIT_LENGTH_BLOCKS,
                    DEFAULT_NOMINAL_WALL_HEIGHT_BLOCKS,
                    DEFAULT_WATER_RUN_MIN_BLOCKS,
                    DEFAULT_WATER_FLUID_RATIO_MIN,
                    DEFAULT_SEGMENT_MAX_DELTA_BLOCKS,
                    DEFAULT_STEPPED_TRANSITION_MAX_DELTA_BLOCKS,
                    DEFAULT_NATURAL_BOUNDARY_MIN_DELTA_BLOCKS);
        }

        public int normalizedWallUnitLengthBlocks() {
            return wallUnitLengthBlocks <= 0 ? DEFAULT_WALL_UNIT_LENGTH_BLOCKS : wallUnitLengthBlocks;
        }

        public int normalizedNominalWallHeightBlocks() {
            return nominalWallHeightBlocks <= 0 ? DEFAULT_NOMINAL_WALL_HEIGHT_BLOCKS : nominalWallHeightBlocks;
        }

        public int normalizedWaterRunMinBlocks() {
            return waterRunMinBlocks <= 0 ? DEFAULT_WATER_RUN_MIN_BLOCKS : waterRunMinBlocks;
        }

        public double normalizedWaterFluidRatioMin() {
            return waterFluidRatioMin <= 0.0D ? DEFAULT_WATER_FLUID_RATIO_MIN
                    : Math.min(1.0D, waterFluidRatioMin);
        }

        public int normalizedHeightSegmentMaxDeltaBlocks() {
            return heightSegmentMaxDeltaBlocks <= 0
                    ? DEFAULT_SEGMENT_MAX_DELTA_BLOCKS : heightSegmentMaxDeltaBlocks;
        }

        public int normalizedHeightSteppedTransitionMaxDeltaBlocks() {
            int segmentMax = normalizedHeightSegmentMaxDeltaBlocks();
            int steppedMax = heightSteppedTransitionMaxDeltaBlocks <= 0
                    ? DEFAULT_STEPPED_TRANSITION_MAX_DELTA_BLOCKS : heightSteppedTransitionMaxDeltaBlocks;
            return Math.max(segmentMax, steppedMax);
        }

        public int normalizedNaturalBoundaryMinDeltaBlocks() {
            return Math.max(normalizedHeightSteppedTransitionMaxDeltaBlocks() + 1,
                    naturalBoundaryMinDeltaBlocks <= 0
                            ? DEFAULT_NATURAL_BOUNDARY_MIN_DELTA_BLOCKS : naturalBoundaryMinDeltaBlocks);
        }
    }

}
