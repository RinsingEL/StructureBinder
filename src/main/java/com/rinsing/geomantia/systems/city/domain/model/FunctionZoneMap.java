package com.rinsing.geomantia.systems.city.domain.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

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
}
