package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import java.util.Set;

/** Solver/resource failures do not establish that the designer's intent is invalid. */
public final class CityBlueprintFailureRouting {
    private static final Set<String> PROGRAM_FAILURES = Set.of(
            "CITY_BLUEPRINT_REQUIRED_STRUCTURE_SEARCH_LIMIT_EXHAUSTED",
            "CITY_BLUEPRINT_LANDSCAPE_SEARCH_LIMIT_EXHAUSTED",
            "CITY_BLUEPRINT_INTERNAL_SAFETY_LIMIT_REACHED",
            "CITY_BLUEPRINT_COMPILED_ANCHOR_FINALIZATION_FAILED");

    private CityBlueprintFailureRouting() { }

    public static boolean isProgramFailure(String reason) {
        return PROGRAM_FAILURES.contains(reason);
    }

    public static boolean isProgramFailure(String reason, JsonElement evidence) {
        return isProgramFailure(reason) || hasMissingAuthoredFrontage(evidence);
    }

    private static boolean hasMissingAuthoredFrontage(JsonElement evidence) {
        if (evidence == null || evidence.isJsonNull()) return false;
        if (evidence.isJsonArray()) {
            for (JsonElement child : evidence.getAsJsonArray()) if (hasMissingAuthoredFrontage(child)) return true;
        } else if (evidence.isJsonObject()) {
            for (var entry : evidence.getAsJsonObject().entrySet()) {
                if ("hardBlocks".equals(entry.getKey()) && entry.getValue().isJsonArray()) {
                    for (JsonElement block : entry.getValue().getAsJsonArray()) {
                        if (block.isJsonPrimitive() && block.getAsString().startsWith("D4_ARRAY_LAYOUT_FRONTAGE_ENTRANCE_AMBIGUOUS"))
                            return true;
                    }
                } else if (hasMissingAuthoredFrontage(entry.getValue())) return true;
            }
        }
        return false;
    }

    public static void blockOnProgram(JsonObject response) {
        response.addProperty("failureOwner", "program");
        response.addProperty("status", "blocked_by_program");
        response.addProperty("nextAction", "city_post_d4_auto_compile_retry");
        JsonObject policy = new JsonObject();
        policy.addProperty("instruction", "Preserve the accepted Blueprint. The host must resolve the execution or "
                + "authored-template metadata failure before retrying; do not replace required content, redesign, "
                + "or automatically repeat an unchanged compilation to hide this failure.");
        policy.addProperty("sourceCodeInspectionAllowed", false);
        policy.addProperty("projectDocumentationInspectionAllowed", false);
        policy.addProperty("rawRunArtifactInspectionAllowed", false);
        response.add("agentRecoveryPolicy", policy);
    }
}
