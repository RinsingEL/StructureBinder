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
        Set<String> compositionProfileRefs,
        Set<String> styleProfileRefs,
        Set<String> roadProfileRefs,
        Set<String> surfaceDetailProfileRefs,
        LandUseRuleCatalog landUseRuleCatalog,
        Map<String, SurfaceRecipe> surfaceRecipes,
        Map<String, FoundationProfile> foundationProfiles,
        Map<String, LandscapeProfile> landscapeProfiles) {

    public static final String SCHEMA_VERSION = "city_blueprint_reference_catalog.v0.4";
    private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "structureRefs", "fillPools",
            "algorithmProfiles", "compositionProfiles", "styleProfiles", "roadProfiles",
            "surfaceDetailProfiles", "landUseRuleProfile", "surfaceRecipes", "foundationProfiles",
            "landscapeProfiles");

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
        Set<String> algorithms = profileRefs(array(root, "algorithmProfiles"), "algorithmProfileRef",
                Set.of("algorithmProfileRef", "algorithm"), "$.algorithmProfiles", item -> {
                    enumString(item, "algorithm", Set.of("COMPACT", "GRID", "LINEAR", "COURTYARD",
                            "ORGANIC_COMPACT"));
                    algorithmsByRef.put(string(item, "algorithmProfileRef", "$.algorithmProfiles[].algorithmProfileRef"),
                            string(item, "algorithm", "$.algorithmProfiles[].algorithm"));
                });
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
        if (structures.isEmpty() || pools.isEmpty() || algorithms.isEmpty() || compositions.isEmpty()
            || styles.isEmpty() || roads.isEmpty() || surfaces.isEmpty() || surfaceRecipes.isEmpty()
                || foundationProfiles.isEmpty() || landscapeProfiles.isEmpty()) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID, "$",
                    "Every reference catalog namespace must contain at least one entry.");
        }
        return new CityBlueprintReferenceCatalog(root.deepCopy(), structures, pools, algorithms,
                Map.copyOf(algorithmsByRef), compositions,
                styles, roads, surfaces, landUseRules, Map.copyOf(surfaceRecipes),
                Map.copyOf(foundationProfiles),
                Map.copyOf(landscapeProfiles));
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
                if (algorithm == SurfaceAlgorithm.CONTOUR_BANDS) {
                    expected.addAll(materials);
                    expected.addAll(contourWidths);
                }
            }
            exactFields(item, expected, path, CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID);
            String surface = enabled ? blockId(item, "surfaceBlockId", path) : null;
            String crop = enabled && algorithm == SurfaceAlgorithm.CONTOUR_BANDS
                    ? blockId(item, "cropBlockId", path) : null;
            String bank = enabled && algorithm == SurfaceAlgorithm.CONTOUR_BANDS
                    ? blockId(item, "channelBankBlockId", path) : null;
            String water = enabled && algorithm == SurfaceAlgorithm.CONTOUR_BANDS
                    ? blockId(item, "channelWaterBlockId", path) : null;
            String overlay = enabled && algorithm == SurfaceAlgorithm.CONTOUR_BANDS
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

    private static ParcelStyle parcelStyle(JsonObject item, String path) {
        Set<String> fields = Set.of("coreParcelCountMin", "coreParcelCountMax", "fillParcelCountMin",
                "fillParcelCountMax", "parcelAreaMinBlocks", "parcelAreaMaxBlocks",
                "branchFromExistingChance", "gapMinBlocks", "gapMaxBlocks");
        exactFields(item, fields, path, CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID);
        int coreMin = positiveInt(item, "coreParcelCountMin", path);
        int coreMax = positiveInt(item, "coreParcelCountMax", path);
        int fillMin = nonNegativeInt(item, "fillParcelCountMin", path);
        int fillMax = nonNegativeInt(item, "fillParcelCountMax", path);
        int areaMin = positiveInt(item, "parcelAreaMinBlocks", path);
        int areaMax = positiveInt(item, "parcelAreaMaxBlocks", path);
        double branchChance = probability(item, "branchFromExistingChance", path);
        int gapMin = nonNegativeInt(item, "gapMinBlocks", path);
        int gapMax = nonNegativeInt(item, "gapMaxBlocks", path);
        if (coreMin > coreMax || fillMin > fillMax || areaMin > areaMax || gapMin > gapMax) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID, path,
                    "Parcel style min values must not exceed their matching max values.");
        }
        return new ParcelStyle(coreMin, coreMax, fillMin, fillMax, areaMin, areaMax,
                branchChance, gapMin, gapMax);
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
            if (surfacePrintEnabled && surfaceAlgorithm == SurfaceAlgorithm.UNIFORM
                    && (cropBlockId != null || channelBankBlockId != null || channelWaterBlockId != null
                    || channelBankOverlayBlockId != null)) {
                throw new IllegalArgumentException("UNIFORM forbids contour-only materials");
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
            int coreParcelCountMin,
            int coreParcelCountMax,
            int fillParcelCountMin,
            int fillParcelCountMax,
            int parcelAreaMinBlocks,
            int parcelAreaMaxBlocks,
            double branchFromExistingChance,
            int gapMinBlocks,
            int gapMaxBlocks) {
        public ParcelStyle {
            if (coreParcelCountMin <= 0 || coreParcelCountMin > coreParcelCountMax
                    || fillParcelCountMin < 0 || fillParcelCountMin > fillParcelCountMax
                    || parcelAreaMinBlocks <= 0 || parcelAreaMinBlocks > parcelAreaMaxBlocks
                    || !Double.isFinite(branchFromExistingChance)
                    || branchFromExistingChance < 0.0 || branchFromExistingChance > 1.0
                    || gapMinBlocks < 0 || gapMinBlocks > gapMaxBlocks) {
                throw new IllegalArgumentException("Invalid parcel style ranges");
            }
        }
    }

    public enum LandscapeType { FARMLAND, COMMON_GREEN, WOODLAND, MEADOW, POND }

    @FunctionalInterface
    private interface EntryValidator {
        void validate(JsonObject item);
    }
}
