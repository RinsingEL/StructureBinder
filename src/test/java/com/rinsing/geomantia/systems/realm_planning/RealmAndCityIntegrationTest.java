package com.rinsing.geomantia.systems.realm_planning;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RealmAndCityIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void designContentPreservedAndHandedOver() throws Exception {
        Path root = tempDir.resolve("design_handover");
        Path run = root.resolve("run_t4");
        Files.createDirectories(run);
        writeArtifacts(run);

        var explorer = new PatchExplorerService(root);
        String capitalSelection = select(explorer, "handover_capital", "plain", "PLAIN-01");
        String secondSelection = select(explorer, "handover_second", "upland", "UPLAND-01");

        var service = new RealmT4PatchPlanningService(root, (rId, reg) -> {
            Files.writeString(run.resolve("city_seed_registry.json"), reg.toString());
            JsonObject resp = new JsonObject();
            JsonObject arts = new JsonObject();
            arts.addProperty("citySeedRegistry", "city_seed_registry.json");
            resp.add("artifacts", arts);
            return resp;
        });
        JsonObject createReq = new JsonObject();
        createReq.addProperty("runId", "run_t4");
        createReq.addProperty("realmId", "realm_a");
        createReq.addProperty("planningSessionId", "handover_plan");
        service.create(createReq);

        // 1. Select capital with independent town scale, service hierarchy, positioning, functions, gameplay requirements, and style direction
        JsonObject capReq = new JsonObject();
        capReq.addProperty("runId", "run_t4");
        capReq.addProperty("planningSessionId", "handover_plan");
        capReq.addProperty("patchSelectionRef", capitalSelection);
        capReq.addProperty("name", "霜峰王城");
        capReq.addProperty("theoreticalScale", "town");
        capReq.addProperty("serviceHierarchy", "national_center");
        capReq.addProperty("positioning", "高山峡谷要冲，兼顾议事与防御");
        JsonArray capFuncs = new JsonArray();
        capFuncs.add("administration");
        capFuncs.add("defense");
        capReq.add("functionalFocus", capFuncs);
        JsonArray capGameplay = new JsonArray();
        capGameplay.add("create_council");
        capGameplay.add("armory_crafting");
        capReq.add("gameplayRequirements", capGameplay);
        JsonObject capStyle = new JsonObject();
        capStyle.addProperty("architecture", "nordic_stone");
        capStyle.addProperty("vegetation", "taiga_sparse");
        capReq.add("styleDirection", capStyle);
        capReq.addProperty("selectionReason", "优质山前平原，控制峡谷入口");

        JsonObject capResult = service.selectCapital(capReq).getAsJsonObject("selectedCapital");
        assertEquals("霜峰王城", capResult.get("name").getAsString());
        assertEquals("town", capResult.get("theoreticalScale").getAsString());
        assertEquals("national_center", capResult.get("serviceHierarchy").getAsString());
        assertEquals("高山峡谷要冲，兼顾议事与防御", capResult.get("positioning").getAsString());
        assertEquals(capFuncs, capResult.getAsJsonArray("functionalFocus"));
        assertEquals(capGameplay, capResult.getAsJsonArray("gameplayRequirements"));
        assertEquals(capStyle, capResult.getAsJsonObject("styleDirection"));

        // 2. Add non-capital town with specialized outpost hierarchy, functions, gameplay, and style
        JsonObject secondReq = new JsonObject();
        secondReq.addProperty("runId", "run_t4");
        secondReq.addProperty("planningSessionId", "handover_plan");
        secondReq.addProperty("patchSelectionRef", secondSelection);
        secondReq.addProperty("citySeedId", "city_realm_a_iron_mine");
        secondReq.addProperty("role", "mining_town");
        secondReq.addProperty("name", "深铁哨镇");
        secondReq.addProperty("theoreticalScale", "village");
        secondReq.addProperty("serviceHierarchy", "specialized_outpost");
        secondReq.addProperty("positioning", "富铁高地采矿据点与熔炼工坊");
        JsonArray secondFuncs = new JsonArray();
        secondFuncs.add("mining");
        secondFuncs.add("metallurgy");
        secondReq.add("functionalFocus", secondFuncs);
        JsonArray secondGameplay = new JsonArray();
        secondGameplay.add("furnace_array");
        secondReq.add("gameplayRequirements", secondGameplay);
        JsonObject secondStyle = new JsonObject();
        secondStyle.addProperty("architecture", "dwarven_masonry");
        secondReq.add("styleDirection", secondStyle);
        secondReq.addProperty("selectionReason", "邻近高地矿脉");

        JsonObject secondResult = service.add(secondReq).getAsJsonObject("addedCitySeed");
        assertEquals("深铁哨镇", secondResult.get("name").getAsString());
        assertEquals("village", secondResult.get("theoreticalScale").getAsString());
        assertEquals("specialized_outpost", secondResult.get("serviceHierarchy").getAsString());
        assertEquals("富铁高地采矿据点与熔炼工坊", secondResult.get("positioning").getAsString());
        assertEquals(secondFuncs, secondResult.getAsJsonArray("functionalFocus"));
        assertEquals(secondGameplay, secondResult.getAsJsonArray("gameplayRequirements"));
        assertEquals(secondStyle, secondResult.getAsJsonObject("styleDirection"));

        // 3. Finalize and verify persistence into city_seed_registry.json
        JsonObject finalizeReq = new JsonObject();
        finalizeReq.addProperty("runId", "run_t4");
        finalizeReq.addProperty("planningSessionId", "handover_plan");
        reviewProposal(service, "run_t4", "handover_plan");
        service.finalizePlanning(finalizeReq);

        Path registryPath = run.resolve("city_seed_registry.json");
        assertTrue(Files.isRegularFile(registryPath));
        JsonObject registry = JsonParser.parseString(Files.readString(registryPath)).getAsJsonObject();
        JsonArray seeds = registry.getAsJsonArray("citySeeds");
        assertEquals(2, seeds.size());

        JsonObject savedCapital = null;
        JsonObject savedMine = null;
        for (var el : seeds) {
            JsonObject s = el.getAsJsonObject();
            if ("city_realm_a_capital".equals(s.get("citySeedId").getAsString())) savedCapital = s;
            if ("city_realm_a_iron_mine".equals(s.get("citySeedId").getAsString())) savedMine = s;
        }
        assertNotNull(savedCapital);
        assertNotNull(savedMine);

        assertEquals("霜峰王城", savedCapital.get("name").getAsString());
        assertEquals("town", savedCapital.get("theoreticalScale").getAsString());
        assertEquals("national_center", savedCapital.get("serviceHierarchy").getAsString());
        assertEquals("高山峡谷要冲，兼顾议事与防御", savedCapital.get("positioning").getAsString());
        assertEquals(capFuncs, savedCapital.getAsJsonArray("functionalFocus"));
        assertEquals(capGameplay, savedCapital.getAsJsonArray("gameplayRequirements"));
        assertEquals(capStyle, savedCapital.getAsJsonObject("styleDirection"));

        assertEquals("深铁哨镇", savedMine.get("name").getAsString());
        assertEquals("village", savedMine.get("theoreticalScale").getAsString());
        assertEquals("specialized_outpost", savedMine.get("serviceHierarchy").getAsString());
        assertEquals("富铁高地采矿据点与熔炼工坊", savedMine.get("positioning").getAsString());
        assertEquals(secondFuncs, savedMine.getAsJsonArray("functionalFocus"));
        assertEquals(secondGameplay, savedMine.getAsJsonArray("gameplayRequirements"));
        assertEquals(secondStyle, savedMine.getAsJsonObject("styleDirection"));

        // 4. Verify deserialization via RealmPlanningService.citySeedFromJson
        Method citySeedFromJson = RealmPlanningService.class.getDeclaredMethod("citySeedFromJson", JsonObject.class);
        citySeedFromJson.setAccessible(true);
        Object deserialized = citySeedFromJson.invoke(null, savedCapital);
        assertNotNull(deserialized);
        Method asJsonMethod = deserialized.getClass().getDeclaredMethod("asJson");
        asJsonMethod.setAccessible(true);
        JsonObject roundTripJson = (JsonObject) asJsonMethod.invoke(deserialized);
        assertEquals("霜峰王城", roundTripJson.get("name").getAsString());
        assertEquals("town", roundTripJson.get("theoreticalScale").getAsString());
        assertEquals("national_center", roundTripJson.get("serviceHierarchy").getAsString());
        assertEquals("高山峡谷要冲，兼顾议事与防御", roundTripJson.get("positioning").getAsString());
        assertEquals(capGameplay, roundTripJson.getAsJsonArray("gameplayRequirements"));
        assertEquals(capStyle, roundTripJson.getAsJsonObject("styleDirection"));
    }

    @Test
    void terrainExpansionCostsAppliedAndDecoupledFromBlocking() throws Exception {
        // 1. Verify RealmProfileInput schema accepts terrainCosts
        JsonObject profile = new JsonObject();
        profile.addProperty("realmId", "dwarf_realm");
        profile.addProperty("name", "铁砧矮人同盟");
        profile.addProperty("targetContinentId", "continent_0");
        profile.addProperty("theme", "mountain mining realm");
        profile.add("cultureTags", strings("dwarven", "mountain_craft"));
        profile.add("industryTags", strings("metallurgy", "stone_cutting"));
        profile.add("materialTags", strings("deepslate", "iron"));
        profile.add("landformPreferences", strings("ridge", "upland"));
        profile.add("avoidLandforms", strings("ocean"));

        JsonObject scalePlan = new JsonObject();
        scalePlan.addProperty("priority", "major");
        scalePlan.addProperty("normalizationGroup", "continent_0");
        scalePlan.addProperty("targetAreaRatio", 0.3);
        scalePlan.addProperty("minAreaRatio", 0.2);
        scalePlan.addProperty("maxAreaRatio", 0.4);
        profile.add("scalePlan", scalePlan);

        JsonObject expansionStyle = new JsonObject();
        expansionStyle.addProperty("waterAffinity", 0.2);
        expansionStyle.addProperty("compactness", 0.6);
        expansionStyle.addProperty("coastalBias", 0.1);
        expansionStyle.addProperty("resourceSeeking", 0.8);
        expansionStyle.addProperty("borderPressure", 0.4);
        expansionStyle.addProperty("mountainAffinity", 0.8);
        expansionStyle.addProperty("forestAffinity", -0.2);
        expansionStyle.addProperty("seaCrossingPolicy", "limited");

        JsonObject terrainCosts = new JsonObject();
        terrainCosts.addProperty("mountain", 0.6);
        terrainCosts.addProperty("water", 4.5);
        expansionStyle.add("terrainCosts", terrainCosts);
        profile.add("expansionStyle", expansionStyle);

        JsonObject req = new JsonObject();
        JsonArray profiles = new JsonArray();
        profiles.add(profile);
        req.add("realmProfiles", profiles);
        JsonArray validated = RealmProfileInput.requireProfiles(req);
        assertEquals(1, validated.size());

        // 2. Test terrainCostProfile reflection on RealmPlanningService
        Method fromJson = Class.forName("com.rinsing.geomantia.systems.realm_planning.RealmPlanningService$RealmProfile")
                .getDeclaredMethod("fromJson", JsonObject.class, String.class, int.class);
        fromJson.setAccessible(true);
        Object realmProfileObj = fromJson.invoke(null, profile, "continent_0", 0);

        RealmPlanningService planningService = new RealmPlanningService(tempDir);
        Method costProfileMethod = RealmPlanningService.class.getDeclaredMethod("terrainCostProfile",
                Class.forName("com.rinsing.geomantia.systems.realm_planning.RealmPlanningService$RealmProfile"));
        costProfileMethod.setAccessible(true);
        Object costProfile = costProfileMethod.invoke(planningService, realmProfileObj);
        assertNotNull(costProfile);

        Method costForMethod = costProfile.getClass().getDeclaredMethod("costFor", String.class);
        costForMethod.setAccessible(true);
        double ridgeCost = (double) costForMethod.invoke(costProfile, "ridge");
        double waterCost = (double) costForMethod.invoke(costProfile, "water");
        assertEquals(0.6, ridgeCost, 1e-4, "Ridge cost should be updated to custom mountain cost 0.6");
        assertEquals(4.5, waterCost, 1e-4, "Water cost should be updated to custom water cost 4.5");

        // 3. Test edgeBlocked: high water cost with 'limited' does NOT block, while 'none' DOES block
        Method edgeBlockedMethod = RealmPlanningService.class.getDeclaredMethod("edgeBlocked",
                Class.forName("com.rinsing.geomantia.systems.realm_planning.RealmPlanningService$RealmRun"),
                String.class,
                Class.forName("com.rinsing.geomantia.systems.realm_planning.RealmPlanningService$WorldCell"),
                Class.forName("com.rinsing.geomantia.systems.realm_planning.RealmPlanningService$WorldCell"));
        edgeBlockedMethod.setAccessible(true);

        Path root = tempDir.resolve("test_expansion");
        Path runDir = root.resolve("run_exp");
        Files.createDirectories(runDir);
        Class<?> runClass = Class.forName("com.rinsing.geomantia.systems.realm_planning.RealmPlanningService$RealmRun");
        var runConstructor = runClass.getDeclaredConstructor(String.class, Path.class, WorldSurveyResult.class);
        runConstructor.setAccessible(true);
        Object runObj = runConstructor.newInstance("run_exp", runDir, null);

        // Add profile to run
        var profilesField = runObj.getClass().getDeclaredField("profiles");
        profilesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Object> runProfiles = (List<Object>) profilesField.get(runObj);
        runProfiles.add(realmProfileObj);

        // Create a water cell
        Class<?> featureClass = Class.forName("com.rinsing.geomantia.systems.realm_planning.WorldFeatureCell");
        Class<?> cellClass = Class.forName("com.rinsing.geomantia.systems.realm_planning.RealmPlanningService$WorldCell");
        var cellConstructor = cellClass.getDeclaredConstructor(
                int.class, int.class, int.class, int.class, String.class, String.class,
                String.class, String.class, double.class, double.class, double.class,
                List.class, featureClass, int.class);
        cellConstructor.setAccessible(true);
        Object waterCell = cellConstructor.newInstance(
                0, 1, 0, 16, "continent_0", "patch_water", "water", "water",
                60.0, 0.0, 0.0, List.of(), null, 16);

        // Limited policy with high cost: edgeBlocked must return false!
        boolean blockedLimited = (boolean) edgeBlockedMethod.invoke(planningService, runObj, "dwarf_realm", null, waterCell);
        assertFalse(blockedLimited, "Limited water crossing policy must NOT block water edges!");

        // Switch policy to 'none': edgeBlocked must return true!
        expansionStyle.addProperty("seaCrossingPolicy", "none");
        Object noneProfileObj = fromJson.invoke(null, profile, "continent_0", 0);
        runProfiles.clear();
        runProfiles.add(noneProfileObj);
        boolean blockedNone = (boolean) edgeBlockedMethod.invoke(planningService, runObj, "dwarf_realm", null, waterCell);
        assertTrue(blockedNone, "None water crossing policy MUST block water edges!");
    }

    @Test
    void capitalScaleCanBeIndependentlyChosen() throws Exception {
        Path root = tempDir.resolve("capital_scale");
        Path run = root.resolve("run_t4");
        Files.createDirectories(run);
        writeArtifacts(run);

        var explorer = new PatchExplorerService(root);
        String capitalSelection = select(explorer, "independent_cap", "plain", "PLAIN-01");

        var service = new RealmT4PatchPlanningService(root, (id, value) -> null);
        JsonObject createReq = new JsonObject();
        createReq.addProperty("runId", "run_t4");
        createReq.addProperty("realmId", "realm_a");
        createReq.addProperty("planningSessionId", "scale_plan");
        service.create(createReq);

        // Select capital with theoreticalScale="town" despite role="capital"
        JsonObject capReq = new JsonObject();
        capReq.addProperty("runId", "run_t4");
        capReq.addProperty("planningSessionId", "scale_plan");
        capReq.addProperty("patchSelectionRef", capitalSelection);
        capReq.addProperty("theoreticalScale", "town");
        capReq.addProperty("name", "议事小都");
        capReq.addProperty("serviceHierarchy", "national_center");

        JsonObject capResult = service.selectCapital(capReq).getAsJsonObject("selectedCapital");
        assertEquals("capital", capResult.get("role").getAsString());
        assertEquals("town", capResult.get("theoreticalScale").getAsString(),
                "Capital scale must be independently selectable as town!");
        assertEquals("national_center", capResult.get("serviceHierarchy").getAsString());
        assertEquals(2, capResult.get("planningRadiusCells").getAsInt(),
                "Town capital must have planningRadiusCells=2 instead of capital's default 4!");
    }

    @Test
    void wholeCountryCityProposalCanBeReviewedAndRevised() throws Exception {
        Path root = tempDir.resolve("revision_flow");
        Path run = root.resolve("run_t4");
        Files.createDirectories(run);
        writeArtifacts(run);

        var explorer = new PatchExplorerService(root);
        String selection1 = select(explorer, "rev_sess_1", "plain", "PLAIN-01");
        String selection2 = select(explorer, "rev_sess_2", "upland", "UPLAND-01");

        var service = new RealmT4PatchPlanningService(root, (id, value) -> null);
        JsonObject createReq = new JsonObject();
        createReq.addProperty("runId", "run_t4");
        createReq.addProperty("realmId", "realm_a");
        createReq.addProperty("planningSessionId", "revision_plan");
        service.create(createReq);

        // 1. Select capital
        JsonObject capReq = new JsonObject();
        capReq.addProperty("runId", "run_t4");
        capReq.addProperty("planningSessionId", "revision_plan");
        capReq.addProperty("patchSelectionRef", selection1);
        service.selectCapital(capReq);

        // 2. Add non-capital town
        JsonObject addReq = new JsonObject();
        addReq.addProperty("runId", "run_t4");
        addReq.addProperty("planningSessionId", "revision_plan");
        addReq.addProperty("patchSelectionRef", selection2);
        addReq.addProperty("citySeedId", "city_proposal_bad");
        addReq.addProperty("role", "mining_town");
        service.add(addReq);

        JsonObject session = JsonParser.parseString(Files.readString(
                run.resolve("realm_t4_patch_planning_revision_plan/planning_session.json"))).getAsJsonObject();
        assertEquals(2, session.getAsJsonArray("citySeeds").size());
        assertTrue(session.getAsJsonArray("usedPatchSelectionRefs").contains(new com.google.gson.JsonPrimitive(selection2)));

        // 3. AI reviews proposal, rejects city_proposal_bad, calls removeCity
        JsonObject removeReq = new JsonObject();
        removeReq.addProperty("runId", "run_t4");
        removeReq.addProperty("planningSessionId", "revision_plan");
        removeReq.addProperty("citySeedId", "city_proposal_bad");
        JsonObject removeResult = service.removeCity(removeReq);
        assertEquals("remove_city_seed", removeResult.get("operation").getAsString());

        session = JsonParser.parseString(Files.readString(
                run.resolve("realm_t4_patch_planning_revision_plan/planning_session.json"))).getAsJsonObject();
        assertEquals(1, session.getAsJsonArray("citySeeds").size(), "Only capital remains");
        assertFalse(session.getAsJsonArray("usedPatchSelectionRefs").contains(new com.google.gson.JsonPrimitive(selection2)),
                "Removed city's patchSelectionRef must be released back to the available pool!");

        // 4. Re-add a different city with selection2
        JsonObject reAddReq = new JsonObject();
        reAddReq.addProperty("runId", "run_t4");
        reAddReq.addProperty("planningSessionId", "revision_plan");
        reAddReq.addProperty("patchSelectionRef", selection2);
        reAddReq.addProperty("citySeedId", "city_proposal_good");
        reAddReq.addProperty("role", "mountain_keep");
        service.add(reAddReq);

        session = JsonParser.parseString(Files.readString(
                run.resolve("realm_t4_patch_planning_revision_plan/planning_session.json"))).getAsJsonObject();
        assertEquals(2, session.getAsJsonArray("citySeeds").size());

        // 5. Finalize successfully after revision
        JsonObject finalizeReq = new JsonObject();
        finalizeReq.addProperty("runId", "run_t4");
        finalizeReq.addProperty("planningSessionId", "revision_plan");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.finalizePlanning(finalizeReq))
                .getMessage().startsWith("T4_CITY_PROPOSAL_REVIEW_REQUIRED"));
        JsonObject preview = service.preview(finalizeReq).getAsJsonObject("cityDistributionPreview");
        assertEquals(2, preview.getAsJsonArray("cityLegend").size());
        Path image = root.resolve(preview.get("imagePath").getAsString());
        assertNotNull(javax.imageio.ImageIO.read(image.toFile()), "Whole-realm draft must be a real rendered image");
        JsonObject presented = com.rinsing.geomantia.systems.provider.application.PlanningToolPresentation
                .present(service.preview(finalizeReq), root);
        assertEquals(1, presented.getAsJsonArray("imageEvidence").size(), "Agent receives actual image contents");
        String oldHash = preview.get("proposalHash").getAsString();
        JsonObject revise = finalizeReq.deepCopy(); revise.addProperty("proposalHash",oldHash);
        revise.addProperty("decision","revise"); revise.addProperty("assessment","重新复核全国分工，需调整据点。");
        service.review(revise);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.finalizePlanning(finalizeReq))
                .getMessage().startsWith("T4_CITY_PROPOSAL_REVIEW_REQUIRED"));
        reviewProposal(service, "run_t4", "revision_plan");
        JsonObject removeRevised = finalizeReq.deepCopy(); removeRevised.addProperty("citySeedId", "city_proposal_good");
        service.removeCity(removeRevised);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.finalizePlanning(finalizeReq))
                .getMessage().startsWith("T4_CITY_PROPOSAL_REVIEW_REQUIRED"));
        JsonObject staleReview = finalizeReq.deepCopy(); staleReview.addProperty("proposalHash", oldHash);
        staleReview.addProperty("decision", "accept"); staleReview.addProperty("assessment", "old map");
        assertThrows(IllegalArgumentException.class, () -> service.review(staleReview));
        service.add(reAddReq);
        reviewProposal(service, "run_t4", "revision_plan");
        JsonObject finalized = service.finalizePlanning(finalizeReq);
        assertEquals("finalize", finalized.get("operation").getAsString());
        assertEquals(2, finalized.getAsJsonObject("citySeedRegistry").getAsJsonArray("citySeeds").size());
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

    static void reviewProposal(RealmT4PatchPlanningService service, String runId, String sessionId) throws Exception {
        JsonObject request = new JsonObject(); request.addProperty("runId",runId); request.addProperty("planningSessionId",sessionId);
        JsonObject preview=service.preview(request).getAsJsonObject("cityDistributionPreview");
        request.add("proposalHash",preview.get("proposalHash")); request.addProperty("decision","accept");
        request.addProperty("assessment","已查看全国分布图，复核全部城市的规模、定位、设计范围和保护范围。");
        service.review(request);
    }

    private static JsonArray strings(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) array.add(value);
        return array;
    }

    private static JsonObject registrySeed(String id, String role, int blockX, int blockZ, int planningRadius) {
        JsonObject seed = new JsonObject();
        seed.addProperty("citySeedId", id);
        seed.addProperty("realmId", "realm_a");
        seed.addProperty("role", role);
        seed.addProperty("theoreticalScale", "town");
        JsonObject anchorBlock = new JsonObject();
        anchorBlock.addProperty("x", blockX);
        anchorBlock.addProperty("z", blockZ);
        seed.add("anchorBlock", anchorBlock);
        JsonObject anchorGrid = new JsonObject();
        anchorGrid.addProperty("x", blockX / 16);
        anchorGrid.addProperty("z", blockZ / 16);
        seed.add("anchorGrid", anchorGrid);
        seed.addProperty("candidateRangeCells", 4);
        seed.addProperty("planningRadiusCells", planningRadius);
        seed.addProperty("subregionId", id + "_sub");
        seed.addProperty("candidateId", id + "_cand");
        seed.addProperty("graphDistanceToNearestCity", -1.0);
        seed.add("requiredConditions", strings("land", "inside_realm"));
        seed.add("coreFunctions", strings("market"));
        seed.addProperty("trigger", "always");
        JsonObject source = new JsonObject();
        source.addProperty("reason", "test fixture");
        seed.add("source", source);
        JsonObject bounds = new JsonObject();
        bounds.addProperty("minX", blockX - 16);
        bounds.addProperty("minZ", blockZ - 16);
        bounds.addProperty("maxX", blockX + 16);
        bounds.addProperty("maxZ", blockZ + 16);
        seed.add("designBounds", bounds);
        seed.add("protectionBounds", bounds);
        return seed;
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

        JsonObject registry = new JsonObject();
        registry.addProperty("registryId", "registry_run_t4");
        registry.addProperty("surveyId", "survey_run_t4");
        registry.addProperty("territoryMapId", "territory_run_t4");
        registry.add("citySeeds", new JsonArray());
        Files.writeString(run.resolve("city_seed_registry.json"), registry.toString());
    }
}
