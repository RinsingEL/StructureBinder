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

class PatchExplorerServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void exploresThreeScopesWithStablePagesSparseRelationsAndSelectionPreview() throws Exception {
        Path root = tempDir.resolve("realm_debug");
        Path run = root.resolve("run_a");
        Files.createDirectories(run);
        writeRealmArtifacts(run);
        writeCityArtifacts(run);
        PatchExplorerService service = new PatchExplorerService(root);

        JsonObject t2Open = service.open(request("run_a", "realm_t2", "realm_a"));
        assertEquals("dominant_biome_contiguous_region", t2Open.get("candidateBasis").getAsString());
        assertEquals("patch_explorer_session.v0.1", read(root.resolve(t2Open.getAsJsonObject("artifacts")
                .get("explorationSession").getAsString())).get("schemaVersion").getAsString());
        assertTrue(t2Open.getAsJsonArray("typeCatalog").size() >= 2);
        assertTrue(hasPatchType(t2Open.getAsJsonArray("typeCatalog"), "minecraft:plains"));
        assertFalse(hasPatchType(t2Open.getAsJsonArray("typeCatalog"), "plain"));
        assertEquals("kingdom_a", service.open(request("run_a", "realm_t2", "kingdom_a"))
                .get("scopeId").getAsString(), "realm IDs must not depend on a realm_ prefix");

        JsonObject showRequest = new JsonObject();
        showRequest.addProperty("runId", "run_a");
        showRequest.addProperty("sessionId", t2Open.get("sessionId").getAsString());
        showRequest.add("interestTypes", strings("minecraft:plains", "minecraft:beach"));
        JsonObject firstPage = service.showCandidates(showRequest);
        JsonArray typePages = firstPage.getAsJsonArray("typePages");
        assertEquals(2, typePages.size());
        assertEquals(3, typePages.get(0).getAsJsonObject().getAsJsonArray("candidates").size());
        assertTrue(typePages.get(0).getAsJsonObject().get("hasNext").getAsBoolean());
        assertTrue(typePages.get(0).getAsJsonObject().getAsJsonArray("candidates").get(0).getAsJsonObject()
                .getAsJsonObject("terrainComposition").has("plain"));
        assertTrue(firstPage.getAsJsonArray("relations").size() < 21, firstPage.toString());
        String pageToken = typePages.get(0).getAsJsonObject().get("nextPageToken").getAsString();
        JsonObject invalidTokenUse = new JsonObject();
        invalidTokenUse.addProperty("runId", "run_a");
        invalidTokenUse.addProperty("sessionId", t2Open.get("sessionId").getAsString());
        invalidTokenUse.add("interestTypes", strings("minecraft:plains"));
        invalidTokenUse.addProperty("pageToken", pageToken);
        assertThrows(IllegalArgumentException.class, () -> service.showCandidates(invalidTokenUse));
        for (var relation : firstPage.getAsJsonArray("relations")) {
            JsonObject fact = relation.getAsJsonObject();
            assertTrue(fact.has("boundaryDistanceBlocks"));
            assertFalse(fact.has("narrative"));
            assertFalse(fact.has("occupied"));
            assertFalse(fact.has("capital"));
        }

        JsonObject select = new JsonObject();
        select.addProperty("runId", "run_a");
        select.addProperty("sessionId", t2Open.get("sessionId").getAsString());
        select.addProperty("candidateId", "MINECRAFT_PLAINS-01");
        JsonObject selected = service.selectCandidate(select);
        assertTrue(selected.get("patchSelectionRef").getAsString().startsWith("psel_"));
        assertTrue(selected.getAsJsonObject("selection").getAsJsonObject("terrainComposition").has("plain"));
        Path confirmation = root.resolve(selected.getAsJsonObject("artifacts").get("confirmationPreview").getAsString());
        assertTrue(Files.size(confirmation) > 0);
        JsonObject resolved = service.resolveSelection("run_a", selected.get("patchSelectionRef").getAsString());
        assertEquals("realm_t2", resolved.get("scopeType").getAsString());
        assertEquals("realm_a", service.resolveT2Selection("run_a", "realm_a",
                selected.get("patchSelectionRef").getAsString()).get("scopeId").getAsString());

        JsonObject continentOpen = service.open(request("run_a", "realm_t2", "continent_0"));
        show(service, continentOpen, "minecraft:plains");
        JsonObject continentSelect = new JsonObject();
        continentSelect.addProperty("runId", "run_a");
        continentSelect.addProperty("sessionId", continentOpen.get("sessionId").getAsString());
        continentSelect.addProperty("candidateId", "MINECRAFT_PLAINS-01");
        String continentSelectionRef = service.selectCandidate(continentSelect)
                .get("patchSelectionRef").getAsString();
        assertEquals("continent_0", service.resolveT2Selection("run_a", "realm_a", continentSelectionRef)
                .get("scopeId").getAsString());

        JsonObject t4Open = service.open(request("run_a", "realm_t4", "realm_a"));
        JsonObject t4Show = show(service, t4Open, "minecraft:plains");
        assertFalse(t4Show.getAsJsonArray("typePages").get(0).getAsJsonObject()
                .getAsJsonArray("candidates").isEmpty());

        JsonObject d4Open = service.open(request("run_a", "city_d4", "city_a"));
        JsonObject d4Show = show(service, d4Open, "plain");
        JsonObject d4Candidate = d4Show.getAsJsonArray("typePages").get(0).getAsJsonObject()
                .getAsJsonArray("candidates").get(0).getAsJsonObject();
        assertEquals("d3_landform_patch", d4Candidate.get("candidateBasis").getAsString());
        assertTrue(d4Candidate.get("areaBlocks").getAsLong() < d4Candidate.get("originalAreaBlocks").getAsLong());
        assertTrue(d4Candidate.get("areaBlocks").getAsLong() > 0,
                "root grid.blockBounds must not be treated as hard occupied");
        assertEquals(2, d4Show.getAsJsonArray("typePages").get(0).getAsJsonObject()
                .getAsJsonArray("candidates").size(),
                "hard occupied cells must split one D3 source patch into independent continuous candidates");
        assertEquals(4L * 16 * 16, d4Candidate.get("areaBlocks").getAsLong());
        assertEquals(4L * 16 * 16, d4Candidate.get("largestContinuousAreaBlocks").getAsLong());
        JsonObject smallerComponent = d4Show.getAsJsonArray("typePages").get(0).getAsJsonObject()
                .getAsJsonArray("candidates").get(1).getAsJsonObject();
        assertEquals(1L * 16 * 16, smallerComponent.get("areaBlocks").getAsLong());
        assertEquals(d4Candidate.getAsJsonArray("sourcePatchRefs"), smallerComponent.getAsJsonArray("sourcePatchRefs"));

        JsonObject d4SelectRequest = new JsonObject();
        d4SelectRequest.addProperty("runId", "run_a");
        d4SelectRequest.addProperty("sessionId", d4Open.get("sessionId").getAsString());
        d4SelectRequest.addProperty("candidateId", d4Candidate.get("candidateId").getAsString());
        d4SelectRequest.addProperty("selectionReason", "smaller verified component");
        JsonObject d4Selection = service.selectCandidate(d4SelectRequest);
        String d4SelectionRef = d4Selection.get("patchSelectionRef").getAsString();
        JsonObject selectedComponent = d4Selection.getAsJsonObject("selection")
                .getAsJsonObject("selectedComponent");
        assertEquals("patch_selection_legal_region.v0.1",
                selectedComponent.get("schemaVersion").getAsString());
        assertEquals(4, selectedComponent.getAsJsonArray("memberCells").size());
        assertEquals("smaller verified component",
                d4Selection.getAsJsonObject("selection").get("selectionReason").getAsString());
        JsonObject slot = new JsonObject();
        slot.addProperty("patchSelectionRef", d4SelectionRef);
        JsonObject designSlotPlan = new JsonObject();
        JsonArray slots = new JsonArray();
        slots.add(slot);
        designSlotPlan.add("slots", slots);
        JsonObject resolvedPlan = service.resolveD4DesignSlotPlan("run_a", "city_a", designSlotPlan);
        assertEquals("d3_plain_1", resolvedPlan.getAsJsonArray("slots").get(0).getAsJsonObject()
                .getAsJsonArray("candidatePatchRefs").get(0).getAsString());
        assertEquals(selectedComponent, resolvedPlan.getAsJsonArray("slots").get(0).getAsJsonObject()
                .getAsJsonObject("candidateLegalRegion"));

        JsonObject arrayPlan = new JsonObject();
        arrayPlan.addProperty("patchSelectionRef", d4SelectionRef);
        JsonObject resolvedArray = service.resolveD4ArrayPlan("run_a", "city_a", arrayPlan);
        assertEquals(selectedComponent, resolvedArray.getAsJsonObject("candidateLegalRegion"));

        JsonObject layoutItem = new JsonObject();
        layoutItem.addProperty("patchSelectionRef", d4SelectionRef);
        JsonObject layoutPlan = new JsonObject();
        JsonArray layoutPlans = new JsonArray();
        layoutPlans.add(layoutItem);
        layoutPlan.add("layoutPlans", layoutPlans);
        JsonObject resolvedLayout = service.resolveD4ArrayLayoutPlan("run_a", "city_a", layoutPlan);
        assertEquals(selectedComponent, resolvedLayout.getAsJsonArray("layoutPlans").get(0).getAsJsonObject()
                .getAsJsonObject("candidateLegalRegion"));

        JsonObject expansion = new JsonObject();
        expansion.addProperty("patchSelectionRef", d4SelectionRef);
        expansion.add("nextArrayLayoutPlanItem", new JsonObject());
        JsonObject resolvedExpansion = service.resolveD4ExpansionRequest("run_a", "city_a", expansion);
        assertEquals("d3_plain_1", resolvedExpansion.get("selectedGlobalPatchRef").getAsString());
        assertTrue(resolvedExpansion.get("newFunctionalArea").getAsBoolean());
        assertEquals(selectedComponent, resolvedExpansion.getAsJsonObject("candidateLegalRegion"));
        assertEquals(selectedComponent, resolvedExpansion.getAsJsonObject("nextArrayLayoutPlanItem")
                .getAsJsonObject("candidateLegalRegion"));
        assertThrows(IllegalArgumentException.class,
                () -> service.resolveD4ArrayPlan("run_a", "other_city", expansion));

        JsonObject compatibilityPlan = JsonParser.parseString("""
                {"candidatePatchRefs":["manual_patch"],"outwardDirection":"east"}
                """).getAsJsonObject();
        assertEquals(compatibilityPlan,
                service.resolveD4ArrayPlan("run_a", "city_a", compatibilityPlan));
        assertEquals(compatibilityPlan,
                service.resolveD4ExpansionRequest("run_a", "city_a", compatibilityPlan));

        Path selectionPath = root.resolve(d4Selection.getAsJsonObject("artifacts")
                .get("selection").getAsString());
        JsonObject persistedSelection = read(selectionPath);
        JsonObject tamperedSelection = persistedSelection.deepCopy();
        tamperedSelection.getAsJsonObject("selectedComponent").getAsJsonArray("memberCells")
                .get(0).getAsJsonObject().addProperty("blockMaxX", 9999);
        Files.writeString(selectionPath, tamperedSelection.toString());
        IllegalArgumentException componentMismatch = assertThrows(IllegalArgumentException.class,
                () -> service.resolveSelection("run_a", d4SelectionRef));
        assertTrue(componentMismatch.getMessage().contains("COMPONENT_MISMATCH"), componentMismatch.getMessage());
        Files.writeString(selectionPath, persistedSelection.toString());

        Path candidateSessionPath = run.resolve("city_d4_candidate_session_city_a")
                .resolve("d4_candidate_session.json");
        JsonObject candidateSession = read(candidateSessionPath);
        candidateSession.addProperty("updatedAt", "read-only-candidate-generation");
        candidateSession.add("slotCandidateSet", JsonParser.parseString("""
                {"candidates":[{"estimatedCollisionEnvelope":
                {"minX":80,"minZ":80,"maxX":95,"maxZ":95}}]}
                """).getAsJsonObject());
        Files.writeString(candidateSessionPath, candidateSession.toString());
        assertEquals(selectedComponent,
                service.resolveSelection("run_a", d4SelectionRef).getAsJsonObject("selectedComponent"),
                "read-only candidate history must not stale a patch selection or become occupied");

        Path designLoop = run.resolve("city_d4_design_loop_city_a");
        Files.createDirectories(designLoop);
        Files.writeString(designLoop.resolve("d4_design_loop_occupied_field.json"), "{\"occupiedEnvelopes\":[]}");
        assertEquals(selectedComponent,
                service.resolveSelection("run_a", d4SelectionRef).getAsJsonObject("selectedComponent"),
                "an empty occupied artifact must not stale a patch selection");
        Files.writeString(designLoop.resolve("d4_design_loop_occupied_field.json"), """
                {"occupiedEnvelopes":[{"minX":80,"minZ":0,"maxX":95,"maxZ":15}]}
                """);
        IllegalArgumentException staleOccupied = assertThrows(IllegalArgumentException.class,
                () -> service.resolveSelection("run_a", d4SelectionRef));
        assertTrue(staleOccupied.getMessage().contains("STALE_SOURCE"), staleOccupied.getMessage());
    }

    @Test
    void rejectsSelectionAfterSourceIdentityChanges() throws Exception {
        Path root = tempDir.resolve("realm_debug");
        Path run = root.resolve("run_stale");
        Files.createDirectories(run);
        writeRealmArtifacts(run);
        PatchExplorerService service = new PatchExplorerService(root);
        JsonObject open = service.open(request("run_stale", "realm_t2", "continent_0"));
        show(service, open, "minecraft:plains");

        JsonObject context = read(run.resolve("world_survey_context.json"));
        context.addProperty("changedAfterOpen", true);
        Files.writeString(run.resolve("world_survey_context.json"), context.toString());
        JsonObject select = new JsonObject();
        select.addProperty("runId", "run_stale");
        select.addProperty("sessionId", open.get("sessionId").getAsString());
        select.addProperty("candidateId", "MINECRAFT_PLAINS-01");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.selectCandidate(select));
        assertTrue(error.getMessage().contains("STALE_SOURCE"), error.getMessage());
    }

    @Test
    void rejectsForgedD4CandidateLegalRegionAtEveryExternalBoundary() throws Exception {
        PatchExplorerService service = new PatchExplorerService(tempDir.resolve("realm_debug"));
        JsonObject forgedRegion = new JsonObject();

        JsonObject designRoot = new JsonObject();
        designRoot.add("candidateLegalRegion", forgedRegion.deepCopy());
        designRoot.add("slots", new JsonArray());
        assertReservedFieldRejected(() -> service.resolveD4DesignSlotPlan("run_a", "city_a", designRoot));

        JsonObject forgedSlot = new JsonObject();
        forgedSlot.add("candidateLegalRegion", forgedRegion.deepCopy());
        JsonObject designWithSlot = new JsonObject();
        JsonArray slots = new JsonArray();
        slots.add(forgedSlot);
        designWithSlot.add("slots", slots);
        assertReservedFieldRejected(() -> service.resolveD4DesignSlotPlan("run_a", "city_a", designWithSlot));

        JsonObject arrayPlan = new JsonObject();
        arrayPlan.add("candidateLegalRegion", forgedRegion.deepCopy());
        assertReservedFieldRejected(() -> service.resolveD4ArrayPlan("run_a", "city_a", arrayPlan));

        JsonObject layoutRoot = new JsonObject();
        layoutRoot.add("candidateLegalRegion", forgedRegion.deepCopy());
        layoutRoot.add("layoutPlans", new JsonArray());
        assertReservedFieldRejected(() -> service.resolveD4ArrayLayoutPlan("run_a", "city_a", layoutRoot));

        JsonObject forgedLayoutItem = new JsonObject();
        forgedLayoutItem.add("candidateLegalRegion", forgedRegion.deepCopy());
        JsonObject layoutPlan = new JsonObject();
        JsonArray layoutItems = new JsonArray();
        layoutItems.add(forgedLayoutItem);
        layoutPlan.add("layoutPlans", layoutItems);
        assertReservedFieldRejected(() -> service.resolveD4ArrayLayoutPlan("run_a", "city_a", layoutPlan));

        JsonObject expansionRoot = new JsonObject();
        expansionRoot.add("candidateLegalRegion", forgedRegion.deepCopy());
        assertReservedFieldRejected(() -> service.resolveD4ExpansionRequest("run_a", "city_a", expansionRoot));

        for (String itemField : new String[]{"nextArrayLayoutPlanItem", "arrayLayoutPlanItem"}) {
            JsonObject nestedItem = new JsonObject();
            nestedItem.add("candidateLegalRegion", forgedRegion.deepCopy());
            JsonObject expansion = new JsonObject();
            expansion.add(itemField, nestedItem);
            assertReservedFieldRejected(() -> service.resolveD4ExpansionRequest("run_a", "city_a", expansion));
        }
    }

    private static void assertReservedFieldRejected(ThrowingCall call) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, call::run);
        assertTrue(error.getMessage().startsWith("PATCH_SELECTION_D4_RESERVED_FIELD_FORBIDDEN:"),
                error.getMessage());
    }

    private static JsonObject show(PatchExplorerService service, JsonObject open, String... types) throws Exception {
        JsonObject request = new JsonObject();
        request.addProperty("runId", open.get("runId").getAsString());
        request.addProperty("sessionId", open.get("sessionId").getAsString());
        request.add("interestTypes", strings(types));
        return service.showCandidates(request);
    }

    private static JsonObject request(String runId, String scopeType, String scopeId) {
        JsonObject request = new JsonObject();
        request.addProperty("runId", runId);
        request.addProperty("scopeType", scopeType);
        request.addProperty("scopeId", scopeId);
        return request;
    }

    private static void writeRealmArtifacts(Path run) throws Exception {
        JsonObject context = new JsonObject();
        context.addProperty("schemaVersion", "realm_planning.v1.2");
        context.addProperty("surveyId", "survey_fixture");
        context.addProperty("sealed", true);
        context.addProperty("cellStepBlocks", 16);
        Files.writeString(run.resolve("world_survey_context.json"), context.toString());

        JsonObject map = new JsonObject();
        map.addProperty("schemaVersion", "realm_planning.v1.2");
        JsonArray cells = new JsonArray();
        addPatch(cells, "p1", "plain", 0, 0, 5);
        addPatch(cells, "p2", "plain", 0, 2, 4);
        addPatch(cells, "p3", "plain", 0, 4, 3);
        addPatch(cells, "p4", "plain", 0, 6, 2);
        addPatch(cells, "s1", "shore", 5, 0, 3);
        map.add("cells", cells);
        map.add("patches", new JsonArray());
        Files.writeString(run.resolve("world_patch_map.json"), map.toString());

        JsonObject candidatePackage = new JsonObject();
        candidatePackage.addProperty("realmId", "realm_a");
        candidatePackage.add("allowedPatches", strings("p1", "p2", "p3", "p4", "s1"));
        JsonArray candidatePackages = new JsonArray();
        candidatePackages.add(candidatePackage);
        JsonObject arbitraryRealmPackage = new JsonObject();
        arbitraryRealmPackage.addProperty("realmId", "kingdom_a");
        arbitraryRealmPackage.add("allowedPatches", strings("p1", "p2", "p3", "p4", "s1"));
        candidatePackages.add(arbitraryRealmPackage);
        Files.writeString(run.resolve("candidate_map_packages.json"), candidatePackages.toString());

        JsonObject realmA = new JsonObject();
        realmA.addProperty("realmId", "realm_a");
        realmA.addProperty("targetContinentId", "continent_0");
        JsonObject kingdomA = new JsonObject();
        kingdomA.addProperty("realmId", "kingdom_a");
        kingdomA.addProperty("targetContinentId", "continent_0");
        JsonArray realmProfiles = new JsonArray();
        realmProfiles.add(realmA);
        realmProfiles.add(kingdomA);
        Files.writeString(run.resolve("realm_profiles.json"), realmProfiles.toString());

        JsonObject territory = new JsonObject();
        territory.addProperty("territoryMapId", "territory_fixture");
        JsonArray territoryCells = new JsonArray();
        for (int x = 0; x <= 5; x++) {
            JsonObject cell = new JsonObject();
            cell.addProperty("gridX", x);
            cell.addProperty("gridZ", 0);
            cell.addProperty("realmId", "realm_a");
            cell.addProperty("status", "owned");
            territoryCells.add(cell);
        }
        territory.add("territoryCells", territoryCells);
        Files.writeString(run.resolve("realm_territory_map.json"), territory.toString());
    }

    private static void addPatch(JsonArray cells, String patch, String type, int startX, int z, int count) {
        for (int i = 0; i < count; i++) {
            JsonObject cell = new JsonObject();
            cell.addProperty("gridX", startX + i);
            cell.addProperty("gridZ", z);
            cell.addProperty("blockX", (startX + i) * 16);
            cell.addProperty("blockZ", z * 16);
            cell.addProperty("patchId", patch);
            cell.addProperty("continentId", "continent_0");
            cell.addProperty("landform", type);
            cell.addProperty("baseLandform", "legacy_base_" + type);
            cell.addProperty("landformConfidence", 0.9);
            JsonObject biomeHist = new JsonObject();
            biomeHist.addProperty("shore".equals(type) ? "minecraft:beach" : "minecraft:plains", 15);
            biomeHist.addProperty("minecraft:forest", 1);
            cell.add("biomeHist", biomeHist);
            cells.add(cell);
        }
    }

    private static void writeCityArtifacts(Path run) throws Exception {
        Path d3 = run.resolve("city_d3_city_a");
        Files.createDirectories(d3);
        JsonObject review = new JsonObject();
        review.addProperty("schemaVersion", "city_landform_review.v0.1");
        review.addProperty("cityId", "city_a");
        JsonObject grid = new JsonObject();
        grid.addProperty("cellStepBlocks", 16);
        JsonObject bounds = new JsonObject();
        bounds.addProperty("minX", 0);
        bounds.addProperty("minZ", 0);
        bounds.addProperty("maxX", 127);
        bounds.addProperty("maxZ", 127);
        grid.add("blockBounds", bounds);
        review.add("grid", grid);
        JsonArray patches = new JsonArray();
        JsonObject patch = new JsonObject();
        patch.addProperty("landformPatchId", "d3_plain_1");
        patch.addProperty("landformType", "plain");
        patch.add("landformTags", new JsonArray());
        JsonArray members = new JsonArray();
        for (int x = 0; x < 6; x++) {
            JsonObject cell = new JsonObject();
            cell.addProperty("blockMinX", x * 16);
            cell.addProperty("blockMinZ", 0);
            members.add(cell);
        }
        patch.add("memberCells", members);
        patches.add(patch);
        review.add("landformPatches", patches);
        Files.writeString(d3.resolve("city_landform_review_package.json"), review.toString());

        Path sessionDir = run.resolve("city_d4_candidate_session_city_a");
        Files.createDirectories(sessionDir);
        JsonObject session = new JsonObject();
        JsonArray occupied = new JsonArray();
        JsonObject envelope = new JsonObject();
        envelope.addProperty("minX", 16);
        envelope.addProperty("minZ", 0);
        envelope.addProperty("maxX", 31);
        envelope.addProperty("maxZ", 15);
        occupied.add(envelope);
        session.add("occupiedEnvelopes", occupied);
        Files.writeString(sessionDir.resolve("d4_candidate_session.json"), session.toString());

        Path anchorDir = run.resolve("city_d4_city_a");
        Files.createDirectories(anchorDir);
        JsonObject anchorMap = new JsonObject();
        JsonObject anchorGrid = new JsonObject();
        JsonObject wholeGrid = new JsonObject();
        wholeGrid.addProperty("minX", 0);
        wholeGrid.addProperty("minZ", 0);
        wholeGrid.addProperty("maxX", 127);
        wholeGrid.addProperty("maxZ", 127);
        anchorGrid.add("blockBounds", wholeGrid);
        anchorMap.add("grid", anchorGrid);
        anchorMap.add("anchors", new JsonArray());
        Files.writeString(anchorDir.resolve("structure_anchor_map.json"), anchorMap.toString());
    }

    private static JsonArray strings(String... values) {
        JsonArray result = new JsonArray();
        for (String value : values) {
            result.add(value);
        }
        return result;
    }

    private static boolean hasPatchType(JsonArray catalog, String type) {
        for (var element : catalog) {
            if (type.equals(element.getAsJsonObject().get("patchType").getAsString())) return true;
        }
        return false;
    }

    private static JsonObject read(Path path) throws Exception {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }
}
