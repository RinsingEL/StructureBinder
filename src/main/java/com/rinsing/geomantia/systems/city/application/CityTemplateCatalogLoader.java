package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class CityTemplateCatalogLoader {
    public static final String DEFAULT_FILE_NAME = "template_catalog.json";

    public CityTemplateCatalog load(Path path) throws IOException {
        Path catalogFile = Files.isDirectory(path) ? path.resolve(DEFAULT_FILE_NAME) : path;
        try {
            return load(Files.readString(catalogFile));
        } catch (JsonParseException | IllegalStateException ex) {
            throw new CityTemplateCatalog.CatalogException("CITY_TEMPLATE_CATALOG_JSON_INVALID",
                    "Invalid template catalog JSON: " + catalogFile, ex);
        }
    }

    public CityTemplateCatalog load(String json) {
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                throw fail("CITY_TEMPLATE_CATALOG_ROOT_INVALID", "Catalog root must be an object.");
            }
            return load(root.getAsJsonObject());
        } catch (JsonParseException | IllegalStateException ex) {
            throw new CityTemplateCatalog.CatalogException("CITY_TEMPLATE_CATALOG_JSON_INVALID",
                    "Invalid template catalog JSON.", ex);
        }
    }

    public CityTemplateCatalog load(JsonObject root) {
        if (root == null) {
            throw fail("CITY_TEMPLATE_CATALOG_ROOT_INVALID", "Catalog root must not be null.");
        }
        rejectUnknownFields(root, Set.of("schemaVersion", "templates"), "catalog");
        String schema = requiredString(root, "schemaVersion");
        if (!CityTemplateCatalog.SCHEMA.equals(schema)) {
            throw fail("CITY_TEMPLATE_CATALOG_SCHEMA_UNSUPPORTED", "Unsupported schemaVersion: " + schema);
        }
        JsonArray entries = requiredArray(root, "templates");
        List<CityTemplateCatalog.Template> templates = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (int index = 0; index < entries.size(); index++) {
            JsonElement element = entries.get(index);
            if (!element.isJsonObject()) {
                throw fail("CITY_TEMPLATE_CATALOG_TEMPLATE_INVALID",
                        "templates[" + index + "] must be an object.");
            }
            CityTemplateCatalog.Template template = parseTemplate(element.getAsJsonObject(), index);
            String key = template.templateId() + "\u0000" + template.variantId();
            if (!keys.add(key)) {
                throw fail("CITY_TEMPLATE_CATALOG_DUPLICATE_VARIANT",
                        "Duplicate templateId/variantId: " + template.templateId() + " / "
                                + template.variantId());
            }
            templates.add(template);
        }
        if (templates.isEmpty()) {
            throw fail("CITY_TEMPLATE_CATALOG_TEMPLATES_EMPTY", "templates must not be empty.");
        }
        return new CityTemplateCatalog(templates);
    }

    private static CityTemplateCatalog.Template parseTemplate(JsonObject object, int index) {
        rejectUnknownFields(object, Set.of(
                "buildingSemantic", "style", "templateId", "templateRef", "nbtFile", "contentHash", "variant",
                "variantId",
                "width", "height", "depth", "size", "dimensions", "rawSize", "allowedRotations", "allowedMirrors",
                "roadEntrances", "terrainPosePolicy", "supportPolicy", "clearanceBlocks"),
                "templates[" + index + "]");
        String buildingSemantic = requiredString(object, "buildingSemantic");
        String style = requiredString(object, "style");
        String templateId = requiredString(object, "templateId");
        String nbtFile = object.has("templateRef")
                ? requiredString(object, "templateRef") : requiredString(object, "nbtFile");
        String contentHash = requiredString(object, "contentHash");
        String variantId = object.has("variant")
                ? requiredString(object, "variant") : requiredString(object, "variantId");
        CityTemplatePlacementGeometry.Size size = parseSize(object, index);
        List<CityTemplatePlacementGeometry.Rotation> rotations = parseRotations(object, index);
        List<CityTemplatePlacementGeometry.Mirror> mirrors = parseMirrors(object, index);
        List<CityTemplatePlacementGeometry.RoadEntrance> entrances = parseEntrances(object, size, index);
        String terrainPosePolicy = requiredString(object, "terrainPosePolicy");
        String supportPolicy = requiredString(object, "supportPolicy");
        int clearanceBlocks = requiredInt(object, "clearanceBlocks", "CITY_TEMPLATE_CATALOG_CLEARANCE_INVALID");
        if (clearanceBlocks < 0) {
            throw fail("CITY_TEMPLATE_CATALOG_CLEARANCE_INVALID", "clearanceBlocks must be non-negative.");
        }
        try {
            return new CityTemplateCatalog.Template(buildingSemantic, style, templateId, nbtFile, contentHash,
                    variantId, size, rotations, mirrors, entrances, terrainPosePolicy, supportPolicy,
                    clearanceBlocks);
        } catch (CityTemplateCatalog.CatalogException ex) {
            throw ex;
        } catch (IllegalArgumentException ex) {
            throw fail("CITY_TEMPLATE_CATALOG_TEMPLATE_INVALID", "Invalid template at index " + index, ex);
        }
    }

    private static CityTemplatePlacementGeometry.Size parseSize(JsonObject object, int index) {
        JsonObject size = null;
        if (object.has("size")) {
            size = requiredObject(object, "size", "CITY_TEMPLATE_CATALOG_SIZE_INVALID");
            rejectUnknownFields(size, Set.of("width", "height", "depth"),
                    "templates[" + index + "].size");
        } else if (object.has("dimensions")) {
            size = requiredObject(object, "dimensions", "CITY_TEMPLATE_CATALOG_SIZE_INVALID");
        } else if (object.has("rawSize")) {
            size = requiredObject(object, "rawSize", "CITY_TEMPLATE_CATALOG_SIZE_INVALID");
            rejectUnknownFields(size, Set.of("width", "height", "depth"),
                    "templates[" + index + "].dimensions");
        }
        int width = size == null ? requiredInt(object, "width", "CITY_TEMPLATE_CATALOG_SIZE_INVALID")
                : requiredInt(size, "width", "CITY_TEMPLATE_CATALOG_SIZE_INVALID");
        int height = size == null ? requiredInt(object, "height", "CITY_TEMPLATE_CATALOG_SIZE_INVALID")
                : requiredInt(size, "height", "CITY_TEMPLATE_CATALOG_SIZE_INVALID");
        int depth = size == null ? requiredInt(object, "depth", "CITY_TEMPLATE_CATALOG_SIZE_INVALID")
                : requiredInt(size, "depth", "CITY_TEMPLATE_CATALOG_SIZE_INVALID");
        if (width <= 0 || height <= 0 || depth <= 0) {
            throw fail("CITY_TEMPLATE_CATALOG_SIZE_INVALID",
                    "Template dimensions must be positive at index " + index + ".");
        }
        return new CityTemplatePlacementGeometry.Size(width, height, depth);
    }

    private static List<CityTemplatePlacementGeometry.Rotation> parseRotations(JsonObject object, int index) {
        JsonArray values = requiredArray(object, "allowedRotations");
        List<CityTemplatePlacementGeometry.Rotation> result = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            String value = enumString(values.get(i), "rotation", index, i);
            try {
                CityTemplatePlacementGeometry.Rotation rotation =
                        CityTemplatePlacementGeometry.Rotation.valueOf(value);
                if (!result.contains(rotation)) {
                    result.add(rotation);
                }
            } catch (IllegalArgumentException ex) {
                throw fail("CITY_TEMPLATE_CATALOG_ROTATION_INVALID",
                        "Invalid rotation " + value + " at templates[" + index + "]", ex);
            }
        }
        if (result.isEmpty()) {
            throw fail("CITY_TEMPLATE_CATALOG_ROTATION_INVALID", "allowedRotations must not be empty.");
        }
        return result;
    }

    private static List<CityTemplatePlacementGeometry.Mirror> parseMirrors(JsonObject object, int index) {
        JsonArray values = requiredArray(object, "allowedMirrors");
        List<CityTemplatePlacementGeometry.Mirror> result = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            String value = enumString(values.get(i), "mirror", index, i);
            try {
                CityTemplatePlacementGeometry.Mirror mirror =
                        CityTemplatePlacementGeometry.Mirror.valueOf(value);
                if (!result.contains(mirror)) {
                    result.add(mirror);
                }
            } catch (IllegalArgumentException ex) {
                throw fail("CITY_TEMPLATE_CATALOG_MIRROR_INVALID",
                        "Invalid mirror " + value + " at templates[" + index + "]", ex);
            }
        }
        if (result.isEmpty()) {
            throw fail("CITY_TEMPLATE_CATALOG_MIRROR_INVALID", "allowedMirrors must not be empty.");
        }
        return result;
    }

    private static List<CityTemplatePlacementGeometry.RoadEntrance> parseEntrances(JsonObject object,
                                                                                      CityTemplatePlacementGeometry.Size size,
                                                                                      int index) {
        JsonArray values = requiredArray(object, "roadEntrances");
        List<CityTemplatePlacementGeometry.RoadEntrance> result = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            if (!values.get(i).isJsonObject()) {
                throw fail("CITY_TEMPLATE_CATALOG_ENTRANCE_INVALID",
                        "roadEntrances[" + i + "] must be an object at templates[" + index + "].");
            }
            JsonObject entry = values.get(i).getAsJsonObject();
            rejectUnknownFields(entry, Set.of("entranceId", "id", "x", "z", "position", "direction"),
                    "templates[" + index + "].roadEntrances[" + i + "]");
            String entranceId = entry.has("entranceId") ? requiredString(entry, "entranceId")
                    : entry.has("id") ? requiredString(entry, "id") : "entrance_" + String.format("%04d", i);
            JsonObject position = entry.has("position")
                    ? requiredObject(entry, "position", "CITY_TEMPLATE_CATALOG_ENTRANCE_INVALID") : entry;
            int x = requiredInt(position, "x", "CITY_TEMPLATE_CATALOG_ENTRANCE_INVALID");
            int z = requiredInt(position, "z", "CITY_TEMPLATE_CATALOG_ENTRANCE_INVALID");
            if (x < 0 || x >= size.width() || z < 0 || z >= size.depth()) {
                throw fail("CITY_TEMPLATE_CATALOG_ENTRANCE_OUT_OF_BOUNDS",
                        "Road entrance is outside template dimensions at templates[" + index + "] index " + i);
            }
            String directionValue = requiredString(entry, "direction");
            CityTemplatePlacementGeometry.Direction direction;
            try {
                direction = CityTemplatePlacementGeometry.Direction.valueOf(directionValue);
            } catch (IllegalArgumentException ex) {
                throw fail("CITY_TEMPLATE_CATALOG_DIRECTION_INVALID",
                        "Invalid road entrance direction: " + directionValue, ex);
            }
            result.add(new CityTemplatePlacementGeometry.RoadEntrance(entranceId, new BlockPoint(x, z), direction));
        }
        return result;
    }

    private static String enumString(JsonElement element, String kind, int index, int valueIndex) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw fail("CITY_TEMPLATE_CATALOG_" + kind.toUpperCase() + "_INVALID",
                    "Invalid " + kind + " at templates[" + index + "] index " + valueIndex);
        }
        return element.getAsString();
    }

    private static JsonArray requiredArray(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            throw fail("CITY_TEMPLATE_CATALOG_FIELD_MISSING", "Required array field is missing: " + key);
        }
        return object.getAsJsonArray(key);
    }

    private static JsonObject requiredObject(JsonObject object, String key, String reasonCode) {
        if (!object.has(key) || !object.get(key).isJsonObject()) {
            throw fail(reasonCode, "Expected object field: " + key);
        }
        return object.getAsJsonObject(key);
    }

    private static String requiredString(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.getAsJsonPrimitive(key).isString()
                || object.get(key).getAsString().isBlank()) {
            throw fail("CITY_TEMPLATE_CATALOG_FIELD_MISSING", "Required string field is missing: " + key);
        }
        return object.get(key).getAsString().trim();
    }

    private static int requiredInt(JsonObject object, String key, String reasonCode) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.getAsJsonPrimitive(key).isNumber()) {
            throw fail(reasonCode, "Expected integer field: " + key);
        }
        try {
            return object.get(key).getAsBigDecimal().intValueExact();
        } catch (ArithmeticException ex) {
            throw fail(reasonCode, "Expected integer field: " + key, ex);
        }
    }

    private static void rejectUnknownFields(JsonObject object, Set<String> allowed, String owner) {
        for (String key : object.keySet()) {
            if (!allowed.contains(key)) {
                throw fail("CITY_TEMPLATE_CATALOG_FIELD_UNKNOWN", "Unknown field " + owner + "." + key);
            }
        }
    }

    private static CityTemplateCatalog.CatalogException fail(String reasonCode, String message) {
        return new CityTemplateCatalog.CatalogException(reasonCode, message);
    }

    private static CityTemplateCatalog.CatalogException fail(String reasonCode, String message, Throwable cause) {
        return new CityTemplateCatalog.CatalogException(reasonCode, message, cause);
    }
}
