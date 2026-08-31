package com.rinsing.geomantia.systems.city.infrastructure.landuse;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.domain.landuse.BoundaryPolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.SurfacePolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.VegetationPolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseRule;
import com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseRuleCatalog;
import com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Strict loader for the user-owned LandUse rule profile selected by settings.json. */
public final class LandUseRuleCatalogLoader {
    private static final Set<String> ROOT_FIELDS = Set.of("schema", "profileId", "rules");
    private static final Set<String> RULE_FIELDS = Set.of(
            "ruleRef", "landUseType", "semanticTerms", "footprintMultiplier", "extraAreaBlocks",
            "minAreaBlocks", "maxAreaBlocks", "actionBudget", "baseStepCost", "slopeCost", "reliefCost",
            "waterCost", "forestAffinity", "competitionWeight", "mergeSameType", "surfacePolicy",
            "vegetationPolicy", "boundaryPolicy", "decorationPolicy");

    public LandUseRuleCatalog load(Path cityLandUseConfigRoot, LandUseSettings settings) {
        if (cityLandUseConfigRoot == null) {
            throw new IllegalArgumentException("LAND_USE_CONFIG_ROOT_MISSING");
        }
        if (settings == null) {
            throw new IllegalArgumentException("LAND_USE_SETTINGS_MISSING");
        }
        Path root = cityLandUseConfigRoot.toAbsolutePath().normalize();
        Path profiles = root.resolve("profiles").normalize();
        Path profilePath = profiles.resolve(settings.profileId() + ".json").normalize();
        if (!profilePath.startsWith(profiles)) {
            throw new IllegalArgumentException("LAND_USE_RULE_PROFILE_PATH_INVALID: " + settings.profileId());
        }
        if (!Files.isRegularFile(profilePath)) {
            throw new IllegalArgumentException("LAND_USE_RULE_PROFILE_MISSING: " + profilePath);
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(profilePath));
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("LAND_USE_RULE_PROFILE_OBJECT_REQUIRED: " + profilePath);
            }
            return parse(parsed.getAsJsonObject(), settings.profileId());
        } catch (IOException ex) {
            throw new IllegalArgumentException("LAND_USE_RULE_PROFILE_READ_FAILED: " + profilePath, ex);
        }
    }

    public static LandUseRuleCatalog parse(JsonObject root, String selectedProfileId) {
        requireExactFields(root, ROOT_FIELDS, "LAND_USE_RULE_PROFILE");
        String schema = requiredString(root, "schema", "LAND_USE_RULE_PROFILE");
        if (!LandUseRuleCatalog.RULE_VERSION.equals(schema)) {
            throw new IllegalArgumentException("LAND_USE_RULE_PROFILE_SCHEMA_UNSUPPORTED: " + schema);
        }
        String profileId = requiredString(root, "profileId", "LAND_USE_RULE_PROFILE");
        if (!selectedProfileId.equals(profileId)) {
            throw new IllegalArgumentException("LAND_USE_RULE_PROFILE_ID_MISMATCH: expected "
                    + selectedProfileId + " but found " + profileId);
        }
        JsonArray rulesJson = requiredArray(root, "rules", "LAND_USE_RULE_PROFILE");
        if (rulesJson.isEmpty()) {
            throw new IllegalArgumentException("LAND_USE_RULE_PROFILE_RULES_EMPTY");
        }
        List<LandUseRule> rules = new ArrayList<>();
        for (JsonElement element : rulesJson) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("LAND_USE_RULE_PROFILE_RULE_OBJECT_REQUIRED");
            }
            rules.add(parseRule(element.getAsJsonObject()));
        }
        return new LandUseRuleCatalog(rules);
    }

    private static LandUseRule parseRule(JsonObject json) {
        requireExactFields(json, RULE_FIELDS, "LAND_USE_RULE");
        return new LandUseRule(
                requiredString(json, "ruleRef", "LAND_USE_RULE"),
                requiredString(json, "landUseType", "LAND_USE_RULE"),
                requiredStringArray(json, "semanticTerms", "LAND_USE_RULE"),
                requiredDouble(json, "footprintMultiplier", "LAND_USE_RULE"),
                requiredInt(json, "extraAreaBlocks", "LAND_USE_RULE"),
                requiredInt(json, "minAreaBlocks", "LAND_USE_RULE"),
                requiredInt(json, "maxAreaBlocks", "LAND_USE_RULE"),
                requiredDouble(json, "actionBudget", "LAND_USE_RULE"),
                requiredDouble(json, "baseStepCost", "LAND_USE_RULE"),
                requiredDouble(json, "slopeCost", "LAND_USE_RULE"),
                requiredDouble(json, "reliefCost", "LAND_USE_RULE"),
                requiredDouble(json, "waterCost", "LAND_USE_RULE"),
                requiredDouble(json, "forestAffinity", "LAND_USE_RULE"),
                requiredDouble(json, "competitionWeight", "LAND_USE_RULE"),
                requiredBoolean(json, "mergeSameType", "LAND_USE_RULE"),
                requiredPolicy(json, "surfacePolicy", SurfacePolicy.class),
                requiredPolicy(json, "vegetationPolicy", VegetationPolicy.class),
                requiredPolicy(json, "boundaryPolicy", BoundaryPolicy.class),
                requiredString(json, "decorationPolicy", "LAND_USE_RULE"));
    }

    private static void requireExactFields(JsonObject object, Set<String> expected, String scope) {
        for (String key : object.keySet()) {
            if (!expected.contains(key)) {
                throw new IllegalArgumentException(scope + "_UNKNOWN_FIELD: " + key);
            }
        }
        for (String key : expected) {
            if (!object.has(key)) {
                throw new IllegalArgumentException(scope + "_FIELD_REQUIRED: " + key);
            }
        }
    }

    private static String requiredString(JsonObject object, String key, String scope) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.getAsJsonPrimitive(key).isString()) {
            throw new IllegalArgumentException(scope + "_STRING_REQUIRED: " + key);
        }
        String value = object.get(key).getAsString().trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException(scope + "_STRING_REQUIRED: " + key);
        }
        return value;
    }

    private static JsonArray requiredArray(JsonObject object, String key, String scope) {
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            throw new IllegalArgumentException(scope + "_ARRAY_REQUIRED: " + key);
        }
        return object.getAsJsonArray(key);
    }

    private static List<String> requiredStringArray(JsonObject object, String key, String scope) {
        JsonArray values = requiredArray(object, key, scope);
        List<String> result = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (JsonElement value : values) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException(scope + "_STRING_ARRAY_REQUIRED: " + key);
            }
            String term = value.getAsString().trim();
            if (term.isBlank() || !unique.add(term.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(scope + "_STRING_ARRAY_INVALID: " + key);
            }
            result.add(term);
        }
        return List.copyOf(result);
    }

    private static double requiredDouble(JsonObject object, String key, String scope) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.getAsJsonPrimitive(key).isNumber()) {
            throw new IllegalArgumentException(scope + "_NUMBER_REQUIRED: " + key);
        }
        double value = object.get(key).getAsDouble();
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(scope + "_NUMBER_INVALID: " + key);
        }
        return value;
    }

    private static int requiredInt(JsonObject object, String key, String scope) {
        double value = requiredDouble(object, key, scope);
        if (value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(scope + "_INTEGER_REQUIRED: " + key);
        }
        return (int) value;
    }

    private static boolean requiredBoolean(JsonObject object, String key, String scope) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.getAsJsonPrimitive(key).isBoolean()) {
            throw new IllegalArgumentException(scope + "_BOOLEAN_REQUIRED: " + key);
        }
        return object.get(key).getAsBoolean();
    }

    private static <T extends Enum<T>> T requiredPolicy(JsonObject object, String key, Class<T> type) {
        String value = requiredString(object, key, "LAND_USE_RULE");
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("LAND_USE_RULE_POLICY_INVALID: " + key + "=" + value, ex);
        }
    }
}
