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

    private static JsonArray stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }
}
