package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public final class CityWorkflowCandidateSelector {
    public JsonObject chooseAnchorCandidate(JsonObject candidateSet) {
        JsonObject best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (JsonElement slotElem : array(candidateSet, "slotCandidates")) {
            JsonObject slot = slotElem.getAsJsonObject();
            String slotId = stringValue(slot, "slotId", stringValue(candidateSet, "currentSlotId", ""));
            for (JsonElement candidateElem : array(slot, "candidates")) {
                JsonObject candidate = candidateElem.getAsJsonObject();
                double score = totalScore(candidate);
                if (best == null || score > bestScore) {
                    best = candidate;
                    bestScore = score;
                    if (!best.has("slotId")) {
                        best.addProperty("slotId", slotId);
                    }
                }
            }
        }
        if (best == null) {
            throw new IllegalArgumentException("WORKFLOW_NO_D4_CANDIDATE: current slot produced no candidate.");
        }
        return best;
    }

    public JsonObject chooseStructureClusterGroup(JsonObject candidateSet) {
        return chooseGroup(candidateSet, "groupCandidates",
                "WORKFLOW_NO_D4_STRUCTURE_CLUSTER_GROUP: no complete group candidate.");
    }

    public JsonObject chooseArrayCandidate(JsonObject candidateSet) {
        return chooseGroup(candidateSet, "arrayCandidates",
                "WORKFLOW_NO_D4_ARRAY_CANDIDATE: no complete array candidate.");
    }

    private JsonObject chooseGroup(JsonObject candidateSet, String candidatesKey, String emptyMessage) {
        JsonObject best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (JsonElement groupElem : array(candidateSet, candidatesKey)) {
            if (!groupElem.isJsonObject()) {
                continue;
            }
            JsonObject group = groupElem.getAsJsonObject();
            double score = totalScore(group);
            if (best == null || score > bestScore) {
                best = group;
                bestScore = score;
            }
        }
        if (best == null) {
            throw new IllegalArgumentException(emptyMessage);
        }
        return best;
    }

    private static double totalScore(JsonObject candidate) {
        JsonObject scoreBreakdown = candidate.has("scoreBreakdown")
                && candidate.get("scoreBreakdown").isJsonObject()
                ? candidate.getAsJsonObject("scoreBreakdown") : new JsonObject();
        return scoreBreakdown.has("total") && !scoreBreakdown.get("total").isJsonNull()
                ? scoreBreakdown.get("total").getAsDouble() : 0.0;
    }

    private static JsonArray array(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull() || !obj.get(key).isJsonArray()) {
            return new JsonArray();
        }
        return obj.getAsJsonArray(key);
    }

    private static String stringValue(JsonObject obj, String key, String defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(key).getAsString();
    }
}
