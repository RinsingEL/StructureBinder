package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Program-owned preparation before waking the designer, including restart/revision turns. */
final class PreparedCityDesignTurn {
    static final List<String> TOOLS = com.rinsing.geomantia.systems.city.application.CityD4Workflow.TOOLS;
    private PreparedCityDesignTurn() { }

    static boolean applies(ProviderPlanningDiscovery.PlanningStep step) {
        return step.stage() == ProviderPlanningDiscovery.Stage.CITY
                && (Set.of("city_prepare_d4_blueprint_context", "city_submit_d4_blueprint").contains(step.nextAction()) || TOOLS.contains(step.nextAction()));
    }

    static Input prepare(JsonObject queue, PlanningToolExecutor gateway, Path debugRoot) throws Exception {
        JsonObject prepared = PlanningTurnControl.payload(gateway.execute("city_prepare_d4_blueprint_context", new JsonObject()));
        String failure = PlanningTurnControl.failure(prepared);
        if (!failure.isBlank()) throw new IOException(failure);
        JsonObject context = prepared.getAsJsonObject("cityBlueprintContext");
        if (context == null || !context.has("contextId")) throw new IOException("PLANNING_DESIGN_CONTEXT_REQUIRED");
        JsonObject state = queue.deepCopy();
        state.add("preparedBlueprintContext", context.deepCopy());
        if (prepared.has("designSession")) state.add("designSession", prepared.get("designSession").deepCopy());
        if (prepared.has("designReviewWorkflow")) state.add("designReviewWorkflow", prepared.get("designReviewWorkflow").deepCopy());
        state.addProperty("contextId", context.get("contextId").getAsString());
        for (String key : List.of("failureCount", "maximumFailureCount", "remainingFailureCount", "retryAllowed", "failureBudget")) {
            if (prepared.has(key)) state.add(key, prepared.get(key).deepCopy());
        }
        state.add("d4Workflow", prepared.get("d4Workflow").deepCopy());
        state.addProperty("nextAction", prepared.getAsJsonObject("d4Workflow").get("nextAction").getAsString());
        state.add("instruction", prepared.getAsJsonObject("d4Workflow").get("instruction").deepCopy());
        Path root = debugRoot.toRealPath();
        Set<Path> images = new LinkedHashSet<>();
        JsonObject revision = CityRevisionEvidence.load(root, context,
                prepared.has("failureBudget") ? prepared.getAsJsonObject("failureBudget") : prepared);
        if (revision != null) {
            state.add("revisionEvidence", revision);
            // Show the actual failed layout first, followed by terrain, not three unchanged terrain views.
            if (revision.has("compiledPreview")) collectImages(revision.get("compiledPreview"), root, images);
        }
        if (images.isEmpty()) {
            collectImages(context.get("patchReviewEvidence"), root, images);
            collectImages(context.get("d3ReviewPackage"), root, images);
        }
        if (images.isEmpty()) throw new IOException("PLANNING_DESIGN_PREVIEW_REQUIRED");
        return new Input(state, List.copyOf(images));
    }

    static void collectImages(JsonElement value, Path root, Set<Path> images) throws IOException {
        if (value == null || value.isJsonNull() || images.size() >= 3) return;
        if (value.isJsonObject()) {
            for (var entry : value.getAsJsonObject().entrySet()) collectImages(entry.getValue(), root, images);
        } else if (value.isJsonArray()) {
            for (var child : value.getAsJsonArray()) collectImages(child, root, images);
        } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                && value.getAsString().toLowerCase(Locale.ROOT).endsWith(".png")) {
            Path requested = Path.of(value.getAsString());
            Path path = (requested.isAbsolute() ? requested : root.resolve(requested)).normalize();
            if (path.startsWith(root) && Files.isRegularFile(path) && path.toRealPath().startsWith(root)) images.add(path);
        }
    }

    record Input(JsonObject state, List<Path> images) { }
}
