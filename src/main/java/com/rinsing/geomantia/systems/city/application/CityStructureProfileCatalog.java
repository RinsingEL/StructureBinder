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
    public static final String SCHEMA = "city_semantic_profile_catalog";
    public static final String SOURCE_SCHEMA = "terrasense_structure_profile_source";
    private static final Pattern RESOURCE_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Set<String> LEGACY_SEMANTIC_FIELDS = Set.of(
            "semanticTerms", "semantic_terms",
            "placementTerms", "placement_terms", "placement",
            "usageTerms", "usage_terms", "usage",
            "templateRoleTerms", "template_role_terms", "template_role",
            "qualityTerms", "quality_terms", "quality",
            "terrainTerms", "terrain_terms", "terrain",
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
        SourceDescriptor descriptor = validateSourceDescriptor(source);
        String sourceType = descriptor.sourceType();
        String catalogMode = descriptor.catalogMode();
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
        if (descriptor.vocabularySnapshotRequired()) {
            Path vocabularyPath = resolve(baseDirectory, requiredString(source, "vocabularySnapshotPath"));
            if (!Files.isRegularFile(vocabularyPath)) {
                throw new IllegalArgumentException("TerraSense vocabulary snapshot not found: " + vocabularyPath);
            }
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
        String reviewState = firstString(obj, "reviewState", "review_state");
        if ("debug".equals(catalogMode) && reviewState.isBlank()) {
            reviewState = "unreviewed";
        }
        if (!"debug".equals(catalogMode)) {
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
        List<String> planningRoleTerms = firstStrings(obj, curation,
                "planningRoleTerms", "planning_role_terms", "planning_role");
        List<CityStructureTerrainMode> terrainModes = terrainModes(obj, curation, sourceRef);
        if (!"debug".equals(catalogMode) && terrainModes.isEmpty()) {
            throw new IllegalArgumentException("CITY_STRUCTURE_TERRAIN_MODE_REQUIRED: "
                    + semanticProfileId + " must declare at least one terrainModes value.");
        }
        List<String> styleTerms = firstStrings(obj, curation, "styleTerms", "style_terms", "style");

        return java.util.Optional.of(new StructureProfile(
                semanticProfileId,
                firstString(obj, "sourceProfileRef", "source_profile_ref", "profileRef"),
                reviewState,
                functionTerms,
                planningRoleTerms,
                terrainModes,
                styleTerms,
                catalogMode));
    }

    private static void rejectLegacySemanticFields(JsonObject obj, JsonObject nested, String sourceRef) {
        for (String field : LEGACY_SEMANTIC_FIELDS) {
            if ((obj != null && obj.has(field)) || (nested != null && nested.has(field))) {
                throw legacyFlow(sourceRef + " uses legacy semantic field " + field
                        + "; export TerraSense functionTerms/planningRoleTerms/styleTerms and City terrainModes instead.");
            }
        }
    }

    private static SourceDescriptor validateSourceDescriptor(JsonObject source) {
        String schema = requiredString(source, "schema");
        String sourceType = requiredString(source, "sourceType");
        String catalogMode = requiredString(source, "catalogMode");
        if (!SOURCE_SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("Unsupported TerraSense profile source schema: " + schema);
        }
        if (Set.of("official", "debug").contains(catalogMode)) {
            if (!Set.of("official", "debug").contains(catalogMode)) {
                throw new IllegalArgumentException("Unsupported catalogMode for " + schema + ": " + catalogMode);
            }
            if (!("structure_profile_jsonl".equals(sourceType) || "debug_catalog".equals(sourceType))) {
                throw new IllegalArgumentException("Unsupported TerraSense sourceType: " + sourceType);
            }
            if ("debug_catalog".equals(sourceType) != "debug".equals(catalogMode)) {
                throw new IllegalArgumentException("debug_catalog requires catalogMode=debug.");
            }
            return new SourceDescriptor(sourceType, catalogMode, false);
        }
        if ("binder".equals(catalogMode)) {
            if (!"binder".equals(catalogMode)) {
                throw new IllegalArgumentException("Unsupported catalogMode for " + schema + ": " + catalogMode);
            }
            if (!"structure_profile_jsonl".equals(sourceType)) {
                throw new IllegalArgumentException("Unsupported TerraSense sourceType: " + sourceType);
            }
            if (!"single_template".equals(requiredString(source, "sampleType"))) {
                throw new IllegalArgumentException("binder source requires sampleType=single_template.");
            }
            if (booleanValue(source, "allowDebugUnapproved", true)) {
                throw new IllegalArgumentException("binder source requires allowDebugUnapproved=false.");
            }
            requiredString(source, "terrasenseRunId");
            requiredString(source, "vocabularySnapshotPath");
            return new SourceDescriptor(sourceType, catalogMode, true);
        }
        throw new IllegalArgumentException("Unsupported catalogMode for " + schema + ": " + catalogMode);
    }

    private static Path resolve(Path baseDirectory, String raw) {
        Path path = Path.of(raw);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return (baseDirectory == null ? Path.of(".") : baseDirectory).resolve(path).normalize();
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

    private static List<CityStructureTerrainMode> terrainModes(JsonObject obj, JsonObject nested,
                                                                String sourceRef) {
        JsonArray values = firstArray(obj, "terrainModes", "terrain_modes");
        if (values.isEmpty()) values = firstArray(nested, "terrainModes", "terrain_modes");
        List<CityStructureTerrainMode> modes = new ArrayList<>();
        Set<CityStructureTerrainMode> unique = new LinkedHashSet<>();
        for (JsonElement element : values) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("CITY_STRUCTURE_TERRAIN_MODE_INVALID: "
                        + sourceRef + ": terrainModes entries must be strings.");
            }
            CityStructureTerrainMode mode = CityStructureTerrainMode.parse(element.getAsString(), sourceRef);
            if (!unique.add(mode)) {
                throw new IllegalArgumentException("CITY_STRUCTURE_TERRAIN_MODE_DUPLICATE: "
                        + sourceRef + ": duplicate terrainMode " + mode.name());
            }
            modes.add(mode);
        }
        return List.copyOf(modes);
    }

    private static boolean booleanValue(JsonObject obj, String key, boolean defaultValue) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsBoolean() : defaultValue;
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
            obj.addProperty("schema", SCHEMA);
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

    public record StructureProfile(String semanticProfileId, String sourceProfileRef, String reviewState,
                                   List<String> functionTerms, List<String> planningRoleTerms,
                                   List<CityStructureTerrainMode> terrainModes, List<String> styleTerms,
                                   String catalogMode) {
        public StructureProfile {
            functionTerms = List.copyOf(functionTerms);
            planningRoleTerms = List.copyOf(planningRoleTerms);
            terrainModes = List.copyOf(terrainModes);
            styleTerms = List.copyOf(styleTerms);
        }

        public JsonObject asSemanticJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("semanticProfileId", semanticProfileId);
            obj.addProperty("sourceProfileRef", sourceProfileRef);
            obj.addProperty("reviewState", reviewState);
            obj.add("functionTerms", stringArray(functionTerms));
            obj.add("planningRoleTerms", stringArray(planningRoleTerms));
            JsonArray modes = new JsonArray();
            terrainModes.forEach(mode -> modes.add(mode.name()));
            obj.add("terrainModes", modes);
            obj.add("styleTerms", stringArray(styleTerms));
            obj.addProperty("catalogMode", catalogMode);
            return obj;
        }
    }

    private record SourceDescriptor(String sourceType, String catalogMode,
                                    boolean vocabularySnapshotRequired) {
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
