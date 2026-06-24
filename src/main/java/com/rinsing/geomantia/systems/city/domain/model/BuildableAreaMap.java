package com.rinsing.geomantia.systems.city.domain.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

public record BuildableAreaMap(
        String schemaVersion,
        String cityId,
        PlanningGrid grid,
        List<ZoneBuildability> zones,
        CityQualityReport quality) {

    public static final String CURRENT_SCHEMA_VERSION = "buildable_area_map.v0.1";

    public BuildableAreaMap {
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
        if (quality == null) {
            throw new IllegalArgumentException("quality is required");
        }
    }

    public record ZoneBuildability(
            String zonePatchId,
            CityFunctionType functionType,
            int originalCellCount,
            int reservedCellCount,
            int buildableCellCount,
            int originalAreaBlocks,
            int reservedAreaBlocks,
            int buildableAreaBlocks,
            List<ReservedCell> reservedCells,
            List<BuildableCell> buildableCells) {

        public ZoneBuildability {
            if (zonePatchId == null || zonePatchId.isBlank()) {
                throw new IllegalArgumentException("zonePatchId is required");
            }
            if (functionType == null) {
                throw new IllegalArgumentException("functionType is required");
            }
            reservedCells = List.copyOf(reservedCells);
            buildableCells = List.copyOf(buildableCells);
        }

        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("zonePatchId", zonePatchId);
            obj.addProperty("functionType", functionType.contractName());
            obj.addProperty("originalCellCount", originalCellCount);
            obj.addProperty("reservedCellCount", reservedCellCount);
            obj.addProperty("buildableCellCount", buildableCellCount);
            obj.addProperty("originalAreaBlocks", originalAreaBlocks);
            obj.addProperty("reservedAreaBlocks", reservedAreaBlocks);
            obj.addProperty("buildableAreaBlocks", buildableAreaBlocks);
            JsonArray reserved = new JsonArray();
            reservedCells.forEach(cell -> reserved.add(cell.asJson()));
            obj.add("reservedCells", reserved);
            JsonArray buildable = new JsonArray();
            buildableCells.forEach(cell -> buildable.add(cell.asJson()));
            obj.add("buildableCells", buildable);
            return obj;
        }

        public static ZoneBuildability fromJson(JsonObject obj) {
            CityFunctionType type = CityFunctionType.fromContractName(RoadIntent.requiredString(obj, "functionType"));
            if (type == null) {
                throw new IllegalArgumentException("Unknown functionType: " + RoadIntent.requiredString(obj, "functionType"));
            }
            return new ZoneBuildability(
                    RoadIntent.requiredString(obj, "zonePatchId"),
                    type,
                    RoadIntent.intValue(obj, "originalCellCount", 0),
                    RoadIntent.intValue(obj, "reservedCellCount", 0),
                    RoadIntent.intValue(obj, "buildableCellCount", 0),
                    RoadIntent.intValue(obj, "originalAreaBlocks", 0),
                    RoadIntent.intValue(obj, "reservedAreaBlocks", 0),
                    RoadIntent.intValue(obj, "buildableAreaBlocks", 0),
                    parseReservedCells(RoadIntent.optionalArray(obj, "reservedCells")),
                    parseBuildableCells(RoadIntent.optionalArray(obj, "buildableCells")));
        }
    }

    public record ReservedCell(int cellX, int cellZ, int blockMinX, int blockMinZ,
                               List<String> reservedRefs, List<String> reservationTypes) {
        public ReservedCell {
            reservedRefs = List.copyOf(reservedRefs);
            reservationTypes = List.copyOf(reservationTypes);
        }

        public JsonObject asJson() {
            JsonObject obj = cellJson(cellX, cellZ, blockMinX, blockMinZ);
            obj.add("reservedRefs", strings(reservedRefs));
            obj.add("reservationTypes", strings(reservationTypes));
            return obj;
        }

        public static ReservedCell fromJson(JsonObject obj) {
            return new ReservedCell(
                    RoadIntent.intValue(obj, "cellX", 0),
                    RoadIntent.intValue(obj, "cellZ", 0),
                    RoadIntent.intValue(obj, "blockMinX", 0),
                    RoadIntent.intValue(obj, "blockMinZ", 0),
                    RoadIntent.strings(RoadIntent.optionalArray(obj, "reservedRefs")),
                    RoadIntent.strings(RoadIntent.optionalArray(obj, "reservationTypes")));
        }
    }

    public record BuildableCell(int cellX, int cellZ, int blockMinX, int blockMinZ) {
        public JsonObject asJson() {
            return cellJson(cellX, cellZ, blockMinX, blockMinZ);
        }

        public static BuildableCell fromJson(JsonObject obj) {
            return new BuildableCell(
                    RoadIntent.intValue(obj, "cellX", 0),
                    RoadIntent.intValue(obj, "cellZ", 0),
                    RoadIntent.intValue(obj, "blockMinX", 0),
                    RoadIntent.intValue(obj, "blockMinZ", 0));
        }
    }

    public JsonObject asJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", schemaVersion);
        obj.addProperty("cityId", cityId);
        obj.add("grid", grid.asJson());
        JsonArray array = new JsonArray();
        zones.forEach(zone -> array.add(zone.asJson()));
        obj.add("zones", array);
        obj.add("quality", quality.asJson());
        return obj;
    }

    public static BuildableAreaMap fromJson(JsonObject obj) {
        List<ZoneBuildability> zones = new ArrayList<>();
        for (JsonElement elem : RoadIntent.requiredArray(obj, "zones")) {
            zones.add(ZoneBuildability.fromJson(elem.getAsJsonObject()));
        }
        return new BuildableAreaMap(
                RoadIntent.requiredString(obj, "schemaVersion"),
                RoadIntent.requiredString(obj, "cityId"),
                planningGrid(RoadIntent.requiredObject(obj, "grid")),
                zones,
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

    private static List<ReservedCell> parseReservedCells(JsonArray array) {
        List<ReservedCell> result = new ArrayList<>();
        for (JsonElement elem : array) {
            result.add(ReservedCell.fromJson(elem.getAsJsonObject()));
        }
        return result;
    }

    private static List<BuildableCell> parseBuildableCells(JsonArray array) {
        List<BuildableCell> result = new ArrayList<>();
        for (JsonElement elem : array) {
            result.add(BuildableCell.fromJson(elem.getAsJsonObject()));
        }
        return result;
    }

    private static JsonObject cellJson(int cellX, int cellZ, int blockMinX, int blockMinZ) {
        JsonObject obj = new JsonObject();
        obj.addProperty("cellX", cellX);
        obj.addProperty("cellZ", cellZ);
        obj.addProperty("blockMinX", blockMinX);
        obj.addProperty("blockMinZ", blockMinZ);
        return obj;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }
}
