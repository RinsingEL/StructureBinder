package com.rinsing.geomantia.systems.realm_planning;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Set;

/** Strict formal AI input; debug fixture defaults are not a production design. */
public final class RealmProfileInput {
    private RealmProfileInput() { }

    public static JsonObject schema() {
        JsonObject properties = new JsonObject();
        for (String key : new String[]{"realmId", "name", "targetContinentId", "theme"}) properties.add(key, text());
        for (String key : new String[]{"cultureTags", "industryTags", "materialTags", "landformPreferences", "avoidLandforms"}) {
            JsonObject values = type("array");
            values.add("items", text());
            properties.add(key, values);
        }
        JsonObject scale = new JsonObject();
        scale.add("priority", choice("minor", "normal", "major", "empire"));
        scale.add("normalizationGroup", text());
        for (String key : new String[]{"targetAreaRatio", "minAreaRatio", "maxAreaRatio"}) scale.add(key, number(0, 1));
        properties.add("scalePlan", object(scale));
        JsonObject expansion = new JsonObject();
        for (String key : new String[]{"waterAffinity", "compactness", "coastalBias", "resourceSeeking", "borderPressure"}) {
            expansion.add(key, number(0, 1));
        }
        expansion.add("mountainAffinity", number(-1, 1));
        expansion.add("forestAffinity", number(-1, 1));
        expansion.add("seaCrossingPolicy", choice("none", "limited", "allowed"));
        JsonObject terrainCosts = type("object");
        terrainCosts.add("additionalProperties", number(0.05, 50));
        expansion.add("terrainCosts", terrainCosts);
        properties.add("expansionStyle", object(expansion, "terrainCosts"));
        return object(properties);
    }

    public static JsonArray requireProfiles(JsonObject request) {
        JsonElement value = request.get("realmProfiles");
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().isEmpty()) {
            throw invalid("$.realmProfiles", "Supply explicit realm designs; automatic draft profiles are not allowed.");
        }
        JsonArray profiles = value.getAsJsonArray();
        if (profiles.size() > 12) throw invalid("$.realmProfiles", "At most 12 realms are supported.");
        if (request.has("realmCount")) {
            JsonElement count = request.get("realmCount");
            try {
                if (!count.isJsonPrimitive() || !count.getAsJsonPrimitive().isNumber()
                        || count.getAsBigDecimal().intValueExact() != profiles.size()) {
                    throw invalid("$.realmCount", "Expected an integer matching the submitted realmProfiles count.");
                }
            } catch (ArithmeticException exception) {
                throw invalid("$.realmCount", "Expected an integer matching the submitted realmProfiles count.");
            }
        }
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < profiles.size(); i++) {
            String path = "$.realmProfiles[" + i + "]";
            validate(profiles.get(i), schema(), path);
            JsonObject profile = profiles.get(i).getAsJsonObject();
            String id = profile.get("realmId").getAsString();
            if (!id.matches("[A-Za-z0-9._-]+") || !ids.add(id)) throw invalid(path + ".realmId", "Invalid or duplicate identity.");
            JsonObject scale = profile.getAsJsonObject("scalePlan");
            double min = scale.get("minAreaRatio").getAsDouble();
            double target = scale.get("targetAreaRatio").getAsDouble();
            double max = scale.get("maxAreaRatio").getAsDouble();
            if (min > target || target > max) throw invalid(path + ".scalePlan", "Require min <= target <= max.");
        }
        return profiles;
    }

    private static void validate(JsonElement value, JsonObject schema, String path) {
        if (value == null || value.isJsonNull()) throw invalid(path, "Required value missing.");
        switch (schema.get("type").getAsString()) {
            case "object" -> {
                if (!value.isJsonObject()) throw invalid(path, "Expected object.");
                JsonObject properties = schema.has("properties") ? schema.getAsJsonObject("properties") : null;
                boolean additionalPropertiesAllowed = false;
                JsonObject additionalPropertiesSchema = null;
                if (schema.has("additionalProperties")) {
                    JsonElement addProps = schema.get("additionalProperties");
                    if (addProps.isJsonObject()) {
                        additionalPropertiesAllowed = true;
                        additionalPropertiesSchema = addProps.getAsJsonObject();
                    } else if (addProps.isJsonPrimitive() && addProps.getAsJsonPrimitive().isBoolean()) {
                        additionalPropertiesAllowed = addProps.getAsBoolean();
                    }
                }
                for (String key : value.getAsJsonObject().keySet()) {
                    if (properties != null && properties.has(key)) continue;
                    if (additionalPropertiesSchema != null) {
                        validate(value.getAsJsonObject().get(key), additionalPropertiesSchema, path + "." + key);
                        continue;
                    }
                    if (!additionalPropertiesAllowed) {
                        throw invalid(path + "." + key, "Unknown field; use the tool schema's exact names.");
                    }
                }
                if (properties != null) {
                    Set<String> requiredSet = new HashSet<>();
                    if (schema.has("required") && schema.get("required").isJsonArray()) {
                        for (JsonElement req : schema.getAsJsonArray("required")) {
                            requiredSet.add(req.getAsString());
                        }
                    } else {
                        requiredSet.addAll(properties.keySet());
                    }
                    for (var field : properties.entrySet()) {
                        JsonElement propValue = value.getAsJsonObject().get(field.getKey());
                        if (propValue == null || propValue.isJsonNull()) {
                            if (requiredSet.contains(field.getKey())) {
                                throw invalid(path + "." + field.getKey(), "Required value missing.");
                            }
                            continue;
                        }
                        validate(propValue, field.getValue().getAsJsonObject(), path + "." + field.getKey());
                    }
                }
            }
            case "array" -> {
                if (!value.isJsonArray()) throw invalid(path, "Expected array; an explicit empty array is allowed.");
                for (int i = 0; i < value.getAsJsonArray().size(); i++) validate(value.getAsJsonArray().get(i),
                        schema.getAsJsonObject("items"), path + "[" + i + "]");
            }
            case "string" -> {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank()) {
                    throw invalid(path, "Expected non-empty string.");
                }
                if (schema.has("enum") && !schema.getAsJsonArray("enum").contains(value)) {
                    throw invalid(path, "Expected one of " + schema.get("enum") + ".");
                }
            }
            case "number" -> {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw invalid(path, "Expected number.");
                double n = value.getAsDouble();
                if (!Double.isFinite(n) || n < schema.get("minimum").getAsDouble() || n > schema.get("maximum").getAsDouble()) {
                    throw invalid(path, "Outside declared range; values are not silently clamped.");
                }
            }
            default -> throw new IllegalStateException("Unsupported schema node");
        }
    }

    private static IllegalArgumentException invalid(String path, String message) {
        return new IllegalArgumentException("REALM_PROFILE_INPUT_INVALID: " + path + ": " + message);
    }

    private static JsonObject type(String type) {
        JsonObject result = new JsonObject(); result.addProperty("type", type); return result;
    }
    private static JsonObject text() {
        JsonObject result = type("string"); result.addProperty("minLength", 1); return result;
    }
    private static JsonObject choice(String... values) {
        JsonObject result = text(); JsonArray choices = new JsonArray();
        for (String value : values) choices.add(value);
        result.add("enum", choices); return result;
    }
    private static JsonObject number(double min, double max) {
        JsonObject result = type("number"); result.addProperty("minimum", min); result.addProperty("maximum", max); return result;
    }
    private static JsonObject object(JsonObject properties, String... optionalFields) {
        JsonObject result = type("object"); result.add("properties", properties);
        result.addProperty("additionalProperties", false);
        Set<String> optional = Set.of(optionalFields);
        JsonArray required = new JsonArray();
        properties.keySet().stream().filter(k -> !optional.contains(k)).forEach(required::add);
        result.add("required", required);
        return result;
    }
}
