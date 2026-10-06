package com.rinsing.geomantia.systems.realm_planning;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.realm_planning.application.access.PlanningAreaAccessConfig;
import com.rinsing.geomantia.systems.realm_planning.application.terrain.TerrainScalePatchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealmT4PatchPlanningServiceTest {
    @Test
    void addonMaskRejectsTheFullCityEnvelopeWithoutConsumingSelection() throws Exception {
        Path root = tempDir.resolve("addon_boundary"); Path run = root.resolve("run_t4");
        Files.createDirectories(run); writeArtifacts(run);
        // Outside all candidate cells; still inside the proposed capital's full protection envelope.
        com.rinsing.geomantia.systems.realm_planning.application.reservation.RegionReservationStore.create(run, "minecraft:overworld",
                List.of(new com.rinsing.geomantia.api.regions.ReservedRegion("boss:border", List.of(
                        new com.rinsing.geomantia.api.regions.RegionBounds(400, 40, 420, 60)))));
        var explorer = new PatchExplorerService(root);
        String selection = select(explorer, "addon_capital", "plain", "PLAIN-01");
        var service = new RealmT4PatchPlanningService(root, (id, value) -> null);
        JsonObject request = new JsonObject(); request.addProperty("runId", "run_t4");
        request.addProperty("realmId", "realm_a"); request.addProperty("planningSessionId", "addon_plan");
        service.create(request); request.addProperty("patchSelectionRef", selection);
        var failure = assertThrows(IllegalArgumentException.class, () -> service.selectCapital(request));
        assertTrue(failure.getMessage().contains("ADDON_REGION_RESERVED"), failure.toString());
        JsonObject session = JsonParser.parseString(Files.readString(run.resolve("realm_t4_patch_planning_addon_plan/planning_session.json"))).getAsJsonObject();
        assertTrue(session.getAsJsonArray("usedPatchSelectionRefs").isEmpty());
        assertTrue(session.getAsJsonArray("citySeeds").isEmpty());
    }

    @TempDir
    Path tempDir;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void rejectsSelectedCityInsideConfiguredOriginExclusion(boolean geographic) throws Exception {
        Path root = tempDir.resolve("gated_realm_debug");
        Path run = root.resolve("run_t4");
        Files.createDirectories(run);
        writeArtifacts(run);
        if (geographic) {
            JsonObject grid = new JsonObject(); grid.addProperty("cellStepBlocks",16);
            JsonArray cells = new JsonArray();
            for (int x = -4; x <= 32; x++) {
                JsonObject cell = new JsonObject(); cell.addProperty("gridX",x); cell.addProperty("gridZ",0);
                cell.addProperty("waterFrac",0); cells.add(cell);
            }
            grid.add("cells",cells);
            Files.writeString(run.resolve("world_feature_grid.json"),grid.toString());
            Files.writeString(run.resolve("world_survey_manifest.json"),"{\"status\":\"sealed\"}");
        }
        PatchExplorerService explorer = new PatchExplorerService(root);
        String selectionRef = select(explorer, "gated_capital", "plain", "PLAIN-01");
        RealmT4PatchPlanningService service = new RealmT4PatchPlanningService(root, (runId, registry) -> null,
                new PlanningAreaAccessConfig(true, geographic ? 0 : 64, geographic ? 0 : 512, Set.of("minecraft:overworld")));
        JsonObject createRequest = new JsonObject();
        createRequest.addProperty("runId", "run_t4");
        createRequest.addProperty("realmId", "realm_a");
        createRequest.addProperty("planningSessionId", "gated_plan");
        service.create(createRequest);
        JsonObject capitalRequest = new JsonObject();
        capitalRequest.addProperty("runId", "run_t4");
        capitalRequest.addProperty("planningSessionId", "gated_plan");
        capitalRequest.addProperty("patchSelectionRef", selectionRef);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> service.selectCapital(capitalRequest));
        assertTrue(failure.getMessage().startsWith("T4_CITY_INSIDE_INITIAL_ACTIVITY_EXCLUSION"));
    }

    @Test
    void rejectsAnOverlappingReservationFromAnotherRealmBeforeConsumingSelection() throws Exception {
        Path root = tempDir.resolve("cross_realm"); Path run = root.resolve("run_t4");
        Files.createDirectories(run); writeArtifacts(run);
        JsonObject registry = JsonParser.parseString(Files.readString(run.resolve("city_seed_registry.json"))).getAsJsonObject();
        JsonObject neighbor = registrySeed("neighbor", "outpost", 30, 0, 1);
        neighbor.addProperty("realmId", "realm_b"); neighbor.addProperty("theoreticalScale", "outpost");
        registry.getAsJsonArray("citySeeds").add(neighbor);
        Files.writeString(run.resolve("city_seed_registry.json"),registry.toString());
        var explorer = new PatchExplorerService(root);
        String selection = select(explorer,"cross_capital","plain","PLAIN-01");
        var service = new RealmT4PatchPlanningService(root,(id,value)->null);
        JsonObject request = new JsonObject(); request.addProperty("runId","run_t4");
        request.addProperty("realmId","realm_a");request.addProperty("planningSessionId","cross_plan");
        service.create(request);request.addProperty("patchSelectionRef",selection);
        var failure = assertThrows(IllegalArgumentException.class,()->service.selectCapital(request));
        assertTrue(failure.getMessage().contains("T4_CITY_PROTECTION_OVERLAP"));
        assertTrue(failure.getMessage().contains("neighbor"));
        JsonObject session=JsonParser.parseString(Files.readString(run.resolve("realm_t4_patch_planning_cross_plan/planning_session.json"))).getAsJsonObject();
        assertTrue(session.getAsJsonArray("usedPatchSelectionRefs").isEmpty());
        assertTrue(session.getAsJsonArray("citySeeds").isEmpty());
    }

    @Test
    void migratesLegacyCapitalCoordinatesToIntentWithoutInheritingTheSite() throws Exception {
        Path root = tempDir.resolve("legacy_realm_debug");
        Path run = root.resolve("run_t4");
        Files.createDirectories(run);
        writeArtifacts(run);
        Files.delete(run.resolve("capital_city_intents.json"));
        JsonObject legacy = new JsonObject();
        legacy.addProperty("citySeedId", "city_realm_a_capital");
        legacy.addProperty("realmId", "realm_a");
        legacy.addProperty("cityRole", "capital");
        legacy.addProperty("theoreticalScale", "capital");
        legacy.add("anchorGrid", point(20, 0));
        legacy.add("anchorBlock", point(320, 0));
        JsonArray legacySeeds = new JsonArray();
        legacySeeds.add(legacy);
        Files.writeString(run.resolve("capital_city_seeds.json"), legacySeeds.toString());

        RealmT4PatchPlanningService service = new RealmT4PatchPlanningService(root, (runId, registry) -> null);
        JsonObject request = new JsonObject();
        request.addProperty("runId", "run_t4");
        request.addProperty("realmId", "realm_a");
        request.addProperty("planningSessionId", "legacy_plan");
        JsonObject session = service.create(request).getAsJsonObject("planningSession");

        assertTrue(session.getAsJsonArray("citySeeds").isEmpty());
        JsonObject intent = session.getAsJsonObject("capitalIntent");
        assertEquals("legacy_coordinates_discarded", intent.get("migrationMode").getAsString());
        assertFalse(intent.has("anchorGrid"));
        assertFalse(intent.has("anchorBlock"));
    }

    @Test
    void requiresAiSelectedCapitalBeforeCitiesAndReplacesAutoRealmSeedsOnFinalize() throws Exception {
        Path root = tempDir.resolve("realm_debug");
        Path run = root.resolve("run_t4");
        Files.createDirectories(run);
        writeArtifacts(run);

        PatchExplorerService explorer = new PatchExplorerService(root);
        String capitalSelectionRef = select(explorer, "explore_capital", "plain", "PLAIN-01");
        String townSelectionRef = select(explorer, "explore_town", "upland", "UPLAND-01");

        boolean[] synchronizedArtifacts = {false};
        RealmT4PatchPlanningService service = new RealmT4PatchPlanningService(root, (runId, registry) -> {
            synchronizedArtifacts[0] = true;
            Files.writeString(run.resolve("city_seed_registry.json"), registry.toString());
            JsonObject response = new JsonObject();
            JsonObject artifacts = new JsonObject();
            artifacts.addProperty("citySeedRegistry", "city_seed_registry.json");
            artifacts.addProperty("t4Report", "t4_report.json");
            response.add("artifacts", artifacts);
            response.add("nextActions", strings("rerun_acceptance"));
            return response;
        });
        JsonObject createRequest = new JsonObject();
        createRequest.addProperty("runId", "run_t4");
        createRequest.addProperty("realmId", "realm_a");
        createRequest.addProperty("planningSessionId", "plan_a");
        Path quantityConfig = root.getParent().resolve("config/geomantia/realm_planning.json");
        Files.createDirectories(quantityConfig.getParent());
        Files.writeString(quantityConfig, new RealmPopulationConfig(3,2,2).asJson().toString());
        JsonObject created = service.create(createRequest);
        assertEquals(2,created.getAsJsonObject("planningSession").getAsJsonObject("cityCountRequirements").get("minCitiesPerRealm").getAsInt());
        // An active session keeps its frozen contract when the player changes future defaults.
        Files.writeString(quantityConfig, new RealmPopulationConfig(3,1,1).asJson().toString());
        JsonArray initialSeeds = created.getAsJsonObject("planningSession").getAsJsonArray("citySeeds");
        assertEquals(0, initialSeeds.size());
        assertEquals("awaiting_selection", created.getAsJsonObject("planningSession")
                .get("capitalSelectionStatus").getAsString());

        JsonObject finalizeRequest = new JsonObject();
        finalizeRequest.addProperty("runId", "run_t4");
        finalizeRequest.addProperty("planningSessionId", "plan_a");
        assertThrows(IllegalArgumentException.class, () -> service.finalizePlanning(finalizeRequest));

        JsonObject addBeforeCapital = addRequest(townSelectionRef, "city_too_early", "town");
        assertThrows(IllegalArgumentException.class, () -> service.add(addBeforeCapital));

        JsonObject capitalRequest = new JsonObject();
        capitalRequest.addProperty("runId", "run_t4");
        capitalRequest.addProperty("planningSessionId", "plan_a");
        capitalRequest.addProperty("patchSelectionRef", capitalSelectionRef);
        capitalRequest.addProperty("selectionReason", "AI chose the largest continuous plain");
        JsonObject selectedCapital = service.selectCapital(capitalRequest);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.finalizePlanning(finalizeRequest))
                .getMessage().contains("CITY_COUNT_CONFIG"));
        JsonObject capital = selectedCapital.getAsJsonObject("selectedCapital");
        assertEquals("capital", capital.get("role").getAsString());
        assertEquals(capitalSelectionRef, capital.getAsJsonObject("source")
                .get("patchSelectionRef").getAsString());
        assertEquals("ai_candidate_selection", capital.getAsJsonObject("source")
                .get("siteSelectionMode").getAsString());
        assertThrows(IllegalArgumentException.class, () -> service.selectCapital(capitalRequest));

        JsonObject capitalThroughAdd = addRequest(townSelectionRef, "city_second_capital", "capital");
        capitalThroughAdd.addProperty("role", "capital");
        assertThrows(IllegalArgumentException.class, () -> service.add(capitalThroughAdd));

        JsonObject tooLarge = addRequest(townSelectionRef, "city_too_large", "large_city");
        tooLarge.addProperty("minimumAreaBlocks", 999_999);
        assertThrows(IllegalArgumentException.class, () -> service.add(tooLarge));

        JsonObject add = addRequest(townSelectionRef, "city_ai_forest", "town");
        add.add("coreFunctions", strings("market", "farming"));
        JsonObject added = service.add(add);
        assertEquals(townSelectionRef, added.getAsJsonObject("addedCitySeed").getAsJsonObject("source")
                .get("patchSelectionRef").getAsString());
        assertThrows(IllegalArgumentException.class, () -> service.add(add));

        RealmAndCityIntegrationTest.reviewProposal(service,"run_t4","plan_a");
        JsonObject finalized = service.finalizePlanning(finalizeRequest);
        JsonArray finalSeeds = finalized.getAsJsonObject("citySeedRegistry").getAsJsonArray("citySeeds");
        assertEquals(2, finalSeeds.size());
        assertTrue(hasSeed(finalSeeds, "city_realm_a_capital"));
        assertTrue(hasSeed(finalSeeds, "city_ai_forest"));
        assertFalse(hasSeed(finalSeeds, "city_realm_a_auto_port"));
        assertTrue(synchronizedArtifacts[0]);
        assertEquals("t4_report.json", finalized.getAsJsonObject("artifacts").get("t4Report").getAsString());
        assertEquals("rerun_acceptance", finalized.getAsJsonArray("nextActions").get(0).getAsString());

        JsonObject persisted = JsonParser.parseString(Files.readString(run.resolve("city_seed_registry.json")))
                .getAsJsonObject();
        assertEquals(2, persisted.getAsJsonArray("citySeeds").size());
        JsonObject persistedAiSeed = findSeed(persisted.getAsJsonArray("citySeeds"), "city_ai_forest");
        assertEquals(townSelectionRef, persistedAiSeed.getAsJsonObject("source")
                .get("patchSelectionRef").getAsString());
    }

    @Test
    void convertsTScaleAnchorBlockBackToWorldSurveyGridForTerritory() throws Exception {
        Path root = tempDir.resolve("scaled_anchor_realm_debug");
        Path run = root.resolve("run_t4");
        Files.createDirectories(run);
        writeArtifacts(run);
        PatchExplorerService explorer = new PatchExplorerService(root);
        JsonObject openRequest = new JsonObject();
        openRequest.addProperty("runId", "run_t4");
        openRequest.addProperty("scopeType", "realm_t4");
        openRequest.addProperty("realmId", "realm_a");
        JsonObject opened = explorer.open(openRequest, (runId, scopeType, scopeId, identity, sourceCells) -> {
            List<TerrainScalePatchService.PatchCell> cells = new ArrayList<>();
            for (int x = 8; x <= 16; x++) {
                cells.add(new TerrainScalePatchService.PatchCell(x, 0, x * 8, 0,
                        "t_plain_1", "plain", 64.0, false, "minecraft:plains", 0.0, 0.0));
            }
            return new TerrainScalePatchService.Result(8, List.of(new TerrainScalePatchService.Patch(
                    "t_plain_1", "plain", 1.0, List.of("plain_patch"), cells)), 1, cells.size());
        });
        JsonObject showRequest = new JsonObject();
        showRequest.addProperty("runId", "run_t4");
        showRequest.addProperty("sessionId", opened.get("sessionId").getAsString());
        showRequest.add("interestTypes", strings("plain"));
        explorer.showCandidates(showRequest);
        JsonObject selectRequest = new JsonObject();
        selectRequest.addProperty("runId", "run_t4");
        selectRequest.addProperty("sessionId", opened.get("sessionId").getAsString());
        selectRequest.addProperty("candidateId", "PLAIN-01");
        String selectionRef = explorer.selectCandidate(selectRequest).get("patchSelectionRef").getAsString();

        RealmT4PatchPlanningService service = new RealmT4PatchPlanningService(root, (runId, registry) -> null);
        JsonObject createRequest = new JsonObject();
        createRequest.addProperty("runId", "run_t4");
        createRequest.addProperty("realmId", "realm_a");
        createRequest.addProperty("planningSessionId", "scaled_anchor_plan");
        service.create(createRequest);
        JsonObject capitalRequest = new JsonObject();
        capitalRequest.addProperty("runId", "run_t4");
        capitalRequest.addProperty("planningSessionId", "scaled_anchor_plan");
        capitalRequest.addProperty("patchSelectionRef", selectionRef);
        JsonObject capital = service.selectCapital(capitalRequest).getAsJsonObject("selectedCapital");

        assertEquals(68, capital.getAsJsonObject("anchorBlock").get("x").getAsInt());
        assertEquals(4, capital.getAsJsonObject("anchorGrid").get("x").getAsInt(),
                "T grid x=8 must be converted from block x=68 to W grid x=4");
    }

    private static String select(PatchExplorerService explorer, String sessionId, String patchType,
            String candidateId) throws Exception {
        JsonObject openRequest = new JsonObject();
        openRequest.addProperty("runId", "run_t4");
        openRequest.addProperty("scopeType", "realm_t4");
        openRequest.addProperty("realmId", "realm_a");
        openRequest.addProperty("sessionId", sessionId);
        JsonObject open = explorer.open(openRequest);
        JsonObject showRequest = new JsonObject();
        showRequest.addProperty("runId", "run_t4");
        showRequest.addProperty("sessionId", open.get("sessionId").getAsString());
        showRequest.add("interestTypes", strings(patchType));
        explorer.showCandidates(showRequest);
        JsonObject selectRequest = new JsonObject();
        selectRequest.addProperty("runId", "run_t4");
        selectRequest.addProperty("sessionId", open.get("sessionId").getAsString());
        selectRequest.addProperty("candidateId", candidateId);
        return explorer.selectCandidate(selectRequest).get("patchSelectionRef").getAsString();
    }

    private static JsonObject addRequest(String selectionRef, String citySeedId, String scale) {
        JsonObject request = new JsonObject();
        request.addProperty("runId", "run_t4");
        request.addProperty("planningSessionId", "plan_a");
        request.addProperty("patchSelectionRef", selectionRef);
        request.addProperty("citySeedId", citySeedId);
        request.addProperty("role", "market_town");
        request.addProperty("theoreticalScale", scale);
        return request;
    }

    private static void writeArtifacts(Path run) throws Exception {
        JsonObject context = new JsonObject();
        context.addProperty("sealed", true);
        context.addProperty("cellStepBlocks", 16);
        Files.writeString(run.resolve("world_survey_context.json"), context.toString());

        JsonObject patchMap = new JsonObject();
        JsonArray cells = new JsonArray();
        for (int x = 0; x < 24; x++) {
            JsonObject cell = new JsonObject();
            cell.addProperty("gridX", x < 12 ? x : x + 256);
            cell.addProperty("gridZ", 0);
            cell.addProperty("blockX", (x < 12 ? x : x + 256) * 16);
            cell.addProperty("blockZ", 0);
            cell.addProperty("continentId", "continent_0");
            cell.addProperty("patchId", x < 12 ? "plain_patch" : "upland_patch");
            cell.addProperty("landform", x < 12 ? "plain" : "upland");
            cell.addProperty("baseLandform", x < 12 ? "lowland" : "upland");
            cell.addProperty("landformConfidence", 0.9);
            JsonObject biomeHist = new JsonObject();
            biomeHist.addProperty(x < 12 ? "minecraft:plains" : "minecraft:forest", 15);
            biomeHist.addProperty(x < 12 ? "minecraft:forest" : "minecraft:plains", 1);
            cell.add("biomeHist", biomeHist);
            cells.add(cell);
        }
        patchMap.add("cells", cells);
        patchMap.add("patches", new JsonArray());
        Files.writeString(run.resolve("world_patch_map.json"), patchMap.toString());

        JsonObject territory = new JsonObject();
        territory.addProperty("territoryMapId", "territory_run_t4");
        JsonArray territoryCells = new JsonArray();
        for (int x = 0; x <= 20; x++) {
            JsonObject cell = new JsonObject();
            cell.addProperty("gridX", x < 12 ? x : x + 256);
            cell.addProperty("gridZ", 0);
            cell.addProperty("realmId", "realm_a");
            cell.addProperty("status", "owned");
            territoryCells.add(cell);
        }
        territory.add("territoryCells", territoryCells);
        Files.writeString(run.resolve("realm_territory_map.json"), territory.toString());

        JsonObject intent = new JsonObject();
        intent.addProperty("citySeedId", "city_realm_a_capital");
        intent.addProperty("realmId", "realm_a");
        intent.addProperty("cityRole", "capital");
        intent.addProperty("theoreticalScale", "capital");
        intent.addProperty("mustExist", true);
        intent.add("requiredConditions", strings("land", "inside_realm"));
        intent.add("coreFunctions", strings("administration", "market", "defense"));
        intent.addProperty("realmCoreSelectionId", "selection_realm_a");
        intent.addProperty("sourceMode", "t2_realm_core_intent");
        JsonArray intents = new JsonArray();
        intents.add(intent);
        Files.writeString(run.resolve("capital_city_intents.json"), intents.toString());

        JsonObject capital = registrySeed("city_realm_a_capital", "capital", 20, 0, 4);
        JsonObject autoPort = registrySeed("city_realm_a_auto_port", "port", 10, 0, 2);
        JsonObject registry = new JsonObject();
        registry.addProperty("registryId", "registry_run_t4");
        registry.addProperty("surveyId", "survey_run_t4");
        registry.addProperty("territoryMapId", "territory_run_t4");
        JsonArray seeds = new JsonArray();
        seeds.add(capital);
        seeds.add(autoPort);
        registry.add("citySeeds", seeds);
        Files.writeString(run.resolve("city_seed_registry.json"), registry.toString());
    }

    private static JsonObject registrySeed(String id, String role, int x, int z, int radius) {
        JsonObject seed = new JsonObject();
        seed.addProperty("citySeedId", id);
        seed.addProperty("realmId", "realm_a");
        seed.addProperty("role", role);
        seed.addProperty("theoreticalScale", "capital".equals(role) ? "capital" : "town");
        seed.add("anchorGrid", point(x, z));
        seed.add("anchorBlock", point(x * 16, z * 16));
        seed.addProperty("planningRadiusCells", radius);
        seed.addProperty("candidateRangeCells", 4);
        seed.addProperty("subregionId", role);
        seed.addProperty("candidateId", id);
        seed.addProperty("graphDistanceToNearestCity", -1);
        seed.add("requiredConditions", strings("land", "inside_realm"));
        seed.add("coreFunctions", strings());
        seed.addProperty("trigger", "always");
        JsonObject source = new JsonObject();
        source.addProperty("reason", "fixture");
        seed.add("source", source);
        return seed;
    }

    private static JsonObject point(int x, int z) {
        JsonObject point = new JsonObject();
        point.addProperty("x", x);
        point.addProperty("z", z);
        return point;
    }

    private static JsonArray strings(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) array.add(value);
        return array;
    }

    private static boolean hasSeed(JsonArray seeds, String id) {
        return findSeed(seeds, id) != null;
    }

    private static JsonObject findSeed(JsonArray seeds, String id) {
        for (var element : seeds) {
            if (id.equals(element.getAsJsonObject().get("citySeedId").getAsString())) {
                return element.getAsJsonObject();
            }
        }
        return null;
    }
}
