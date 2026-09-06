package com.rinsing.geomantia.systems.realm_planning.application.access;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Walkable city envelope from activated world artifacts, not draft planning/search bounds. */
final class CityFootprint {
    private static final int WALK_MARGIN = 32;
    private int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
    private int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

    double clearance(double x, double z) {
        if (minX > maxX) return Double.NEGATIVE_INFINITY;
        return Math.min(Math.min(x - minX, maxX + 1.0 - x),
                Math.min(z - minZ, maxZ + 1.0 - z)) + WALK_MARGIN;
    }

    static Map<String, CityFootprint> load(Path worldRoot, String runId, String dimension) throws IOException {
        Path root = worldRoot.resolve("geomantia_city_masks");
        Map<String, CityFootprint> result = new HashMap<>();
        for (var value : array(read(root.resolve("active_planned_structure_registry.json")), "registries")) {
            JsonObject registry = value.getAsJsonObject();
            if (!runId.equals(string(registry, "runId"))) continue;
            CityFootprint footprint = new CityFootprint();
            for (var item : array(registry, "plannedStructures")) {
                JsonObject structure = item.getAsJsonObject();
                for (String key : new String[]{"maskEnvelope", "reservedEnvelope", "plannedFootprint"}) {
                    if (structure.has(key)) footprint.bounds(structure.getAsJsonObject(key));
                }
            }
            result.put(string(registry, "cityId"), footprint);
        }
        for (var value : array(read(root.resolve("active_city_land_use_area_plans.json")), "plans")) {
            JsonObject plan = value.getAsJsonObject();
            CityFootprint footprint = result.get(string(plan, "cityId"));
            // A LandUse plan alone must never release an unrelated run/city/dimension.
            if (footprint == null || !dimension.equals(string(plan, "dimensionId"))) continue;
            for (var area : array(plan.getAsJsonObject("areaPlan"), "areas")) {
                JsonObject object = area.getAsJsonObject();
                for (var span : array(object, "memberSpans")) {
                    JsonObject row = span.getAsJsonObject();
                    footprint.include(row.get("minX").getAsInt(), row.get("z").getAsInt());
                    footprint.include(row.get("maxX").getAsInt(), row.get("z").getAsInt());
                }
                for (var cell : array(object, "memberCells")) footprint.point(cell.getAsJsonObject());
            }
            for (var cell : array(plan.getAsJsonObject("surfacePrintPlan"), "featureCells")) {
                footprint.point(cell.getAsJsonObject());
            }
        }
        return Map.copyOf(result);
    }

    private void bounds(JsonObject bounds) {
        include(bounds.get("minX").getAsInt(), bounds.get("minZ").getAsInt());
        include(bounds.get("maxX").getAsInt(), bounds.get("maxZ").getAsInt());
    }
    private void point(JsonObject point) { include(point.get("x").getAsInt(), point.get("z").getAsInt()); }
    private void include(int x, int z) {
        minX = Math.min(minX, x); maxX = Math.max(maxX, x);
        minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
    }
    private static JsonObject read(Path path) throws IOException {
        return Files.isRegularFile(path) ? JsonParser.parseString(Files.readString(path)).getAsJsonObject() : null;
    }
    private static JsonArray array(JsonObject object, String name) {
        return object != null && object.has(name) ? object.getAsJsonArray(name) : new JsonArray();
    }
    private static String string(JsonObject object, String name) {
        return object.has(name) ? object.get(name).getAsString() : "";
    }
}
