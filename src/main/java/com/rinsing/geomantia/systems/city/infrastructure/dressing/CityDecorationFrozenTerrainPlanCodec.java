package com.rinsing.geomantia.systems.city.infrastructure.dressing;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgram;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Strict codec for activation-time terrain runs and foundation segments. */
public final class CityDecorationFrozenTerrainPlanCodec {
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schema", "cityId", "catalogHash", "runs", "foundationSegments");
    private static final Set<String> RUN_FIELDS = Set.of(
            "runId", "programId", "paletteSlotId", "continuationAxis", "crossCoordinate",
            "terminationOrdinal", "terminationReasonCode", "slots", "foundationSegments");
    private static final Set<String> SLOT_FIELDS = Set.of(
            "runId", "slotId", "worldAnchor", "runOrdinal", "surfaceY", "targetY", "water",
            "terrainClass", "decision", "contentRef", "appliedContentRef", "reasonCode", "layers");
    private static final Set<String> LAYER_FIELDS = Set.of(
            "layerId", "contentRef", "appliedContentRef", "required");
    private static final Set<String> ANCHOR_FIELDS = Set.of("x", "z");
    private static final Set<String> SEGMENT_FIELDS = Set.of(
            "runId", "x0", "z0", "y0", "x1", "z1", "y1", "halfWidth",
            "maxDepthBlocks", "shoulderBlocks");

    public JsonObject toJson(CityDecorationTerrainRunCompiler.FrozenPlan plan) {
        JsonObject root = new JsonObject();
        root.addProperty("schema", plan.schema());
        root.addProperty("cityId", plan.cityId());
        root.addProperty("catalogHash", plan.catalogHash());
        JsonArray runs = new JsonArray();
        plan.runs().forEach(run -> runs.add(runJson(run)));
        root.add("runs", runs);
        JsonArray segments = new JsonArray();
        plan.foundationSegments().forEach(segment -> segments.add(segmentJson(segment)));
        root.add("foundationSegments", segments);
        return root;
    }

    public CityDecorationTerrainRunCompiler.FrozenPlan parse(JsonObject root) {
        requireOnly(root, ROOT_FIELDS, "root");
        requireSchema(root);
        List<CityDecorationTerrainRunCompiler.Run> runs = new ArrayList<>();
        for (JsonElement element : array(root, "runs")) {
            JsonObject run = object(element, "run");
            requireOnly(run, RUN_FIELDS, "run");
            List<CityDecorationTerrainRunCompiler.SlotOutcome> slots = new ArrayList<>();
            for (JsonElement slotElement : array(run, "slots")) {
                JsonObject slot = object(slotElement, "slot");
                requireOnly(slot, SLOT_FIELDS, "slot");
                JsonObject anchor = object(slot.get("worldAnchor"), "worldAnchor");
                requireOnly(anchor, ANCHOR_FIELDS, "worldAnchor");
                List<CityDecorationTerrainRunCompiler.LayerSelection> layers = new ArrayList<>();
                for (JsonElement layerElement : array(slot, "layers")) {
                    JsonObject layer = object(layerElement, "layer");
                    requireOnly(layer, LAYER_FIELDS, "layer");
                    layers.add(new CityDecorationTerrainRunCompiler.LayerSelection(
                            string(layer, "layerId"), string(layer, "contentRef"),
                            string(layer, "appliedContentRef"), bool(layer, "required")));
                }
                String contentRef = string(slot, "contentRef");
                String appliedContentRef = string(slot, "appliedContentRef");
                slots.add(new CityDecorationTerrainRunCompiler.SlotOutcome(
                        string(slot, "runId"), string(slot, "slotId"),
                        new BlockPoint(integer(anchor, "x"), integer(anchor, "z")),
                        integer(slot, "runOrdinal"), integer(slot, "surfaceY"), integer(slot, "targetY"),
                        bool(slot, "water"), CityDecorationTerrainRunCompiler.TerrainClass.valueOf(
                        string(slot, "terrainClass")), CityDecorationTerrainRunCompiler.Decision.valueOf(
                        string(slot, "decision")), contentRef,
                        appliedContentRef, string(slot, "reasonCode"), layers));
            }
            List<CityDecorationTerrainRunCompiler.FoundationSegment> runSegments = new ArrayList<>();
            for (JsonElement segment : array(run, "foundationSegments")) {
                runSegments.add(parseSegment(object(segment, "foundation segment")));
            }
            runs.add(new CityDecorationTerrainRunCompiler.Run(string(run, "runId"),
                    string(run, "programId"), string(run, "paletteSlotId"),
                    CompiledDecorationProgram.Axis.parse(string(run, "continuationAxis")),
                    integer(run, "crossCoordinate"), slots,
                    run.has("terminationOrdinal") && !run.get("terminationOrdinal").isJsonNull()
                            ? integer(run, "terminationOrdinal") : null,
                    stringAllowEmpty(run, "terminationReasonCode"), runSegments));
        }
        List<CityDecorationTerrainRunCompiler.FoundationSegment> segments = new ArrayList<>();
        for (JsonElement element : array(root, "foundationSegments")) {
            segments.add(parseSegment(object(element, "foundation segment")));
        }
        return new CityDecorationTerrainRunCompiler.FrozenPlan(string(root, "schema"),
                string(root, "cityId"), string(root, "catalogHash"), runs, segments);
    }

    private JsonObject runJson(CityDecorationTerrainRunCompiler.Run run) {
        JsonObject json = new JsonObject();
        json.addProperty("runId", run.runId());
        json.addProperty("programId", run.programId());
        json.addProperty("paletteSlotId", run.paletteSlotId());
        json.addProperty("continuationAxis", run.continuationAxis().serializedName());
        json.addProperty("crossCoordinate", run.crossCoordinate());
        if (run.terminationOrdinal() == null) {
            json.add("terminationOrdinal", com.google.gson.JsonNull.INSTANCE);
        } else {
            json.addProperty("terminationOrdinal", run.terminationOrdinal());
        }
        json.addProperty("terminationReasonCode", run.terminationReasonCode());
        JsonArray slots = new JsonArray();
        run.slots().forEach(slot -> slots.add(slotJson(slot)));
        json.add("slots", slots);
        JsonArray segments = new JsonArray();
        run.foundationSegments().forEach(segment -> segments.add(segmentJson(segment)));
        json.add("foundationSegments", segments);
        return json;
    }

    private JsonObject slotJson(CityDecorationTerrainRunCompiler.SlotOutcome slot) {
        JsonObject json = new JsonObject();
        json.addProperty("runId", slot.runId());
        json.addProperty("slotId", slot.slotId());
        JsonObject anchor = new JsonObject();
        anchor.addProperty("x", slot.worldAnchor().x());
        anchor.addProperty("z", slot.worldAnchor().z());
        json.add("worldAnchor", anchor);
        json.addProperty("runOrdinal", slot.runOrdinal());
        json.addProperty("surfaceY", slot.surfaceY());
        json.addProperty("targetY", slot.targetY());
        json.addProperty("water", slot.water());
        json.addProperty("terrainClass", slot.terrainClass().name());
        json.addProperty("decision", slot.decision().name());
        json.addProperty("contentRef", slot.contentRef());
        json.addProperty("appliedContentRef", slot.appliedContentRef());
        json.addProperty("reasonCode", slot.reasonCode());
        JsonArray layers = new JsonArray();
        slot.layers().forEach(layer -> {
            JsonObject layerJson = new JsonObject();
            layerJson.addProperty("layerId", layer.layerId());
            layerJson.addProperty("contentRef", layer.contentRef());
            layerJson.addProperty("appliedContentRef", layer.appliedContentRef());
            layerJson.addProperty("required", layer.required());
            layers.add(layerJson);
        });
        json.add("layers", layers);
        return json;
    }

    private JsonObject segmentJson(CityDecorationTerrainRunCompiler.FoundationSegment segment) {
        JsonObject json = new JsonObject();
        json.addProperty("runId", segment.runId());
        json.addProperty("x0", segment.x0());
        json.addProperty("z0", segment.z0());
        json.addProperty("y0", segment.y0());
        json.addProperty("x1", segment.x1());
        json.addProperty("z1", segment.z1());
        json.addProperty("y1", segment.y1());
        json.addProperty("halfWidth", segment.halfWidth());
        json.addProperty("maxDepthBlocks", segment.maxDepthBlocks());
        json.addProperty("shoulderBlocks", segment.shoulderBlocks());
        return json;
    }

    private CityDecorationTerrainRunCompiler.FoundationSegment parseSegment(JsonObject json) {
        requireOnly(json, SEGMENT_FIELDS, "foundation segment");
        return new CityDecorationTerrainRunCompiler.FoundationSegment(string(json, "runId"),
                integer(json, "x0"), integer(json, "z0"), integer(json, "y0"),
                integer(json, "x1"), integer(json, "z1"), integer(json, "y1"),
                integer(json, "halfWidth"), integer(json, "maxDepthBlocks"),
                integer(json, "shoulderBlocks"));
    }

    private static void requireSchema(JsonObject root) {
        String schema = string(root, "schema");
        if (!CityDecorationTerrainRunCompiler.SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("CITY_DECORATION_FROZEN_TERRAIN_SCHEMA_UNSUPPORTED");
        }
    }

    private static void requireOnly(JsonObject object, Set<String> allowed, String owner) {
        for (String key : object.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("CITY_DECORATION_FROZEN_TERRAIN_FIELD_UNSUPPORTED: "
                        + owner + "." + key);
            }
        }
    }

    private static JsonArray array(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            throw new IllegalArgumentException("CITY_DECORATION_FROZEN_TERRAIN_FIELD_INVALID: " + key);
        }
        return object.getAsJsonArray(key);
    }

    private static JsonObject object(JsonElement element, String owner) {
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException("CITY_DECORATION_FROZEN_TERRAIN_FIELD_INVALID: " + owner);
        }
        return element.getAsJsonObject();
    }

    private static String string(JsonObject object, String key) {
        String value = stringAllowEmpty(object, key);
        if (value.isBlank()) {
            throw new IllegalArgumentException("CITY_DECORATION_FROZEN_TERRAIN_FIELD_INVALID: " + key);
        }
        return value;
    }

    private static String stringAllowEmpty(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.getAsJsonPrimitive(key).isString()) {
            throw new IllegalArgumentException("CITY_DECORATION_FROZEN_TERRAIN_FIELD_INVALID: " + key);
        }
        return object.get(key).getAsString();
    }

    private static int integer(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.getAsJsonPrimitive(key).isNumber()) {
            throw new IllegalArgumentException("CITY_DECORATION_FROZEN_TERRAIN_FIELD_INVALID: " + key);
        }
        return object.get(key).getAsInt();
    }

    private static boolean bool(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.getAsJsonPrimitive(key).isBoolean()) {
            throw new IllegalArgumentException("CITY_DECORATION_FROZEN_TERRAIN_FIELD_INVALID: " + key);
        }
        return object.get(key).getAsBoolean();
    }
}
