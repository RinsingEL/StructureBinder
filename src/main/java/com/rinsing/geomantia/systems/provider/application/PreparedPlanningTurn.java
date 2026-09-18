package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** One evidence and execution contract for both embedded and external agents. */
record PreparedPlanningTurn(JsonObject state, List<Path> images, List<String> tools, PlanningTurnControl control) {
    static PreparedPlanningTurn prepare(ProviderPlanningDiscovery.PlanningStep run,
            ProviderPlanningToolGateway gateway, Path serverDirectory, Path debugRoot) throws Exception {
        com.google.gson.JsonObject designState = run.state().deepCopy();
        List<Path> designImages = run.initialImages();
        List<String> designTools = PlanningStepPolicy.toolsFor(run.stage());
        JsonObject d3Evidence = null;
        if (PreparedCityDesignTurn.applies(run)) {
            var prepared = PreparedCityDesignTurn.prepare(designState, gateway, debugRoot);
            designState = prepared.state();
            designImages = prepared.images();
            designTools = PreparedCityDesignTurn.TOOLS;
        } else {
            if (run.stage() == ProviderPlanningDiscovery.Stage.T2 || run.stage() == ProviderPlanningDiscovery.Stage.T4) {
                var prepared = PreparedRealmDesignTurn.prepare(run, gateway, debugRoot);
                designState = prepared.state();
                designImages = prepared.images();
            } else if (run.stage() == ProviderPlanningDiscovery.Stage.CITY) {
                Path d3 = debugRoot.resolve(run.runId()).resolve("city_test_runs").resolve(run.citySeedId())
                        .resolve("steps/d3/city_landform_review_package.json");
                JsonObject review = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(d3)).getAsJsonObject();
                d3Evidence = review;
                designState.add("d3ReviewPackage", CityD3ReviewDecisionView.overview(review));
                JsonObject registry = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(
                        debugRoot.resolve(run.runId()).resolve("city_seed_registry.json"))).getAsJsonObject();
                for (var seed : registry.getAsJsonArray("citySeeds"))
                    if (run.citySeedId().equals(seed.getAsJsonObject().get("citySeedId").getAsString()))
                        designState.add("citySeed", seed.deepCopy());
                java.util.Set<Path> images = new java.util.LinkedHashSet<>();
                PreparedCityDesignTurn.collectImages(review, debugRoot.toRealPath(), images);
                if (images.isEmpty()) throw new IOException("PLANNING_DESIGN_PREVIEW_REQUIRED");
                designImages = List.copyOf(images);
                designTools = List.of(CityD3ReviewDecisionView.TOOL, "city_review_d3_site");
            }
            var sources = new ManagedCityPlanningSources(serverDirectory).resolve();
            designState.add("authoringBrief", sources.authoringBrief().deepCopy());
        }
        List<String> allowed = designTools;
        JsonObject scopedD3Evidence = d3Evidence;
        DeepSeekToolLoopClient.ToolExecutor scoped = (tool, arguments) -> {
            if (!allowed.contains(tool)) {
                var denied = new com.google.gson.JsonObject();
                denied.addProperty("ok", false);
                denied.addProperty("error", "PLANNING_TOOL_NOT_IN_CURRENT_DECISION_SCOPE: " + tool);
                return denied;
            }
            if (CityD3ReviewDecisionView.TOOL.equals(tool))
                return CityD3ReviewDecisionView.page(scopedD3Evidence, arguments);
            return PreparedRealmDesignTurn.execute(run.stage(), gateway, tool, arguments);
        };
        return new PreparedPlanningTurn(designState, designImages, designTools, new PlanningTurnControl(scoped, designState));
    }
}
