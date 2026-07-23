package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CityD4StagedPlanCompiler {
    public StagePlan compile(JsonObject designSlotPlan) {
        if (designSlotPlan == null || !designSlotPlan.has("slots")
                || !designSlotPlan.get("slots").isJsonArray()) {
            throw new IllegalArgumentException("D4_STAGED_PLAN_REQUIRES_SLOTS: designSlotPlan.slots is required.");
        }
        Map<String, JsonObject> slots = new LinkedHashMap<>();
        for (JsonElement elem : array(designSlotPlan, "slots")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject slot = elem.getAsJsonObject();
            String slotId = requiredString(slot, "slotId");
            slots.put(slotId, slot);
        }
        List<String> order = placementOrder(designSlotPlan, slots.keySet());
        JsonArray keySlots = new JsonArray();
        List<String> keyOrder = new ArrayList<>();
        List<JsonObject> arraySlots = new ArrayList<>();
        boolean seenArray = false;
        for (String slotId : order) {
            JsonObject slot = slots.get(slotId);
            if (slot == null) {
                throw new IllegalArgumentException("D4_SLOT_ORDER_VIOLATION: placementOrder references missing slot "
                        + slotId + ".");
            }
            String strategy = normalizePlacementStrategy(slot);
            if ("array_fill".equals(strategy)) {
                seenArray = true;
                arraySlots.add(slot.deepCopy());
                continue;
            }
            if (seenArray) {
                throw new IllegalArgumentException("D4_KEY_STRUCTURES_MUST_PRECEDE_ARRAYS: key slot "
                        + slotId + " appears after an array_fill slot.");
            }
            keySlots.add(slot.deepCopy());
            keyOrder.add(slotId);
        }
        if (!arraySlots.isEmpty() && keySlots.isEmpty()) {
            throw new IllegalArgumentException("D4_KEY_STRUCTURE_STAGE_REQUIRED: array_fill requires at least one "
                    + "key_structure slot before arrays.");
        }
        JsonObject keyPlan = designSlotPlan.deepCopy();
        keyPlan.addProperty("planningMode", "key_structure_stage");
        keyPlan.add("slots", keySlots);
        keyPlan.add("placementOrder", stringArray(keyOrder));
        return new StagePlan(designSlotPlan.deepCopy(), keyPlan, List.copyOf(arraySlots));
    }

    public JsonObject arrayCandidatePlanFromSlot(JsonObject sourceDesignSlotPlan, JsonObject slot) {
        JsonObject plan = slot.has("arrayCandidatePlan") && slot.get("arrayCandidatePlan").isJsonObject()
                ? slot.getAsJsonObject("arrayCandidatePlan").deepCopy() : new JsonObject();
        String cityId = stringValue(sourceDesignSlotPlan, "cityId");
        String slotId = requiredString(slot, "slotId");
        plan.addProperty("schemaVersion", CityStructureArrayCandidatePlanner.PLAN_SCHEMA);
        if (stringValue(plan, "cityId").isBlank()) {
            plan.addProperty("cityId", cityId);
        }
        if (stringValue(plan, "arrayId").isBlank()) {
            plan.addProperty("arrayId", stringValue(slot, "arrayId", slotId));
        }
        plan.addProperty("sourceSlotId", slotId);
        if (stringValue(plan, "placementGroupId").isBlank()) {
            plan.addProperty("placementGroupId", stringValue(slot, "placementGroupId",
                    stringValue(plan, "arrayId", slotId)));
        }
        if (stringValue(plan, "displayRole").isBlank() && slot.has("displayRole")) {
            plan.addProperty("displayRole", stringValue(slot, "displayRole", slotId));
        }
        if (!plan.has("candidatePatchRefs")) {
            plan.add("candidatePatchRefs", requiredArrayCopy(slot, "candidatePatchRefs"));
        }
        if (!plan.has("structureIds")) {
            plan.add("structureIds", slotStructureIds(slot));
        }
        if (!plan.has("arrayCount")) {
            int arrayCount = intValue(slot, "arrayCount", 0);
            if (arrayCount <= 0) {
                throw new IllegalArgumentException("D4_ARRAY_COUNT_REQUIRED: array_fill slot "
                        + slotId + " must set arrayCount.");
            }
            plan.addProperty("arrayCount", arrayCount);
        }
        if (!plan.has("variantSelectionMode")) {
            plan.addProperty("variantSelectionMode", stringValue(slot, "variantSelectionMode", "seeded_random"));
        }
        for (String key : List.of("patterns", "structureWeights", "variantSeed", "priority",
                "clearanceBlocks", "smallClearanceBlocks", "vegetationMarginBlocks", "roadAccessMarginBlocks")) {
            copyIfPresent(slot, plan, key);
        }
        return plan;
    }

    public JsonObject minimalArrayLayoutPlan(JsonObject sourceDesignSlotPlan, String planningMode) {
        JsonObject plan = new JsonObject();
        boolean v03 = CityStructureArrayLayoutLoopPlanner.PLANNING_MODE_V03.equals(planningMode);
        plan.addProperty("schemaVersion", v03
                ? CityStructureArrayLayoutLoopPlanner.PLAN_SCHEMA_V03
                : CityStructureArrayLayoutLoopPlanner.PLAN_SCHEMA);
        plan.addProperty("planningMode", v03
                ? CityStructureArrayLayoutLoopPlanner.PLANNING_MODE_V03
                : CityStructureArrayLayoutLoopPlanner.PLANNING_MODE_V02);
        plan.addProperty("cityId", stringValue(sourceDesignSlotPlan, "cityId"));
        plan.addProperty("cityScale", stringValue(sourceDesignSlotPlan, "cityScale", "town"));
        JsonObject intent = new JsonObject();
        intent.addProperty("summary", "created by " + (v03 ? "array_layout_loop_v0_3" : "array_layout_loop_v0_2")
                + " workflow");
        plan.add("designIntent", intent);
        plan.add("layoutPlans", new JsonArray());
        return plan;
    }

    public JsonObject mergeStructureAnchorPlans(JsonObject basePlan,
                                                JsonObject appendedPlan,
                                                String cityId,
                                                JsonArray stageTrace) {
        String normalizedCityId = cityId == null || cityId.isBlank()
                ? stringValue(basePlan, "cityId", stringValue(appendedPlan, "cityId")) : cityId;
        JsonArray anchors = new JsonArray();
        Set<String> anchorIds = new LinkedHashSet<>();
        appendAnchors(anchors, anchorIds, basePlan, "base");
        appendAnchors(anchors, anchorIds, appendedPlan, "array");
        JsonObject plan = new JsonObject();
        plan.addProperty("schemaVersion", CityStructureAnchorPlanner.PLAN_SCHEMA);
        plan.addProperty("cityId", normalizedCityId);
        plan.add("anchors", anchors);
        JsonObject trace = new JsonObject();
        trace.addProperty("schemaVersion", "city_d4_staged_key_then_array_trace.v0.1");
        trace.addProperty("planningMode", "key_then_array");
        trace.addProperty("stageCount", stageTrace.size());
        trace.add("stages", stageTrace.deepCopy());
        plan.add("stagedD4Trace", trace);
        return plan;
    }

    private static List<String> placementOrder(JsonObject designSlotPlan, Set<String> slotIds) {
        JsonArray explicit = array(designSlotPlan, "placementOrder");
        List<String> order = new ArrayList<>();
        if (!explicit.isEmpty()) {
            for (JsonElement elem : explicit) {
                if (!elem.isJsonNull()) {
                    order.add(elem.getAsString());
                }
            }
            return order;
        }
        order.addAll(slotIds);
        return order;
    }

    private static String normalizePlacementStrategy(JsonObject slot) {
        String raw = stringValue(slot, "placementStrategy", "key_structure");
        return switch (raw) {
            case "array_fill", "array", "array_group" -> "array_fill";
            case "key_structure", "single_ai_selected", "single_anchor", "manual_anchor" -> "key_structure";
            default -> throw new IllegalArgumentException("D4_PLACEMENT_STRATEGY_UNSUPPORTED: "
                    + requiredString(slot, "slotId") + " uses " + raw + ".");
        };
    }

    private static void appendAnchors(JsonArray anchors,
                                      Set<String> anchorIds,
                                      JsonObject plan,
                                      String source) {
        for (JsonElement elem : array(plan, "anchors")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject anchor = elem.getAsJsonObject();
            String anchorId = requiredString(anchor, "anchorId");
            if (!anchorIds.add(anchorId)) {
                throw new IllegalArgumentException("D4_STAGED_DUPLICATE_ANCHOR_ID: " + anchorId
                        + " from " + source + ".");
            }
            JsonObject normalized = anchor.deepCopy();
            CityStructureAnchorPlanner.applyPlacementProvenance(normalized, normalized);
            anchors.add(normalized);
        }
    }

    private static JsonArray slotStructureIds(JsonObject slot) {
        JsonArray ids = new JsonArray();
        if (slot.has("structureIds") && slot.get("structureIds").isJsonArray()) {
            for (JsonElement elem : slot.getAsJsonArray("structureIds")) {
                if (!elem.isJsonNull()) {
                    ids.add(elem.getAsString());
                }
            }
        } else if (slot.has("structureId") && !slot.get("structureId").isJsonNull()) {
            ids.add(slot.get("structureId").getAsString());
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("D4_ARRAY_STRUCTURE_IDS_REQUIRED: array_fill slot "
                    + requiredString(slot, "slotId") + " must set structureId or structureIds[].");
        }
        return ids;
    }

    private static JsonArray requiredArrayCopy(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonArray()) {
            throw new IllegalArgumentException(key + " array is required.");
        }
        return obj.getAsJsonArray(key).deepCopy();
    }

    private static void copyIfPresent(JsonObject source, JsonObject target, String key) {
        if (source != null && source.has(key) && !target.has(key)) {
            target.add(key, source.get(key).deepCopy());
        }
    }

    private static JsonArray stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static JsonArray array(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull() || !obj.get(key).isJsonArray()) {
            return new JsonArray();
        }
        return obj.getAsJsonArray(key);
    }

    private static String requiredString(JsonObject obj, String key) {
        String value = stringValue(obj, key, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException(key + " is required.");
        }
        return value;
    }

    private static String stringValue(JsonObject obj, String key) {
        return stringValue(obj, key, "");
    }

    private static String stringValue(JsonObject obj, String key, String defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(key).getAsString();
    }

    private static int intValue(JsonObject obj, String key, int defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(key).getAsInt();
    }

    public record StagePlan(JsonObject sourceDesignSlotPlan,
                            JsonObject keyDesignSlotPlan,
                            List<JsonObject> arraySlots) {
    }
}
