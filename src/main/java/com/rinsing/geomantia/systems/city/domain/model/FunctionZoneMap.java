package com.rinsing.geomantia.systems.city.domain.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

public record FunctionZoneMap(
        String schemaVersion,
        String cityId,
        PlanningGrid grid,
        List<FunctionZonePatch> zones,
        List<CellAssignment> cellAssignments,
        CityQualityReport quality) {

    public static final String CURRENT_SCHEMA_VERSION = "function_zone_map.v0.1";

    public FunctionZoneMap {
        if (schemaVersion == null || schemaVersion.isBlank()) {
            throw new IllegalArgumentException("schemaVersion is required");
        }
        if (cityId == null || cityId.isBlank()) {
            throw new IllegalArgumentException("cityId is required");
        }
        if (grid == null) {
            throw new IllegalArgumentException("grid is required");
        }
        zones = List.copyOf(zones);
        cellAssignments = List.copyOf(cellAssignments);
        if (quality == null) {
            throw new IllegalArgumentException("quality is required");
        }
    }

    public record CellAssignment(String zonePatchId, String landformPatchId, String geometryMode,
                                 BlockBounds blockBounds, List<PatchMemberCell> memberCells) {
        public CellAssignment {
            if (zonePatchId == null || zonePatchId.isBlank()) {
                throw new IllegalArgumentException("zonePatchId is required");
            }
            if (landformPatchId == null || landformPatchId.isBlank()) {
                throw new IllegalArgumentException("landformPatchId is required");
            }
            geometryMode = geometryMode == null ? "patch_envelope" : geometryMode;
            if (blockBounds == null) {
                throw new IllegalArgumentException("blockBounds is required");
            }
            memberCells = List.copyOf(memberCells);
        }

        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("zonePatchId", zonePatchId);
            obj.addProperty("landformPatchId", landformPatchId);
            obj.addProperty("geometryMode", geometryMode);
            JsonObject bounds = new JsonObject();
            bounds.addProperty("minX", blockBounds.minX());
            bounds.addProperty("minZ", blockBounds.minZ());
            bounds.addProperty("maxX", blockBounds.maxX());
            bounds.addProperty("maxZ", blockBounds.maxZ());
            obj.add("blockBounds", bounds);
            JsonArray cells = new JsonArray();
            memberCells.forEach(cell -> cells.add(cell.asJson()));
            obj.add("memberCells", cells);
            return obj;
        }

        public static CellAssignment fromJson(JsonObject obj) {
            JsonObject bounds = RoadIntent.requiredObject(obj, "blockBounds");
            return new CellAssignment(
                    RoadIntent.requiredString(obj, "zonePatchId"),
                    RoadIntent.requiredString(obj, "landformPatchId"),
                    RoadIntent.stringValue(obj, "geometryMode", "patch_envelope"),
                    new BlockBounds(
                            RoadIntent.intValue(bounds, "minX", 0),
                            RoadIntent.intValue(bounds, "minZ", 0),
                            RoadIntent.intValue(bounds, "maxX", 0),
                            RoadIntent.intValue(bounds, "maxZ", 0)),
                    FunctionZoneMap.memberCells(RoadIntent.optionalArray(obj, "memberCells")));
        }
    }

    public JsonObject asJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", schemaVersion);
        obj.addProperty("cityId", cityId);
        obj.add("grid", grid.asJson());
        JsonArray zoneArray = new JsonArray();
        zones.forEach(zone -> zoneArray.add(zone.asJson()));
        obj.add("zones", zoneArray);
        JsonArray assignmentArray = new JsonArray();
        cellAssignments.forEach(assignment -> assignmentArray.add(assignment.asJson()));
        obj.add("cellAssignments", assignmentArray);
        obj.add("quality", quality.asJson());
        return obj;
    }

    public static FunctionZoneMap fromJson(JsonObject obj) {
        List<FunctionZonePatch> zones = new ArrayList<>();
        for (JsonElement elem : RoadIntent.requiredArray(obj, "zones")) {
            zones.add(FunctionZonePatch.fromJson(elem.getAsJsonObject()));
        }
        List<CellAssignment> assignments = new ArrayList<>();
        for (JsonElement elem : RoadIntent.optionalArray(obj, "cellAssignments")) {
            assignments.add(CellAssignment.fromJson(elem.getAsJsonObject()));
        }
        return new FunctionZoneMap(
                RoadIntent.requiredString(obj, "schemaVersion"),
                RoadIntent.requiredString(obj, "cityId"),
                planningGrid(RoadIntent.requiredObject(obj, "grid")),
                zones,
                assignments,
                CityQualityReport.fromJson(RoadIntent.requiredObject(obj, "quality")));
    }

    private static PlanningGrid planningGrid(JsonObject obj) {
        return new PlanningGrid(
                RoadIntent.intValue(obj, "originBlockX", 0),
                RoadIntent.intValue(obj, "originBlockZ", 0),
                RoadIntent.intValue(obj, "cellStepBlocks", 1),
                RoadIntent.intValue(obj, "cellsX", 1),
                RoadIntent.intValue(obj, "cellsZ", 1));
    }

    private static List<PatchMemberCell> memberCells(JsonArray array) {
        List<PatchMemberCell> cells = new ArrayList<>();
        for (JsonElement elem : array) {
            JsonObject obj = elem.getAsJsonObject();
            cells.add(new PatchMemberCell(
                    RoadIntent.intValue(obj, "cellX", 0),
                    RoadIntent.intValue(obj, "cellZ", 0),
                    RoadIntent.intValue(obj, "blockMinX", 0),
                    RoadIntent.intValue(obj, "blockMinZ", 0)));
        }
        return cells;
    }
}
