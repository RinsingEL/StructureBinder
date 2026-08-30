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
            "catalogSnapshotRef", "generationSeed", "designIntent", "styleProfile", "groups",
            "arrayCompositions", "relations", "roadProfile", "surfaceDetailProfile", "outdoorPlan");
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
                arrayCompositions(requiredArray(root, "arrayCompositions", "$.arrayCompositions")),
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
            if (group.placementRelation() != null) {
                item.add("placementRelation", placementRelationJson(group.placementRelation()));
            }
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
            item.addProperty("targetAreaShare", group.targetAreaShare());
            item.add("spaceComposition", spaceCompositionJson(group.spaceComposition()));
            item.add("expansionPolicy", expansionPolicyJson(group.expansionPolicy()));
            item.add("buildingGreeneryPolicy", buildingGreeneryPolicyJson(group.buildingGreeneryPolicy()));
            groups.add(item);
        }
        root.add("groups", groups);
        JsonArray compositions = new JsonArray();
        for (CityBlueprint.ArrayComposition composition : blueprint.arrayCompositions()) {
            JsonObject item = new JsonObject();
            item.addProperty("compositionId", composition.compositionId());
            item.addProperty("algorithmProfileRef", composition.algorithmProfileRef());
            item.addProperty("centerGroupId", composition.centerGroupId());
            item.add("memberGroupIds", strings(composition.memberGroupIds()));
            compositions.add(item);
        }
        root.add("arrayCompositions", compositions);
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
        exactFields(object, Set.of("mode", "envelopeProfile", "foundationProfileRef",
                "spatialGrounds", "landscapes"), path);
        return new CityBlueprint.OutdoorPlan(
                enumValue(object, "mode", CityBlueprint.OutdoorMode.class, path),
                enumValue(object, "envelopeProfile", CityBlueprint.EnvelopeProfile.class, path),
                requiredString(object, "foundationProfileRef", path + ".foundationProfileRef"),
                spatialGrounds(requiredArray(object, "spatialGrounds", path + ".spatialGrounds")),
                landscapes(requiredArray(object, "landscapes", path + ".landscapes")));
    }

    private static List<CityBlueprint.SpatialGround> spatialGrounds(JsonArray array) {
        List<CityBlueprint.SpatialGround> result = new ArrayList<>();
        Set<String> fields = Set.of("sourceGroupId", "sharedSpaceType", "hierarchyLevel", "membership");
        for (int index = 0; index < array.size(); index++) {
            String path = "$.outdoorPlan.spatialGrounds[" + index + "]";
            JsonObject item = objectElement(array.get(index), path);
            exactFields(item, fields, path);
            result.add(new CityBlueprint.SpatialGround(
                    requiredString(item, "sourceGroupId", path + ".sourceGroupId"),
                    enumValue(item, "sharedSpaceType", CityBlueprint.SharedSpaceType.class, path),
                    enumValue(item, "hierarchyLevel", CityBlueprint.SpatialHierarchy.class, path),
                    enumValue(item, "membership", CityBlueprint.OutdoorMembership.class, path)));
        }
        return List.copyOf(result);
    }

    private static List<CityBlueprint.Landscape> landscapes(JsonArray array) {
        List<CityBlueprint.Landscape> result = new ArrayList<>();
        Set<String> fields = Set.of("landscapeId", "landscapeProfileRef", "purpose", "originMode",
                "owner", "placementDomain", "instanceCount", "parcelCount", "preferredPatchRefs",
                "terrainPolicy", "required", "fillSelection");
        for (int index = 0; index < array.size(); index++) {
            String path = "$.outdoorPlan.landscapes[" + index + "]";
            JsonObject item = objectElement(array.get(index), path);
            exactFields(item, fields, Set.of("owner", "placementDomain"), path);
            CityBlueprint.LandscapeOwner owner = item.has("owner")
                    ? landscapeOwner(requiredObject(item, "owner", path + ".owner"), path + ".owner")
                    : null;
            result.add(new CityBlueprint.Landscape(
                    requiredString(item, "landscapeId", path + ".landscapeId"),
                    requiredString(item, "landscapeProfileRef", path + ".landscapeProfileRef"),
                    enumValue(item, "purpose", CityBlueprint.LandscapePurpose.class, path),
                    enumValue(item, "originMode", CityBlueprint.LandscapeOriginMode.class, path),
                    owner,
                    optionalEnum(item, "placementDomain", CityBlueprint.LandscapePlacementDomain.class, path),
                    positiveInt(item, "instanceCount", path + ".instanceCount"),
                    positiveInt(item, "parcelCount", path + ".parcelCount"),
                    stringList(requiredArray(item, "preferredPatchRefs", path + ".preferredPatchRefs"),
                            path + ".preferredPatchRefs"),
                    enumValue(item, "terrainPolicy", CityBlueprint.TerrainPolicy.class, path),
                    requiredBoolean(item, "required", path + ".required"),
                    fillSelection(requiredObject(item, "fillSelection", path + ".fillSelection"),
                            path + ".fillSelection")));
        }
        return List.copyOf(result);
    }

    private static CityBlueprint.LandscapeOwner landscapeOwner(JsonObject object, String path) {
        exactFields(object, Set.of("groupId", "requiredStructureRef"), Set.of("requiredStructureRef"), path);
        return new CityBlueprint.LandscapeOwner(
                requiredString(object, "groupId", path + ".groupId"),
                object.has("requiredStructureRef")
                        ? requiredString(object, "requiredStructureRef", path + ".requiredStructureRef") : "");
    }

    private static CityBlueprint.FillSelection fillSelection(JsonObject object, String path) {
        exactFields(object, Set.of("variants"), path);
        JsonArray variants = requiredArray(object, "variants", path + ".variants");
        if (variants.isEmpty()) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_FIELD_MISSING, path + ".variants",
                    "fillSelection.variants must not be empty.");
        }
        List<CityBlueprint.FillVariant> result = new ArrayList<>();
        Set<String> fields = Set.of("fillProfileRef", "selectionWeight", "roleShares", "contentWeights");
        for (int index = 0; index < variants.size(); index++) {
            String itemPath = path + ".variants[" + index + "]";
            JsonObject item = objectElement(variants.get(index), itemPath);
            exactFields(item, fields, itemPath);
            JsonArray roleShares = requiredArray(item, "roleShares", itemPath + ".roleShares");
            if (roleShares.isEmpty()) {
                fail(CityBlueprintReasonCode.CITY_BLUEPRINT_FIELD_MISSING, itemPath + ".roleShares",
                        "roleShares must not be empty.");
            }
            result.add(new CityBlueprint.FillVariant(
                    requiredString(item, "fillProfileRef", itemPath + ".fillProfileRef"),
                    positiveNumber(item, "selectionWeight", itemPath + ".selectionWeight"),
                    roleShares(roleShares, itemPath + ".roleShares"),
                    contentWeights(requiredArray(item, "contentWeights", itemPath + ".contentWeights"),
                            itemPath + ".contentWeights")));
        }
        return new CityBlueprint.FillSelection(result);
    }

    private static List<CityBlueprint.RoleShare> roleShares(JsonArray array, String path) {
        List<CityBlueprint.RoleShare> result = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            String itemPath = path + "[" + index + "]";
            JsonObject item = objectElement(array.get(index), itemPath);
            exactFields(item, Set.of("roleRef", "growthForm", "targetShare"), itemPath);
            double share = positiveNumber(item, "targetShare", itemPath + ".targetShare");
            if (share >= 1.0) {
                fail(CityBlueprintReasonCode.CITY_BLUEPRINT_FIELD_MISSING, itemPath + ".targetShare",
                        "targetShare must be greater than 0 and less than 1.");
            }
            result.add(new CityBlueprint.RoleShare(
                    requiredString(item, "roleRef", itemPath + ".roleRef"),
                    enumValue(item, "growthForm", CityBlueprint.RegionGrowthForm.class, itemPath), share));
        }
        return List.copyOf(result);
    }

    private static List<CityBlueprint.ContentWeight> contentWeights(JsonArray array, String path) {
        List<CityBlueprint.ContentWeight> result = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            String itemPath = path + "[" + index + "]";
            JsonObject item = objectElement(array.get(index), itemPath);
            exactFields(item, Set.of("contentRef", "weight"), itemPath);
            result.add(new CityBlueprint.ContentWeight(
                    requiredString(item, "contentRef", itemPath + ".contentRef"),
                    positiveNumber(item, "weight", itemPath + ".weight")));
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
                "placementRelation", "role", "priority",
                "extentClass", "densityClass",
                "algorithmProfileRef", "terrainPolicy", "requiredStructureRefs", "fillPoolRef",
                "connectionPlan", "compositionProfileRef", "attachedFeatures", "targetAreaShare",
                "spaceComposition", "expansionPolicy", "buildingGreeneryPolicy");
        for (int index = 0; index < array.size(); index++) {
            String path = "$.groups[" + index + "]";
            JsonObject item = objectElement(array.get(index), path);
            exactFields(item, fields, Set.of("placementRelation", "connectionPlan"), path);
            result.add(new CityBlueprint.Group(
                    requiredString(item, "groupId", path + ".groupId"),
                    enumValue(item, "groupKind", CityBlueprint.GroupKind.class, path),
                    nonEmptyStringList(item, "preferredPatchRefs", path + ".preferredPatchRefs"),
                    enumValue(item, "preferredPatchZone", CityBlueprint.PreferredPatchZone.class, path),
                    item.has("placementRelation")
                            ? placementRelation(requiredObject(item, "placementRelation",
                            path + ".placementRelation"), path + ".placementRelation") : null,
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
                            path + ".attachedFeatures"),
                    boundedShare(item, "targetAreaShare", path + ".targetAreaShare"),
                    spaceComposition(requiredObject(item, "spaceComposition", path + ".spaceComposition"),
                            path + ".spaceComposition"),
                    expansionPolicy(requiredObject(item, "expansionPolicy", path + ".expansionPolicy"),
                            path + ".expansionPolicy"),
                    buildingGreeneryPolicy(requiredObject(item, "buildingGreeneryPolicy",
                            path + ".buildingGreeneryPolicy"), path + ".buildingGreeneryPolicy")));
        }
        return List.copyOf(result);
    }

    private static CityBlueprint.SpaceComposition spaceComposition(JsonObject object, String path) {
        exactFields(object, Set.of("buildingShare", "landscapeShare", "openSpaceShare"), path);
        return new CityBlueprint.SpaceComposition(
                boundedShare(object, "buildingShare", path + ".buildingShare"),
                boundedShare(object, "landscapeShare", path + ".landscapeShare"),
                boundedShare(object, "openSpaceShare", path + ".openSpaceShare"));
    }

    private static JsonObject spaceCompositionJson(CityBlueprint.SpaceComposition composition) {
        JsonObject object = new JsonObject();
        object.addProperty("buildingShare", composition.buildingShare());
        object.addProperty("landscapeShare", composition.landscapeShare());
        object.addProperty("openSpaceShare", composition.openSpaceShare());
        return object;
    }

    private static CityBlueprint.ExpansionPolicy expansionPolicy(JsonObject object, String path) {
        exactFields(object, Set.of("allowOutwardExpansion", "allowRelationConnection", "stopWhenTargetReached"), path);
        return new CityBlueprint.ExpansionPolicy(
                requiredBoolean(object, "allowOutwardExpansion", path + ".allowOutwardExpansion"),
                requiredBoolean(object, "allowRelationConnection", path + ".allowRelationConnection"),
                requiredBoolean(object, "stopWhenTargetReached", path + ".stopWhenTargetReached"));
    }

    private static CityBlueprint.BuildingGreeneryPolicy buildingGreeneryPolicy(JsonObject object, String path) {
        exactFields(object, Set.of("coverage", "patternPreference", "densityPreference"), path);
        return new CityBlueprint.BuildingGreeneryPolicy(
                enumValue(object, "coverage", CityBlueprint.GreeneryCoverage.class, path),
                enumValue(object, "patternPreference", CityBlueprint.GreeneryPatternPreference.class, path),
                enumValue(object, "densityPreference", CityBlueprint.GreeneryDensityPreference.class, path));
    }

    private static JsonObject buildingGreeneryPolicyJson(CityBlueprint.BuildingGreeneryPolicy policy) {
        JsonObject object = new JsonObject();
        object.addProperty("coverage", policy.coverage().name());
        object.addProperty("patternPreference", policy.patternPreference().name());
        object.addProperty("densityPreference", policy.densityPreference().name());
        return object;
    }

    private static JsonObject expansionPolicyJson(CityBlueprint.ExpansionPolicy policy) {
        JsonObject object = new JsonObject();
        object.addProperty("allowOutwardExpansion", policy.allowOutwardExpansion());
        object.addProperty("allowRelationConnection", policy.allowRelationConnection());
        object.addProperty("stopWhenTargetReached", policy.stopWhenTargetReached());
        return object;
    }

    private static CityBlueprint.PlacementRelation placementRelation(JsonObject object, String path) {
        exactFields(object, Set.of("kind", "patchRefs", "groupRefs"), path);
        return new CityBlueprint.PlacementRelation(
                enumValue(object, "kind", CityBlueprint.PlacementRelationKind.class, path),
                stringList(requiredArray(object, "patchRefs", path + ".patchRefs"), path + ".patchRefs"),
                stringList(requiredArray(object, "groupRefs", path + ".groupRefs"), path + ".groupRefs"));
    }

    private static JsonObject placementRelationJson(CityBlueprint.PlacementRelation relation) {
        JsonObject object = new JsonObject();
        object.addProperty("kind", relation.kind().name());
        object.add("patchRefs", strings(relation.patchRefs()));
        object.add("groupRefs", strings(relation.groupRefs()));
        return object;
    }

    private static List<CityBlueprint.ArrayComposition> arrayCompositions(JsonArray array) {
        List<CityBlueprint.ArrayComposition> result = new ArrayList<>();
        Set<String> fields = Set.of("compositionId", "algorithmProfileRef", "centerGroupId",
                "memberGroupIds");
        for (int index = 0; index < array.size(); index++) {
            String path = "$.arrayCompositions[" + index + "]";
            JsonObject item = objectElement(array.get(index), path);
            exactFields(item, fields, path);
            result.add(new CityBlueprint.ArrayComposition(
                    requiredString(item, "compositionId", path + ".compositionId"),
                    requiredString(item, "algorithmProfileRef", path + ".algorithmProfileRef"),
                    requiredString(item, "centerGroupId", path + ".centerGroupId"),
                    nonEmptyStringList(item, "memberGroupIds", path + ".memberGroupIds")));
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

    private static double positiveNumber(JsonObject object, String key, String path) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.getAsJsonPrimitive(key).isNumber()) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_FIELD_MISSING, path,
                    "A positive finite number is required.");
        }
        double value = object.get(key).getAsDouble();
        if (!Double.isFinite(value) || value <= 0.0) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_FIELD_MISSING, path,
                    "A positive finite number is required.");
        }
        return value;
    }

    private static double boundedShare(JsonObject object, String key, String path) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.getAsJsonPrimitive(key).isNumber()) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_FIELD_MISSING, path,
                    "A finite share in [0,1] is required.");
        }
        double value = object.get(key).getAsDouble();
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_FIELD_MISSING, path,
                    "A finite share in [0,1] is required.");
        }
        return value;
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

    private static int positiveInt(JsonObject object, String key, String path) {
        long value = requiredLong(object, key, path);
        if (value <= 0 || value > Integer.MAX_VALUE) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_FIELD_MISSING, path,
                    "A positive 32-bit integer is required.");
        }
        return (int) value;
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
        object.addProperty("foundationProfileRef", plan.foundationProfileRef());
        JsonArray grounds = new JsonArray();
        for (CityBlueprint.SpatialGround ground : plan.spatialGrounds()) {
            JsonObject item = new JsonObject();
            item.addProperty("sourceGroupId", ground.sourceGroupId());
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
            item.addProperty("purpose", landscape.purpose().name());
            item.addProperty("originMode", landscape.originMode().name());
            if (landscape.owner() != null) {
                JsonObject owner = new JsonObject();
                owner.addProperty("groupId", landscape.owner().groupId());
                if (!landscape.owner().groupOwned()) {
                    owner.addProperty("requiredStructureRef", landscape.owner().requiredStructureRef());
                }
                item.add("owner", owner);
            }
            if (landscape.placementDomain() != null) {
                item.addProperty("placementDomain", landscape.placementDomain().name());
            }
            item.addProperty("instanceCount", landscape.instanceCount());
            item.addProperty("parcelCount", landscape.parcelCount());
            item.add("preferredPatchRefs", strings(landscape.preferredPatchRefs()));
            item.addProperty("terrainPolicy", landscape.terrainPolicy().name());
            item.addProperty("required", landscape.required());
            item.add("fillSelection", fillSelectionJson(landscape.fillSelection()));
            landscapes.add(item);
        }
        object.add("landscapes", landscapes);
        return object;
    }

    private static JsonObject fillSelectionJson(CityBlueprint.FillSelection selection) {
        JsonObject object = new JsonObject();
        JsonArray variants = new JsonArray();
        for (CityBlueprint.FillVariant variant : selection.variants()) {
            JsonObject item = new JsonObject();
            item.addProperty("fillProfileRef", variant.fillProfileRef());
            item.addProperty("selectionWeight", variant.selectionWeight());
            JsonArray roleShares = new JsonArray();
            for (CityBlueprint.RoleShare share : variant.roleShares()) {
                JsonObject role = new JsonObject();
                role.addProperty("roleRef", share.roleRef());
                role.addProperty("growthForm", share.growthForm().name());
                role.addProperty("targetShare", share.targetShare());
                roleShares.add(role);
            }
            item.add("roleShares", roleShares);
            JsonArray contentWeights = new JsonArray();
            for (CityBlueprint.ContentWeight weight : variant.contentWeights()) {
                JsonObject content = new JsonObject();
                content.addProperty("contentRef", weight.contentRef());
                content.addProperty("weight", weight.weight());
                contentWeights.add(content);
            }
            item.add("contentWeights", contentWeights);
            variants.add(item);
        }
        object.add("variants", variants);
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
