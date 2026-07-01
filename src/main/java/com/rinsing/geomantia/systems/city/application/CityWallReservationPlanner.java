package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.domain.model.CityLandformReviewPackage;
import com.rinsing.geomantia.systems.city.domain.model.LandformPatchSummary;
import com.rinsing.geomantia.systems.city.domain.model.PatchMemberCell;
import com.rinsing.geomantia.systems.gis.domain.cell.LandformType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CityWallReservationPlanner {
    public static final String SCHEMA = "city_wall_reservation_plan.v0.2";
    public static final String DEFAULT_WALL_VERSION = "v2";
    public static final String V1_DEBUG = "v1_debug";
    public static final int DEFAULT_WALL_CORRIDOR_HALF_WIDTH_BLOCKS = 4;
    public static final int DEFAULT_WALL_MARGIN_BLOCKS = 24;
    public static final int DEFAULT_SEGMENT_LENGTH_BLOCKS = 15;

    public JsonObject plan(CityLandformReviewPackage reviewPackage,
                           JsonObject anchorMap,
                           String requestedWallVersion,
                           int wallMarginBlocks,
                           int segmentLengthBlocks,
                           int wallCorridorHalfWidthBlocks) {
        String wallVersion = normalizeWallVersion(requestedWallVersion);
        int margin = wallMarginBlocks <= 0 ? DEFAULT_WALL_MARGIN_BLOCKS : wallMarginBlocks;
        int segmentLength = segmentLengthBlocks <= 0 ? DEFAULT_SEGMENT_LENGTH_BLOCKS : segmentLengthBlocks;
        int halfWidth = wallCorridorHalfWidthBlocks <= 0
                ? DEFAULT_WALL_CORRIDOR_HALF_WIDTH_BLOCKS : wallCorridorHalfWidthBlocks;
        if (reviewPackage == null) {
            throw new IllegalArgumentException("D3 city_landform_review_package.json is required for wall reservation.");
        }
        if (anchorMap == null || !anchorMap.has("anchors") || !anchorMap.get("anchors").isJsonArray()) {
            throw new IllegalArgumentException("D4 structure_anchor_map.json is required for wall reservation.");
        }

        List<Cell> selectedCells = V1_DEBUG.equals(wallVersion)
                ? rectangleCellsFromAnchors(reviewPackage, anchorMap, margin)
                : patchBoundaryCells(reviewPackage, anchorMap, margin);
        if (selectedCells.isEmpty()) {
            throw new IllegalArgumentException("WALL_PATCH_BOUNDARY_UNAVAILABLE: no D3 patch cells are available for City wall v2.");
        }

        List<Segment> segments = boundarySegments(selectedCells, reviewPackage.grid().cellStepBlocks(), halfWidth);
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("WALL_PATCH_BOUNDARY_UNAVAILABLE: D3 patch cells produced no wall boundary.");
        }

        JsonObject plan = new JsonObject();
        plan.addProperty("schemaVersion", SCHEMA);
        plan.addProperty("cityId", reviewPackage.cityId());
        plan.addProperty("wallVersion", wallVersion);
        plan.addProperty("boundarySource", V1_DEBUG.equals(wallVersion)
                ? "v1_debug_anchor_rectangle" : "d3_patch_member_cell_outer_boundary");
        plan.addProperty("wallPlanningStage", "d5_reservation_mask");
        plan.addProperty("wallCorridorHalfWidthBlocks", halfWidth);
        plan.addProperty("wallMarginBlocks", margin);
        plan.addProperty("segmentLengthBlocks", segmentLength);
        plan.add("sourcePatchRefs", sourcePatchRefs(reviewPackage, anchorMap));
        plan.add("wallCenterline", centerlines(segments));
        plan.add("wallCorridorMask", corridorMasks(segments));
        plan.add("towerCandidatePoints", towerCandidates(segments));
        plan.add("gateCandidateZones", gateCandidates(segments));
        plan.add("maskContribution", maskContribution(segments));
        plan.add("wallBounds", boundsJson(union(segments)));
        return plan;
    }

    public static String normalizeWallVersion(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_WALL_VERSION;
        }
        return V1_DEBUG.equalsIgnoreCase(raw) ? V1_DEBUG : DEFAULT_WALL_VERSION;
    }

    private static List<Cell> patchBoundaryCells(CityLandformReviewPackage reviewPackage,
                                                 JsonObject anchorMap,
                                                 int marginBlocks) {
        int step = Math.max(1, reviewPackage.grid().cellStepBlocks());
        int marginCells = Math.max(1, (int) Math.ceil(marginBlocks / (double) step));
        Map<String, LandformPatchSummary> patchesByRef = patchesByRef(reviewPackage);
        Set<String> selectedRefs = selectedPatchRefs(anchorMap);
        Set<String> expandedPatchIds = new LinkedHashSet<>();
        for (String ref : selectedRefs) {
            LandformPatchSummary patch = patchesByRef.get(ref);
            if (patch == null) {
                continue;
            }
            expandedPatchIds.add(patch.landformPatchId());
            for (String neighborRef : patch.neighborLandformPatchIds()) {
                LandformPatchSummary neighbor = patchesByRef.get(neighborRef);
                if (neighbor != null && boundaryFriendly(neighbor.landformType())) {
                    expandedPatchIds.add(neighbor.landformPatchId());
                }
            }
        }
        List<Cell> base = new ArrayList<>();
        for (LandformPatchSummary patch : reviewPackage.landformPatches()) {
            if (expandedPatchIds.contains(patch.landformPatchId())) {
                base.addAll(cellsForPatch(reviewPackage, patch));
            }
        }
        return expandCells(base, step, marginCells);
    }

    private static List<Cell> rectangleCellsFromAnchors(CityLandformReviewPackage reviewPackage,
                                                        JsonObject anchorMap,
                                                        int marginBlocks) {
        BlockBounds union = null;
        for (JsonElement elem : array(anchorMap, "anchors")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject anchor = elem.getAsJsonObject();
            JsonObject source = anchor.has("reservedEnvelope") && anchor.get("reservedEnvelope").isJsonObject()
                    ? anchor.getAsJsonObject("reservedEnvelope")
                    : anchor.getAsJsonObject("plannedFootprint");
            if (source == null) {
                continue;
            }
            BlockBounds bounds = bounds(source);
            union = union == null ? bounds : new BlockBounds(
                    Math.min(union.minX(), bounds.minX()),
                    Math.min(union.minZ(), bounds.minZ()),
                    Math.max(union.maxX(), bounds.maxX()),
                    Math.max(union.maxZ(), bounds.maxZ()));
        }
        if (union == null) {
            return List.of();
        }
        int step = Math.max(1, reviewPackage.grid().cellStepBlocks());
        BlockBounds expanded = expand(union, marginBlocks);
        List<Cell> cells = new ArrayList<>();
        for (int x = floorToStep(expanded.minX(), step); x <= expanded.maxX(); x += step) {
            for (int z = floorToStep(expanded.minZ(), step); z <= expanded.maxZ(); z += step) {
                cells.add(new Cell(x, z));
            }
        }
        return cells;
    }

    private static boolean boundaryFriendly(LandformType type) {
        return type == LandformType.SHORE
                || type == LandformType.RIDGE
                || type == LandformType.SLOPE
                || type == LandformType.CLIFF;
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

    private static List<Cell> cellsForPatch(CityLandformReviewPackage reviewPackage, LandformPatchSummary patch) {
        int step = Math.max(1, reviewPackage.grid().cellStepBlocks());
        List<Cell> cells = new ArrayList<>();
        if (!patch.memberCells().isEmpty()) {
            for (PatchMemberCell cell : patch.memberCells()) {
                cells.add(new Cell(cell.blockMinX(), cell.blockMinZ()));
            }
            return cells;
        }
        BlockBounds bounds = patch.blockBounds();
        for (int x = floorToStep(bounds.minX(), step); x <= bounds.maxX(); x += step) {
            for (int z = floorToStep(bounds.minZ(), step); z <= bounds.maxZ(); z += step) {
                cells.add(new Cell(x, z));
            }
        }
        return cells;
    }

    private static List<Cell> expandCells(List<Cell> cells, int step, int marginCells) {
        Set<Cell> set = new LinkedHashSet<>();
        for (Cell cell : cells) {
            for (int dx = -marginCells; dx <= marginCells; dx++) {
                for (int dz = -marginCells; dz <= marginCells; dz++) {
                    set.add(new Cell(cell.x() + dx * step, cell.z() + dz * step));
                }
            }
        }
        return List.copyOf(set);
    }

    private static List<Segment> boundarySegments(List<Cell> cells, int step, int halfWidth) {
        Set<Cell> set = new LinkedHashSet<>(cells);
        List<Segment> segments = new ArrayList<>();
        for (Cell cell : cells) {
            int x = cell.x();
            int z = cell.z();
            int maxX = x + step - 1;
            int maxZ = z + step - 1;
            if (!set.contains(new Cell(x, z - step))) {
                segments.add(segment("north", new BlockPoint(x, z), new BlockPoint(maxX, z), halfWidth));
            }
            if (!set.contains(new Cell(x + step, z))) {
                segments.add(segment("east", new BlockPoint(maxX, z), new BlockPoint(maxX, maxZ), halfWidth));
            }
            if (!set.contains(new Cell(x, z + step))) {
                segments.add(segment("south", new BlockPoint(maxX, maxZ), new BlockPoint(x, maxZ), halfWidth));
            }
            if (!set.contains(new Cell(x - step, z))) {
                segments.add(segment("west", new BlockPoint(x, maxZ), new BlockPoint(x, z), halfWidth));
            }
        }
        segments.sort(Comparator.comparing((Segment s) -> s.side).thenComparingInt(s -> s.bounds.minX())
                .thenComparingInt(s -> s.bounds.minZ()));
        return mergeCollinear(segments, halfWidth);
    }

    private static Segment segment(String side, BlockPoint from, BlockPoint to, int halfWidth) {
        BlockBounds bounds = corridorBounds(from, to, halfWidth);
        return new Segment(side, from, to, bounds);
    }

    private static List<Segment> mergeCollinear(List<Segment> raw, int halfWidth) {
        List<Segment> result = new ArrayList<>();
        for (Segment segment : raw) {
            if (result.isEmpty()) {
                result.add(segment);
                continue;
            }
            Segment last = result.get(result.size() - 1);
            if (canMerge(last, segment)) {
                BlockPoint from = last.from;
                BlockPoint to = segment.to;
                result.set(result.size() - 1, segment(last.side, from, to, halfWidth));
            } else {
                result.add(segment);
            }
        }
        return result;
    }

    private static boolean canMerge(Segment a, Segment b) {
        if (!a.side.equals(b.side)) {
            return false;
        }
        boolean horizontal = a.from.z() == a.to.z() && b.from.z() == b.to.z();
        boolean vertical = a.from.x() == a.to.x() && b.from.x() == b.to.x();
        if (horizontal && a.from.z() == b.from.z()) {
            return Math.abs(a.to.x() - b.from.x()) <= 1 || Math.abs(a.to.x() - b.to.x()) <= 1;
        }
        if (vertical && a.from.x() == b.from.x()) {
            return Math.abs(a.to.z() - b.from.z()) <= 1 || Math.abs(a.to.z() - b.to.z()) <= 1;
        }
        return false;
    }

    private static JsonArray centerlines(List<Segment> segments) {
        JsonArray out = new JsonArray();
        int index = 0;
        for (Segment segment : segments) {
            JsonObject obj = new JsonObject();
            obj.addProperty("segmentId", "wall_centerline_" + index++);
            obj.addProperty("sideHint", segment.side);
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

    private static JsonArray towerCandidates(List<Segment> segments) {
        JsonArray out = new JsonArray();
        int interval = Math.max(1, segments.size() / 12);
        for (int i = 0; i < segments.size(); i += interval) {
            Segment segment = segments.get(i);
            JsonObject obj = new JsonObject();
            obj.addProperty("towerId", "wall_tower_candidate_" + i);
            obj.add("block", segment.from.asJson());
            obj.addProperty("reasonCode", "PATCH_BOUNDARY_INTERVAL_TOWER");
            out.add(obj);
        }
        return out;
    }

    private static JsonArray gateCandidates(List<Segment> segments) {
        JsonArray out = new JsonArray();
        List<Segment> sorted = segments.stream()
                .sorted(Comparator.comparingInt(s -> -Math.max(s.bounds.widthBlocks(), s.bounds.heightBlocks())))
                .limit(4)
                .toList();
        int index = 0;
        for (Segment segment : sorted) {
            JsonObject obj = new JsonObject();
            obj.addProperty("gateCandidateId", "wall_gate_candidate_" + index++);
            obj.addProperty("sideHint", segment.side);
            obj.add("center", segment.bounds.center().asJson());
            obj.add("blockBounds", boundsJson(segment.bounds));
            obj.addProperty("reasonCode", "PATCH_BOUNDARY_LONG_SEGMENT");
            out.add(obj);
        }
        return out;
    }

    private static JsonObject maskContribution(List<Segment> segments) {
        JsonObject obj = new JsonObject();
        obj.addProperty("noVegetationMaskType", "wall_reservation_corridor");
        obj.addProperty("vegetationLimitedMaskType", "wall_reservation_transition");
        obj.addProperty("noVanillaStructureMaskType", "wall_reservation_corridor");
        obj.addProperty("segmentCount", segments.size());
        return obj;
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

    private static int floorToStep(int value, int step) {
        return Math.floorDiv(value, step) * step;
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

    private record Cell(int x, int z) {
    }

    private record Segment(String side, BlockPoint from, BlockPoint to, BlockBounds bounds) {
    }
}
