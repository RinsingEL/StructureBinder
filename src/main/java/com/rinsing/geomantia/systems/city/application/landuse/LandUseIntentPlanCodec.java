package com.rinsing.geomantia.systems.city.application.landuse;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSurfaceSettings;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class LandUseIntentPlanCodec {
    private static final Set<String> TOP_FIELDS = Set.of(
            "schema", "cityId", "seedSalt", "groupOverrides", "subjectOverrides",
            "surfaceAlgorithmDefaults", "surfaceOverrides");
    private static final Set<String> GROUP_FIELDS = Set.of("groupId", "memberAnchorIds", "ruleRef");
    private static final Set<String> SUBJECT_FIELDS = Set.of("targetType", "targetId", "mode", "ruleRef");
    private static final Set<String> SURFACE_ALGORITHM_DEFAULT_FIELDS = Set.of(
            "surfaceAlgorithm", "surfaceBlockId", "cropBlockId", "channelBankBlockId",
            "channelWaterBlockId", "channelBankOverlayBlockId");
    private static final Set<String> SURFACE_FIELDS = Set.of(
            "targetGroupId", "surfacePrintEnabled", "autoConnect", "surfaceAlgorithm", "surfaceBlockId",
            "cropBlockId", "channelBankBlockId", "channelWaterBlockId", "channelBankOverlayBlockId",
            "algorithmAnchor");
    private static final Set<String> POINT_FIELDS = Set.of("x", "z");

    public LandUseIntentPlan parse(JsonObject source, String defaultCityId) {
        JsonObject obj = source == null ? defaultPlan(defaultCityId) : source;
        rejectUnknown(obj, TOP_FIELDS, "landUseIntentPlan");
        String schema = requiredString(obj, "schema");
        if (!LandUseIntentPlan.SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("LAND_USE_INTENT_SCHEMA_UNSUPPORTED: " + schema);
        }
        String cityId = requiredString(obj, "cityId");
        String seedSalt = optionalString(obj, "seedSalt", "");
        List<LandUseIntentPlan.GroupOverride> groups = parseGroups(optionalArray(obj, "groupOverrides"));
        List<LandUseIntentPlan.SubjectOverride> subjects = parseSubjects(optionalArray(obj, "subjectOverrides"));
        List<LandUseIntentPlan.SurfaceAlgorithmDefault> defaults = parseSurfaceAlgorithmDefaults(
                optionalArray(obj, "surfaceAlgorithmDefaults"));
        List<LandUseIntentPlan.SurfaceOverride> surfaces = parseSurfaces(optionalArray(obj, "surfaceOverrides"));
        return new LandUseIntentPlan(cityId, seedSalt, groups, subjects, defaults, surfaces);
    }

    private List<LandUseIntentPlan.GroupOverride> parseGroups(JsonArray array) {
        List<LandUseIntentPlan.GroupOverride> groups = new ArrayList<>();
        Set<String> groupIds = new HashSet<>();
        Set<String> memberIds = new HashSet<>();
        for (int i = 0; i < array.size(); i++) {
            JsonObject value = objectAt(array, i, "groupOverrides");
            rejectUnknown(value, GROUP_FIELDS, "groupOverrides[" + i + "]");
            String groupId = requiredString(value, "groupId");
            if (!groupIds.add(groupId)) throw new IllegalArgumentException("LAND_USE_GROUP_OVERRIDE_DUPLICATE: " + groupId);
            List<String> members = strings(requiredArray(value, "memberAnchorIds"), "memberAnchorIds");
            if (members.isEmpty()) throw new IllegalArgumentException("LAND_USE_GROUP_OVERRIDE_MEMBERS_EMPTY: " + groupId);
            for (String member : members) {
                if (!memberIds.add(member)) {
                    throw new IllegalArgumentException("LAND_USE_GROUP_OVERRIDE_MEMBER_DUPLICATE: " + member);
                }
            }
            groups.add(new LandUseIntentPlan.GroupOverride(groupId, members, optionalString(value, "ruleRef", "")));
        }
        return groups;
    }

    private List<LandUseIntentPlan.SubjectOverride> parseSubjects(JsonArray array) {
        List<LandUseIntentPlan.SubjectOverride> subjects = new ArrayList<>();
        Set<String> targets = new HashSet<>();
        for (int i = 0; i < array.size(); i++) {
            JsonObject value = objectAt(array, i, "subjectOverrides");
            rejectUnknown(value, SUBJECT_FIELDS, "subjectOverrides[" + i + "]");
            LandUseIntentPlan.TargetType targetType = enumValue(LandUseIntentPlan.TargetType.class,
                    requiredString(value, "targetType"));
            String targetId = requiredString(value, "targetId");
            if (!targets.add(targetType + ":" + targetId)) {
                throw new IllegalArgumentException("LAND_USE_SUBJECT_OVERRIDE_DUPLICATE: " + targetType + ":" + targetId);
            }
            LandUseIntentPlan.Mode mode = enumValue(LandUseIntentPlan.Mode.class, requiredString(value, "mode"));
            boolean hasRule = value.has("ruleRef") && !value.get("ruleRef").isJsonNull();
            String ruleRef = hasRule ? requiredString(value, "ruleRef") : "";
            if (mode == LandUseIntentPlan.Mode.SET_RULE && !hasRule) {
                throw new IllegalArgumentException("LAND_USE_SET_RULE_REQUIRES_RULE_REF: " + targetId);
            }
            if (mode == LandUseIntentPlan.Mode.EXCLUDE && hasRule) {
                throw new IllegalArgumentException("LAND_USE_EXCLUDE_FORBIDS_RULE_REF: " + targetId);
            }
            subjects.add(new LandUseIntentPlan.SubjectOverride(targetType, targetId, mode, ruleRef));
        }
        return subjects;
    }

    private List<LandUseIntentPlan.SurfaceOverride> parseSurfaces(JsonArray array) {
        List<LandUseIntentPlan.SurfaceOverride> overrides = new ArrayList<>();
        Set<String> targets = new HashSet<>();
        for (int i = 0; i < array.size(); i++) {
            JsonObject value = objectAt(array, i, "surfaceOverrides");
            rejectRemovedDirectionFields(value, "surfaceOverrides[" + i + "]");
            rejectUnknown(value, SURFACE_FIELDS, "surfaceOverrides[" + i + "]");
            String targetGroupId = requiredString(value, "targetGroupId");
            if (!targets.add(targetGroupId)) {
                throw new IllegalArgumentException("LAND_USE_SURFACE_OVERRIDE_DUPLICATE: " + targetGroupId);
            }
            String surfaceBlockId = optionalBlockId(value, "surfaceBlockId");
            String cropBlockId = optionalBlockId(value, "cropBlockId");
            LandUseSurfaceSettings.SurfaceAlgorithm algorithm = value.has("surfaceAlgorithm")
                    ? enumValue(LandUseSurfaceSettings.SurfaceAlgorithm.class,
                    requiredString(value, "surfaceAlgorithm")) : null;
            overrides.add(new LandUseIntentPlan.SurfaceOverride(targetGroupId,
                    optionalBoolean(value, "surfacePrintEnabled"), optionalBoolean(value, "autoConnect"),
                    algorithm, surfaceBlockId, cropBlockId,
                    optionalBlockId(value, "channelBankBlockId"),
                    optionalBlockId(value, "channelWaterBlockId"),
                    optionalBlockId(value, "channelBankOverlayBlockId"),
                    optionalPoint(value, "algorithmAnchor")));
        }
        return overrides;
    }

    private List<LandUseIntentPlan.SurfaceAlgorithmDefault> parseSurfaceAlgorithmDefaults(JsonArray array) {
        List<LandUseIntentPlan.SurfaceAlgorithmDefault> defaults = new ArrayList<>();
        Set<LandUseSurfaceSettings.SurfaceAlgorithm> algorithms = new HashSet<>();
        for (int i = 0; i < array.size(); i++) {
            JsonObject value = objectAt(array, i, "surfaceAlgorithmDefaults");
            rejectUnknown(value, SURFACE_ALGORITHM_DEFAULT_FIELDS, "surfaceAlgorithmDefaults[" + i + "]");
            LandUseSurfaceSettings.SurfaceAlgorithm algorithm = enumValue(
                    LandUseSurfaceSettings.SurfaceAlgorithm.class, requiredString(value, "surfaceAlgorithm"));
            if (!algorithms.add(algorithm)) {
                throw new IllegalArgumentException("LAND_USE_SURFACE_ALGORITHM_DEFAULT_DUPLICATE:"
                        + algorithm.name().toLowerCase(Locale.ROOT));
            }
            defaults.add(new LandUseIntentPlan.SurfaceAlgorithmDefault(algorithm,
                    requiredBlockId(value, "surfaceBlockId"), optionalBlockId(value, "cropBlockId"),
                    optionalBlockId(value, "channelBankBlockId"),
                    optionalBlockId(value, "channelWaterBlockId"),
                    optionalBlockId(value, "channelBankOverlayBlockId")));
        }
        return defaults;
    }

    private static JsonObject defaultPlan(String cityId) {
        if (cityId == null || cityId.isBlank()) throw new IllegalArgumentException("cityId is required");
        JsonObject obj = new JsonObject();
        obj.addProperty("schema", LandUseIntentPlan.SCHEMA);
        obj.addProperty("cityId", cityId);
        obj.add("groupOverrides", new JsonArray());
        obj.add("subjectOverrides", new JsonArray());
        obj.add("surfaceAlgorithmDefaults", new JsonArray());
        obj.add("surfaceOverrides", new JsonArray());
        return obj;
    }

    private static void rejectRemovedDirectionFields(JsonObject value, String owner) {
        if (value.has("directionMode") || value.has("directionCenter")) {
            throw new IllegalArgumentException("LAND_USE_SURFACE_RADIAL_DIRECTION_REMOVED: " + owner);
        }
    }

    private static void rejectUnknown(JsonObject obj, Set<String> allowed, String owner) {
        for (String key : obj.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("LAND_USE_INTENT_UNKNOWN_FIELD: " + owner + "." + key);
            }
        }
    }

    private static JsonObject objectAt(JsonArray array, int index, String owner) {
        if (!array.get(index).isJsonObject()) {
            throw new IllegalArgumentException(owner + "[" + index + "] must be an object");
        }
        return array.get(index).getAsJsonObject();
    }

    private static List<String> strings(JsonArray array, String owner) {
        List<String> values = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (JsonElement element : array) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
                    || element.getAsString().isBlank() || !unique.add(element.getAsString())) {
                throw new IllegalArgumentException(owner + " must contain unique non-blank strings");
            }
            values.add(element.getAsString());
        }
        return values;
    }

    private static JsonArray requiredArray(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) throw new IllegalArgumentException(key + " array is required");
        return obj.getAsJsonArray(key);
    }

    private static JsonArray optionalArray(JsonObject obj, String key) {
        if (!obj.has(key)) return new JsonArray();
        return requiredArray(obj, key);
    }

    private static String requiredString(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive() || !obj.getAsJsonPrimitive(key).isString()
                || obj.get(key).getAsString().isBlank()) {
            throw new IllegalArgumentException(key + " string is required");
        }
        return obj.get(key).getAsString();
    }

    private static String optionalString(JsonObject obj, String key, String fallback) {
        return obj.has(key) ? requiredString(obj, key) : fallback;
    }

    private static Boolean optionalBoolean(JsonObject obj, String key) {
        if (!obj.has(key)) return null;
        if (!obj.get(key).isJsonPrimitive() || !obj.getAsJsonPrimitive(key).isBoolean()) {
            throw new IllegalArgumentException(key + " boolean is required");
        }
        return obj.get(key).getAsBoolean();
    }

    private static String optionalBlockId(JsonObject obj, String key) {
        if (!obj.has(key)) return null;
        String value = requiredString(obj, key);
        if (!com.rinsing.geomantia.systems.city.domain.landuse.LandUseSurfaceSettings.isValidBlockId(value)) {
            throw new IllegalArgumentException("LAND_USE_SURFACE_BLOCK_ID_INVALID:" + key + ':' + value);
        }
        return value;
    }

    private static String requiredBlockId(JsonObject obj, String key) {
        String value = requiredString(obj, key);
        if (!LandUseSurfaceSettings.isValidBlockId(value)) {
            throw new IllegalArgumentException("LAND_USE_SURFACE_BLOCK_ID_INVALID:" + key + ':' + value);
        }
        return value;
    }

    private static BlockPoint optionalPoint(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        if (!obj.get(key).isJsonObject()) {
            throw new IllegalArgumentException(key + " object is required");
        }
        JsonObject value = obj.getAsJsonObject(key);
        rejectUnknown(value, POINT_FIELDS, key);
        return new BlockPoint(requiredInt(value, "x"), requiredInt(value, "z"));
    }

    private static int requiredInt(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive()
                || !obj.getAsJsonPrimitive(key).isNumber()) {
            throw new IllegalArgumentException(key + " integer is required");
        }
        try {
            return obj.get(key).getAsBigDecimal().intValueExact();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(key + " integer is required", ex);
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported " + type.getSimpleName() + ": " + value, ex);
        }
    }
}
