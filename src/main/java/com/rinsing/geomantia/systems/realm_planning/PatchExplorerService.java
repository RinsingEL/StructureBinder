package com.rinsing.geomantia.systems.realm_planning;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.CityD4CandidateLegalRegion;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
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
    private static final String SNAPSHOT_SCHEMA = "patch_explorer_scope_snapshot.v0.1";
    private static final String CANDIDATE_MODEL = "realm_biome_primary_city_landform_v0_1";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Set<String> SCOPES = Set.of("realm_t2", "realm_t4", "city_d4");
    private static final int DEFAULT_PAGE_SIZE = 3;
    private static final int MAX_PAGE_SIZE = 12;
    private static final int[][] CARDINAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private final Path debugRoot;

    public PatchExplorerService(Path debugRoot) {
        this.debugRoot = Objects.requireNonNull(debugRoot, "debugRoot").toAbsolutePath().normalize();
    }

    public JsonObject open(JsonObject request) throws IOException {
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
        Scope scope = loadScope(runId, scopeType, scopeId);
        JsonObject session = new JsonObject();
        session.addProperty("schemaVersion", SESSION_SCHEMA);
        session.addProperty("sessionId", sessionId);
        session.addProperty("runId", runId);
        session.addProperty("scopeType", scopeType);
        session.addProperty("scopeId", scopeId);
        session.addProperty("candidateModel", CANDIDATE_MODEL);
        session.addProperty("candidateBasis", candidateBasis(scopeType));
        session.addProperty("sourceIdentity", scope.sourceIdentity());
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
        writeJson(sessionDir.resolve("patch_explorer_session.json"), session);

        JsonObject response = base("open", runId, sessionId);
        response.addProperty("scopeType", scopeType);
        response.addProperty("scopeId", scopeId);
        response.addProperty("candidateBasis", candidateBasis(scopeType));
        response.addProperty("sourceIdentity", scope.sourceIdentity());
        response.add("typeCatalog", typeCatalog(scope));
        response.add("artifacts", artifactRefs(sessionDir, null, null));
        response.add("nextActions", strings(List.of("patch_explorer_show_candidates")));
        return response;
    }

    public JsonObject showCandidates(JsonObject request) throws IOException {
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
        Path previewPath = sessionDir.resolve("candidate_preview_" + pageKey + ".png");
        render(scope, displayed, null, previewPath);

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
        pageArtifact.add("typePages", typePages);
        pageArtifact.add("relations", relations);
        pageArtifact.addProperty("candidatePreview", debugRef(previewPath));
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
        response.add("typePages", typePages);
        response.add("relations", relations);
        response.add("artifacts", artifactRefs(sessionDir, pagePath, previewPath));
        response.add("nextActions", strings(List.of("patch_explorer_show_candidates", "patch_explorer_select_candidate")));
        return response;
    }

    public JsonObject selectCandidate(JsonObject request) throws IOException {
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
        Cell anchor = suggestedAnchor(selected.cells());
        String selectionRef = "psel_" + sha256(sessionId + "\n" + candidateId + "\n" + scope.sourceIdentity());
        JsonObject selection = new JsonObject();
        selection.addProperty("schemaVersion", SELECTION_SCHEMA);
        selection.addProperty("patchSelectionRef", selectionRef);
        selection.addProperty("sessionId", sessionId);
        selection.addProperty("runId", runId);
        selection.addProperty("scopeType", scope.scopeType());
        selection.addProperty("scopeId", scope.scopeId());
        selection.addProperty("candidateBasis", candidateBasis(scope.scopeType()));
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
        selection.addProperty("selectionReason", stringValue(request, "selectionReason", ""));
        selection.addProperty("selectedAt", Instant.now().toString());
        Path selectionDir = runDir(runId).resolve("patch_explorer_selections");
        Files.createDirectories(selectionDir);
        Path selectionPath = selectionDir.resolve(selectionRef + ".json");
        writeJson(selectionPath, selection);
        Path confirmationPath = sessionDir(runId, sessionId).resolve("selected_" + candidateId + ".png");
        render(scope, List.of(selected), selected, confirmationPath);
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
        String expectedRef = "psel_" + sha256(requiredString(selection, "sessionId") + "\n"
                + requiredString(selection, "candidateId") + "\n" + scope.sourceIdentity());
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
        snapshot.addProperty("candidateBasis", candidateBasis(scope.scopeType()));
        snapshot.addProperty("cellStepBlocks", scope.cellStepBlocks());
        snapshot.addProperty("sourceIdentity", scope.sourceIdentity());
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
                JsonObject cellJson = new JsonObject();
                cellJson.addProperty("x", cell.x());
                cellJson.addProperty("z", cell.z());
                cellJson.addProperty("blockX", cell.blockX());
                cellJson.addProperty("blockZ", cell.blockZ());
                cellJson.addProperty("patchRef", cell.patchRef());
                cellJson.addProperty("candidateType", cell.type());
                cellJson.addProperty("terrainType", cell.terrainType());
                cellJson.addProperty("baseLandform", cell.baseLandform());
                cellJson.addProperty("confidence", cell.confidence());
                cells.add(cellJson);
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

    private Scope readScopeSnapshot(Path path, String expectedIdentity, List<String> sourceArtifacts)
            throws IOException {
        JsonObject snapshot = readObject(path);
        if (!SNAPSHOT_SCHEMA.equals(stringValue(snapshot, "schemaVersion", ""))
                || !CANDIDATE_MODEL.equals(stringValue(snapshot, "candidateModel", ""))
                || !expectedIdentity.equals(stringValue(snapshot, "sourceIdentity", ""))) {
            throw new IllegalArgumentException("PATCH_EXPLORER_SCOPE_SNAPSHOT_STALE_OR_UNSUPPORTED");
        }
        int step = intValue(snapshot, "cellStepBlocks", 0);
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
                        doubleValue(cell, "confidence", 0.5)));
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
        candidates.sort(CANDIDATE_ORDER);
        return new Scope(requiredString(snapshot, "runId"), requiredString(snapshot, "scopeType"),
                requiredString(snapshot, "scopeId"), step, List.copyOf(candidates), allCells(candidates),
                expectedIdentity, sourceArtifacts, List.copyOf(occupied));
    }

    private Scope loadScope(String runId, String scopeType, String scopeId) throws IOException {
        Path runDir = runDir(runId);
        if ("city_d4".equals(scopeType)) {
            return loadCityScope(runId, scopeId, runDir);
        }
        return loadRealmScope(runId, scopeType, scopeId, runDir);
    }

    private Scope loadRealmScope(String runId, String scopeType, String scopeId, Path runDir) throws IOException {
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
        Map<String, List<Cell>> byBiome = new LinkedHashMap<>();
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
            String type = dominantBiome(cell);
            String terrainType = normalizeType(stringValue(cell, "landform", "unknown"));
            String baseLandform = normalizeType(stringValue(cell, "baseLandform", terrainType));
            double confidence = biomeDominance(cell);
            Cell normalized = new Cell(x, z, intValue(cell, "blockX", x * step),
                    intValue(cell, "blockZ", z * step), step, patchRef, type, terrainType,
                    baseLandform, confidence);
            byBiome.computeIfAbsent(type, ignored -> new ArrayList<>()).add(normalized);
        }
        List<Candidate> candidates = candidatesFromBiomeCells(byBiome);
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("PATCH_EXPLORER_SCOPE_EMPTY: " + scopeId);
        }
        String identity = sourceIdentity(scopeType, scopeId, sources);
        return new Scope(runId, scopeType, scopeId, step, candidates, allCells(candidates), identity,
                sources.stream().map(this::debugRef).toList(), List.of());
    }

    private Scope loadCityScope(String runId, String cityId, Path runDir) throws IOException {
        String safeCity = safeId(cityId, "citySeedId");
        Path d3Path = runDir.resolve("city_d3_" + safeCity).resolve("city_landform_review_package.json");
        requireFile(d3Path, "PATCH_EXPLORER_D3_PACKAGE_MISSING");
        JsonObject review = readObject(d3Path);
        if (!"city_landform_review.v0.1".equals(stringValue(review, "schemaVersion", ""))) {
            throw new IllegalArgumentException("PATCH_EXPLORER_D3_SCHEMA_UNSUPPORTED");
        }
        if (!cityId.equals(stringValue(review, "cityId", cityId))) {
            throw new IllegalArgumentException("PATCH_EXPLORER_D3_CITY_MISMATCH");
        }
        JsonObject grid = object(review, "grid");
        int step = intValue(grid, "cellStepBlocks", 0);
        if (step <= 0) {
            throw new IllegalArgumentException("PATCH_EXPLORER_D3_CELL_STEP_INVALID");
        }
        JsonObject core = object(grid, "blockBounds");
        int minX = intValue(core, "minX", Integer.MIN_VALUE);
        int minZ = intValue(core, "minZ", Integer.MIN_VALUE);
        int maxX = intValue(core, "maxX", Integer.MAX_VALUE);
        int maxZ = intValue(core, "maxZ", Integer.MAX_VALUE);
        List<Path> sources = new ArrayList<>(List.of(d3Path));
        List<Bounds> occupied = loadOccupied(runDir, safeCity, sources);
        Map<String, List<Cell>> byPatch = new LinkedHashMap<>();
        for (JsonElement element : array(review, "landformPatches")) {
            JsonObject patch = element.getAsJsonObject();
            String patchRef = requiredString(patch, "landformPatchId");
            String type = normalizeType(stringValue(patch, "landformType", "unknown"));
            double confidence = containsString(array(patch, "landformTags"), "low_confidence") ? 0.4 : 0.8;
            for (JsonElement cellElement : array(patch, "memberCells")) {
                JsonObject cell = cellElement.getAsJsonObject();
                int bx = intValue(cell, "blockMinX", 0);
                int bz = intValue(cell, "blockMinZ", 0);
                if (bx + step - 1 < minX || bz + step - 1 < minZ || bx >= maxX || bz >= maxZ) {
                    continue;
                }
                byPatch.computeIfAbsent(patchRef, ignored -> new ArrayList<>())
                        .add(new Cell(Math.floorDiv(bx, step), Math.floorDiv(bz, step), bx, bz, step,
                                patchRef, type, type, type, confidence));
            }
        }
        List<Candidate> candidates = candidatesFromCells(byPatch, true, occupied);
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("PATCH_EXPLORER_SCOPE_EMPTY: " + cityId);
        }
        String identity = citySourceIdentity(cityId, d3Path, occupied);
        return new Scope(runId, "city_d4", cityId, step, candidates, allCells(candidates), identity,
                sources.stream().map(this::debugRef).toList(), occupied);
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
        return List.of(
                runDir.resolve("city_d4_candidate_session_" + cityId).resolve("d4_candidate_session.json"),
                runDir.resolve("city_d4_array_layout_" + cityId).resolve("d4_array_occupied_field.json"),
                runDir.resolve("city_d4_design_loop_" + cityId).resolve("d4_design_loop_occupied_field.json"),
                runDir.resolve("city_d4_" + cityId).resolve("structure_anchor_map.json"));
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

    private static List<Candidate> candidatesFromBiomeCells(Map<String, List<Cell>> byBiome) {
        List<RawCandidate> raw = new ArrayList<>();
        for (Map.Entry<String, List<Cell>> entry : byBiome.entrySet()) {
            List<List<Cell>> components = components(entry.getValue());
            components.sort(Comparator.<List<Cell>>comparingInt(List::size).reversed()
                    .thenComparingInt(cells -> bounds(cells).minZ())
                    .thenComparingInt(cells -> bounds(cells).minX()));
            for (int index = 0; index < components.size(); index++) {
                List<Cell> component = components.get(index);
                List<String> sourcePatchRefs = component.stream().map(Cell::patchRef).distinct().sorted().toList();
                raw.add(new RawCandidate(sourcePatchRefs, index, entry.getKey(), component, component));
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
            item.addProperty("candidateBasis", candidateBasis(scope.scopeType()));
            item.addProperty("patchCount", candidates.size());
            item.addProperty("totalAreaBlocks", candidates.stream().mapToLong(Candidate::areaBlocks).sum());
            item.addProperty("maxAreaBlocks", candidates.stream().mapToLong(Candidate::areaBlocks).max().orElse(0));
            item.addProperty("medianAreaBlocks", medianArea(candidates));
            item.addProperty("supportsCoarseAnchor", candidates.stream().anyMatch(candidate ->
                    candidate.cells().size() >= 4));
            item.addProperty("factSource", "city_d4".equals(scope.scopeType())
                    ? "CityLandformReviewPackage.landformPatches"
                    : "sealed WorldPatchMap.cells[].biomeHist dominant biome");
            item.addProperty("meanConfidence", candidates.stream().mapToDouble(Candidate::confidence).average().orElse(0.0));
            List<Cell> catalogCells = candidates.stream().flatMap(candidate -> candidate.cells().stream()).toList();
            item.add("terrainComposition", composition(catalogCells, Cell::terrainType));
            item.add("baseLandformComposition", composition(catalogCells, Cell::baseLandform));
            if ("city_d4".equals(scope.scopeType())) {
                item.addProperty("availableAreaBlocks", candidates.stream().mapToLong(Candidate::areaBlocks).sum());
                item.addProperty("originalAreaBlocks", candidates.stream().mapToLong(Candidate::originalAreaBlocks).sum());
            }
            result.add(item);
        }
        return result;
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
            item.addProperty("candidateBasis", candidateBasis(scope.scopeType()));
            item.addProperty("areaBlocks", candidate.areaBlocks());
            item.addProperty("largestContinuousAreaBlocks", candidate.largestContinuousAreaBlocks());
            item.addProperty("originalAreaBlocks", candidate.originalAreaBlocks());
            item.addProperty("cellCount", candidate.cells().size());
            item.addProperty("hardLegal", candidate.areaBlocks() > 0);
            item.addProperty("confidence", candidate.confidence());
            item.add("sourcePatchRefs", strings(candidate.sourcePatchRefs()));
            item.add("bounds", boundsJson(candidate.bounds(), scope.cellStepBlocks()));
            item.add("center", centerJson(candidate.cells()));
            item.add("suggestedAnchor", anchorJson(suggestedAnchor(candidate.cells())));
            item.add("terrainMetrics", metrics(candidate.cells()));
            item.add("terrainComposition", composition(candidate.cells(), Cell::terrainType));
            item.add("baseLandformComposition", composition(candidate.cells(), Cell::baseLandform));
            result.add(item);
        }
        return result;
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

    private void render(Scope scope, List<Candidate> candidates, Candidate selected, Path output) throws IOException {
        Bounds bounds = bounds(scope.scopeCells());
        int widthCells = Math.max(1, bounds.maxX() - bounds.minX() + 1);
        int heightCells = Math.max(1, bounds.maxZ() - bounds.minZ() + 1);
        int scale = Math.max(2, Math.min(14, 920 / Math.max(widthCells, heightCells)));
        int legendWidth = 260;
        BufferedImage image = new BufferedImage(widthCells * scale + legendWidth,
                Math.max(360, heightCells * scale + 44), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(244, 245, 247));
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.setColor(new Color(218, 221, 225));
            for (Cell cell : scope.scopeCells()) {
                g.fillRect((cell.x() - bounds.minX()) * scale, (cell.z() - bounds.minZ()) * scale, scale, scale);
            }
            Set<String> candidateIds = new HashSet<>();
            for (Candidate candidate : candidates) {
                candidateIds.add(candidate.candidateId());
                Color color = color(candidate.type());
                if (selected != null && candidate != selected) {
                    color = new Color(color.getRed(), color.getGreen(), color.getBlue(), 65);
                }
                g.setColor(color);
                for (Cell cell : candidate.cells()) {
                    g.fillRect((cell.x() - bounds.minX()) * scale, (cell.z() - bounds.minZ()) * scale, scale, scale);
                }
                Bounds cb = candidate.bounds();
                g.setColor(color.darker());
                g.setStroke(new BasicStroke(2f));
                g.drawRect((cb.minX() - bounds.minX()) * scale, (cb.minZ() - bounds.minZ()) * scale,
                        Math.max(scale, (cb.maxX() - cb.minX() + 1) * scale),
                        Math.max(scale, (cb.maxZ() - cb.minZ() + 1) * scale));
                double[] center = center(candidate.cells());
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(12, Math.min(20, scale + 7))));
                g.drawString(candidate.candidateId(), (int) ((center[0] - bounds.minX()) * scale),
                        (int) ((center[1] - bounds.minZ()) * scale));
            }
            if (selected != null) {
                Cell anchor = suggestedAnchor(selected.cells());
                int x = (anchor.x() - bounds.minX()) * scale + scale / 2;
                int z = (anchor.z() - bounds.minZ()) * scale + scale / 2;
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(4f));
                g.drawLine(x - 8, z, x + 8, z);
                g.drawLine(x, z - 8, x, z + 8);
                g.setColor(Color.BLACK);
                g.setStroke(new BasicStroke(1.5f));
                g.drawOval(x - 10, z - 10, 20, 20);
            }
            int x = widthCells * scale + 18;
            int y = 30;
            g.setColor(new Color(24, 28, 34));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 17));
            g.drawString(selected == null ? "Patch Explorer" : "Patch selection", x, y);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            y += 24;
            g.drawString(scope.scopeType() + " / " + scope.scopeId(), x, y);
            for (Candidate candidate : candidates) {
                y += 25;
                g.setColor(color(candidate.type()));
                g.fillRect(x, y - 12, 13, 13);
                g.setColor(new Color(24, 28, 34));
                g.drawString(candidate.candidateId() + "  " + candidate.areaBlocks() + " blocks", x + 20, y);
            }
            if ("city_d4".equals(scope.scopeType())) {
                y += 28;
                g.setColor(new Color(90, 90, 90));
                g.drawString("area excludes hard occupied", x, y);
            }
        } finally {
            g.dispose();
        }
        Files.createDirectories(output.getParent());
        ImageIO.write(image, "png", output.toFile());
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

    private String citySourceIdentity(String cityId, Path d3Path, List<Bounds> occupied) throws IOException {
        Path normalizedD3 = d3Path.toAbsolutePath().normalize();
        if (!normalizedD3.startsWith(debugRoot)) {
            throw new IllegalArgumentException("PATCH_EXPLORER_SOURCE_OUTSIDE_DEBUG_ROOT: " + d3Path);
        }
        StringBuilder source = new StringBuilder(CANDIDATE_MODEL).append('\n')
                .append("city_d4").append('\n').append(cityId).append('\n')
                .append(debugRef(normalizedD3)).append(':').append(sha256(normalizedD3)).append('\n');
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

    private JsonObject artifactRefs(Path sessionDir, Path pagePath, Path previewPath) {
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("explorationSession", debugRef(sessionDir.resolve("patch_explorer_session.json")));
        if (pagePath != null) {
            artifacts.addProperty("candidatePage", debugRef(pagePath));
        }
        if (previewPath != null) {
            artifacts.addProperty("candidatePreview", debugRef(previewPath));
        }
        return artifacts;
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

    private static Cell suggestedAnchor(List<Cell> cells) {
        Set<String> keys = new HashSet<>();
        for (Cell cell : cells) {
            keys.add(key(cell.x(), cell.z()));
        }
        return cells.stream().max(Comparator.comparingInt((Cell cell) -> boundaryDepth(cell, keys))
                .thenComparingInt(cell -> -cell.z()).thenComparingInt(cell -> -cell.x())).orElseThrow();
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

    private static String dominantBiome(JsonObject cell) {
        if (!cell.has("biomeHist") || !cell.get("biomeHist").isJsonObject()) {
            return "unknown";
        }
        String best = "unknown";
        int bestCount = -1;
        for (Map.Entry<String, JsonElement> entry : cell.getAsJsonObject("biomeHist").entrySet()) {
            int count = entry.getValue().getAsInt();
            if (count > bestCount || count == bestCount && entry.getKey().compareTo(best) < 0) {
                best = entry.getKey();
                bestCount = count;
            }
        }
        return normalizeType(best);
    }

    private static double biomeDominance(JsonObject cell) {
        if (!cell.has("biomeHist") || !cell.get("biomeHist").isJsonObject()) {
            return 0.0;
        }
        int total = 0;
        int largest = 0;
        for (JsonElement value : cell.getAsJsonObject("biomeHist").asMap().values()) {
            int count = value.getAsInt();
            total += count;
            largest = Math.max(largest, count);
        }
        return total == 0 ? 0.0 : largest / (double) total;
    }

    private static String candidateBasis(String scopeType) {
        return "city_d4".equals(scopeType) ? "d3_landform_patch" : "dominant_biome_contiguous_region";
    }

    private static String direction(double dx, double dz) {
        if (Math.abs(dx) < 0.5 && Math.abs(dz) < 0.5) {
            return "overlap";
        }
        String ns = dz < -0.5 ? "north" : dz > 0.5 ? "south" : "";
        String ew = dx < -0.5 ? "west" : dx > 0.5 ? "east" : "";
        return ns + ew;
    }

    private static Color color(String type) {
        if (type.contains(":")) {
            return biomeColor(type.substring(type.indexOf(':') + 1));
        }
        return switch (type) {
            case "water" -> new Color(55, 126, 184, 220);
            case "shore" -> new Color(54, 162, 151, 220);
            case "plain" -> new Color(113, 168, 76, 220);
            case "terrace" -> new Color(189, 170, 86, 220);
            case "slope" -> new Color(210, 140, 65, 220);
            case "cliff" -> new Color(119, 104, 96, 220);
            case "ridge" -> new Color(164, 80, 88, 220);
            case "valley" -> new Color(91, 139, 107, 220);
            case "basin" -> new Color(141, 108, 164, 220);
            default -> new Color(112, 119, 128, 220);
        };
    }

    private static Color biomeColor(String biome) {
        if (biome.contains("ocean") || biome.contains("river")) return new Color(55, 126, 184, 220);
        if (biome.contains("beach") || biome.contains("shore")) return new Color(214, 190, 112, 220);
        if (biome.contains("badlands")) return new Color(177, 91, 61, 220);
        if (biome.contains("desert")) return new Color(219, 177, 79, 220);
        if (biome.contains("swamp")) return new Color(65, 118, 102, 220);
        if (biome.contains("jungle")) return new Color(47, 119, 67, 220);
        if (biome.contains("forest") || biome.contains("taiga")) return new Color(54, 109, 72, 220);
        if (biome.contains("plains") || biome.contains("meadow")) return new Color(112, 166, 76, 220);
        if (biome.contains("savanna")) return new Color(157, 156, 68, 220);
        if (biome.contains("peak") || biome.contains("grove")) return new Color(126, 132, 137, 220);
        if (biome.contains("mushroom")) return new Color(156, 91, 143, 220);
        Color generated = Color.getHSBColor(Math.floorMod(biome.hashCode(), 360) / 360.0f, 0.48f, 0.72f);
        return new Color(generated.getRed(), generated.getGreen(), generated.getBlue(), 220);
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

    private static final Comparator<Candidate> CANDIDATE_ORDER = Comparator.comparing(Candidate::type)
            .thenComparing(Comparator.comparingLong(Candidate::largestContinuousAreaBlocks).reversed())
            .thenComparing(Comparator.comparingLong(Candidate::areaBlocks).reversed())
            .thenComparing(Candidate::candidateId);

    private record Scope(String runId, String scopeType, String scopeId, int cellStepBlocks,
                         List<Candidate> candidates, List<Cell> scopeCells, String sourceIdentity,
                         List<String> sourceArtifacts, List<Bounds> occupied) {
    }

    private record Cell(int x, int z, int blockX, int blockZ, int step, String patchRef, String type,
                        String terrainType, String baseLandform, double confidence) {
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
