package com.rinsing.geomantia.systems.city.infrastructure.dressing;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationContentCatalog.CatalogException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loads style profiles from config/geomantia/city_decoration/styles/*.json. */
public final class CityDecorationStyleProfileCatalogLoader {
    private static final String STYLES_DIR = "styles";
    private static final Set<String> PROFILE_FIELDS = Set.of("schemaVersion", "styleProfileId", "mappings");
    private static final Set<String> MAPPING_FIELDS = Set.of("semanticRef", "variants");
    private static final Set<String> VARIANT_FIELDS = Set.of("contentRef", "weight");

    public CityDecorationStyleProfileCatalog load(Path catalogRoot, CityDecorationContentCatalog contentCatalog) {
        if (catalogRoot == null || contentCatalog == null) {
            throw fail("CITY_DECORATION_STYLE_CATALOG_ROOT_REQUIRED", "catalogRoot and contentCatalog are required.");
        }
        Path root = catalogRoot.toAbsolutePath().normalize();
        Path stylesRoot = root.resolve(STYLES_DIR).normalize();
        if (!stylesRoot.startsWith(root)) {
            throw fail("CITY_DECORATION_STYLE_CATALOG_PATH_INVALID", "styles directory escapes catalog root.");
        }
        if (!Files.exists(stylesRoot)) {
            return new CityDecorationStyleProfileCatalog(Map.of());
        }
        if (!Files.isDirectory(stylesRoot)) {
            throw fail("CITY_DECORATION_STYLE_CATALOG_PATH_INVALID", "styles is not a directory: " + stylesRoot);
        }

        Map<String, CityDecorationStyleProfileCatalog.StyleProfile> profiles = new LinkedHashMap<>();
        try (var files = Files.list(stylesRoot)) {
            for (Path path : files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(file -> file.getFileName().toString())).toList()) {
                CityDecorationStyleProfileCatalog.StyleProfile profile = parse(path, contentCatalog);
                String expectedFileName = profile.styleProfileId() + ".json";
                if (!expectedFileName.equals(path.getFileName().toString())) {
                    throw fail("CITY_DECORATION_STYLE_PROFILE_FILE_NAME_MISMATCH",
                            "Expected " + expectedFileName + " but found " + path.getFileName());
                }
                if (profiles.putIfAbsent(profile.styleProfileId(), profile) != null) {
                    throw fail("CITY_DECORATION_STYLE_PROFILE_ID_DUPLICATE",
                            "Duplicate styleProfileId: " + profile.styleProfileId());
                }
            }
        } catch (CatalogException ex) {
            throw ex;
        } catch (IOException ex) {
            throw fail("CITY_DECORATION_STYLE_CATALOG_READ_FAILED", "Cannot read styles directory: " + stylesRoot, ex);
        }
        return new CityDecorationStyleProfileCatalog(profiles);
    }

    private CityDecorationStyleProfileCatalog.StyleProfile parse(Path path,
                                                                  CityDecorationContentCatalog contentCatalog) {
        JsonObject source;
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw fail("CITY_DECORATION_STYLE_PROFILE_INVALID", "Profile root must be an object: " + path);
            }
            source = parsed.getAsJsonObject();
        } catch (CatalogException ex) {
            throw ex;
        } catch (Exception ex) {
            throw fail("CITY_DECORATION_STYLE_PROFILE_READ_FAILED", "Cannot read style profile: " + path, ex);
        }
        requireOnly(source, PROFILE_FIELDS, "profile");
        if (!CityDecorationStyleProfileCatalog.SCHEMA.equals(requiredString(source, "schemaVersion"))) {
            throw fail("CITY_DECORATION_STYLE_PROFILE_SCHEMA_UNSUPPORTED", "Unsupported profile schema: " + path);
        }
        String styleProfileId = requiredString(source, "styleProfileId");
        Map<String, CityDecorationStyleProfileCatalog.Mapping> mappings = new LinkedHashMap<>();
        for (JsonElement element : requiredArray(source, "mappings")) {
            if (!element.isJsonObject()) {
                throw fail("CITY_DECORATION_STYLE_MAPPING_INVALID", "mappings entries must be objects: " + styleProfileId);
            }
            JsonObject mappingJson = element.getAsJsonObject();
            requireOnly(mappingJson, MAPPING_FIELDS, "mapping");
            String semanticRef = requiredString(mappingJson, "semanticRef");
            List<CityDecorationStyleProfileCatalog.Variant> variants = new ArrayList<>();
            for (JsonElement variantElement : requiredArray(mappingJson, "variants")) {
                if (!variantElement.isJsonObject()) {
                    throw fail("CITY_DECORATION_STYLE_VARIANT_INVALID", "variants entries must be objects: " + semanticRef);
                }
                JsonObject variantJson = variantElement.getAsJsonObject();
                requireOnly(variantJson, VARIANT_FIELDS, "variant");
                String contentRef = requiredString(variantJson, "contentRef");
                contentCatalog.requireContent(contentRef);
                variants.add(new CityDecorationStyleProfileCatalog.Variant(contentRef,
                        requiredPositiveNumber(variantJson, "weight")));
            }
            CityDecorationStyleProfileCatalog.Mapping mapping =
                    new CityDecorationStyleProfileCatalog.Mapping(semanticRef, variants);
            if (mappings.putIfAbsent(semanticRef, mapping) != null) {
                throw fail("CITY_DECORATION_STYLE_SEMANTIC_REF_DUPLICATE",
                        "Duplicate semanticRef in " + styleProfileId + ": " + semanticRef);
            }
        }
        if (mappings.isEmpty()) {
            throw fail("CITY_DECORATION_STYLE_MAPPINGS_REQUIRED", "mappings must not be empty: " + styleProfileId);
        }
        return new CityDecorationStyleProfileCatalog.StyleProfile(styleProfileId,
                profileHash(styleProfileId, mappings), mappings);
    }

    private static String profileHash(String styleProfileId,
                                      Map<String, CityDecorationStyleProfileCatalog.Mapping> mappings) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder normalized = new StringBuilder(CityDecorationStyleProfileCatalog.SCHEMA)
                    .append('\n').append(styleProfileId);
            mappings.values().stream().sorted(Comparator.comparing(
                    CityDecorationStyleProfileCatalog.Mapping::semanticRef)).forEach(mapping -> {
                normalized.append('\n').append(mapping.semanticRef());
                mapping.variants().stream().sorted(Comparator.comparing(
                        CityDecorationStyleProfileCatalog.Variant::contentRef)).forEach(variant -> normalized
                        .append('\n').append(variant.contentRef()).append('=').append(variant.weight()));
            });
            return "sha256:" + HexFormat.of().formatHex(digest.digest(
                    normalized.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", ex);
        }
    }

    private static void requireOnly(JsonObject object, Set<String> allowed, String owner) {
        for (String key : object.keySet()) {
            if (!allowed.contains(key)) {
                throw fail("CITY_DECORATION_STYLE_FIELD_UNKNOWN", "Unknown " + owner + " field: " + key);
            }
        }
    }

    private static String requiredString(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.getAsJsonPrimitive(key).isString() || object.get(key).getAsString().isBlank()) {
            throw fail("CITY_DECORATION_STYLE_FIELD_REQUIRED", "Required string field: " + key);
        }
        return object.get(key).getAsString().trim();
    }

    private static JsonArray requiredArray(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            throw fail("CITY_DECORATION_STYLE_FIELD_REQUIRED", "Required array field: " + key);
        }
        return object.getAsJsonArray(key);
    }

    private static double requiredPositiveNumber(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.getAsJsonPrimitive(key).isNumber()) {
            throw fail("CITY_DECORATION_STYLE_FIELD_REQUIRED", "Required positive number field: " + key);
        }
        double value = object.get(key).getAsDouble();
        if (!Double.isFinite(value) || value <= 0.0D) {
            throw fail("CITY_DECORATION_STYLE_FIELD_INVALID", key + " must be a positive finite number.");
        }
        return value;
    }

    private static CatalogException fail(String reasonCode, String message) {
        return new CatalogException(reasonCode, message);
    }

    private static CatalogException fail(String reasonCode, String message, Throwable cause) {
        return new CatalogException(reasonCode, message, cause);
    }
}
