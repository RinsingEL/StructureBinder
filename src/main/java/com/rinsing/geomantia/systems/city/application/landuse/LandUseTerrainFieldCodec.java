package com.rinsing.geomantia.systems.city.application.landuse;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;

import java.util.ArrayList;
import java.util.List;

public final class LandUseTerrainFieldCodec {
    public JsonObject toJson(LandUseTerrainField field) {
        JsonObject obj = new JsonObject();
        obj.addProperty("schema", field.schema());
        obj.addProperty("cityId", field.cityId());
        obj.add("planningBounds", boundsJson(field.planningBounds()));
        obj.addProperty("cellStepBlocks", field.cellStepBlocks());
        JsonArray cells = new JsonArray();
        for (LandUseTerrainField.Cell cell : field.cells()) {
            JsonObject value = new JsonObject();
            value.addProperty("cellX", cell.cellX());
            value.addProperty("cellZ", cell.cellZ());
            value.addProperty("blockMinX", cell.blockMinX());
            value.addProperty("blockMinZ", cell.blockMinZ());
            value.addProperty("cellStepBlocks", cell.cellStepBlocks());
            value.addProperty("elevation", cell.elevation());
            value.addProperty("slope", cell.slope());
            value.addProperty("localRelief", cell.localRelief());
            value.addProperty("roughness", cell.roughness());
            value.addProperty("water", cell.water());
            value.addProperty("waterDepth", cell.waterDepth());
            if (Double.isFinite(cell.waterDistance())) {
                value.addProperty("waterDistance", cell.waterDistance());
            } else {
                value.add("waterDistance", null);
            }
            value.addProperty("biomeId", cell.biomeId());
            value.addProperty("landformType", cell.landformType());
            value.addProperty("landformPatchId", cell.landformPatchId());
            value.addProperty("sampled", cell.sampled());
            cells.add(value);
        }
        obj.add("cells", cells);
        return obj;
    }

    public LandUseTerrainField fromJson(JsonObject obj) {
        if (obj == null) throw new IllegalArgumentException("LandUse terrain field JSON is required");
        String schema = requiredString(obj, "schema");
        int defaultStep = requiredInt(obj, "cellStepBlocks");
        List<LandUseTerrainField.Cell> cells = new ArrayList<>();
        for (JsonElement element : requiredArray(obj, "cells")) {
            JsonObject value = element.getAsJsonObject();
            cells.add(new LandUseTerrainField.Cell(
                    requiredInt(value, "cellX"), requiredInt(value, "cellZ"),
                    requiredInt(value, "blockMinX"), requiredInt(value, "blockMinZ"),
                    intValue(value, "cellStepBlocks", defaultStep), doubleValue(value, "elevation", 0),
                    doubleValue(value, "slope", 0), doubleValue(value, "localRelief", 0),
                    doubleValue(value, "roughness", 0), booleanValue(value, "water", false),
                    doubleValue(value, "waterDepth", 0), nullableDouble(value, "waterDistance"),
                    stringValue(value, "biomeId", "unknown"), stringValue(value, "landformType", "unknown"),
                    stringValue(value, "landformPatchId", ""), booleanValue(value, "sampled", false)));
        }
        return new LandUseTerrainField(schema, requiredString(obj, "cityId"),
                bounds(requiredObject(obj, "planningBounds")), defaultStep, cells);
    }

    static JsonObject boundsJson(BlockBounds bounds) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minX", bounds.minX());
        obj.addProperty("minZ", bounds.minZ());
        obj.addProperty("maxX", bounds.maxX());
        obj.addProperty("maxZ", bounds.maxZ());
        return obj;
    }

    static BlockBounds bounds(JsonObject obj) {
        return new BlockBounds(requiredInt(obj, "minX"), requiredInt(obj, "minZ"),
                requiredInt(obj, "maxX"), requiredInt(obj, "maxZ"));
    }

    static JsonObject requiredObject(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonObject()) {
            throw new IllegalArgumentException(key + " object is required");
        }
        return obj.getAsJsonObject(key);
    }

    static JsonArray requiredArray(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonArray()) {
            throw new IllegalArgumentException(key + " array is required");
        }
        return obj.getAsJsonArray(key);
    }

    static String requiredString(JsonObject obj, String key) {
        String value = stringValue(obj, key, "");
        if (value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    static int requiredInt(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return obj.get(key).getAsInt();
    }

    static int intValue(JsonObject obj, String key, int fallback) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : fallback;
    }

    static double doubleValue(JsonObject obj, String key, double fallback) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsDouble() : fallback;
    }

    private static double nullableDouble(JsonObject obj, String key) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsDouble() : Double.POSITIVE_INFINITY;
    }

    static boolean booleanValue(JsonObject obj, String key, boolean fallback) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsBoolean() : fallback;
    }

    static String stringValue(JsonObject obj, String key, String fallback) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : fallback;
    }
}
