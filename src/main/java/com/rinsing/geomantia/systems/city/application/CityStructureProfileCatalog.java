package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class CityStructureProfileCatalog {
    private static final Pattern RESOURCE_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Set<String> LEGACY_SEMANTIC_FIELDS = Set.of(
            "functionTags", "function_tags", "function_candidates", "functionAffinity", "function_affinity",
            "styleTags", "style_tags", "styleAffinity", "style_affinity",
            "placementTags", "placement_tags", "placementAffinity", "placement_affinity",
            "usageTags", "usage_tags", "usageAffinity", "usage_affinity",
            "qualityTags", "quality_tags");

    private CityStructureProfileCatalog() {
    }

    public static ImportedCatalog importCatalog(Path baseDirectory, JsonObject source) throws IOException {
        if (source == null) {
            throw new IllegalArgumentException("terrasenseProfileSource object is required.");
        }
        String sourceType = stringValue(source, "sourceType", "debug_catalog");
        String catalogMode = stringValue(source, "catalogMode",
                sourceType.equals("debug_catalog") ? "debug" : "official");
        if ("compat".equals(catalogMode) || "c3_5_compat_catalog".equals(sourceType)) {
            throw legacyFlow("compat TerraSense catalog is removed; use StructureProfile.jsonl/debug catalog.");
        }
        Path inputPath = switch (sourceType) {
            case "structure_profile_jsonl" -> resolve(baseDirectory, requiredString(source, "profilePath"));
            case "debug_catalog" -> resolve(baseDirectory, requiredString(source, "debugCatalogPath"));
            default -> throw new IllegalArgumentException("Unsupported TerraSense sourceType: " + sourceType);
        };
        if (!Files.exists(inputPath)) {
            throw new IllegalArgumentException("TerraSense profile source not found: " + inputPath);
        }

        List<String> warnings = new ArrayList<>();
        List<String> needsReview = new ArrayList<>();
        List<StructureProfile> profiles = sourceType.equals("structure_profile_jsonl")
                ? readJsonl(inputPath, catalogMode, warnings, needsReview)
                : readDebugCatalog(inputPath, catalogMode, warnings, needsReview);

        Map<String, StructureProfile> byId = new LinkedHashMap<>();
        for (StructureProfile profile : profiles) {
            if (byId.putIfAbsent(profile.semanticProfileId(), profile) != null) {
                warnings.add("Duplicate semantic profile ignored after first occurrence: "
                        + profile.semanticProfileId());
            }
        }
        JsonObject normalizedSource = source.deepCopy();
        normalizedSource.addProperty("resolvedPath", inputPath.toAbsolutePath().toString());
        return new ImportedCatalog(catalogMode, normalizedSource, List.copyOf(byId.values()), warnings, needsReview);
    }

    public static IllegalArgumentException legacyFlow(String message) {
        return new IllegalArgumentException("LEGACY_CITY_FUNCTION_ZONE_FLOW_REMOVED: " + message);
    }

    private static List<StructureProfile> readJsonl(Path path, String catalogMode, List<String> warnings,
                                                    List<String> needsReview) throws IOException {
        List<StructureProfile> profiles = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                profileFromJson(JsonParser.parseString(line).getAsJsonObject(), catalogMode,
                        "StructureProfile line " + lineNo, warnings, needsReview).ifPresent(profiles::add);
            }
        }
        return profiles;
    }

    private static List<StructureProfile> readDebugCatalog(Path path, String catalogMode, List<String> warnings,
                                                           List<String> needsReview) throws IOException {
        JsonObject obj = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        if (!"debug".equals(stringValue(obj, "catalogMode", catalogMode))) {
            throw new IllegalArgumentException("debug_catalog source must reference catalogMode=debug.");
        }
        List<StructureProfile> profiles = new ArrayList<>();
        int index = 0;
        for (JsonElement elem : requiredArray(obj, "structures")) {
            index++;
            if (!elem.isJsonObject()) {
                continue;
            }
            profileFromJson(elem.getAsJsonObject(), "debug",
                    "debug catalog entry " + index, warnings, needsReview).ifPresent(profiles::add);
        }
        return profiles;
    }

    private static java.util.Optional<StructureProfile> profileFromJson(JsonObject obj, String catalogMode,
                                                                        String sourceRef, List<String> warnings,
                                                                        List<String> needsReview) {
        JsonObject curation = objectValue(obj, "curation");
        rejectLegacySemanticFields(obj, curation, sourceRef);
        String semanticProfileId = firstString(obj, "semanticProfileId", "semantic_profile_id",
                "structureId", "structure_id", "id");
        if (!RESOURCE_ID.matcher(semanticProfileId).matches()) {
            needsReview.add(sourceRef + ": invalid or missing semanticProfileId");
            return java.util.Optional.empty();
        }
        if (!"debug".equals(catalogMode)) {
            String reviewState = firstString(obj, "reviewState", "review_state");
            if (!"approved".equalsIgnoreCase(reviewState)) {
                needsReview.add(semanticProfileId + ": review_state is not approved");
                return java.util.Optional.empty();
            }
        }

        List<String> functionTerms = firstStrings(obj, curation, "functionTerms", "function_terms", "function");
        if (functionTerms.isEmpty()) {
            needsReview.add(semanticProfileId + ": missing TerraSense function terms");
            return java.util.Optional.empty();
        }
        List<String> qualityTerms = firstStrings(obj, curation, "qualityTerms", "quality_terms", "quality");
        if (qualityTerms.stream().anyMatch(term -> term.equals("reject") || term.equals("quality.reject"))) {
            needsReview.add(semanticProfileId + ": rejected by quality terms");
            return java.util.Optional.empty();
        }

        List<String> styleTerms = firstStrings(obj, curation, "styleTerms", "style_terms", "style");
        List<String> placementTerms = firstStrings(obj, curation, "placementTerms", "placement_terms", "placement");
        List<String> usageTerms = firstStrings(obj, curation, "usageTerms", "usage_terms", "usage");
        List<String> templateRoleTerms = firstStrings(obj, curation, "templateRoleTerms", "template_role_terms",
                "template_role");
        List<String> semanticTerms = firstStrings(obj, curation, "semanticTerms", "semantic_terms");
        if (semanticTerms.isEmpty()) {
            semanticTerms = semanticTerms(functionTerms, styleTerms, placementTerms, usageTerms,
                    templateRoleTerms, qualityTerms);
        }

        return java.util.Optional.of(new StructureProfile(
                semanticProfileId,
                firstString(obj, "sourceProfileRef", "source_profile_ref", "profileRef"),
                semanticTerms,
                functionTerms,
                styleTerms,
                placementTerms,
                usageTerms,
                templateRoleTerms,
                qualityTerms,
                catalogMode));
    }

    private static void rejectLegacySemanticFields(JsonObject obj, JsonObject nested, String sourceRef) {
        for (String field : LEGACY_SEMANTIC_FIELDS) {
            if ((obj != null && obj.has(field)) || (nested != null && nested.has(field))) {
                throw legacyFlow(sourceRef + " uses legacy semantic field " + field
                        + "; export TerraSense semanticTerms/functionTerms instead.");
            }
        }
    }

    private static Path resolve(Path baseDirectory, String raw) {
        Path path = Path.of(raw);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return (baseDirectory == null ? Path.of(".") : baseDirectory).resolve(path).normalize();
    }

    private static List<String> semanticTerms(List<String>... groups) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (List<String> group : groups) {
            terms.addAll(group);
        }
        return List.copyOf(terms);
    }

    private static String firstString(JsonObject obj, String... keys) {
        for (String key : keys) {
            String value = stringValue(obj, key, "");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static List<String> firstStrings(JsonObject obj, JsonObject nested, String... keys) {
        for (String key : keys) {
            JsonArray array = firstArray(obj, key);
            if (!array.isEmpty()) {
                return strings(array);
            }
            array = firstArray(nested, key);
            if (!array.isEmpty()) {
                return strings(array);
            }
        }
        return List.of();
    }

    private static JsonArray firstArray(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj != null && obj.has(key) && obj.get(key).isJsonArray()) {
                return obj.getAsJsonArray(key);
            }
        }
        return new JsonArray();
    }

    private static List<String> strings(JsonArray array) {
        List<String> values = new ArrayList<>();
        for (JsonElement elem : array) {
            if (!elem.isJsonNull()) {
                values.add(elem.getAsString().toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(values);
    }

    private static String requiredString(JsonObject obj, String key) {
        String value = stringValue(obj, key, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException(key + " is required.");
        }
        return value;
    }

    private static String stringValue(JsonObject obj, String key, String defaultValue) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : defaultValue;
    }

    private static JsonObject objectValue(JsonObject obj, String key) {
        return obj != null && obj.has(key) && obj.get(key).isJsonObject() ? obj.getAsJsonObject(key) : new JsonObject();
    }

    private static JsonArray requiredArray(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonArray()) {
            throw new IllegalArgumentException(key + " array is required.");
        }
        return obj.getAsJsonArray(key);
    }

    private static JsonArray stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    public record ImportedCatalog(String catalogMode, JsonObject source, List<StructureProfile> profiles,
                                  List<String> warnings, List<String> needsReview) {
        public ImportedCatalog {
            profiles = List.copyOf(profiles);
            warnings = List.copyOf(warnings);
            needsReview = List.copyOf(needsReview);
        }

        public Map<String, StructureProfile> byId() {
            Map<String, StructureProfile> result = new LinkedHashMap<>();
            for (StructureProfile profile : profiles) {
                result.put(profile.semanticProfileId(), profile);
            }
            return result;
        }

        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("schemaVersion", "city_semantic_profile_catalog.v0.1");
            obj.addProperty("catalogMode", catalogMode);
            obj.add("source", source.deepCopy());
            JsonArray array = new JsonArray();
            profiles.forEach(profile -> array.add(profile.asSemanticJson()));
            obj.add("semanticProfiles", array);
            JsonObject quality = new JsonObject();
            quality.addProperty("passed", needsReview.isEmpty());
            quality.addProperty("score", needsReview.isEmpty() ? 100 : 70);
            quality.add("warnings", stringArray(warnings));
            quality.add("needsReview", stringArray(needsReview));
            obj.add("quality", quality);
            return obj;
        }
    }

    public record StructureProfile(String semanticProfileId, String sourceProfileRef,
                                   List<String> semanticTerms, List<String> functionTerms,
                                   List<String> styleTerms, List<String> placementTerms, List<String> usageTerms,
                                   List<String> templateRoleTerms, List<String> qualityTerms,
                                   String catalogMode) {
        public StructureProfile {
            semanticTerms = List.copyOf(semanticTerms);
            functionTerms = List.copyOf(functionTerms);
            styleTerms = List.copyOf(styleTerms);
            placementTerms = List.copyOf(placementTerms);
            usageTerms = List.copyOf(usageTerms);
            templateRoleTerms = List.copyOf(templateRoleTerms);
            qualityTerms = List.copyOf(qualityTerms);
        }

        public JsonObject asSemanticJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("semanticProfileId", semanticProfileId);
            obj.addProperty("sourceProfileRef", sourceProfileRef);
            obj.add("semanticTerms", stringArray(semanticTerms));
            obj.add("functionTerms", stringArray(functionTerms));
            obj.add("styleTerms", stringArray(styleTerms));
            obj.add("placementTerms", stringArray(placementTerms));
            obj.add("usageTerms", stringArray(usageTerms));
            obj.add("templateRoleTerms", stringArray(templateRoleTerms));
            obj.add("qualityTerms", stringArray(qualityTerms));
            obj.addProperty("catalogMode", catalogMode);
            return obj;
        }
    }

    public record Footprint(int widthBlocks, int depthBlocks, int heightBlocks) {
        public boolean valid() {
            return widthBlocks > 0 && depthBlocks > 0;
        }

        public BlockBounds centeredAt(int x, int z, String rotation) {
            int width = widthBlocks;
            int depth = depthBlocks;
            String rot = rotation == null ? "NONE" : rotation.toUpperCase(Locale.ROOT);
            if (rot.equals("CLOCKWISE_90") || rot.equals("COUNTERCLOCKWISE_90")) {
                width = depthBlocks;
                depth = widthBlocks;
            }
            int minX = x - width / 2;
            int minZ = z - depth / 2;
            return new BlockBounds(minX, minZ, minX + width - 1, minZ + depth - 1);
        }

        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("widthBlocks", widthBlocks);
            obj.addProperty("depthBlocks", depthBlocks);
            obj.addProperty("heightBlocks", heightBlocks);
            return obj;
        }
    }

}
