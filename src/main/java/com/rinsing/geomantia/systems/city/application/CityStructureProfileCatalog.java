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
            if (byId.putIfAbsent(profile.structureId(), profile) != null) {
                warnings.add("Duplicate structure profile ignored after first occurrence: " + profile.structureId());
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
        String structureId = firstString(obj, "structureId", "structure_id", "id");
        if (!RESOURCE_ID.matcher(structureId).matches()) {
            needsReview.add(sourceRef + ": invalid or missing structureId");
            return java.util.Optional.empty();
        }
        if (!"debug".equals(catalogMode)) {
            String reviewState = firstString(obj, "reviewState", "review_state");
            if (!"approved".equalsIgnoreCase(reviewState)) {
                needsReview.add(structureId + ": review_state is not approved");
                return java.util.Optional.empty();
            }
        }

        List<String> functionTerms = firstStrings(obj, curation, "functionTerms", "function_terms", "function");
        if (functionTerms.isEmpty()) {
            needsReview.add(structureId + ": missing TerraSense function terms");
            return java.util.Optional.empty();
        }
        List<String> qualityTerms = firstStrings(obj, curation, "qualityTerms", "quality_terms", "quality");
        if (qualityTerms.stream().anyMatch(term -> term.equals("reject") || term.equals("quality.reject"))) {
            needsReview.add(structureId + ": rejected by quality terms");
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

        String footprintMode = firstString(obj, "footprintMode", "footprint_mode");
        Footprint fixedFootprint = footprint(obj, "fixedFootprint", "fixed_footprint", "footprint");
        AreaRange areaRange = areaRange(obj);
        if (footprintMode.isBlank()) {
            footprintMode = fixedFootprint.valid() ? "fixed_footprint" : "variable_area";
        }
        if ("fixed_footprint".equals(footprintMode) && !fixedFootprint.valid()) {
            needsReview.add(structureId + ": fixed_footprint without reliable fixedFootprint");
            return java.util.Optional.empty();
        }
        if ("variable_area".equals(footprintMode) && !areaRange.startFootprint().valid()) {
            needsReview.add(structureId + ": variable_area without reliable startFootprint");
            return java.util.Optional.empty();
        }

        String profileType = firstString(obj, "profileType", "profile_type");
        if (profileType.isBlank()) {
            profileType = "variable_area".equals(footprintMode) ? "jigsaw_system" : "single";
        }
        int clearance = intValue(obj, "clearanceBlocks", intValue(obj, "clearance_blocks", 0));
        int maxDistance = firstInt(obj, 0, "maxDistanceFromCenter", "max_distance_from_center",
                "jigsawMaxExpansionRadius", "jigsaw_max_expansion_radius");
        List<String> rotations = strings(firstArray(obj, "allowedRotations", "allowed_rotations"));
        if (rotations.isEmpty()) {
            rotations = List.of("NONE");
        }
        String placementKind = firstString(obj, "placementKind", "placement_kind");
        String sampleType = firstString(obj, "sampleType", "sample_type");
        String placementCommand = firstString(obj, "placementCommand", "placement_command");

        return java.util.Optional.of(new StructureProfile(
                structureId,
                firstString(obj, "sourceProfileRef", "source_profile_ref", "profileRef"),
                profileType,
                sampleType,
                placementKind,
                placementCommand,
                footprintMode,
                semanticTerms,
                functionTerms,
                styleTerms,
                placementTerms,
                usageTerms,
                templateRoleTerms,
                qualityTerms,
                fixedFootprint,
                areaRange,
                rotations,
                clearance,
                maxDistance,
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

    private static AreaRange areaRange(JsonObject obj) {
        JsonObject source = objectValue(obj, "expectedAreaRange");
        if (source.size() == 0) {
            source = objectValue(obj, "expected_area_range");
        }
        Footprint start = footprint(source, "startFootprint", "start_footprint", "footprint");
        if (!start.valid()) {
            start = footprint(obj, "startFootprint", "start_footprint");
        }
        int min = intValue(source, "minAreaBlocks", intValue(source, "min_area_blocks", 0));
        int max = intValue(source, "maxAreaBlocks", intValue(source, "max_area_blocks", 0));
        if (min <= 0 && start.valid()) {
            min = start.widthBlocks() * start.depthBlocks();
        }
        if (max <= 0) {
            max = min;
        }
        return new AreaRange(min, max, start);
    }

    private static Footprint footprint(JsonObject obj, String... keys) {
        JsonObject source = new JsonObject();
        for (String key : keys) {
            source = objectValue(obj, key);
            if (source.size() > 0) {
                break;
            }
        }
        if (source.size() == 0 && (obj.has("widthBlocks") || obj.has("width") || obj.has("x"))) {
            source = obj;
        }
        return new Footprint(
                firstInt(source, 0, "widthBlocks", "width", "x"),
                firstInt(source, 0, "depthBlocks", "depth", "z"),
                firstInt(source, 0, "heightBlocks", "height", "y"));
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

    private static int firstInt(JsonObject obj, int defaultValue, String... keys) {
        for (String key : keys) {
            if (obj != null && obj.has(key) && !obj.get(key).isJsonNull()) {
                return obj.get(key).getAsInt();
            }
        }
        return defaultValue;
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

    private static int intValue(JsonObject obj, String key, int defaultValue) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : defaultValue;
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

    private static JsonObject boundsJson(BlockBounds bounds) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minX", bounds.minX());
        obj.addProperty("minZ", bounds.minZ());
        obj.addProperty("maxX", bounds.maxX());
        obj.addProperty("maxZ", bounds.maxZ());
        return obj;
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
                result.put(profile.structureId(), profile);
            }
            return result;
        }

        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("schemaVersion", "city_structure_profile_catalog.v0.2");
            obj.addProperty("catalogMode", catalogMode);
            obj.add("source", source.deepCopy());
            JsonArray array = new JsonArray();
            profiles.forEach(profile -> array.add(profile.asJson()));
            obj.add("structures", array);
            JsonObject quality = new JsonObject();
            quality.addProperty("passed", needsReview.isEmpty());
            quality.addProperty("score", needsReview.isEmpty() ? 100 : 70);
            quality.add("warnings", stringArray(warnings));
            quality.add("needsReview", stringArray(needsReview));
            obj.add("quality", quality);
            return obj;
        }
    }

    public record StructureProfile(String structureId, String sourceProfileRef, String profileType,
                                   String sampleType, String placementKind, String placementCommand,
                                   String footprintMode, List<String> semanticTerms, List<String> functionTerms,
                                   List<String> styleTerms, List<String> placementTerms, List<String> usageTerms,
                                   List<String> templateRoleTerms, List<String> qualityTerms,
                                   Footprint fixedFootprint, AreaRange expectedAreaRange, List<String> allowedRotations,
                                   int clearanceBlocks, int maxDistanceFromCenterBlocks, String catalogMode) {
        public StructureProfile {
            semanticTerms = List.copyOf(semanticTerms);
            functionTerms = List.copyOf(functionTerms);
            styleTerms = List.copyOf(styleTerms);
            placementTerms = List.copyOf(placementTerms);
            usageTerms = List.copyOf(usageTerms);
            templateRoleTerms = List.copyOf(templateRoleTerms);
            qualityTerms = List.copyOf(qualityTerms);
            allowedRotations = List.copyOf(allowedRotations);
        }

        public boolean jigsawLike() {
            return "variable_area".equals(footprintMode) || "jigsaw_system".equals(profileType);
        }

        public Footprint planningFootprint() {
            return fixedFootprint.valid() ? fixedFootprint : expectedAreaRange.startFootprint();
        }

        public int jigsawExpansionRadius(int defaultRadius) {
            if (maxDistanceFromCenterBlocks > 0) {
                return maxDistanceFromCenterBlocks;
            }
            if (expectedAreaRange.maxAreaBlocks() > 0) {
                return Math.max(16, (int) Math.ceil(Math.sqrt(expectedAreaRange.maxAreaBlocks())));
            }
            return defaultRadius;
        }

        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("structureId", structureId);
            obj.addProperty("sourceProfileRef", sourceProfileRef);
            obj.addProperty("profileType", profileType);
            obj.addProperty("sampleType", sampleType);
            obj.addProperty("placementKind", placementKind);
            obj.addProperty("placementCommand", placementCommand);
            obj.addProperty("footprintMode", footprintMode);
            obj.add("semanticTerms", stringArray(semanticTerms));
            obj.add("functionTerms", stringArray(functionTerms));
            obj.add("styleTerms", stringArray(styleTerms));
            obj.add("placementTerms", stringArray(placementTerms));
            obj.add("usageTerms", stringArray(usageTerms));
            obj.add("templateRoleTerms", stringArray(templateRoleTerms));
            obj.add("qualityTerms", stringArray(qualityTerms));
            obj.add("fixedFootprint", fixedFootprint.asJson());
            obj.add("expectedAreaRange", expectedAreaRange.asJson());
            obj.add("allowedRotations", stringArray(allowedRotations));
            obj.addProperty("clearanceBlocks", clearanceBlocks);
            obj.addProperty("maxDistanceFromCenterBlocks", maxDistanceFromCenterBlocks);
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

    public record AreaRange(int minAreaBlocks, int maxAreaBlocks, Footprint startFootprint) {
        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("minAreaBlocks", minAreaBlocks);
            obj.addProperty("maxAreaBlocks", maxAreaBlocks);
            obj.add("startFootprint", startFootprint.asJson());
            return obj;
        }
    }
}
