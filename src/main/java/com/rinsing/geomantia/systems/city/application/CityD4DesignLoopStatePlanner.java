package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.CityLandformReviewPackage;
import com.rinsing.geomantia.systems.city.domain.model.LandformPatchSummary;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CityD4DesignLoopStatePlanner {
    public static final String STATE_SCHEMA = "city_d4_design_loop_state.v0.1";
    public static final String OCCUPIED_FIELD_SCHEMA = "city_d4_design_loop_occupied_field.v0.1";
    public static final String FUNCTION_ZONES_SCHEMA = "city_d4_design_loop_function_zones.v0.1";
    public static final String ARRAY_ZONES_SCHEMA = "city_d4_design_loop_array_zones.v0.1";
    public static final String PATCH_AVAILABILITY_SCHEMA = "city_d4_design_loop_patch_availability.v0.1";
    public static final String TRACE_SCHEMA = "city_d4_design_loop_execution_trace.v0.1";
    public static final String SUMMARY_SCHEMA = "city_d4_design_loop_next_ai_context_summary.v0.1";
    public static final String DEFAULT_PLANNING_MODE = "d4_multi_round_design_loop_v0_1";

    public CreateResult create(CityLandformReviewPackage reviewPackage,
                               JsonObject options,
                               JsonObject baseStructureAnchorMap) {
        if (reviewPackage == null) {
            throw new IllegalArgumentException("CityLandformReviewPackage is required for D4 design loop state.");
        }
        JsonObject config = options == null ? new JsonObject() : options;
        String cityId = stringValue(config, "cityId", reviewPackage.cityId());
        String planningMode = stringValue(config, "planningMode", DEFAULT_PLANNING_MODE);
        JsonObject state = new JsonObject();
        state.addProperty("schemaVersion", STATE_SCHEMA);
        state.addProperty("cityId", cityId);
        state.addProperty("planningMode", planningMode);
        state.addProperty("loopId", cityId + "/d4_design_loop");
        state.addProperty("stateId", stateId(0));
        state.addProperty("roundIndex", 0);
        state.addProperty("status", "ready_for_next_round");
        state.addProperty("createdAt", Instant.now().toString());
        state.addProperty("updatedAt", Instant.now().toString());
        state.add("sourceD3Patches", sourcePatches(reviewPackage));
        state.add("placedStructures", new JsonArray());
        state.add("anchors", new JsonArray());
        state.add("functionZones", zones(FUNCTION_ZONES_SCHEMA, planningMode, "zones"));
        state.add("arrayZones", zones(ARRAY_ZONES_SCHEMA, planningMode, "arrayZones"));
        state.add("executionTrace", trace(planningMode));
        if (baseStructureAnchorMap != null && baseStructureAnchorMap.has("anchors")) {
            seedAnchors(state, baseStructureAnchorMap.getAsJsonArray("anchors"));
        }
        refreshDerivedState(state, null);
        return new CreateResult(state, quality(List.of(), List.of()));
    }

    public AppendResult appendOneRound(JsonObject currentState, JsonObject roundPatch) {
        JsonObject state = normalizeForWriteBack(currentState);
        if (roundPatch == null) {
            throw new IllegalArgumentException("designLoopRound object is required.");
        }
        int nextRound = intValue(state, "roundIndex", 0) + 1;
        JsonArray anchors = copiedArray(roundPatch, "anchors");
        if (anchors.isEmpty() && roundPatch.has("structureAnchorMap")
                && roundPatch.get("structureAnchorMap").isJsonObject()) {
            anchors = copiedArray(roundPatch.getAsJsonObject("structureAnchorMap"), "anchors");
        }
        JsonArray placedStructures = copiedArray(roundPatch, "placedStructures");
        JsonArray addedOccupied = new JsonArray();
        appendAnchors(state, anchors, nextRound, addedOccupied);
        appendPlacedStructures(state, placedStructures, nextRound, addedOccupied);
        appendZones(object(state, "functionZones"), "zones", zonesFromRound(roundPatch, "functionZones", "zones"));
        appendZones(object(state, "arrayZones"), "arrayZones", zonesFromRound(roundPatch, "arrayZones", "arrayZones"));
        appendZones(object(state, "arrayZones"), "arrayZones", zonesFromRound(roundPatch, "functionalArrayZones", "arrayZones"));
        state.addProperty("roundIndex", nextRound);
        state.addProperty("stateId", stateId(nextRound));
        state.addProperty("status", "ready_for_next_round");
        state.addProperty("updatedAt", Instant.now().toString());
        appendRoundTrace(state, roundPatch, nextRound, anchors.size(), placedStructures.size(), addedOccupied.size());
        refreshDerivedState(state, roundPatch.has("nextAiContextSummary")
                && roundPatch.get("nextAiContextSummary").isJsonObject()
                ? roundPatch.getAsJsonObject("nextAiContextSummary") : null);
        return new AppendResult(state, quality(List.of(), List.of()));
    }

    public WriteBackResult writeBack(JsonObject currentState) {
        JsonObject state = normalizeForWriteBack(currentState);
        state.addProperty("updatedAt", Instant.now().toString());
        refreshDerivedState(state, object(state, "nextAiContextSummary"));
        return new WriteBackResult(state, quality(List.of(), List.of()));
    }

    public ReadResult read(JsonObject currentState) {
        JsonObject state = normalizeForWriteBack(currentState);
        refreshDerivedState(state, object(state, "nextAiContextSummary"));
        return new ReadResult(state, quality(List.of(), List.of()));
    }

    public JsonObject normalizeForWriteBack(JsonObject currentState) {
        if (currentState == null || !STATE_SCHEMA.equals(stringValue(currentState, "schemaVersion"))) {
            throw new IllegalArgumentException("D4_DESIGN_LOOP_STATE_REQUIRED: current design loop state is required.");
        }
        JsonObject state = currentState.deepCopy();
        stripRetiredEnvelopeFields(state);
        if (!state.has("placedStructures") || !state.get("placedStructures").isJsonArray()) {
            state.add("placedStructures", new JsonArray());
        }
        if (!state.has("anchors") || !state.get("anchors").isJsonArray()) {
            state.add("anchors", new JsonArray());
        }
        if (!state.has("functionZones") || !state.get("functionZones").isJsonObject()) {
            state.add("functionZones", zones(FUNCTION_ZONES_SCHEMA, planningMode(state), "zones"));
        }
        if (!state.has("arrayZones") || !state.get("arrayZones").isJsonObject()) {
            state.add("arrayZones", zones(ARRAY_ZONES_SCHEMA, planningMode(state), "arrayZones"));
        }
        if (!state.has("executionTrace") || !state.get("executionTrace").isJsonObject()) {
            state.add("executionTrace", trace(planningMode(state)));
        }
        return state;
    }

    private void seedAnchors(JsonObject state, JsonArray anchors) {
        JsonArray addedOccupied = new JsonArray();
        appendAnchors(state, anchors.deepCopy(), 0, addedOccupied);
        JsonObject traceItem = new JsonObject();
        traceItem.addProperty("eventType", "seed_base_anchors");
        traceItem.addProperty("roundIndex", 0);
        traceItem.addProperty("anchorCount", anchors.size());
        traceItem.addProperty("occupiedEnvelopeAddedCount", addedOccupied.size());
        array(object(state, "executionTrace"), "rounds").add(traceItem);
    }

    private void appendAnchors(JsonObject state, JsonArray anchors, int roundIndex, JsonArray addedOccupied) {
        JsonArray target = array(state, "anchors");
        for (JsonElement elem : anchors) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject anchor = elem.getAsJsonObject().deepCopy();
            stripRetiredEnvelopeFields(anchor);
            anchor.addProperty("designLoopRoundIndex", roundIndex);
            target.add(anchor);
            addedOccupied.add(occupiedFrom(anchor, "anchor", roundIndex));
        }
    }

    private void appendPlacedStructures(JsonObject state, JsonArray placedStructures, int roundIndex,
                                        JsonArray addedOccupied) {
        JsonArray target = array(state, "placedStructures");
        for (JsonElement elem : placedStructures) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject placed = elem.getAsJsonObject().deepCopy();
            stripRetiredEnvelopeFields(placed);
            placed.addProperty("designLoopRoundIndex", roundIndex);
            target.add(placed);
            addedOccupied.add(occupiedFrom(placed, "placedStructure", roundIndex));
        }
    }

    private JsonObject occupiedFrom(JsonObject source, String sourceKind, int roundIndex) {
        EnvelopeSelection selected = envelope(source);
        if (selected == null) {
            throw new IllegalArgumentException("D4_DESIGN_LOOP_OCCUPIED_ENVELOPE_REQUIRED: "
                    + sourceKind + " " + sourceId(source) + " must provide collisionEnvelope or bodyEnvelope.");
        }
        JsonObject occupied = new JsonObject();
        occupied.addProperty("occupiedId", sourceKind + "_" + sourceId(source) + "_r"
                + String.format(Locale.ROOT, "%04d", roundIndex));
        occupied.addProperty("sourceKind", sourceKind);
        occupied.addProperty("sourceRoundIndex", roundIndex);
        copyString(source, occupied, "anchorId");
        copyString(source, occupied, "templateId");
        copyString(source, occupied, "arrayId");
        copyString(source, occupied, "functionZoneId");
        occupied.addProperty("envelopeSource", selected.key());
        occupied.add("blockBounds", selected.bounds().deepCopy());
        return occupied;
    }

    private EnvelopeSelection envelope(JsonObject source) {
        JsonObject collision = envelopeObject(source, "collisionEnvelope");
        if (!collision.entrySet().isEmpty()) {
            return new EnvelopeSelection("collisionEnvelope", collision);
        }
        JsonObject body = envelopeObject(source, "bodyEnvelope");
        if (!body.entrySet().isEmpty()) {
            return new EnvelopeSelection("bodyEnvelope", body);
        }
        return null;
    }

    private JsonObject envelopeObject(JsonObject source, String key) {
        if (source == null || !source.has(key) || !source.get(key).isJsonObject()) {
            return new JsonObject();
        }
        JsonObject obj = source.getAsJsonObject(key);
        if (obj.has("blockBounds") && obj.get("blockBounds").isJsonObject()) {
            return obj.getAsJsonObject("blockBounds").deepCopy();
        }
        return obj.deepCopy();
    }

    private void refreshDerivedState(JsonObject state, JsonObject requestedSummary) {
        JsonArray occupied = rebuildOccupiedField(state);
        JsonObject occupiedField = new JsonObject();
        occupiedField.addProperty("schemaVersion", OCCUPIED_FIELD_SCHEMA);
        occupiedField.addProperty("cityId", stringValue(state, "cityId"));
        occupiedField.addProperty("planningMode", planningMode(state));
        occupiedField.addProperty("roundIndex", intValue(state, "roundIndex", 0));
        occupiedField.addProperty("sourcePolicy", "collision_or_body_envelope_only");
        occupiedField.add("occupied", occupied);
        state.add("occupiedField", occupiedField);
        normalizeZones(object(state, "functionZones"), FUNCTION_ZONES_SCHEMA, "zones");
        normalizeZones(object(state, "arrayZones"), ARRAY_ZONES_SCHEMA, "arrayZones");
        state.add("patchAvailability", patchAvailability(state, occupied));
        state.add("nextAiContextSummary", nextAiContextSummary(state, requestedSummary));
        state.add("quality", quality(List.of(), List.of()));
    }

    private JsonArray rebuildOccupiedField(JsonObject state) {
        JsonArray occupied = new JsonArray();
        for (JsonElement elem : array(state, "anchors")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject source = elem.getAsJsonObject();
            int round = intValue(source, "designLoopRoundIndex", 0);
            occupied.add(occupiedFrom(source, "anchor", round));
        }
        for (JsonElement elem : array(state, "placedStructures")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject source = elem.getAsJsonObject();
            int round = intValue(source, "designLoopRoundIndex", 0);
            occupied.add(occupiedFrom(source, "placedStructure", round));
        }
        return occupied;
    }

    private JsonObject patchAvailability(JsonObject state, JsonArray occupied) {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", PATCH_AVAILABILITY_SCHEMA);
        obj.addProperty("cityId", stringValue(state, "cityId"));
        obj.addProperty("planningMode", planningMode(state));
        obj.addProperty("roundIndex", intValue(state, "roundIndex", 0));
        JsonArray patches = new JsonArray();
        for (JsonElement elem : array(state, "sourceD3Patches")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject source = elem.getAsJsonObject();
            JsonObject patch = source.deepCopy();
            BlockBounds patchBounds = bounds(object(source, "blockBounds"));
            int overlapCount = 0;
            for (JsonElement occElem : occupied) {
                if (!occElem.isJsonObject()) {
                    continue;
                }
                BlockBounds occBounds = bounds(object(occElem.getAsJsonObject(), "blockBounds"));
                if (patchBounds.overlaps(occBounds)) {
                    overlapCount++;
                }
            }
            patch.addProperty("occupiedEnvelopeOverlapCount", overlapCount);
            patch.addProperty("availabilityStatus", overlapCount == 0 ? "available" : "partially_occupied");
            patches.add(patch);
        }
        obj.add("patches", patches);
        return obj;
    }

    private JsonObject nextAiContextSummary(JsonObject state, JsonObject requestedSummary) {
        JsonObject summary = requestedSummary == null ? new JsonObject() : requestedSummary.deepCopy();
        stripRetiredEnvelopeFields(summary);
        summary.addProperty("schemaVersion", SUMMARY_SCHEMA);
        summary.addProperty("cityId", stringValue(state, "cityId"));
        summary.addProperty("planningMode", planningMode(state));
        summary.addProperty("roundIndex", intValue(state, "roundIndex", 0));
        summary.addProperty("anchorCount", array(state, "anchors").size());
        summary.addProperty("placedStructureCount", array(state, "placedStructures").size());
        summary.addProperty("occupiedEnvelopeCount", array(object(state, "occupiedField"), "occupied").size());
        summary.addProperty("functionZoneCount", array(object(state, "functionZones"), "zones").size());
        summary.addProperty("arrayZoneCount", array(object(state, "arrayZones"), "arrayZones").size());
        summary.addProperty("stageBoundary", "d4_state_only_no_submission_stage");
        return summary;
    }

    private void appendRoundTrace(JsonObject state, JsonObject roundPatch, int roundIndex,
                                  int anchorCount, int placedStructureCount, int occupiedCount) {
        JsonObject event = new JsonObject();
        event.addProperty("eventType", "append_one_round");
        event.addProperty("roundIndex", roundIndex);
        event.addProperty("roundId", stringValue(roundPatch, "roundId",
                "round_" + String.format(Locale.ROOT, "%04d", roundIndex)));
        event.addProperty("status", "accepted");
        event.addProperty("addedAnchorCount", anchorCount);
        event.addProperty("addedPlacedStructureCount", placedStructureCount);
        event.addProperty("occupiedEnvelopeAddedCount", occupiedCount);
        event.addProperty("functionZoneAddedCount", zonesFromRound(roundPatch, "functionZones", "zones").size());
        event.addProperty("arrayZoneAddedCount", zonesFromRound(roundPatch, "arrayZones", "arrayZones").size()
                + zonesFromRound(roundPatch, "functionalArrayZones", "arrayZones").size());
        event.addProperty("recordedAt", Instant.now().toString());
        if (roundPatch.has("executionTrace") && roundPatch.get("executionTrace").isJsonObject()) {
            JsonObject sourceTrace = roundPatch.getAsJsonObject("executionTrace").deepCopy();
            stripRetiredEnvelopeFields(sourceTrace);
            event.add("sourceExecutionTrace", sourceTrace);
        }
        array(object(state, "executionTrace"), "rounds").add(event);
    }

    private JsonArray sourcePatches(CityLandformReviewPackage reviewPackage) {
        JsonArray patches = new JsonArray();
        for (LandformPatchSummary patch : reviewPackage.landformPatches()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("landformPatchId", patch.landformPatchId());
            obj.addProperty("mapLabel", patch.mapLabel());
            obj.addProperty("landformType", patch.landformType().name());
            obj.add("blockBounds", boundsJson(patch.blockBounds()));
            obj.add("centerBlock", patch.centerBlock().asJson());
            patches.add(obj);
        }
        return patches;
    }

    private JsonObject trace(String planningMode) {
        JsonObject trace = new JsonObject();
        trace.addProperty("schemaVersion", TRACE_SCHEMA);
        trace.addProperty("planningMode", planningMode);
        trace.add("rounds", new JsonArray());
        return trace;
    }

    private JsonObject zones(String schema, String planningMode, String arrayKey) {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", schema);
        obj.addProperty("planningMode", planningMode);
        obj.add(arrayKey, new JsonArray());
        return obj;
    }

    private void normalizeZones(JsonObject obj, String schema, String arrayKey) {
        obj.addProperty("schemaVersion", schema);
        if (!obj.has("planningMode")) {
            obj.addProperty("planningMode", DEFAULT_PLANNING_MODE);
        }
        if (!obj.has(arrayKey) || !obj.get(arrayKey).isJsonArray()) {
            obj.add(arrayKey, new JsonArray());
        }
        stripRetiredEnvelopeFields(obj);
    }

    private JsonArray zonesFromRound(JsonObject roundPatch, String key, String arrayKey) {
        if (roundPatch == null || !roundPatch.has(key)) {
            return new JsonArray();
        }
        JsonElement elem = roundPatch.get(key);
        if (elem.isJsonArray()) {
            return elem.getAsJsonArray().deepCopy();
        }
        if (elem.isJsonObject()) {
            return copiedArray(elem.getAsJsonObject(), arrayKey);
        }
        return new JsonArray();
    }

    private void appendZones(JsonObject target, String arrayKey, JsonArray source) {
        JsonArray targetArray = array(target, arrayKey);
        for (JsonElement elem : source) {
            JsonElement copy = elem.deepCopy();
            stripRetiredEnvelopeFields(copy);
            targetArray.add(copy);
        }
    }

    private JsonObject quality(List<String> hardBlocks, List<String> warnings) {
        JsonObject quality = new JsonObject();
        quality.addProperty("passed", hardBlocks.isEmpty());
        quality.addProperty("score", hardBlocks.isEmpty() ? 100 : 0);
        quality.add("hardBlocks", stringArray(hardBlocks));
        quality.add("warnings", stringArray(warnings));
        quality.add("needsReview", new JsonArray());
        return quality;
    }

    private JsonArray copiedArray(JsonObject obj, String key) {
        return obj != null && obj.has(key) && obj.get(key).isJsonArray()
                ? obj.getAsJsonArray(key).deepCopy() : new JsonArray();
    }

    private JsonArray stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private String planningMode(JsonObject state) {
        return stringValue(state, "planningMode", DEFAULT_PLANNING_MODE);
    }

    private String stateId(int roundIndex) {
        return "d4_design_loop_state_" + String.format(Locale.ROOT, "%04d", roundIndex);
    }

    private String sourceId(JsonObject source) {
        String anchorId = stringValue(source, "anchorId");
        if (!anchorId.isBlank()) {
            return anchorId;
        }
        String id = stringValue(source, "templateId", stringValue(source, "placedStructureId"));
        return id.isBlank() ? "unknown" : id;
    }

    private void copyString(JsonObject source, JsonObject target, String key) {
        String value = stringValue(source, key);
        if (!value.isBlank()) {
            target.addProperty(key, value);
        }
    }

    private BlockBounds bounds(JsonObject obj) {
        return new BlockBounds(intValue(obj, "minX", 0), intValue(obj, "minZ", 0),
                intValue(obj, "maxX", 0), intValue(obj, "maxZ", 0));
    }

    private JsonObject boundsJson(BlockBounds bounds) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minX", bounds.minX());
        obj.addProperty("minZ", bounds.minZ());
        obj.addProperty("maxX", bounds.maxX());
        obj.addProperty("maxZ", bounds.maxZ());
        return obj;
    }

    private JsonArray array(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonArray()) {
            JsonArray created = new JsonArray();
            if (obj != null) {
                obj.add(key, created);
            }
            return created;
        }
        return obj.getAsJsonArray(key);
    }

    private JsonObject object(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonObject()) {
            JsonObject created = new JsonObject();
            if (obj != null) {
                obj.add(key, created);
            }
            return created;
        }
        return obj.getAsJsonObject(key);
    }

    private String stringValue(JsonObject obj, String key) {
        return stringValue(obj, key, "");
    }

    private String stringValue(JsonObject obj, String key, String defaultValue) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsString() : defaultValue;
    }

    private int intValue(JsonObject obj, String key, int defaultValue) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsInt() : defaultValue;
    }

    private void stripRetiredEnvelopeFields(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            obj.remove("safetyEnvelope");
            obj.remove("estimatedSafetyEnvelope");
            obj.remove("groupSafetyEnvelope");
            for (JsonElement child : new ArrayList<>(obj.entrySet().stream()
                    .map(java.util.Map.Entry::getValue)
                    .toList())) {
                stripRetiredEnvelopeFields(child);
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                stripRetiredEnvelopeFields(child);
            }
        }
    }

    public record CreateResult(JsonObject loopState, JsonObject qualityReport) {
        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("ok", qualityReport.get("passed").getAsBoolean());
            obj.addProperty("planningMode", loopState.get("planningMode").getAsString());
            obj.add("designLoopState", loopState.deepCopy());
            obj.add("occupiedField", loopState.getAsJsonObject("occupiedField").deepCopy());
            obj.add("functionZones", loopState.getAsJsonObject("functionZones").deepCopy());
            obj.add("arrayZones", loopState.getAsJsonObject("arrayZones").deepCopy());
            obj.add("patchAvailability", loopState.getAsJsonObject("patchAvailability").deepCopy());
            obj.add("nextAiContextSummary", loopState.getAsJsonObject("nextAiContextSummary").deepCopy());
            obj.add("executionTrace", loopState.getAsJsonObject("executionTrace").deepCopy());
            obj.add("qualityReport", qualityReport.deepCopy());
            return obj;
        }
    }

    public record AppendResult(JsonObject loopState, JsonObject qualityReport) {
        public JsonObject asJson() {
            return new CreateResult(loopState, qualityReport).asJson();
        }
    }

    public record WriteBackResult(JsonObject loopState, JsonObject qualityReport) {
        public JsonObject asJson() {
            return new CreateResult(loopState, qualityReport).asJson();
        }
    }

    public record ReadResult(JsonObject loopState, JsonObject qualityReport) {
        public JsonObject asJson() {
            return new CreateResult(loopState, qualityReport).asJson();
        }
    }

    private record EnvelopeSelection(String key, JsonObject bounds) {
    }
}
