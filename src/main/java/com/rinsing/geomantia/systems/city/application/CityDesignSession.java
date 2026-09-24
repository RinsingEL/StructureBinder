package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;
import com.rinsing.geomantia.systems.city.domain.model.CityLandformReviewPackage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Persistent, coordinate-free intent and material work preceding a Blueprint preview. */
final class CityDesignSession {
    static final String FILE = "city_design_session.json";
    private CityDesignSession() { }

    static JsonObject current(Path dir, String contextId) throws IOException {
        Path file = dir.resolve(FILE);
        if (Files.isRegularFile(file)) {
            JsonObject value = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (contextId.equals(text(value, "contextId"))) return value;
        }
        JsonObject value = new JsonObject();
        value.addProperty("contextId", contextId);
        value.add("groups", new JsonArray());
        return value;
    }

    static JsonObject submit(Path dir, String contextId, JsonObject request, JsonObject context) throws IOException {
        if (!contextId.equals(text(context, "contextId"))) throw invalid("contextId is stale; prepare the current context.");
        JsonObject state = current(dir, contextId);
        var terrain = CityLandformReviewPackage.fromJson(context.getAsJsonObject("d3ReviewPackage"));
        var patches = new LinkedHashMap<String, com.rinsing.geomantia.systems.city.domain.model.LandformPatchSummary>();
        terrain.landformPatches().forEach(p -> patches.put(p.landformPatchId(), p));
        JsonObject snapshot = context.getAsJsonObject("catalogSnapshot");
        JsonObject catalog = snapshot.getAsJsonObject("referenceCatalog");
        CityTemplateCatalog templates = new CityTemplateCatalogLoader().load(snapshot.getAsJsonObject("templateCatalog"));
        Map<String, JsonObject> refs = index(array(catalog, "structureRefs"), "structureRef");
        Map<String, JsonObject> pools = index(array(catalog, "fillPools"), "poolRef");
        Map<String, JsonObject> groups = index(array(state, "groups"), "groupId");
        if (request.has("designIntent")) {
            JsonObject intent = request.getAsJsonObject("designIntent");
            requireFields(intent, Set.of("groups"));
            JsonArray incoming = requiredArray(intent, "groups");
            if (incoming.isEmpty()) throw invalid("designIntent.groups must contain at least one intended group.");
            Map<String, JsonObject> next = new LinkedHashMap<>(groups);
            Set<String> incomingIds = new HashSet<>();
            for (JsonElement element : incoming) {
                JsonObject group = element.getAsJsonObject();
                requireFields(group, Set.of("groupId", "role", "intent", "preferredPatchRefs"));
                String id = requiredText(group, "groupId"); requiredText(group, "role"); requiredText(group, "intent");
                JsonArray patchRefs = requiredArray(group, "preferredPatchRefs");
                if (patchRefs.isEmpty()) throw invalid(id + ": choose at least one preview patch.");
                Set<String> uniquePatches = new LinkedHashSet<>();
                for (var ref : patchRefs) {
                    if (!patches.containsKey(ref.getAsString())) throw invalid(id + ": unknown patch " + ref);
                    if (!uniquePatches.add(ref.getAsString())) throw invalid(id + ": duplicate patch " + ref + "; list each patch once.");
                }
                if (!incomingIds.add(id)) throw invalid("Duplicate groupId " + id + "; use a unique ID.");
                JsonObject entry = group.deepCopy();
                JsonObject old = groups.get(id);
                if (old != null) {
                    JsonObject oldIntent = old.deepCopy(); oldIntent.remove("materials"); oldIntent.remove("estimate");
                    if (oldIntent.equals(entry)) {
                        if (old.has("materials")) entry.add("materials", old.get("materials").deepCopy());
                        if (old.has("estimate")) entry.add("estimate", old.get("estimate").deepCopy());
                    }
                }
                next.put(id, entry);
            }
            groups = next;
        }
        JsonArray materialResults = new JsonArray();
        if (request.has("materialSelections")) {
            JsonArray selections = requiredArray(request, "materialSelections");
            if (selections.isEmpty()) throw invalid("materialSelections must contain at least one group.");
            for (var element : selections) {
                JsonObject selection = element.getAsJsonObject();
                requireFields(selection, Set.of("groupId", "query", "filters", "limit", "offset", "structureRefs", "fillPoolRefs"));
                String id = requiredText(selection, "groupId");
                for (String key : List.of("structureRefs", "fillPoolRefs")) if (selection.has(key)) requiredArray(selection, key);
                if (selection.has("query") && (!selection.get("query").isJsonPrimitive() || !selection.getAsJsonPrimitive("query").isString()))
                    throw invalid(id + ": query must be a string.");
                JsonObject group = groups.get(id);
                if (group == null) throw invalid("Submit the intent for group " + id + " before selecting materials.");
                LinkedHashSet<String> selected = new LinkedHashSet<>();
                for (var ref : array(selection, "structureRefs")) {
                    if (!refs.containsKey(ref.getAsString())) throw invalid(id + ": unknown structureRef " + ref);
                    selected.add(ref.getAsString());
                }
                for (var pool : array(selection, "fillPoolRefs")) {
                    JsonObject entry = pools.get(pool.getAsString());
                    if (entry == null) throw invalid(id + ": unknown fillPoolRef " + pool);
                    array(entry, "structureRefs").forEach(ref -> selected.add(ref.getAsString()));
                }
                if (!selected.isEmpty() && List.of("filters", "limit", "offset").stream().anyMatch(selection::has))
                    throw invalid(id + ": browse with filters/limit/offset first, then confirm structureRefs/fillPoolRefs separately.");
                JsonObject response = selected.isEmpty() ? CityMaterialCatalogBrowser.browse(snapshot, selection) : new JsonObject();
                response.addProperty("groupId", id);
                response.addProperty("selectionConfirmed", !selected.isEmpty());
                if (!selected.isEmpty()) response.add("candidates", CityMaterialCatalogBrowser.selected(snapshot, selected));
                for (var item : response.getAsJsonArray("candidates")) {
                    JsonObject candidate = item.getAsJsonObject();
                    candidate.add("dimensions", dimensions(refs.get(text(candidate, "structureRef")), templates));
                }
                if (!selected.isEmpty()) {
                    JsonObject estimate = estimate(group, selected, refs, templates, patches, terrain.grid().cellStepBlocks());
                    group.add("materials", selection.deepCopy()); group.add("estimate", estimate);
                    JsonArray countGuide = new JsonArray();
                    var scale = com.rinsing.geomantia.systems.city.domain.model.CityScale.fromContractName(text(context.getAsJsonObject("citySeed"), "theoreticalScale"));
                    if (scale != null) for (String algorithm : List.of("GRID", "LINEAR", "COURTYARD", "CENTER_SYMMETRIC", "COMPACT", "ORGANIC_COMPACT", "CONTIGUOUS")) {
                        JsonObject suggestion = new JsonObject(); suggestion.addProperty("algorithm", algorithm);
                        for (var extent : com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint.ExtentClass.values())
                            suggestion.addProperty(extent.name(), CityBlueprintCompilerService.minimumGroupStructureCount(scale, extent, algorithm));
                        countGuide.add(suggestion);
                    }
                    estimate.add("existingScaleDefaultsByArray", countGuide);
                    response.add("estimate", estimate.deepCopy());
                }
                materialResults.add(response);
            }
        }
        JsonArray saved = new JsonArray(); groups.values().forEach(saved::add); state.add("groups", saved);
        Files.createDirectories(dir);
        Path temp = Files.createTempFile(dir, "design-session-", ".tmp");
        try {
            Files.writeString(temp, CityJson.GSON.toJson(state));
            Files.move(temp, dir.resolve(FILE), StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
        JsonObject result = new JsonObject(); result.addProperty("ok", true); result.addProperty("designInProgress", true);
        result.addProperty("nextAction", "city_submit_d4_blueprint");
        result.add("designSession", state); result.add("materialResults", materialResults);
        if (context.has("scaleDesignTask")) result.add("scaleDesignTask", context.get("scaleDesignTask").deepCopy());
        result.addProperty("instruction", "Follow d4Workflow: select materials for the current district, submit districtDesign, inspect images and assess before complete. Use structureCount and nested arrays to realize the city scale recommendations.");
        return result;
    }

    private static JsonArray dimensions(JsonObject entry, CityTemplateCatalog templates) {
        JsonArray result = new JsonArray();
        for (var element : array(entry, "templateCandidates")) {
            JsonObject candidate = element.getAsJsonObject();
            var template = templates.requireTemplate(text(candidate, "templateId"), text(candidate, "variantId"));
            JsonObject size = candidate.deepCopy();
            size.addProperty("width", template.width()); size.addProperty("height", template.height()); size.addProperty("depth", template.depth());
            size.addProperty("clearance", template.clearanceBlocks()); result.add(size);
        }
        return result;
    }

    private static JsonObject estimate(JsonObject group, Set<String> selected, Map<String, JsonObject> refs,
                                       CityTemplateCatalog templates,
                                       Map<String, com.rinsing.geomantia.systems.city.domain.model.LandformPatchSummary> patches, int cellStepBlocks) {
        List<JsonObject> sizes = new ArrayList<>();
        for (String ref : selected) for (var element : dimensions(refs.get(ref), templates)) {
            sizes.add(element.getAsJsonObject());
        }
        int gap = 0; // Raw NBT capacity; circulation is a separate design choice.
        long lower = 0, upper = 0, area = 0;
        JsonArray patchEstimates = new JsonArray();
        for (var ref : array(group, "preferredPatchRefs")) {
            var patch = patches.get(ref.getAsString()); var box = patch.blockBounds();
            long memberArea = patch.memberCells().isEmpty() ? patch.areaBlocks()
                    : patch.memberCells().stream().distinct().count() * cellStepBlocks * cellStepBlocks;
            long lo = Long.MAX_VALUE, hi = 0;
            for (JsonObject size : sizes) {
                int width = size.get("width").getAsInt(), depth = size.get("depth").getAsInt();
                long count = Math.min(memberArea / ((long) width * depth),
                        (long) (box.widthBlocks() / width) * (box.heightBlocks() / depth));
                lo = Math.min(lo, count); hi = Math.max(hi, count);
            }
            if (sizes.isEmpty()) lo = 0;
            lower += lo; upper += hi; area += memberArea;
            JsonObject value = new JsonObject(); value.addProperty("patchRef", ref.getAsString());
            value.addProperty("capacityUsingLargestTemplate", lo); value.addProperty("capacityUsingSmallestTemplate", hi);
            value.addProperty("terrainAreaBlocks", memberArea);
            value.addProperty("areaBasis", patch.memberCells().isEmpty() ? "envelope_estimate" : "exact_member_cells");
            value.addProperty("widthBlocks", box.widthBlocks()); value.addProperty("depthBlocks", box.heightBlocks());
            patchEstimates.add(value);
        }
        JsonObject value = new JsonObject(); value.addProperty("terrainAreaBlocks", area);
        value.addProperty("capacityUsingLargestTemplate", lower); value.addProperty("capacityUsingSmallestTemplate", upper);
        value.addProperty("assumedGapBlocks", gap); value.add("patches", patchEstimates);
        value.addProperty("footprintBasis", "RAW_NBT_WIDTH_DEPTH");
        value.addProperty("hardGate", false);
        value.addProperty("instruction", "Geometric capacity estimate, not a target or guaranteed fit. Reserve additional room for landscape, roads and open space. Choose intended structureCount and nesting; terrain and collisions filter individual members in the preview.");
        return value;
    }

    static JsonArray array(JsonObject o, String key) { return o != null && o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray(); }
    static String text(JsonObject o, String key) { return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : ""; }
    private static JsonArray requiredArray(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) throw invalid(key + " must be an array.");
        return o.getAsJsonArray(key);
    }
    private static String requiredText(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive() || !o.getAsJsonPrimitive(key).isString() || text(o,key).isBlank())
            throw invalid(key + " must be a non-empty string.");
        return text(o,key);
    }
    private static Map<String, JsonObject> index(JsonArray a, String key) {
        Map<String, JsonObject> result = new LinkedHashMap<>(); a.forEach(e -> result.put(text(e.getAsJsonObject(), key), e.getAsJsonObject())); return result;
    }
    private static void requireFields(JsonObject object, Set<String> allowed) {
        for (String key : object.keySet()) if (!allowed.contains(key)) throw invalid("Unknown field " + key + "; allowed=" + allowed);
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException("CITY_DESIGN_SESSION_INVALID: " + message); }
}
