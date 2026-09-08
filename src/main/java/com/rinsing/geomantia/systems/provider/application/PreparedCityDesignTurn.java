package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Program-owned preparation before waking the designer, including restart/revision turns. */
final class PreparedCityDesignTurn {
    static final List<String> TOOLS = List.of("patch_explorer_show_candidates", "city_submit_d4_blueprint");
    private PreparedCityDesignTurn() { }

    static boolean applies(ProviderPlanningDiscovery.PlanningStep step) {
        return step.stage() == ProviderPlanningDiscovery.Stage.CITY
                && Set.of("city_prepare_d4_blueprint_context", "city_submit_d4_blueprint").contains(step.nextAction());
    }

    static Input prepare(JsonObject queue, DeepSeekToolLoopClient.ToolExecutor gateway, Path debugRoot) throws Exception {
        JsonObject prepared = PlanningTurnControl.payload(gateway.execute("city_prepare_d4_blueprint_context", new JsonObject()));
        String failure = PlanningTurnControl.failure(prepared);
        if (!failure.isBlank()) throw new IOException(failure);
        JsonObject context = prepared.getAsJsonObject("cityBlueprintContext");
        if (context == null || !context.has("contextId")) throw new IOException("PLANNING_DESIGN_CONTEXT_REQUIRED");
        JsonObject state = queue.deepCopy();
        state.add("preparedBlueprintContext", context.deepCopy());
        state.addProperty("contextId", context.get("contextId").getAsString());
        for (String key : List.of("failureCount", "maximumFailureCount", "remainingFailureCount", "retryAllowed", "failureBudget")) {
            if (prepared.has(key)) state.add(key, prepared.get(key).deepCopy());
        }
        state.addProperty("nextAction", "city_submit_d4_blueprint");
        state.addProperty("instruction", "The host has already prepared this COMPLETE current frozen design view and its "
                + "actual terrain images. It supersedes older truncated tool results in this session. All authored "
                + "structure functions/styles, selectable pools/profiles and design algorithms are below. Design or "
                + "revise this city one district at a time: inspect the three terrain views, select a Top Patch, "
                + "then submit submissionMode=DRAFT with the districts designed so far. Inspect the resulting layout "
                + "and correct current failures before adding the next district. Preserve valid districts; use a full "
                + "cityBlueprint when adding groups (patch supports replacement only). Use RELATIVE_WEIGHTS for partial groups. "
                + "After inspecting buildings, roads and landscape together, submit submissionMode=FINAL. "
                + "Format correction allows ten attempts independently of the five design-compilation failures. "
                + "Omit host-owned schema/cityId/sourceD3Ref/catalogSnapshotRef/generationSeed. "
                + "For local revisions, submit replace-only blueprintPatch with revisionEvidence.baseDraftHash for a rejected draft "
                + "or revisionEvidence.baseBlueprintHash for an accepted design (never both), "
                + "preserving unaffected choices. For full input you may explicitly choose RELATIVE_WEIGHTS to avoid summing ratios by hand. "
                + "Choose either fillPools=[{poolRef,weight},...] or legacy fillPoolRef, never both. Each expansion unit rolls one pool; "
                + "the host tries GRID, LINEAR, COURTYARD, COMPACT, ORGANIC_COMPACT in fixed order and reserves access. "
                + "connectionPlan may override structurePools or structurePoolRef, otherwise it inherits all fill pools. "
                + "proportionMode is a TOOL ARGUMENT beside cityBlueprint, NOT inside cityBlueprint. "
                + "Do not query status or prepare again. Only use "
                + "patch_explorer_show_candidates with the existing patchReviewEvidence.sessionId if you need additional "
                + "terrain candidates. Keep prior validation feedback and the existing failure budget; do not restart the design.");
        Path root = debugRoot.toRealPath();
        Set<Path> images = new LinkedHashSet<>();
        JsonObject revision = CityRevisionEvidence.load(root, context,
                prepared.has("failureBudget") ? prepared.getAsJsonObject("failureBudget") : prepared);
        if (revision != null) {
            state.add("revisionEvidence", revision);
            // Show the actual failed layout first, followed by terrain, not three unchanged terrain views.
            if (revision.has("compiledPreview")) collectImages(revision.get("compiledPreview"), root, images);
        }
        collectImages(context.get("patchReviewEvidence"), root, images);
        collectImages(context.get("d3ReviewPackage"), root, images);
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
