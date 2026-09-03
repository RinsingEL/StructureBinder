package com.rinsing.geomantia.systems.realm_planning;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Pack/player-owned authority for the formal origin-centered W survey range. */
public record WorldSurveySettingsConfig(int planningRadiusBlocks) {
    public static final String SCHEMA = "geomantia_world_survey_settings.v0.1";
    private static final int MIN_RADIUS_BLOCKS = 512;
    private static final int MAX_RADIUS_BLOCKS = 262144;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public WorldSurveySettingsConfig {
        if (planningRadiusBlocks < MIN_RADIUS_BLOCKS || planningRadiusBlocks > MAX_RADIUS_BLOCKS) {
            throw new IllegalArgumentException("WORLD_SURVEY_CONFIG_RADIUS_OUT_OF_RANGE");
        }
    }

    public static WorldSurveySettingsConfig defaults() {
        return new WorldSurveySettingsConfig(WorldSurveyRunner.DEFAULT_PLANNING_RADIUS_BLOCKS);
    }

    public static WorldSurveySettingsConfig loadOrCreate(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            WorldSurveySettingsConfig defaults = defaults();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(defaults.asJson()));
            return defaults;
        }
        JsonObject json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        if (!SCHEMA.equals(string(json, "schema", ""))) {
            throw new IllegalArgumentException("WORLD_SURVEY_CONFIG_SCHEMA_UNSUPPORTED");
        }
        return new WorldSurveySettingsConfig(integer(json, "planningRadiusBlocks",
                WorldSurveyRunner.DEFAULT_PLANNING_RADIUS_BLOCKS));
    }

    public JsonObject asJson() {
        JsonObject json = new JsonObject();
        json.addProperty("schema", SCHEMA);
        json.addProperty("planningRadiusBlocks", planningRadiusBlocks);
        return json;
    }

    private static String string(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }

    private static int integer(JsonObject object, String key, int fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : fallback;
    }
}
