package com.rinsing.geomantia.systems.city.domain.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Objects;

public record CityQualityReport(
        boolean passed,
        int score,
        List<String> hardBlocks,
        List<String> warnings,
        List<String> needsReview,
        JsonObject metrics) {

    public CityQualityReport {
        hardBlocks = List.copyOf(Objects.requireNonNullElse(hardBlocks, List.of()));
        warnings = List.copyOf(Objects.requireNonNullElse(warnings, List.of()));
        needsReview = List.copyOf(Objects.requireNonNullElse(needsReview, List.of()));
        metrics = metrics == null ? new JsonObject() : metrics;
    }

    public JsonObject asJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("passed", passed);
        obj.addProperty("score", score);
        obj.add("hardBlocks", stringArray(hardBlocks));
        obj.add("warnings", stringArray(warnings));
        obj.add("needsReview", stringArray(needsReview));
        obj.add("metrics", metrics);
        return obj;
    }

    public static CityQualityReport fromJson(JsonObject obj) {
        if (obj == null) {
            throw new IllegalArgumentException("CityQualityReport JSON is required");
        }
        return new CityQualityReport(
                boolValue(obj, "passed", false),
                intValue(obj, "score", 0),
                strings(arrayValue(obj, "hardBlocks")),
                strings(arrayValue(obj, "warnings")),
                strings(arrayValue(obj, "needsReview")),
                obj.has("metrics") && obj.get("metrics").isJsonObject()
                        ? obj.getAsJsonObject("metrics")
                        : new JsonObject());
    }

    private static JsonArray stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static JsonArray arrayValue(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonArray()
                ? obj.getAsJsonArray(key)
                : new JsonArray();
    }

    private static List<String> strings(JsonArray array) {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        for (JsonElement elem : array) {
            if (!elem.isJsonNull()) {
                values.add(elem.getAsString());
            }
        }
        return values;
    }

    private static boolean boolValue(JsonObject obj, String key, boolean defaultValue) {
        return obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsBoolean()
                : defaultValue;
    }

    private static int intValue(JsonObject obj, String key, int defaultValue) {
        return obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsInt()
                : defaultValue;
    }
}
