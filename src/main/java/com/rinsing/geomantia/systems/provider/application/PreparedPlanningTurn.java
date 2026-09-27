package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** One evidence and execution contract for both embedded and external agents. */
public record PreparedPlanningTurn(JsonObject state, List<Path> images, List<String> tools, PlanningTurnControl control) {
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
            if (run.stage() == ProviderPlanningDiscovery.Stage.T1) {
                var atlas = RealmCoreAtlas.prepare(sources.directory(), debugRoot.resolve(run.runId()), sources.authoringBrief());
                designState.add("authoringBrief", atlas.brief());
                designState.addProperty("creativeGuidance", AgentPromptConfig.read("realm/t1.md"));
                var images = new java.util.ArrayList<>(designImages);
                images.addAll(atlas.images());
                designImages = List.copyOf(images);
            } else {
                designState.add("authoringBrief", sources.authoringBrief().deepCopy());
            }
        }
        // D4 receives the same editable guidance in its canonical context; do not send it twice.
        if (run.stage() != ProviderPlanningDiscovery.Stage.EXTENSION && !PreparedCityDesignTurn.applies(run)) {
            designState.addProperty("environmentStyleGuidance", AgentPromptConfig.read("realm/environment_style.md"));
        }
        if (run.stage() == ProviderPlanningDiscovery.Stage.CITY || run.stage() == ProviderPlanningDiscovery.Stage.EXTENSION) {
            attachRealmIntent(designState, debugRoot.resolve(run.runId()), run.realmId());
        }
        List<String> allowed = designTools;
        JsonObject scopedD3Evidence = d3Evidence;
        PlanningToolExecutor scoped = (tool, arguments) -> {
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

    static void attachRealmIntent(JsonObject state, Path runDirectory, String realmId) throws IOException {
        Path path = runDirectory.resolve("realm_profiles.json");
        if (realmId.isBlank() || !java.nio.file.Files.isRegularFile(path)) return;
        var value = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(path));
        var profiles = value.isJsonArray() ? value.getAsJsonArray() : value.getAsJsonObject().getAsJsonArray("realmProfiles");
        if (profiles == null) return;
        for (var entry : profiles) {
            var profile = entry.getAsJsonObject();
            if (realmId.equals(profile.get("realmId").getAsString())) {
                state.add("realmDesignIntent", profile.deepCopy());
                state.addProperty("realmDesignGuidance", "继承realmDesignIntent.theme中的国度职责与生活方式，结合本城实际地形和群系落实差异；上游AI拟定的建筑传统仍须验证环境协调，不能以继承theme为由沿用不相容主体风格。作者明确设定与素材标注继续有效，不改写素材语义，不虚构未实现能力。");
                return;
            }
        }
    }
}
