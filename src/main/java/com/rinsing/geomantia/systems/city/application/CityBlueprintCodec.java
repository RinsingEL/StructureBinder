package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprintContractException;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprintReasonCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class CityBlueprintCodec {
    public static final long MAX_SAFE_GENERATION_SEED = 9_007_199_254_740_991L;
    private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "cityId", "sourceD3Ref",
            "catalogSnapshotRef", "generationSeed", "designIntent", "styleProfile", "groups", "relations",
            "roadProfile", "surfaceDetailProfile", "outdoorPlan");
    private static final Set<String> FORBIDDEN_FIELDS = Set.of("x", "y", "z", "blockX", "blockY", "blockZ",
            "worldX", "worldY", "worldZ", "anchor", "anchorBlock", "rotation", "mirror", "candidateId",
            "algorithm", "algorithmName", "templateId", "templateRef", "nbtFile");

    public CityBlueprint read(JsonObject root) {
        rejectForbidden(root, "$");
        exactFields(root, ROOT_FIELDS, "$");
        String schema = requiredString(root, "schemaVersion", "$.schemaVersion");
        if (!CityBlueprint.SCHEMA_VERSION.equals(schema)) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_SCHEMA_UNSUPPORTED, "$.schemaVersion",
                    "Unsupported CityBlueprint schemaVersion: " + schema);
        }
        return new CityBlueprint(
                schema,
                requiredString(root, "cityId", "$.cityId"),
                artifactRef(requiredObject(root, "sourceD3Ref", "$.sourceD3Ref"), "$.sourceD3Ref"),
                artifactRef(requiredObject(root, "catalogSnapshotRef", "$.catalogSnapshotRef"),
                        "$.catalogSnapshotRef"),
                requiredLong(root, "generationSeed", "$.generationSeed"),
                designIntent(requiredObject(root, "designIntent", "$.designIntent")),
                profileRef(requiredObject(root, "styleProfile", "$.styleProfile"), "$.styleProfile"),
                groups(requiredArray(root, "groups", "$.groups")),
                relations(requiredArray(root, "relations", "$.relations")),
                profileRef(requiredObject(root, "roadProfile", "$.roadProfile"), "$.roadProfile"),
                profileRef(requiredObject(root, "surfaceDetailProfile", "$.surfaceDetailProfile"),
                        "$.surfaceDetailProfile"),
                outdoorPlan(requiredObject(root, "outdoorPlan", "$.outdoorPlan")));
    }

    public JsonObject write(CityBlueprint blueprint) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", blueprint.schemaVersion());
        root.addProperty("cityId", blueprint.cityId());
        root.add("sourceD3Ref", artifactRefJson(blueprint.sourceD3Ref()));
        root.add("catalogSnapshotRef", artifactRefJson(blueprint.catalogSnapshotRef()));
        root.addProperty("generationSeed", blueprint.generationSeed());
        JsonObject intent = new JsonObject();
        intent.addProperty("cityIdentity", blueprint.designIntent().cityIdentity());
        intent.addProperty("theme", blueprint.designIntent().theme());
        intent.add("functionalRoles", strings(blueprint.designIntent().functionalRoles()));
        root.add("designIntent", intent);
        root.add("styleProfile", profileRefJson(blueprint.styleProfile()));
        JsonArray groups = new JsonArray();
        for (CityBlueprint.Group group : blueprint.groups()) {
            JsonObject item = new JsonObject();
            item.addProperty("groupId", group.groupId());
            item.addProperty("groupKind", group.groupKind().name());
            item.add("preferredPatchRefs", strings(group.preferredPatchRefs()));
            item.addProperty("preferredPatchZone", group.preferredPatchZone().name());
            item.addProperty("role", group.role());
            item.addProperty("priority", group.priority().name());
            item.addProperty("extentClass", group.extentClass().name());
            item.addProperty("densityClass", group.densityClass().name());
            item.addProperty("algorithmProfileRef", group.algorithmProfileRef());
            item.addProperty("terrainPolicy", group.terrainPolicy().name());
            item.add("requiredStructureRefs", strings(group.requiredStructureRefs()));
            item.addProperty("fillPoolRef", group.fillPoolRef());
            if (group.connectionPlan() != null) {
                item.add("connectionPlan", connectionPlanJson(group.connectionPlan()));
            }
            item.addProperty("compositionProfileRef", group.compositionProfileRef());
            item.add("attachedFeatures", strings(group.attachedFeatures()));
            groups.add(item);
        }
        root.add("groups", groups);
        JsonArray relations = new JsonArray();
        for (CityBlueprint.Relation relation : blueprint.relations()) {
            JsonObject item = new JsonObject();
            item.addProperty("fromGroupId", relation.fromGroupId());
            item.addProperty("toGroupId", relation.toGroupId());
            item.addProperty("relationKind", relation.relationKind().name());
            item.addProperty("strength", relation.strength().name());
            item.addProperty("distancePreference", relation.distancePreference().name());
            item.addProperty("directionPreference", relation.directionPreference().name());
            relations.add(item);
        }
        root.add("relations", relations);
        root.add("roadProfile", profileRefJson(blueprint.roadProfile()));
        root.add("surfaceDetailProfile", profileRefJson(blueprint.surfaceDetailProfile()));
        root.add("outdoorPlan", outdoorPlanJson(blueprint.outdoorPlan()));
        return root;
    }

    private static CityBlueprint.OutdoorPlan outdoorPlan(JsonObject object) {
        String path = "$.outdoorPlan";
        exactFields(object, Set.of("mode", "envelopeProfile", "spatialGrounds", "landscapes"), path);
        return new CityBlueprint.OutdoorPlan(
                enumValue(object, "mode", CityBlueprint.OutdoorMode.class, path),
                enumValue(object, "envelopeProfile", CityBlueprint.EnvelopeProfile.class, path),
                spatialGrounds(requiredArray(object, "spatialGrounds", path + ".spatialGrounds")),
                landscapes(requiredArray(object, "landscapes", path + ".landscapes")));
    }

    private static List<CityBlueprint.SpatialGround> spatialGrounds(JsonArray array) {
        List<CityBlueprint.SpatialGround> result = new ArrayList<>();
        Set<String> fields = Set.of("sourceGroupId", "landUseRuleRef", "surfaceRecipeRef",
                "sharedSpaceType", "hierarchyLevel", "membership");
        for (int index = 0; index < array.size(); index++) {
            String path = "$.outdoorPlan.spatialGrounds[" + index + "]";
            JsonObject item = objectElement(array.get(index), path);
            exactFields(item, fields, path);
            result.add(new CityBlueprint.SpatialGround(
                    requiredString(item, "sourceGroupId", path + ".sourceGroupId"),
                    requiredString(item, "landUseRuleRef", path + ".landUseRuleRef"),
                    requiredString(item, "surfaceRecipeRef", path + ".surfaceRecipeRef"),
                    enumValue(item, "sharedSpaceType", CityBlueprint.SharedSpaceType.class, path),
                    enumValue(item, "hierarchyLevel", CityBlueprint.SpatialHierarchy.class, path),
                    enumValue(item, "membership", CityBlueprint.OutdoorMembership.class, path)));
        }
        return List.copyOf(result);
    }

    private static List<CityBlueprint.Landscape> landscapes(JsonArray array) {
        List<CityBlueprint.Landscape> result = new ArrayList<>();
        Set<String> fields = Set.of("landscapeId", "landscapeProfileRef", "attachedGroupIds",
                "preferredPatchRefs", "extentClass", "intensity", "continuity", "growthRelation",
                "referenceGroupIds", "terrainPolicy", "required");
        for (int index = 0; index < array.size(); index++) {
            String path = "$.outdoorPlan.landscapes[" + index + "]";
            JsonObject item = objectElement(array.get(index), path);
            exactFields(item, fields, path);
            result.add(new CityBlueprint.Landscape(
                    requiredString(item, "landscapeId", path + ".landscapeId"),
                    requiredString(item, "landscapeProfileRef", path + ".landscapeProfileRef"),
                    stringList(requiredArray(item, "attachedGroupIds", path + ".attachedGroupIds"),
                            path + ".attachedGroupIds"),
                    stringList(requiredArray(item, "preferredPatchRefs", path + ".preferredPatchRefs"),
                            path + ".preferredPatchRefs"),
                    enumValue(item, "extentClass", CityBlueprint.ExtentClass.class, path),
                    enumValue(item, "intensity", CityBlueprint.OutdoorIntensity.class, path),
                    enumValue(item, "continuity", CityBlueprint.LandscapeContinuity.class, path),
                    enumValue(item, "growthRelation", CityBlueprint.LandscapeGrowthRelation.class, path),
                    stringList(requiredArray(item, "referenceGroupIds", path + ".referenceGroupIds"),
                            path + ".referenceGroupIds"),
                    enumValue(item, "terrainPolicy", CityBlueprint.TerrainPolicy.class, path),
                    requiredBoolean(item, "required", path + ".required")));
        }
        return List.copyOf(result);
    }

    private static CityBlueprint.ArtifactRef artifactRef(JsonObject object, String path) {
        exactFields(object, Set.of("path", "schemaVersion", "contentHash"), path);
        String hash = requiredString(object, "contentHash", path + ".contentHash");
        if (!hash.matches("sha256:[0-9a-f]{64}")) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_FIELD_MISSING, path + ".contentHash",
                    "contentHash must use sha256:<64 lowercase hex>.");
        }
        return new CityBlueprint.ArtifactRef(requiredString(object, "path", path + ".path"),
                requiredString(object, "schemaVersion", path + ".schemaVersion"), hash);
    }

    private static CityBlueprint.DesignIntent designIntent(JsonObject object) {
        exactFields(object, Set.of("cityIdentity", "theme", "functionalRoles"), "$.designIntent");
        List<String> roles = stringList(requiredArray(object, "functionalRoles", "$.designIntent.functionalRoles"),
                "$.designIntent.functionalRoles");
        if (roles.isEmpty()) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_FIELD_MISSING, "$.designIntent.functionalRoles",
                    "functionalRoles must not be empty.");
        }
        return new CityBlueprint.DesignIntent(requiredString(object, "cityIdentity", "$.designIntent.cityIdentity"),
                requiredString(object, "theme", "$.designIntent.theme"), roles);
    }

    private static CityBlueprint.ProfileRef profileRef(JsonObject object, String path) {
        exactFields(object, Set.of("profileRef"), path);
        return new CityBlueprint.ProfileRef(requiredString(object, "profileRef", path + ".profileRef"));
    }

    private static List<CityBlueprint.Group> groups(JsonArray array) {
        List<CityBlueprint.Group> result = new ArrayList<>();
        Set<String> fields = Set.of("groupId", "groupKind", "preferredPatchRefs", "preferredPatchZone",
                "role", "priority",
                "extentClass", "densityClass",
                "algorithmProfileRef", "terrainPolicy", "requiredStructureRefs", "fillPoolRef",
                "connectionPlan", "compositionProfileRef", "attachedFeatures");
        for (int index = 0; index < array.size(); index++) {
            String path = "$.groups[" + index + "]";
            JsonObject item = objectElement(array.get(index), path);
            exactFields(item, fields, Set.of("connectionPlan"), path);
            result.add(new CityBlueprint.Group(
                    requiredString(item, "groupId", path + ".groupId"),
                    enumValue(item, "groupKind", CityBlueprint.GroupKind.class, path),
                    nonEmptyStringList(item, "preferredPatchRefs", path + ".preferredPatchRefs"),
                    enumValue(item, "preferredPatchZone", CityBlueprint.PreferredPatchZone.class, path),
                    requiredString(item, "role", path + ".role"),
                    enumValue(item, "priority", CityBlueprint.GroupPriority.class, path),
                    enumValue(item, "extentClass", CityBlueprint.ExtentClass.class, path),
                    enumValue(item, "densityClass", CityBlueprint.DensityClass.class, path),
                    requiredString(item, "algorithmProfileRef", path + ".algorithmProfileRef"),
                    enumValue(item, "terrainPolicy", CityBlueprint.TerrainPolicy.class, path),
                    stringList(requiredArray(item, "requiredStructureRefs", path + ".requiredStructureRefs"),
                            path + ".requiredStructureRefs"),
                    requiredString(item, "fillPoolRef", path + ".fillPoolRef"),
                    item.has("connectionPlan")
                            ? connectionPlan(requiredObject(item, "connectionPlan", path + ".connectionPlan"),
                            path + ".connectionPlan") : null,
                    requiredString(item, "compositionProfileRef", path + ".compositionProfileRef"),
                    stringList(requiredArray(item, "attachedFeatures", path + ".attachedFeatures"),
                            path + ".attachedFeatures")));
        }
        return List.copyOf(result);
    }

    private static CityBlueprint.ConnectionPlan connectionPlan(JsonObject object, String path) {
        Set<String> fields = Set.of("structurePoolRef", "algorithmProfileRef", "densityClass", "parameters");
        exactFields(object, fields, fields, path);
        String pool = optionalString(object, "structurePoolRef", path + ".structurePoolRef");
        String algorithm = optionalString(object, "algorithmProfileRef", path + ".algorithmProfileRef");
        CityBlueprint.DensityClass density = optionalEnum(object, "densityClass",
                CityBlueprint.DensityClass.class, path);
        CityBlueprint.ConnectionParameters parameters = object.has("parameters")
                ? connectionParameters(requiredObject(object, "parameters", path + ".parameters"),
                path + ".parameters") : CityBlueprint.ConnectionParameters.empty();
        return new CityBlueprint.ConnectionPlan(pool, algorithm, density, parameters);
    }

    private static CityBlueprint.ConnectionParameters connectionParameters(JsonObject object, String path) {
        Set<String> fields = Set.of("clusterShape", "sideMode", "stagger", "widthClass");
        exactFields(object, fields, fields, path);
        return new CityBlueprint.ConnectionParameters(
                optionalEnum(object, "clusterShape", CityBlueprint.ClusterShape.class, path),
                optionalEnum(object, "sideMode", CityBlueprint.SideMode.class, path),
                optionalBoolean(object, "stagger", path + ".stagger"),
                optionalEnum(object, "widthClass", CityBlueprint.WidthClass.class, path));
    }

    private static List<CityBlueprint.Relation> relations(JsonArray array) {
        List<CityBlueprint.Relation> result = new ArrayList<>();
        Set<String> fields = Set.of("fromGroupId", "toGroupId", "relationKind", "strength",
                "distancePreference", "directionPreference");
        for (int index = 0; index < array.size(); index++) {
            String path = "$.relations[" + index + "]";
            JsonObject item = objectElement(array.get(index), path);
            exactFields(item, fields, path);
            result.add(new CityBlueprint.Relation(
                    requiredString(item, "fromGroupId", path + ".fromGroupId"),
                    requiredString(item, "toGroupId", path + ".toGroupId"),
                    enumValue(item, "relationKind", CityBlueprint.RelationKind.class, path),
                    enumValue(item, "strength", CityBlueprint.RelationStrength.class, path),
                    enumValue(item, "distancePreference", CityBlueprint.DistancePreference.class, path),
                    enumValue(item, "directionPreference", CityBlueprint.DirectionPreference.class, path)));
        }
        return List.copyOf(result);
    }

    private static void rejectForbidden(JsonElement value, String path) {
        if (value == null || value.isJsonNull() || value.isJsonPrimitive()) return;
        if (value.isJsonArray()) {
            for (int i = 0; i < value.getAsJsonArray().size(); i++) {
                rejectForbidden(value.getAsJsonArray().get(i), path + "[" + i + "]");
            }
            return;
        }
        for (var entry : value.getAsJsonObject().entrySet()) {
            if (FORBIDDEN_FIELDS.contains(entry.getKey())) {
                fail(CityBlueprintReasonCode.CITY_BLUEPRINT_FORBIDDEN_PLACEMENT_FIELD,
                        path + "." + entry.getKey(), "Coordinate, candidate, template and free algorithm fields are forbidden.");
            }
            rejectForbidden(entry.getValue(), path + "." + entry.getKey());
        }
    }

    private static <E extends Enum<E>> E enumValue(JsonObject object, String key, Class<E> type, String path) {
        String value = requiredString(object, key, path + "." + key);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_ENUM_UNSUPPORTED, path + "." + key,
                    "Unsupported enum value: " + value);
            return null;
        }
    }

    private static <E extends Enum<E>> E optionalEnum(JsonObject object, String key, Class<E> type, String path) {
        if (!object.has(key)) return null;
        return enumValue(object, key, type, path);
    }

    private static void exactFields(JsonObject object, Set<String> expected, String path) {
        exactFields(object, expected, Set.of(), path);
    }

    private static void exactFields(JsonObject object, Set<String> expected, Set<String> optional, String path) {
        for (String key : object.keySet()) {
            if (!expected.contains(key)) {
                fail(CityBlueprintReasonCode.CITY_BLUEPRINT_FIELD_UNKNOWN, path + "." + key,
                        "Unknown field: " + key);
            }
        }
        for (String key : expected) {
            if (optional.contains(key)) continue;
            if (!object.has(key) || object.get(key).isJsonNull()) {
                fail(CityBlueprintReasonCode.CITY_BLUEPRINT_FIELD_MISSING, path + "." + key,
                        "Required field is missing: " + key);
            }
        }
    }

    private static String optionalString(JsonObject object, String key, String path) {
        if (!object.has(key)) return null;
        return requiredString(object, key, path);
    }

    private static Boolean optionalBoolean(JsonObject object, String key, String path) {
        if (!object.has(key)) return null;
        if (!object.get(key).isJsonPrimitive() || !object.getAsJsonPrimitive(key).isBoolean()) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_FIELD_MISSING, path, "A boolean is required.");
        }
        return object.get(key).getAsBoolean();
    }

    private static boolean requiredBoolean(JsonObject object, String key, String path) {
        Boolean value = optionalBoolean(object, key, path);
        if (value == null) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_FIELD_MISSING, path, "A boolean is required.");
        }
        return value;
    }

    private static JsonObject requiredObject(JsonObject object, String key, String path) {
        if (!object.has(key) || !object.get(key).isJsonObject()) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_FIELD_MISSING, path, key + " object is required.");
        }
        return object.getAsJsonObject(key);
    }

    private static JsonArray requiredArray(JsonObject object, String key, String path) {
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_FIELD_MISSING, path, key + " array is required.");
        }
        return object.getAsJsonArray(key);
    }

    private static JsonObject objectElement(JsonElement element, String path) {
        if (!element.isJsonObject()) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_JSON_INVALID, path, "Expected an object.");
        }
        return element.getAsJsonObject();
    }

    private static String requiredString(JsonObject object, String key, String path) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.getAsJsonPrimitive(key).isString() || object.get(key).getAsString().isBlank()) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_FIELD_MISSING, path, "A non-empty string is required.");
        }
        return object.get(key).getAsString().trim();
    }

    private static long requiredLong(JsonObject object, String key, String path) {
        try {
            if (!object.has(key) || !object.get(key).isJsonPrimitive()
                    || !object.getAsJsonPrimitive(key).isNumber()
                    || !object.get(key).getAsString().matches("-?(0|[1-9][0-9]*)")) {
                throw new NumberFormatException();
            }
            long value = object.get(key).getAsLong();
            if (value < -MAX_SAFE_GENERATION_SEED || value > MAX_SAFE_GENERATION_SEED) {
                throw new NumberFormatException("outside JavaScript safe integer range");
            }
            return value;
        } catch (RuntimeException exception) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_FIELD_MISSING, path,
                    "A JavaScript-safe signed integer is required.");
            return 0;
        }
    }

    private static List<String> stringList(JsonArray array, String path) {
        List<String> values = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            JsonElement item = array.get(index);
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()
                    || item.getAsString().isBlank()) {
                fail(CityBlueprintReasonCode.CITY_BLUEPRINT_FIELD_MISSING, path + "[" + index + "]",
                        "A non-empty string is required.");
            }
            values.add(item.getAsString().trim());
        }
        return List.copyOf(values);
    }

    private static List<String> nonEmptyStringList(JsonObject object, String key, String path) {
        List<String> values = stringList(requiredArray(object, key, path), path);
        if (values.isEmpty()) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_FIELD_MISSING, path,
                    key + " must contain at least one value.");
        }
        return values;
    }

    private static JsonObject artifactRefJson(CityBlueprint.ArtifactRef ref) {
        JsonObject object = new JsonObject();
        object.addProperty("path", ref.path());
        object.addProperty("schemaVersion", ref.schemaVersion());
        object.addProperty("contentHash", ref.contentHash());
        return object;
    }

    private static JsonObject profileRefJson(CityBlueprint.ProfileRef ref) {
        JsonObject object = new JsonObject();
        object.addProperty("profileRef", ref.profileRef());
        return object;
    }

    private static JsonObject connectionPlanJson(CityBlueprint.ConnectionPlan plan) {
        JsonObject object = new JsonObject();
        if (plan.structurePoolRef() != null) object.addProperty("structurePoolRef", plan.structurePoolRef());
        if (plan.algorithmProfileRef() != null) object.addProperty("algorithmProfileRef", plan.algorithmProfileRef());
        if (plan.densityClass() != null) object.addProperty("densityClass", plan.densityClass().name());
        if (!plan.parameters().emptyParameters()) {
            JsonObject parameters = new JsonObject();
            if (plan.parameters().clusterShape() != null) {
                parameters.addProperty("clusterShape", plan.parameters().clusterShape().name());
            }
            if (plan.parameters().sideMode() != null) {
                parameters.addProperty("sideMode", plan.parameters().sideMode().name());
            }
            if (plan.parameters().stagger() != null) {
                parameters.addProperty("stagger", plan.parameters().stagger());
            }
            if (plan.parameters().widthClass() != null) {
                parameters.addProperty("widthClass", plan.parameters().widthClass().name());
            }
            object.add("parameters", parameters);
        }
        return object;
    }

    private static JsonObject outdoorPlanJson(CityBlueprint.OutdoorPlan plan) {
        JsonObject object = new JsonObject();
        object.addProperty("mode", plan.mode().name());
        object.addProperty("envelopeProfile", plan.envelopeProfile().name());
        JsonArray grounds = new JsonArray();
        for (CityBlueprint.SpatialGround ground : plan.spatialGrounds()) {
            JsonObject item = new JsonObject();
            item.addProperty("sourceGroupId", ground.sourceGroupId());
            item.addProperty("landUseRuleRef", ground.landUseRuleRef());
            item.addProperty("surfaceRecipeRef", ground.surfaceRecipeRef());
            item.addProperty("sharedSpaceType", ground.sharedSpaceType().name());
            item.addProperty("hierarchyLevel", ground.hierarchyLevel().name());
            item.addProperty("membership", ground.membership().name());
            grounds.add(item);
        }
        object.add("spatialGrounds", grounds);
        JsonArray landscapes = new JsonArray();
        for (CityBlueprint.Landscape landscape : plan.landscapes()) {
            JsonObject item = new JsonObject();
            item.addProperty("landscapeId", landscape.landscapeId());
            item.addProperty("landscapeProfileRef", landscape.landscapeProfileRef());
            item.add("attachedGroupIds", strings(landscape.attachedGroupIds()));
            item.add("preferredPatchRefs", strings(landscape.preferredPatchRefs()));
            item.addProperty("extentClass", landscape.extentClass().name());
            item.addProperty("intensity", landscape.intensity().name());
            item.addProperty("continuity", landscape.continuity().name());
            item.addProperty("growthRelation", landscape.growthRelation().name());
            item.add("referenceGroupIds", strings(landscape.referenceGroupIds()));
            item.addProperty("terrainPolicy", landscape.terrainPolicy().name());
            item.addProperty("required", landscape.required());
            landscapes.add(item);
        }
        object.add("landscapes", landscapes);
        return object;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static void fail(CityBlueprintReasonCode code, String path, String message) {
        throw new CityBlueprintContractException(code, path, message);
    }
}
