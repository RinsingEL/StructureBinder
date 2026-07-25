package com.rinsing.geomantia.systems.realm_planning;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealmT4PatchPlanningServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void retainsCapitalConsumesSelectionAndReplacesAutoRealmSeedsOnFinalize() throws Exception {
        Path root = tempDir.resolve("realm_debug");
        Path run = root.resolve("run_t4");
        Files.createDirectories(run);
        writeArtifacts(run);

        PatchExplorerService explorer = new PatchExplorerService(root);
        JsonObject openRequest = new JsonObject();
        openRequest.addProperty("runId", "run_t4");
        openRequest.addProperty("scopeType", "realm_t4");
        openRequest.addProperty("realmId", "realm_a");
        JsonObject open = explorer.open(openRequest);
        JsonObject showRequest = new JsonObject();
        showRequest.addProperty("runId", "run_t4");
        showRequest.addProperty("sessionId", open.get("sessionId").getAsString());
        showRequest.add("interestTypes", strings("minecraft:plains"));
        explorer.showCandidates(showRequest);
        JsonObject selectRequest = new JsonObject();
        selectRequest.addProperty("runId", "run_t4");
        selectRequest.addProperty("sessionId", open.get("sessionId").getAsString());
        selectRequest.addProperty("candidateId", "MINECRAFT_PLAINS-01");
        String selectionRef = explorer.selectCandidate(selectRequest).get("patchSelectionRef").getAsString();

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
        JsonObject created = service.create(createRequest);
        JsonArray initialSeeds = created.getAsJsonObject("planningSession").getAsJsonArray("citySeeds");
        assertEquals(1, initialSeeds.size());
        assertEquals("capital", initialSeeds.get(0).getAsJsonObject().get("role").getAsString());

        JsonObject tooLarge = addRequest(selectionRef, "city_too_large", "large_city");
        assertThrows(IllegalArgumentException.class, () -> service.add(tooLarge));

        JsonObject add = addRequest(selectionRef, "city_ai_plain", "town");
        add.add("coreFunctions", strings("market", "farming"));
        JsonObject added = service.add(add);
        assertEquals(selectionRef, added.getAsJsonObject("addedCitySeed").getAsJsonObject("source")
                .get("patchSelectionRef").getAsString());
        assertThrows(IllegalArgumentException.class, () -> service.add(add));

        JsonObject finalizeRequest = new JsonObject();
        finalizeRequest.addProperty("runId", "run_t4");
        finalizeRequest.addProperty("planningSessionId", "plan_a");
        JsonObject finalized = service.finalizePlanning(finalizeRequest);
        JsonArray finalSeeds = finalized.getAsJsonObject("citySeedRegistry").getAsJsonArray("citySeeds");
        assertEquals(2, finalSeeds.size());
        assertTrue(hasSeed(finalSeeds, "city_realm_a_capital"));
        assertTrue(hasSeed(finalSeeds, "city_ai_plain"));
        assertFalse(hasSeed(finalSeeds, "city_realm_a_auto_port"));
        assertTrue(synchronizedArtifacts[0]);
        assertEquals("t4_report.json", finalized.getAsJsonObject("artifacts").get("t4Report").getAsString());
        assertEquals("rerun_acceptance", finalized.getAsJsonArray("nextActions").get(0).getAsString());

        JsonObject persisted = JsonParser.parseString(Files.readString(run.resolve("city_seed_registry.json")))
                .getAsJsonObject();
        assertEquals(2, persisted.getAsJsonArray("citySeeds").size());
        JsonObject persistedAiSeed = findSeed(persisted.getAsJsonArray("citySeeds"), "city_ai_plain");
        assertEquals(selectionRef, persistedAiSeed.getAsJsonObject("source")
                .get("patchSelectionRef").getAsString());
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
        for (int x = 0; x < 4; x++) {
            JsonObject cell = new JsonObject();
            cell.addProperty("gridX", x);
            cell.addProperty("gridZ", 0);
            cell.addProperty("blockX", x * 16);
            cell.addProperty("blockZ", 0);
            cell.addProperty("continentId", "continent_0");
            cell.addProperty("patchId", "plain_patch");
            cell.addProperty("landform", "plain");
            cell.addProperty("baseLandform", "lowland");
            cell.addProperty("landformConfidence", 0.9);
            JsonObject biomeHist = new JsonObject();
            biomeHist.addProperty("minecraft:plains", 15);
            biomeHist.addProperty("minecraft:forest", 1);
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
            cell.addProperty("gridX", x);
            cell.addProperty("gridZ", 0);
            cell.addProperty("realmId", "realm_a");
            cell.addProperty("status", "owned");
            territoryCells.add(cell);
        }
        territory.add("territoryCells", territoryCells);
        Files.writeString(run.resolve("realm_territory_map.json"), territory.toString());

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
