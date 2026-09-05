package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.*;
import java.util.*;

/** A designer's view of a frozen context, not a second compiler input or a new truth source. */
final class CityPlanningDecisionView {
    private CityPlanningDecisionView() { }

    static JsonObject from(JsonObject source) {
        JsonObject view = source.deepCopy();
        view.addProperty("schema", "city_blueprint_decision_context.v0.1");
        JsonObject d3 = source.getAsJsonObject("d3ReviewPackage");
        if (d3 != null && d3.has("landformPatches")) {
            JsonObject terrain = d3.deepCopy();
            Map<String, List<JsonObject>> byType = new TreeMap<>();
            for (JsonElement item : d3.getAsJsonArray("landformPatches")) {
                JsonObject patch = item.getAsJsonObject();
                byType.computeIfAbsent(string(patch, "landformType"), ignored -> new ArrayList<>()).add(patch);
            }
            JsonArray candidates = new JsonArray();
            JsonObject counts = new JsonObject();
            byType.forEach((type, patches) -> {
                counts.addProperty(type, patches.size());
                patches.sort(Comparator.comparingDouble((JsonObject patch) -> -number(patch, "areaBlocks"))
                        .thenComparing(patch -> string(patch, "landformPatchId")));
                patches.stream().limit(1).forEach(patch -> candidates.add(pick(patch,
                        "landformPatchId", "mapLabel", "landformType", "areaBlocks", "centerBlock", "blockBounds",
                        "metricsSummary", "landformTags", "overlayTags", "biomeSummary")));
            });
            terrain.add("landformPatches", candidates);
            terrain.add("availablePatchCountsByType", counts);
            terrain.addProperty("candidateCoverage", "Largest candidate per terrain type, not the complete inventory. "
                    + "Use patch_explorer_show_candidates with the existing patchReviewEvidence.sessionId to compare "
                    + "more or page any terrain type. Geometry and all remaining candidates stay in sourceD3Ref.");
            terrain.remove("aiPromptContext"); // Legacy D3 district instructions are not the Blueprint design contract.
            terrain.remove("debugRefs");
            view.add("d3ReviewPackage", terrain);
        }
        JsonObject snapshot = source.getAsJsonObject("catalogSnapshot");
        if (snapshot != null && snapshot.has("templateCatalog") && snapshot.has("referenceCatalog")) {
            JsonObject catalog = snapshot.deepCopy();
            JsonObject semanticCatalog = catalog.getAsJsonObject("structureCatalog");
            if (semanticCatalog != null && semanticCatalog.has("semanticProfiles")) {
                for (JsonElement profile : semanticCatalog.getAsJsonArray("semanticProfiles")) {
                    // The frozen snapshot reference already carries provenance; this repeated URI is not a choice.
                    profile.getAsJsonObject().remove("sourceProfileRef");
                }
            }
            Map<String, JsonObject> templates = new HashMap<>();
            for (JsonElement entry : snapshot.getAsJsonObject("templateCatalog").getAsJsonArray("templates")) {
                JsonObject template = entry.getAsJsonObject();
                templates.put(string(template, "templateId"), template);
            }
            JsonObject references = snapshot.getAsJsonObject("referenceCatalog").deepCopy();
            JsonArray refs = new JsonArray();
            JsonObject geometry = new JsonObject();
            for (JsonElement entry : references.getAsJsonArray("structureRefs")) {
                JsonObject reference = entry.getAsJsonObject();
                String ref = string(reference, "structureRef");
                refs.add(ref);
                JsonArray sizes = new JsonArray();
                for (JsonElement candidate : reference.getAsJsonArray("templateCandidates")) {
                    JsonObject template = templates.get(string(candidate.getAsJsonObject(), "templateId"));
                    if (template == null) continue; // Import validation remains authoritative.
                    JsonObject size = template.getAsJsonObject("rawSize");
                    JsonArray dimensions = new JsonArray();
                    for (String key : List.of("width", "height", "depth")) dimensions.add(size.get(key));
                    dimensions.add(template.has("clearanceBlocks") ? template.get("clearanceBlocks").getAsInt() : 0);
                    sizes.add(dimensions);
                }
                geometry.add(ref, sizes);
            }
            references.add("structureRefs", refs);
            catalog.add("referenceCatalog", references);
            catalog.remove("templateCatalog");
            catalog.add("structureGeometry", geometry);
            catalog.addProperty("geometryAuthority", "Variant resolution, rotations, entrance coordinates and hashes "
                    + "are frozen in catalogSnapshotRef and owned by the compiler. structureGeometry rows contain "
                    + "[width,height,depth,clearanceBlocks] for each available variant, in blocks.");
            // Every authored function/style/role and every selectable pool/profile remains intact.
            view.add("catalogSnapshot", catalog);
        }
        return view;
    }

    private static JsonObject pick(JsonObject source, String... keys) {
        JsonObject result = new JsonObject();
        for (String key : keys) if (source.has(key)) result.add(key, source.get(key).deepCopy());
        return result;
    }
    private static String string(JsonObject source, String key) {
        return source.has(key) ? source.get(key).getAsString() : "";
    }
    private static double number(JsonObject source, String key) {
        return source.has(key) ? source.get(key).getAsDouble() : 0;
    }
}
