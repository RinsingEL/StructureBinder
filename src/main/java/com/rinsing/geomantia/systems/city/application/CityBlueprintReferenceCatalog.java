package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprintContractException;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprintReasonCode;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSurfaceSettings;
import com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseRuleCatalog;
import com.rinsing.geomantia.systems.city.infrastructure.landuse.LandUseRuleCatalogLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Frozen whitelist consumed by Blueprint validation and, after the review gate, by the compiler. */
public record CityBlueprintReferenceCatalog(
        JsonObject json,
        Set<String> structureRefs,
        Set<String> fillPoolRefs,
        Set<String> algorithmProfileRefs,
        Map<String, String> algorithmsByProfileRef,
        Map<String, Boolean> centerAxisStreetEnabledByProfileRef,
        Set<String> compositionProfileRefs,
        Set<String> styleProfileRefs,
        Set<String> roadProfileRefs,
        Set<String> surfaceDetailProfileRefs,
        LandUseRuleCatalog landUseRuleCatalog,
        Map<String, SurfaceRecipe> surfaceRecipes,
        Map<String, FoundationProfile> foundationProfiles,
        Map<String, LandscapeProfile> landscapeProfiles,
        Map<String, LandscapeFillProfile> landscapeFillProfiles) {

    public static final String SCHEMA_VERSION = "city_blueprint_reference_catalog.v0.8";
    private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "structureRefs", "fillPools",
            "algorithmProfiles", "compositionProfiles", "styleProfiles", "roadProfiles",
            "surfaceDetailProfiles", "landUseRuleProfile", "surfaceRecipes", "foundationProfiles",
            "landscapeProfiles", "landscapeFillProfiles");

    public static CityBlueprintReferenceCatalog parse(JsonObject root, CityTemplateCatalog templateCatalog) {
        if (root == null) fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID,
                "$", "blueprintReferenceCatalog object is required.");
        exactFields(root, ROOT_FIELDS, "$", CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID);
        String schema = string(root, "schemaVersion", "$.schemaVersion");
        if (!SCHEMA_VERSION.equals(schema)) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_SCHEMA_UNSUPPORTED,
                    "$.schemaVersion", "Unsupported blueprint reference catalog schema: " + schema);
        }
        Set<String> structures = structureRefs(array(root, "structureRefs"), templateCatalog);
        Set<String> pools = refs(array(root, "fillPools"), "poolRef", Set.of("poolRef", "structureRefs"),
                "$.fillPools", item -> {
                    for (JsonElement entry : array(item, "structureRefs")) {
                        String ref = stringElement(entry, "$.fillPools[].structureRefs[]");
                        if (!structures.contains(ref)) {
                            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_STRUCTURE_REF_UNKNOWN,
                                    "$.fillPools[].structureRefs", "Unknown structureRef in fill pool: " + ref);
                        }
                    }
                });
        Map<String, String> algorithmsByRef = new LinkedHashMap<>();
        Map<String, Boolean> centerAxisStreetsByRef = new LinkedHashMap<>();
        Set<String> algorithms = new LinkedHashSet<>();
        JsonArray algorithmProfiles = array(root, "algorithmProfiles");
        for (int index = 0; index < algorithmProfiles.size(); index++) {
            String path = "$.algorithmProfiles[" + index + "]";
            JsonObject item = object(algorithmProfiles.get(index), path);
            Set<String> fields = item.has("centerAxisStreetEnabled")
                    ? Set.of("algorithmProfileRef", "algorithm", "centerAxisStreetEnabled")
                    : Set.of("algorithmProfileRef", "algorithm");
            exactFields(item, fields, path, CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID);
            String ref = string(item, "algorithmProfileRef", path + ".algorithmProfileRef");
            duplicate(algorithms, ref, path);
            String algorithm = string(item, "algorithm", path + ".algorithm");
            if (!Set.of("COMPACT", "GRID", "LINEAR", "COURTYARD", "ORGANIC_COMPACT",
                    "CENTER_SYMMETRIC").contains(algorithm)) {
                fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID,
                        path + ".algorithm", "Unsupported value: " + algorithm);
            }
            boolean axisStreet = item.has("centerAxisStreetEnabled")
                    && bool(item, "centerAxisStreetEnabled", path + ".centerAxisStreetEnabled");
            if (axisStreet && !"CENTER_SYMMETRIC".equals(algorithm)) {
                fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID,
                        path + ".centerAxisStreetEnabled",
                        "centerAxisStreetEnabled is only valid for CENTER_SYMMETRIC.");
            }
            algorithmsByRef.put(ref, algorithm);
            centerAxisStreetsByRef.put(ref, axisStreet);
        }
        Set<String> compositions = refs(array(root, "compositionProfiles"), "compositionProfileRef",
                Set.of("compositionProfileRef", "mode"), "$.compositionProfiles",
                item -> enumString(item, "mode", Set.of("ROUND_ROBIN")));
        Set<String> styles = profileRefs(array(root, "styleProfiles"), "profileRef", Set.of("profileRef"),
                "$.styleProfiles", ignored -> { });
        Set<String> roads = profileRefs(array(root, "roadProfiles"), "profileRef",
                Set.of("profileRef", "hierarchy", "density"), "$.roadProfiles", item -> {
                    enumString(item, "hierarchy", Set.of("SIMPLE", "HIERARCHICAL"));
                    enumString(item, "density", Set.of("SPARSE", "BALANCED", "DENSE"));
                });
        Set<String> surfaces = profileRefs(array(root, "surfaceDetailProfiles"), "profileRef",
                Set.of("profileRef", "intensity"), "$.surfaceDetailProfiles",
                item -> enumString(item, "intensity", Set.of("LOW", "MEDIUM", "HIGH")));
        JsonObject ruleProfile = object(root.get("landUseRuleProfile"), "$.landUseRuleProfile");
        String ruleProfileId = string(ruleProfile, "profileId", "$.landUseRuleProfile.profileId");
        LandUseRuleCatalog landUseRules;
        try {
            landUseRules = LandUseRuleCatalogLoader.parse(ruleProfile, ruleProfileId);
        } catch (IllegalArgumentException exception) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID,
                    "$.landUseRuleProfile", exception.getMessage());
            return null;
        }
        Map<String, SurfaceRecipe> surfaceRecipes = surfaceRecipes(array(root, "surfaceRecipes"));
        Map<String, FoundationProfile> foundationProfiles = foundationProfiles(
                array(root, "foundationProfiles"), landUseRules, surfaceRecipes);
        Map<String, LandscapeProfile> landscapeProfiles = landscapeProfiles(
                array(root, "landscapeProfiles"), landUseRules, surfaceRecipes);
        Map<String, LandscapeFillProfile> landscapeFillProfiles = landscapeFillProfiles(
                array(root, "landscapeFillProfiles"));
        if (structures.isEmpty() || pools.isEmpty() || algorithms.isEmpty() || compositions.isEmpty()
            || styles.isEmpty() || roads.isEmpty() || surfaces.isEmpty() || surfaceRecipes.isEmpty()
                || foundationProfiles.isEmpty() || landscapeProfiles.isEmpty() || landscapeFillProfiles.isEmpty()) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID, "$",
                    "Every reference catalog namespace must contain at least one entry.");
        }
        for (LandscapeProfile landscapeProfile : landscapeProfiles.values()) {
            boolean supported = landscapeFillProfiles.values().stream().anyMatch(fillProfile ->
                    fillProfile.compatibleLandscapeTypes().contains(landscapeProfile.landscapeType()));
            if (!supported) {
                fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID,
                        "$.landscapeProfiles[" + landscapeProfile.landscapeProfileRef() + "].landscapeType",
                        "Every landscape profile must have at least one compatible landscapeFillProfile.");
            }
        }
        return new CityBlueprintReferenceCatalog(root.deepCopy(), structures, pools, algorithms,
                Map.copyOf(algorithmsByRef), Map.copyOf(centerAxisStreetsByRef), compositions,
                styles, roads, surfaces, landUseRules, Map.copyOf(surfaceRecipes),
                Map.copyOf(foundationProfiles),
                Map.copyOf(landscapeProfiles), Map.copyOf(landscapeFillProfiles));
    }

    private static Map<String, SurfaceRecipe> surfaceRecipes(JsonArray array) {
        Map<String, SurfaceRecipe> result = new LinkedHashMap<>();
        Set<String> common = Set.of("surfaceRecipeRef", "surfacePrintEnabled", "autoConnectDefault",
                "surfaceAlgorithm");
        Set<String> materials = Set.of("surfaceBlockId", "cropBlockId", "channelBankBlockId",
                "channelWaterBlockId", "channelBankOverlayBlockId");
        Set<String> contourWidths = Set.of("fieldBeforeBlocks", "channelWidthBlocks", "fieldAfterBlocks");
        for (int index = 0; index < array.size(); index++) {
            String path = "$.surfaceRecipes[" + index + "]";
            JsonObject item = object(array.get(index), path);
            String ref = string(item, "surfaceRecipeRef", path + ".surfaceRecipeRef");
            boolean enabled = bool(item, "surfacePrintEnabled", path + ".surfacePrintEnabled");
            boolean autoConnect = bool(item, "autoConnectDefault", path + ".autoConnectDefault");
            SurfaceAlgorithm algorithm = enumValue(item, "surfaceAlgorithm", SurfaceAlgorithm.class, path);
            if (!enabled && autoConnect) {
                fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID,
                        path + ".autoConnectDefault",
                        "A disabled surface recipe cannot enable automatic surface connection.");
            }
            Set<String> expected = new LinkedHashSet<>(common);
            if (enabled) {
                expected.add("surfaceBlockId");
                if (item.has("boundaryBlockId")) expected.add("boundaryBlockId");
                for (String material : materials) {
                    if (item.has(material)) expected.add(material);
                }
                if (algorithm == SurfaceAlgorithm.CONTOUR_BANDS) {
                    expected.addAll(materials);
                    expected.addAll(contourWidths);
                }
            }
            exactFields(item, expected, path, CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID);
            String surface = enabled ? blockId(item, "surfaceBlockId", path) : null;
            String crop = enabled && item.has("cropBlockId") ? blockId(item, "cropBlockId", path) : null;
            String bank = enabled && item.has("channelBankBlockId")
                    ? blockId(item, "channelBankBlockId", path) : null;
            String water = enabled && item.has("channelWaterBlockId")
                    ? blockId(item, "channelWaterBlockId", path) : null;
            String overlay = enabled && item.has("channelBankOverlayBlockId")
                    ? blockId(item, "channelBankOverlayBlockId", path) : null;
            String boundary = enabled && item.has("boundaryBlockId")
                    ? blockId(item, "boundaryBlockId", path) : null;
            int fieldBefore = enabled && algorithm == SurfaceAlgorithm.CONTOUR_BANDS
                    ? positiveInt(item, "fieldBeforeBlocks", path) : 0;
            int channelWidth = enabled && algorithm == SurfaceAlgorithm.CONTOUR_BANDS
                    ? positiveInt(item, "channelWidthBlocks", path) : 0;
            int fieldAfter = enabled && algorithm == SurfaceAlgorithm.CONTOUR_BANDS
                    ? positiveInt(item, "fieldAfterBlocks", path) : 0;
            if (result.put(ref, new SurfaceRecipe(ref, enabled, autoConnect, algorithm,
                    surface, crop, bank, water, overlay, boundary, fieldBefore, channelWidth, fieldAfter)) != null) {
                fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_DUPLICATE_REF,
                        path, "Duplicate reference: " + ref);
            }
        }
        return result;
    }

    private static Map<String, FoundationProfile> foundationProfiles(JsonArray array,
                                                                      LandUseRuleCatalog rules,
                                                                      Map<String, SurfaceRecipe> recipes) {
        Map<String, FoundationProfile> result = new LinkedHashMap<>();
        Set<String> fields = Set.of("foundationProfileRef", "landUseRuleRef", "surfaceRecipeRef",
                "structureMarginBlocks", "closeRadiusBlocks", "maxJoinDistanceBlocks");
        for (int index = 0; index < array.size(); index++) {
            String path = "$.foundationProfiles[" + index + "]";
            JsonObject item = object(array.get(index), path);
            exactFields(item, fields, path, CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID);
            String ref = string(item, "foundationProfileRef", path + ".foundationProfileRef");
            String ruleRef = string(item, "landUseRuleRef", path + ".landUseRuleRef");
            String recipeRef = string(item, "surfaceRecipeRef", path + ".surfaceRecipeRef");
            if (rules.byRef(ruleRef).isEmpty()) {
                fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID,
                        path + ".landUseRuleRef", "Unknown LandUse ruleRef: " + ruleRef);
            }
            if (!recipes.containsKey(recipeRef)) {
                fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID,
                        path + ".surfaceRecipeRef", "Unknown surfaceRecipeRef: " + recipeRef);
            }
            int margin = nonNegativeInt(item, "structureMarginBlocks", path);
            int close = nonNegativeInt(item, "closeRadiusBlocks", path);
            int join = nonNegativeInt(item, "maxJoinDistanceBlocks", path);
            if (margin > close || close > join) {
                fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID, path,
                        "Foundation distances must be monotonic: structureMarginBlocks <= "
                                + "closeRadiusBlocks <= maxJoinDistanceBlocks.");
            }
            FoundationProfile profile = new FoundationProfile(ref, ruleRef, recipeRef, margin, close, join);
            if (result.put(ref, profile) != null) {
                fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_DUPLICATE_REF,
                        path, "Duplicate reference: " + ref);
            }
        }
        return result;
    }

    private static Map<String, LandscapeProfile> landscapeProfiles(JsonArray array,
                                                                    LandUseRuleCatalog rules,
                                                                    Map<String, SurfaceRecipe> recipes) {
        Map<String, LandscapeProfile> result = new LinkedHashMap<>();
        Set<String> fields = Set.of("landscapeProfileRef", "landscapeType", "landUseRuleRef",
                "surfaceRecipeRef", "baseAreaSmall", "baseAreaMedium", "baseAreaLarge", "membership",
                "parcelStyle");
        for (int index = 0; index < array.size(); index++) {
            String path = "$.landscapeProfiles[" + index + "]";
            JsonObject item = object(array.get(index), path);
            exactFields(item, fields, path, CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID);
            String ref = string(item, "landscapeProfileRef", path + ".landscapeProfileRef");
            String ruleRef = string(item, "landUseRuleRef", path + ".landUseRuleRef");
            String recipeRef = string(item, "surfaceRecipeRef", path + ".surfaceRecipeRef");
            if (rules.byRef(ruleRef).isEmpty()) {
                fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID,
                        path + ".landUseRuleRef", "Unknown LandUse ruleRef: " + ruleRef);
            }
            if (!recipes.containsKey(recipeRef)) {
                fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID,
                        path + ".surfaceRecipeRef", "Unknown surfaceRecipeRef: " + recipeRef);
            }
            int small = positiveInt(item, "baseAreaSmall", path);
            int medium = positiveInt(item, "baseAreaMedium", path);
            int large = positiveInt(item, "baseAreaLarge", path);
            if (small > medium || medium > large) {
                fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID, path,
                        "Landscape base areas must be monotonic: small <= medium <= large.");
            }
            LandscapeProfile profile = new LandscapeProfile(ref,
                    enumValue(item, "landscapeType", LandscapeType.class, path), ruleRef, recipeRef,
                    small, medium, large,
                    enumValue(item, "membership", CityBlueprint.OutdoorMembership.class, path),
                    parcelStyle(object(item.get("parcelStyle"), path + ".parcelStyle"), path + ".parcelStyle"));
            if (result.put(ref, profile) != null) {
                fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_DUPLICATE_REF,
                        path, "Duplicate reference: " + ref);
            }
        }
        return result;
    }

    private static Map<String, LandscapeFillProfile> landscapeFillProfiles(JsonArray array) {
        Map<String, LandscapeFillProfile> result = new LinkedHashMap<>();
        Set<String> fields = Set.of("fillProfileRef", "displayName", "visualIntent", "algorithm",
                "relayOrigin", "compatibleLandscapeTypes", "primaryRoleRef", "roles",
                "allowedContentRefs", "examples");
        for (int index = 0; index < array.size(); index++) {
            String path = "$.landscapeFillProfiles[" + index + "]";
            JsonObject item = object(array.get(index), path);
            exactFields(item, fields, path, CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID);
            String ref = string(item, "fillProfileRef", path + ".fillProfileRef");
            FillAlgorithm algorithm = enumValue(item, "algorithm", FillAlgorithm.class, path);
            RelayOrigin relayOrigin = enumValue(item, "relayOrigin", RelayOrigin.class, path);
            Set<LandscapeType> compatibleTypes = landscapeTypes(
                    array(item, "compatibleLandscapeTypes"), path + ".compatibleLandscapeTypes");
            Map<String, FillRole> roles = fillRoles(array(item, "roles"), path + ".roles");
            String primaryRoleRef = string(item, "primaryRoleRef", path + ".primaryRoleRef");
            if (!roles.containsKey(primaryRoleRef)) {
                fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID,
                        path + ".primaryRoleRef", "primaryRoleRef must name a declared role.");
            }
            if (roles.get(primaryRoleRef).materialRole() != MaterialRole.PRIMARY_CONTENT) {
                fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID,
                        path + ".primaryRoleRef", "primaryRoleRef must use materialRole PRIMARY_CONTENT.");
            }
            Set<String> allowedContentRefs = new LinkedHashSet<>(stringList(
                    array(item, "allowedContentRefs"), path + ".allowedContentRefs", true));
            List<FillExample> examples = fillExamples(array(item, "examples"), path + ".examples",
                    roles, primaryRoleRef, allowedContentRefs);
            LandscapeFillProfile profile = new LandscapeFillProfile(ref,
                    string(item, "displayName", path + ".displayName"),
                    string(item, "visualIntent", path + ".visualIntent"), algorithm,
                    relayOrigin, compatibleTypes, primaryRoleRef, roles,
                    Set.copyOf(allowedContentRefs), examples);
            if (result.put(ref, profile) != null) {
                fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_DUPLICATE_REF,
                        path, "Duplicate reference: " + ref);
            }
        }
        return result;
    }

    private static Set<LandscapeType> landscapeTypes(JsonArray array, String path) {
        if (array.isEmpty()) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID, path,
                    "compatibleLandscapeTypes must not be empty.");
        }
        Set<LandscapeType> result = new LinkedHashSet<>();
        for (int index = 0; index < array.size(); index++) {
            String value = stringElement(array.get(index), path + "[" + index + "]");
            LandscapeType type;
            try {
                type = LandscapeType.valueOf(value);
            } catch (IllegalArgumentException exception) {
                fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID,
                        path + "[" + index + "]", "Unsupported landscape type: " + value);
                return Set.of();
            }
            if (!result.add(type)) {
                fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID,
                        path + "[" + index + "]", "Landscape types must be unique.");
            }
        }
        return Set.copyOf(result);
    }

    private static Map<String, FillRole> fillRoles(JsonArray array, String path) {
        if (array.isEmpty()) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID, path,
                    "roles must not be empty.");
        }
        Map<String, FillRole> result = new LinkedHashMap<>();
        double defaultSum = 0.0;
        for (int index = 0; index < array.size(); index++) {
            String itemPath = path + "[" + index + "]";
            JsonObject item = object(array.get(index), itemPath);
            exactFields(item, Set.of("roleRef", "materialRole", "allowedGrowthForms", "defaultGrowthForm",
                    "minShare", "maxShare", "defaultShare"), itemPath,
                    CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID);
            String roleRef = string(item, "roleRef", itemPath + ".roleRef");
            MaterialRole materialRole = enumValue(item, "materialRole", MaterialRole.class, itemPath);
            Set<CityBlueprint.RegionGrowthForm> allowedGrowthForms = growthForms(
                    array(item, "allowedGrowthForms"), itemPath + ".allowedGrowthForms");
            CityBlueprint.RegionGrowthForm defaultGrowthForm = enumValue(item, "defaultGrowthForm",
                    CityBlueprint.RegionGrowthForm.class, itemPath);
            if (!allowedGrowthForms.contains(defaultGrowthForm)) {
                fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID,
                        itemPath + ".defaultGrowthForm", "defaultGrowthForm must be allowed by this role.");
            }
            double min = share(item, "minShare", itemPath + ".minShare", true);
            double max = share(item, "maxShare", itemPath + ".maxShare", false);
            double defaultShare = share(item, "defaultShare", itemPath + ".defaultShare", true);
            if (min > max || defaultShare < min || defaultShare > max) {
                fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID, itemPath,
                        "Role shares must satisfy 0 <= minShare <= defaultShare <= maxShare <= 1.");
            }
            if (result.put(roleRef, new FillRole(roleRef, materialRole, allowedGrowthForms,
                    defaultGrowthForm, min, max, defaultShare)) != null) {
                fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_DUPLICATE_REF,
                        itemPath, "Duplicate roleRef: " + roleRef);
            }
            defaultSum += defaultShare;
        }
        requireUnitSum(defaultSum, path, "Role defaultShare values");
        return Map.copyOf(result);
    }

    private static Set<CityBlueprint.RegionGrowthForm> growthForms(JsonArray array, String path) {
        if (array.isEmpty()) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID, path,
                    "allowedGrowthForms must not be empty.");
        }
        Set<CityBlueprint.RegionGrowthForm> result = new LinkedHashSet<>();
        for (int index = 0; index < array.size(); index++) {
            String value = stringElement(array.get(index), path + "[" + index + "]");
            CityBlueprint.RegionGrowthForm form;
            try {
                form = CityBlueprint.RegionGrowthForm.valueOf(value);
            } catch (IllegalArgumentException exception) {
                fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID,
                        path + "[" + index + "]", "Unsupported growth form: " + value);
                return Set.of();
            }
            if (!result.add(form)) {
                fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID,
                        path + "[" + index + "]", "Growth forms must be unique.");
            }
        }
        return Set.copyOf(result);
    }

    private static List<FillExample> fillExamples(JsonArray array, String path, Map<String, FillRole> roles,
                                                   String primaryRoleRef, Set<String> allowedContentRefs) {
        if (array.isEmpty()) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID, path,
                    "examples must not be empty.");
        }
        List<FillExample> result = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (int index = 0; index < array.size(); index++) {
            String itemPath = path + "[" + index + "]";
            JsonObject item = object(array.get(index), itemPath);
            exactFields(item, Set.of("exampleId", "description", "roleShares", "contentWeights"), itemPath,
                    CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID);
            String exampleId = string(item, "exampleId", itemPath + ".exampleId");
            if (!ids.add(exampleId)) {
                fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_DUPLICATE_REF,
                        itemPath + ".exampleId", "Duplicate exampleId: " + exampleId);
            }
            List<ExampleRoleShare> roleShares = exampleRoleShares(array(item, "roleShares"),
                    itemPath + ".roleShares", roles, primaryRoleRef);
            List<ExampleContentWeight> contentWeights = exampleContentWeights(array(item, "contentWeights"),
                    itemPath + ".contentWeights", allowedContentRefs);
            result.add(new FillExample(exampleId, string(item, "description", itemPath + ".description"),
                    roleShares, contentWeights));
        }
        return List.copyOf(result);
    }

    private static List<ExampleRoleShare> exampleRoleShares(JsonArray array, String path,
                                                             Map<String, FillRole> roles,
                                                             String primaryRoleRef) {
        if (array.isEmpty()) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID, path,
                    "Example roleShares must not be empty.");
        }
        List<ExampleRoleShare> result = new ArrayList<>();
        Set<String> refs = new LinkedHashSet<>();
        Map<String, Double> aggregateShares = new LinkedHashMap<>();
        double sum = 0.0;
        for (int index = 0; index < array.size(); index++) {
            String itemPath = path + "[" + index + "]";
            JsonObject item = object(array.get(index), itemPath);
            exactFields(item, Set.of("roleRef", "growthForm", "targetShare"), itemPath,
                    CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID);
            String roleRef = string(item, "roleRef", itemPath + ".roleRef");
            FillRole role = roles.get(roleRef);
            CityBlueprint.RegionGrowthForm growthForm = enumValue(item, "growthForm",
                    CityBlueprint.RegionGrowthForm.class, itemPath);
            double target = share(item, "targetShare", itemPath + ".targetShare", false);
            if (role == null || target >= 1.0 || !role.allowedGrowthForms().contains(growthForm)) {
                fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID, itemPath,
                        "Each ordered example stage must use a declared role, an allowed growthForm, "
                                + "and a targetShare in (0,1).");
            }
            refs.add(roleRef);
            aggregateShares.merge(roleRef, target, Double::sum);
            result.add(new ExampleRoleShare(roleRef, growthForm, target));
            sum += target;
        }
        if (!refs.contains(primaryRoleRef)) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID, path,
                    "Example roleShares must contain primaryRoleRef.");
        }
        if (!refs.equals(roles.keySet())) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID, path,
                    "Example region stages must contain every declared roleRef.");
        }
        requireUnitSum(sum, path, "Example targetShare values");
        for (FillRole role : roles.values()) {
            double aggregate = aggregateShares.getOrDefault(role.roleRef(), 0.0);
            if (!Double.isFinite(aggregate) || aggregate < role.minShare() || aggregate > role.maxShare()) {
                fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID, path,
                        "Example stages for role " + role.roleRef()
                                + " must aggregate within its configured share range.");
            }
        }
        return List.copyOf(result);
    }

    private static List<ExampleContentWeight> exampleContentWeights(JsonArray array, String path,
                                                                     Set<String> allowedContentRefs) {
        List<ExampleContentWeight> result = new ArrayList<>();
        Set<String> refs = new LinkedHashSet<>();
        for (int index = 0; index < array.size(); index++) {
            String itemPath = path + "[" + index + "]";
            JsonObject item = object(array.get(index), itemPath);
            exactFields(item, Set.of("contentRef", "weight"), itemPath,
                    CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID);
            String contentRef = string(item, "contentRef", itemPath + ".contentRef");
            double weight = positiveNumber(item, "weight", itemPath + ".weight");
            if (!allowedContentRefs.contains(contentRef) || !refs.add(contentRef)) {
                fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID, itemPath,
                        "Example contentRef must be unique and present in allowedContentRefs.");
            }
            result.add(new ExampleContentWeight(contentRef, weight));
        }
        return List.copyOf(result);
    }

    private static ParcelStyle parcelStyle(JsonObject item, String path) {
        Set<String> fields = Set.of("parcelCountMin", "parcelCountMax", "parcelAreaMinBlocks",
                "parcelAreaMaxBlocks", "minSharedBoundaryBlocks");
        exactFields(item, fields, path, CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID);
        int countMin = positiveInt(item, "parcelCountMin", path);
        int countMax = positiveInt(item, "parcelCountMax", path);
        int areaMin = positiveInt(item, "parcelAreaMinBlocks", path);
        int areaMax = positiveInt(item, "parcelAreaMaxBlocks", path);
        int minSharedBoundary = positiveInt(item, "minSharedBoundaryBlocks", path);
        if (countMin > countMax || areaMin > areaMax || minSharedBoundary > areaMin) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID, path,
                    "Parcel style min values must not exceed their matching max values.");
        }
        return new ParcelStyle(countMin, countMax, areaMin, areaMax, minSharedBoundary);
    }

    private static Set<String> structureRefs(JsonArray array, CityTemplateCatalog templates) {
        Set<String> result = new LinkedHashSet<>();
        for (int index = 0; index < array.size(); index++) {
            String path = "$.structureRefs[" + index + "]";
            JsonObject item = object(array.get(index), path);
            exactFields(item, Set.of("structureRef", "templateCandidates"), path,
                    CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID);
            String ref = string(item, "structureRef", path + ".structureRef");
            duplicate(result, ref, path);
            JsonArray candidates = array(item, "templateCandidates");
            if (candidates.isEmpty()) {
                fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID,
                        path + ".templateCandidates", "templateCandidates must not be empty.");
            }
            for (int candidateIndex = 0; candidateIndex < candidates.size(); candidateIndex++) {
                String candidatePath = path + ".templateCandidates[" + candidateIndex + "]";
                JsonObject candidate = object(candidates.get(candidateIndex), candidatePath);
                exactFields(candidate, Set.of("templateId", "variantId"), candidatePath,
                        CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID);
                try {
                    templates.requireTemplate(string(candidate, "templateId", candidatePath + ".templateId"),
                            string(candidate, "variantId", candidatePath + ".variantId"));
                } catch (CityTemplateCatalog.CatalogException exception) {
                    fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_TEMPLATE_UNKNOWN,
                            candidatePath, exception.getMessage());
                }
            }
        }
        return Set.copyOf(result);
    }

    private static Set<String> profileRefs(JsonArray array, String refField, Set<String> fields, String path,
                                           EntryValidator validator) {
        return refs(array, refField, fields, path, validator);
    }

    private static Set<String> refs(JsonArray array, String refField, Set<String> fields, String path,
                                    EntryValidator validator) {
        Set<String> result = new LinkedHashSet<>();
        for (int index = 0; index < array.size(); index++) {
            String itemPath = path + "[" + index + "]";
            JsonObject item = object(array.get(index), itemPath);
            exactFields(item, fields, itemPath, CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID);
            String ref = string(item, refField, itemPath + "." + refField);
            duplicate(result, ref, itemPath);
            validator.validate(item);
        }
        return Set.copyOf(result);
    }

    private static void enumString(JsonObject item, String field, Set<String> values) {
        String value = string(item, field, "$[]." + field);
        if (!values.contains(value)) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID, "$[]." + field,
                    "Unsupported value " + value + "; expected one of " + values);
        }
    }

    private static void duplicate(Set<String> values, String value, String path) {
        if (!values.add(value)) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_DUPLICATE_REF, path,
                    "Duplicate reference: " + value);
        }
    }

    private static void exactFields(JsonObject object, Set<String> fields, String path,
                                    CityBlueprintReasonCode code) {
        for (String key : object.keySet()) {
            if (!fields.contains(key)) fail(code, path + "." + key, "Unknown field: " + key);
        }
        for (String key : fields) {
            if (!object.has(key) || object.get(key).isJsonNull()) {
                fail(code, path + "." + key, "Required field is missing: " + key);
            }
        }
    }

    private static JsonObject object(JsonElement element, String path) {
        if (element == null || !element.isJsonObject()) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID, path, "Expected object.");
        }
        return element.getAsJsonObject();
    }

    private static JsonArray array(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID, "$." + key,
                    key + " array is required.");
        }
        return object.getAsJsonArray(key);
    }

    private static String string(JsonObject object, String key, String path) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.getAsJsonPrimitive(key).isString() || object.get(key).getAsString().isBlank()) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID, path,
                    "A non-empty string is required.");
        }
        return object.get(key).getAsString().trim();
    }

    private static String stringElement(JsonElement element, String path) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
                || element.getAsString().isBlank()) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID, path,
                    "A non-empty string is required.");
        }
        return element.getAsString().trim();
    }

    private static List<String> stringList(JsonArray array, String path, boolean allowEmpty) {
        if (!allowEmpty && array.isEmpty()) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID, path,
                    "Array must not be empty.");
        }
        List<String> result = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (int index = 0; index < array.size(); index++) {
            String value = stringElement(array.get(index), path + "[" + index + "]");
            if (!unique.add(value)) {
                fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID,
                        path + "[" + index + "]", "Values must be unique.");
            }
            result.add(value);
        }
        return List.copyOf(result);
    }

    private static boolean bool(JsonObject object, String key, String path) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.getAsJsonPrimitive(key).isBoolean()) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID,
                    path, "A boolean is required.");
        }
        return object.get(key).getAsBoolean();
    }

    private static int positiveInt(JsonObject object, String key, String path) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.getAsJsonPrimitive(key).isNumber()) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID,
                    path + "." + key, "A positive integer is required.");
        }
        double raw = object.get(key).getAsDouble();
        if (raw != Math.rint(raw) || raw <= 0 || raw > Integer.MAX_VALUE) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID,
                    path + "." + key, "A positive integer is required.");
        }
        return (int) raw;
    }

    private static int nonNegativeInt(JsonObject object, String key, String path) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.getAsJsonPrimitive(key).isNumber()) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID,
                    path + "." + key, "A non-negative integer is required.");
        }
        double raw = object.get(key).getAsDouble();
        if (raw != Math.rint(raw) || raw < 0 || raw > Integer.MAX_VALUE) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID,
                    path + "." + key, "A non-negative integer is required.");
        }
        return (int) raw;
    }

    private static double probability(JsonObject object, String key, String path) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.getAsJsonPrimitive(key).isNumber()) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID,
                    path + "." + key, "A probability from 0 to 1 is required.");
        }
        double raw = object.get(key).getAsDouble();
        if (!Double.isFinite(raw) || raw < 0.0 || raw > 1.0) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID,
                    path + "." + key, "A probability from 0 to 1 is required.");
        }
        return raw;
    }

    private static double share(JsonObject object, String key, String path, boolean allowZero) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.getAsJsonPrimitive(key).isNumber()) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID, path,
                    "A share from 0 to 1 is required.");
        }
        double raw = object.get(key).getAsDouble();
        if (!Double.isFinite(raw) || raw > 1.0 || (allowZero ? raw < 0.0 : raw <= 0.0)) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID, path,
                    allowZero ? "A share from 0 to 1 is required."
                            : "A share greater than 0 and at most 1 is required.");
        }
        return raw;
    }

    private static double positiveNumber(JsonObject object, String key, String path) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.getAsJsonPrimitive(key).isNumber()) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID, path,
                    "A positive finite number is required.");
        }
        double raw = object.get(key).getAsDouble();
        if (!Double.isFinite(raw) || raw <= 0.0) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID, path,
                    "A positive finite number is required.");
        }
        return raw;
    }

    private static void requireUnitSum(double sum, String path, String label) {
        if (Math.abs(sum - 1.0) > 1.0e-6) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID, path,
                    label + " must sum to 1.0.");
        }
    }

    private static String blockId(JsonObject object, String key, String path) {
        String value = string(object, key, path + "." + key);
        if (!LandUseSurfaceSettings.isValidBlockId(value)) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID,
                    path + "." + key, "Invalid block ID: " + value);
        }
        return value;
    }

    private static <E extends Enum<E>> E enumValue(JsonObject object, String key, Class<E> type, String path) {
        String value = string(object, key, path + "." + key);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID,
                    path + "." + key, "Unsupported value: " + value);
            return null;
        }
    }

    private static void fail(CityBlueprintReasonCode code, String path, String message) {
        throw new CityBlueprintContractException(code, path, message);
    }

    public record SurfaceRecipe(
            String surfaceRecipeRef,
            boolean surfacePrintEnabled,
            boolean autoConnectDefault,
            SurfaceAlgorithm surfaceAlgorithm,
            String surfaceBlockId,
            String cropBlockId,
            String channelBankBlockId,
            String channelWaterBlockId,
            String channelBankOverlayBlockId,
            String boundaryBlockId,
            int fieldBeforeBlocks,
            int channelWidthBlocks,
            int fieldAfterBlocks) {
        public SurfaceRecipe {
            if (surfaceRecipeRef == null || surfaceRecipeRef.isBlank() || surfaceAlgorithm == null) {
                throw new IllegalArgumentException("surfaceRecipeRef and surfaceAlgorithm are required");
            }
            boolean contour = surfacePrintEnabled && surfaceAlgorithm == SurfaceAlgorithm.CONTOUR_BANDS;
            if (!surfacePrintEnabled && (autoConnectDefault || surfaceBlockId != null || cropBlockId != null
                    || channelBankBlockId != null || channelWaterBlockId != null
                    || channelBankOverlayBlockId != null || boundaryBlockId != null)) {
                throw new IllegalArgumentException("A disabled surface recipe cannot contain print materials");
            }
            if (surfacePrintEnabled && (surfaceBlockId == null || surfaceBlockId.isBlank())) {
                throw new IllegalArgumentException("An enabled surface recipe requires surfaceBlockId");
            }
            if (contour && (cropBlockId == null || channelBankBlockId == null || channelWaterBlockId == null
                    || channelBankOverlayBlockId == null)) {
                throw new IllegalArgumentException("CONTOUR_BANDS requires complete crop and channel materials");
            }
            if (contour && (fieldBeforeBlocks <= 0 || channelWidthBlocks <= 0 || fieldAfterBlocks <= 0)) {
                throw new IllegalArgumentException("CONTOUR_BANDS requires positive configured band widths");
            }
            if (!contour && (fieldBeforeBlocks != 0 || channelWidthBlocks != 0 || fieldAfterBlocks != 0)) {
                throw new IllegalArgumentException("Only CONTOUR_BANDS may configure band widths");
            }
        }
    }

    public enum SurfaceAlgorithm { UNIFORM, CONTOUR_BANDS }

    public record FoundationProfile(
            String foundationProfileRef,
            String landUseRuleRef,
            String surfaceRecipeRef,
            int structureMarginBlocks,
            int closeRadiusBlocks,
            int maxJoinDistanceBlocks) {
        public FoundationProfile {
            if (foundationProfileRef == null || foundationProfileRef.isBlank()
                    || landUseRuleRef == null || landUseRuleRef.isBlank()
                    || surfaceRecipeRef == null || surfaceRecipeRef.isBlank()) {
                throw new IllegalArgumentException("Foundation profile references are required");
            }
            if (structureMarginBlocks < 0 || structureMarginBlocks > closeRadiusBlocks
                    || closeRadiusBlocks > maxJoinDistanceBlocks) {
                throw new IllegalArgumentException("Foundation distances must satisfy 0 <= margin <= close <= join");
            }
        }
    }

    public record LandscapeProfile(
            String landscapeProfileRef,
            LandscapeType landscapeType,
            String landUseRuleRef,
            String surfaceRecipeRef,
            int baseAreaSmall,
            int baseAreaMedium,
            int baseAreaLarge,
            CityBlueprint.OutdoorMembership membership,
            ParcelStyle parcelStyle) {
        public LandscapeProfile {
            if (landscapeProfileRef == null || landscapeProfileRef.isBlank()
                    || landscapeType == null || landUseRuleRef == null || landUseRuleRef.isBlank()
                    || surfaceRecipeRef == null || surfaceRecipeRef.isBlank() || membership == null) {
                throw new IllegalArgumentException("Landscape profile references and enums are required");
            }
            if (baseAreaSmall <= 0 || baseAreaSmall > baseAreaMedium || baseAreaMedium > baseAreaLarge) {
                throw new IllegalArgumentException("Landscape base areas must be positive and monotonic");
            }
            if (parcelStyle == null) throw new IllegalArgumentException("parcelStyle is required");
        }

        public int baseArea(CityBlueprint.ExtentClass extentClass) {
            return switch (extentClass) {
                case SMALL -> baseAreaSmall;
                case MEDIUM -> baseAreaMedium;
                case LARGE -> baseAreaLarge;
            };
        }
    }

    public record ParcelStyle(
            int parcelCountMin,
            int parcelCountMax,
            int parcelAreaMinBlocks,
            int parcelAreaMaxBlocks,
            int minSharedBoundaryBlocks) {
        public ParcelStyle {
            if (parcelCountMin <= 0 || parcelCountMin > parcelCountMax
                    || parcelAreaMinBlocks <= 0 || parcelAreaMinBlocks > parcelAreaMaxBlocks
                    || minSharedBoundaryBlocks <= 0 || minSharedBoundaryBlocks > parcelAreaMinBlocks) {
                throw new IllegalArgumentException("Invalid parcel style ranges");
            }
        }
    }

    public record LandscapeFillProfile(
            String fillProfileRef,
            String displayName,
            String visualIntent,
            FillAlgorithm algorithm,
            RelayOrigin relayOrigin,
            Set<LandscapeType> compatibleLandscapeTypes,
            String primaryRoleRef,
            Map<String, FillRole> roles,
            Set<String> allowedContentRefs,
            List<FillExample> examples) {
        public LandscapeFillProfile {
            compatibleLandscapeTypes = Set.copyOf(compatibleLandscapeTypes);
            roles = Map.copyOf(roles);
            allowedContentRefs = Set.copyOf(allowedContentRefs);
            examples = List.copyOf(examples);
        }
    }

    public record FillRole(
            String roleRef,
            MaterialRole materialRole,
            Set<CityBlueprint.RegionGrowthForm> allowedGrowthForms,
            CityBlueprint.RegionGrowthForm defaultGrowthForm,
            double minShare,
            double maxShare,
            double defaultShare) {
        public FillRole {
            allowedGrowthForms = Set.copyOf(allowedGrowthForms);
        }
    }

    public record FillExample(
            String exampleId,
            String description,
            List<ExampleRoleShare> roleShares,
            List<ExampleContentWeight> contentWeights) {
        public FillExample {
            roleShares = List.copyOf(roleShares);
            contentWeights = List.copyOf(contentWeights);
        }
    }

    public record ExampleRoleShare(
            String roleRef,
            CityBlueprint.RegionGrowthForm growthForm,
            double targetShare) {
    }

    public record ExampleContentWeight(String contentRef, double weight) {
    }

    public enum LandscapeType { FARMLAND, COMMON_GREEN, WOODLAND, MEADOW, POND }

    public enum FillAlgorithm { SINGLE_SOURCE_REGION_RELAY }

    public enum RelayOrigin { PARENT_REGION_LOCAL_BOUNDARY }

    public enum MaterialRole { PRIMARY_CONTENT, BANK, WATER, GROUND }

    @FunctionalInterface
    private interface EntryValidator {
        void validate(JsonObject item);
    }
}
