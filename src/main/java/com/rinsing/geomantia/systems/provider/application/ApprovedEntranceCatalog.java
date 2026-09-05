package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.application.CityTemplateCatalogLoader;
import java.util.*;

/** Author-owned entrance overlay, applied before the planning context is frozen. Never guesses ports. */
final class ApprovedEntranceCatalog {
    private ApprovedEntranceCatalog() {}

    static JsonObject apply(JsonObject catalog, JsonObject annotations) {
        if (!"terrasense_approved_entrances.v1".equals(string(annotations, "schema"))) throw fail("SCHEMA", "catalog");
        var validated = new CityTemplateCatalogLoader().load(catalog);
        Map<String, JsonObject> byId = new HashMap<>();
        for (var value : annotations.getAsJsonArray("structures")) {
            JsonObject row = value.getAsJsonObject();
            String id = string(row, "structureId");
            if (id.isBlank() || byId.putIfAbsent(id, row) != null) throw fail("DUPLICATE", id);
        }
        JsonObject result = catalog.deepCopy();
        // Preserve the author's template/ref identity, variant, transforms, dimensions and content hash.
        // Source template IDs must match exactly; no filename or hash-only identity fallback.
        for (var value : result.getAsJsonArray("templates")) {
            JsonObject template = value.getAsJsonObject();
            String ref = template.has("templateRef") ? string(template, "templateRef") : string(template, "nbtFile");
            JsonObject row = byId.get(ref);
            if (row == null || !"approved".equals(string(row, "reviewState"))) throw fail("REVIEW_REQUIRED", ref);
            if (!string(row, "contentHash").matches("sha256:[a-f0-9]{64}")
                    || !string(row, "contentHash").equals(string(template, "contentHash"))) throw fail("CONTENT_CHANGED", ref);
            if (!string(row, "captureDigest").matches("sha256:[a-f0-9]{64}")) throw fail("PROVENANCE_REQUIRED", ref);
            var raw = validated.templates().stream().filter(t -> t.templateRef().equals(ref)).findFirst().orElseThrow();
            JsonObject size = row.getAsJsonObject("size");
            if (integer(size, "width") != raw.width() || integer(size, "height") != raw.height() || integer(size, "depth") != raw.depth()) throw fail("SIZE_CHANGED", ref);
            JsonArray ports = row.getAsJsonArray("roadEntrances");
            if (ports == null || ports.size() > 32) throw fail("PORTS_INVALID", ref);
            String intent = string(row, "intent");
            if ("connect".equals(intent)) {
                if (ports.isEmpty()) throw fail("PORT_REQUIRED", ref);
            } else if (!"no_connection".equals(intent) || !ports.isEmpty() || string(row, "note").isBlank()) throw fail("INTENT_INVALID", ref);
            Set<String> ids = new HashSet<>();
            for (var portValue : ports) {
                JsonObject port = portValue.getAsJsonObject();
                String id = string(port, "entranceId");
                if (!id.matches("[a-zA-Z0-9_-]{1,64}") || !ids.add(id)) throw fail("PORT_ID_INVALID", ref);
                int x = integer(port, "x"), z = integer(port, "z");
                boolean outward = switch (string(port, "direction")) {
                    case "WEST" -> x == 0;
                    case "EAST" -> x == raw.width() - 1;
                    case "NORTH" -> z == 0;
                    case "SOUTH" -> z == raw.depth() - 1;
                    default -> false;
                };
                if (x < 0 || x >= raw.width() || z < 0 || z >= raw.depth() || !outward) throw fail("NOT_OUTWARD_BOUNDARY", ref);
            }
            template.add("roadEntrances", ports.deepCopy());
        }
        new CityTemplateCatalogLoader().load(result);
        return result;
    }

    private static int integer(JsonObject object, String key) {
        try { return object.get(key).getAsBigDecimal().intValueExact(); }
        catch (RuntimeException ex) { throw fail("INTEGER_REQUIRED", key); }
    }
    private static String string(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.getAsJsonPrimitive(key).isString() ? o.get(key).getAsString() : "";
    }
    private static IllegalArgumentException fail(String code, String ref) {
        return new IllegalArgumentException("PLANNING_ENTRANCE_" + code + ": " + ref);
    }
}
