package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Read-only structure discovery over the curated TerraSense profile catalog.
 *
 * <p>This deliberately resolves human labels only through the frozen vocabulary snapshot supplied by
 * {@code terrasenseProfileSource}; City does not maintain a second tag dictionary.</p>
 */
public final class CityStructureCatalogQueryService {
    public static final String SCHEMA = "city_structure_catalog_query";
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    public JsonObject query(Path baseDirectory, JsonObject terraSenseProfileSource, JsonObject request)
            throws IOException {
        CityStructureProfileCatalog.ImportedCatalog catalog = CityStructureProfileCatalog.importCatalog(
                baseDirectory, terraSenseProfileSource);
        TermResolver resolver = TermResolver.load(baseDirectory, terraSenseProfileSource, catalog.profiles());

        List<String> allOfTerms = resolver.resolveAll(stringArray(request, "allOfTerms"), "allOfTerms");
        List<String> anyOfTerms = resolver.resolveAll(stringArray(request, "anyOfTerms"), "anyOfTerms");
        List<String> excludeTerms = resolver.resolveAll(stringArray(request, "excludeTerms"), "excludeTerms");
        int limit = limit(request);

        List<CityStructureProfileCatalog.StructureProfile> matches = catalog.profiles().stream()
                .filter(profile -> matches(profile, allOfTerms, anyOfTerms, excludeTerms))
                .sorted(Comparator.comparing(CityStructureProfileCatalog.StructureProfile::semanticProfileId))
                .toList();

        JsonObject response = new JsonObject();
        response.addProperty("schema", SCHEMA);
        response.addProperty("ok", true);
        response.addProperty("readOnly", true);
        response.add("source", catalog.source().deepCopy());
        response.addProperty("catalogMode", catalog.catalogMode());
        response.add("resolvedTerms", resolvedTerms(allOfTerms, anyOfTerms, excludeTerms));
        JsonObject vocabulary = new JsonObject();
        vocabulary.addProperty("available", resolver.vocabularyAvailable());
        if (resolver.vocabularyPath() != null) {
            vocabulary.addProperty("resolvedPath", resolver.vocabularyPath().toString());
        }
        vocabulary.addProperty("termCount", resolver.vocabularyTermCount());
        response.add("vocabulary", vocabulary);
        response.addProperty("matchedCount", matches.size());
        response.addProperty("returnedCount", Math.min(matches.size(), limit));
        response.addProperty("limit", limit);
        JsonArray candidates = new JsonArray();
        matches.stream().limit(limit).forEach(profile -> candidates.add(candidateSummary(profile,
                allOfTerms, anyOfTerms)));
        response.add("candidates", candidates);
        response.add("warnings", stringArray(catalog.warnings()));
        response.add("needsReview", stringArray(catalog.needsReview()));
        return response;
    }

    private static boolean matches(CityStructureProfileCatalog.StructureProfile profile,
                                   List<String> allOfTerms,
                                   List<String> anyOfTerms,
                                   List<String> excludeTerms) {
        Set<String> terms = allTerms(profile);
        return terms.containsAll(allOfTerms)
                && (anyOfTerms.isEmpty() || anyOfTerms.stream().anyMatch(terms::contains))
                && excludeTerms.stream().noneMatch(terms::contains);
    }

    private static JsonObject candidateSummary(CityStructureProfileCatalog.StructureProfile profile,
                                               List<String> allOfTerms,
                                               List<String> anyOfTerms) {
        Set<String> terms = allTerms(profile);
        LinkedHashSet<String> matched = new LinkedHashSet<>();
        allOfTerms.forEach(term -> {
            if (terms.contains(term)) {
                matched.add(term);
            }
        });
        anyOfTerms.forEach(term -> {
            if (terms.contains(term)) {
                matched.add(term);
            }
        });

        JsonObject candidate = new JsonObject();
        candidate.addProperty("semanticProfileId", profile.semanticProfileId());
        candidate.add("matchedCanonicalTerms", stringArray(List.copyOf(matched)));
        JsonObject profileTerms = new JsonObject();
        profileTerms.add("functionTerms", stringArray(profile.functionTerms()));
        profileTerms.add("planningRoleTerms", stringArray(profile.planningRoleTerms()));
        JsonArray terrainModes = new JsonArray();
        profile.terrainModes().forEach(mode -> terrainModes.add(mode.name()));
        profileTerms.add("terrainModes", terrainModes);
        profileTerms.add("styleTerms", stringArray(profile.styleTerms()));
        candidate.add("terms", profileTerms);

        JsonObject profileSource = new JsonObject();
        profileSource.addProperty("sourceProfileRef", profile.sourceProfileRef());
        profileSource.addProperty("reviewState", profile.reviewState());
        profileSource.addProperty("catalogMode", profile.catalogMode());
        candidate.add("profileSource", profileSource);
        return candidate;
    }

    private static Set<String> allTerms(CityStructureProfileCatalog.StructureProfile profile) {
        Set<String> terms = new LinkedHashSet<>();
        terms.addAll(profile.functionTerms());
        terms.addAll(profile.planningRoleTerms());
        terms.addAll(profile.styleTerms());
        return terms;
    }

    private static JsonObject resolvedTerms(List<String> allOfTerms, List<String> anyOfTerms,
                                            List<String> excludeTerms) {
        JsonObject result = new JsonObject();
        result.add("allOfTerms", stringArray(allOfTerms));
        result.add("anyOfTerms", stringArray(anyOfTerms));
        result.add("excludeTerms", stringArray(excludeTerms));
        return result;
    }

    private static int limit(JsonObject request) {
        if (request == null || !request.has("limit") || request.get("limit").isJsonNull()) {
            return DEFAULT_LIMIT;
        }
        int limit;
        try {
            limit = request.get("limit").getAsInt();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("CITY_STRUCTURE_QUERY_LIMIT_INVALID: limit must be an integer.");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("CITY_STRUCTURE_QUERY_LIMIT_INVALID: limit must be between 1 and "
                    + MAX_LIMIT + ".");
        }
        return limit;
    }

    private static List<String> stringArray(JsonObject request, String key) {
        if (request == null || !request.has(key) || request.get(key).isJsonNull()) {
            return List.of();
        }
        if (!request.get(key).isJsonArray()) {
            throw new IllegalArgumentException("CITY_STRUCTURE_QUERY_TERMS_INVALID: " + key
                    + " must be an array of non-empty strings.");
        }
        List<String> values = new ArrayList<>();
        for (JsonElement element : request.getAsJsonArray(key)) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
                    || element.getAsString().isBlank()) {
                throw new IllegalArgumentException("CITY_STRUCTURE_QUERY_TERMS_INVALID: " + key
                        + " must be an array of non-empty strings.");
            }
            values.add(element.getAsString());
        }
        return List.copyOf(values);
    }

    private static JsonArray stringArray(List<String> values) {
        JsonArray result = new JsonArray();
        values.forEach(result::add);
        return result;
    }

    private static Path resolve(Path baseDirectory, String raw) {
        Path path = Path.of(raw);
        return path.isAbsolute() ? path.normalize()
                : (baseDirectory == null ? Path.of(".") : baseDirectory).resolve(path).normalize();
    }

    private static final class TermResolver {
        private final Set<String> knownCanonicalTerms;
        private final Map<String, Set<String>> canonicalByLookup;
        private final Path vocabularyPath;
        private final int vocabularyTermCount;

        private TermResolver(Set<String> knownCanonicalTerms, Map<String, Set<String>> canonicalByLookup,
                             Path vocabularyPath, int vocabularyTermCount) {
            this.knownCanonicalTerms = Set.copyOf(knownCanonicalTerms);
            this.canonicalByLookup = Map.copyOf(canonicalByLookup);
            this.vocabularyPath = vocabularyPath;
            this.vocabularyTermCount = vocabularyTermCount;
        }

        static TermResolver load(Path baseDirectory, JsonObject source,
                                 List<CityStructureProfileCatalog.StructureProfile> profiles) throws IOException {
            Set<String> known = new LinkedHashSet<>();
            for (CityStructureProfileCatalog.StructureProfile profile : profiles) {
                known.addAll(allTerms(profile));
            }
            Map<String, Set<String>> byLookup = new LinkedHashMap<>();
            String rawVocabularyPath = firstString(source, "vocabularySnapshotPath", "vocabulary_snapshot_path");
            if (rawVocabularyPath.isBlank()) {
                return new TermResolver(known, byLookup, null, 0);
            }
            Path vocabularyPath = CityStructureCatalogQueryService.resolve(baseDirectory, rawVocabularyPath);
            if (!Files.exists(vocabularyPath)) {
                throw new IllegalArgumentException("CITY_STRUCTURE_VOCABULARY_SNAPSHOT_NOT_FOUND: "
                        + vocabularyPath);
            }
            JsonElement root = JsonParser.parseString(Files.readString(vocabularyPath));
            JsonArray terms = vocabularyTerms(root);
            int termCount = 0;
            for (JsonElement element : terms) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject term = element.getAsJsonObject();
                String canonical = normalize(firstString(term, "termId", "term_id"));
                if (canonical.isBlank()) {
                    continue;
                }
                String mergeInto = normalize(firstString(term, "mergeInto", "merge_into"));
                String target = mergeInto.isBlank() ? canonical : mergeInto;
                known.add(target);
                termCount++;
                addLookup(byLookup, canonical, target);
                addLookup(byLookup, firstString(term, "label"), target);
                if (term.has("aliases") && term.get("aliases").isJsonArray()) {
                    for (JsonElement alias : term.getAsJsonArray("aliases")) {
                        if (alias.isJsonPrimitive() && alias.getAsJsonPrimitive().isString()) {
                            addLookup(byLookup, alias.getAsString(), target);
                        }
                    }
                }
            }
            return new TermResolver(known, byLookup, vocabularyPath.toAbsolutePath(), termCount);
        }

        List<String> resolveAll(List<String> rawTerms, String fieldName) {
            LinkedHashSet<String> result = new LinkedHashSet<>();
            for (String raw : rawTerms) {
                result.add(resolve(raw, fieldName));
            }
            return List.copyOf(result);
        }

        String resolve(String raw, String fieldName) {
            String normalized = normalize(raw);
            if (knownCanonicalTerms.contains(normalized)) {
                return normalized;
            }
            Set<String> candidates = canonicalByLookup.get(normalized);
            if (candidates == null || candidates.isEmpty()) {
                String vocabularyHint = vocabularyPath == null
                        ? " No vocabularySnapshotPath is available for display labels or aliases."
                        : "";
                throw new IllegalArgumentException("CITY_STRUCTURE_QUERY_TERM_UNRESOLVED: " + fieldName + " term '"
                        + raw + "' is not a known canonical term, vocabulary label, or alias." + vocabularyHint);
            }
            if (candidates.size() > 1) {
                throw new IllegalArgumentException("CITY_STRUCTURE_QUERY_TERM_AMBIGUOUS: " + fieldName + " term '"
                        + raw + "' resolves to " + String.join(", ", candidates) + ". Use a canonical term id.");
            }
            return candidates.iterator().next();
        }

        boolean vocabularyAvailable() {
            return vocabularyPath != null;
        }

        Path vocabularyPath() {
            return vocabularyPath;
        }

        int vocabularyTermCount() {
            return vocabularyTermCount;
        }

        private static JsonArray vocabularyTerms(JsonElement root) {
            if (root.isJsonArray()) {
                return root.getAsJsonArray();
            }
            if (!root.isJsonObject()) {
                throw new IllegalArgumentException("CITY_STRUCTURE_VOCABULARY_SNAPSHOT_INVALID: expected an array or object.");
            }
            JsonObject object = root.getAsJsonObject();
            for (String key : List.of("terms", "vocabulary", "entries", "items")) {
                if (object.has(key) && object.get(key).isJsonArray()) {
                    return object.getAsJsonArray(key);
                }
            }
            throw new IllegalArgumentException("CITY_STRUCTURE_VOCABULARY_SNAPSHOT_INVALID: terms array is required.");
        }

        private static void addLookup(Map<String, Set<String>> byLookup, String raw, String canonical) {
            String lookup = normalize(raw);
            if (!lookup.isBlank()) {
                byLookup.computeIfAbsent(lookup, ignored -> new LinkedHashSet<>()).add(canonical);
            }
        }

        private static String firstString(JsonObject object, String... keys) {
            for (String key : keys) {
                if (object != null && object.has(key) && !object.get(key).isJsonNull()) {
                    String value = object.get(key).getAsString();
                    if (!value.isBlank()) {
                        return value;
                    }
                }
            }
            return "";
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        }
    }
}
