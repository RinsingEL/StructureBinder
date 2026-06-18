package com.rinsing.geomantia.systems.city.domain.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record PatchGroupPlan(
        String schemaVersion,
        String cityId,
        List<Group> groups) {

    public static final String CURRENT_SCHEMA_VERSION = "city_patch_group_plan.v0.1";

    public PatchGroupPlan {
        if (schemaVersion == null || schemaVersion.isBlank()) {
            throw new IllegalArgumentException("schemaVersion is required");
        }
        if (cityId == null || cityId.isBlank()) {
            throw new IllegalArgumentException("cityId is required");
        }
        groups = List.copyOf(groups);
        if (groups.isEmpty()) {
            throw new IllegalArgumentException("groups is required");
        }
    }

    public record Group(
            String groupId,
            String groupName,
            String zoneName,
            String functionType,
            List<String> patchLabels,
            List<String> landformPatchRefs,
            String mainBuildingRole,
            List<String> supportingBuildingRoles,
            String groupReason,
            String adjacencyIntent,
            boolean splitRequested) {

        public Group {
            if (groupId == null || groupId.isBlank()) {
                throw new IllegalArgumentException("groupId is required");
            }
            if (zoneName == null || zoneName.isBlank()) {
                throw new IllegalArgumentException("zoneName is required");
            }
            if (functionType == null || functionType.isBlank()) {
                throw new IllegalArgumentException("functionType is required");
            }
            patchLabels = List.copyOf(Objects.requireNonNullElse(patchLabels, List.of()));
            landformPatchRefs = List.copyOf(Objects.requireNonNullElse(landformPatchRefs, List.of()));
            if (patchLabels.isEmpty() && landformPatchRefs.isEmpty()) {
                throw new IllegalArgumentException("patchLabels or landformPatchRefs is required");
            }
            supportingBuildingRoles = List.copyOf(Objects.requireNonNullElse(supportingBuildingRoles, List.of()));
            groupName = groupName == null ? "" : groupName;
            mainBuildingRole = mainBuildingRole == null ? "" : mainBuildingRole;
            groupReason = groupReason == null ? "" : groupReason;
            adjacencyIntent = adjacencyIntent == null ? "" : adjacencyIntent;
        }
    }

    public JsonObject asJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", schemaVersion);
        obj.addProperty("cityId", cityId);
        JsonArray groupArray = new JsonArray();
        for (Group group : groups) {
            JsonObject groupObj = new JsonObject();
            groupObj.addProperty("groupId", group.groupId());
            groupObj.addProperty("groupName", group.groupName());
            groupObj.addProperty("zoneName", group.zoneName());
            groupObj.addProperty("functionType", group.functionType());
            groupObj.add("patchLabels", stringArray(group.patchLabels()));
            groupObj.add("landformPatchRefs", stringArray(group.landformPatchRefs()));
            groupObj.addProperty("mainBuildingRole", group.mainBuildingRole());
            groupObj.add("supportingBuildingRoles", stringArray(group.supportingBuildingRoles()));
            groupObj.addProperty("groupReason", group.groupReason());
            groupObj.addProperty("adjacencyIntent", group.adjacencyIntent());
            groupObj.addProperty("splitRequested", group.splitRequested());
            groupArray.add(groupObj);
        }
        obj.add("groups", groupArray);
        return obj;
    }

    public static PatchGroupPlan fromJson(JsonObject obj) {
        if (obj == null) {
            throw new IllegalArgumentException("patchGroupPlan is required");
        }
        JsonArray groupArray = requiredArray(obj, "groups");
        List<Group> groups = new ArrayList<>();
        for (JsonElement elem : groupArray) {
            JsonObject groupObj = elem.getAsJsonObject();
            groups.add(new Group(
                    requiredString(groupObj, "groupId"),
                    stringValue(groupObj, "groupName", ""),
                    requiredString(groupObj, "zoneName"),
                    requiredString(groupObj, "functionType"),
                    strings(optionalArray(groupObj, "patchLabels")),
                    strings(optionalArray(groupObj, "landformPatchRefs")),
                    stringValue(groupObj, "mainBuildingRole", ""),
                    strings(optionalArray(groupObj, "supportingBuildingRoles")),
                    stringValue(groupObj, "groupReason", ""),
                    stringValue(groupObj, "adjacencyIntent", ""),
                    booleanValue(groupObj, "splitRequested", false)));
        }
        return new PatchGroupPlan(
                stringValue(obj, "schemaVersion", CURRENT_SCHEMA_VERSION),
                requiredString(obj, "cityId"),
                groups);
    }

    private static JsonArray stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static List<String> strings(JsonArray array) {
        List<String> result = new ArrayList<>();
        for (JsonElement elem : array) {
            if (!elem.isJsonNull()) {
                result.add(elem.getAsString());
            }
        }
        return result;
    }

    private static JsonArray requiredArray(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) {
            throw new IllegalArgumentException(key + " array is required");
        }
        return obj.getAsJsonArray(key);
    }

    private static JsonArray optionalArray(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) {
            return new JsonArray();
        }
        return obj.getAsJsonArray(key);
    }

    private static String requiredString(JsonObject obj, String key) {
        String value = stringValue(obj, key, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private static String stringValue(JsonObject obj, String key, String defaultValue) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(key).getAsString();
    }

    private static boolean booleanValue(JsonObject obj, String key, boolean defaultValue) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(key).getAsBoolean();
    }
}
