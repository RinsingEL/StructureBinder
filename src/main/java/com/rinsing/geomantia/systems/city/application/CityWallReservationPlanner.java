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
    public static final String SCHEMA_V3 = "city_wall_reservation_plan.v0.3";
    public static final String DEFAULT_WALL_VERSION = "v2";
    public static final String V1_DEBUG = "v1_debug";
    public static final String V2 = "v2";
    public static final String V3 = "v3";
    public static final String V4 = "v4";
    public static final int DEFAULT_WALL_CORRIDOR_HALF_WIDTH_BLOCKS = 4;
    public static final int DEFAULT_WALL_MARGIN_BLOCKS = 24;
    public static final int DEFAULT_SEGMENT_LENGTH_BLOCKS = 15;
    public static final int DEFAULT_WALL_BREATHING_ROOM_BLOCKS = 24;
    public static final int DEFAULT_PATCH_EXPANSION_MAX_ROUNDS = 4;
    public static final int DEFAULT_CONCAVITY_OPENING_MAX_BLOCKS = 64;
    public static final double DEFAULT_CONCAVITY_DEPTH_RATIO_MIN = 0.6;

    public JsonObject plan(CityLandformReviewPackage reviewPackage,
                           JsonObject anchorMap,
                           String requestedWallVersion,
                           int wallMarginBlocks,
                           int segmentLengthBlocks,
                           int wallCorridorHalfWidthBlocks) {
        return plan(reviewPackage, anchorMap, requestedWallVersion, wallMarginBlocks, segmentLengthBlocks,
                wallCorridorHalfWidthBlocks, V3Options.defaults());
    }

    public JsonObject plan(CityLandformReviewPackage reviewPackage,
                           JsonObject anchorMap,
                           String requestedWallVersion,
                           int wallMarginBlocks,
                           int segmentLengthBlocks,
                           int wallCorridorHalfWidthBlocks,
                           V3Options v3Options) {
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

        if (V3.equals(wallVersion) || V4.equals(wallVersion)) {
            JsonObject plan = planV3(reviewPackage, anchorMap, margin, segmentLength, halfWidth,
                    v3Options == null ? V3Options.defaults() : v3Options);
            if (V4.equals(wallVersion)) {
                plan.addProperty("wallVersion", V4);
                plan.addProperty("boundarySource", "actual_footprint_land_ring_deferred_to_d7");
                plan.addProperty("wallPlanningStage", "d5_reservation_mask_for_d7_v4_graph");
                plan.addProperty("finalBoundaryDeferredToD7", true);
            }
            return plan;
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
        if (V1_DEBUG.equalsIgnoreCase(raw)) {
            return V1_DEBUG;
        }
        if (V3.equalsIgnoreCase(raw)) {
            return V3;
        }
        if (V4.equalsIgnoreCase(raw)) {
            return V4;
        }
        return DEFAULT_WALL_VERSION;
    }

    private static JsonObject planV3(CityLandformReviewPackage reviewPackage,
                                     JsonObject anchorMap,
                                     int margin,
                                     int segmentLength,
                                     int halfWidth,
                                     V3Options options) {
        int step = Math.max(1, reviewPackage.grid().cellStepBlocks());
        Map<String, LandformPatchSummary> patchesByRef = patchesByRef(reviewPackage);
        Map<Cell, LandformPatchSummary> patchByCell = patchByCell(reviewPackage);
        Set<Cell> seedCells = seedCells(reviewPackage, anchorMap, patchesByRef, options.wallBreathingRoomBlocks());
        if (seedCells.isEmpty()) {
            throw new IllegalArgumentException("WALL_PATCH_BOUNDARY_UNAVAILABLE: no seed patch cells are available for City wall v3.");
        }

        List<JsonObject> expansionTrace = new ArrayList<>();
        Set<Cell> domain = new LinkedHashSet<>(seedCells);
        for (int round = 1; round <= Math.max(0, options.patchExpansionMaxRounds()); round++) {
            Set<Cell> additions = new LinkedHashSet<>();
            for (Cell cell : domain) {
                for (Cell neighbor : neighbors(cell, step)) {
                    if (domain.contains(neighbor)) {
                        continue;
                    }
                    LandformPatchSummary patch = patchByCell.get(neighbor);
                    if (patch == null) {
                        continue;
                    }
                    if (shouldAbsorbPatchCell(patch, round)) {
                        additions.add(neighbor);
                        expansionTrace.add(traceCell("PATCH_ABSORBED_COMPACTNESS", round, neighbor, patch));
                    } else if (round == 1 && boundaryFriendly(patch.landformType())) {
                        additions.add(neighbor);
                        expansionTrace.add(traceCell("PATCH_ABSORBED_NATURAL_BOUNDARY", round, neighbor, patch));
                    } else {
                        expansionTrace.add(traceCell(rejectReason(patch), round, neighbor, patch));
                    }
                }
            }
            if (additions.isEmpty()) {
                break;
            }
            domain.addAll(additions);
        }

        CleanupResult cleanup = cleanupDomain(domain, step, options);
        Set<Cell> cleaned = cleanup.cells();
        List<Segment> segments = boundarySegments(new ArrayList<>(cleaned), step, halfWidth);
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("WALL_PATCH_BOUNDARY_UNAVAILABLE: v3 city domain produced no wall boundary.");
        }

        JsonObject plan = new JsonObject();
        plan.addProperty("schemaVersion", SCHEMA_V3);
        plan.addProperty("cityId", reviewPackage.cityId());
        plan.addProperty("wallVersion", V3);
        plan.addProperty("boundarySource", "structure_seeded_patch_region_hull");
        plan.addProperty("wallPlanningStage", "d5_reservation_mask");
        plan.addProperty("wallCorridorHalfWidthBlocks", halfWidth);
        plan.addProperty("wallMarginBlocks", margin);
        plan.addProperty("segmentLengthBlocks", segmentLength);
        plan.addProperty("wallBreathingRoomBlocks", options.wallBreathingRoomBlocks());
        plan.addProperty("patchExpansionMaxRounds", options.patchExpansionMaxRounds());
        plan.addProperty("concavityOpeningMaxBlocks", options.concavityOpeningMaxBlocks());
        plan.addProperty("concavityDepthRatioMin", options.concavityDepthRatioMin());
        plan.add("sourcePatchRefs", sourcePatchRefs(reviewPackage, anchorMap));
        plan.add("seedPatches", seedPatchRefs(reviewPackage, seedCells, patchByCell));
        plan.add("patchExpansionTrace", jsonArray(expansionTrace));
        plan.add("cityDomainMask", cellMasks(cleaned, step, "city_domain_cell", "city_domain_hull"));
        plan.add("domainCleanupReport", cleanup.report());
        plan.add("outerWallRing", centerlines(segments));
        plan.add("wallCenterline", centerlines(segments));
        plan.add("wallCorridorMask", corridorMasks(segments));
        plan.add("towerCandidatePoints", towerCandidates(segments));
        plan.add("gateCandidateZones", gateCandidates(segments));
        plan.add("maskContribution", maskContribution(segments));
        plan.add("wallBounds", boundsJson(union(segments)));
        JsonObject debug = new JsonObject();
        debug.addProperty("seedCellCount", seedCells.size());
        debug.addProperty("domainCellCount", cleaned.size());
        debug.addProperty("wallSegmentCount", segments.size());
        debug.addProperty("cellStepBlocks", step);
        plan.add("wallReservationDebug", debug);
        return plan;
    }

    private static boolean shouldAbsorbPatchCell(LandformPatchSummary patch, int round) {
        if (round == 1) {
            return true;
        }
        LandformType type = patch.landformType();
        return type != LandformType.CLIFF && type != LandformType.WATER;
    }

    private static String rejectReason(LandformPatchSummary patch) {
        return switch (patch.landformType()) {
            case CLIFF -> "PATCH_REJECTED_TERRAIN_STEEP";
            case WATER -> "PATCH_REJECTED_DEEP_WATER";
            default -> "PATCH_REJECTED_LOW_GAIN";
        };
    }

    private static Map<Cell, LandformPatchSummary> patchByCell(CityLandformReviewPackage reviewPackage) {
        Map<Cell, LandformPatchSummary> out = new LinkedHashMap<>();
        for (LandformPatchSummary patch : reviewPackage.landformPatches()) {
            for (Cell cell : cellsForPatch(reviewPackage, patch)) {
                out.putIfAbsent(cell, patch);
            }
        }
        return out;
    }

    private static Set<Cell> seedCells(CityLandformReviewPackage reviewPackage,
                                       JsonObject anchorMap,
                                       Map<String, LandformPatchSummary> patchesByRef,
                                       int breathingRoomBlocks) {
        int step = Math.max(1, reviewPackage.grid().cellStepBlocks());
        int breathingCells = Math.max(1, (int) Math.ceil(breathingRoomBlocks / (double) step));
        Set<Cell> seed = new LinkedHashSet<>();
        for (String ref : selectedPatchRefs(anchorMap)) {
            LandformPatchSummary patch = patchesByRef.get(ref);
            if (patch != null) {
                seed.addAll(cellsForPatch(reviewPackage, patch));
            }
        }
        for (JsonElement anchorElem : array(anchorMap, "anchors")) {
            if (!anchorElem.isJsonObject()) {
                continue;
            }
            JsonObject anchor = anchorElem.getAsJsonObject();
            JsonObject footprint = anchor.has("plannedFootprint") && anchor.get("plannedFootprint").isJsonObject()
                    ? anchor.getAsJsonObject("plannedFootprint")
                    : anchor.has("reservedEnvelope") && anchor.get("reservedEnvelope").isJsonObject()
                    ? anchor.getAsJsonObject("reservedEnvelope") : null;
            if (footprint != null) {
                BlockBounds expanded = expand(bounds(footprint), breathingRoomBlocks);
                for (int x = floorToStep(expanded.minX(), step); x <= expanded.maxX(); x += step) {
                    for (int z = floorToStep(expanded.minZ(), step); z <= expanded.maxZ(); z += step) {
                        seed.add(new Cell(x, z));
                    }
                }
            }
            if (anchor.has("anchorBlock") && anchor.get("anchorBlock").isJsonObject()) {
                JsonObject block = anchor.getAsJsonObject("anchorBlock");
                Cell center = new Cell(floorToStep(intValue(block, "x", 0), step),
                        floorToStep(intValue(block, "z", 0), step));
                for (int dx = -breathingCells; dx <= breathingCells; dx++) {
                    for (int dz = -breathingCells; dz <= breathingCells; dz++) {
                        seed.add(new Cell(center.x() + dx * step, center.z() + dz * step));
                    }
                }
            }
        }
        return seed;
    }

    private static CleanupResult cleanupDomain(Set<Cell> domain, int step, V3Options options) {
        Set<Cell> cells = new LinkedHashSet<>(domain);
        JsonObject report = new JsonObject();
        JsonArray events = new JsonArray();
        int filled = 0;
        for (int pass = 0; pass < 4; pass++) {
            List<Cell> additions = new ArrayList<>();
            for (Cell candidate : candidateHoleCells(cells, step)) {
                int neighbors = neighborCount(cells, candidate, step);
                if (neighbors >= 3) {
                    additions.add(candidate);
                }
            }
            if (additions.isEmpty()) {
                break;
            }
            for (Cell cell : additions) {
                if (cells.add(cell)) {
                    filled++;
                    JsonObject event = new JsonObject();
                    event.addProperty("reasonCode", "DOMAIN_CONCAVITY_FILLED");
                    event.addProperty("cellX", cell.x());
                    event.addProperty("cellZ", cell.z());
                    events.add(event);
                }
            }
        }

        Set<Cell> main = largestComponent(cells, step);
        int trimmed = cells.size() - main.size();
        report.addProperty("inputCellCount", domain.size());
        report.addProperty("outputCellCount", main.size());
        report.addProperty("filledCellCount", filled);
        report.addProperty("trimmedDisconnectedCellCount", trimmed);
        report.addProperty("concavityOpeningMaxBlocks", options.concavityOpeningMaxBlocks());
        report.addProperty("concavityDepthRatioMin", options.concavityDepthRatioMin());
        report.add("events", events);
        if (trimmed > 0) {
            JsonObject event = new JsonObject();
            event.addProperty("reasonCode", "DOMAIN_APPENDAGE_TRIMMED");
            event.addProperty("trimmedCellCount", trimmed);
            events.add(event);
        }
        return new CleanupResult(main, report);
    }

    private static Set<Cell> largestComponent(Set<Cell> cells, int step) {
        Set<Cell> remaining = new LinkedHashSet<>(cells);
        Set<Cell> largest = new LinkedHashSet<>();
        while (!remaining.isEmpty()) {
            Cell start = remaining.iterator().next();
            Set<Cell> component = new LinkedHashSet<>();
            List<Cell> queue = new ArrayList<>();
            queue.add(start);
            remaining.remove(start);
            for (int i = 0; i < queue.size(); i++) {
                Cell cell = queue.get(i);
                component.add(cell);
                for (Cell neighbor : neighbors(cell, step)) {
                    if (remaining.remove(neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }
            if (component.size() > largest.size()) {
                largest = component;
            }
        }
        return largest;
    }

    private static List<Cell> candidateHoleCells(Set<Cell> cells, int step) {
        List<Cell> out = new ArrayList<>();
        if (cells.isEmpty()) {
            return out;
        }
        BlockBounds bounds = cellBounds(cells, step);
        for (int x = bounds.minX(); x <= bounds.maxX(); x += step) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z += step) {
                Cell cell = new Cell(x, z);
                if (!cells.contains(cell) && neighborCount(cells, cell, step) >= 2) {
                    out.add(cell);
                }
            }
        }
        return out;
    }

    private static int neighborCount(Set<Cell> cells, Cell cell, int step) {
        int count = 0;
        for (Cell neighbor : neighbors(cell, step)) {
            if (cells.contains(neighbor)) {
                count++;
            }
        }
        return count;
    }

    private static List<Cell> neighbors(Cell cell, int step) {
        return List.of(
                new Cell(cell.x(), cell.z() - step),
                new Cell(cell.x() + step, cell.z()),
                new Cell(cell.x(), cell.z() + step),
                new Cell(cell.x() - step, cell.z()));
    }

    private static JsonObject traceCell(String reasonCode, int round, Cell cell, LandformPatchSummary patch) {
        JsonObject obj = new JsonObject();
        obj.addProperty("reasonCode", reasonCode);
        obj.addProperty("round", round);
        obj.addProperty("cellX", cell.x());
        obj.addProperty("cellZ", cell.z());
        obj.addProperty("landformPatchId", patch.landformPatchId());
        obj.addProperty("mapLabel", patch.mapLabel());
        obj.addProperty("landformType", patch.landformType().contractName());
        return obj;
    }

    private static JsonArray seedPatchRefs(CityLandformReviewPackage reviewPackage,
                                           Set<Cell> seedCells,
                                           Map<Cell, LandformPatchSummary> patchByCell) {
        JsonArray out = new JsonArray();
        Set<String> seen = new LinkedHashSet<>();
        for (Cell cell : seedCells) {
            LandformPatchSummary patch = patchByCell.get(cell);
            if (patch == null || !seen.add(patch.landformPatchId())) {
                continue;
            }
            JsonObject obj = new JsonObject();
            obj.addProperty("landformPatchId", patch.landformPatchId());
            obj.addProperty("mapLabel", patch.mapLabel());
            obj.addProperty("landformType", patch.landformType().contractName());
            obj.addProperty("geometryMode", patch.geometryMode());
            obj.addProperty("cellStepBlocks", Math.max(1, reviewPackage.grid().cellStepBlocks()));
            obj.add("blockBounds", boundsJson(patch.blockBounds()));
            if (!patch.memberCells().isEmpty()) {
                JsonArray cells = new JsonArray();
                for (PatchMemberCell memberCell : patch.memberCells()) {
                    cells.add(memberCell.asJson());
                }
                obj.add("memberCells", cells);
            }
            out.add(obj);
        }
        return out;
    }

    private static JsonArray cellMasks(Set<Cell> cells, int step, String maskType, String sourceRef) {
        JsonArray out = new JsonArray();
        int index = 0;
        for (Cell cell : cells) {
            JsonObject obj = new JsonObject();
            obj.addProperty("maskId", maskType + "_" + index++);
            obj.addProperty("maskType", maskType);
            obj.addProperty("sourceRef", sourceRef);
            obj.add("blockBounds", boundsJson(new BlockBounds(cell.x(), cell.z(),
                    cell.x() + step - 1, cell.z() + step - 1)));
            out.add(obj);
        }
        return out;
    }

    private static JsonArray jsonArray(List<JsonObject> objects) {
        JsonArray out = new JsonArray();
        for (JsonObject object : objects) {
            out.add(object);
        }
        return out;
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

    private static BlockBounds cellBounds(Set<Cell> cells, int step) {
        Cell first = cells.iterator().next();
        int minX = first.x();
        int minZ = first.z();
        int maxX = first.x() + step - 1;
        int maxZ = first.z() + step - 1;
        for (Cell cell : cells) {
            minX = Math.min(minX, cell.x());
            minZ = Math.min(minZ, cell.z());
            maxX = Math.max(maxX, cell.x() + step - 1);
            maxZ = Math.max(maxZ, cell.z() + step - 1);
        }
        return new BlockBounds(minX, minZ, maxX, maxZ);
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

    public record V3Options(int wallBreathingRoomBlocks,
                            int patchExpansionMaxRounds,
                            int concavityOpeningMaxBlocks,
                            double concavityDepthRatioMin) {
        public static V3Options defaults() {
            return new V3Options(
                    DEFAULT_WALL_BREATHING_ROOM_BLOCKS,
                    DEFAULT_PATCH_EXPANSION_MAX_ROUNDS,
                    DEFAULT_CONCAVITY_OPENING_MAX_BLOCKS,
                    DEFAULT_CONCAVITY_DEPTH_RATIO_MIN);
        }
    }

    private record CleanupResult(Set<Cell> cells, JsonObject report) {
    }
}
