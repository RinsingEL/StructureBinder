package com.rinsing.geomantia.systems.city.domain.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Objects;

public record FunctionZoneTerrainStats(
        String zonePatchId,
        int areaBlocks,
        String shapeClass,
        double heightMin,
        double heightMax,
        double heightMean,
        double waterDepthMin,
        double waterDepthMax,
        double waterDepthMean,
        double slopeMean,
        double slopeP90,
        double slopeMax,
        int shorelineLengthBlocks,
        double waterContactRatio,
        List<String> dominantLandformTypes,
        List<String> gisFlags,
        String estimatedCapacity) {

    public FunctionZoneTerrainStats {
        if (zonePatchId == null || zonePatchId.isBlank()) {
            throw new IllegalArgumentException("zonePatchId is required");
        }
        if (areaBlocks < 0) {
            throw new IllegalArgumentException("areaBlocks must be >= 0");
        }
        shapeClass = shapeClass == null ? "unknown" : shapeClass;
        dominantLandformTypes = List.copyOf(Objects.requireNonNullElse(dominantLandformTypes, List.of()));
        gisFlags = List.copyOf(Objects.requireNonNullElse(gisFlags, List.of()));
        estimatedCapacity = estimatedCapacity == null ? "0-0" : estimatedCapacity;
    }

    public JsonObject asJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("zonePatchId", zonePatchId);
        obj.addProperty("areaBlocks", areaBlocks);
        obj.addProperty("shapeClass", shapeClass);
        obj.addProperty("heightMin", heightMin);
        obj.addProperty("heightMax", heightMax);
        obj.addProperty("heightMean", heightMean);
        obj.addProperty("waterDepthMin", waterDepthMin);
        obj.addProperty("waterDepthMax", waterDepthMax);
        obj.addProperty("waterDepthMean", waterDepthMean);
        obj.addProperty("slopeMean", slopeMean);
        obj.addProperty("slopeP90", slopeP90);
        obj.addProperty("slopeMax", slopeMax);
        obj.addProperty("shorelineLengthBlocks", shorelineLengthBlocks);
        obj.addProperty("waterContactRatio", waterContactRatio);
        obj.add("dominantLandformTypes", stringArray(dominantLandformTypes));
        obj.add("gisFlags", stringArray(gisFlags));
        obj.addProperty("estimatedCapacity", estimatedCapacity);
        return obj;
    }

    public static FunctionZoneTerrainStats fromJson(JsonObject obj) {
        return new FunctionZoneTerrainStats(
                RoadIntent.requiredString(obj, "zonePatchId"),
                RoadIntent.intValue(obj, "areaBlocks", 0),
                RoadIntent.stringValue(obj, "shapeClass", "unknown"),
                doubleValue(obj, "heightMin", 0),
                doubleValue(obj, "heightMax", 0),
                doubleValue(obj, "heightMean", 0),
                doubleValue(obj, "waterDepthMin", 0),
                doubleValue(obj, "waterDepthMax", 0),
                doubleValue(obj, "waterDepthMean", 0),
                doubleValue(obj, "slopeMean", 0),
                doubleValue(obj, "slopeP90", 0),
                doubleValue(obj, "slopeMax", 0),
                RoadIntent.intValue(obj, "shorelineLengthBlocks", 0),
                doubleValue(obj, "waterContactRatio", 0),
                RoadIntent.strings(RoadIntent.optionalArray(obj, "dominantLandformTypes")),
                RoadIntent.strings(RoadIntent.optionalArray(obj, "gisFlags")),
                RoadIntent.stringValue(obj, "estimatedCapacity", "0-0"));
    }

    private static JsonArray stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static double doubleValue(JsonObject obj, String key, double defaultValue) {
        return obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsDouble()
                : defaultValue;
    }
}
