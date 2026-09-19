package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.domain.model.CityLandformReviewPackage;
import com.rinsing.geomantia.systems.city.domain.model.LandformPatchSummary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CityWallReservationPlanner {
    public static final String SCHEMA = "city_wall_reservation_plan";
    public static final int DEFAULT_WALL_CORRIDOR_HALF_WIDTH_BLOCKS = 4;
    public static final int DEFAULT_WALL_MARGIN_BLOCKS = 24;
    public static final int DEFAULT_WALL_UNIT_LENGTH_BLOCKS = 8;
    public static final int DEFAULT_COVERAGE_RESCAN_MARGIN_BLOCKS = 16;

    public JsonObject plan(CityLandformReviewPackage reviewPackage,
                           JsonObject anchorMap,
                           int wallMarginBlocks,
                           int wallCorridorHalfWidthBlocks) {
        return plan(reviewPackage, anchorMap, wallMarginBlocks, wallCorridorHalfWidthBlocks, null);
    }

    public JsonObject plan(CityLandformReviewPackage reviewPackage,
                           JsonObject anchorMap,
                           int wallMarginBlocks,
                           int wallCorridorHalfWidthBlocks,
                           BlockBounds patchContextBounds) {
        int margin = wallMarginBlocks <= 0 ? DEFAULT_WALL_MARGIN_BLOCKS : wallMarginBlocks;
        int halfWidth = wallCorridorHalfWidthBlocks <= 0
                ? DEFAULT_WALL_CORRIDOR_HALF_WIDTH_BLOCKS : wallCorridorHalfWidthBlocks;
        if (reviewPackage == null) {
            throw new IllegalArgumentException("D3 city_landform_review_package.json is required for wall reservation.");
        }
        if (anchorMap == null || !anchorMap.has("anchors") || !anchorMap.get("anchors").isJsonArray()) {
            throw new IllegalArgumentException("D4 structure_anchor_map.json is required for wall reservation.");
        }
        BlockBounds plannedFootprintUnion = anchorBoundsUnion(anchorMap, "plannedFootprint");
        BlockBounds envelopeUnion = anchorEnvelopeUnion(anchorMap);
        BlockBounds structureUnion = envelopeUnion == null ? plannedFootprintUnion : envelopeUnion;
        if (structureUnion == null) {
            throw new IllegalArgumentException("D5_STRUCTURE_FOOTPRINT_UNAVAILABLE: D4 anchors have no plannedFootprint/reservedEnvelope.");
        }

        int unitLength = DEFAULT_WALL_UNIT_LENGTH_BLOCKS;
        List<BlockBounds> districtFootprints = new ArrayList<>();
        Set<String> districtGroups = new LinkedHashSet<>();
        for (JsonElement id : array(anchorMap, "districtGroupIds")) districtGroups.add(id.getAsString());
        for (JsonElement element : array(anchorMap, "anchors")) {
            JsonObject anchor = element.getAsJsonObject();
            if (!districtGroups.isEmpty() && !districtGroups.contains(stringValue(anchor,"placementGroupId",""))) continue;
            JsonObject one = new JsonObject(); JsonArray anchors = new JsonArray(); anchors.add(anchor); one.add("anchors", anchors);
            BlockBounds footprint = anchorEnvelopeUnion(one);
            if (footprint != null) districtFootprints.add(footprint);
        }
        List<Segment> segments = new com.rinsing.geomantia.systems.city.algorithm.landuse.CityDistrictBoundary()
                .outline(districtFootprints, Math.max(margin, halfWidth + unitLength), 32).stream()
                .map(edge -> segment(edge.side(), edge.from(), edge.to(), halfWidth)).toList();
        BlockBounds wallBounds = union(segments);
        BlockBounds corridorBounds = union(segments);
        int rescanMargin = Math.max(DEFAULT_COVERAGE_RESCAN_MARGIN_BLOCKS, halfWidth + unitLength);
        BlockBounds requiredCoverage = expand(union(corridorBounds, structureUnion), rescanMargin);
        BlockBounds coreGridCoverage = new BlockBounds(
                reviewPackage.grid().blockMinX(),
                reviewPackage.grid().blockMinZ(),
                reviewPackage.grid().blockMaxX() - 1,
                reviewPackage.grid().blockMaxZ() - 1);
        boolean usePatchContext = patchContextBounds != null
                && containsBounds(patchContextBounds, coreGridCoverage);
        BlockBounds d3Coverage = usePatchContext ? patchContextBounds : coreGridCoverage;
        if (!containsBounds(d3Coverage, requiredCoverage)) {
            throw new IllegalArgumentException("D5_REQUIRES_PATCH_RESCAN: wall/structure reservation requires "
                    + boundsText(requiredCoverage) + " but D3 coverage is " + boundsText(d3Coverage) + ".");
        }

        JsonArray wallLine = wallLine(segments);
        JsonArray wallCorridorMask = corridorMasks(segments);
        JsonArray gateSlots = gateSlots(segments, halfWidth, anchorMap);
        JsonArray wallNodeSlots = wallNodeSlots(segments, gateSlots);

        JsonObject plan = new JsonObject();
        plan.addProperty("schema", SCHEMA);
        plan.addProperty("cityId", reviewPackage.cityId());
        plan.addProperty("boundarySource", "district_coarse_exterior");
        plan.addProperty("wallPlanningStage", "d5_final_plane_and_worldgen_mask");
        plan.addProperty("finalBoundaryAuthority", "d5");
        plan.addProperty("finalBoundaryDeferredToD7", false);
        plan.addProperty("patchUsage", "semantic_and_coverage_check_only");
        plan.addProperty("wallCorridorHalfWidthBlocks", halfWidth);
        plan.addProperty("wallMarginBlocks", margin);
        plan.addProperty("wallUnitLengthBlocks", unitLength);
        plan.addProperty("segmentLengthBlocks", unitLength);
        plan.add("sourcePatchRefs", sourcePatchRefs(reviewPackage, anchorMap));
        plan.add("sourcePlannedFootprintUnion", plannedFootprintUnion == null
                ? new JsonObject() : boundsJson(plannedFootprintUnion));
        plan.add("sourceEnvelopeUnion", envelopeUnion == null ? new JsonObject() : boundsJson(envelopeUnion));
        plan.add("sourceD3CoreGridBounds", boundsJson(coreGridCoverage));
        plan.add("sourceD3CoverageBounds", boundsJson(d3Coverage));
        plan.addProperty("d3CoverageSource", usePatchContext ? "patch_context_bounds" : "grid");
        plan.add("requiredD3CoverageBounds", boundsJson(requiredCoverage));
        plan.add("wallBounds", boundsJson(wallBounds));
        plan.add("wallCoverageBounds", boundsJson(requiredCoverage));
        plan.add("wallLine", wallLine.deepCopy());
        plan.add("wallCenterline", wallLine.deepCopy());
        plan.add("outerWallRing", wallLine.deepCopy());
        plan.add("wallCorridorMask", wallCorridorMask.deepCopy());
        plan.add("gateSlots", gateSlots.deepCopy());
        plan.addProperty("exitRoadStatus", gateSlots.isEmpty() ? "EXIT_ROAD_REQUIRED" : "connected");
        plan.add("gateCorridorMask", gateSlotMasks(gateSlots));
        plan.add("wallNodeSlots", wallNodeSlots);
        plan.add("towerCandidatePoints", towerCandidates(wallNodeSlots));
        plan.add("gateCandidateZones", gateSlots.deepCopy());
        plan.add("plannedCityStructureAllowlist", plannedCityStructureAllowlist(anchorMap));
        plan.add("maskContribution", maskContribution(wallCorridorMask, gateSlots, anchorMap));
        plan.add("maskChannels", maskChannels(wallCorridorMask, gateSlots, anchorMap));

        JsonObject coverage = new JsonObject();
        coverage.addProperty("status", "passed");
        coverage.addProperty("reasonCode", "D5_COVERAGE_OK");
        coverage.addProperty("patchBoundaryIsFinalWallLine", false);
        coverage.addProperty("rescanMarginBlocks", rescanMargin);
        coverage.addProperty("coverageSource", usePatchContext ? "patch_context_bounds" : "grid");
        coverage.add("coreGridBounds", boundsJson(coreGridCoverage));
        coverage.add("requiredCoverageBounds", boundsJson(requiredCoverage));
        coverage.add("d3CoverageBounds", boundsJson(d3Coverage));
        plan.add("coverageCheck", coverage);

        JsonObject debug = new JsonObject();
        debug.addProperty("wallLineSegmentCount", segments.size());
        debug.addProperty("gateSlotCount", gateSlots.size());
        debug.addProperty("wallNodeSlotCount", wallNodeSlots.size());
        debug.addProperty("anchorCount", array(anchorMap, "anchors").size());
        plan.add("wallReservationDebug", debug);
        return plan;
    }

    private static Map<String, LandformPatchSummary> patchesByRef(CityLandformReviewPackage reviewPackage) {
        Map<String, LandformPatchSummary> map = new LinkedHashMap<>();
        for (LandformPatchSummary patch : reviewPackage.landformPatches()) {
            map.put(patch.landformPatchId(), patch);
            map.put(patch.mapLabel(), patch);
        }
        return map;
    }

    private static Set<String> selectedPatchRefs(JsonObject anchorMap) {
        Set<String> refs = new LinkedHashSet<>();
        for (JsonElement anchorElem : array(anchorMap, "anchors")) {
            if (!anchorElem.isJsonObject()) {
                continue;
            }
            JsonObject anchor = anchorElem.getAsJsonObject();
            for (JsonElement patchElem : array(anchor, "sourcePatches")) {
                if (!patchElem.isJsonObject()) {
                    continue;
                }
                JsonObject patch = patchElem.getAsJsonObject();
                addIfPresent(refs, patch, "landformPatchId");
                addIfPresent(refs, patch, "mapLabel");
            }
            for (JsonElement patchElem : array(anchor, "sourcePatchIds")) {
                if (!patchElem.isJsonNull()) {
                    refs.add(patchElem.getAsString());
                }
            }
        }
        return refs;
    }

    private static JsonArray sourcePatchRefs(CityLandformReviewPackage reviewPackage, JsonObject anchorMap) {
        Map<String, LandformPatchSummary> patchesByRef = patchesByRef(reviewPackage);
        JsonArray out = new JsonArray();
        Set<String> seen = new LinkedHashSet<>();
        for (String ref : selectedPatchRefs(anchorMap)) {
            LandformPatchSummary patch = patchesByRef.get(ref);
            if (patch == null || !seen.add(patch.landformPatchId())) {
                continue;
            }
            JsonObject obj = new JsonObject();
            obj.addProperty("landformPatchId", patch.landformPatchId());
            obj.addProperty("mapLabel", patch.mapLabel());
            obj.addProperty("landformType", patch.landformType().contractName());
            obj.add("blockBounds", boundsJson(patch.blockBounds()));
            out.add(obj);
        }
        return out;
    }

    private static BlockBounds anchorEnvelopeUnion(JsonObject anchorMap) {
        BlockBounds union = null;
        for (JsonElement elem : array(anchorMap, "anchors")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject anchor = elem.getAsJsonObject();
            BlockBounds bounds = null;
            for (String key : List.of("reservedEnvelope", "collisionEnvelope", "maskEnvelope", "plannedFootprint")) {
                if (anchor.has(key) && anchor.get(key).isJsonObject()) {
                    BlockBounds candidate = bounds(anchor.getAsJsonObject(key));
                    bounds = bounds == null ? candidate : union(bounds, candidate);
                }
            }
            if (bounds != null) {
                union = union == null ? bounds : union(union, bounds);
            }
        }
        return union;
    }

    private static BlockBounds anchorBoundsUnion(JsonObject anchorMap, String key) {
        BlockBounds union = null;
        for (JsonElement elem : array(anchorMap, "anchors")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject anchor = elem.getAsJsonObject();
            if (!anchor.has(key) || !anchor.get(key).isJsonObject()) {
                continue;
            }
            BlockBounds bounds = bounds(anchor.getAsJsonObject(key));
            union = union == null ? bounds : union(union, bounds);
        }
        return union;
    }

    private static Segment segment(String side, BlockPoint from, BlockPoint to, int halfWidth) {
        BlockBounds bounds = corridorBounds(from, to, halfWidth);
        return new Segment(side, from, to, bounds);
    }

    private static JsonArray wallLine(List<Segment> segments) {
        JsonArray out = new JsonArray();
        int index = 0;
        for (Segment segment : segments) {
            JsonObject obj = new JsonObject();
            obj.addProperty("lineId", "d5_wall_line_" + index);
            obj.addProperty("segmentId", "d5_wall_line_" + index++);
            obj.addProperty("sideHint", segment.side);
            obj.addProperty("authority", "d5_final_wall_plane");
            obj.add("from", segment.from.asJson());
            obj.add("to", segment.to.asJson());
            obj.add("blockBounds", boundsJson(segment.bounds));
            out.add(obj);
        }
        return out;
    }

    private static JsonArray corridorMasks(List<Segment> segments) {
        JsonArray out = new JsonArray();
        int index = 0;
        for (Segment segment : segments) {
            JsonObject obj = new JsonObject();
            obj.addProperty("maskId", "wall_corridor_" + index++);
            obj.addProperty("maskType", "wall_reservation_corridor");
            obj.addProperty("sourceRef", "wall_reservation");
            obj.add("blockBounds", boundsJson(segment.bounds));
            out.add(obj);
        }
        return out;
    }

    private static JsonArray gateSlots(List<Segment> segments, int halfWidth, JsonObject anchorMap) {
        JsonArray out = new JsonArray();
        for (JsonElement element : array(anchorMap, "streetBands")) {
            if (!element.isJsonObject()) continue;
            JsonObject road = element.getAsJsonObject();
            if (!road.has("bounds")) continue;
            BlockBounds roadBounds = bounds(road.getAsJsonObject("bounds"));
            boolean roadHorizontal = roadBounds.widthBlocks() > roadBounds.heightBlocks();
            for (Segment segment : segments) {
                boolean horizontal = segment.from.z() == segment.to.z();
                if (horizontal == roadHorizontal || !roadBounds.overlaps(segment.bounds)) continue;
                int coordinate = horizontal ? segment.from.z() : segment.from.x();
                if (horizontal ? roadBounds.minZ() >= coordinate || roadBounds.maxZ() <= coordinate
                        : roadBounds.minX() >= coordinate || roadBounds.maxX() <= coordinate) continue;
                BlockBounds opening = horizontal
                        ? new BlockBounds(roadBounds.minX()-1, segment.from.z()-halfWidth,
                        roadBounds.maxX()+1, segment.from.z()+halfWidth)
                        : new BlockBounds(segment.from.x()-halfWidth, roadBounds.minZ()-1,
                        segment.from.x()+halfWidth, roadBounds.maxZ()+1);
                JsonObject gate = new JsonObject();
                gate.addProperty("gateSlotId", "road_gate_" + out.size());
                gate.addProperty("sideHint", segment.side);
                gate.addProperty("sourceRoadId", stringValue(road, "streetBandId", ""));
                gate.addProperty("reasonCode", "DISTRICT_EXIT_ROAD_CROSSING");
                gate.addProperty("gateWidthBlocks", horizontal ? opening.widthBlocks() : opening.heightBlocks());
                gate.add("center", opening.center().asJson());
                gate.add("blockBounds", boundsJson(opening));
                out.add(gate);
            }
        }
        return out;
    }

    private static JsonArray gateSlotMasks(JsonArray gateSlots) {
        JsonArray out = new JsonArray();
        int index = 0;
        for (JsonElement elem : gateSlots) {
            if (!elem.isJsonObject() || !elem.getAsJsonObject().has("blockBounds")) {
                continue;
            }
            JsonObject gate = elem.getAsJsonObject();
            JsonObject mask = new JsonObject();
            mask.addProperty("maskId", "gate_corridor_" + index++);
            mask.addProperty("maskType", "gate_corridor");
            mask.addProperty("sourceRef", stringValue(gate, "gateSlotId", "gate_slot"));
            mask.addProperty("roadPassthroughAllowed", true);
            mask.add("blockBounds", gate.getAsJsonObject("blockBounds").deepCopy());
            out.add(mask);
        }
        return out;
    }

    private static JsonArray wallNodeSlots(List<Segment> segments, JsonArray gateSlots) {
        JsonArray out = new JsonArray(); Set<String> corners = new LinkedHashSet<>();
        for (Segment segment : segments) {
            BlockBounds node = new BlockBounds(segment.from.x()-3, segment.from.z()-6,
                    segment.from.x()+3, segment.from.z()+3);
            if (overlapsAny(node, gateSlots))
                throw new IllegalArgumentException("WALL_GATE_CORNER_CONFLICT: move the exit road away from the corner");
            addNodeSlot(out, corners, segment.from, "guard_tower", "DISTRICT_CORNER");
            int length = Math.abs(segment.to.x()-segment.from.x())+Math.abs(segment.to.z()-segment.from.z());
            if (length > 160) {
                BlockPoint middle = new BlockPoint((segment.from.x()+segment.to.x())/2,
                        (segment.from.z()+segment.to.z())/2);
                if (!overlapsAny(new BlockBounds(middle.x()-3,middle.z()-6,middle.x()+3,middle.z()+3),gateSlots))
                    addNodeSlot(out,corners,middle,"guard_tower","LONG_WALL_SUPPORT");
            }
        }
        return out;
    }

    private static void addNodeSlot(JsonArray out, Set<String> seen, BlockPoint point,
                                    String nodeType, String reasonCode) {
        String key = point.x() + "," + point.z();
        if (!seen.add(key)) {
            return;
        }
        JsonObject slot = new JsonObject();
        slot.addProperty("nodeSlotId", "d5_node_slot_" + (out.size()));
        slot.addProperty("nodeType", nodeType);
        slot.addProperty("templateId", "guard_tower");
        slot.addProperty("reasonCode", reasonCode);
        slot.add("block", point.asJson());
        slot.add("blockBounds", boundsJson(new BlockBounds(point.x() - 3, point.z() - 6,
                point.x() + 3, point.z() + 3)));
        out.add(slot);
    }

    private static boolean overlapsAny(BlockBounds bounds, JsonArray objects) {
        for (JsonElement elem : objects) {
            if (elem.isJsonObject() && elem.getAsJsonObject().has("blockBounds")
                    && bounds.overlaps(bounds(elem.getAsJsonObject().getAsJsonObject("blockBounds")))) {
                return true;
            }
        }
        return false;
    }

    private static JsonArray towerCandidates(JsonArray wallNodeSlots) {
        JsonArray out = new JsonArray();
        for (JsonElement elem : wallNodeSlots) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject slot = elem.getAsJsonObject();
            if (!slot.has("block")) {
                continue;
            }
            JsonObject obj = new JsonObject();
            obj.addProperty("towerId", stringValue(slot, "nodeSlotId", "wall_node_slot"));
            obj.addProperty("nodeType", stringValue(slot, "nodeType", ""));
            obj.add("block", slot.getAsJsonObject("block").deepCopy());
            obj.addProperty("reasonCode", stringValue(slot, "reasonCode", "D5_WALL_NODE_SLOT"));
            out.add(obj);
        }
        return out;
    }

    private static JsonArray plannedCityStructureAllowlist(JsonObject anchorMap) {
        JsonArray out = new JsonArray();
        for (JsonElement elem : array(anchorMap, "anchors")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject anchor = elem.getAsJsonObject();
            JsonObject item = new JsonObject();
            item.addProperty("anchorId", stringValue(anchor, "anchorId", ""));
            item.addProperty("templateId", stringValue(anchor, "templateId", ""));
            if (anchor.has("reservedEnvelope") && anchor.get("reservedEnvelope").isJsonObject()) {
                item.add("blockBounds", anchor.getAsJsonObject("reservedEnvelope").deepCopy());
            } else if (anchor.has("plannedFootprint") && anchor.get("plannedFootprint").isJsonObject()) {
                item.add("blockBounds", anchor.getAsJsonObject("plannedFootprint").deepCopy());
            }
            out.add(item);
        }
        return out;
    }

    private static JsonObject maskContribution(JsonArray wallCorridorMask, JsonArray gateSlots,
                                                 JsonObject anchorMap) {
        JsonObject obj = new JsonObject();
        obj.addProperty("noVegetationMaskType", "wall_reservation_corridor");
        obj.addProperty("vegetationLimitedMaskType", "wall_reservation_transition");
        obj.addProperty("noVanillaStructureMaskType", "wall_reservation_corridor");
        obj.addProperty("noRoadsideStructureMaskType", "wall_reservation_corridor");
        obj.addProperty("gateCorridorMaskType", "gate_corridor");
        obj.addProperty("wallCorridorMaskCount", wallCorridorMask.size());
        obj.addProperty("gateCorridorMaskCount", gateSlots.size());
        obj.addProperty("plannedCityStructureAllowlistCount", array(anchorMap, "anchors").size());
        obj.addProperty("roadPassthroughAllowedAtGateCorridor", true);
        return obj;
    }

    private static JsonObject maskChannels(JsonArray wallCorridorMask, JsonArray gateSlots, JsonObject anchorMap) {
        JsonObject channels = new JsonObject();
        channels.add("wallCorridor", wallCorridorMask.deepCopy());
        channels.add("gateCorridor", gateSlotMasks(gateSlots));
        channels.add("noVegetation", wallCorridorMask.deepCopy());
        channels.add("noSurfaceFeature", wallCorridorMask.deepCopy());
        channels.add("noVanillaStructure", wallCorridorMask.deepCopy());
        channels.add("noRoadsideStructure", wallCorridorMask.deepCopy());
        channels.add("plannedCityStructureAllowlist", plannedCityStructureAllowlist(anchorMap));
        return channels;
    }

    private static BlockBounds union(List<Segment> segments) {
        BlockBounds union = segments.get(0).bounds;
        for (Segment segment : segments) {
            BlockBounds bounds = segment.bounds;
            union = new BlockBounds(
                    Math.min(union.minX(), bounds.minX()),
                    Math.min(union.minZ(), bounds.minZ()),
                    Math.max(union.maxX(), bounds.maxX()),
                    Math.max(union.maxZ(), bounds.maxZ()));
        }
        return union;
    }

    private static BlockBounds union(BlockBounds a, BlockBounds b) {
        return new BlockBounds(
                Math.min(a.minX(), b.minX()),
                Math.min(a.minZ(), b.minZ()),
                Math.max(a.maxX(), b.maxX()),
                Math.max(a.maxZ(), b.maxZ()));
    }

    private static BlockBounds corridorBounds(BlockPoint from, BlockPoint to, int halfWidth) {
        return new BlockBounds(
                Math.min(from.x(), to.x()) - halfWidth,
                Math.min(from.z(), to.z()) - halfWidth,
                Math.max(from.x(), to.x()) + halfWidth,
                Math.max(from.z(), to.z()) + halfWidth);
    }

    private static BlockBounds expand(BlockBounds bounds, int margin) {
        return new BlockBounds(bounds.minX() - margin, bounds.minZ() - margin,
                bounds.maxX() + margin, bounds.maxZ() + margin);
    }

    private static boolean containsBounds(BlockBounds outer, BlockBounds inner) {
        return outer.contains(inner.minX(), inner.minZ())
                && outer.contains(inner.maxX(), inner.maxZ());
    }

    private static String boundsText(BlockBounds bounds) {
        return "[" + bounds.minX() + "," + bounds.minZ() + " -> "
                + bounds.maxX() + "," + bounds.maxZ() + "]";
    }

    private static void addIfPresent(Set<String> refs, JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull() && !obj.get(key).getAsString().isBlank()) {
            refs.add(obj.get(key).getAsString());
        }
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

    private record Segment(String side, BlockPoint from, BlockPoint to, BlockBounds bounds) {
    }

}
