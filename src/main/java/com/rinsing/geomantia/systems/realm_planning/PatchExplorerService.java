package com.rinsing.geomantia.systems.realm_planning;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.CityD4CandidateLegalRegion;
import com.rinsing.geomantia.systems.city.application.CityTestRunLayout;
import com.rinsing.geomantia.systems.city.application.landuse.LandUseTerrainFieldCodec;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.gis.preview.LandformPatchPalette;
import com.rinsing.geomantia.systems.realm_planning.application.terrain.PatchCandidateTerrainPreviewService;
import com.rinsing.geomantia.systems.realm_planning.application.terrain.RealmT4CoarseTerrainPreviewService;
import com.rinsing.geomantia.systems.realm_planning.application.terrain.TerrainScalePatchService;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Artifact-backed patch exploration shared by realm T2/T4 and City D4. */
public final class PatchExplorerService {
    public static final String SESSION_SCHEMA = "patch_explorer_session.v0.1";
    public static final String PAGE_SCHEMA = "patch_explorer_candidate_page.v0.1";
    public static final String SELECTION_SCHEMA = "patch_selection.v0.1";
    private static final String SNAPSHOT_SCHEMA = "patch_explorer_scope_snapshot.v0.2";
    private static final String CANDIDATE_MODEL = "landform_patch_candidates_v0_2";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Set<String> SCOPES = Set.of("realm_t2", "realm_t4", "city_d4");
    private static final int DEFAULT_PAGE_SIZE = 3;
    private static final int MAX_PAGE_SIZE = 12;
    private static final int[][] CARDINAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final double[] HEIGHT_STOPS = {-64, 50, 70, 90, 120, 160, 220, 320};
    private static final Color[] HEIGHT_COLORS = {
            new Color(48, 75, 71), new Color(67, 112, 73), new Color(112, 145, 79),
            new Color(164, 159, 88), new Color(154, 140, 111), new Color(160, 158, 149),
            new Color(207, 207, 201), new Color(242, 242, 238)
    };
    private final Path debugRoot;

    public PatchExplorerService(Path debugRoot) {
        this.debugRoot = Objects.requireNonNull(debugRoot, "debugRoot").toAbsolutePath().normalize();
    }

    public JsonObject open(JsonObject request) throws IOException {
        return open(request, null);
    }

    public JsonObject open(JsonObject request, TerrainPatchRunner terrainPatchRunner) throws IOException {
        String runId = safeId(requiredString(request, "runId"), "runId");
        String scopeType = requiredString(request, "scopeType").toLowerCase(Locale.ROOT);
        if (!SCOPES.contains(scopeType)) {
            throw new IllegalArgumentException("PATCH_EXPLORER_SCOPE_UNSUPPORTED: " + scopeType);
        }
        String scopeId = scopeId(request, scopeType);
        String sessionId = stringValue(request, "sessionId", "");
        if (sessionId.isBlank()) {
            sessionId = "pex_" + UUID.randomUUID().toString().replace("-", "");
        }
        sessionId = safeId(sessionId, "sessionId");
        Scope scope = loadScope(runId, scopeType, scopeId, terrainPatchRunner);
        JsonObject session = new JsonObject();
        session.addProperty("schemaVersion", SESSION_SCHEMA);
        session.addProperty("sessionId", sessionId);
        session.addProperty("runId", runId);
        session.addProperty("scopeType", scopeType);
        session.addProperty("scopeId", scopeId);
        session.addProperty("candidateModel", CANDIDATE_MODEL);
        session.addProperty("candidateBasis", candidateBasis(scope));
        session.addProperty("sourceIdentity", scope.sourceIdentity());
        session.addProperty("preferGeneratorNativeTerrain",
                booleanValue(request, "preferGeneratorNativeTerrain", true));
        session.addProperty("createdAt", Instant.now().toString());
        session.addProperty("updatedAt", Instant.now().toString());
        session.add("displayedCandidates", new JsonArray());
        session.add("interestTypes", new JsonArray());
        session.add("sourceArtifacts", strings(scope.sourceArtifacts()));
        Path sessionDir = sessionDir(runId, sessionId);
        Files.createDirectories(sessionDir);
        Path snapshotPath = sessionDir.resolve("scope_snapshot.json");
        writeScopeSnapshot(snapshotPath, scope);
        session.addProperty("scopeSnapshot", debugRef(snapshotPath));
        session.addProperty("scopeSnapshotIdentity", "sha256:" + sha256(snapshotPath));
        Path terrainOverviewPath = sessionDir.resolve("terrain_overview.png");
        Path allPatchesOverviewPath = sessionDir.resolve("all_patches_overview.png");
        renderOverview(scope, List.of(), OverviewMode.TERRAIN, terrainOverviewPath);
        renderOverview(scope, List.of(), OverviewMode.ALL_PATCHES, allPatchesOverviewPath);
        session.add("patchTypePalette", patchTypePalette());
        JsonObject overviewArtifacts = new JsonObject();
        overviewArtifacts.addProperty("terrainOverview", debugRef(terrainOverviewPath));
        overviewArtifacts.addProperty("allPatchesOverview", debugRef(allPatchesOverviewPath));
        session.add("overviewArtifacts", overviewArtifacts);
        writeJson(sessionDir.resolve("patch_explorer_session.json"), session);

        JsonObject response = base("open", runId, sessionId);
        response.addProperty("scopeType", scopeType);
        response.addProperty("scopeId", scopeId);
        response.addProperty("candidateBasis", candidateBasis(scope));
        response.addProperty("sourceIdentity", scope.sourceIdentity());
        response.add("typeCatalog", typeCatalog(scope));
        response.add("patchTypePalette", patchTypePalette());
        JsonObject artifacts = artifactRefs(sessionDir, null, null, scope.coarseTerrainSource());
        artifacts.addProperty("terrainOverview", debugRef(terrainOverviewPath));
        artifacts.addProperty("allPatchesOverview", debugRef(allPatchesOverviewPath));
        response.add("artifacts", artifacts);
        response.add("nextActions", strings(List.of("patch_explorer_show_candidates")));
        return response;
    }

    public SessionTerrainContext sessionTerrainContext(JsonObject request) throws IOException {
        String runId = safeId(requiredString(request, "runId"), "runId");
        String sessionId = safeId(requiredString(request, "sessionId"), "sessionId");
        JsonObject session = loadSession(runId, sessionId);
        return new SessionTerrainContext(requiredString(session, "scopeType"),
                booleanValue(session, "preferGeneratorNativeTerrain", true));
    }

    public JsonObject showCandidates(JsonObject request) throws IOException {
        return showCandidates(request, null);
    }

    public JsonObject showCandidates(JsonObject request, TerrainPreviewRunner terrainPreviewRunner) throws IOException {
        String runId = safeId(requiredString(request, "runId"), "runId");
        String sessionId = safeId(requiredString(request, "sessionId"), "sessionId");
        JsonObject session = loadSession(runId, sessionId);
        Scope scope = freshScope(session);
        List<String> interestTypes = requiredTypes(request);
        int pageSize = intValue(request, "pageSize", DEFAULT_PAGE_SIZE);
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("PATCH_EXPLORER_PAGE_SIZE_INVALID: pageSize must be 1.." + MAX_PAGE_SIZE);
        }
        int page = pageIndex(request, pageSize, scope.sourceIdentity(), interestTypes);
        Map<String, List<Candidate>> ranked = rankByType(scope.candidates());
        List<Candidate> displayed = new ArrayList<>();
        JsonArray typePages = new JsonArray();
        for (String type : interestTypes) {
            List<Candidate> all = ranked.get(type);
            if (all == null) {
                throw new IllegalArgumentException("PATCH_EXPLORER_TYPE_NOT_IN_SCOPE: " + type);
            }
            int from = Math.min(all.size(), page * pageSize);
            int to = Math.min(all.size(), from + pageSize);
            List<Candidate> slice = all.subList(from, to);
            displayed.addAll(slice);
            JsonObject typePage = new JsonObject();
            typePage.addProperty("patchType", type);
            typePage.addProperty("color", LandformPatchPalette.hex(type));
            typePage.addProperty("page", page);
            typePage.addProperty("pageSize", pageSize);
            typePage.addProperty("totalCandidates", all.size());
            typePage.addProperty("hasPrevious", page > 0);
            typePage.addProperty("hasNext", to < all.size());
            typePage.addProperty("nextPageToken", to < all.size()
                    ? pageToken(page + 1, pageSize, scope.sourceIdentity(), interestTypes) : "");
            typePage.add("candidates", candidateJson(slice, scope));
            typePages.add(typePage);
        }
        displayed.sort(CANDIDATE_ORDER);
        JsonArray relations = sparseRelations(displayed, scope.cellStepBlocks());
        Path sessionDir = sessionDir(runId, sessionId);
        String pageKey = shortHash(String.join(",", interestTypes) + ":" + page + ":" + pageSize);
        Path pagePath = sessionDir.resolve("candidate_page_" + pageKey + ".json");
        Path topPatchesOverviewPath = sessionDir.resolve("top_patches_overview_" + pageKey + ".png");
        renderOverview(scope, displayed, OverviewMode.TOP_PATCHES, topPatchesOverviewPath);

        JsonObject pageArtifact = new JsonObject();
        pageArtifact.addProperty("schemaVersion", PAGE_SCHEMA);
        pageArtifact.addProperty("sessionId", sessionId);
        pageArtifact.addProperty("runId", runId);
        pageArtifact.addProperty("scopeType", scope.scopeType());
        pageArtifact.addProperty("scopeId", scope.scopeId());
        pageArtifact.addProperty("sourceIdentity", scope.sourceIdentity());
        pageArtifact.add("interestTypes", strings(interestTypes));
        pageArtifact.addProperty("page", page);
        pageArtifact.addProperty("pageSize", pageSize);
        pageArtifact.add("patchTypePalette", patchTypePalette());
        pageArtifact.add("typePages", typePages);
        pageArtifact.add("relations", relations);
        JsonObject pageArtifacts = new JsonObject();
        pageArtifacts.addProperty("topPatchesOverview", debugRef(topPatchesOverviewPath));
        pageArtifact.add("artifacts", pageArtifacts);
        writeJson(pagePath, pageArtifact);

        session.add("interestTypes", strings(interestTypes));
        JsonArray displayedIds = new JsonArray();
        for (Candidate candidate : displayed) {
            displayedIds.add(candidate.candidateId());
        }
        session.add("displayedCandidates", displayedIds);
        session.addProperty("lastPageArtifact", debugRef(pagePath));
        session.addProperty("updatedAt", Instant.now().toString());
        writeJson(sessionDir.resolve("patch_explorer_session.json"), session);

        JsonObject response = base("show_candidates", runId, sessionId);
        response.addProperty("scopeType", scope.scopeType());
        response.addProperty("scopeId", scope.scopeId());
        response.addProperty("sourceIdentity", scope.sourceIdentity());
        response.add("patchTypePalette", patchTypePalette());
        response.add("typePages", typePages);
        response.add("relations", relations);
        JsonObject artifacts = artifactRefs(sessionDir, pagePath, null, scope.coarseTerrainSource());
        artifacts.addProperty("terrainOverview", debugRef(sessionDir.resolve("terrain_overview.png")));
        artifacts.addProperty("allPatchesOverview", debugRef(sessionDir.resolve("all_patches_overview.png")));
        artifacts.addProperty("topPatchesOverview", debugRef(topPatchesOverviewPath));
        response.add("artifacts", artifacts);
        response.add("nextActions", strings(List.of("patch_explorer_show_candidates", "patch_explorer_select_candidate")));
        return response;
    }

    public JsonObject selectCandidate(JsonObject request) throws IOException {
        return selectCandidate(request, null);
    }

    public JsonObject selectCandidate(JsonObject request, TerrainPreviewRunner terrainPreviewRunner) throws IOException {
        String runId = safeId(requiredString(request, "runId"), "runId");
        String sessionId = safeId(requiredString(request, "sessionId"), "sessionId");
        String candidateId = safeId(requiredString(request, "candidateId"), "candidateId");
        JsonObject session = loadSession(runId, sessionId);
        Scope scope = freshScope(session);
        if (!containsString(array(session, "displayedCandidates"), candidateId)) {
            throw new IllegalArgumentException("PATCH_EXPLORER_CANDIDATE_NOT_DISPLAYED: " + candidateId);
        }
        Candidate selected = scope.candidates().stream()
                .filter(candidate -> candidate.candidateId().equals(candidateId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("PATCH_EXPLORER_CANDIDATE_STALE: " + candidateId));
        Cell anchor = suggestedAnchor(selected.cells(), scope);
        PatchCandidateTerrainPreviewService.Result terrainPreview = createTerrainPreview(scope, selected,
                PatchCandidateTerrainPreviewService.Level.CITY_SCALE_CONFIRMATION, terrainPreviewRunner);
        String terrainPreviewSourceIdentity = terrainPreview == null ? "" : terrainPreview.sourceIdentity();
        String selectionRef = selectionRef(sessionId, candidateId, scope.sourceIdentity(), terrainPreviewSourceIdentity);
        JsonObject selection = new JsonObject();
        selection.addProperty("schemaVersion", SELECTION_SCHEMA);
        selection.addProperty("patchSelectionRef", selectionRef);
        selection.addProperty("sessionId", sessionId);
        selection.addProperty("runId", runId);
        selection.addProperty("scopeType", scope.scopeType());
        selection.addProperty("scopeId", scope.scopeId());
        selection.addProperty("candidateBasis", candidateBasis(scope));
        selection.addProperty("sourceIdentity", scope.sourceIdentity());
        selection.addProperty("scopeSnapshotIdentity", requiredString(session, "scopeSnapshotIdentity"));
        selection.addProperty("candidateId", selected.candidateId());
        selection.addProperty("patchType", selected.type());
        selection.add("sourcePatchRefs", strings(selected.sourcePatchRefs()));
        selection.addProperty("areaBlocks", selected.areaBlocks());
        selection.addProperty("largestContinuousAreaBlocks", selected.largestContinuousAreaBlocks());
        selection.addProperty("originalAreaBlocks", selected.originalAreaBlocks());
        selection.addProperty("cellCount", selected.cells().size());
        selection.addProperty("cellStepBlocks", scope.cellStepBlocks());
        selection.add("bounds", boundsJson(selected.bounds(), scope.cellStepBlocks()));
        selection.add("selectedComponent", legalRegionJson(selected, scope.cellStepBlocks(), selectionRef));
        selection.add("suggestedAnchor", anchorJson(anchor));
        selection.add("terrainComposition", composition(selected.cells(), Cell::terrainType));
        selection.add("baseLandformComposition", composition(selected.cells(), Cell::baseLandform));
        JsonObject coarseTerrainEvidence = coarseTerrainEvidence(selected.cells(), scope.coarseTerrainSource());
        if (coarseTerrainEvidence != null) {
            selection.add("coarseTerrainEvidence", coarseTerrainEvidence);
        }
        if (terrainPreview != null) {
            selection.add("terrainPreview", terrainPreviewJson(terrainPreview));
        }
        selection.addProperty("selectionReason", stringValue(request, "selectionReason", ""));
        selection.addProperty("selectedAt", Instant.now().toString());
        Path selectionDir = runDir(runId).resolve("patch_explorer_selections");
        Files.createDirectories(selectionDir);
        Path selectionPath = selectionDir.resolve(selectionRef + ".json");
        writeJson(selectionPath, selection);
        Path confirmationPath;
        if (terrainPreview != null) {
            confirmationPath = terrainPreview.terrainPreviewPath();
        } else {
            confirmationPath = sessionDir(runId, sessionId).resolve("selected_" + candidateId + ".png");
            renderOverview(scope, List.of(selected), OverviewMode.TOP_PATCHES, confirmationPath);
        }
        selection.addProperty("confirmationPreview", debugRef(confirmationPath));
        writeJson(selectionPath, selection);

        JsonObject response = base("select_candidate", runId, sessionId);
        response.addProperty("patchSelectionRef", selectionRef);
        response.add("selection", selection.deepCopy());
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("selection", debugRef(selectionPath));
        artifacts.addProperty("confirmationPreview", debugRef(confirmationPath));
        artifacts.addProperty("explorationSession", debugRef(sessionDir(runId, sessionId)
                .resolve("patch_explorer_session.json")));
        addHeightWaterPreview(artifacts, scope.coarseTerrainSource());
        if (terrainPreview != null) {
            artifacts.add("selectedTerrainPreview", terrainPreviewRefs(terrainPreview));
            artifacts.addProperty("cityScaleTerrainPreview", debugRef(terrainPreview.terrainPreviewPath()));
        }
        response.add("artifacts", artifacts);
        response.add("nextActions", strings(List.of("return_to_patch_explorer_session", "continue_with_patch_selection")));
        return response;
    }

    public JsonObject resolveSelection(String runId, String patchSelectionRef) throws IOException {
        runId = safeId(runId, "runId");
        patchSelectionRef = safeId(patchSelectionRef, "patchSelectionRef");
        if (!patchSelectionRef.startsWith("psel_")) {
            throw new IllegalArgumentException("PATCH_SELECTION_REF_INVALID: " + patchSelectionRef);
        }
        Path path = runDir(runId).resolve("patch_explorer_selections").resolve(patchSelectionRef + ".json");
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("PATCH_SELECTION_REF_NOT_FOUND: " + patchSelectionRef);
        }
        JsonObject selection = readObject(path);
        if (!SELECTION_SCHEMA.equals(stringValue(selection, "schemaVersion", ""))) {
            throw new IllegalArgumentException("PATCH_SELECTION_SCHEMA_UNSUPPORTED");
        }
        JsonObject session = loadSession(runId, requiredString(selection, "sessionId"));
        Scope scope = freshScope(session);
        if (!scope.sourceIdentity().equals(requiredString(selection, "sourceIdentity"))) {
            throw new IllegalArgumentException("PATCH_SELECTION_STALE_SOURCE: " + patchSelectionRef);
        }
        if (!requiredString(session, "scopeSnapshotIdentity")
                .equals(requiredString(selection, "scopeSnapshotIdentity"))) {
            throw new IllegalArgumentException("PATCH_SELECTION_SCOPE_SNAPSHOT_MISMATCH: " + patchSelectionRef);
        }
        String terrainPreviewSourceIdentity = "";
        if (selection.has("terrainPreview") && selection.get("terrainPreview").isJsonObject()) {
            JsonObject terrainPreview = selection.getAsJsonObject("terrainPreview");
            terrainPreviewSourceIdentity = validateFrozenTerrainPreview(terrainPreview);
        }
        String expectedRef = selectionRef(requiredString(selection, "sessionId"),
                requiredString(selection, "candidateId"), scope.sourceIdentity(), terrainPreviewSourceIdentity);
        if (!patchSelectionRef.equals(expectedRef)) {
            throw new IllegalArgumentException("PATCH_SELECTION_REF_CONTENT_MISMATCH: " + patchSelectionRef);
        }
        Candidate current = scope.candidates().stream()
                .filter(candidate -> candidate.candidateId().equals(requiredString(selection, "candidateId")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("PATCH_SELECTION_CANDIDATE_MISSING"));
        List<String> selectedRefs = new ArrayList<>();
        for (JsonElement element : array(selection, "sourcePatchRefs")) selectedRefs.add(element.getAsString());
        if (!current.sourcePatchRefs().equals(selectedRefs)
                || !current.type().equals(requiredString(selection, "patchType"))
                || current.areaBlocks() != longValue(selection, "areaBlocks", -1L)
                || current.largestContinuousAreaBlocks() != longValue(selection,
                "largestContinuousAreaBlocks", -1L)) {
            throw new IllegalArgumentException("PATCH_SELECTION_CONTENT_MISMATCH: " + patchSelectionRef);
        }
        JsonObject expectedComponent = legalRegionJson(current, scope.cellStepBlocks(), patchSelectionRef);
        if (!selection.has("selectedComponent") || !selection.get("selectedComponent").isJsonObject()
                || !expectedComponent.equals(selection.getAsJsonObject("selectedComponent"))) {
            throw new IllegalArgumentException("PATCH_SELECTION_COMPONENT_MISMATCH: " + patchSelectionRef);
        }
        return selection;
    }

    public JsonObject resolveD4DesignSlotPlan(String runId, String citySeedId, JsonObject designSlotPlan)
            throws IOException {
        rejectReservedD4LegalRegion(designSlotPlan, "designSlotPlan");
        for (JsonElement element : array(designSlotPlan, "slots")) {
            if (element.isJsonObject()) {
                rejectReservedD4LegalRegion(element.getAsJsonObject(), "designSlotPlan.slots[]");
            }
        }
        JsonObject resolved = designSlotPlan.deepCopy();
        for (JsonElement element : array(resolved, "slots")) {
            JsonObject slot = element.getAsJsonObject();
            applyD4Selection(runId, citySeedId, slot, "candidatePatchRefs");
        }
        return resolved;
    }

    public JsonObject resolveT2Selection(String runId, String realmId, String patchSelectionRef) throws IOException {
        JsonObject selection = resolveSelection(runId, patchSelectionRef);
        String scopeId = requiredString(selection, "scopeId");
        String expectedContinent = continentForRealm(runDir(runId).resolve("realm_profiles.json"), realmId);
        if (!"realm_t2".equals(requiredString(selection, "scopeType"))
                || !realmId.equals(scopeId) && !scopeId.equals(expectedContinent)) {
            throw new IllegalArgumentException("PATCH_SELECTION_T2_SCOPE_MISMATCH: " + patchSelectionRef);
        }
        return selection;
    }

    public JsonObject resolveD4ArrayPlan(String runId, String citySeedId, JsonObject arrayPlan) throws IOException {
        rejectReservedD4LegalRegion(arrayPlan, "arrayCandidatePlan");
        JsonObject resolved = arrayPlan.deepCopy();
        applyD4Selection(runId, citySeedId, resolved, "candidatePatchRefs");
        return resolved;
    }

    public JsonObject resolveD4ArrayLayoutPlan(String runId, String citySeedId, JsonObject arrayLayoutPlan)
            throws IOException {
        rejectReservedD4LegalRegion(arrayLayoutPlan, "arrayLayoutPlan");
        for (JsonElement element : array(arrayLayoutPlan, "layoutPlans")) {
            if (element.isJsonObject()) {
                rejectReservedD4LegalRegion(element.getAsJsonObject(), "arrayLayoutPlan.layoutPlans[]");
            }
        }
        JsonObject resolved = arrayLayoutPlan.deepCopy();
        for (JsonElement element : array(resolved, "layoutPlans")) {
            if (element.isJsonObject()) {
                applyD4Selection(runId, citySeedId, element.getAsJsonObject(), "candidatePatchRefs");
            }
        }
        return resolved;
    }

    public JsonObject resolveD4ExpansionRequest(String runId, String citySeedId, JsonObject expansionRequest)
            throws IOException {
        rejectReservedD4LegalRegion(expansionRequest, "arrayExpansionRequest");
        for (String itemField : List.of("nextArrayLayoutPlanItem", "arrayLayoutPlanItem")) {
            if (expansionRequest.has(itemField) && expansionRequest.get(itemField).isJsonObject()) {
                rejectReservedD4LegalRegion(expansionRequest.getAsJsonObject(itemField),
                        "arrayExpansionRequest." + itemField);
            }
        }
        JsonObject resolved = expansionRequest.deepCopy();
        JsonObject selection = applyD4Selection(runId, citySeedId, resolved, "candidatePatchRefs");
        if (selection != null) {
            JsonArray refs = selection.getAsJsonArray("sourcePatchRefs");
            if (refs.size() != 1) {
                throw new IllegalArgumentException("PATCH_SELECTION_D4_EXPANSION_REQUIRES_SINGLE_PATCH");
            }
            resolved.addProperty("selectedGlobalPatchRef", refs.get(0).getAsString());
            resolved.addProperty("newFunctionalArea", true);
            for (String itemField : List.of("nextArrayLayoutPlanItem", "arrayLayoutPlanItem")) {
                if (resolved.has(itemField) && resolved.get(itemField).isJsonObject()) {
                    resolved.getAsJsonObject(itemField).add(CityD4CandidateLegalRegion.FIELD,
                            selection.getAsJsonObject("selectedComponent").deepCopy());
                }
            }
        }
        return resolved;
    }

    private JsonObject applyD4Selection(String runId, String citySeedId, JsonObject target,
                                        String patchRefsField) throws IOException {
        String selectionRef = stringValue(target, "patchSelectionRef", "");
        if (selectionRef.isBlank()) {
            return null;
        }
        JsonObject selection = resolveSelection(runId, selectionRef);
        if (!"city_d4".equals(requiredString(selection, "scopeType"))
                || !citySeedId.equals(requiredString(selection, "scopeId"))) {
            throw new IllegalArgumentException("PATCH_SELECTION_D4_SCOPE_MISMATCH: " + selectionRef);
        }
        target.add(patchRefsField, selection.getAsJsonArray("sourcePatchRefs").deepCopy());
        target.add(CityD4CandidateLegalRegion.FIELD,
                selection.getAsJsonObject("selectedComponent").deepCopy());
        return selection;
    }

    private static void rejectReservedD4LegalRegion(JsonObject target, String location) {
        if (target != null && target.has(CityD4CandidateLegalRegion.FIELD)) {
            throw new IllegalArgumentException("PATCH_SELECTION_D4_RESERVED_FIELD_FORBIDDEN: "
                    + location + "." + CityD4CandidateLegalRegion.FIELD);
        }
    }

    private Scope freshScope(JsonObject session) throws IOException {
        String scopeType = requiredString(session, "scopeType");
        String scopeId = requiredString(session, "scopeId");
        List<String> sourceArtifacts;
        String currentIdentity;
        if ("city_d4".equals(scopeType)) {
            Scope current = loadCityScope(requiredString(session, "runId"), scopeId,
                    runDir(requiredString(session, "runId")));
            currentIdentity = current.sourceIdentity();
            sourceArtifacts = current.sourceArtifacts();
        } else {
            List<Path> sources = sessionSources(session);
            currentIdentity = sourceIdentity(scopeType, scopeId, sources);
            sourceArtifacts = sources.stream().map(this::debugRef).toList();
        }
        String expected = requiredString(session, "sourceIdentity");
        if (!expected.equals(currentIdentity)) {
            throw new IllegalArgumentException("PATCH_EXPLORER_SESSION_STALE_SOURCE: expected "
                    + expected + " but current source is " + currentIdentity);
        }
        Path snapshotPath = debugRoot.resolve(requiredString(session, "scopeSnapshot"))
                .toAbsolutePath().normalize();
        if (!snapshotPath.startsWith(debugRoot)) {
            throw new IllegalArgumentException("PATCH_EXPLORER_SNAPSHOT_OUTSIDE_DEBUG_ROOT");
        }
        if (!("sha256:" + sha256(snapshotPath)).equals(requiredString(session, "scopeSnapshotIdentity"))) {
            throw new IllegalArgumentException("PATCH_EXPLORER_SCOPE_SNAPSHOT_TAMPERED");
        }
        return readScopeSnapshot(snapshotPath, expected, sourceArtifacts);
    }

    private List<Path> sessionSources(JsonObject session) {
        List<Path> sources = new ArrayList<>();
        for (JsonElement element : array(session, "sourceArtifacts")) {
            Path source = debugRoot.resolve(element.getAsString()).toAbsolutePath().normalize();
            if (!source.startsWith(debugRoot)) {
                throw new IllegalArgumentException("PATCH_EXPLORER_SOURCE_OUTSIDE_DEBUG_ROOT: " + source);
            }
            requireFile(source, "PATCH_EXPLORER_SOURCE_MISSING");
            sources.add(source);
        }
        return sources;
    }

    private void writeScopeSnapshot(Path path, Scope scope) throws IOException {
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("schemaVersion", SNAPSHOT_SCHEMA);
        snapshot.addProperty("runId", scope.runId());
        snapshot.addProperty("scopeType", scope.scopeType());
        snapshot.addProperty("scopeId", scope.scopeId());
        snapshot.addProperty("candidateModel", CANDIDATE_MODEL);
        snapshot.addProperty("candidateBasis", candidateBasis(scope));
        snapshot.addProperty("cellStepBlocks", scope.cellStepBlocks());
        snapshot.addProperty("sourceIdentity", scope.sourceIdentity());
        if (scope.coarseTerrainSource() != null) {
            snapshot.add("coarseTerrainSource", scope.coarseTerrainSource().asJson());
        }
        if (scope.terrainPatchSource() != null) {
            snapshot.add("terrainPatchSource", scope.terrainPatchSource().asJson());
        }
        JsonArray scopeCells = new JsonArray();
        for (Cell cell : scope.scopeCells()) {
            scopeCells.add(cellJson(cell));
        }
        snapshot.add("scopeCells", scopeCells);
        JsonArray candidates = new JsonArray();
        for (Candidate candidate : scope.candidates()) {
            JsonObject item = new JsonObject();
            item.addProperty("candidateId", candidate.candidateId());
            item.addProperty("type", candidate.type());
            item.add("sourcePatchRefs", strings(candidate.sourcePatchRefs()));
            item.addProperty("areaBlocks", candidate.areaBlocks());
            item.addProperty("largestContinuousAreaBlocks", candidate.largestContinuousAreaBlocks());
            item.addProperty("originalAreaBlocks", candidate.originalAreaBlocks());
            item.addProperty("confidence", candidate.confidence());
            JsonArray cells = new JsonArray();
            for (Cell cell : candidate.cells()) {
                cells.add(cellJson(cell));
            }
            item.add("cells", cells);
            candidates.add(item);
        }
        snapshot.add("candidates", candidates);
        JsonArray occupied = new JsonArray();
        for (Bounds bounds : scope.occupied()) {
            JsonObject item = new JsonObject();
            item.addProperty("minX", bounds.minX());
            item.addProperty("minZ", bounds.minZ());
            item.addProperty("maxX", bounds.maxX());
            item.addProperty("maxZ", bounds.maxZ());
            occupied.add(item);
        }
        snapshot.add("occupied", occupied);
        writeJson(path, snapshot);
    }

    private static JsonObject cellJson(Cell cell) {
        JsonObject result = new JsonObject();
        result.addProperty("x", cell.x());
        result.addProperty("z", cell.z());
        result.addProperty("blockX", cell.blockX());
        result.addProperty("blockZ", cell.blockZ());
        result.addProperty("patchRef", cell.patchRef());
        result.addProperty("candidateType", cell.type());
        result.addProperty("terrainType", cell.terrainType());
        result.addProperty("baseLandform", cell.baseLandform());
        result.addProperty("confidence", cell.confidence());
        if (cell.coarseTerrain() != null) {
            result.add("coarseTerrain", cell.coarseTerrain().asJson());
        }
        return result;
    }

    private Scope readScopeSnapshot(Path path, String expectedIdentity, List<String> sourceArtifacts)
            throws IOException {
        JsonObject snapshot = readObject(path);
        if (!SNAPSHOT_SCHEMA.equals(stringValue(snapshot, "schemaVersion", ""))
                || !CANDIDATE_MODEL.equals(stringValue(snapshot, "candidateModel", ""))
                || !expectedIdentity.equals(stringValue(snapshot, "sourceIdentity", ""))) {
            throw new IllegalArgumentException("PATCH_EXPLORER_SCOPE_SNAPSHOT_STALE_OR_UNSUPPORTED");
        }
        int step = intValue(snapshot, "cellStepBlocks", 0);
        CoarseTerrainSource coarseTerrainSource = snapshot.has("coarseTerrainSource")
                && snapshot.get("coarseTerrainSource").isJsonObject()
                ? CoarseTerrainSource.fromJson(snapshot.getAsJsonObject("coarseTerrainSource")) : null;
        TerrainPatchSource terrainPatchSource = snapshot.has("terrainPatchSource")
                && snapshot.get("terrainPatchSource").isJsonObject()
                ? TerrainPatchSource.fromJson(snapshot.getAsJsonObject("terrainPatchSource")) : null;
        List<Candidate> candidates = new ArrayList<>();
        for (JsonElement element : array(snapshot, "candidates")) {
            JsonObject item = element.getAsJsonObject();
            String type = requiredString(item, "type");
            List<Cell> cells = new ArrayList<>();
            for (JsonElement cellElement : array(item, "cells")) {
                JsonObject cell = cellElement.getAsJsonObject();
                cells.add(new Cell(intValue(cell, "x", 0), intValue(cell, "z", 0),
                        intValue(cell, "blockX", 0), intValue(cell, "blockZ", 0), step,
                        requiredString(cell, "patchRef"),
                        stringValue(cell, "candidateType", type),
                        stringValue(cell, "terrainType", type),
                        stringValue(cell, "baseLandform", stringValue(cell, "terrainType", type)),
                        doubleValue(cell, "confidence", 0.5),
                        cell.has("coarseTerrain") && cell.get("coarseTerrain").isJsonObject()
                                ? CoarseTerrain.fromJson(cell.getAsJsonObject("coarseTerrain")) : null));
            }
            if (cells.isEmpty()) {
                throw new IllegalArgumentException("PATCH_EXPLORER_SCOPE_SNAPSHOT_EMPTY_CANDIDATE");
            }
            List<String> refs = new ArrayList<>();
            for (JsonElement ref : array(item, "sourcePatchRefs")) refs.add(ref.getAsString());
            candidates.add(new Candidate(requiredString(item, "candidateId"), type, List.copyOf(refs),
                    List.copyOf(cells), longValue(item, "areaBlocks", 0L),
                    longValue(item, "largestContinuousAreaBlocks", longValue(item, "areaBlocks", 0L)),
                    longValue(item, "originalAreaBlocks", 0L), bounds(cells),
                    doubleValue(item, "confidence", 0.5)));
        }
        List<Bounds> occupied = new ArrayList<>();
        for (JsonElement element : array(snapshot, "occupied")) occupied.add(bounds(element.getAsJsonObject()));
        List<Cell> scopeCells = new ArrayList<>();
        for (JsonElement element : array(snapshot, "scopeCells")) {
            scopeCells.add(readCell(element.getAsJsonObject(), step, "unknown"));
        }
        if (scopeCells.isEmpty()) {
            throw new IllegalArgumentException("PATCH_EXPLORER_SCOPE_SNAPSHOT_EMPTY_SCOPE");
        }
        candidates.sort(CANDIDATE_ORDER);
        return new Scope(requiredString(snapshot, "runId"), requiredString(snapshot, "scopeType"),
                requiredString(snapshot, "scopeId"), step, List.copyOf(candidates), List.copyOf(scopeCells),
                expectedIdentity, sourceArtifacts, List.copyOf(occupied), coarseTerrainSource, terrainPatchSource);
    }

    private static Cell readCell(JsonObject cell, int step, String fallbackType) {
        String type = stringValue(cell, "candidateType", fallbackType);
        return new Cell(intValue(cell, "x", 0), intValue(cell, "z", 0),
                intValue(cell, "blockX", 0), intValue(cell, "blockZ", 0), step,
                stringValue(cell, "patchRef", ""), type,
                stringValue(cell, "terrainType", type),
                stringValue(cell, "baseLandform", stringValue(cell, "terrainType", type)),
                doubleValue(cell, "confidence", 0.5),
                cell.has("coarseTerrain") && cell.get("coarseTerrain").isJsonObject()
                        ? CoarseTerrain.fromJson(cell.getAsJsonObject("coarseTerrain")) : null);
    }

    private Scope loadScope(String runId, String scopeType, String scopeId,
            TerrainPatchRunner terrainPatchRunner) throws IOException {
        Path runDir = runDir(runId);
        if ("city_d4".equals(scopeType)) {
            return loadCityScope(runId, scopeId, runDir);
        }
        return loadRealmScope(runId, scopeType, scopeId, runDir, terrainPatchRunner);
    }

    private Scope loadRealmScope(String runId, String scopeType, String scopeId, Path runDir,
            TerrainPatchRunner terrainPatchRunner) throws IOException {
        Path contextPath = runDir.resolve("world_survey_context.json");
        Path patchPath = runDir.resolve("world_patch_map.json");
        requireFile(contextPath, "PATCH_EXPLORER_W_CONTEXT_MISSING");
        requireFile(patchPath, "PATCH_EXPLORER_W_PATCH_MAP_MISSING");
        JsonObject context = readObject(contextPath);
        if (!booleanValue(context, "sealed", false)) {
            throw new IllegalArgumentException("PATCH_EXPLORER_W_NOT_SEALED");
        }
        JsonObject patchMap = readObject(patchPath);
        int step = intValue(context, "cellStepBlocks", 0);
        if (step <= 0) {
            throw new IllegalArgumentException("PATCH_EXPLORER_CELL_STEP_INVALID");
        }
        Set<String> owned = null;
        Set<String> allowedPatches = null;
        TerrainEvidenceBundle terrainEvidence = TerrainEvidenceBundle.empty();
        TerrainPatchSource terrainPatchSource = null;
        Path territoryPath = runDir.resolve("realm_territory_map.json");
        List<Path> sources = new ArrayList<>(List.of(contextPath, patchPath));
        String continentScope = scopeId;
        if ("realm_t4".equals(scopeType)) {
            requireFile(territoryPath, "PATCH_EXPLORER_TERRITORY_MISSING");
            sources.add(territoryPath);
            owned = new HashSet<>();
            for (JsonElement element : array(readObject(territoryPath), "territoryCells")) {
                JsonObject cell = element.getAsJsonObject();
                if (scopeId.equals(stringValue(cell, "realmId", ""))
                        && "owned".equals(stringValue(cell, "status", ""))) {
                    owned.add(key(intValue(cell, "gridX", 0), intValue(cell, "gridZ", 0)));
                }
            }
            if (owned.isEmpty()) {
                throw new IllegalArgumentException("PATCH_EXPLORER_REALM_HAS_NO_OWNED_TERRITORY: " + scopeId);
            }
            Path evidencePath = runDir.resolve("realm_t4_terrain_preview")
                    .resolve(scopeId + "_coarse_terrain_evidence.json");
            if (Files.isRegularFile(evidencePath)) {
                terrainEvidence = loadTerrainEvidence(runId, scopeId, runDir, evidencePath, step);
                sources.add(evidencePath);
            }
        } else {
            Path packagesPath = runDir.resolve("candidate_map_packages.json");
            JsonObject candidatePackage = candidatePackageForRealm(packagesPath, scopeId);
            if (candidatePackage != null) {
                allowedPatches = new HashSet<>();
                for (JsonElement element : array(candidatePackage, "allowedPatches")) {
                    if (element.isJsonPrimitive()) {
                        String patchRef = element.getAsString();
                        if (!patchRef.isBlank()) {
                            allowedPatches.add(patchRef);
                        }
                    }
                }
                continentScope = "";
                sources.add(packagesPath);
            }
        }
        List<Cell> sourceCells = new ArrayList<>();
        for (JsonElement element : array(patchMap, "cells")) {
            JsonObject cell = element.getAsJsonObject();
            int x = intValue(cell, "gridX", 0);
            int z = intValue(cell, "gridZ", 0);
            if (owned != null && !owned.contains(key(x, z))) {
                continue;
            }
            String patchRef = stringValue(cell, "patchId", "");
            if (patchRef.isBlank()) {
                continue;
            }
            if (owned == null && allowedPatches != null && !allowedPatches.contains(patchRef)) {
                continue;
            }
            if (owned == null && allowedPatches == null && !continentScope.isBlank()
                    && !continentScope.equals(stringValue(cell, "continentId", ""))) {
                continue;
            }
            String terrainType = normalizeType(stringValue(cell, "landform", "unknown"));
            String baseLandform = normalizeType(stringValue(cell, "baseLandform", terrainType));
            double confidence = doubleValue(cell, "landformConfidence", 0.5);
            Cell normalized = new Cell(x, z, intValue(cell, "blockX", x * step),
                    intValue(cell, "blockZ", z * step), step, patchRef, terrainType, terrainType,
                    baseLandform, confidence, terrainEvidence.cells().get(key(x, z)));
            sourceCells.add(normalized);
        }
        Map<String, List<Cell>> byPatch = new LinkedHashMap<>();
        if (terrainPatchRunner == null) {
            for (Cell cell : sourceCells) {
                byPatch.computeIfAbsent(cell.patchRef(), ignored -> new ArrayList<>()).add(cell);
            }
        } else {
            String coarseSourceIdentity = sourceIdentity(scopeType, scopeId, sources);
            List<TerrainScalePatchService.SeedCell> seeds = sourceCells.stream()
                    .map(cell -> new TerrainScalePatchService.SeedCell(cell.x(), cell.z(), cell.blockX(),
                            cell.blockZ(), cell.step(), cell.patchRef()))
                    .toList();
            TerrainScalePatchService.Result refined = Objects.requireNonNull(
                    terrainPatchRunner.refine(runId, scopeType, scopeId, coarseSourceIdentity, seeds),
                    "Terrain patch runner returned null.");
            terrainPatchSource = TerrainPatchSource.from(refined.source());
            int refinedStep = refined.cellStepBlocks();
            if (refinedStep <= 0 || refined.patches().isEmpty()) {
                throw new IllegalArgumentException("PATCH_EXPLORER_T_SCALE_PATCH_EMPTY: " + scopeId);
            }
            for (TerrainScalePatchService.Patch patch : refined.patches()) {
                String type = normalizeType(patch.type());
                for (TerrainScalePatchService.PatchCell cell : patch.cells()) {
                    CoarseTerrain coarseTerrain = new CoarseTerrain(cell.elevation(), cell.water(),
                            cell.biomeId(), type, cell.biomeId(), 0, 0.0, 0.0,
                            cell.slope(), cell.localRelief());
                    byPatch.computeIfAbsent(patch.patchRef(), ignored -> new ArrayList<>())
                            .add(new Cell(cell.gridX(), cell.gridZ(), cell.blockX(), cell.blockZ(), refinedStep,
                                    patch.patchRef(), type, type, type, patch.confidence(), coarseTerrain));
                }
            }
            step = refinedStep;
        }
        List<Candidate> candidates = candidatesFromCells(byPatch, false, List.of());
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("PATCH_EXPLORER_SCOPE_EMPTY: " + scopeId);
        }
        String identity = sourceIdentity(scopeType, scopeId, sources);
        return new Scope(runId, scopeType, scopeId, step, candidates, allCells(candidates), identity,
                sources.stream().map(this::debugRef).toList(), List.of(), terrainEvidence.source(),
                terrainPatchSource);
    }

    private TerrainEvidenceBundle loadTerrainEvidence(String runId, String realmId, Path runDir,
            Path evidencePath, int expectedStep) throws IOException {
        JsonObject evidence = readObject(evidencePath);
        if (!RealmT4CoarseTerrainPreviewService.SCHEMA_VERSION.equals(
                stringValue(evidence, "schemaVersion", ""))) {
            throw new IllegalArgumentException("PATCH_EXPLORER_T4_TERRAIN_EVIDENCE_SCHEMA_UNSUPPORTED");
        }
        if (!runId.equals(stringValue(evidence, "runId", ""))
                || !realmId.equals(stringValue(evidence, "realmId", ""))) {
            throw new IllegalArgumentException("PATCH_EXPLORER_T4_TERRAIN_EVIDENCE_SCOPE_MISMATCH");
        }
        JsonObject grid = object(evidence, "grid");
        if (intValue(grid, "cellStepBlocks", 0) != expectedStep) {
            throw new IllegalArgumentException("PATCH_EXPLORER_T4_TERRAIN_EVIDENCE_STEP_MISMATCH");
        }
        Map<String, CoarseTerrain> cells = new HashMap<>();
        for (JsonElement element : array(evidence, "cells")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject cell = element.getAsJsonObject();
            int gridX = intValue(cell, "gridX", 0);
            int gridZ = intValue(cell, "gridZ", 0);
            cells.put(key(gridX, gridZ), CoarseTerrain.fromJson(cell));
        }
        JsonObject provider = object(evidence, "provider");
        String previewRef = "";
        if (evidence.has("artifacts") && evidence.get("artifacts").isJsonObject()) {
            String relative = stringValue(evidence.getAsJsonObject("artifacts"), "heightWaterPreview", "");
            if (!relative.isBlank()) {
                Path previewPath = runDir.resolve(relative).normalize();
                if (!previewPath.startsWith(runDir)) {
                    throw new IllegalArgumentException("PATCH_EXPLORER_T4_TERRAIN_PREVIEW_OUTSIDE_RUN");
                }
                requireFile(previewPath, "PATCH_EXPLORER_T4_TERRAIN_PREVIEW_MISSING");
                previewRef = debugRef(previewPath);
            }
        }
        CoarseTerrainSource source = new CoarseTerrainSource(
                stringValue(provider, "providerId", "unknown"),
                stringValue(provider, "sourceKind", "unknown"),
                booleanValue(provider, "fastPath", false),
                stringValue(provider, "fallbackReason", ""),
                stringValue(provider, "sourceFingerprint", "unknown"),
                stringValue(provider, "samplingSemantics", "unspecified"),
                debugRef(evidencePath), previewRef,
                booleanValue(evidence, "advisoryOnly", true),
                stringValue(evidence, "requiredNextGate", RealmT4CoarseTerrainPreviewService.REQUIRED_NEXT_GATE));
        return new TerrainEvidenceBundle(Map.copyOf(cells), source);
    }

    private Scope loadCityScope(String runId, String cityId, Path runDir) throws IOException {
        String safeCity = safeId(cityId, "citySeedId");
        CityTestRunLayout layout = CityTestRunLayout.open(runDir, safeCity);
        Path d3Path = layout.stepDirectory(CityTestRunLayout.D3)
                .resolve("city_landform_review_package.json");
        Path terrainPath = layout.stepDirectory(CityTestRunLayout.LAND_USE)
                .resolve("land_use_terrain_field.json");
        requireFile(d3Path, "PATCH_EXPLORER_D3_PACKAGE_MISSING");
        requireFile(terrainPath, "PATCH_EXPLORER_D3_TERRAIN_FIELD_MISSING");
        JsonObject review = readObject(d3Path);
        if (!"city_landform_review.v0.1".equals(stringValue(review, "schemaVersion", ""))) {
            throw new IllegalArgumentException("PATCH_EXPLORER_D3_SCHEMA_UNSUPPORTED");
        }
        if (!cityId.equals(stringValue(review, "cityId", cityId))) {
            throw new IllegalArgumentException("PATCH_EXPLORER_D3_CITY_MISMATCH");
        }
        LandUseTerrainField terrain = new LandUseTerrainFieldCodec().fromJson(readObject(terrainPath));
        if (!cityId.equals(terrain.cityId())) {
            throw new IllegalArgumentException("PATCH_EXPLORER_D3_TERRAIN_CITY_MISMATCH");
        }
        int step = terrain.cellStepBlocks();
        if (step <= 0 || step != intValue(object(review, "grid"), "cellStepBlocks", 0)) {
            throw new IllegalArgumentException("PATCH_EXPLORER_D3_CELL_STEP_INVALID");
        }
        List<Path> sources = new ArrayList<>(List.of(d3Path, terrainPath));
        List<Bounds> occupied = loadOccupied(runDir, safeCity, sources);
        Map<String, String> typeByPatch = new HashMap<>();
        Map<String, Double> confidenceByPatch = new HashMap<>();
        for (JsonElement element : array(review, "landformPatches")) {
            JsonObject patch = element.getAsJsonObject();
            String patchRef = requiredString(patch, "landformPatchId");
            typeByPatch.put(patchRef, normalizeType(stringValue(patch, "landformType", "unknown")));
            confidenceByPatch.put(patchRef,
                    containsString(array(patch, "landformTags"), "low_confidence") ? 0.4 : 0.8);
        }
        Map<String, List<Cell>> byPatch = new LinkedHashMap<>();
        List<Cell> scopeCells = new ArrayList<>();
        for (LandUseTerrainField.Cell terrainCell : terrain.cells()) {
            String patchRef = terrainCell.landformPatchId();
            String type = typeByPatch.getOrDefault(patchRef, normalizeType(terrainCell.landformType()));
            double confidence = confidenceByPatch.getOrDefault(patchRef, terrainCell.sampled() ? 0.8 : 0.4);
            CoarseTerrain coarseTerrain = new CoarseTerrain(terrainCell.elevation(), terrainCell.water(),
                    terrainCell.biomeId(), type, terrainCell.biomeId(), 0, 0.0, 0.0,
                    terrainCell.slope(), terrainCell.localRelief());
            Cell cell = new Cell(terrainCell.cellX(), terrainCell.cellZ(), terrainCell.blockMinX(),
                    terrainCell.blockMinZ(), step, patchRef, type, type, type, confidence, coarseTerrain);
            scopeCells.add(cell);
            if (!patchRef.isBlank()) {
                byPatch.computeIfAbsent(patchRef, ignored -> new ArrayList<>()).add(cell);
            }
        }
        List<Candidate> candidates = candidatesFromCells(byPatch, true, occupied);
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("PATCH_EXPLORER_SCOPE_EMPTY: " + cityId);
        }
        String identity = citySourceIdentity(cityId, d3Path, terrainPath, occupied);
        return new Scope(runId, "city_d4", cityId, step, candidates, List.copyOf(scopeCells), identity,
                sources.stream().map(this::debugRef).toList(), occupied, null, null);
    }

    private List<Bounds> loadOccupied(Path runDir, String cityId, List<Path> sources) throws IOException {
        List<Bounds> result = new ArrayList<>();
        List<Path> paths = occupiedArtifactPaths(runDir, cityId);
        for (int i = 0; i < paths.size(); i++) {
            Path path = paths.get(i);
            if (!Files.isRegularFile(path)) {
                continue;
            }
            sources.add(path);
            JsonObject artifact = readObject(path);
            if (i == 0) {
                collectDeclaredOccupiedEnvelopes(artifact, result);
            } else {
                collectOccupied(artifact, result);
            }
        }
        return result.stream().distinct().toList();
    }

    private static List<Path> occupiedArtifactPaths(Path runDir, String cityId) {
        CityTestRunLayout layout = CityTestRunLayout.open(runDir, cityId);
        return List.of(
                layout.stepDirectory(CityTestRunLayout.D4_CANDIDATE_SESSION)
                        .resolve("d4_candidate_session.json"),
                layout.stepDirectory(CityTestRunLayout.D4_ARRAY_LAYOUT)
                        .resolve("d4_array_occupied_field.json"),
                layout.stepDirectory(CityTestRunLayout.D4_DESIGN_LOOP)
                        .resolve("d4_design_loop_occupied_field.json"),
                layout.stepDirectory(CityTestRunLayout.D4).resolve("structure_anchor_map.json"));
    }

    private static void collectOccupied(JsonObject artifact, List<Bounds> target) {
        collectDeclaredOccupiedEnvelopes(artifact, target);
        collectNamedCollisionEnvelopes(artifact, target, "root");
    }

    private static void collectDeclaredOccupiedEnvelopes(JsonObject artifact, List<Bounds> target) {
        for (JsonElement element : array(artifact, "occupiedEnvelopes")) {
            JsonObject occupied = element.getAsJsonObject();
            JsonObject bounds = hasBounds(occupied) ? occupied : firstBoundsObject(occupied,
                    "collisionEnvelope", "estimatedCollisionEnvelope", "lockedCollisionEnvelope", "blockBounds");
            if (bounds != null) {
                target.add(bounds(bounds));
            }
        }
    }

    private static void collectNamedCollisionEnvelopes(JsonElement element, List<Bounds> target, String key) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectNamedCollisionEnvelopes(child, target, key);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        if (Set.of("collisionEnvelope", "estimatedCollisionEnvelope", "lockedCollisionEnvelope")
                .contains(key) && hasBounds(object)) {
            target.add(bounds(object));
            return;
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!entry.getKey().toLowerCase(Locale.ROOT).contains("safety")
                    && !entry.getKey().toLowerCase(Locale.ROOT).contains("mask")) {
                collectNamedCollisionEnvelopes(entry.getValue(), target, entry.getKey());
            }
        }
    }

    private static JsonObject firstBoundsObject(JsonObject object, String... keys) {
        for (String key : keys) {
            if (object.has(key) && object.get(key).isJsonObject() && hasBounds(object.getAsJsonObject(key))) {
                return object.getAsJsonObject(key);
            }
        }
        return null;
    }

    private static Bounds bounds(JsonObject object) {
        return new Bounds(intValue(object, "minX", 0), intValue(object, "minZ", 0),
                intValue(object, "maxX", 0), intValue(object, "maxZ", 0));
    }

    private static List<Candidate> candidatesFromCells(Map<String, List<Cell>> byPatch, boolean subtractOccupied,
                                                        List<Bounds> occupied) {
        List<RawCandidate> raw = new ArrayList<>();
        for (Map.Entry<String, List<Cell>> entry : byPatch.entrySet()) {
            List<List<Cell>> components = components(entry.getValue());
            components.sort(Comparator.<List<Cell>>comparingInt(List::size).reversed()
                    .thenComparingInt(cells -> bounds(cells).minZ()).thenComparingInt(cells -> bounds(cells).minX()));
            int availableComponentIndex = 0;
            for (int index = 0; index < components.size(); index++) {
                List<Cell> original = components.get(index);
                List<Cell> available = original;
                if (subtractOccupied) {
                    available = original.stream().filter(cell -> occupied.stream().noneMatch(bounds ->
                            bounds.intersects(cell.blockX(), cell.blockZ(), cell.blockX() + cell.step() - 1,
                                    cell.blockZ() + cell.step() - 1))).toList();
                }
                if (available.isEmpty()) {
                    continue;
                }
                Cell first = original.get(0);
                List<List<Cell>> availableComponents = components(available);
                availableComponents.sort(Comparator.<List<Cell>>comparingInt(List::size).reversed()
                        .thenComparingInt(cells -> bounds(cells).minZ())
                        .thenComparingInt(cells -> bounds(cells).minX()));
                for (List<Cell> availableComponent : availableComponents) {
                    raw.add(new RawCandidate(List.of(entry.getKey()), availableComponentIndex++, first.type(),
                            original, availableComponent));
                }
            }
        }
        return candidatesFromRaw(raw);
    }

    private static List<Candidate> candidatesFromRaw(List<RawCandidate> raw) {
        Map<String, List<RawCandidate>> byType = new HashMap<>();
        for (RawCandidate candidate : raw) {
            byType.computeIfAbsent(candidate.type(), ignored -> new ArrayList<>()).add(candidate);
        }
        List<Candidate> result = new ArrayList<>();
        for (Map.Entry<String, List<RawCandidate>> entry : byType.entrySet()) {
            entry.getValue().sort(Comparator.<RawCandidate>comparingLong(RawCandidate::largestContinuousArea).reversed()
                    .thenComparing(Comparator.comparingLong(RawCandidate::availableArea).reversed())
                    .thenComparing(RawCandidate::sourceKey).thenComparingInt(RawCandidate::componentIndex));
            for (int index = 0; index < entry.getValue().size(); index++) {
                RawCandidate rawCandidate = entry.getValue().get(index);
                String id = entry.getKey().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_")
                        + "-" + String.format(Locale.ROOT, "%02d", index + 1);
                double confidence = rawCandidate.available().stream().mapToDouble(Cell::confidence).average().orElse(0.5);
                result.add(new Candidate(id, rawCandidate.type(), rawCandidate.sourcePatchRefs(),
                        List.copyOf(rawCandidate.available()), rawCandidate.availableArea(),
                        rawCandidate.largestContinuousArea(), rawCandidate.originalArea(),
                        bounds(rawCandidate.available()), confidence));
            }
        }
        result.sort(CANDIDATE_ORDER);
        return List.copyOf(result);
    }

    private static JsonArray typeCatalog(Scope scope) {
        Map<String, List<Candidate>> ranked = rankByType(scope.candidates());
        JsonArray result = new JsonArray();
        for (Map.Entry<String, List<Candidate>> entry : ranked.entrySet()) {
            List<Candidate> candidates = entry.getValue();
            JsonObject item = new JsonObject();
            item.addProperty("patchType", entry.getKey());
            item.addProperty("displayName", displayName(entry.getKey()));
            item.addProperty("color", LandformPatchPalette.hex(entry.getKey()));
            item.addProperty("candidateBasis", candidateBasis(scope));
            item.addProperty("patchCount", candidates.size());
            item.addProperty("totalAreaBlocks", candidates.stream().mapToLong(Candidate::areaBlocks).sum());
            item.addProperty("maxAreaBlocks", candidates.stream().mapToLong(Candidate::areaBlocks).max().orElse(0));
            item.addProperty("medianAreaBlocks", medianArea(candidates));
            item.addProperty("supportsCoarseAnchor", candidates.stream().anyMatch(candidate ->
                    candidate.cells().size() >= 4));
            item.addProperty("factSource", "city_d4".equals(scope.scopeType())
                    ? "CityLandformReviewPackage.landformPatches"
                    : "T-scale terrain resample + landform classification + patch merge");
            item.addProperty("meanConfidence", candidates.stream().mapToDouble(Candidate::confidence).average().orElse(0.0));
            List<Cell> catalogCells = candidates.stream().flatMap(candidate -> candidate.cells().stream()).toList();
            item.add("terrainComposition", composition(catalogCells, Cell::terrainType));
            item.add("baseLandformComposition", composition(catalogCells, Cell::baseLandform));
            JsonObject coarseTerrainEvidence = coarseTerrainEvidence(catalogCells, scope.coarseTerrainSource());
            if (coarseTerrainEvidence != null) {
                item.add("coarseTerrainEvidence", coarseTerrainEvidence);
            }
            if ("city_d4".equals(scope.scopeType())) {
                item.addProperty("availableAreaBlocks", candidates.stream().mapToLong(Candidate::areaBlocks).sum());
                item.addProperty("originalAreaBlocks", candidates.stream().mapToLong(Candidate::originalAreaBlocks).sum());
            }
            result.add(item);
        }
        return result;
    }

    private static JsonObject patchTypePalette() {
        JsonObject palette = new JsonObject();
        palette.addProperty("schemaVersion", LandformPatchPalette.SCHEMA_VERSION);
        JsonObject colors = new JsonObject();
        LandformPatchPalette.colorsByType().forEach(colors::addProperty);
        palette.add("colors", colors);
        return palette;
    }

    private static Map<String, List<Candidate>> rankByType(List<Candidate> candidates) {
        Map<String, List<Candidate>> result = new LinkedHashMap<>();
        candidates.stream().map(Candidate::type).distinct().sorted().forEach(type -> result.put(type,
                candidates.stream().filter(candidate -> candidate.type().equals(type)).sorted(CANDIDATE_ORDER).toList()));
        return result;
    }

    private static JsonArray candidateJson(List<Candidate> candidates, Scope scope) {
        JsonArray result = new JsonArray();
        for (Candidate candidate : candidates) {
            JsonObject item = new JsonObject();
            item.addProperty("candidateId", candidate.candidateId());
            item.addProperty("patchType", candidate.type());
            item.addProperty("displayName", displayName(candidate.type()));
            item.addProperty("candidateBasis", candidateBasis(scope));
            item.addProperty("areaBlocks", candidate.areaBlocks());
            item.addProperty("largestContinuousAreaBlocks", candidate.largestContinuousAreaBlocks());
            item.addProperty("originalAreaBlocks", candidate.originalAreaBlocks());
            item.addProperty("cellCount", candidate.cells().size());
            item.addProperty("hardLegal", candidate.areaBlocks() > 0);
            item.addProperty("confidence", candidate.confidence());
            item.add("sourcePatchRefs", strings(candidate.sourcePatchRefs()));
            item.add("bounds", boundsJson(candidate.bounds(), scope.cellStepBlocks()));
            item.add("center", centerJson(candidate.cells()));
            item.add("suggestedAnchor", anchorJson(suggestedAnchor(candidate.cells(), scope)));
            item.add("terrainMetrics", metrics(candidate.cells()));
            item.add("terrainComposition", composition(candidate.cells(), Cell::terrainType));
            item.add("baseLandformComposition", composition(candidate.cells(), Cell::baseLandform));
            JsonObject coarseTerrainEvidence = coarseTerrainEvidence(candidate.cells(), scope.coarseTerrainSource());
            if (coarseTerrainEvidence != null) {
                item.add("coarseTerrainEvidence", coarseTerrainEvidence);
            }
            result.add(item);
        }
        return result;
    }

    private PatchCandidateTerrainPreviewService.Result createTerrainPreview(Scope scope, Candidate candidate,
            PatchCandidateTerrainPreviewService.Level level, TerrainPreviewRunner runner)
            throws IOException {
        if (runner == null) {
            return null;
        }
        Cell anchor = suggestedAnchor(candidate.cells(), scope);
        List<PatchCandidateTerrainPreviewService.CoarseCell> memberCells = candidate.cells().stream()
                .map(cell -> new PatchCandidateTerrainPreviewService.CoarseCell(cell.x(), cell.z()))
                .toList();
        PatchCandidateTerrainPreviewService.Target target =
                new PatchCandidateTerrainPreviewService.Target(scope.scopeType(), candidate.candidateId(),
                        anchor.blockX() + anchor.step() / 2, anchor.blockZ() + anchor.step() / 2,
                        scope.cellStepBlocks(), memberCells);
        PatchCandidateTerrainPreviewService.Result result = Objects.requireNonNull(
                runner.refine(scope.runId(), scope.scopeId(), scope.sourceIdentity(), target, level),
                "Terrain preview runner returned null.");
        if (scope.terrainPatchSource() != null) {
            JsonObject provider = object(result.evidence(), "provider");
            TerrainPatchSource source = scope.terrainPatchSource();
            if (!source.providerId().equals(stringValue(provider, "providerId", ""))
                    || !source.sourceKind().equals(stringValue(provider, "sourceKind", ""))
                    || !source.sourceFingerprint().equals(stringValue(provider, "sourceFingerprint", ""))
                    || !source.samplingSemantics().equals(stringValue(provider, "samplingSemantics", ""))) {
                throw new IllegalArgumentException("PATCH_EXPLORER_T_SCALE_PATCH_PROVIDER_CHANGED_REOPEN_SESSION");
            }
        }
        if (scope.coarseTerrainSource() != null) {
            JsonObject provider = object(result.evidence(), "provider");
            CoarseTerrainSource coarse = scope.coarseTerrainSource();
            if (!coarse.providerId().equals(stringValue(provider, "providerId", ""))
                    || !coarse.sourceKind().equals(stringValue(provider, "sourceKind", ""))
                    || !coarse.sourceFingerprint().equals(stringValue(provider, "sourceFingerprint", ""))
                    || !coarse.samplingSemantics().equals(stringValue(provider, "samplingSemantics", ""))) {
                throw new IllegalArgumentException("PATCH_EXPLORER_TERRAIN_PREVIEW_PROVIDER_CHANGED_REOPEN_SESSION");
            }
        }
        return result;
    }

    private JsonObject terrainPreviewJson(PatchCandidateTerrainPreviewService.Result result) {
        JsonObject evidence = result.evidence();
        JsonObject terrainPreview = new JsonObject();
        terrainPreview.addProperty("schemaVersion", PatchCandidateTerrainPreviewService.SCHEMA_VERSION);
        terrainPreview.addProperty("sourceIdentity", result.sourceIdentity());
        terrainPreview.addProperty("evidenceIdentity", result.evidenceIdentity());
        terrainPreview.addProperty("evaluationLevel", requiredString(evidence, "evaluationLevel"));
        terrainPreview.addProperty("cacheHit", result.cacheHit());
        terrainPreview.add("provider", object(evidence, "provider").deepCopy());
        terrainPreview.add("grid", object(evidence, "grid").deepCopy());
        terrainPreview.add("buildabilityPolicy", object(evidence, "buildabilityPolicy").deepCopy());
        terrainPreview.add("summary", object(evidence, "summary").deepCopy());
        terrainPreview.addProperty("advisoryOnly", booleanValue(evidence, "advisoryOnly", true));
        terrainPreview.addProperty("requiredNextGate", stringValue(evidence, "requiredNextGate",
                PatchCandidateTerrainPreviewService.REQUIRED_NEXT_GATE));
        terrainPreview.add("artifacts", terrainPreviewRefs(result));
        return terrainPreview;
    }

    private JsonObject terrainPreviewRefs(PatchCandidateTerrainPreviewService.Result result) {
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("evidence", debugRef(result.evidencePath()));
        artifacts.addProperty("terrainPreview", debugRef(result.terrainPreviewPath()));
        return artifacts;
    }

    private String validateFrozenTerrainPreview(JsonObject terrainPreview) throws IOException {
        if (!PatchCandidateTerrainPreviewService.SCHEMA_VERSION.equals(
                requiredString(terrainPreview, "schemaVersion"))) {
            throw new IllegalArgumentException("PATCH_SELECTION_TERRAIN_PREVIEW_SCHEMA_UNSUPPORTED");
        }
        JsonObject artifacts = object(terrainPreview, "artifacts");
        Path evidencePath = debugRoot.resolve(requiredString(artifacts, "evidence"))
                .toAbsolutePath().normalize();
        if (!evidencePath.startsWith(debugRoot)) {
            throw new IllegalArgumentException("PATCH_SELECTION_TERRAIN_PREVIEW_OUTSIDE_DEBUG_ROOT");
        }
        requireFile(evidencePath, "PATCH_SELECTION_TERRAIN_PREVIEW_MISSING");
        String identity = "sha256:" + sha256(evidencePath);
        if (!identity.equals(requiredString(terrainPreview, "evidenceIdentity"))) {
            throw new IllegalArgumentException("PATCH_SELECTION_TERRAIN_PREVIEW_TAMPERED");
        }
        JsonObject evidence = readObject(evidencePath);
        if (!PatchCandidateTerrainPreviewService.SCHEMA_VERSION.equals(
                stringValue(evidence, "schemaVersion", ""))
                || !requiredString(terrainPreview, "sourceIdentity")
                .equals(stringValue(evidence, "sourceIdentity", ""))) {
            throw new IllegalArgumentException("PATCH_SELECTION_TERRAIN_PREVIEW_CONTENT_MISMATCH");
        }
        return requiredString(terrainPreview, "sourceIdentity");
    }

    private static String selectionRef(String sessionId, String candidateId, String scopeIdentity,
            String terrainPreviewSourceIdentity) {
        String source = sessionId + "\n" + candidateId + "\n" + scopeIdentity;
        if (terrainPreviewSourceIdentity != null && !terrainPreviewSourceIdentity.isBlank()) {
            source += "\n" + terrainPreviewSourceIdentity;
        }
        return "psel_" + sha256(source);
    }

    private static JsonArray sparseRelations(List<Candidate> candidates, int step) {
        JsonArray result = new JsonArray();
        Set<String> emitted = new HashSet<>();
        Map<String, Geometry> geometryCache = new HashMap<>();
        for (Candidate source : candidates) {
            Map<String, Candidate> nearestByType = new LinkedHashMap<>();
            for (Candidate other : candidates) {
                if (source == other || source.type().equals(other.type())) {
                    continue;
                }
                Candidate current = nearestByType.get(other.type());
                if (current == null || geometry(source, other, step, geometryCache).boundaryDistanceCells()
                        < geometry(source, current, step, geometryCache).boundaryDistanceCells()
                        || geometry(source, other, step, geometryCache).boundaryDistanceCells()
                        == geometry(source, current, step, geometryCache).boundaryDistanceCells()
                        && other.candidateId().compareTo(current.candidateId()) < 0) {
                    nearestByType.put(other.type(), other);
                }
            }
            Set<Candidate> related = new LinkedHashSet<>(nearestByType.values());
            for (Candidate other : candidates) {
                if (source != other && geometry(source, other, step, geometryCache).adjacent()) {
                    related.add(other);
                }
            }
            for (Candidate other : related) {
                String pair = source.candidateId().compareTo(other.candidateId()) < 0
                        ? source.candidateId() + "|" + other.candidateId()
                        : other.candidateId() + "|" + source.candidateId();
                if (emitted.add(pair)) {
                    result.add(relationJson(source, other, step));
                }
            }
        }
        return result;
    }

    private static Geometry geometry(Candidate left, Candidate right, int step, Map<String, Geometry> cache) {
        String cacheKey = left.candidateId() + ">" + right.candidateId();
        return cache.computeIfAbsent(cacheKey, ignored -> geometry(left, right, step));
    }

    private static JsonObject relationJson(Candidate left, Candidate right, int step) {
        Geometry geometry = geometry(left, right, step);
        JsonObject item = new JsonObject();
        item.addProperty("candidateId", left.candidateId());
        item.addProperty("otherCandidateId", right.candidateId());
        item.addProperty("adjacent", geometry.adjacent());
        item.addProperty("intersects", geometry.intersects());
        item.addProperty("contains", contains(left.cells(), right.cells()));
        item.addProperty("containedBy", contains(right.cells(), left.cells()));
        item.addProperty("separated", !geometry.adjacent() && !geometry.intersects());
        item.addProperty("boundaryDistanceCells", geometry.boundaryDistanceCells());
        item.addProperty("boundaryDistanceBlocks", geometry.boundaryDistanceCells() * step);
        item.addProperty("centerDistanceCells", geometry.centerDistanceCells());
        item.addProperty("centerDistanceBlocks", geometry.centerDistanceCells() * step);
        item.addProperty("direction", geometry.direction());
        item.addProperty("sharedBoundaryCells", geometry.sharedBoundaryCells());
        item.addProperty("sharedBoundaryBlocks", geometry.sharedBoundaryCells() * step);
        item.addProperty("contactRatio", geometry.contactRatio());
        JsonObject contact = new JsonObject();
        contact.addProperty("gridX", geometry.contactX());
        contact.addProperty("gridZ", geometry.contactZ());
        contact.addProperty("blockX", geometry.contactX() * step);
        contact.addProperty("blockZ", geometry.contactZ() * step);
        item.add("closestContactPoint", contact);
        return item;
    }

    private static Geometry geometry(Candidate left, Candidate right, int step) {
        Set<String> rightKeys = new HashSet<>();
        for (Cell cell : right.cells()) {
            rightKeys.add(key(cell.x(), cell.z()));
        }
        int shared = 0;
        int best = Integer.MAX_VALUE;
        Cell bestLeft = left.cells().get(0);
        Cell bestRight = right.cells().get(0);
        boolean intersects = false;
        for (Cell a : left.cells()) {
            if (rightKeys.contains(key(a.x(), a.z()))) {
                intersects = true;
            }
            for (int[] direction : CARDINAL) {
                if (rightKeys.contains(key(a.x() + direction[0], a.z() + direction[1]))) {
                    shared++;
                }
            }
            for (Cell b : right.cells()) {
                int distance = Math.max(0, Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z()) - 1);
                if (distance < best) {
                    best = distance;
                    bestLeft = a;
                    bestRight = b;
                }
            }
        }
        double[] lc = center(left.cells());
        double[] rc = center(right.cells());
        double dx = rc[0] - lc[0];
        double dz = rc[1] - lc[1];
        String direction = direction(dx, dz);
        int perimeter = perimeter(left.cells());
        return new Geometry(shared > 0, intersects, best, Math.hypot(dx, dz), direction, shared,
                perimeter == 0 ? 0.0 : shared / (double) perimeter,
                (bestLeft.x() + bestRight.x()) / 2, (bestLeft.z() + bestRight.z()) / 2);
    }

    private void renderOverview(Scope scope, List<Candidate> candidates, OverviewMode mode, Path output)
            throws IOException {
        Bounds bounds = bounds(scope.scopeCells());
        int widthCells = Math.max(1, bounds.maxX() - bounds.minX() + 1);
        int heightCells = Math.max(1, bounds.maxZ() - bounds.minZ() + 1);
        int scale = Math.max(2, Math.min(14, 920 / Math.max(widthCells, heightCells)));
        int mapWidth = widthCells * scale;
        int mapHeight = heightCells * scale;
        int legendWidth = 290;
        BufferedImage image = new BufferedImage(mapWidth + legendWidth,
                Math.max(390, mapHeight), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(242, 244, 243));
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            Map<String, Cell> terrainByCell = new HashMap<>();
            for (Cell cell : scope.scopeCells()) {
                terrainByCell.put(key(cell.x(), cell.z()), cell);
            }
            for (Cell cell : scope.scopeCells()) {
                g.setColor(terrainColor(cell, terrainByCell));
                g.fillRect((cell.x() - bounds.minX()) * scale, (cell.z() - bounds.minZ()) * scale, scale, scale);
            }

            Map<String, OverlayCell> overlays = new LinkedHashMap<>();
            if (mode == OverviewMode.ALL_PATCHES) {
                for (Cell cell : scope.scopeCells()) {
                    if (!cell.patchRef().isBlank()) {
                        overlays.put(key(cell.x(), cell.z()),
                                new OverlayCell(cell, cell.patchRef(), cell.type()));
                    }
                }
            } else if (mode == OverviewMode.TOP_PATCHES) {
                for (Candidate candidate : candidates) {
                    for (Cell cell : candidate.cells()) {
                        overlays.put(key(cell.x(), cell.z()),
                                new OverlayCell(cell, candidate.candidateId(), candidate.type()));
                    }
                }
            }
            int overlayAlpha = mode == OverviewMode.TOP_PATCHES ? 170 : 118;
            for (OverlayCell overlay : overlays.values()) {
                Color base = LandformPatchPalette.color(overlay.type());
                g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), overlayAlpha));
                Cell cell = overlay.cell();
                g.fillRect((cell.x() - bounds.minX()) * scale, (cell.z() - bounds.minZ()) * scale,
                        scale, scale);
            }
            if (!overlays.isEmpty()) {
                g.setStroke(new BasicStroke(scale >= 6 ? 1.5f : 1.0f));
                for (OverlayCell overlay : overlays.values()) {
                    drawOverlayBoundary(g, overlay, overlays, bounds, scale,
                            mode == OverviewMode.TOP_PATCHES);
                }
            }
            if (mode == OverviewMode.TOP_PATCHES) {
                drawCandidateLabels(g, candidates, bounds, scale, mapWidth, mapHeight);
            }

            g.setColor(new Color(33, 38, 40, 185));
            g.setStroke(new BasicStroke(1.5f));
            g.drawRect(0, 0, mapWidth - 1, mapHeight - 1);
            int x = mapWidth + 18;
            int y = 30;
            g.setColor(new Color(24, 28, 34));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 17));
            g.drawString(mode.title(), x, y);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            y += 22;
            g.drawString(scope.scopeType() + " / " + scope.scopeId(), x, y);
            y += 18;
            g.drawString("cell step: " + scope.cellStepBlocks() + " blocks", x, y);
            y += 18;
            g.drawString(widthCells + " x " + heightCells + " cells", x, y);
            y += 25;
            Map<String, Long> counts = overviewCounts(scope, candidates, mode);
            for (Map.Entry<String, String> entry : LandformPatchPalette.colorsByType().entrySet()) {
                Color color = LandformPatchPalette.color(entry.getKey());
                g.setColor(color);
                g.fillRect(x, y - 11, 14, 14);
                g.setColor(new Color(24, 28, 34));
                String count = counts.containsKey(entry.getKey()) ? "  " + counts.get(entry.getKey()) : "";
                g.drawString(entry.getKey() + "  " + entry.getValue() + count, x + 21, y + 1);
                y += 22;
            }
            y += 10;
            g.setColor(new Color(75, 79, 82));
            g.drawString("same bounds / fixed patch colors", x, y);
            if ("city_d4".equals(scope.scopeType())) {
                y += 18;
                g.drawString("Top area excludes hard occupied", x, y);
            }
        } finally {
            g.dispose();
        }
        Files.createDirectories(output.getParent());
        ImageIO.write(image, "png", output.toFile());
    }

    private static void drawCandidateLabels(Graphics2D g, List<Candidate> candidates, Bounds bounds,
            int scale, int mapWidth, int mapHeight) {
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(10, Math.min(15, scale + 5))));
        List<Rectangle> occupied = new ArrayList<>();
        List<Candidate> ordered = candidates.stream()
                .sorted(Comparator.comparingLong(Candidate::areaBlocks).reversed()
                        .thenComparing(Candidate::candidateId))
                .toList();
        for (Candidate candidate : ordered) {
            double[] center = center(candidate.cells());
            int anchorX = (int) Math.round((center[0] - bounds.minX() + 0.5) * scale);
            int anchorZ = (int) Math.round((center[1] - bounds.minZ() + 0.5) * scale);
            String label = candidate.candidateId();
            int textWidth = g.getFontMetrics().stringWidth(label);
            int textHeight = g.getFontMetrics().getHeight();
            Rectangle labelBounds = findLabelBounds(anchorX, anchorZ, textWidth + 6, textHeight + 1,
                    mapWidth, mapHeight, occupied);
            int labelCenterX = labelBounds.x + labelBounds.width / 2;
            int labelCenterZ = labelBounds.y + labelBounds.height / 2;
            if (Math.abs(labelCenterX - anchorX) + Math.abs(labelCenterZ - anchorZ) > textHeight) {
                g.setColor(new Color(245, 245, 242, 190));
                g.setStroke(new BasicStroke(1.0f));
                g.drawLine(anchorX, anchorZ, labelCenterX, labelCenterZ);
            }
            g.setColor(new Color(20, 23, 25, 195));
            g.fillRect(labelBounds.x, labelBounds.y, labelBounds.width, labelBounds.height);
            g.setColor(Color.WHITE);
            g.drawString(label, labelBounds.x + 3,
                    labelBounds.y + (labelBounds.height - textHeight) / 2 + g.getFontMetrics().getAscent());
            occupied.add(new Rectangle(labelBounds.x - 2, labelBounds.y - 2,
                    labelBounds.width + 4, labelBounds.height + 4));
        }
    }

    private static Rectangle findLabelBounds(int anchorX, int anchorZ, int width, int height,
            int mapWidth, int mapHeight, List<Rectangle> occupied) {
        int vertical = height + 5;
        int horizontal = width / 2 + 8;
        int[][] offsets = {
                {0, 0}, {0, -vertical}, {0, vertical},
                {-horizontal, 0}, {horizontal, 0},
                {-horizontal, -vertical}, {horizontal, -vertical},
                {-horizontal, vertical}, {horizontal, vertical},
                {0, -vertical * 2}, {0, vertical * 2}
        };
        Rectangle best = null;
        long bestOverlap = Long.MAX_VALUE;
        for (int[] offset : offsets) {
            int x = clamp(anchorX - width / 2 + offset[0], 2, Math.max(2, mapWidth - width - 2));
            int y = clamp(anchorZ - height + 3 + offset[1], 2, Math.max(2, mapHeight - height - 2));
            Rectangle candidate = new Rectangle(x, y, width, height);
            long overlap = occupied.stream().map(candidate::intersection)
                    .filter(intersection -> !intersection.isEmpty())
                    .mapToLong(intersection -> (long) intersection.width * intersection.height).sum();
            if (overlap == 0) {
                return candidate;
            }
            if (overlap < bestOverlap) {
                best = candidate;
                bestOverlap = overlap;
            }
        }
        return best == null ? new Rectangle(2, 2, width, height) : best;
    }

    private static Color terrainColor(Cell cell, Map<String, Cell> terrainByCell) {
        CoarseTerrain terrain = cell.coarseTerrain();
        if (terrain == null) {
            return new Color(211, 215, 214);
        }
        if (terrain.water()) {
            return new Color(78, 139, 181);
        }
        Color base = elevationColor(terrain.elevation());
        double west = elevation(terrainByCell.get(key(cell.x() - 1, cell.z())), terrain.elevation());
        double east = elevation(terrainByCell.get(key(cell.x() + 1, cell.z())), terrain.elevation());
        double north = elevation(terrainByCell.get(key(cell.x(), cell.z() - 1)), terrain.elevation());
        double south = elevation(terrainByCell.get(key(cell.x(), cell.z() + 1)), terrain.elevation());
        double shade = Math.max(0.72, Math.min(1.25, 1.0 + ((west - east) + (north - south)) * 0.018));
        return new Color(scaleChannel(base.getRed(), shade), scaleChannel(base.getGreen(), shade),
                scaleChannel(base.getBlue(), shade));
    }

    private static double elevation(Cell cell, double fallback) {
        return cell == null || cell.coarseTerrain() == null ? fallback : cell.coarseTerrain().elevation();
    }

    private static Color elevationColor(double elevation) {
        if (elevation <= HEIGHT_STOPS[0]) {
            return HEIGHT_COLORS[0];
        }
        for (int index = 1; index < HEIGHT_STOPS.length; index++) {
            if (elevation <= HEIGHT_STOPS[index]) {
                double ratio = (elevation - HEIGHT_STOPS[index - 1])
                        / (HEIGHT_STOPS[index] - HEIGHT_STOPS[index - 1]);
                Color left = HEIGHT_COLORS[index - 1];
                Color right = HEIGHT_COLORS[index];
                return new Color(interpolate(left.getRed(), right.getRed(), ratio),
                        interpolate(left.getGreen(), right.getGreen(), ratio),
                        interpolate(left.getBlue(), right.getBlue(), ratio));
            }
        }
        return HEIGHT_COLORS[HEIGHT_COLORS.length - 1];
    }

    private static int interpolate(int left, int right, double ratio) {
        return (int) Math.round(left + (right - left) * ratio);
    }

    private static int scaleChannel(int channel, double scale) {
        return Math.max(0, Math.min(255, (int) Math.round(channel * scale)));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void drawOverlayBoundary(Graphics2D g, OverlayCell overlay,
            Map<String, OverlayCell> overlays, Bounds bounds, int scale, boolean typeColor) {
        Cell cell = overlay.cell();
        int x = (cell.x() - bounds.minX()) * scale;
        int z = (cell.z() - bounds.minZ()) * scale;
        Color base = LandformPatchPalette.color(overlay.type());
        g.setColor(typeColor ? base.darker() : new Color(30, 34, 36, 175));
        for (int direction = 0; direction < CARDINAL.length; direction++) {
            int[] delta = CARDINAL[direction];
            OverlayCell neighbor = overlays.get(key(cell.x() + delta[0], cell.z() + delta[1]));
            if (neighbor != null && overlay.regionId().equals(neighbor.regionId())) {
                continue;
            }
            switch (direction) {
                case 0 -> g.drawLine(x + scale, z, x + scale, z + scale);
                case 1 -> g.drawLine(x, z, x, z + scale);
                case 2 -> g.drawLine(x, z + scale, x + scale, z + scale);
                default -> g.drawLine(x, z, x + scale, z);
            }
        }
    }

    private static Map<String, Long> overviewCounts(Scope scope, List<Candidate> candidates, OverviewMode mode) {
        Map<String, Long> result = new LinkedHashMap<>();
        if (mode == OverviewMode.TOP_PATCHES) {
            candidates.stream().collect(java.util.stream.Collectors.groupingBy(Candidate::type,
                    LinkedHashMap::new, java.util.stream.Collectors.counting())).forEach(result::put);
        } else if (mode == OverviewMode.ALL_PATCHES) {
            Map<String, Set<String>> refs = new LinkedHashMap<>();
            for (Cell cell : scope.scopeCells()) {
                if (!cell.patchRef().isBlank()) {
                    refs.computeIfAbsent(cell.type(), ignored -> new LinkedHashSet<>()).add(cell.patchRef());
                }
            }
            refs.forEach((type, patches) -> result.put(type, (long) patches.size()));
        }
        return result;
    }

    private JsonObject loadSession(String runId, String sessionId) throws IOException {
        Path path = sessionDir(runId, sessionId).resolve("patch_explorer_session.json");
        requireFile(path, "PATCH_EXPLORER_SESSION_NOT_FOUND");
        JsonObject session = readObject(path);
        if (!SESSION_SCHEMA.equals(stringValue(session, "schemaVersion", ""))
                || !CANDIDATE_MODEL.equals(stringValue(session, "candidateModel", ""))) {
            throw new IllegalArgumentException("PATCH_EXPLORER_SESSION_SCHEMA_UNSUPPORTED");
        }
        if (!runId.equals(requiredString(session, "runId")) || !sessionId.equals(requiredString(session, "sessionId"))) {
            throw new IllegalArgumentException("PATCH_EXPLORER_SESSION_IDENTITY_MISMATCH");
        }
        return session;
    }

    private Path runDir(String runId) {
        Path path = debugRoot.resolve(safeId(runId, "runId")).normalize();
        if (!path.startsWith(debugRoot)) {
            throw new IllegalArgumentException("PATCH_EXPLORER_PATH_OUTSIDE_DEBUG_ROOT");
        }
        return path;
    }

    private Path sessionDir(String runId, String sessionId) {
        return runDir(runId).resolve("patch_explorer_" + safeId(sessionId, "sessionId")).normalize();
    }

    private String sourceIdentity(String scopeType, String scopeId, List<Path> paths) throws IOException {
        StringBuilder source = new StringBuilder(CANDIDATE_MODEL).append('\n')
                .append(scopeType).append('\n').append(scopeId).append('\n');
        for (Path path : paths.stream().map(Path::toAbsolutePath).map(Path::normalize).sorted().toList()) {
            if (!path.startsWith(debugRoot)) {
                throw new IllegalArgumentException("PATCH_EXPLORER_SOURCE_OUTSIDE_DEBUG_ROOT: " + path);
            }
            source.append(debugRef(path)).append(':').append(sha256(path)).append('\n');
        }
        return "sha256:" + sha256(source.toString());
    }

    private String citySourceIdentity(String cityId, Path d3Path, Path terrainPath,
            List<Bounds> occupied) throws IOException {
        Path normalizedD3 = d3Path.toAbsolutePath().normalize();
        Path normalizedTerrain = terrainPath.toAbsolutePath().normalize();
        if (!normalizedD3.startsWith(debugRoot) || !normalizedTerrain.startsWith(debugRoot)) {
            throw new IllegalArgumentException("PATCH_EXPLORER_SOURCE_OUTSIDE_DEBUG_ROOT: " + d3Path);
        }
        StringBuilder source = new StringBuilder(CANDIDATE_MODEL).append('\n')
                .append("city_d4").append('\n').append(cityId).append('\n')
                .append(debugRef(normalizedD3)).append(':').append(sha256(normalizedD3)).append('\n')
                .append(debugRef(normalizedTerrain)).append(':').append(sha256(normalizedTerrain)).append('\n');
        occupied.stream().distinct()
                .sorted(Comparator.comparingInt(Bounds::minX).thenComparingInt(Bounds::minZ)
                        .thenComparingInt(Bounds::maxX).thenComparingInt(Bounds::maxZ))
                .forEach(bounds -> source.append("occupied:")
                        .append(bounds.minX()).append(',').append(bounds.minZ()).append(',')
                        .append(bounds.maxX()).append(',').append(bounds.maxZ()).append('\n'));
        return "sha256:" + sha256(source.toString());
    }

    private String debugRef(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(debugRoot)) {
            throw new IllegalArgumentException("PATCH_EXPLORER_ARTIFACT_OUTSIDE_DEBUG_ROOT: " + path);
        }
        return debugRoot.relativize(normalized).toString().replace('\\', '/');
    }

    private static String scopeId(JsonObject request, String scopeType) {
        String value = stringValue(request, "scopeId", "");
        if (value.isBlank() && "realm_t4".equals(scopeType)) {
            value = stringValue(request, "realmId", "");
        }
        if (value.isBlank() && "city_d4".equals(scopeType)) {
            value = stringValue(request, "citySeedId", "");
        }
        if (value.isBlank() && "realm_t2".equals(scopeType)) {
            value = stringValue(request, "realmId", stringValue(request, "continentId", ""));
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException("PATCH_EXPLORER_SCOPE_ID_REQUIRED: " + scopeType);
        }
        return safeId(value, "scopeId");
    }

    private static JsonObject candidatePackageForRealm(Path packagesPath, String realmId) throws IOException {
        if (!Files.isRegularFile(packagesPath)) {
            return null;
        }
        JsonElement root = JsonParser.parseString(Files.readString(packagesPath));
        if (!root.isJsonArray()) {
            return null;
        }
        for (JsonElement element : root.getAsJsonArray()) {
            JsonObject object = element.getAsJsonObject();
            if (realmId.equals(stringValue(object, "realmId", ""))) {
                return object;
            }
        }
        return null;
    }

    private static String continentForRealm(Path profilesPath, String realmId) throws IOException {
        if (!Files.isRegularFile(profilesPath)) {
            return "";
        }
        JsonElement root = JsonParser.parseString(Files.readString(profilesPath));
        if (!root.isJsonArray()) {
            return "";
        }
        for (JsonElement element : root.getAsJsonArray()) {
            JsonObject object = element.getAsJsonObject();
            if (realmId.equals(stringValue(object, "realmId", ""))) {
                return stringValue(object, "targetContinentId", stringValue(object, "continentId", ""));
            }
        }
        return "";
    }

    private JsonObject artifactRefs(Path sessionDir, Path pagePath, Path previewPath,
            CoarseTerrainSource coarseTerrainSource) {
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("explorationSession", debugRef(sessionDir.resolve("patch_explorer_session.json")));
        if (pagePath != null) {
            artifacts.addProperty("candidatePage", debugRef(pagePath));
        }
        if (previewPath != null) {
            artifacts.addProperty("candidatePreview", debugRef(previewPath));
        }
        addHeightWaterPreview(artifacts, coarseTerrainSource);
        return artifacts;
    }

    private static void addHeightWaterPreview(JsonObject artifacts, CoarseTerrainSource source) {
        if (source != null && !source.heightWaterPreview().isBlank()) {
            artifacts.addProperty("heightWaterPreview", source.heightWaterPreview());
        }
    }

    private static JsonObject base(String operation, String runId, String sessionId) {
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.addProperty("schemaVersion", "patch_explorer_response.v0.1");
        response.addProperty("operation", operation);
        response.addProperty("runId", runId);
        response.addProperty("sessionId", sessionId);
        response.addProperty("status", "completed");
        return response;
    }

    private static List<String> requiredTypes(JsonObject request) {
        JsonArray values = array(request, "interestTypes");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("PATCH_EXPLORER_INTEREST_TYPES_REQUIRED");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (JsonElement element : values) {
            unique.add(normalizeType(element.getAsString()));
        }
        return List.copyOf(unique);
    }

    private static int pageIndex(JsonObject request, int pageSize, String sourceIdentity,
                                 List<String> interestTypes) {
        String token = stringValue(request, "pageToken", "");
        if (!token.isBlank()) {
            try {
                String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
                String[] parts = decoded.split(":", 4);
                if (parts.length != 4 || Integer.parseInt(parts[1]) != pageSize
                        || !parts[2].equals(shortHash(sourceIdentity))
                        || !parts[3].equals(shortHash(String.join(",", interestTypes)))) {
                    throw new IllegalArgumentException("token binding mismatch");
                }
                return Integer.parseInt(parts[0]);
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException("PATCH_EXPLORER_PAGE_TOKEN_INVALID");
            }
        }
        int page = intValue(request, "page", 0);
        if (page < 0) {
            throw new IllegalArgumentException("PATCH_EXPLORER_PAGE_INVALID");
        }
        return page;
    }

    private static String pageToken(int page, int pageSize, String sourceIdentity, List<String> interestTypes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (page + ":" + pageSize + ":" + shortHash(sourceIdentity) + ":"
                        + shortHash(String.join(",", interestTypes))).getBytes(StandardCharsets.UTF_8));
    }

    private static List<List<Cell>> components(List<Cell> cells) {
        Map<String, Cell> remaining = new LinkedHashMap<>();
        for (Cell cell : cells) {
            remaining.put(key(cell.x(), cell.z()), cell);
        }
        List<List<Cell>> result = new ArrayList<>();
        while (!remaining.isEmpty()) {
            Cell seed = remaining.values().iterator().next();
            remaining.remove(key(seed.x(), seed.z()));
            List<Cell> component = new ArrayList<>();
            ArrayDeque<Cell> queue = new ArrayDeque<>();
            queue.add(seed);
            while (!queue.isEmpty()) {
                Cell current = queue.removeFirst();
                component.add(current);
                for (int[] direction : CARDINAL) {
                    Cell next = remaining.remove(key(current.x() + direction[0], current.z() + direction[1]));
                    if (next != null) {
                        queue.addLast(next);
                    }
                }
            }
            component.sort(Comparator.comparingInt(Cell::z).thenComparingInt(Cell::x));
            result.add(component);
        }
        return result;
    }

    private static List<Cell> largestComponent(List<Cell> cells) {
        return components(cells).stream().max(Comparator.<List<Cell>>comparingInt(List::size)
                .thenComparingInt(component -> -bounds(component).minZ())
                .thenComparingInt(component -> -bounds(component).minX())).orElse(List.of());
    }

    private static Cell suggestedAnchor(List<Cell> cells, Scope scope) {
        Set<String> keys = new HashSet<>();
        for (Cell cell : cells) {
            keys.add(key(cell.x(), cell.z()));
        }
        if ("realm_t4".equals(scope.scopeType()) && scope.coarseTerrainSource() != null
                && cells.stream().anyMatch(cell -> cell.coarseTerrain() != null)) {
            return cells.stream().min(Comparator
                    .comparingInt(PatchExplorerService::terrainAnchorRank)
                    .thenComparingDouble(cell -> cell.coarseTerrain() == null
                            ? Double.POSITIVE_INFINITY : cell.coarseTerrain().localRelief())
                    .thenComparingDouble(cell -> cell.coarseTerrain() == null
                            ? Double.POSITIVE_INFINITY : cell.coarseTerrain().slopeProxy())
                    .thenComparing(Comparator.comparingInt((Cell cell) -> boundaryDepth(cell, keys)).reversed())
                    .thenComparingInt(Cell::z).thenComparingInt(Cell::x)).orElseThrow();
        }
        return cells.stream().max(Comparator.comparingInt((Cell cell) -> boundaryDepth(cell, keys))
                .thenComparingInt(cell -> -cell.z()).thenComparingInt(cell -> -cell.x())).orElseThrow();
    }

    private static int terrainAnchorRank(Cell cell) {
        if (cell.coarseTerrain() == null) {
            return 2;
        }
        return cell.coarseTerrain().water() ? 1 : 0;
    }

    private static int boundaryDepth(Cell cell, Set<String> keys) {
        int depth = 0;
        while (depth < 128) {
            int radius = depth + 1;
            boolean complete = true;
            for (int dx = -radius; dx <= radius && complete; dx++) {
                int dz = radius - Math.abs(dx);
                complete = keys.contains(key(cell.x() + dx, cell.z() + dz))
                        && keys.contains(key(cell.x() + dx, cell.z() - dz));
            }
            if (!complete) {
                return depth;
            }
            depth++;
        }
        return depth;
    }

    private static int perimeter(List<Cell> cells) {
        Set<String> keys = new HashSet<>();
        cells.forEach(cell -> keys.add(key(cell.x(), cell.z())));
        int result = 0;
        for (Cell cell : cells) {
            for (int[] direction : CARDINAL) {
                if (!keys.contains(key(cell.x() + direction[0], cell.z() + direction[1]))) {
                    result++;
                }
            }
        }
        return result;
    }

    private static boolean contains(List<Cell> outer, List<Cell> inner) {
        Set<String> keys = new HashSet<>();
        outer.forEach(cell -> keys.add(key(cell.x(), cell.z())));
        return inner.stream().allMatch(cell -> keys.contains(key(cell.x(), cell.z())));
    }

    private static Bounds bounds(List<Cell> cells) {
        return new Bounds(cells.stream().mapToInt(Cell::x).min().orElse(0),
                cells.stream().mapToInt(Cell::z).min().orElse(0),
                cells.stream().mapToInt(Cell::x).max().orElse(0),
                cells.stream().mapToInt(Cell::z).max().orElse(0));
    }

    private static List<Cell> allCells(List<Candidate> candidates) {
        Map<String, Cell> cells = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            candidate.cells().forEach(cell -> cells.putIfAbsent(key(cell.x(), cell.z()), cell));
        }
        return List.copyOf(cells.values());
    }

    private static double[] center(List<Cell> cells) {
        return new double[]{cells.stream().mapToDouble(Cell::x).average().orElse(0.0),
                cells.stream().mapToDouble(Cell::z).average().orElse(0.0)};
    }

    private static JsonObject centerJson(List<Cell> cells) {
        double[] center = center(cells);
        int step = cells.get(0).step();
        JsonObject result = new JsonObject();
        result.addProperty("gridX", center[0]);
        result.addProperty("gridZ", center[1]);
        result.addProperty("blockX", Math.round(center[0] * step));
        result.addProperty("blockZ", Math.round(center[1] * step));
        return result;
    }

    private static JsonObject anchorJson(Cell cell) {
        JsonObject result = new JsonObject();
        result.addProperty("gridX", cell.x());
        result.addProperty("gridZ", cell.z());
        result.addProperty("blockX", cell.blockX() + cell.step() / 2);
        result.addProperty("blockZ", cell.blockZ() + cell.step() / 2);
        result.addProperty("method", "max_boundary_clearance_within_candidate");
        return result;
    }

    private static JsonObject boundsJson(Bounds bounds, int step) {
        JsonObject result = new JsonObject();
        JsonObject grid = new JsonObject();
        grid.addProperty("minX", bounds.minX());
        grid.addProperty("minZ", bounds.minZ());
        grid.addProperty("maxX", bounds.maxX());
        grid.addProperty("maxZ", bounds.maxZ());
        result.add("grid", grid);
        JsonObject block = new JsonObject();
        block.addProperty("minX", bounds.minX() * step);
        block.addProperty("minZ", bounds.minZ() * step);
        block.addProperty("maxX", (bounds.maxX() + 1) * step - 1);
        block.addProperty("maxZ", (bounds.maxZ() + 1) * step - 1);
        result.add("block", block);
        return result;
    }

    private static JsonObject legalRegionJson(Candidate candidate, int step, String selectionRef) {
        JsonObject region = new JsonObject();
        region.addProperty("schemaVersion", CityD4CandidateLegalRegion.SCHEMA);
        region.addProperty("patchSelectionRef", selectionRef);
        region.addProperty("cellStepBlocks", step);
        JsonObject blockBounds = new JsonObject();
        blockBounds.addProperty("minX", candidate.bounds().minX() * step);
        blockBounds.addProperty("minZ", candidate.bounds().minZ() * step);
        blockBounds.addProperty("maxX", (candidate.bounds().maxX() + 1) * step - 1);
        blockBounds.addProperty("maxZ", (candidate.bounds().maxZ() + 1) * step - 1);
        region.add("bounds", blockBounds);
        JsonArray cells = new JsonArray();
        candidate.cells().stream()
                .sorted(Comparator.comparingInt(Cell::z).thenComparingInt(Cell::x))
                .forEach(cell -> {
                    JsonObject member = new JsonObject();
                    member.addProperty("gridX", cell.x());
                    member.addProperty("gridZ", cell.z());
                    member.addProperty("blockMinX", cell.blockX());
                    member.addProperty("blockMinZ", cell.blockZ());
                    member.addProperty("blockMaxX", cell.blockX() + cell.step() - 1);
                    member.addProperty("blockMaxZ", cell.blockZ() + cell.step() - 1);
                    cells.add(member);
                });
        region.add("memberCells", cells);
        return region;
    }

    private static JsonObject metrics(List<Cell> cells) {
        JsonObject result = new JsonObject();
        result.addProperty("meanConfidence", cells.stream().mapToDouble(Cell::confidence).average().orElse(0.0));
        result.addProperty("geometrySource", "member_cells");
        return result;
    }

    private static JsonObject coarseTerrainEvidence(List<Cell> cells, CoarseTerrainSource source) {
        if (source == null) {
            return null;
        }
        List<CoarseTerrain> samples = cells.stream().map(Cell::coarseTerrain).filter(Objects::nonNull).toList();
        if (samples.isEmpty()) {
            return null;
        }
        List<Double> heights = samples.stream().map(CoarseTerrain::elevation).sorted().toList();
        List<Double> slopes = samples.stream().map(CoarseTerrain::slopeProxy).sorted().toList();
        long water = samples.stream().filter(CoarseTerrain::water).count();
        JsonObject result = new JsonObject();
        result.addProperty("sampleCount", samples.size());
        result.addProperty("coverage", samples.size() / (double) Math.max(1, cells.size()));
        result.addProperty("heightP10", percentile(heights, 0.10));
        result.addProperty("heightP50", percentile(heights, 0.50));
        result.addProperty("heightP90", percentile(heights, 0.90));
        result.addProperty("robustRelief", percentile(heights, 0.90) - percentile(heights, 0.10));
        result.addProperty("waterFrac", water / (double) samples.size());
        result.addProperty("slopeProxyP90", percentile(slopes, 0.90));
        result.add("terrainIdHistogram", histogram(samples, CoarseTerrain::terrainId));
        result.add("sourceBiomeIdHistogram", histogram(samples, CoarseTerrain::sourceBiomeId));
        JsonObject provider = new JsonObject();
        provider.addProperty("providerId", source.providerId());
        provider.addProperty("sourceKind", source.sourceKind());
        provider.addProperty("fastPath", source.fastPath());
        provider.addProperty("fallbackReason", source.fallbackReason());
        provider.addProperty("sourceFingerprint", source.sourceFingerprint());
        provider.addProperty("samplingSemantics", source.samplingSemantics());
        result.add("provider", provider);
        result.addProperty("artifact", source.artifact());
        if (!source.heightWaterPreview().isBlank()) {
            result.addProperty("heightWaterPreview", source.heightWaterPreview());
        }
        result.addProperty("advisoryOnly", source.advisoryOnly());
        result.addProperty("requiredNextGate", source.requiredNextGate());
        return result;
    }

    private static JsonObject histogram(List<CoarseTerrain> samples,
            java.util.function.Function<CoarseTerrain, String> classifier) {
        Map<String, Long> counts = new HashMap<>();
        samples.forEach(sample -> counts.merge(classifier.apply(sample), 1L, Long::sum));
        JsonObject result = new JsonObject();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .forEach(entry -> result.addProperty(entry.getKey(), entry.getValue()));
        return result;
    }

    private static double percentile(List<Double> sorted, double fraction) {
        if (sorted.isEmpty()) {
            return 0.0;
        }
        int index = (int) Math.round((sorted.size() - 1) * fraction);
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static JsonObject composition(List<Cell> cells,
                                          java.util.function.Function<Cell, String> classifier) {
        Map<String, Long> counts = new HashMap<>();
        for (Cell cell : cells) {
            counts.merge(classifier.apply(cell), 1L, Long::sum);
        }
        JsonObject result = new JsonObject();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .forEach(entry -> {
                    JsonObject fact = new JsonObject();
                    fact.addProperty("cellCount", entry.getValue());
                    fact.addProperty("ratio", cells.isEmpty() ? 0.0 : entry.getValue() / (double) cells.size());
                    result.add(entry.getKey(), fact);
                });
        return result;
    }

    private static String candidateBasis(Scope scope) {
        if ("city_d4".equals(scope.scopeType())) {
            return "d3_landform_patch";
        }
        return scope.terrainPatchSource() == null ? "sealed_w_landform_patch" : "t_scale_landform_patch";
    }

    private static String direction(double dx, double dz) {
        if (Math.abs(dx) < 0.5 && Math.abs(dz) < 0.5) {
            return "overlap";
        }
        String ns = dz < -0.5 ? "north" : dz > 0.5 ? "south" : "";
        String ew = dx < -0.5 ? "west" : dx > 0.5 ? "east" : "";
        return ns + ew;
    }

    private static String displayName(String type) {
        if (type.contains(":")) {
            String path = type.substring(type.indexOf(':') + 1);
            StringBuilder result = new StringBuilder();
            for (String word : path.split("_")) {
                if (!result.isEmpty()) result.append(' ');
                result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
            return result.toString();
        }
        return switch (type) {
            case "water" -> "Water";
            case "shore" -> "Shore";
            case "plain" -> "Plain";
            case "terrace" -> "Terrace";
            case "slope" -> "Slope";
            case "cliff" -> "Cliff";
            case "ridge" -> "Ridge";
            case "valley" -> "Valley";
            case "basin" -> "Basin";
            default -> "Unknown";
        };
    }

    private static String normalizeType(String value) {
        String normalized = value == null ? "unknown" : value.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? "unknown" : normalized.replace('-', '_').replace(' ', '_');
    }

    private static long medianArea(List<Candidate> candidates) {
        List<Long> values = candidates.stream().map(Candidate::areaBlocks).sorted().toList();
        return values.isEmpty() ? 0L : values.get(values.size() / 2);
    }

    private static String key(int x, int z) {
        return x + "," + z;
    }

    private static String safeId(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,160}")) {
            throw new IllegalArgumentException("PATCH_EXPLORER_" + field.toUpperCase(Locale.ROOT) + "_INVALID");
        }
        return value;
    }

    private static String requiredString(JsonObject object, String key) {
        String value = stringValue(object, key, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException(key + " is required.");
        }
        return value;
    }

    private static String stringValue(JsonObject object, String key, String fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString() : fallback;
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsInt() : fallback;
    }

    private static long longValue(JsonObject object, String key, long fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsLong() : fallback;
    }

    private static double doubleValue(JsonObject object, String key, double fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsDouble() : fallback;
    }

    private static boolean booleanValue(JsonObject object, String key, boolean fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsBoolean() : fallback;
    }

    private static JsonArray array(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonArray()
                ? object.getAsJsonArray(key) : new JsonArray();
    }

    private static JsonObject object(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonObject()) {
            throw new IllegalArgumentException(key + " object is required.");
        }
        return object.getAsJsonObject(key);
    }

    private static boolean containsString(JsonArray array, String value) {
        for (JsonElement element : array) {
            if (element.isJsonPrimitive() && value.equals(element.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasBounds(JsonObject object) {
        return object.has("minX") && object.has("minZ") && object.has("maxX") && object.has("maxZ");
    }

    private static JsonArray strings(List<String> values) {
        JsonArray result = new JsonArray();
        values.forEach(result::add);
        return result;
    }

    private static JsonObject readObject(Path path) throws IOException {
        JsonElement parsed = JsonParser.parseString(Files.readString(path));
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("PATCH_EXPLORER_ARTIFACT_NOT_OBJECT: " + path);
        }
        return parsed.getAsJsonObject();
    }

    private static void writeJson(Path path, JsonElement value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, GSON.toJson(value));
    }

    private static void requireFile(Path path, String code) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(code + ": " + path);
        }
    }

    private static String shortHash(String value) {
        return sha256(value).substring(0, 12);
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder();
            for (byte value : digest) {
                result.append(String.format(Locale.ROOT, "%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) {
                result.append(String.format(Locale.ROOT, "%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @FunctionalInterface
    public interface TerrainPreviewRunner {
        PatchCandidateTerrainPreviewService.Result refine(String runId, String realmId,
                String scopeSourceIdentity, PatchCandidateTerrainPreviewService.Target target,
                PatchCandidateTerrainPreviewService.Level level) throws IOException;
    }

    @FunctionalInterface
    public interface TerrainPatchRunner {
        TerrainScalePatchService.Result refine(String runId, String scopeType, String scopeId,
                String scopeSourceIdentity, List<TerrainScalePatchService.SeedCell> sourceCells) throws IOException;
    }

    public record SessionTerrainContext(String scopeType, boolean preferGeneratorNativeTerrain) {
    }

    private static final Comparator<Candidate> CANDIDATE_ORDER = Comparator.comparing(Candidate::type)
            .thenComparing(Comparator.comparingLong(Candidate::largestContinuousAreaBlocks).reversed())
            .thenComparing(Comparator.comparingLong(Candidate::areaBlocks).reversed())
            .thenComparing(Candidate::candidateId);

    private enum OverviewMode {
        TERRAIN("Terrain overview"),
        ALL_PATCHES("All patches"),
        TOP_PATCHES("Top patches");

        private final String title;

        OverviewMode(String title) {
            this.title = title;
        }

        String title() {
            return title;
        }
    }

    private record Scope(String runId, String scopeType, String scopeId, int cellStepBlocks,
                         List<Candidate> candidates, List<Cell> scopeCells, String sourceIdentity,
                         List<String> sourceArtifacts, List<Bounds> occupied,
                         CoarseTerrainSource coarseTerrainSource, TerrainPatchSource terrainPatchSource) {
    }

    private record Cell(int x, int z, int blockX, int blockZ, int step, String patchRef, String type,
                        String terrainType, String baseLandform, double confidence, CoarseTerrain coarseTerrain) {
    }

    private record OverlayCell(Cell cell, String regionId, String type) {
    }

    private record CoarseTerrain(double elevation, boolean water, String biomeId, String terrainId,
                                 String sourceBiomeId, int neighborCount, double neighborElevationDeltaMean,
                                 double neighborElevationDeltaMax, double slopeProxy, double localRelief) {
        static CoarseTerrain fromJson(JsonObject json) {
            return new CoarseTerrain(doubleValue(json, "elevation", 0.0),
                    booleanValue(json, "water", false), stringValue(json, "biomeId", "unknown"),
                    stringValue(json, "terrainId", "unknown"), stringValue(json, "sourceBiomeId", "unknown"),
                    intValue(json, "neighborCount", 0), doubleValue(json, "neighborElevationDeltaMean", 0.0),
                    doubleValue(json, "neighborElevationDeltaMax", 0.0),
                    doubleValue(json, "slopeProxy", 0.0), doubleValue(json, "localRelief", 0.0));
        }

        JsonObject asJson() {
            JsonObject result = new JsonObject();
            result.addProperty("elevation", elevation);
            result.addProperty("water", water);
            result.addProperty("biomeId", biomeId);
            result.addProperty("terrainId", terrainId);
            result.addProperty("sourceBiomeId", sourceBiomeId);
            result.addProperty("neighborCount", neighborCount);
            result.addProperty("neighborElevationDeltaMean", neighborElevationDeltaMean);
            result.addProperty("neighborElevationDeltaMax", neighborElevationDeltaMax);
            result.addProperty("slopeProxy", slopeProxy);
            result.addProperty("localRelief", localRelief);
            return result;
        }
    }

    private record CoarseTerrainSource(String providerId, String sourceKind, boolean fastPath,
                                       String fallbackReason, String sourceFingerprint, String samplingSemantics,
                                       String artifact,
                                       String heightWaterPreview, boolean advisoryOnly, String requiredNextGate) {
        static CoarseTerrainSource fromJson(JsonObject json) {
            return new CoarseTerrainSource(stringValue(json, "providerId", "unknown"),
                    stringValue(json, "sourceKind", "unknown"), booleanValue(json, "fastPath", false),
                    stringValue(json, "fallbackReason", ""), stringValue(json, "sourceFingerprint", "unknown"),
                    stringValue(json, "samplingSemantics", "unspecified"), stringValue(json, "artifact", ""),
                    stringValue(json, "heightWaterPreview", ""),
                    booleanValue(json, "advisoryOnly", true),
                    stringValue(json, "requiredNextGate", RealmT4CoarseTerrainPreviewService.REQUIRED_NEXT_GATE));
        }

        JsonObject asJson() {
            JsonObject result = new JsonObject();
            result.addProperty("providerId", providerId);
            result.addProperty("sourceKind", sourceKind);
            result.addProperty("fastPath", fastPath);
            result.addProperty("fallbackReason", fallbackReason);
            result.addProperty("sourceFingerprint", sourceFingerprint);
            result.addProperty("samplingSemantics", samplingSemantics);
            result.addProperty("artifact", artifact);
            result.addProperty("heightWaterPreview", heightWaterPreview);
            result.addProperty("advisoryOnly", advisoryOnly);
            result.addProperty("requiredNextGate", requiredNextGate);
            return result;
        }
    }

    private record TerrainPatchSource(String providerId, String sourceKind, String sourceFingerprint,
                                      String samplingSemantics) {
        static TerrainPatchSource from(TerrainScalePatchService.Source source) {
            return new TerrainPatchSource(source.providerId(), source.sourceKind(), source.sourceFingerprint(),
                    source.samplingSemantics());
        }

        static TerrainPatchSource fromJson(JsonObject json) {
            return new TerrainPatchSource(requiredString(json, "providerId"), requiredString(json, "sourceKind"),
                    requiredString(json, "sourceFingerprint"), requiredString(json, "samplingSemantics"));
        }

        JsonObject asJson() {
            JsonObject result = new JsonObject();
            result.addProperty("providerId", providerId);
            result.addProperty("sourceKind", sourceKind);
            result.addProperty("sourceFingerprint", sourceFingerprint);
            result.addProperty("samplingSemantics", samplingSemantics);
            return result;
        }
    }

    private record TerrainEvidenceBundle(Map<String, CoarseTerrain> cells, CoarseTerrainSource source) {
        static TerrainEvidenceBundle empty() {
            return new TerrainEvidenceBundle(Map.of(), null);
        }
    }

    private record Candidate(String candidateId, String type, List<String> sourcePatchRefs, List<Cell> cells,
                             long areaBlocks, long largestContinuousAreaBlocks, long originalAreaBlocks,
                             Bounds bounds, double confidence) {
    }

    private record RawCandidate(List<String> sourcePatchRefs, int componentIndex, String type, List<Cell> original,
                                List<Cell> available) {
        String sourceKey() {
            return String.join("|", sourcePatchRefs);
        }

        long availableArea() {
            return available.isEmpty() ? 0L : (long) available.size() * available.get(0).step() * available.get(0).step();
        }

        long largestContinuousArea() {
            List<Cell> largest = largestComponent(available);
            return largest.isEmpty() ? 0L : (long) largest.size() * largest.get(0).step() * largest.get(0).step();
        }

        long originalArea() {
            return original.isEmpty() ? 0L : (long) original.size() * original.get(0).step() * original.get(0).step();
        }
    }

    private record Bounds(int minX, int minZ, int maxX, int maxZ) {
        boolean intersects(int otherMinX, int otherMinZ, int otherMaxX, int otherMaxZ) {
            return minX <= otherMaxX && maxX >= otherMinX && minZ <= otherMaxZ && maxZ >= otherMinZ;
        }
    }

    private record Geometry(boolean adjacent, boolean intersects, int boundaryDistanceCells,
                            double centerDistanceCells, String direction, int sharedBoundaryCells,
                            double contactRatio, int contactX, int contactZ) {
    }
}
