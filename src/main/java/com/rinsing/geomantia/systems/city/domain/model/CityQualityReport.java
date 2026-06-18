package com.rinsing.geomantia.systems.city.domain.model;

import com.google.gson.JsonArray;
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

    private static JsonArray stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }
}
