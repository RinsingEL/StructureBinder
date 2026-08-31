package com.rinsing.geomantia.systems.realm_planning;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.CityTestRunLayout;
import com.rinsing.geomantia.systems.realm_planning.application.terrain.PatchCandidateTerrainPreviewService;
import com.rinsing.geomantia.systems.realm_planning.application.terrain.RealmT4CoarseTerrainPreviewService;
import com.rinsing.geomantia.systems.realm_planning.application.terrain.TerrainPreviewProvider;
import com.rinsing.geomantia.systems.realm_planning.application.terrain.TerrainPreviewProviderAvailability;
import com.rinsing.geomantia.systems.realm_planning.application.terrain.TerrainPreviewProviderDescriptor;
import com.rinsing.geomantia.systems.realm_planning.application.terrain.TerrainPreviewProviderSelection;
import com.rinsing.geomantia.systems.realm_planning.application.terrain.TerrainPreviewSample;
import com.rinsing.geomantia.systems.realm_planning.application.terrain.TerrainPreviewSourceKind;
import com.rinsing.geomantia.systems.realm_planning.application.terrain.TerrainScalePatchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
        assertEquals("sealed_w_landform_patch", t2Open.get("candidateBasis").getAsString());
        assertEquals("patch_explorer_session", read(root.resolve(t2Open.getAsJsonObject("artifacts")
                .get("explorationSession").getAsString())).get("schema").getAsString());
        assertTrue(t2Open.getAsJsonArray("typeCatalog").size() >= 2);
        assertTrue(hasPatchType(t2Open.getAsJsonArray("typeCatalog"), "plain"));
        assertFalse(hasPatchType(t2Open.getAsJsonArray("typeCatalog"), "minecraft:plains"));
        assertEquals("#4CAF50", t2Open.getAsJsonObject("patchTypePalette")
                .getAsJsonObject("colors").get("plain").getAsString());
        assertEquals("#4CAF50", t2Open.getAsJsonArray("typeCatalog").get(0).getAsJsonObject()
                .get("color").getAsString());
        assertOverviewSet(root, t2Open.getAsJsonObject("artifacts"));
        assertEquals("kingdom_a", service.open(request("run_a", "realm_t2", "kingdom_a"))
                .get("scopeId").getAsString(), "realm IDs must not depend on a realm_ prefix");

        JsonObject showRequest = new JsonObject();
        showRequest.addProperty("runId", "run_a");
        showRequest.addProperty("sessionId", t2Open.get("sessionId").getAsString());
        showRequest.add("interestTypes", strings("plain", "shore"));
        JsonObject firstPage = service.showCandidates(showRequest);
        JsonArray typePages = firstPage.getAsJsonArray("typePages");
        assertEquals(2, typePages.size());
        assertEquals(3, typePages.get(0).getAsJsonObject().getAsJsonArray("candidates").size());
        assertTrue(typePages.get(0).getAsJsonObject().get("hasNext").getAsBoolean());
        assertEquals(1, typePages.get(0).getAsJsonObject().getAsJsonArray("candidates")
                .get(0).getAsJsonObject().getAsJsonArray("sourcePatchRefs").size(),
                "one source patch must remain one candidate instead of merging by biome");
        assertFalse(firstPage.getAsJsonObject("artifacts").has("candidatePreview"));
        assertTopPatchesOverview(root, firstPage);
        assertTrue(typePages.get(0).getAsJsonObject().getAsJsonArray("candidates").get(0).getAsJsonObject()
                .getAsJsonObject("terrainComposition").has("plain"));
        assertTrue(firstPage.getAsJsonArray("relations").size() < 21, firstPage.toString());
        String pageToken = typePages.get(0).getAsJsonObject().get("nextPageToken").getAsString();
        JsonObject invalidTokenUse = new JsonObject();
        invalidTokenUse.addProperty("runId", "run_a");
        invalidTokenUse.addProperty("sessionId", t2Open.get("sessionId").getAsString());
        invalidTokenUse.add("interestTypes", strings("plain"));
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
        select.addProperty("candidateId", "PLAIN-01");
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
        show(service, continentOpen, "plain");
        JsonObject continentSelect = new JsonObject();
        continentSelect.addProperty("runId", "run_a");
        continentSelect.addProperty("sessionId", continentOpen.get("sessionId").getAsString());
        continentSelect.addProperty("candidateId", "PLAIN-01");
        String continentSelectionRef = service.selectCandidate(continentSelect)
                .get("patchSelectionRef").getAsString();
        assertEquals("continent_0", service.resolveT2Selection("run_a", "realm_a", continentSelectionRef)
                .get("scopeId").getAsString());

        JsonObject t4Open = service.open(request("run_a", "realm_t4", "realm_a"));
        JsonObject t4Show = show(service, t4Open, "plain");
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
        assertEquals("patch_selection_legal_region",
                selectedComponent.get("schema").getAsString());
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

        Path candidateSessionPath = CityTestRunLayout.open(run, "city_a")
                .stepDirectory(CityTestRunLayout.D4_CANDIDATE_SESSION)
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

        Path designLoop = CityTestRunLayout.open(run, "city_a")
                .stepDirectory(CityTestRunLayout.D4_DESIGN_LOOP);
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
    void realmT4UsesFrozenCoarseTerrainEvidenceForFactsPreviewAndAnchorWithoutChangingOtherScopes()
            throws Exception {
        Path root = tempDir.resolve("terrain_realm_debug");
        Path run = root.resolve("run_terrain");
        Files.createDirectories(run);
        writeRealmArtifacts(run);
        writeCityArtifacts(run);
        Path evidencePath = writeCoarseTerrainEvidence(run);
        PatchExplorerService service = new PatchExplorerService(root);

        JsonObject t4Open = service.open(request("run_terrain", "realm_t4", "realm_a"));
        assertEquals("landform_patch_candidates",
                read(root.resolve(t4Open.getAsJsonObject("artifacts").get("explorationSession").getAsString()))
                        .get("candidateModel").getAsString());
        assertEquals("run_terrain/realm_t4_terrain_preview/realm_a_coarse_height_water_preview.png",
                t4Open.getAsJsonObject("artifacts").get("heightWaterPreview").getAsString());
        JsonObject catalogEvidence = t4Open.getAsJsonArray("typeCatalog").get(0).getAsJsonObject()
                .getAsJsonObject("coarseTerrainEvidence");
        assertEquals("rtf_native", catalogEvidence.getAsJsonObject("provider").get("providerId").getAsString());
        assertEquals("rtf:flat", catalogEvidence.getAsJsonObject("terrainIdHistogram").entrySet().stream()
                .max(java.util.Comparator.comparingInt(entry -> entry.getValue().getAsInt()))
                .orElseThrow().getKey());
        assertTrue(catalogEvidence.get("advisoryOnly").getAsBoolean());
        assertEquals("city_d3_site_review", catalogEvidence.get("requiredNextGate").getAsString());

        JsonObject shown = show(service, t4Open, "plain");
        JsonObject candidate = shown.getAsJsonArray("typePages").get(0).getAsJsonObject()
                .getAsJsonArray("candidates").get(0).getAsJsonObject();
        assertEquals(5, candidate.getAsJsonObject("coarseTerrainEvidence").get("sampleCount").getAsInt());
        assertEquals(1.0, candidate.getAsJsonObject("coarseTerrainEvidence").get("coverage").getAsDouble());
        assertEquals(2, candidate.getAsJsonObject("suggestedAnchor").get("gridX").getAsInt(),
                "realm_t4 should prefer the dry low-relief cell over the old first boundary cell");
        JsonObject select = new JsonObject();
        select.addProperty("runId", "run_terrain");
        select.addProperty("sessionId", t4Open.get("sessionId").getAsString());
        select.addProperty("candidateId", "PLAIN-01");
        JsonObject selected = service.selectCandidate(select);
        assertEquals(2, selected.getAsJsonObject("selection").getAsJsonObject("suggestedAnchor")
                .get("gridX").getAsInt());
        assertEquals("rtf:climate_temperate", selected.getAsJsonObject("selection")
                .getAsJsonObject("coarseTerrainEvidence").getAsJsonObject("sourceBiomeIdHistogram")
                .entrySet().iterator().next().getKey());
        assertEquals(t4Open.getAsJsonObject("artifacts").get("heightWaterPreview").getAsString(),
                selected.getAsJsonObject("artifacts").get("heightWaterPreview").getAsString());

        JsonObject session = read(root.resolve(t4Open.getAsJsonObject("artifacts")
                .get("explorationSession").getAsString()));
        JsonObject snapshot = read(root.resolve(session.get("scopeSnapshot").getAsString()));
        assertTrue(snapshot.has("coarseTerrainSource"));
        assertTrue(snapshot.getAsJsonArray("candidates").get(0).getAsJsonObject()
                .getAsJsonArray("cells").get(0).getAsJsonObject().has("coarseTerrain"));

        JsonObject t2Open = service.open(request("run_terrain", "realm_t2", "realm_a"));
        JsonObject t2Candidate = show(service, t2Open, "plain").getAsJsonArray("typePages")
                .get(0).getAsJsonObject().getAsJsonArray("candidates").get(0).getAsJsonObject();
        assertFalse(t2Candidate.has("coarseTerrainEvidence"));
        assertEquals(0, t2Candidate.getAsJsonObject("suggestedAnchor").get("gridX").getAsInt());
        JsonObject d4Open = service.open(request("run_terrain", "city_d4", "city_a"));
        JsonObject d4Candidate = show(service, d4Open, "plain").getAsJsonArray("typePages")
                .get(0).getAsJsonObject().getAsJsonArray("candidates").get(0).getAsJsonObject();
        assertFalse(d4Candidate.has("coarseTerrainEvidence"));

        JsonObject modified = read(evidencePath);
        modified.addProperty("changedAfterOpen", true);
        Files.writeString(evidencePath, modified.toString());
        IllegalArgumentException stale = assertThrows(IllegalArgumentException.class,
                () -> service.resolveSelection("run_terrain", selected.get("patchSelectionRef").getAsString()));
        assertTrue(stale.getMessage().contains("STALE_SOURCE"), stale.getMessage());
    }

    @Test
    void realmT4RefinesDisplayedCandidatesAndFreezesCityScaleConfirmationIntoSelection() throws Exception {
        Path root = tempDir.resolve("refined_realm_debug");
        Path run = root.resolve("run_refined");
        Files.createDirectories(run);
        writeRealmArtifacts(run);
        writeCityArtifacts(run);
        rewriteRealmCellStep(run, 128);
        writeCoarseTerrainEvidence(run, "run_refined", 128);
        PatchExplorerService service = new PatchExplorerService(root);
        PatchExplorerService.TerrainPreviewRunner runner = terrainPreviewRunner(root);
        JsonObject openRequest = request("run_refined", "realm_t4", "realm_a");
        openRequest.addProperty("preferGeneratorNativeTerrain", false);
        JsonObject opened = service.open(openRequest);
        assertFalse(service.sessionTerrainContext(sessionRequest(opened)).preferGeneratorNativeTerrain());

        JsonObject showRequest = sessionRequest(opened);
        showRequest.add("interestTypes", strings("plain"));
        JsonObject shown = service.showCandidates(showRequest, runner);
        JsonObject candidate = shown.getAsJsonArray("typePages").get(0).getAsJsonObject()
                .getAsJsonArray("candidates").get(0).getAsJsonObject();
        assertFalse(candidate.has("terrainPreview"));
        assertFalse(shown.getAsJsonObject("artifacts").has("candidateTerrainPreviews"));
        assertTopPatchesOverview(root, shown);

        JsonObject selectRequest = sessionRequest(opened);
        selectRequest.addProperty("candidateId", candidate.get("candidateId").getAsString());
        JsonObject selected = service.selectCandidate(selectRequest, runner);
        JsonObject confirmation = selected.getAsJsonObject("selection").getAsJsonObject("terrainPreview");
        assertEquals("city_scale_confirmation", confirmation.get("evaluationLevel").getAsString());
        assertEquals(16, confirmation.getAsJsonObject("grid").get("sampleStepBlocks").getAsInt());
        assertTrue(selected.getAsJsonObject("artifacts").has("cityScaleTerrainPreview"));
        String selectionRef = selected.get("patchSelectionRef").getAsString();
        assertEquals(selectionRef, service.resolveSelection("run_refined", selectionRef)
                .get("patchSelectionRef").getAsString());

        JsonObject t2Opened = service.open(request("run_refined", "realm_t2", "realm_a"));
        JsonObject t2Show = sessionRequest(t2Opened);
        t2Show.add("interestTypes", strings("plain"));
        assertTopPatchesOverview(root, service.showCandidates(t2Show, runner));

        JsonObject d4Opened = service.open(request("run_refined", "city_d4", "city_a"));
        JsonObject d4Show = sessionRequest(d4Opened);
        d4Show.add("interestTypes", strings("plain"));
        assertTopPatchesOverview(root, service.showCandidates(d4Show, runner));

        Path refinementEvidence = root.resolve(confirmation.getAsJsonObject("artifacts")
                .get("evidence").getAsString());
        String originalEvidence = Files.readString(refinementEvidence);
        JsonObject tampered = read(refinementEvidence);
        tampered.addProperty("tampered", true);
        Files.writeString(refinementEvidence, tampered.toString());
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> service.resolveSelection("run_refined", selectionRef));
        assertTrue(rejected.getMessage().contains("TERRAIN_PREVIEW_TAMPERED"), rejected.getMessage());
        Files.writeString(refinementEvidence, originalEvidence);
        assertEquals(selectionRef, service.resolveSelection("run_refined", selectionRef)
                .get("patchSelectionRef").getAsString());
    }

    @Test
    void rejectsSelectionAfterSourceIdentityChanges() throws Exception {
        Path root = tempDir.resolve("realm_debug");
        Path run = root.resolve("run_stale");
        Files.createDirectories(run);
        writeRealmArtifacts(run);
        PatchExplorerService service = new PatchExplorerService(root);
        JsonObject open = service.open(request("run_stale", "realm_t2", "continent_0"));
        show(service, open, "plain");

        JsonObject context = read(run.resolve("world_survey_context.json"));
        context.addProperty("changedAfterOpen", true);
        Files.writeString(run.resolve("world_survey_context.json"), context.toString());
        JsonObject select = new JsonObject();
        select.addProperty("runId", "run_stale");
        select.addProperty("sessionId", open.get("sessionId").getAsString());
        select.addProperty("candidateId", "PLAIN-01");
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

    @Test
    void realmOpenUsesTScalePatchCellsInsteadOfWPatchGeometry() throws Exception {
        Path root = tempDir.resolve("t_scale_realm_debug");
        Path run = root.resolve("run_t_scale");
        Files.createDirectories(run);
        writeRealmArtifacts(run);
        PatchExplorerService service = new PatchExplorerService(root);

        JsonObject opened = service.open(request("run_t_scale", "realm_t2", "realm_a"),
                (runId, scopeType, scopeId, sourceIdentity, sourceCells) -> {
                    assertTrue(sourceCells.stream().anyMatch(cell -> "p1".equals(cell.sourcePatchRef())));
                    TerrainScalePatchService.PatchCell first = new TerrainScalePatchService.PatchCell(
                            0, 0, 0, 0, "t_water_1", "water", 62.0, true,
                            "minecraft:ocean", 0.0, 0.0);
                    TerrainScalePatchService.PatchCell second = new TerrainScalePatchService.PatchCell(
                            1, 0, 8, 0, "t_water_1", "water", 62.0, true,
                            "minecraft:ocean", 0.0, 0.0);
                    TerrainScalePatchService.Patch patch = new TerrainScalePatchService.Patch(
                            "t_water_1", "water", 1.0, List.of("p1"), List.of(first, second));
                    return new TerrainScalePatchService.Result(8, List.of(patch), 1, 2);
                });

        assertEquals("t_scale_landform_patch", opened.get("candidateBasis").getAsString());
        assertEquals(1, opened.getAsJsonArray("typeCatalog").size());
        assertEquals("water", opened.getAsJsonArray("typeCatalog").get(0).getAsJsonObject()
                .get("patchType").getAsString());
        JsonObject session = read(root.resolve(opened.getAsJsonObject("artifacts")
                .get("explorationSession").getAsString()));
        JsonObject snapshot = read(root.resolve(session.get("scopeSnapshot").getAsString()));
        assertEquals(8, snapshot.get("cellStepBlocks").getAsInt());
        assertEquals("t_water_1", snapshot.getAsJsonArray("candidates").get(0).getAsJsonObject()
                .getAsJsonArray("sourcePatchRefs").get(0).getAsString());
    }

    @Test
    void realmT2SharesRefinementAndKeepsSessionSnapshotsLightweight() throws Exception {
        Path root = tempDir.resolve("shared_t_scale_realm_debug");
        Path run = root.resolve("run_shared_t_scale");
        Files.createDirectories(run);
        writeRealmArtifacts(run);
        PatchExplorerService service = new PatchExplorerService(root);
        AtomicInteger refinements = new AtomicInteger();
        PatchExplorerService.TerrainPatchRunner runner =
                (runId, scopeType, scopeId, refinementIdentity, sourceCells) -> {
                    refinements.incrementAndGet();
                    assertTrue(refinementIdentity.startsWith("sha256:"));
                    TerrainScalePatchService.PatchCell cell = new TerrainScalePatchService.PatchCell(
                            0, 0, 0, 0, "shared_plain_1", "plain", 70.0, false,
                            "minecraft:plains", 0.0, 0.0);
                    TerrainScalePatchService.Patch patch = new TerrainScalePatchService.Patch(
                            "shared_plain_1", "plain", 1.0, List.of("p1"), List.of(cell));
                    return new TerrainScalePatchService.Result(16, List.of(patch), 1, 1,
                            new TerrainScalePatchService.Source("provider_fixture", "generator_native",
                                    "provider:fingerprint", "heightmap_preview"));
                };

        JsonObject first = service.open(request("run_shared_t_scale", "realm_t2", "realm_a"),
                "minecraft:overworld|provider_fixture|provider:fingerprint|heightmap_preview", runner);
        JsonObject second = service.open(request("run_shared_t_scale", "realm_t2", "kingdom_a"),
                "minecraft:overworld|provider_fixture|provider:fingerprint|heightmap_preview", runner);

        assertEquals(1, refinements.get(), "identical T2 ranges must sample and classify only once");
        assertFalse(first.get("tScaleRefinementCacheHit").getAsBoolean());
        assertTrue(second.get("tScaleRefinementCacheHit").getAsBoolean());
        assertEquals(first.get("tScaleRefinementArtifact"), second.get("tScaleRefinementArtifact"));
        assertEquals(first.get("tScaleRefinementIdentity"), second.get("tScaleRefinementIdentity"));
        JsonObject secondSession = read(root.resolve(second.getAsJsonObject("artifacts")
                .get("explorationSession").getAsString()));
        JsonObject secondSnapshot = read(root.resolve(secondSession.get("scopeSnapshot").getAsString()));
        assertTrue(secondSnapshot.has("tScaleRefinementArtifact"));
        assertFalse(secondSnapshot.has("scopeCells"));
        assertFalse(secondSnapshot.has("candidates"));
        assertTrue(Files.size(root.resolve(secondSession.get("scopeSnapshot").getAsString())) < 8192L);
        assertEquals("plain", show(service, second, "plain").getAsJsonArray("typePages").get(0)
                .getAsJsonObject().getAsJsonArray("candidates").get(0).getAsJsonObject()
                .get("patchType").getAsString());

        JsonObject differentProvider = service.open(request("run_shared_t_scale", "realm_t2", "realm_a"),
                "minecraft:overworld|provider_other|provider:other|heightmap_preview", runner);
        assertEquals(2, refinements.get(), "provider identity must isolate T-scale refinement caches");
        assertFalse(differentProvider.get("tScaleRefinementCacheHit").getAsBoolean());
        assertFalse(first.get("tScaleRefinementArtifact").equals(
                differentProvider.get("tScaleRefinementArtifact")));
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

    private static JsonObject sessionRequest(JsonObject opened) {
        JsonObject request = new JsonObject();
        request.addProperty("runId", opened.get("runId").getAsString());
        request.addProperty("sessionId", opened.get("sessionId").getAsString());
        return request;
    }

    private static void assertOverviewSet(Path root, JsonObject artifacts) throws Exception {
        BufferedImage terrain = ImageIO.read(root.resolve(artifacts.get("terrainOverview").getAsString()).toFile());
        BufferedImage patches = ImageIO.read(root.resolve(artifacts.get("allPatchesOverview").getAsString()).toFile());
        BufferedImage biomes = ImageIO.read(root.resolve(artifacts.get("biomeOverview").getAsString()).toFile());
        assertTrue(terrain.getWidth() > 0 && terrain.getHeight() > 0);
        assertTrue(biomes.getWidth() > 0 && biomes.getHeight() > 0);
        assertEquals(terrain.getWidth(), patches.getWidth());
        assertEquals(terrain.getHeight(), patches.getHeight());
    }

    private static void assertTopPatchesOverview(Path root, JsonObject shown) throws Exception {
        JsonObject artifacts = shown.getAsJsonObject("artifacts");
        assertOverviewSet(root, artifacts);
        BufferedImage terrain = ImageIO.read(root.resolve(artifacts.get("terrainOverview").getAsString()).toFile());
        BufferedImage top = ImageIO.read(root.resolve(artifacts.get("topPatchesOverview").getAsString()).toFile());
        assertEquals(terrain.getWidth(), top.getWidth());
        assertEquals(terrain.getHeight(), top.getHeight());
        assertTrue(Files.size(root.resolve(artifacts.get("topPatchesOverview").getAsString())) > 0L);
    }

    private static PatchExplorerService.TerrainPreviewRunner terrainPreviewRunner(Path root) {
        TerrainPreviewProviderDescriptor descriptor = new TerrainPreviewProviderDescriptor(
                "rtf_native", TerrainPreviewSourceKind.GENERATOR_NATIVE, true,
                "rtf:seed:settings", "rtf_preview_cell");
        TerrainPreviewProvider provider = new TerrainPreviewProvider() {
            @Override
            public TerrainPreviewProviderDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public TerrainPreviewProviderAvailability availability() {
                return TerrainPreviewProviderAvailability.ready();
            }

            @Override
            public TerrainPreviewSample sample(int blockX, int blockZ) {
                boolean water = blockZ < -256;
                double elevation = blockX > 256 ? 64.0 + blockX / 4.0 : 64.0;
                return new TerrainPreviewSample(blockX, blockZ, elevation, water,
                        water ? "minecraft:river" : "minecraft:plains");
            }
        };
        TerrainPreviewProviderSelection selection = new TerrainPreviewProviderSelection(provider,
                descriptor.providerId(), descriptor.sourceKind().contractName(), descriptor.fastPath(),
                "", descriptor.sourceFingerprint(), descriptor.samplingSemantics());
        PatchCandidateTerrainPreviewService previewService =
                new PatchCandidateTerrainPreviewService(root);
        return (runId, realmId, scopeIdentity, target, level) -> previewService.ensure(
                runId, realmId, "minecraft:overworld", scopeIdentity, target, level, selection);
    }

    private static void writeRealmArtifacts(Path run) throws Exception {
        JsonObject context = new JsonObject();
        context.addProperty("schema", "realm_planning");
        context.addProperty("surveyId", "survey_fixture");
        context.addProperty("sealed", true);
        context.addProperty("cellStepBlocks", 16);
        Files.writeString(run.resolve("world_survey_context.json"), context.toString());

        JsonObject map = new JsonObject();
        map.addProperty("schema", "realm_planning");
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

    private static void rewriteRealmCellStep(Path run, int step) throws Exception {
        JsonObject context = read(run.resolve("world_survey_context.json"));
        context.addProperty("cellStepBlocks", step);
        Files.writeString(run.resolve("world_survey_context.json"), context.toString());
        JsonObject patchMap = read(run.resolve("world_patch_map.json"));
        for (var element : patchMap.getAsJsonArray("cells")) {
            JsonObject cell = element.getAsJsonObject();
            cell.addProperty("blockX", cell.get("gridX").getAsInt() * step);
            cell.addProperty("blockZ", cell.get("gridZ").getAsInt() * step);
        }
        Files.writeString(run.resolve("world_patch_map.json"), patchMap.toString());
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
        CityTestRunLayout layout = CityTestRunLayout.open(run, "city_a");
        Path d3 = layout.stepDirectory(CityTestRunLayout.D3);
        Files.createDirectories(d3);
        JsonObject review = new JsonObject();
        review.addProperty("schema", "city_landform_review");
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

        Path landUse = layout.stepDirectory(CityTestRunLayout.LAND_USE);
        Files.createDirectories(landUse);
        JsonObject terrainField = new JsonObject();
        terrainField.addProperty("schema", "city_land_use_terrain_field");
        terrainField.addProperty("cityId", "city_a");
        terrainField.add("planningBounds", bounds.deepCopy());
        terrainField.addProperty("cellStepBlocks", 16);
        JsonArray terrainCells = new JsonArray();
        for (int x = 0; x < 6; x++) {
            JsonObject cell = new JsonObject();
            cell.addProperty("cellX", x);
            cell.addProperty("cellZ", 0);
            cell.addProperty("blockMinX", x * 16);
            cell.addProperty("blockMinZ", 0);
            cell.addProperty("cellStepBlocks", 16);
            cell.addProperty("elevation", 64 + x * 2);
            cell.addProperty("slope", x == 5 ? 3.0 : 0.5);
            cell.addProperty("localRelief", x == 5 ? 8.0 : 1.0);
            cell.addProperty("roughness", 0.5);
            cell.addProperty("water", false);
            cell.addProperty("waterDepth", 0.0);
            cell.addProperty("waterDistance", 32.0);
            cell.addProperty("biomeId", "minecraft:plains");
            cell.addProperty("landformType", "plain");
            cell.addProperty("landformPatchId", "d3_plain_1");
            cell.addProperty("sampled", true);
            terrainCells.add(cell);
        }
        terrainField.add("cells", terrainCells);
        Files.writeString(landUse.resolve("land_use_terrain_field.json"), terrainField.toString());

        Path sessionDir = layout.stepDirectory(CityTestRunLayout.D4_CANDIDATE_SESSION);
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

    private static Path writeCoarseTerrainEvidence(Path run) throws Exception {
        return writeCoarseTerrainEvidence(run, "run_terrain", 16);
    }

    private static Path writeCoarseTerrainEvidence(Path run, String runId) throws Exception {
        return writeCoarseTerrainEvidence(run, runId, 16);
    }

    private static Path writeCoarseTerrainEvidence(Path run, String runId, int step) throws Exception {
        Path directory = run.resolve("realm_t4_terrain_preview");
        Files.createDirectories(directory);
        Path preview = directory.resolve("realm_a_coarse_height_water_preview.png");
        Files.write(preview, new byte[]{1, 2, 3});
        JsonObject evidence = new JsonObject();
        evidence.addProperty("schema", RealmT4CoarseTerrainPreviewService.SCHEMA);
        evidence.addProperty("runId", runId);
        evidence.addProperty("realmId", "realm_a");
        evidence.addProperty("advisoryOnly", true);
        evidence.addProperty("requiredNextGate", "city_d3_site_review");
        JsonObject grid = new JsonObject();
        grid.addProperty("cellStepBlocks", step);
        evidence.add("grid", grid);
        JsonObject provider = new JsonObject();
        provider.addProperty("providerId", "rtf_native");
        provider.addProperty("sourceKind", "generator_native");
        provider.addProperty("fastPath", true);
        provider.addProperty("fallbackReason", "");
        provider.addProperty("sourceFingerprint", "rtf:seed:settings");
        provider.addProperty("samplingSemantics", "rtf_preview_cell");
        evidence.add("provider", provider);
        JsonArray cells = new JsonArray();
        for (int x = 0; x <= 5; x++) {
            JsonObject cell = new JsonObject();
            cell.addProperty("gridX", x);
            cell.addProperty("gridZ", 0);
            cell.addProperty("blockX", x * step + step / 2);
            cell.addProperty("blockZ", step / 2);
            cell.addProperty("elevation", 70 + x);
            cell.addProperty("water", x == 4);
            cell.addProperty("biomeId", "minecraft:plains");
            cell.addProperty("terrainId", x == 0 ? "rtf:mountain" : "rtf:flat");
            cell.addProperty("sourceBiomeId", "rtf:climate_temperate");
            cell.addProperty("neighborCount", x == 0 || x == 5 ? 1 : 2);
            cell.addProperty("neighborElevationDeltaMean", x == 0 ? 20 : 1);
            cell.addProperty("neighborElevationDeltaMax", x == 0 ? 20 : 1);
            cell.addProperty("slopeProxy", x == 0 ? 1.25 : 0.0625);
            cell.addProperty("localRelief", switch (x) {
                case 0 -> 20;
                case 1 -> 5;
                case 2 -> 0.5;
                default -> 1.0;
            });
            cells.add(cell);
        }
        evidence.add("cells", cells);
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("heightWaterPreview",
                "realm_t4_terrain_preview/realm_a_coarse_height_water_preview.png");
        evidence.add("artifacts", artifacts);
        Path path = directory.resolve("realm_a_coarse_terrain_evidence.json");
        Files.writeString(path, evidence.toString());
        return path;
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
