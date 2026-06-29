package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.domain.model.BuildOperationPlan;
import com.rinsing.geomantia.systems.city.domain.model.CityQualityReport;
import com.rinsing.geomantia.systems.city.domain.model.CitySiteContext;
import com.rinsing.geomantia.systems.city.domain.model.RoadIntent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class CityReservationMaskPlanner {
    public static final String MASK_SCHEMA = "city_reservation_mask_plan.v0.1";

    public Result plan(CitySiteContext context, JsonObject structureAnchorMap) {
        long started = System.nanoTime();
        if (context == null) {
            throw new IllegalArgumentException("CitySiteContext is required for D5.");
        }
        if (structureAnchorMap == null || !structureAnchorMap.has("anchors")) {
            throw new IllegalArgumentException("structure_anchor_map.json is required for D5.");
        }
        String cityId = requiredString(structureAnchorMap, "cityId");
        if (!context.cityId().equals(cityId)) {
            throw new IllegalArgumentException("CitySiteContext and StructureAnchorMap cityId mismatch.");
        }

        JsonArray noVegetation = new JsonArray();
        JsonArray vegetationLimited = new JsonArray();
        JsonArray noVanillaStructure = new JsonArray();
        JsonArray reasons = new JsonArray();
        List<RoadIntent.Node> nodes = new ArrayList<>();
        List<RoadIntent.Edge> edges = new ArrayList<>();
        List<BuildOperationPlan.Operation> operations = new ArrayList<>();

        RoadIntent.Node entryNode = new RoadIntent.Node("entry_main", "city_entry", "",
                context.entryCandidates().isEmpty() ? context.anchorBlock() : context.entryCandidates().get(0).block(),
                "main entry");
        nodes.add(entryNode);

        List<JsonObject> anchors = jsonObjects(structureAnchorMap.getAsJsonArray("anchors"));
        anchors.sort(Comparator.comparingInt(a -> intValue(a, "priority", 0)));
        int index = 0;
        for (JsonObject anchor : anchors) {
            index++;
            String anchorId = requiredString(anchor, "anchorId");
            BlockBounds envelope = bounds(requiredObject(anchor, "reservedEnvelope"));
            BlockBounds maskEnvelope = anchor.has("maskEnvelope") && anchor.get("maskEnvelope").isJsonObject()
                    ? bounds(requiredObject(anchor, "maskEnvelope")) : envelope;
            BlockBounds footprint = bounds(requiredObject(anchor, "plannedFootprint"));
            addMask(noVegetation, anchorId + "_no_vegetation", maskEnvelope, "structure_mask_envelope", anchorId);
            addMask(vegetationLimited, anchorId + "_vegetation_limited",
                    CityStructureAnchorPlanner.expand(maskEnvelope, 4), "structure_transition", anchorId);
            addMask(noVanillaStructure, anchorId + "_no_vanilla_structure", maskEnvelope,
                    "planned_structure", anchorId);
            addReason(reasons, anchorId, "structure", maskEnvelope, "protect planned structure mask envelope");
            addReason(reasons, anchorId, "footprint", footprint, "planned footprint");
        }

        JsonObject mask = new JsonObject();
        mask.addProperty("schemaVersion", MASK_SCHEMA);
        mask.addProperty("cityId", cityId);
        mask.add("grid", context.grid().asJson());
        mask.add("noVegetationMask", noVegetation);
        mask.add("vegetationLimitedMask", vegetationLimited);
        mask.add("noVanillaStructureMask", noVanillaStructure);
        mask.add("reservationReason", reasons);
        mask.add("sourceStructureAnchorMap", structureAnchorMap.deepCopy());
        JsonObject hook = new JsonObject();
        hook.addProperty("required", true);
        hook.addProperty("featureHook", "ConfiguredFeature.place HEAD");
        hook.addProperty("vanillaStructureMaskHook", "ChunkGenerator.tryGenerateStructure HEAD");
        hook.addProperty("plannedStructureHook", "ChunkGenerator.createStructures TAIL");
        hook.addProperty("unavailableReasonCode", "CITY_WORLDGEN_STRUCTURE_HOOK_UNAVAILABLE");
        mask.add("hookRequirements", hook);
        mask.addProperty("requiresLockedMaterializationPlan", true);
        mask.addProperty("roadPlanningStage", "d7_after_worldgen_ledger");
        mask.add("timingMs", timing(started));

        JsonObject quality = new JsonObject();
        quality.addProperty("passed", !anchors.isEmpty());
        quality.addProperty("score", anchors.isEmpty() ? 0 : 100);
        JsonObject metrics = new JsonObject();
        metrics.addProperty("anchorCount", anchors.size());
        metrics.addProperty("noVegetationMaskCount", noVegetation.size());
        metrics.addProperty("noVanillaStructureMaskCount", noVanillaStructure.size());
        metrics.addProperty("d5RoadOperationCount", 0);
        quality.add("metrics", metrics);

        RoadIntent roadIntent = new RoadIntent(RoadIntent.CURRENT_SCHEMA_VERSION, cityId, nodes, edges,
                simpleQuality(true, edges.size()));
        BuildOperationPlan operationPlan = new BuildOperationPlan(BuildOperationPlan.CURRENT_SCHEMA_VERSION,
                cityId, "geomantia_templates/d5", operations);
        return new Result(mask, roadIntent.asJson(), operationPlan.asJson(), quality);
    }

    private static void addMask(JsonArray array, String id, BlockBounds bounds, String type, String sourceRef) {
        JsonObject obj = new JsonObject();
        obj.addProperty("maskId", id);
        obj.addProperty("maskType", type);
        obj.addProperty("sourceRef", sourceRef);
        obj.add("blockBounds", boundsJson(bounds));
        array.add(obj);
    }

    private static void addReason(JsonArray array, String sourceRef, String sourceType,
                                  BlockBounds bounds, String reason) {
        JsonObject obj = new JsonObject();
        obj.addProperty("sourceRef", sourceRef);
        obj.addProperty("sourceType", sourceType);
        obj.addProperty("reason", reason);
        obj.add("blockBounds", boundsJson(bounds));
        array.add(obj);
    }

    private static CityQualityReport simpleQuality(boolean passed, int edgeCount) {
        JsonObject metrics = new JsonObject();
        metrics.addProperty("edgeCount", edgeCount);
        return new CityQualityReport(passed, passed ? 100 : 0, List.of(), List.of(), List.of(), metrics);
    }

    private static JsonObject timing(long started) {
        JsonObject timing = new JsonObject();
        timing.addProperty("total", (System.nanoTime() - started) / 1_000_000L);
        return timing;
    }

    private static List<JsonObject> jsonObjects(JsonArray array) {
        List<JsonObject> result = new ArrayList<>();
        for (JsonElement elem : array) {
            if (elem.isJsonObject()) {
                result.add(elem.getAsJsonObject());
            }
        }
        return result;
    }

    private static BlockPoint blockPoint(JsonObject obj) {
        return new BlockPoint(intValue(obj, "x", 0), intValue(obj, "z", 0));
    }

    private static BlockBounds bounds(JsonObject obj) {
        return new BlockBounds(intValue(obj, "minX", 0), intValue(obj, "minZ", 0),
                intValue(obj, "maxX", 0), intValue(obj, "maxZ", 0));
    }

    private static JsonObject boundsJson(BlockBounds bounds) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minX", bounds.minX());
        obj.addProperty("minZ", bounds.minZ());
        obj.addProperty("maxX", bounds.maxX());
        obj.addProperty("maxZ", bounds.maxZ());
        return obj;
    }

    private static JsonObject requiredObject(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonObject()) {
            throw new IllegalArgumentException(key + " object is required.");
        }
        return obj.getAsJsonObject(key);
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

    private static String safe(String raw) {
        return raw == null ? "unknown" : raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public record Result(JsonObject reservationMaskPlan, JsonObject roadAccessPlan,
                         JsonObject buildOperationPlan, JsonObject qualityReport) {
        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("ok", qualityReport.get("passed").getAsBoolean());
            obj.add("reservationMaskPlan", reservationMaskPlan);
            obj.add("roadAccessPlan", roadAccessPlan);
            obj.add("buildOperationPlan", buildOperationPlan);
            obj.add("qualityReport", qualityReport);
            obj.add("timingMs", reservationMaskPlan.getAsJsonObject("timingMs"));
            return obj;
        }
    }
}
