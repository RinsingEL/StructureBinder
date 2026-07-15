package com.rinsing.geomantia.systems.city.infrastructure.landuse;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public final class LandUseSettingsLoader {
    private static final Set<String> FIELDS = Set.of("schemaVersion", "enabledInWorkflow", "profileId");

    public LandUseSettings load(Path cityLandUseConfigRoot) {
        if (cityLandUseConfigRoot == null) throw new IllegalArgumentException("LAND_USE_CONFIG_ROOT_MISSING");
        Path path = cityLandUseConfigRoot.toAbsolutePath().normalize().resolve("settings.json");
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("LAND_USE_SETTINGS_MISSING: " + path);
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(path));
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("LAND_USE_SETTINGS_OBJECT_REQUIRED");
            JsonObject obj = parsed.getAsJsonObject();
            for (String key : obj.keySet()) {
                if (!FIELDS.contains(key)) throw new IllegalArgumentException("LAND_USE_SETTINGS_UNKNOWN_FIELD: " + key);
            }
            return new LandUseSettings(requiredString(obj, "schemaVersion"),
                    requiredBoolean(obj, "enabledInWorkflow"), requiredString(obj, "profileId"));
        } catch (IOException ex) {
            throw new IllegalArgumentException("LAND_USE_SETTINGS_READ_FAILED: " + path, ex);
        }
    }

    private static String requiredString(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive() || !obj.getAsJsonPrimitive(key).isString()
                || obj.get(key).getAsString().isBlank()) {
            throw new IllegalArgumentException("LAND_USE_SETTINGS_FIELD_INVALID: " + key);
        }
        return obj.get(key).getAsString();
    }

    private static boolean requiredBoolean(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive() || !obj.getAsJsonPrimitive(key).isBoolean()) {
            throw new IllegalArgumentException("LAND_USE_SETTINGS_FIELD_INVALID: " + key);
        }
        return obj.get(key).getAsBoolean();
    }
}
