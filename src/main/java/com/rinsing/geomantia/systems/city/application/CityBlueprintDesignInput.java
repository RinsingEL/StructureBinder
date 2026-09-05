package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import java.util.List;

/** Input convenience only: the canonical codec and author constraints remain authoritative. */
final class CityBlueprintDesignInput {
    private CityBlueprintDesignInput() { }

    static JsonObject bind(JsonObject input, JsonObject context, boolean relativeWeights) {
        JsonObject result = input.deepCopy();
        if (!result.has("schema")) result.addProperty("schema", "city_blueprint");
        for (String key : List.of("cityId", "sourceD3Ref", "catalogSnapshotRef")) {
            if (!result.has(key)) result.add(key, context.get(key).deepCopy());
        }
        if (!result.has("generationSeed")) result.add("generationSeed", context.get("generationSeedSuggestion").deepCopy());
        if (result.has("relations")) for (JsonElement element : result.getAsJsonArray("relations")) {
            JsonObject relation = element.getAsJsonObject();
            String kind = relation.has("relationKind") ? relation.get("relationKind").getAsString() : "";
            if (!"DISTANCE".equals(kind) && !relation.has("distancePreference")) relation.addProperty("distancePreference", "NONE");
            if (!"DIRECTION".equals(kind) && !relation.has("directionPreference")) relation.addProperty("directionPreference", "NONE");
        }
        if (relativeWeights) {
            normalizeArray(result.getAsJsonArray("groups"), "targetAreaShare");
            for (JsonElement element : result.getAsJsonArray("groups")) {
                JsonObject composition = element.getAsJsonObject().getAsJsonObject("spaceComposition");
                normalizeFields(composition, List.of("buildingShare", "landscapeShare", "openSpaceShare"));
            }
            JsonObject outdoor = result.getAsJsonObject("outdoorPlan");
            if (outdoor != null && outdoor.has("landscapes")) for (JsonElement element : outdoor.getAsJsonArray("landscapes")) {
                JsonObject fill = element.getAsJsonObject().getAsJsonObject("fillSelection");
                if (fill != null && fill.has("variants")) for (JsonElement variant : fill.getAsJsonArray("variants"))
                    normalizeArray(variant.getAsJsonObject().getAsJsonArray("roleShares"), "targetShare");
            }
        }
        return result;
    }

    private static double weight(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("CITY_BLUEPRINT_WEIGHT_INVALID: expected a finite nonnegative number");
        double result = value.getAsDouble();
        if (!Double.isFinite(result) || result < 0) throw new IllegalArgumentException("CITY_BLUEPRINT_WEIGHT_INVALID");
        return result;
    }

    private static void normalizeArray(JsonArray values, String key) {
        double max = 0;
        for (JsonElement value : values) max = Math.max(max, weight(value.getAsJsonObject().get(key)));
        if (max == 0) throw new IllegalArgumentException("CITY_BLUEPRINT_WEIGHT_TOTAL_ZERO");
        double total = 0;
        for (JsonElement value : values) total += weight(value.getAsJsonObject().get(key)) / max;
        for (JsonElement value : values) {
            JsonObject obj = value.getAsJsonObject();
            obj.addProperty(key, weight(obj.get(key)) / max / total);
        }
    }

    private static void normalizeFields(JsonObject obj, List<String> keys) {
        JsonArray values = new JsonArray();
        for (String key : keys) { JsonObject item = new JsonObject(); item.add("weight", obj.get(key)); values.add(item); }
        normalizeArray(values, "weight");
        for (int i = 0; i < keys.size(); i++) obj.add(keys.get(i), values.get(i).getAsJsonObject().get("weight"));
    }

    /** Deliberately only RFC 6902 replace operations; additions/removals use a full revision. */
    static JsonObject revise(JsonObject original, JsonArray patch) {
        if (patch.isEmpty() || patch.size() > 128) throw new IllegalArgumentException("CITY_BLUEPRINT_PATCH_SIZE_INVALID");
        JsonObject result = original.deepCopy();
        for (JsonElement entry : patch) {
            JsonObject operation = entry.getAsJsonObject();
            if (operation.size() != 3 || !operation.has("value") || !operation.has("op")
                    || !"replace".equals(operation.get("op").getAsString()) || !operation.has("path"))
                throw new IllegalArgumentException("CITY_BLUEPRINT_PATCH_REPLACE_REQUIRED");
            String path = operation.get("path").getAsString();
            if (!path.startsWith("/") || path.length() < 2) throw new IllegalArgumentException("CITY_BLUEPRINT_PATCH_PATH_INVALID");
            String[] parts = path.substring(1).split("/", -1);
            if (List.of("schema", "cityId", "sourceD3Ref", "catalogSnapshotRef", "generationSeed").contains(parts[0]))
                throw new IllegalArgumentException("CITY_BLUEPRINT_PATCH_IDENTITY_LOCKED");
            JsonElement parent = result;
            for (int i = 0; i < parts.length; i++) {
                String key = parts[i];
                if (key.matches(".*~(?![01]).*")) throw new IllegalArgumentException("CITY_BLUEPRINT_PATCH_PATH_INVALID");
                key = key.replace("~1", "/").replace("~0", "~");
                boolean last = i == parts.length - 1;
                if (parent.isJsonObject()) {
                    JsonObject obj = parent.getAsJsonObject();
                    if (!obj.has(key)) throw new IllegalArgumentException("CITY_BLUEPRINT_PATCH_PATH_NOT_FOUND");
                    if (last) obj.add(key, operation.get("value").deepCopy()); else parent = obj.get(key);
                } else if (parent.isJsonArray()) {
                    if (!key.matches("0|[1-9][0-9]*")) throw new IllegalArgumentException("CITY_BLUEPRINT_PATCH_INDEX_INVALID");
                    int index = Integer.parseInt(key);
                    JsonArray array = parent.getAsJsonArray();
                    if (index >= array.size()) throw new IllegalArgumentException("CITY_BLUEPRINT_PATCH_PATH_NOT_FOUND");
                    if (last) array.set(index, operation.get("value").deepCopy()); else parent = array.get(index);
                } else throw new IllegalArgumentException("CITY_BLUEPRINT_PATCH_PATH_NOT_FOUND");
            }
        }
        return result;
    }
}
