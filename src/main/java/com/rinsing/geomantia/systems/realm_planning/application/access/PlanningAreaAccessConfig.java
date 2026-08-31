package com.rinsing.geomantia.systems.realm_planning.application.access;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

public record PlanningAreaAccessConfig(boolean enabled,
                                       int initialActivityRadiusBlocks,
                                       int firstCityMinimumDistanceBlocks,
                                       Set<String> managedDimensions) {
    public static final String SCHEMA = "geomantia_planning_area_access.v0.1";
    public static final int DEFAULT_INITIAL_RADIUS_BLOCKS = 2048;
    public static final int DEFAULT_FIRST_CITY_DISTANCE_BLOCKS = 3072;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public PlanningAreaAccessConfig {
        if (initialActivityRadiusBlocks < 0) throw new IllegalArgumentException("initialActivityRadiusBlocks < 0");
        if (firstCityMinimumDistanceBlocks < initialActivityRadiusBlocks) {
            throw new IllegalArgumentException("firstCityMinimumDistanceBlocks must be >= initialActivityRadiusBlocks");
        }
        managedDimensions = Set.copyOf(managedDimensions == null || managedDimensions.isEmpty()
                ? Set.of("minecraft:overworld") : managedDimensions);
    }

    public static PlanningAreaAccessConfig defaults() {
        return new PlanningAreaAccessConfig(true, DEFAULT_INITIAL_RADIUS_BLOCKS,
                DEFAULT_FIRST_CITY_DISTANCE_BLOCKS, Set.of("minecraft:overworld"));
    }

    public static PlanningAreaAccessConfig loadOrCreate(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            PlanningAreaAccessConfig defaults = defaults();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(defaults.asJson()));
            return defaults;
        }
        JsonObject json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        if (!SCHEMA.equals(stringValue(json, "schema", ""))) {
            throw new IllegalArgumentException("PLANNING_AREA_CONFIG_SCHEMA_UNSUPPORTED");
        }
        Set<String> dimensions = new LinkedHashSet<>();
        if (json.has("managedDimensions") && json.get("managedDimensions").isJsonArray()) {
            json.getAsJsonArray("managedDimensions").forEach(value -> dimensions.add(value.getAsString()));
        }
        return new PlanningAreaAccessConfig(booleanValue(json, "enabled", true),
                intValue(json, "initialActivityRadiusBlocks", DEFAULT_INITIAL_RADIUS_BLOCKS),
                intValue(json, "firstCityMinimumDistanceBlocks", DEFAULT_FIRST_CITY_DISTANCE_BLOCKS), dimensions);
    }

    public JsonObject asJson() {
        JsonObject json = new JsonObject();
        json.addProperty("schema", SCHEMA);
        json.addProperty("enabled", enabled);
        json.addProperty("initialActivityRadiusBlocks", initialActivityRadiusBlocks);
        json.addProperty("firstCityMinimumDistanceBlocks", firstCityMinimumDistanceBlocks);
        JsonArray dimensions = new JsonArray();
        managedDimensions.stream().sorted().forEach(dimensions::add);
        json.add("managedDimensions", dimensions);
        return json;
    }

    private static String stringValue(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : fallback;
    }

    private static boolean booleanValue(JsonObject object, String key, boolean fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsBoolean() : fallback;
    }
}
