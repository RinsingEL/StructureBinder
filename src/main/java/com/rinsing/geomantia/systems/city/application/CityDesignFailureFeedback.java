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
        Set<String> failedKeys = new LinkedHashSet<>();
        JsonArray selections = array(trace, "selections");
        // Intermediate rejected slots may later succeed. Only final unsatisfied selections are failures.
        for (JsonElement value : selections) {
            JsonObject selection = value.getAsJsonObject();
            if ("no_legal_candidate".equals(string(selection, "status")))
                failedKeys.add(string(selection, "groupId") + "\n" + string(selection, "structureRef"));
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
                    + "the city blindly, or change unrelated parameters; host diagnosis is required.");
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
