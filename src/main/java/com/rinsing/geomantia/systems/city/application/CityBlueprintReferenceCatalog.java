package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprintContractException;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprintReasonCode;

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
        Set<String> surfaceDetailProfileRefs) {

    public static final String SCHEMA_VERSION = "city_blueprint_reference_catalog.v0.2";
    private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "structureRefs", "fillPools",
            "algorithmProfiles", "compositionProfiles", "styleProfiles", "roadProfiles",
            "surfaceDetailProfiles");

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
        if (structures.isEmpty() || pools.isEmpty() || algorithms.isEmpty() || compositions.isEmpty()
                || styles.isEmpty() || roads.isEmpty() || surfaces.isEmpty()) {
            fail(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID, "$",
                    "Every reference catalog namespace must contain at least one entry.");
        }
        return new CityBlueprintReferenceCatalog(root.deepCopy(), structures, pools, algorithms,
                Map.copyOf(algorithmsByRef), compositions,
                styles, roads, surfaces);
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

    private static void fail(CityBlueprintReasonCode code, String path, String message) {
        throw new CityBlueprintContractException(code, path, message);
    }

    @FunctionalInterface
    private interface EntryValidator {
        void validate(JsonObject item);
    }
}
