package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Opens the scoped workspace and supplies a neutral first page; choosing remains the designer's job. */
final class PreparedRealmDesignTurn {
    static PreparedCityDesignTurn.Input prepare(ProviderPlanningDiscovery.PlanningStep step,
                                                DeepSeekToolLoopClient.ToolExecutor gateway, Path root) throws Exception {
        JsonObject state = step.state().deepCopy();
        if (step.stage() == ProviderPlanningDiscovery.Stage.T4 && !state.has("openPlanningSession")) {
            JsonObject created = call(gateway, "realm_t4_patch_planning_create", new JsonObject());
            state.add("openPlanningSession", created.getAsJsonObject("planningSession").deepCopy());
        }
        JsonObject explorer = call(gateway, "patch_explorer_open", new JsonObject());
        JsonObject request = new JsonObject();
        request.add("sessionId", explorer.get("sessionId"));
        JsonArray types = new JsonArray();
        for (JsonElement entry : explorer.getAsJsonArray("typeCatalog"))
            types.add(entry.getAsJsonObject().get("patchType"));
        if (types.isEmpty()) throw new IOException("PLANNING_HOST_BLOCKED: PATCH_EXPLORER_SCOPE_EMPTY");
        request.add("interestTypes", types);
        request.addProperty("pageSize", 1);
        JsonObject candidates = call(gateway, "patch_explorer_show_candidates", request);
        state.add("preparedPatchExplorer", explorer);
        state.add("initialCandidates", candidates);
        state.addProperty("nextAction", step.stage() == ProviderPlanningDiscovery.Stage.T2
                ? "patch_explorer_select_candidate"
                : "selected".equals(state.getAsJsonObject("openPlanningSession").get("capitalSelectionStatus").getAsString())
                ? "realm_t4_patch_planning_finalize" : "realm_t4_patch_planning_select_capital");
        state.addProperty("instruction", "The host has prepared the scope, planning session and one candidate per terrain type. "
                + "This is an overview, not a preferred design. Inspect the images and author brief; request more candidates "
                + "with patch_explorer_show_candidates when useful. T2: choose a displayed candidate with a reason; the host commits it. "
                + "T4: select_capital/add_city accept sessionId + candidateId directly and the host freezes the selection. "
                + "Resume existing citySeeds; never choose a second capital. Finalize only after your city decisions are complete.");
        Set<Path> images = new LinkedHashSet<>();
        PreparedCityDesignTurn.collectImages(candidates, root.toRealPath(), images);
        PreparedCityDesignTurn.collectImages(explorer, root.toRealPath(), images);
        if (images.isEmpty()) throw new IOException("PLANNING_DESIGN_PREVIEW_REQUIRED");
        return new PreparedCityDesignTurn.Input(state, List.copyOf(images));
    }

    static JsonObject call(DeepSeekToolLoopClient.ToolExecutor gateway, String tool, JsonObject args) throws Exception {
        JsonObject output = PlanningTurnControl.payload(gateway.execute(tool, args));
        String failure = PlanningTurnControl.failure(output);
        if (!failure.isBlank()) throw new IOException(failure);
        return output;
    }

    static JsonElement execute(ProviderPlanningDiscovery.Stage stage, DeepSeekToolLoopClient.ToolExecutor gateway,
                               String tool, JsonObject args) throws Exception {
        if (stage == ProviderPlanningDiscovery.Stage.T2 && "patch_explorer_select_candidate".equals(tool)) {
            JsonObject selected = call(gateway, tool, args);
            JsonObject commit = new JsonObject();
            commit.add("patchSelectionRef", selected.get("patchSelectionRef"));
            commit.add("reason", args.get("selectionReason"));
            JsonObject result = call(gateway, "realm_t2_select_coordinate", commit);
            result.addProperty("hostDecisionCommitted", true);
            return result;
        }
        if (stage == ProviderPlanningDiscovery.Stage.T4 && Set.of("realm_t4_patch_planning_select_capital",
                "realm_t4_patch_planning_add_city").contains(tool) && args.has("candidateId")) {
            if (args.has("patchSelectionRef")) throw new IllegalArgumentException("PLANNING_SELECTION_EXACTLY_ONE_REQUIRED");
            JsonObject selection = new JsonObject();
            selection.add("sessionId", args.get("sessionId"));
            selection.add("candidateId", args.get("candidateId"));
            selection.add("selectionReason", args.get("selectionReason"));
            JsonObject frozen = call(gateway, "patch_explorer_select_candidate", selection);
            JsonObject commit = args.deepCopy();
            commit.remove("sessionId"); commit.remove("candidateId");
            commit.add("patchSelectionRef", frozen.get("patchSelectionRef"));
            return gateway.execute(tool, commit);
        }
        return gateway.execute(tool, args);
    }
}
