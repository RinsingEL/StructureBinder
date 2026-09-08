package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import java.util.*;

/** Model-facing local evidence. A failed finite search is not a proof of insufficient space. */
public final class CityDesignFailureFeedback {
    private CityDesignFailureFeedback() { }

    public static JsonObject summarize(JsonObject blueprint, JsonObject trace, String reason) {
        JsonObject result = new JsonObject();
        result.addProperty("schema", "city_design_failure_feedback.v1");
        result.addProperty("reasonCode", reason);
        result.addProperty("capacityInsufficiencyProven", false);
        JsonArray failures = new JsonArray();
        if (trace.has("compilationAcceptance")) {
            JsonObject acceptance = trace.getAsJsonObject("compilationAcceptance");
            for (JsonElement block : array(acceptance, "hardBlocks")) {
                String message = block.getAsString();
                JsonObject failure = new JsonObject();
                failure.addProperty("message", message);
                failure.addProperty("fieldPath", "$.groups");
                failure.addProperty("jsonPointer", "/groups");
                JsonArray affected = new JsonArray();
                for (JsonElement entry : array(blueprint, "groups")) {
                    String id = string(entry.getAsJsonObject(), "groupId");
                    if (!id.isBlank() && message.contains(id)) affected.add(id);
                }
                failure.add("affectedGroupIds", affected);
                JsonArray relations = array(blueprint, "relations");
                for (int i = 0; i < relations.size(); i++) {
                    JsonObject relation = relations.get(i).getAsJsonObject();
                    if ("CONNECTION".equals(string(relation,"relationKind")) && message.contains(
                            string(relation,"fromGroupId") + " -> " + string(relation,"toGroupId"))) {
                        failure.addProperty("fieldPath", "$.relations[" + i + "]");
                        failure.addProperty("jsonPointer", "/relations/" + i);
                    }
                }
                if (trace.has("cityMainRoadPlan")) {
                    JsonArray routes = new JsonArray();
                    for (JsonElement edge : array(trace.getAsJsonObject("cityMainRoadPlan"), "skippedConnections")) {
                        JsonObject e = edge.getAsJsonObject();
                        if (message.contains(string(e,"fromGroupId") + " -> " + string(e,"toGroupId"))) routes.add(e.deepCopy());
                    }
                    failure.add("connectionFailures", routes);
                }
                failure.addProperty("instruction", "Current final blocker. Preserve successful buildings and other groups. "
                        + "The host selects road connectors. Review only the affected destination/patch/layout constraints; "
                        + "candidate search exhaustion does not prove a minimum size. No exact numeric correction is established.");
                failures.add(failure);
            }
            result.add("failures", failures);
            result.add("parameterAdjustments", new JsonArray());
            result.addProperty("instruction", "Only final acceptance blockers are listed; earlier rejected candidates are not repair targets. "
                    + "Use affectedGroupIds and connectionFailures to make a relevant local revision; retain unaffected design.");
            return result;
        }
        Set<String> failedKeys = new LinkedHashSet<>();
        JsonArray selections = array(trace, "selections");
        // Intermediate rejected slots may later succeed. Only final unsatisfied selections are failures.
        for (JsonElement value : selections) {
            JsonObject selection = value.getAsJsonObject();
            if ("no_legal_candidate".equals(string(selection, "status")) && !string(selection, "structureRef").isBlank())
                failedKeys.add(string(selection, "groupId") + "\n" + string(selection, "structureRef"));
            if (Set.of("committed", "selected").contains(string(selection, "status")))
                failedKeys.remove(string(selection, "groupId") + "\n" + string(selection, "structureRef"));
        }
        for (String key : failedKeys) {
            String[] ids = key.split("\n", -1);
            JsonObject failure = new JsonObject();
            failure.addProperty("groupId", ids[0]);
            failure.addProperty("structureRef", ids[1]);
            int index = groupIndex(blueprint, ids[0]);
            failure.addProperty("fieldPath", index < 0 ? "$.groups" : "$.groups[" + index + "]");
            failure.addProperty("jsonPointer", index < 0 ? "/groups" : "/groups/" + index);
            JsonObject counts = new JsonObject();
            JsonArray samples = new JsonArray();
            Set<String> blocks = new LinkedHashSet<>();
            int attempts = 0;
            for (JsonElement value : selections) {
                JsonObject s = value.getAsJsonObject();
                if (!ids[0].equals(string(s, "groupId")) || !ids[1].equals(string(s, "structureRef"))) continue;
                for (JsonElement a : array(s, "attempts")) {
                    attempts++;
                    JsonObject attempt = a.getAsJsonObject();
                    if (attempt.has("filterReasonCounts")) for (var entry : attempt.getAsJsonObject("filterReasonCounts").entrySet())
                        counts.addProperty(entry.getKey(), entry.getValue().getAsInt()
                                + (counts.has(entry.getKey()) ? counts.get(entry.getKey()).getAsInt() : 0));
                    for (JsonElement sample : array(attempt, "failedAttemptPositions"))
                        if (samples.size() < 8) samples.add(sample.deepCopy());
                    for (JsonElement block : array(attempt, "hardBlocks")) blocks.add(block.getAsString());
                }
            }
            JsonArray adjustable = new JsonArray();
            for (String field : List.of("preferredPatchRefs", "preferredPatchZone", "algorithmProfileRef", "densityClass", "terrainPolicy")) {
                JsonObject parameter = new JsonObject();
                parameter.addProperty("jsonPointer", "/groups/" + index + "/" + field);
                parameter.addProperty("field", field);
                adjustable.add(parameter);
            }
            failure.add("adjustableParameters", adjustable);
            failure.addProperty("attemptCount", attempts);
            failure.add("filterReasonCounts", counts);
            failure.add("positionSamples", samples);
            failure.addProperty("positionSamplesAreExhaustive", false);
            JsonArray hardBlocks = new JsonArray();
            blocks.stream().limit(8).forEach(hardBlocks::add);
            failure.add("hardBlocks", hardBlocks);
            failure.addProperty("instruction", "The solver found no placement for this required structure. "
                    + "These are rejected candidate positions, not proof that the district is too small. "
                    + "Preserve required content and unaffected groups. Do not guess a minimum width, enlarge "
                    + "the city blindly, or change unrelated parameters. You may try a related patch, layout or spacing revision; no exact numeric solution is proven.");
            failures.add(failure);
        }
        result.add("failures", failures);
        result.add("parameterAdjustments", new JsonArray());
        result.addProperty("instruction", "Only measured constraints may produce numeric parameter adjustments. "
                + "Empty parameterAdjustments means no proven parameter correction is available, not permission "
                + "to invent one. The full trace is archival; use this inline evidence without filesystem access.");
        return result;
    }

    private static int groupIndex(JsonObject blueprint, String id) {
        JsonArray groups = array(blueprint, "groups");
        for (int i = 0; i < groups.size(); i++)
            if (id.equals(string(groups.get(i).getAsJsonObject(), "groupId"))) return i;
        return -1;
    }
    private static JsonArray array(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : new JsonArray();
    }
    private static String string(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsString() : "";
    }
}
