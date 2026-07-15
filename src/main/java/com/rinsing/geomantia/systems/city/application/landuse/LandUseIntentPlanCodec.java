package com.rinsing.geomantia.systems.city.application.landuse;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class LandUseIntentPlanCodec {
    private static final Set<String> TOP_FIELDS = Set.of(
            "schemaVersion", "cityId", "seedSalt", "groupOverrides", "subjectOverrides");
    private static final Set<String> GROUP_FIELDS = Set.of("groupId", "memberAnchorIds", "ruleRef");
    private static final Set<String> SUBJECT_FIELDS = Set.of("targetType", "targetId", "mode", "ruleRef");

    public LandUseIntentPlan parse(JsonObject source, String defaultCityId) {
        JsonObject obj = source == null ? defaultPlan(defaultCityId) : source;
        rejectUnknown(obj, TOP_FIELDS, "landUseIntentPlan");
        String schema = requiredString(obj, "schemaVersion");
        if (!LandUseIntentPlan.SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("LAND_USE_INTENT_SCHEMA_UNSUPPORTED: " + schema);
        }
        String cityId = requiredString(obj, "cityId");
        String seedSalt = optionalString(obj, "seedSalt", "");
        List<LandUseIntentPlan.GroupOverride> groups = parseGroups(optionalArray(obj, "groupOverrides"));
        List<LandUseIntentPlan.SubjectOverride> subjects = parseSubjects(optionalArray(obj, "subjectOverrides"));
        return new LandUseIntentPlan(cityId, seedSalt, groups, subjects);
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

    private static JsonObject defaultPlan(String cityId) {
        if (cityId == null || cityId.isBlank()) throw new IllegalArgumentException("cityId is required");
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", LandUseIntentPlan.SCHEMA);
        obj.addProperty("cityId", cityId);
        obj.add("groupOverrides", new JsonArray());
        obj.add("subjectOverrides", new JsonArray());
        return obj;
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

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported " + type.getSimpleName() + ": " + value, ex);
        }
    }
}
