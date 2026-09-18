package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Shared stage policy: deterministic transitions and the model's decision scope. */
final class PlanningStepPolicy {
    private PlanningStepPolicy() { }
    static List<String> toolsFor(ProviderPlanningDiscovery.Stage stage) {
        return switch (stage) {
            case W -> List.of("realm_w_refresh");
            case T1 -> List.of("realm_t1_prepare");
            case T2 -> List.of("patch_explorer_show_candidates", "patch_explorer_select_candidate");
            case T3 -> List.of("realm_t3_expand");
            case T4 -> List.of("patch_explorer_show_candidates",
                    "realm_t4_patch_planning_select_capital", "realm_t4_patch_planning_add_city",
                    "realm_t4_patch_planning_finalize");
            case QUEUE_REFRESH -> List.of("city_design_queue_refresh");
            case CITY -> java.util.stream.Stream.concat(List.of("city_design_queue_status", "city_plan_d3", "city_review_d3_site",
                    "patch_explorer_open", "patch_explorer_show_candidates",
                    "city_prepare_d4_blueprint_context").stream(), com.rinsing.geomantia.systems.city.application.CityD4Workflow.TOOLS.stream()).toList();
            case WAITING, COMPLETE -> List.of();
        };
    }

    static boolean hostOnly(ProviderPlanningDiscovery.PlanningStep step) {
        return step.stage() == ProviderPlanningDiscovery.Stage.W || step.stage() == ProviderPlanningDiscovery.Stage.T3
                || step.stage() == ProviderPlanningDiscovery.Stage.QUEUE_REFRESH
                || step.stage() == ProviderPlanningDiscovery.Stage.CITY
                && List.of("city_plan_d3", "patch_explorer_show_candidates").contains(step.nextAction());
    }

    static JsonObject hostArguments(ProviderPlanningDiscovery.PlanningStep step, Path debugRoot) throws IOException {
        JsonObject args = new JsonObject();
        if (!"patch_explorer_show_candidates".equals(step.nextAction())) return args;
        for (var entry : step.state().getAsJsonArray("items")) {
            JsonObject item = entry.getAsJsonObject();
            if (step.citySeedId().equals(item.get("citySeedId").getAsString()))
                args.add("sessionId", item.get("patchExplorerSessionId"));
        }
        if (!args.has("sessionId") || args.get("sessionId").isJsonNull()
                || args.get("sessionId").getAsString().isBlank()) {
            throw new IOException("CITY_D4_PATCH_REVIEW_SESSION_REQUIRED: " + step.citySeedId());
        }
        // D3's region-wide patch list can contain types with no cells inside the city scope.
        // Use the same frozen candidate catalog as showCandidates, not an unscoped type union.
        return new com.rinsing.geomantia.systems.realm_planning.PatchExplorerService(debugRoot)
                .initialPageRequest(step.runId(), args.get("sessionId").getAsString());
    }

}
