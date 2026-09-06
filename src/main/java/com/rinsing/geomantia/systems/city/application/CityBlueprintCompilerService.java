package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import com.rinsing.geomantia.systems.city.application.landuse.LandUseTerrainFieldCodec;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.domain.model.CityLandformReviewPackage;
import com.rinsing.geomantia.systems.city.domain.model.CityScale;
import com.rinsing.geomantia.systems.city.domain.model.LandformPatchSummary;
import com.rinsing.geomantia.systems.city.domain.model.PatchMemberCell;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compiles an accepted, coordinate-free Blueprint into the existing fixed-template D4 anchor contract. */
public final class CityBlueprintCompilerService {
    public static final String TRACE_SCHEMA = "city_generation_compile_trace";
    public static final String EXTENT_SCHEMA = "group_extent_map";
    private static final int INTERNAL_MAX_ANCHORS_PER_GROUP = 256;
    static final int MINIMUM_GROUP_SEPARATION_BLOCKS = 12;

    private final CityBlueprintCodec codec = new CityBlueprintCodec();
    private final CityBlueprintValidator validator = new CityBlueprintValidator();
    private final CityStructureArrayCandidatePlanner candidatePlanner = new CityStructureArrayCandidatePlanner();
    private final CityStructureArrayLayoutLoopPlanner continuousArrayPlanner =
            new CityStructureArrayLayoutLoopPlanner();
    private final CityBlueprintGroupLayoutPlanner groupLayoutPlanner = new CityBlueprintGroupLayoutPlanner();
    private final CityInternalStreetPlanner internalStreetPlanner = new CityInternalStreetPlanner();
    private final CityMainRoadPlanner mainRoadPlanner = new CityMainRoadPlanner();
    private final CityResidentialOverflowPlanner residentialOverflowPlanner =
            new CityResidentialOverflowPlanner();
    private final CityArrayVisualQualityGate arrayVisualQualityGate = new CityArrayVisualQualityGate();
    private final CityTemplateOrientationSolver orientationSolver = new CityTemplateOrientationSolver();
    private final CityLandscapeCapacityReservationPlanner landscapeCapacityPlanner =
            new CityLandscapeCapacityReservationPlanner();

    public CompilationResult compile(Path debugRoot, String runId, String cityId) throws IOException {
        Path runDir = requireRunDirectory(debugRoot, runId);
        Path blueprintDir = CityTestRunLayout.open(runDir, cityId)
                .stepDirectory(CityTestRunLayout.BLUEPRINT);
        JsonObject context = readObject(blueprintDir.resolve("city_blueprint_context.json"),
                "CITY_BLUEPRINT_CONTEXT_NOT_FOUND");
        JsonObject validation = readObject(blueprintDir.resolve("city_blueprint_validation_report.json"),
                "CITY_BLUEPRINT_VALIDATION_REPORT_NOT_FOUND");
        JsonObject submission = readObject(blueprintDir.resolve("city_blueprint_submission_trace.json"),
                "CITY_BLUEPRINT_SUBMISSION_TRACE_NOT_FOUND");
        Path blueprintPath = blueprintDir.resolve("city_blueprint.json");
        Path snapshotPath = blueprintDir.resolve("city_blueprint_catalog_snapshot.json");
        Path d3Path = resolveArtifact(debugRoot, requiredObject(context, "sourceD3Ref"));

        String contextId = string(context, "contextId");
        if (!CityBlueprintService.CONTEXT_SCHEMA.equals(string(context, "schema"))
                || !cityId.equals(string(context, "cityId"))
                || !contextId.equals(contextIdentity(context))
                || !contextId.equals(string(validation, "contextId"))
                || !contextId.equals(string(submission, "contextId"))
                || !booleanValue(validation, "valid", false)
                || !"accepted".equals(string(submission, "status"))) {
            throw fail("CITY_BLUEPRINT_NOT_ACCEPTED", "Blueprint validation/submission artifacts are not accepted.");
        }
        String blueprintRaw = requireFile(blueprintPath, "CITY_BLUEPRINT_NOT_FOUND");
        if (!sha256(blueprintRaw).equals(string(submission, "cityBlueprintHash"))) {
            throw fail("CITY_BLUEPRINT_STALE", "city_blueprint.json no longer matches its accepted submission trace.");
        }
        requireArtifactCurrent(debugRoot, requiredObject(context, "sourceD3Ref"), "CITY_BLUEPRINT_D3_STALE");
        JsonObject catalogSnapshotRef = requiredObject(context, "catalogSnapshotRef");
        if (!CityBlueprintService.SNAPSHOT_SCHEMA.equals(string(catalogSnapshotRef, "schema"))) {
            throw fail("CITY_BLUEPRINT_CATALOG_STALE", "Unsupported catalog snapshot reference schema.");
        }
        requireArtifactCurrent(debugRoot, catalogSnapshotRef,
                "CITY_BLUEPRINT_CATALOG_STALE");
        if (!snapshotPath.equals(resolveArtifact(debugRoot, catalogSnapshotRef))) {
            throw fail("CITY_BLUEPRINT_CATALOG_STALE", "Context catalog snapshot path is not the active city snapshot.");
        }

        JsonObject snapshot = normalizeLegacySchemasForRead(
                readObject(snapshotPath, "CITY_BLUEPRINT_CATALOG_STALE"));
        if (!CityBlueprintService.SNAPSHOT_SCHEMA.equals(string(snapshot, "schema"))) {
            throw fail("CITY_BLUEPRINT_CATALOG_STALE", "Unsupported catalog snapshot schema.");
        }
        JsonObject semanticCatalogJson = requiredObject(snapshot, "structureCatalog");
        if (!CityStructureProfileCatalog.SCHEMA.equals(string(semanticCatalogJson, "schema"))) {
            throw fail("CITY_BLUEPRINT_CATALOG_STALE", "Unsupported structure semantic catalog schema.");
        }
        JsonObject terrainFieldRef = requiredObject(snapshot, "terrainFieldRef");
        if (!LandUseTerrainField.SCHEMA.equals(string(terrainFieldRef, "schema"))) {
            throw fail("CITY_BLUEPRINT_TERRAIN_FIELD_STALE", "Unsupported D3 terrain field schema.");
        }
        requireArtifactCurrent(debugRoot, terrainFieldRef, "CITY_BLUEPRINT_TERRAIN_FIELD_STALE");
        LandUseTerrainField terrainField = new LandUseTerrainFieldCodec().fromJson(normalizeLegacySchemasForRead(
                readObject(resolveArtifact(debugRoot, terrainFieldRef), "CITY_BLUEPRINT_TERRAIN_FIELD_STALE")));
        JsonObject templateCatalogJson = requiredObject(snapshot, "templateCatalog");
        CityTemplateCatalog templates = new CityTemplateCatalogLoader().load(templateCatalogJson);
        CityBlueprintReferenceCatalog references = CityBlueprintReferenceCatalog.parse(
                requiredObject(snapshot, "referenceCatalog"), templates);
        JsonObject d3Json = normalizeLegacySchemasForRead(readObject(d3Path, "CITY_BLUEPRINT_D3_STALE"));
        CityLandformReviewPackage review = CityLandformReviewPackage.fromJson(d3Json);
        validateTerrainField(review, terrainField);
        CityStructureTerrainGate terrainGate = new CityStructureTerrainGate(terrainField, semanticCatalogJson);
        CityBlueprint blueprint = codec.read(normalizeLegacySchemasForRead(
                JsonParser.parseString(blueprintRaw).getAsJsonObject()));
        CityBlueprint.ArtifactRef expectedD3 = artifactRef(requiredObject(context, "sourceD3Ref"));
        CityBlueprint.ArtifactRef expectedCatalog = artifactRef(requiredObject(context, "catalogSnapshotRef"));
        CityBlueprintValidator.ValidationResult revalidation = validator.validate(blueprint,
                new CityBlueprintValidator.ExpectedContext(cityId, expectedD3, expectedCatalog,
                        patchRefs(review)), references);
        if (!revalidation.valid()) {
            String reason = revalidation.issues().isEmpty() ? "CITY_BLUEPRINT_REVALIDATION_FAILED"
                    : revalidation.issues().get(0).reasonCode().name();
            throw fail(reason, "Accepted Blueprint no longer passes compiler-entry validation.");
        }

        JsonObject structureSource = requiredObject(requiredObject(snapshot, "structureCatalog"), "source");
        CatalogIndex catalog = CatalogIndex.parse(requiredObject(snapshot, "referenceCatalog"),
                semanticCatalogJson);
        Map<String, LandformPatchSummary> patches = new LinkedHashMap<>();
        review.landformPatches().forEach(patch -> patches.put(patch.landformPatchId(), patch));
        List<CityBlueprint.Group> groups = orderGroups(blueprint.groups(), blueprint.relations(),
                blueprint.arrayCompositions(), catalog);
        Map<String, CityBlueprint.Group> groupsById = new LinkedHashMap<>();
        groups.forEach(group -> groupsById.put(group.groupId(), group));
        BlockBounds cityPlanningBounds = new BlockBounds(review.grid().blockMinX(), review.grid().blockMinZ(),
                review.grid().blockMaxX() - 1, review.grid().blockMaxZ() - 1);
        int buildingParcelMargin = catalog.foundationMargin(
                blueprint.outdoorPlan().foundationProfileRef());
        Map<String, CityGroupSpatialDemand> spatialDemands =
                planGroupSpatialDemands(groups, review.targetScale().scale(), review.grid().cellStepBlocks(),
                        catalog, templates, cityPlanningBounds, buildingParcelMargin);
        boolean hierarchicalRoadProfile = hierarchicalRoadProfile(blueprint, references);
        int interGroupRoadReserveBlocks = hierarchicalRoadProfile
                ? derivedMainRoadWidth(groups, catalog) + 2 : 0;
        Map<String, CompositionSlot> compositionSlots = planArrayCompositions(blueprint,
                groupsById, patches, review.grid().cellStepBlocks(), cityPlanningBounds, catalog,
                spatialDemands, interGroupRoadReserveBlocks);
        Map<String, Set<String>> groupSeparationExemptions = groupSeparationExemptions(blueprint);
        JsonArray anchors;
        JsonArray occupied;
        JsonArray selections;
        Map<String, GroupState> states = new LinkedHashMap<>();
        List<LandformPatchSummary> planningPatches = review.landformPatches().stream()
                .filter(patch -> !patch.memberCells().isEmpty())
                .sorted(Comparator.comparing(LandformPatchSummary::landformPatchId))
                .toList();
        Set<String> landscapeGapCirculationGroups = landscapeGapCirculationGroups(blueprint);
        for (CityBlueprint.Group group : groups) {
            if (group.groupKind() != CityBlueprint.GroupKind.STRUCTURE) {
                throw fail("CITY_BLUEPRINT_GROUP_KIND_UNSUPPORTED", "Only STRUCTURE groups compile in the current Blueprint pipeline.");
            }
            List<LandformPatchSummary> preferredPatches = new ArrayList<>();
            for (String patchRef : group.preferredPatchRefs()) {
                LandformPatchSummary patch = patches.get(patchRef);
                if (patch == null) {
                    throw fail("CITY_BLUEPRINT_PREFERRED_PATCH_UNKNOWN", "Unknown preferred patch: "
                            + patchRef);
                }
                preferredPatches.add(patch);
            }
            catalog.composition(group.compositionProfileRef());
            String algorithm = catalog.algorithm(group.algorithmProfileRef());
            List<LandformPatchSummary> connectionPatches = List.copyOf(planningPatches);
            CompositionSlot compositionSlot = compositionSlots.get(group.groupId());
            BlockBounds formationBounds = compositionSlot == null
                    ? cityPlanningBounds : compositionSlot.slotBounds();
            BlockPoint preferredOrigin = compositionSlot == null
                    ? patchPlacementOrigin(group, patches, review.grid().cellStepBlocks(), cityPlanningBounds)
                    : compositionSlot.placementOrigin();
            states.put(group.groupId(), new GroupState(group, preferredPatches, connectionPatches,
                    review.grid().cellStepBlocks(), cityPlanningBounds, formationBounds, preferredOrigin,
                    compositionSlot, algorithm,
                    groupLayoutPlanner.parameters(algorithm, group.densityClass()),
                    resolveConnectionConfiguration(group, catalog),
                    minimumGroupStructureCount(review.targetScale().scale(), group.extentClass(), algorithm),
                    groupSeparationExemptions.getOrDefault(group.groupId(), Set.of()),
                    spatialDemands.get(group.groupId()),
                    landscapeGapCirculationGroups.contains(group.groupId())));
        }

        Map<String, GroupState> initialStates = Map.copyOf(states);
        List<RequiredRequest> requiredRequests = new ArrayList<>();
        for (CityBlueprint.Group group : groups) {
            int ordinal = 0;
            for (String structureRef : orderedRequiredStructureRefs(group, catalog)) {
                requiredRequests.add(new RequiredRequest(group.groupId(), structureRef, ++ordinal));
            }
        }
        int[] ranks = new int[requiredRequests.size()];
        int[] limits = new int[requiredRequests.size()];
        int buildingSearchNodes = 0;
        while (true) {
            if (++buildingSearchNodes > CityLandscapeCapacityReservationPlanner.SEARCH_NODE_LIMIT) {
                JsonObject failureTrace = trace(blueprint, context, new JsonArray(), initialStates,
                        ConnectivityPlan.empty(), "failed", "CITY_BLUEPRINT_REQUIRED_STRUCTURE_SEARCH_LIMIT_EXHAUSTED");
                return CompilationResult.failed(failureTrace,
                        "CITY_BLUEPRINT_REQUIRED_STRUCTURE_SEARCH_LIMIT_EXHAUSTED",
                        "Required building search exhausted its 100000-node limit.");
            }
            states = freshStates(initialStates);
            anchors = new JsonArray();
            occupied = new JsonArray();
            selections = new JsonArray();
            boolean placed = true;
            for (int index = 0; index < requiredRequests.size(); index++) {
                RequiredRequest request = requiredRequests.get(index);
                GroupState state = states.get(request.groupId());
                state.beginExactSlotSearch(request.structureRef());
                Placement placement = chooseOne(runDir, review, structureSource, templateCatalogJson,
                        templates, blueprint, state, request.structureRef(), PlacementPhase.REQUIRED,
                        request.ordinal(), occupied, catalog, states, selections, null, terrainGate, ranks[index]);
                JsonObject event = selections.get(selections.size() - 1).getAsJsonObject();
                limits[index] = intValue(event, "candidateCount", 0);
                if (placement == null) {
                    // Later limits belong to a different prefix of committed choices.
                    java.util.Arrays.fill(limits, index + 1, limits.length, 0);
                    JsonObject terrainReasons = terrainFailureReasonCounts(event);
                    if (!terrainReasons.entrySet().isEmpty()) {
                        event.addProperty("status", "no_legal_candidate");
                        event.addProperty("reasonCode", "CITY_BLUEPRINT_SELECTED_PATCH_TERRAIN_UNFIT");
                        event.add("terrainFailureReasonCounts", terrainReasons.deepCopy());
                        state.recordTerrainPlacementFailure(request.structureRef(), terrainReasons);
                        state.stop("SELECTED_PATCH_TERRAIN_UNABLE_TO_SUPPORT_REQUIRED_STRUCTURE");
                    }
                    placed = false;
                    break;
                }
                commit(placement, anchors, occupied, state);
                if ("CENTER_SYMMETRIC".equals(state.layoutAlgorithm())
                        && state.requiredCount() == state.group().requiredStructureRefs().size()) {
                    List<String> pool = catalog.pool(state.group().fillPoolRef());
                    int cursor = 0;
                    while (state.internalStructureCount() < state.minimumStructureCount()) {
                        String structureRef = nextFillRef(pool, state.blockedRefs(), cursor++);
                        if (structureRef == null) {
                            state.stop("MINIMUM_FORMATION_GAP_RECORDED");
                            break;
                        }
                        List<Placement> pair = chooseCenterSymmetricPair(runDir, review, structureSource,
                                templateCatalogJson, templates, blueprint, state, structureRef,
                                state.anchorCount() + 1, occupied, catalog, states, selections, terrainGate);
                        if (pair.isEmpty()) {
                            state.blockRef(structureRef);
                            continue;
                        }
                        for (Placement member : pair) commit(member, anchors, occupied, state);
                    }
                }
            }
            if (placed) break;
            if (!incrementRanks(ranks, limits)) {
                String reason = "CITY_BLUEPRINT_REQUIRED_STRUCTURE_NO_LEGAL_PLACEMENT";
                JsonObject failureTrace = trace(blueprint, context, selections, states,
                        ConnectivityPlan.empty(), "failed", reason);
                return CompilationResult.failed(failureTrace, reason,
                        "All finite required building candidate combinations were exhausted.");
            }
        }
        ConnectivityPlan connectivityPlan = ConnectivityPlan.empty();

        // Street-first city planning: required/core buildings establish each district's frame, then
        // the shared main-road and internal street skeletons reserve the city blocks before any fill
        // building is allowed to occupy them.
        JsonArray preFillStreetSkeleton = streetSkeletonBands(states, anchors, catalog);
        List<JsonObject> requiredStageAnchors = anchors.asList().stream()
                .map(JsonElement::getAsJsonObject).toList();
        CityMainRoadPlanner.Result mainRoads = mainRoadPlanner.plan(blueprint, references, terrainField,
                requiredStageAnchors,
                preFillStreetSkeleton.asList().stream().map(JsonElement::getAsJsonObject).toList(),
                Set.of());
        if (!mainRoads.ok()) {
            JsonObject failureTrace = trace(blueprint, context, selections, states,
                    connectivityPlan, "failed", mainRoads.reasonCode());
            failureTrace.add("streetBands", preFillStreetSkeleton.deepCopy());
            failureTrace.add("cityMainRoadPlan", mainRoads.plan().deepCopy());
            return CompilationResult.failed(failureTrace, mainRoads.reasonCode(), mainRoads.message());
        }
        addRoadBandsToOccupied(preFillStreetSkeleton.asList().stream()
                .map(JsonElement::getAsJsonObject).toList(), occupied);
        addRoadBandsToOccupied(mainRoads.streetBands(), occupied);

        // Form each group with its own array before connection growth. Connection structures are
        // city stitching and must not substitute for the group's required/fill population.
        for (CityBlueprint.Group group : groups) {
            GroupState state = states.get(group.groupId());
            List<String> pool = catalog.pool(group.fillPoolRef());
            int cursor = 0;
            boolean centerSymmetric = "CENTER_SYMMETRIC".equals(state.layoutAlgorithm());
            if (centerSymmetric && state.internalStructureCount() == 0) {
                state.stop("CENTER_STRUCTURE_GAP_RECORDED");
                continue;
            }
            while (state.internalStructureCount() < state.minimumStructureCount()) {
                int requestedBatchSize = centerSymmetric ? 2 : 1;
                if (state.anchorCount() + requestedBatchSize > INTERNAL_MAX_ANCHORS_PER_GROUP) {
                    JsonObject failureTrace = trace(blueprint, context, selections, states,
                            connectivityPlan, "failed",
                            "CITY_BLUEPRINT_INTERNAL_SAFETY_LIMIT_REACHED");
                    return CompilationResult.failed(failureTrace,
                            "CITY_BLUEPRINT_INTERNAL_SAFETY_LIMIT_REACHED",
                            group.groupId() + " exceeded the compiler safety guard before reaching its spatial budget.");
                }
                String structureRef = nextFillRef(pool, state.blockedRefs(), cursor++);
                if (structureRef == null) {
                    state.stop("CONNECTED_SPACE_EXHAUSTED");
                    break;
                }
                List<Placement> placements;
                if (centerSymmetric) {
                    placements = chooseCenterSymmetricPair(runDir, review, structureSource,
                            templateCatalogJson, templates, blueprint, state, structureRef,
                            state.anchorCount() + 1, occupied, catalog, states, selections, terrainGate);
                } else {
                    state.beginExactSlotSearch(structureRef);
                    Placement placement = chooseOne(runDir, review, structureSource, templateCatalogJson,
                            templates, blueprint, state, structureRef, PlacementPhase.FILL,
                            state.anchorCount() + 1, occupied, catalog, states, selections, null, terrainGate);
                    placements = placement == null ? List.of() : List.of(placement);
                }
                if (placements.isEmpty()) {
                    state.blockRef(structureRef);
                    if (state.blockedRefs().containsAll(pool)) {
                        state.stop("CONNECTED_SPACE_EXHAUSTED");
                        break;
                    }
                    continue;
                }
                for (Placement placement : placements) commit(placement, anchors, occupied, state);
            }
            if (state.stopReason().isBlank()) {
                state.stop(state.internalStructureCount() >= state.minimumStructureCount()
                        ? "MINIMUM_FORMATION_REACHED" : "CONNECTED_SPACE_EXHAUSTED");
            }
            if (state.internalStructureCount() < state.minimumStructureCount()) {
                state.stop("MINIMUM_FORMATION_GAP_RECORDED");
            }
        }

        // Buildings form the first real footprint. Landscape starts are solved only after every
        // group has attempted its required and first fill batch, and never send those buildings
        // back through candidate ranking.
        CityLandscapeCapacityReservationPlanner.Result landscapeCapacity = landscapeCapacityPlanner.plan(
                blueprint, references, terrainField, anchors,
                CityLandscapeCapacityReservationPlanner.SEARCH_NODE_LIMIT);
        landscapeCapacity.plan().addProperty("buildingSearchNodeCount", buildingSearchNodes);
        CityLandscapeCapacityReservationPlanner.refreshPlanHash(landscapeCapacity.plan());
        addLandscapeCapacityToOccupied(landscapeCapacity.plan(), occupied);
        applyLandscapeFormationClaims(states, landscapeCapacity.plan());

        states.values().forEach(GroupState::freezeCoreExtent);
        // Function-area relations still own spatial growth. They consume the already-reserved road
        // skeleton as an obstacle; spatial adjacency never gets to invent another physical road.
        connectivityPlan = buildConnectivityPlan(blueprint, states);
        growConnectivity(runDir, review, structureSource,
                templateCatalogJson, templates, blueprint, states, occupied, anchors, selections,
                catalog, connectivityPlan, terrainGate);

        connectivityPlan.markBridgeDelegated(mainRoads.plan());

        // The percentage baseline is intentionally frozen only after every explicit relation has
        // reached handoff. Percentage fill is a second outward-growth phase, not the connection.
        JsonObject dynamicAreaPlan = freezeDynamicAreaTargets(
                blueprint, states, cityPlanningBounds, connectivityPlan);
        fillGroupsToDynamicTargets(runDir, review, structureSource, templateCatalogJson, templates, blueprint,
                groups, states, occupied, anchors, selections, catalog, terrainGate);

        // Landscape parcels are the landscapeShare side of the same frozen percentage
        // budget. Replan them after buildings finish so fields/tree belts/green/paths
        // consume the group's actual target instead of the old extent-class estimate.
        CityLandscapeCapacityReservationPlanner.Result percentageLandscapeCapacity =
                landscapeCapacityPlanner.plan(blueprint, references, terrainField, anchors,
                        CityLandscapeCapacityReservationPlanner.SEARCH_NODE_LIMIT,
                        desiredLandscapeParcelAreas(blueprint, states));
        if (percentageLandscapeCapacity.ok()) {
            landscapeCapacity = percentageLandscapeCapacity;
            landscapeCapacity.plan().addProperty("buildingSearchNodeCount", buildingSearchNodes);
            CityLandscapeCapacityReservationPlanner.refreshPlanHash(landscapeCapacity.plan());
            applyLandscapeFormationClaims(states, landscapeCapacity.plan());
            states.values().stream()
                    .filter(state -> "PREVIEW_RANGE_EXHAUSTED".equals(state.stopReason()))
                    .filter(state -> state.ownedAreaBlocks() >= state.targetAreaBlocks())
                    .forEach(state -> state.stop("PERCENTAGE_TARGET_REACHED_BY_LANDSCAPE"));
        } else {
            dynamicAreaPlan.addProperty("landscapePercentageTargetStatus", "GAP_RECORDED");
            dynamicAreaPlan.addProperty("landscapePercentageTargetReason",
                    percentageLandscapeCapacity.reasonCode());
        }
        recordDynamicAreaOutcomes(dynamicAreaPlan, states);

        String hardRelationFailure = hardRelationFailure(blueprint.relations(), states, connectivityPlan);
        if (!hardRelationFailure.isBlank()) {
            JsonObject failureTrace = trace(blueprint, context, selections, states,
                    connectivityPlan, "failed", "CITY_BLUEPRINT_HARD_RELATION_UNSATISFIED");
            return CompilationResult.failed(failureTrace, "CITY_BLUEPRINT_HARD_RELATION_UNSATISFIED",
                    hardRelationFailure);
        }

        refreshConnections(connectivityPlan, states);
        freezeBuildingParcelPlans(anchors, blueprint, catalog, cityPlanningBounds);
        JsonObject anchorPlan = new JsonObject();
        anchorPlan.addProperty("schema", CityStructureAnchorPlanner.PLAN_SCHEMA);
        anchorPlan.addProperty("cityId", cityId);
        anchorPlan.add("anchors", anchors);
        List<JsonObject> anchorObjects = anchors.asList().stream()
                .map(JsonElement::getAsJsonObject).toList();
        CityInternalStreetPlanner.Finalization streetFinalization = internalStreetPlanner.finalizeSkeleton(
                preFillStreetSkeleton.asList().stream().map(JsonElement::getAsJsonObject).toList(),
                mainRoads.streetBands(), anchorObjects);
        JsonArray streetBands = new JsonArray();
        streetFinalization.streetBands().forEach(streetBands::add);
        CityResidentialOverflowPlanner.Result residentialOverflow = residentialOverflowPlanner.plan(
                anchorObjects, streetBands.asList().stream().map(JsonElement::getAsJsonObject).toList());
        anchorPlan.add("streetBands", streetBands);
        anchorPlan.add("residentialOverflowPlan", residentialOverflow.plan().deepCopy());
        mainRoads.streetBands().forEach(streetBands::add);
        anchorPlan.add("cityMainRoadPlan", mainRoads.plan().deepCopy());
        anchorPlan.add("streetFirstNetworkTrace", streetFinalization.trace().deepCopy());
        CityArrayVisualQualityGate.Result arrayVisualQuality = arrayVisualQualityGate.evaluate(
                anchors, streetBands);
        anchorPlan.add("arrayVisualQuality", arrayVisualQuality.json().deepCopy());
        JsonObject compilationAcceptance = compilationAcceptance(
                blueprint, states, connectivityPlan, arrayVisualQuality, streetFinalization.trace(), mainRoads.plan());
        anchorPlan.add("compilationAcceptance", compilationAcceptance.deepCopy());
        if (!arrayVisualQuality.passed()) {
            anchorPlan.addProperty("arrayVisualGapRecorded", true);
        }
        JsonObject provenance = new JsonObject();
        provenance.addProperty("schema", TRACE_SCHEMA);
        provenance.addProperty("generationSeed", blueprint.generationSeed());
        provenance.addProperty("selectionMode", "programmatic_blueprint_compiler");
        provenance.addProperty("contextId", contextId);
        provenance.addProperty("sourceBlueprintHash", sha256(blueprintRaw));
        anchorPlan.add("cityBlueprintCompileProvenance", provenance);
        landscapeCapacity.plan().addProperty("sourceD4Hash", sha256(CityJson.GSON.toJson(anchorPlan)));
        CityLandscapeCapacityReservationPlanner.refreshPlanHash(landscapeCapacity.plan());
        JsonObject compileTrace = trace(blueprint, context, selections, states,
                connectivityPlan, "compiled", "");
        compileTrace.add("streetBands", streetBands.deepCopy());
        compileTrace.add("cityMainRoadPlan", mainRoads.plan().deepCopy());
        compileTrace.add("streetFirstNetworkTrace", streetFinalization.trace().deepCopy());
        compileTrace.add("residentialOverflowPlan", residentialOverflow.plan().deepCopy());
        compileTrace.add("arrayVisualQuality", arrayVisualQuality.json().deepCopy());
        compileTrace.addProperty("arrayVisualGapRecorded", !arrayVisualQuality.passed());
        compileTrace.add("compilationAcceptance", compilationAcceptance.deepCopy());
        JsonObject functionAreaFormationPlan = functionAreaFormationPlan(states, cityPlanningBounds);
        compileTrace.add("functionAreaFormationPlan", functionAreaFormationPlan.deepCopy());
        compileTrace.add("landscapeCapacityReservationPlan", landscapeCapacity.plan().deepCopy());
        compileTrace.add("dynamicAreaPlan", dynamicAreaPlan.deepCopy());
        JsonObject extentMap = extentMap(blueprint, states, connectivityPlan, functionAreaFormationPlan);
        return CompilationResult.compiled(anchorPlan, compileTrace, extentMap, structureSource,
                templateCatalogJson, landscapeCapacity.plan());
    }

    private JsonObject freezeDynamicAreaTargets(CityBlueprint blueprint,
                                                Map<String, GroupState> states,
                                                BlockBounds planningBounds,
                                                ConnectivityPlan connectivityPlan) {
        GroupState highest = states.values().stream()
                .filter(state -> state.group().priority() == CityBlueprint.GroupPriority.CORE)
                .findFirst()
                .orElseThrow(() -> fail("CITY_BLUEPRINT_GROUP_PRIORITY_HIGHEST_COUNT_INVALID",
                        "Exactly one CORE group is required before dynamic area expansion."));
        // Freeze only area that D4 actually assigned through committed structures/connection growth.
        // Template/array demand is layout guidance, not owned function-area space.
        int highestOwnedArea = Math.max(0, highest.ownedAreaBlocks());
        long referenceArea = highestOwnedArea == 0 ? 0L
                : (long) Math.ceil(highestOwnedArea / highest.group().targetAreaShare());
        int previewArea = (int) Math.min(Integer.MAX_VALUE,
                Math.max(1L, (long) planningBounds.widthBlocks() * planningBounds.heightBlocks()));
        referenceArea = Math.min(referenceArea, previewArea);
        JsonObject plan = new JsonObject();
        plan.addProperty("policy", "FREEZE_UNIQUE_HIGHEST_PRIORITY_AREA_THEN_DERIVE_GROUP_TARGETS");
        plan.addProperty("stageOrder",
                "INITIAL_FORMATION_THEN_RELATION_GROWTH_THEN_FREEZE_THEN_PERCENTAGE_FILL");
        plan.addProperty("relationGrowthCompletedBeforeFreeze", connectivityPlan.links.stream()
                .allMatch(ConnectivityLink::satisfied));
        plan.addProperty("relationEdgeCountAtFreeze", connectivityPlan.links.size());
        plan.addProperty("relationStructureCountAtFreeze", connectivityPlan.links.stream()
                .mapToInt(link -> link.connectionStructureCount).sum());
        plan.addProperty("highestPriorityGroupId", highest.group().groupId());
        plan.addProperty("frozenHighestPriorityAreaBlocks", highestOwnedArea);
        plan.addProperty("frozenAreaSource", "ACTUAL_COMMITTED_OWNED_AND_CONNECTION_AREA");
        plan.addProperty("referenceCityAreaBlocks", referenceArea);
        plan.add("previewBounds", CityStructureCandidateEnvelope.boundsJson(planningBounds));
        JsonArray groupPlans = new JsonArray();
        for (GroupState state : states.values()) {
            int target = (int) Math.min(Integer.MAX_VALUE,
                    Math.round(referenceArea * state.group().targetAreaShare()));
            if (state.group().priority() == CityBlueprint.GroupPriority.CORE) {
                target = highestOwnedArea;
            }
            if (!state.group().expansionPolicy().stopWhenTargetReached()) target = previewArea;
            state.setDynamicTargetAreaBlocks(target);
            state.beginAreaExpansion();
            JsonObject value = new JsonObject();
            value.addProperty("groupId", state.group().groupId());
            value.addProperty("targetAreaShare", state.group().targetAreaShare());
            value.addProperty("targetAreaBlocks", state.targetAreaBlocks());
            value.addProperty("buildingTargetAreaBlocks", state.buildingTargetAreaBlocks());
            value.addProperty("landscapeTargetAreaBlocks", state.landscapeTargetAreaBlocks());
            value.addProperty("minimumAreaBlocks", state.spatialDemand().minimumAreaBlocks());
            value.addProperty("areaGapBeforeExpansion", Math.max(0,
                    state.targetAreaBlocks() - state.ownedAreaBlocks()));
            groupPlans.add(value);
        }
        plan.add("groups", groupPlans);
        return plan;
    }

    private static void recordDynamicAreaOutcomes(JsonObject plan,
                                                  Map<String, GroupState> states) {
        JsonArray outcomes = new JsonArray();
        states.values().forEach(state -> {
            JsonObject value = new JsonObject();
            value.addProperty("groupId", state.group().groupId());
            value.addProperty("targetAreaBlocks", state.targetAreaBlocks());
            value.addProperty("actualOwnedAreaBlocks", state.ownedAreaBlocks());
            value.addProperty("remainingAreaGapBlocks", Math.max(0,
                    state.targetAreaBlocks() - state.ownedAreaBlocks()));
            value.addProperty("stopReason", state.stopReason());
            outcomes.add(value);
        });
        plan.add("outcomes", outcomes);
    }

    private static JsonObject compilationAcceptance(CityBlueprint blueprint,
                                                    Map<String, GroupState> states,
                                                    ConnectivityPlan connectivityPlan,
                                                    CityArrayVisualQualityGate.Result visualQuality,
                                                    JsonObject streetFirstNetworkTrace, JsonObject mainRoadPlan) {
        JsonArray hardBlocks = new JsonArray();
        Set<String> trafficGroupIds = states.values().stream()
                .filter(state -> state.group().groupKind() == CityBlueprint.GroupKind.STRUCTURE)
                .filter(state -> state.group().expansionPolicy().allowRelationConnection())
                .map(state -> state.group().groupId())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> relatedGroupIds = new LinkedHashSet<>();
        blueprint.relations().forEach(relation -> {
            relatedGroupIds.add(relation.fromGroupId());
            relatedGroupIds.add(relation.toGroupId());
        });
        CityTrafficConnectivity traffic = new CityTrafficConnectivity(mainRoadPlan);
        boolean graphConnected = !trafficGroupIds.isEmpty()
                && trafficGroupIds.stream().allMatch(groupId -> states.get(groupId).anchorCount() > 0)
                && traffic.connected(trafficGroupIds);
        long trafficConnectionCount = blueprint.relations().stream()
                .filter(relation -> relation.relationKind() == CityBlueprint.RelationKind.CONNECTION)
                .filter(relation -> trafficGroupIds.contains(relation.fromGroupId())
                        && trafficGroupIds.contains(relation.toGroupId()))
                .count();
        if (trafficGroupIds.size() > 1 && trafficConnectionCount == 0) {
            hardBlocks.add("CITY_MAIN_ROAD_CONNECTION_REQUIRED trafficGroups=" + trafficGroupIds.size());
        }
        if (states.size() > 1) states.values().stream()
                .filter(state -> state.group().expansionPolicy().allowRelationConnection())
                .filter(state -> !relatedGroupIds.contains(state.group().groupId()))
                .forEach(state -> hardBlocks.add(state.group().groupId()
                        + ": FUNCTION_AREA_RELATION_UNSPECIFIED"));
        JsonArray warnings = new JsonArray();
        connectivityPlan.links.stream()
                .filter(link -> !"CONNECTION".equals(link.sourceReason))
                .filter(link -> !link.satisfied())
                .forEach(link -> {
                    String message = link.describe() + ": CONNECTION_SKIPPED_TERRAIN_BLOCKED status=" + link.status;
                    if (link.required) hardBlocks.add(message);
                    else warnings.add(message);
                });
        blueprint.relations().stream()
                .filter(relation -> relation.relationKind() == CityBlueprint.RelationKind.CONNECTION)
                .filter(relation -> !traffic.directlyConnected(relation.fromGroupId(), relation.toGroupId()))
                .forEach(relation -> {
                    String message = relation.fromGroupId() + " -> " + relation.toGroupId() + ": CITY_MAIN_ROAD_CONNECTION_UNAVAILABLE";
                    if (relation.strength() == CityBlueprint.RelationStrength.HARD) hardBlocks.add(message);
                    else warnings.add(message);
                });
        if (trafficGroupIds.size() > 1 && !graphConnected) {
            hardBlocks.add("STRUCTURE_RELATION_GRAPH_DISCONNECTED trafficGroups=" + trafficGroupIds.size());
        }
        states.values().stream()
                .filter(state -> state.anchorCount() == 0)
                .forEach(state -> hardBlocks.add(state.group().groupId() + ": FUNCTION_AREA_EMPTY"));
        states.values().stream()
                .filter(GroupState::hasTerrainPlacementFailures)
                .forEach(state -> warnings.add(state.group().groupId()
                        + ": SELECTED_PATCH_TERRAIN_UNABLE_TO_SUPPORT_REQUIRED_STRUCTURE"));
        states.values().forEach(state -> state.missingRequiredStructures().forEach(gap -> {
            warnings.add(state.group().groupId() + ": REQUIRED_PHASE_STRUCTURE_MISSING structureRef="
                    + gap.structureRef() + " missingCount=" + gap.missingCount());
        }));
        states.values().forEach(state -> state.missingRequiredContent().forEach(gap -> {
            String message = state.group().groupId() + ": REQUIRED_STRUCTURE_MISSING structureRef="
                    + gap.structureRef() + " missingCount=" + gap.missingCount();
            hardBlocks.add(message);
        }));
        JsonArray streetAccessOutcomes = array(streetFirstNetworkTrace, "accessOutcomes");
        streetAccessOutcomes.asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(outcome -> "UNRESOLVED".equals(string(outcome, "status")))
                .forEach(outcome -> warnings.add(string(outcome, "entranceId")
                        + ": STREET_ENTRANCE_UNRESOLVED reasonCode="
                        + string(outcome, "reasonCode")));
        visualQuality.hardBlocks().forEach(hardBlocks::add);
        visualQuality.warnings().forEach(warnings::add);

        JsonObject acceptance = new JsonObject();
        acceptance.addProperty("passed", hardBlocks.isEmpty());
        acceptance.addProperty("acceptancePolicy", "SAFETY_AND_REQUIRED_CONTENT_V1");
        acceptance.addProperty("qualityFullySatisfied", hardBlocks.isEmpty() && warnings.isEmpty());
        acceptance.addProperty("previewCompiled", true);
        acceptance.addProperty("trafficGroupCount", trafficGroupIds.size());
        acceptance.addProperty("trafficConnectionCount", trafficConnectionCount);
        acceptance.addProperty("requiredRelationCount", connectivityPlan.links.stream()
                .filter(link -> link.required && !"CONNECTION".equals(link.sourceReason)).count()
                + blueprint.relations().stream().filter(relation -> relation.strength() == CityBlueprint.RelationStrength.HARD
                        && relation.relationKind() == CityBlueprint.RelationKind.CONNECTION).count());
        acceptance.addProperty("requiredRelationsSatisfied", connectivityPlan.links.stream()
                .filter(link -> link.required && !"CONNECTION".equals(link.sourceReason)).allMatch(ConnectivityLink::satisfied)
                && blueprint.relations().stream()
                .filter(relation -> relation.strength() == CityBlueprint.RelationStrength.HARD
                        && relation.relationKind() == CityBlueprint.RelationKind.CONNECTION)
                .allMatch(relation -> traffic.directlyConnected(relation.fromGroupId(), relation.toGroupId())));
        acceptance.addProperty("structureGraphConnected", graphConnected);
        acceptance.addProperty("allFunctionAreasFormed",
                states.values().stream().allMatch(state -> state.anchorCount() > 0));
        acceptance.addProperty("allRequiredStructuresCommitted",
                states.values().stream().allMatch(state -> state.missingRequiredStructures().isEmpty()));
        acceptance.addProperty("allRequiredContentPresent",
                states.values().stream().allMatch(state -> state.missingRequiredContent().isEmpty()));
        acceptance.addProperty("arrayVisualGeometryPassed", visualQuality.passed());
        acceptance.addProperty("allStreetEntrancesConnected", streetAccessOutcomes.asList().stream()
                .map(JsonElement::getAsJsonObject)
                .noneMatch(outcome -> "UNRESOLVED".equals(string(outcome, "status"))));
        acceptance.add("hardBlocks", hardBlocks);
        acceptance.add("warnings", warnings);
        return acceptance;
    }

    private static void freezeBuildingParcelPlans(JsonArray anchors,
                                                  CityBlueprint blueprint,
                                                  CatalogIndex catalog,
                                                  BlockBounds planningBounds) {
        Map<String, CityBlueprint.Group> groups = new LinkedHashMap<>();
        blueprint.groups().forEach(group -> groups.put(group.groupId(), group));
        List<BlockBounds> hardCollisions = anchors.asList().stream()
                .map(JsonElement::getAsJsonObject)
                .map(anchor -> bounds(requiredObject(anchor, "collisionEnvelope")))
                .toList();
        List<BlockBounds> claimedParcels = new ArrayList<>();
        Map<String, Integer> groupOrdinals = new HashMap<>();
        int margin = catalog.foundationMargin(blueprint.outdoorPlan().foundationProfileRef());
        for (int index = 0; index < anchors.size(); index++) {
            JsonObject anchor = anchors.get(index).getAsJsonObject();
            String groupId = string(anchor, "placementGroupId");
            CityBlueprint.Group group = groups.get(groupId);
            if (group == null) continue;
            BlockBounds footprint = bounds(anchor.has("actualFootprint")
                    ? requiredObject(anchor, "actualFootprint")
                    : requiredObject(anchor, "plannedFootprint"));
            BlockBounds collision = hardCollisions.get(index);
            // The parcel is the building's complete lot, not a second name for its NBT
            // footprint.  Keep the template's mandatory clearance as the hard inner core
            // and reserve the Foundation margin outside that core for paving/greenery.
            BlockBounds preferred = CityStructureMaterializationPlanner.expand(collision, margin);
            BlockBounds resolved = intersect(preferred, planningBounds);
            boolean compressed = !preferred.equals(resolved);
            for (int other = 0; other < hardCollisions.size(); other++) {
                if (other == index) continue;
                BlockBounds before = resolved;
                resolved = shrinkAround(resolved, collision, hardCollisions.get(other));
                compressed |= !before.equals(resolved);
            }
            for (BlockBounds claimed : claimedParcels) {
                BlockBounds before = resolved;
                resolved = shrinkAround(resolved, collision, claimed);
                compressed |= !before.equals(resolved);
            }
            claimedParcels.add(resolved);

            int ordinal = groupOrdinals.merge(groupId, 1, Integer::sum) - 1;
            int slotIndex = anchor.has("blueprintLayout")
                    ? intValue(requiredObject(anchor, "blueprintLayout"), "slotIndex", ordinal) : ordinal;
            GreenCapability capability = catalog.greenCapability(string(anchor, "blueprintStructureRef"));
            CityBlueprint.BuildingGreeneryPolicy policy = group.buildingGreeneryPolicy();
            boolean coverageSelected = capability != null && coverageSelected(policy.coverage(),
                    blueprint.generationSeed(), groupId, slotIndex);
            int usableGreenCells = Math.max(0, area(resolved) - intersectionArea(resolved, collision));
            boolean greenerySelected = coverageSelected && usableGreenCells >= 6;
            String greeneryStatus = capability == null ? "TEMPLATE_UNSUPPORTED"
                    : policy.coverage() == CityBlueprint.GreeneryCoverage.NONE ? "POLICY_NONE"
                    : !coverageSelected ? "COVERAGE_NOT_SELECTED"
                    : usableGreenCells < 6 ? "INSUFFICIENT_SPACE_SKIPPED"
                    : "SELECTED";

            JsonObject plan = new JsonObject();
            plan.addProperty("schema", "city_building_parcel_plan");
            plan.addProperty("planningStage", "D4_BEFORE_ARRAY_COMMIT");
            plan.addProperty("collisionPolicy", "HARD_STRUCTURE_SOFT_COMPRESSIBLE_PARCEL");
            plan.addProperty("marginBlocks", margin);
            plan.add("preferredBounds", CityStructureCandidateEnvelope.boundsJson(preferred));
            plan.add("resolvedBounds", CityStructureCandidateEnvelope.boundsJson(resolved));
            plan.add("hardCollisionEnvelope", CityStructureCandidateEnvelope.boundsJson(collision));
            plan.addProperty("parcelStatus", compressed ? "COMPRESSED" : "FULL");
            plan.addProperty("greenerySelected", greenerySelected);
            plan.addProperty("greeneryStatus", greeneryStatus);
            plan.addProperty("usableGreenCells", usableGreenCells);
            if (greenerySelected) {
                plan.addProperty("greeneryPattern", resolvedPattern(policy.patternPreference(),
                        capability.pattern(), blueprint.generationSeed(), groupId, slotIndex).name());
                plan.addProperty("greeneryDensity", resolvedDensity(policy.densityPreference(),
                        capability.density()).name());
            }
            anchor.add("buildingParcelPlan", plan);
        }
    }

    private static boolean coverageSelected(CityBlueprint.GreeneryCoverage coverage, long seed,
                                            String groupId, int slotIndex) {
        if (coverage == CityBlueprint.GreeneryCoverage.NONE) return false;
        int phase = Math.floorMod(groupId.hashCode() ^ (int) seed, 4);
        int bucket = Math.floorMod(slotIndex + phase, 4);
        return switch (coverage) {
            case NONE -> false;
            case SPARSE -> bucket == 0;
            case BALANCED -> (bucket & 1) == 0;
            case LUSH -> bucket != 3;
        };
    }

    private static CityBlueprintReferenceCatalog.GreenParcelPattern resolvedPattern(
            CityBlueprint.GreeneryPatternPreference preference,
            CityBlueprintReferenceCatalog.GreenParcelPattern fallback,
            long seed, String groupId, int slotIndex) {
        return switch (preference) {
            case TEMPLATE_DEFAULT -> fallback;
            case FREEFORM -> CityBlueprintReferenceCatalog.GreenParcelPattern.FREEFORM;
            case FIELD_GRID -> CityBlueprintReferenceCatalog.GreenParcelPattern.FIELD_GRID;
            case MIXED -> ((slotIndex + Math.floorMod(groupId.hashCode() ^ (int) seed, 2)) & 1) == 0
                    ? CityBlueprintReferenceCatalog.GreenParcelPattern.FREEFORM
                    : CityBlueprintReferenceCatalog.GreenParcelPattern.FIELD_GRID;
        };
    }

    private static CityBlueprintReferenceCatalog.GreenParcelDensity resolvedDensity(
            CityBlueprint.GreeneryDensityPreference preference,
            CityBlueprintReferenceCatalog.GreenParcelDensity fallback) {
        return switch (preference) {
            case TEMPLATE_DEFAULT -> fallback;
            case LOW -> CityBlueprintReferenceCatalog.GreenParcelDensity.LOW;
            case MEDIUM -> CityBlueprintReferenceCatalog.GreenParcelDensity.MEDIUM;
            case HIGH -> CityBlueprintReferenceCatalog.GreenParcelDensity.HIGH;
        };
    }

    private static BlockBounds intersect(BlockBounds left, BlockBounds right) {
        return new BlockBounds(Math.max(left.minX(), right.minX()), Math.max(left.minZ(), right.minZ()),
                Math.min(left.maxX(), right.maxX()), Math.min(left.maxZ(), right.maxZ()));
    }

    private static BlockBounds shrinkAround(BlockBounds parcel, BlockBounds footprint, BlockBounds obstacle) {
        if (!parcel.overlaps(obstacle) || footprint.overlaps(obstacle)) return parcel;
        int minX = parcel.minX();
        int minZ = parcel.minZ();
        int maxX = parcel.maxX();
        int maxZ = parcel.maxZ();
        if (obstacle.maxX() < footprint.minX()) minX = Math.max(minX, obstacle.maxX() + 1);
        else if (obstacle.minX() > footprint.maxX()) maxX = Math.min(maxX, obstacle.minX() - 1);
        else if (obstacle.maxZ() < footprint.minZ()) minZ = Math.max(minZ, obstacle.maxZ() + 1);
        else if (obstacle.minZ() > footprint.maxZ()) maxZ = Math.min(maxZ, obstacle.minZ() - 1);
        return new BlockBounds(minX, minZ, maxX, maxZ);
    }

    private static int intersectionArea(BlockBounds left, BlockBounds right) {
        int width = Math.min(left.maxX(), right.maxX()) - Math.max(left.minX(), right.minX()) + 1;
        int depth = Math.min(left.maxZ(), right.maxZ()) - Math.max(left.minZ(), right.minZ()) + 1;
        return width <= 0 || depth <= 0 ? 0 : width * depth;
    }

    private static Map<String, Integer> desiredLandscapeParcelAreas(CityBlueprint blueprint,
                                                                      Map<String, GroupState> states) {
        Map<String, Integer> landscapeCountsByGroup = new LinkedHashMap<>();
        for (CityBlueprint.Landscape landscape : blueprint.outdoorPlan().landscapes()) {
            if (landscape.required() && landscape.owner() != null) {
                landscapeCountsByGroup.merge(landscape.owner().groupId(), 1, Integer::sum);
            }
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        for (CityBlueprint.Landscape landscape : blueprint.outdoorPlan().landscapes()) {
            if (!landscape.required() || landscape.owner() == null) continue;
            GroupState state = states.get(landscape.owner().groupId());
            int landscapeCount = Math.max(1, landscapeCountsByGroup.getOrDefault(
                    landscape.owner().groupId(), 1));
            if (state == null) continue;
            // Composition shares guide the building phase, but a required landscape is also the
            // group's legal remainder carrier. Once the configured buildings have stopped, grow
            // that landscape through every still-unclaimed target block (for example: one
            // windmill + two houses, then all remaining agriculture area becomes farmland).
            double configuredLandscapeArea = state.targetAreaBlocks()
                    * state.group().spaceComposition().landscapeShare();
            double remainingAreaAfterStructures = Math.max(0,
                    state.targetAreaBlocks() - state.spatialDemandBlocks());
            double totalLandscapeArea = Math.max(configuredLandscapeArea,
                    remainingAreaAfterStructures);
            double perParcel = totalLandscapeArea / landscapeCount
                    / Math.max(1, landscape.instanceCount())
                    / Math.max(1, landscape.parcelCount());
            result.put(landscape.landscapeId(), Math.max(1, (int) Math.ceil(perParcel)));
        }
        return result;
    }

    private static void applyLandscapeFormationClaims(Map<String, GroupState> states, JsonObject plan) {
        Map<String, List<BlockBounds>> spansByGroup = new LinkedHashMap<>();
        Map<String, Integer> areaByGroup = new LinkedHashMap<>();
        for (JsonElement element : array(plan, "instances")) {
            if (!element.isJsonObject()) continue;
            JsonObject instance = element.getAsJsonObject();
            String groupId = string(instance, "ownerGroupId");
            if (!states.containsKey(groupId)) continue;
            areaByGroup.merge(groupId, intValue(instance, "actualAreaBlocks", 0), Integer::sum);
            List<BlockBounds> spans = spansByGroup.computeIfAbsent(groupId, ignored -> new ArrayList<>());
            for (JsonElement spanElement : array(instance, "reservationSpans")) {
                if (spanElement.isJsonObject()) spans.add(landscapeSpanBounds(spanElement.getAsJsonObject()));
            }
        }
        states.forEach((groupId, state) -> state.setLandscapeFormationClaims(
                spansByGroup.getOrDefault(groupId, List.of()), areaByGroup.getOrDefault(groupId, 0)));
    }

    private static BlockBounds landscapeSpanBounds(JsonObject span) {
        int minX = intValue(span, "minX", intValue(span, "x", 0));
        int maxX = intValue(span, "maxX", minX);
        int minZ = intValue(span, "minZ", intValue(span, "z", 0));
        int maxZ = intValue(span, "maxZ", minZ);
        return new BlockBounds(minX, minZ, maxX, maxZ);
    }

    private void fillGroupsToDynamicTargets(Path runDir,
                                             CityLandformReviewPackage review,
                                             JsonObject structureSource,
                                             JsonObject templateCatalogJson,
                                             CityTemplateCatalog templates,
                                             CityBlueprint blueprint,
                                             List<CityBlueprint.Group> groups,
                                             Map<String, GroupState> states,
                                             JsonArray occupied,
                                             JsonArray anchors,
                                             JsonArray selections,
                                             CatalogIndex catalog,
                                             CityStructureTerrainGate terrainGate) throws IOException {
        for (CityBlueprint.Group group : groups) {
            GroupState state = states.get(group.groupId());
            List<String> pool = catalog.pool(group.fillPoolRef());
            int cursor = 0;
            boolean centerSymmetric = "CENTER_SYMMETRIC".equals(state.layoutAlgorithm());
            if (centerSymmetric && state.internalStructureCount() == 0) {
                state.stop("CENTER_STRUCTURE_GAP_RECORDED");
                continue;
            }
            while (state.internalStructureCount() < state.minimumStructureCount()
                    || state.internalSpatialDemandBlocks() < state.buildingTargetAreaBlocks()) {
                if (!group.expansionPolicy().allowOutwardExpansion()
                        && state.internalStructureCount() >= state.minimumStructureCount()) {
                    state.stop("EXPANSION_DISABLED_AREA_GAP");
                    break;
                }
                int requestedBatchSize = centerSymmetric ? 2 : 1;
                if (state.anchorCount() + requestedBatchSize > INTERNAL_MAX_ANCHORS_PER_GROUP) {
                    state.stop("EXPANSION_SAFETY_LIMIT_REACHED");
                    break;
                }
                if (pool.isEmpty()) {
                    state.stop("PREVIEW_RANGE_EXHAUSTED");
                    break;
                }
                List<Placement> placements;
                if (centerSymmetric) {
                    ConnectionBatch batch = choosePercentageExpansionBatch(runDir, review, structureSource,
                            templateCatalogJson, templates, blueprint, state, occupied, catalog, states,
                            selections, terrainGate, requestedBatchSize);
                    placements = batch == null ? List.of() : batch.placements();
                } else {
                    String structureRef = nextFillRef(pool, Set.of(), cursor++);
                    state.beginExactSlotSearch(structureRef);
                    Placement placement = chooseOne(runDir, review, structureSource, templateCatalogJson,
                            templates, blueprint, state, structureRef, PlacementPhase.PERCENTAGE,
                            state.anchorCount() + 1, occupied, catalog, states, selections, null, terrainGate);
                    placements = placement == null ? List.of() : List.of(placement);
                }
                if (placements.isEmpty()) {
                    state.stop("PREVIEW_RANGE_EXHAUSTED");
                    break;
                }
                for (Placement placement : placements) commit(placement, anchors, occupied, state);
                state.advancePercentageCursor(placements.size());
            }
            if (state.stopReason().isBlank()) {
                state.stop(state.internalSpatialDemandBlocks() >= state.buildingTargetAreaBlocks()
                        ? "BUILDING_SHARE_TARGET_REACHED" : "PREVIEW_RANGE_EXHAUSTED");
            }
        }
    }

    public JsonObject persist(Path debugRoot, String runId, String cityId, CompilationResult result)
            throws IOException {
        Path runDir = requireRunDirectory(debugRoot, runId);
        Path output = CityTestRunLayout.open(runDir, cityId).stepDirectory(CityTestRunLayout.D4);
        Files.createDirectories(output);
        Path tracePath = output.resolve("city_generation_compile_trace.json");
        writeAtomic(tracePath, result.compileTrace());
        JsonObject response = new JsonObject();
        response.addProperty("ok", result.ok());
        response.addProperty("status", result.ok() ? "compiled" : "failed");
        if (!result.reasonCode().isBlank()) response.addProperty("reasonCode", result.reasonCode());
        if (!result.message().isBlank()) response.addProperty("message", result.message());
        response.add("cityGenerationCompileTrace", result.compileTrace().deepCopy());
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("cityGenerationCompileTrace", ref(debugRoot, tracePath));
        if (result.ok()) {
            Path extentPath = output.resolve("group_extent_map.json");
            writeAtomic(extentPath, result.groupExtentMap());
            artifacts.addProperty("groupExtentMap", ref(debugRoot, extentPath));
            response.add("groupExtentMap", result.groupExtentMap().deepCopy());
            Path landscapePath = output.resolve("city_landscape_capacity_reservation_plan.json");
            writeAtomic(landscapePath, result.landscapeCapacityReservationPlan());
            artifacts.addProperty("landscapeCapacityReservationPlan", ref(debugRoot, landscapePath));
            response.add("landscapeCapacityReservationPlan",
                    result.landscapeCapacityReservationPlan().deepCopy());
        }
        response.add("artifacts", artifacts);
        return response;
    }

    private Placement chooseOne(Path runDir,
                                CityLandformReviewPackage review,
                                JsonObject structureSource,
                                JsonObject templateCatalog,
                                CityTemplateCatalog templates,
                                CityBlueprint blueprint,
                                GroupState state,
                                String structureRef,
                                PlacementPhase phase,
                                int ordinal,
                                JsonArray occupied,
                                 CatalogIndex catalog,
                                 Map<String, GroupState> states,
                                 JsonArray selections,
                                 GroupState connectivityTarget,
                                 CityStructureTerrainGate terrainGate) throws IOException {
        return chooseOne(runDir, review, structureSource, templateCatalog, templates, blueprint, state,
                structureRef, phase, ordinal, occupied, catalog, states, selections, connectivityTarget,
                terrainGate, 0);
    }

    private Placement chooseOne(Path runDir,
                                CityLandformReviewPackage review,
                                JsonObject structureSource,
                                JsonObject templateCatalog,
                                CityTemplateCatalog templates,
                                CityBlueprint blueprint,
                                GroupState state,
                                String structureRef,
                                PlacementPhase phase,
                                int ordinal,
                                JsonArray occupied,
                                CatalogIndex catalog,
                                Map<String, GroupState> states,
                                JsonArray selections,
                                GroupState connectivityTarget,
                                CityStructureTerrainGate terrainGate,
                                int choiceRank) throws IOException {
        boolean required = phase == PlacementPhase.REQUIRED;
        List<CandidateChoice> choices = new ArrayList<>();
        JsonArray attempts = new JsonArray();
        JsonObject compilerFilterReasons = new JsonObject();
        List<TemplateCandidate> candidates = new ArrayList<>(catalog.templates(structureRef));
        candidates.sort(Comparator.comparingLong(candidate -> tieKey(blueprint.generationSeed(),
                state.group().groupId(), structureRef, candidate.templateId(), candidate.variantId())));
        List<PatchScope> patchScopes = new ArrayList<>();
        patchScopes.add(new PatchScope(state.initialPatchSelectionScope(), null));
        for (PatchScope patchScope : patchScopes) {
            for (TemplateCandidate template : candidates) {
                JsonObject plan = candidatePlan(blueprint, state, structureRef, phase, ordinal,
                        template, templateCatalog, templates, states, connectivityTarget,
                        patchScope.patches(), patchScope.name());
                if (plan.has("frontageSatisfied")
                        && !plan.get("frontageSatisfied").getAsBoolean()) {
                    JsonObject attempt = new JsonObject();
                    attempt.addProperty("templateId", template.templateId());
                    attempt.addProperty("variantId", template.variantId());
                    attempt.addProperty("passed", false);
                    attempt.addProperty("candidateCount", 0);
                    JsonObject reasons = new JsonObject();
                    reasons.addProperty("INTERNAL_FRONTAGE_UNAVAILABLE", 1);
                    attempt.add("filterReasonCounts", reasons);
                    JsonArray hardBlocks = new JsonArray();
                    hardBlocks.add(string(requiredObject(plan, "blueprintLayout"),
                            "frontageFailure"));
                    attempt.add("hardBlocks", hardBlocks);
                    attempt.addProperty("patchSelectionScope", patchScope.name());
                    attempts.add(attempt);
                    continue;
                }
                CityStructureArrayCandidatePlanner.Result result = candidatePlanner.plan(runDir, review,
                        structureSource, plan, null, occupied.deepCopy(),
                        footprint -> candidateFootprintRejectionReason(
                                terrainGate, state, states, structureRef, phase, footprint,
                                requiredObject(plan, "blueprintLayout")));
                JsonObject candidateSet = result.arrayCandidateSet();
                JsonObject attempt = candidateAttemptSummary(template, plan, result);
                attempt.addProperty("patchSelectionScope", patchScope.name());
                attempts.add(attempt);
                for (JsonElement element : array(candidateSet, "arrayCandidates")) {
                    JsonObject candidate = element.getAsJsonObject();
                    TerrainCandidateEvaluation terrain = candidateTerrainEvaluation(candidate, state,
                            structureRef, terrainGate);
                    if (!terrain.passed()) {
                        increment(compilerFilterReasons, terrain.reasonCode());
                        recordCompilerRejectedPositions(attempt, candidate, terrain.reasonCode());
                        if (attempts.size() < 16) attempt.add("terrainGateRejection", terrain.trace());
                        continue;
                    }
                    ConnectivityFit connectivity = connectivityFit(candidate, state, states,
                            phase == PlacementPhase.CONNECTIVITY || phase == PlacementPhase.PERCENTAGE);
                    if (!connectivity.allowed()) {
                        increment(compilerFilterReasons, connectivity.reasonCode());
                        recordCompilerRejectedPositions(attempt, candidate, connectivity.reasonCode());
                        continue;
                    }
                    if (phase != PlacementPhase.CONNECTIVITY
                            && !hardRelationsAllow(candidate, state.group(), blueprint.relations(), states)) {
                        increment(compilerFilterReasons, "HARD_RELATION_UNSATISFIED");
                        recordCompilerRejectedPositions(attempt, candidate, "HARD_RELATION_UNSATISFIED");
                        continue;
                    }
                    double relationFit = relationFit(candidate, state.group(), blueprint.relations(), states);
                    double base = doubleValue(requiredObject(candidate, "scoreBreakdown"), "total", 0.0);
                    double targetFit = connectivityTarget == null ? 0.0
                            : targetFit(candidate, connectivityTarget, state.planningBounds());
                    double total = phase == PlacementPhase.CONNECTIVITY
                            ? base * 0.25 + connectivity.score() * 0.15 + targetFit * 0.60
                            : base * 0.70 + relationFit * 0.12 + connectivity.score() * 0.18;
                    long tie = tieKey(blueprint.generationSeed(), state.group().groupId(), structureRef,
                            template.templateId(), template.variantId(), string(candidate, "arrayCandidateId"));
                    choices.add(new CandidateChoice(candidate.deepCopy(), template,
                            requiredObject(plan, "blueprintLayout").deepCopy(), base, relationFit,
                            connectivity, terrain, targetFit, total, tie));
                }
            }
            if (!choices.isEmpty()) break;
        }
        choices.sort(Comparator.comparingDouble(CandidateChoice::total).reversed()
                .thenComparingLong(CandidateChoice::tieKey)
                .thenComparing(choice -> string(choice.candidate(), "arrayCandidateId")));
        JsonObject event = new JsonObject();
        event.addProperty("sequence", selections.size() + 1);
        event.addProperty("phase", phase.traceName);
        event.addProperty("groupId", state.group().groupId());
        event.addProperty("structureRef", structureRef);
        event.addProperty("candidateCount", choices.size());
        event.add("attempts", attempts);
        event.add("compilerFilterReasonCounts", compilerFilterReasons);
        if (choices.isEmpty() && phase != PlacementPhase.CONNECTIVITY
                && state.advancePastIllegalExactSlot()) {
            event.addProperty("status", "skipped_illegal_slot");
            event.addProperty("reasonCode", "EXACT_SLOT_ILLEGAL_LEFT_EMPTY");
            event.addProperty("skippedLayoutSlotIndex", state.layoutSlotIndex() - 1);
            selections.add(event);
            return chooseOne(runDir, review, structureSource, templateCatalog, templates, blueprint,
                    state, structureRef, phase, ordinal, occupied, catalog, states, selections,
                    connectivityTarget, terrainGate, choiceRank);
        }
        if (choices.isEmpty() || choiceRank < 0 || choiceRank >= choices.size()) {
            event.addProperty("status", "no_legal_candidate");
            event.addProperty("reasonCode", choices.isEmpty() && state.compactLocalFrontierExhausted()
                    ? "CITY_BLUEPRINT_COMPACT_LOCAL_FRONTIER_EXHAUSTED"
                    : required
                    ? "CITY_BLUEPRINT_REQUIRED_STRUCTURE_NO_LEGAL_PLACEMENT"
                    : phase == PlacementPhase.CONNECTIVITY
                            ? "CITY_BLUEPRINT_CONNECTIVITY_SLOT_UNAVAILABLE"
                            : "CITY_BLUEPRINT_FILL_CAPACITY_EXHAUSTED");
            selections.add(event);
            return null;
        }
        CandidateChoice chosen = choices.get(choiceRank);
        event.addProperty("candidateRank", choiceRank);
        event.addProperty("status", "committed");
        event.addProperty("candidateId", string(chosen.candidate(), "arrayCandidateId"));
        event.addProperty("templateId", chosen.template().templateId());
        event.addProperty("variantId", chosen.template().variantId());
        event.addProperty("baseScore", chosen.baseScore());
        event.addProperty("relationFit", chosen.relationFit());
        event.addProperty("connectivityFit", chosen.connectivity().score());
        event.addProperty("connectionGapBlocks", chosen.connectivity().gapBlocks());
        if (phase == PlacementPhase.CONNECTIVITY && connectivityTarget != null) {
            event.addProperty("connectivityTargetGroupId", connectivityTarget.group().groupId());
            event.addProperty("connectivityTargetFit", chosen.targetFit());
        }
        event.add("blueprintLayout", chosen.blueprintLayout().deepCopy());
        event.addProperty("totalScore", chosen.total());
        event.addProperty("stableTieKey", Long.toUnsignedString(chosen.tieKey()));
        event.add("scoreBreakdown", requiredObject(chosen.candidate(), "scoreBreakdown").deepCopy());
        event.add("collisionEnvelope", requiredObject(chosen.candidate(), "groupCollisionEnvelope").deepCopy());
        event.add("terrainGateEvaluation", chosen.terrain().trace().deepCopy());
        selections.add(event);
        JsonArray selectedAnchors = array(requiredObject(chosen.candidate(), "expandedStructureAnchorPlan"),
                "anchors");
        if (selectedAnchors.size() != 1) {
            throw fail("CITY_BLUEPRINT_COMPILER_INTERNAL_CARDINALITY",
                    "Single-structure placement produced " + selectedAnchors.size() + " anchors.");
        }
        JsonObject anchor = selectedAnchors.get(0).getAsJsonObject().deepCopy();
        JsonArray selectedItems = array(chosen.candidate(), "items");
        if (selectedItems.size() != 1 || !selectedItems.get(0).isJsonObject()) {
            throw fail("CITY_BLUEPRINT_COMPILER_INTERNAL_CARDINALITY",
                    "Single-structure placement did not expose one frozen template item.");
        }
        JsonObject frozenItem = selectedItems.get(0).getAsJsonObject();
        for (String field : List.of("templateHash", "rawSize", "terrainPosePolicy", "supportPolicy",
                "clearanceBlocks", "materializationSource", "templatePlacementPlan")) {
            if (frozenItem.has(field)) anchor.add(field, frozenItem.get(field).deepCopy());
        }
        if (frozenItem.has("plannedFootprint")) {
            anchor.add("plannedFootprint", frozenItem.get("plannedFootprint").deepCopy());
        }
        if (frozenItem.has("estimatedCollisionEnvelope")) {
            anchor.add("collisionEnvelope", frozenItem.get("estimatedCollisionEnvelope").deepCopy());
        }
        if (frozenItem.has("estimatedMaskEnvelope")) {
            anchor.add("maskEnvelope", frozenItem.get("estimatedMaskEnvelope").deepCopy());
        }
        anchor.addProperty("anchorId", state.group().groupId() + "_"
                + phase.anchorLabel + "_" + String.format("%03d", ordinal));
        anchor.addProperty("placementGroupId", state.group().groupId());
        anchor.addProperty("blueprintStructureRef", structureRef);
        anchor.addProperty("blueprintRequired", required);
        anchor.addProperty("blueprintPlacementPhase", phase.traceName);
        anchor.addProperty("resolvedTerrainMode", chosen.terrain().resolvedTerrainMode());
        anchor.addProperty("selectionReason", "Programmatic CityBlueprint compiler selection");
        JsonObject layoutTrace = chosen.blueprintLayout().deepCopy();
        if ("LINEAR".equals(state.layoutAlgorithm()) && state.anchorCount() == 0) {
            String entranceDirection = firstRoadEntranceDirection(anchor);
            if (!entranceDirection.isBlank()) {
                layoutTrace.addProperty("primaryEntranceDirection", entranceDirection);
                layoutTrace.addProperty("streetAxisDerivedFromPrimaryEntrance", true);
            }
        }
        if (anchor.has("anchorBlock") && anchor.get("anchorBlock").isJsonObject()) {
            layoutTrace.add("acceptedAnchor", anchor.getAsJsonObject("anchorBlock").deepCopy());
        }
        anchor.add("blueprintLayout", layoutTrace);
        return new Placement(anchor, requiredObject(chosen.candidate(), "groupCollisionEnvelope").deepCopy(),
                structureRef, required, phase, chosen.connectivity());
    }

    private List<Placement> chooseCenterSymmetricPair(Path runDir,
                                                       CityLandformReviewPackage review,
                                                       JsonObject structureSource,
                                                       JsonObject templateCatalog,
                                                       CityTemplateCatalog templates,
                                                       CityBlueprint blueprint,
                                                       GroupState state,
                                                       String structureRef,
                                                       int firstOrdinal,
                                                       JsonArray occupied,
                                                       CatalogIndex catalog,
                                                       Map<String, GroupState> states,
                                                       JsonArray selections,
                                                       CityStructureTerrainGate terrainGate) throws IOException {
        if (state.layoutFrame() == null || state.extent() == null || state.requiredCount() != 1) {
            throw fail("CITY_BLUEPRINT_CENTER_SYMMETRIC_STATE_INVALID",
                    "CENTER_SYMMETRIC requires one committed center structure before paired fill.");
        }
        int pairIndex = Math.max(0, (state.anchorCount() - 1) / 2);
        BlockBounds centerBounds = state.centerStructureExtent();
        int centerSpan = Math.max(width(centerBounds), depth(centerBounds));
        List<CenterSymmetricChoice> choices = new ArrayList<>();
        JsonArray attempts = new JsonArray();
        JsonObject compilerFilterReasons = new JsonObject();
        List<TemplateCandidate> candidates = new ArrayList<>(catalog.templates(structureRef));
        candidates.sort(Comparator.comparingLong(candidate -> tieKey(blueprint.generationSeed(),
                state.group().groupId(), structureRef, candidate.templateId(), candidate.variantId())));
        for (TemplateCandidate template : candidates) {
            CityTemplateCatalog.Template physicalTemplate = templates.requireTemplate(
                    template.templateId(), template.variantId());
            int memberSpan = Math.max(physicalTemplate.width(), physicalTemplate.depth())
                    + (physicalTemplate.clearanceBlocks()
                    + catalog.foundationMargin(blueprint.outdoorPlan().foundationProfileRef())) * 2;
            CityTemplatePlacementGeometry memberGeometry = physicalTemplate.geometry(
                    physicalTemplate.allowedRotations().get(0), physicalTemplate.allowedMirrors().get(0));
            BlockBounds memberAtOrigin = CityStructureMaterializationPlanner.expand(
                    memberGeometry.worldBounds(new BlockPoint(0, 0)), physicalTemplate.clearanceBlocks());
            int anchorCenterTwiceX = centerBounds.minX() + centerBounds.maxX()
                    - memberAtOrigin.minX() - memberAtOrigin.maxX();
            int anchorCenterTwiceZ = centerBounds.minZ() + centerBounds.maxZ()
                    - memberAtOrigin.minZ() - memberAtOrigin.maxZ();
            for (CityBlueprintGroupLayoutPlanner.SymmetricPair pair : groupLayoutPlanner.symmetricPairOptions(
                    state.group().densityClass(), state.layoutFrame(), pairIndex, centerSpan, memberSpan,
                    anchorCenterTwiceX, anchorCenterTwiceZ)) {
                JsonObject plan = candidatePlan(blueprint, state, structureRef, PlacementPhase.FILL,
                        firstOrdinal, template, templateCatalog, templates, states, null);
                String pairId = state.group().groupId() + "_fill_pair_" + String.format("%03d", pairIndex + 1);
                plan.addProperty("arrayId", pairId + "_axis_" + pair.axisVariant());
                plan.addProperty("arrayCount", 2);
                plan.addProperty("spacingBlocks", pair.radiusBlocks());
                plan.add("candidateOrigins", pair.originsJson());
                plan.addProperty("exactCandidateOriginsOnly", true);
                plan.add("candidateLegalRegion", legalRegion(state, state.formationPatches(), null, false));
                JsonObject layout = pair.traceJson(firstOrdinal - 1);
                layout.addProperty("preferredPatchZone", state.group().preferredPatchZone().name());
                layout.addProperty("symmetryPairId", pairId);
                layout.add("visualCenter", centerTwiceJson(centerBounds.minX() + centerBounds.maxX(),
                        centerBounds.minZ() + centerBounds.maxZ()));
                plan.add("blueprintLayout", layout);

                CityStructureArrayCandidatePlanner.Result result = candidatePlanner.plan(runDir, review,
                        structureSource, plan, null, occupied.deepCopy(),
                        footprint -> candidateFootprintRejectionReason(
                                terrainGate, state, states, structureRef, PlacementPhase.FILL, footprint,
                                requiredObject(plan, "blueprintLayout")));
                JsonObject attempt = candidateAttemptSummary(template, plan, result);
                attempt.addProperty("symmetryAxisVariant", pair.axisVariant());
                attempts.add(attempt);
                for (JsonElement element : array(result.arrayCandidateSet(), "arrayCandidates")) {
                    JsonObject candidate = element.getAsJsonObject();
                    if (!matchesSymmetricPair(candidate, pair)) {
                        increment(compilerFilterReasons, "CENTER_SYMMETRY_EXACT_ORIGINS_UNSATISFIED");
                        recordCompilerRejectedPositions(attempt, candidate,
                                "CENTER_SYMMETRY_EXACT_ORIGINS_UNSATISFIED");
                        continue;
                    }
                    TerrainCandidateEvaluation terrain = symmetricPairTerrainEvaluation(candidate, state,
                            structureRef, terrainGate);
                    if (!terrain.passed()) {
                        increment(compilerFilterReasons, terrain.reasonCode());
                        recordCompilerRejectedPositions(attempt, candidate, terrain.reasonCode());
                        if (attempts.size() < 16) attempt.add("terrainGateRejection", terrain.trace());
                        continue;
                    }
                    ConnectivityFit connectivity = connectivityFit(candidate, state, states, false);
                    if (!connectivity.allowed()) {
                        increment(compilerFilterReasons, connectivity.reasonCode());
                        recordCompilerRejectedPositions(attempt, candidate, connectivity.reasonCode());
                        continue;
                    }
                    if (!hardRelationsAllow(candidate, state.group(), blueprint.relations(), states)) {
                        increment(compilerFilterReasons, "HARD_RELATION_UNSATISFIED");
                        recordCompilerRejectedPositions(attempt, candidate, "HARD_RELATION_UNSATISFIED");
                        continue;
                    }
                    double relationFit = relationFit(candidate, state.group(), blueprint.relations(), states);
                    double base = doubleValue(requiredObject(candidate, "scoreBreakdown"), "total", 0.0);
                    double total = base * 0.70 + relationFit * 0.12 + connectivity.score() * 0.18;
                    long tie = tieKey(blueprint.generationSeed(), state.group().groupId(), structureRef,
                            template.templateId(), template.variantId(), Integer.toString(pair.pairIndex()),
                            Integer.toString(pair.axisVariant()), string(candidate, "arrayCandidateId"));
                    choices.add(new CenterSymmetricChoice(candidate.deepCopy(), template, layout.deepCopy(),
                            pair, base, relationFit, connectivity, terrain, total, tie));
                }
            }
        }
        choices.sort(Comparator.comparingDouble(CenterSymmetricChoice::total).reversed()
                .thenComparingLong(CenterSymmetricChoice::tieKey)
                .thenComparing(choice -> string(choice.candidate(), "arrayCandidateId")));

        JsonObject event = new JsonObject();
        event.addProperty("sequence", selections.size() + 1);
        event.addProperty("phase", PlacementPhase.FILL.traceName);
        event.addProperty("groupId", state.group().groupId());
        event.addProperty("structureRef", structureRef);
        event.addProperty("candidateCount", choices.size());
        event.addProperty("requestedBatchSize", 2);
        event.addProperty("atomicPair", true);
        event.add("attempts", attempts);
        event.add("compilerFilterReasonCounts", compilerFilterReasons);
        if (choices.isEmpty()) {
            event.addProperty("status", "no_legal_candidate");
            event.addProperty("reasonCode", "CITY_BLUEPRINT_CENTER_SYMMETRIC_PAIR_UNAVAILABLE");
            selections.add(event);
            return List.of();
        }

        CenterSymmetricChoice chosen = choices.get(0);
        event.addProperty("status", "committed");
        event.addProperty("candidateId", string(chosen.candidate(), "arrayCandidateId"));
        event.addProperty("templateId", chosen.template().templateId());
        event.addProperty("variantId", chosen.template().variantId());
        event.addProperty("baseScore", chosen.baseScore());
        event.addProperty("relationFit", chosen.relationFit());
        event.addProperty("connectivityFit", chosen.connectivity().score());
        event.addProperty("connectionGapBlocks", chosen.connectivity().gapBlocks());
        event.add("blueprintLayout", chosen.blueprintLayout().deepCopy());
        event.addProperty("totalScore", chosen.total());
        event.addProperty("stableTieKey", Long.toUnsignedString(chosen.tieKey()));
        event.add("scoreBreakdown", requiredObject(chosen.candidate(), "scoreBreakdown").deepCopy());
        event.add("collisionEnvelope", requiredObject(chosen.candidate(), "groupCollisionEnvelope").deepCopy());
        event.add("terrainGateEvaluation", chosen.terrain().trace().deepCopy());
        event.add("centerSymmetryProof", centerSymmetryProof(chosen.pair(), centerBounds,
                chosen.candidate()));
        selections.add(event);

        JsonArray selectedAnchors = array(requiredObject(chosen.candidate(), "expandedStructureAnchorPlan"),
                "anchors");
        JsonArray selectedItems = array(chosen.candidate(), "items");
        if (selectedAnchors.size() != 2 || selectedItems.size() != 2) {
            throw fail("CITY_BLUEPRINT_COMPILER_INTERNAL_CARDINALITY",
                    "CENTER_SYMMETRIC pair must freeze exactly two structures.");
        }
        List<Placement> placements = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            JsonObject anchor = selectedAnchors.get(index).getAsJsonObject().deepCopy();
            JsonObject frozenItem = selectedItems.get(index).getAsJsonObject();
            for (String field : List.of("templateHash", "rawSize", "terrainPosePolicy", "supportPolicy",
                    "clearanceBlocks", "materializationSource", "templatePlacementPlan")) {
                if (frozenItem.has(field)) anchor.add(field, frozenItem.get(field).deepCopy());
            }
            if (frozenItem.has("plannedFootprint")) {
                anchor.add("plannedFootprint", frozenItem.get("plannedFootprint").deepCopy());
            }
            JsonObject itemCollision = requiredObject(frozenItem, "estimatedCollisionEnvelope").deepCopy();
            anchor.add("collisionEnvelope", itemCollision.deepCopy());
            if (frozenItem.has("estimatedMaskEnvelope")) {
                anchor.add("maskEnvelope", frozenItem.get("estimatedMaskEnvelope").deepCopy());
            }
            anchor.addProperty("anchorId", state.group().groupId() + "_fill_"
                    + String.format("%03d", firstOrdinal + index));
            anchor.addProperty("placementGroupId", state.group().groupId());
            anchor.addProperty("blueprintStructureRef", structureRef);
            anchor.addProperty("blueprintRequired", false);
            anchor.addProperty("blueprintPlacementPhase", PlacementPhase.FILL.traceName);
            anchor.addProperty("resolvedTerrainMode", chosen.terrain().resolvedTerrainMode());
            anchor.addProperty("selectionReason", "Programmatic CityBlueprint center-symmetric pair selection");
            JsonObject layoutTrace = chosen.blueprintLayout().deepCopy();
            layoutTrace.addProperty("slotIndex", firstOrdinal - 1 + index);
            layoutTrace.addProperty("symmetryPairMember", index == 0 ? "FIRST" : "OPPOSITE");
            layoutTrace.add("centerSymmetryProof", centerSymmetryProof(chosen.pair(), centerBounds,
                    chosen.candidate()));
            if (anchor.has("anchorBlock") && anchor.get("anchorBlock").isJsonObject()) {
                layoutTrace.add("acceptedAnchor", anchor.getAsJsonObject("anchorBlock").deepCopy());
            }
            anchor.add("blueprintLayout", layoutTrace);
            placements.add(new Placement(anchor, itemCollision, structureRef, false,
                    PlacementPhase.FILL, chosen.connectivity()));
        }
        return List.copyOf(placements);
    }

    private static boolean matchesSymmetricPair(JsonObject candidate,
                                                CityBlueprintGroupLayoutPlanner.SymmetricPair pair) {
        JsonArray anchors = array(requiredObject(candidate, "expandedStructureAnchorPlan"), "anchors");
        if (anchors.size() != 2) return false;
        BlockPoint first = point(requiredObject(anchors.get(0).getAsJsonObject(), "anchorBlock"));
        BlockPoint opposite = point(requiredObject(anchors.get(1).getAsJsonObject(), "anchorBlock"));
        return first.equals(pair.first()) && opposite.equals(pair.opposite())
                && first.x() + opposite.x() == pair.anchorCenterTwiceX()
                && first.z() + opposite.z() == pair.anchorCenterTwiceZ();
    }

    private static String firstRoadEntranceDirection(JsonObject anchor) {
        if (!anchor.has("templatePlacementPlan") || !anchor.get("templatePlacementPlan").isJsonObject()) {
            return "";
        }
        JsonObject placement = anchor.getAsJsonObject("templatePlacementPlan");
        if (!placement.has("transformed") || !placement.get("transformed").isJsonObject()) return "";
        JsonObject transformed = placement.getAsJsonObject("transformed");
        if (!transformed.has("roadEntrances") || !transformed.get("roadEntrances").isJsonArray()) return "";
        JsonArray entrances = transformed.getAsJsonArray("roadEntrances");
        if (entrances.isEmpty() || !entrances.get(0).isJsonObject()) return "";
        return string(entrances.get(0).getAsJsonObject(), "direction");
    }

    private static BlockPoint firstRoadEntrancePosition(JsonObject anchor) {
        if (!anchor.has("templatePlacementPlan") || !anchor.get("templatePlacementPlan").isJsonObject()) {
            return null;
        }
        JsonObject placement = anchor.getAsJsonObject("templatePlacementPlan");
        JsonObject transformed = requiredObject(placement, "transformed");
        JsonArray entrances = array(transformed, "roadEntrances");
        if (entrances.isEmpty() || !entrances.get(0).isJsonObject()) return null;
        JsonObject entrance = entrances.get(0).getAsJsonObject();
        if (!entrance.has("worldPosition") || !entrance.get("worldPosition").isJsonObject()) return null;
        return point(entrance.getAsJsonObject("worldPosition"));
    }

    private static TerrainCandidateEvaluation symmetricPairTerrainEvaluation(JsonObject candidate,
                                                                              GroupState state,
                                                                              String structureRef,
                                                                              CityStructureTerrainGate terrainGate) {
        JsonArray anchors = array(requiredObject(candidate, "expandedStructureAnchorPlan"), "anchors");
        JsonArray items = array(candidate, "items");
        JsonObject trace = new JsonObject();
        trace.addProperty("schema", "city_structure_terrain_gate_batch_trace");
        trace.addProperty("evaluationScope", "all_pair_member_footprints");
        trace.addProperty("structureRef", structureRef);
        JsonArray members = new JsonArray();
        String resolvedMode = "";
        for (int index = 0; index < 2; index++) {
            TerrainCandidateEvaluation member = anchorTerrainEvaluation(
                    anchors.get(index).getAsJsonObject(),
                    bounds(requiredObject(items.get(index).getAsJsonObject(), "estimatedCollisionEnvelope")),
                    state, structureRef, terrainGate);
            members.add(member.trace().deepCopy());
            if (!member.passed()) {
                trace.addProperty("status", "rejected");
                trace.addProperty("reasonCode", member.reasonCode());
                trace.add("memberEvaluations", members);
                return TerrainCandidateEvaluation.rejected(member.reasonCode(), structureRef, trace);
            }
            resolvedMode = member.resolvedTerrainMode();
        }
        trace.addProperty("status", "passed");
        trace.addProperty("memberCount", 2);
        trace.add("memberEvaluations", members);
        return new TerrainCandidateEvaluation(true, "", resolvedMode, trace);
    }

    private static JsonObject centerSymmetryProof(CityBlueprintGroupLayoutPlanner.SymmetricPair pair,
                                                  BlockBounds centerBounds,
                                                  JsonObject candidate) {
        JsonObject proof = new JsonObject();
        proof.add("anchorCenter", centerTwiceJson(pair.anchorCenterTwiceX(), pair.anchorCenterTwiceZ()));
        proof.add("first", pair.first().asJson());
        proof.add("opposite", pair.opposite().asJson());
        int summedAnchorX = pair.first().x() + pair.opposite().x();
        int summedAnchorZ = pair.first().z() + pair.opposite().z();
        proof.addProperty("summedAnchorX", summedAnchorX);
        proof.addProperty("summedAnchorZ", summedAnchorZ);
        proof.addProperty("expectedSummedAnchorX", pair.anchorCenterTwiceX());
        proof.addProperty("expectedSummedAnchorZ", pair.anchorCenterTwiceZ());

        JsonArray items = array(candidate, "items");
        BlockBounds firstBounds = bounds(requiredObject(items.get(0).getAsJsonObject(),
                "estimatedCollisionEnvelope"));
        BlockBounds oppositeBounds = bounds(requiredObject(items.get(1).getAsJsonObject(),
                "estimatedCollisionEnvelope"));
        int centerTwiceX = centerBounds.minX() + centerBounds.maxX();
        int centerTwiceZ = centerBounds.minZ() + centerBounds.maxZ();
        int summedMemberCenterTwiceX = firstBounds.minX() + firstBounds.maxX()
                + oppositeBounds.minX() + oppositeBounds.maxX();
        int summedMemberCenterTwiceZ = firstBounds.minZ() + firstBounds.maxZ()
                + oppositeBounds.minZ() + oppositeBounds.maxZ();
        proof.add("visualCenter", centerTwiceJson(centerTwiceX, centerTwiceZ));
        proof.addProperty("summedMemberCenterTwiceX", summedMemberCenterTwiceX);
        proof.addProperty("summedMemberCenterTwiceZ", summedMemberCenterTwiceZ);
        proof.addProperty("expectedMemberCenterTwiceX", centerTwiceX * 2);
        proof.addProperty("expectedMemberCenterTwiceZ", centerTwiceZ * 2);
        proof.addProperty("verified", summedAnchorX == pair.anchorCenterTwiceX()
                && summedAnchorZ == pair.anchorCenterTwiceZ()
                && summedMemberCenterTwiceX == centerTwiceX * 2
                && summedMemberCenterTwiceZ == centerTwiceZ * 2);
        return proof;
    }

    private static JsonObject centerTwiceJson(int xTimesTwo, int zTimesTwo) {
        JsonObject value = new JsonObject();
        value.addProperty("x", xTimesTwo / 2.0);
        value.addProperty("z", zTimesTwo / 2.0);
        value.addProperty("xTimesTwo", xTimesTwo);
        value.addProperty("zTimesTwo", zTimesTwo);
        return value;
    }

    private void commit(Placement placement, JsonArray anchors, JsonArray occupied, GroupState state) {
        if (state.landscapeGapCirculation() && placement.anchor().has("blueprintLayout")) {
            placement.anchor().getAsJsonObject("blueprintLayout")
                    .addProperty("internalCirculationMode", "LANDSCAPE_GAPS");
        }
        anchors.add(placement.anchor());
        JsonObject envelope = new JsonObject();
        envelope.add("blockBounds", placement.collisionEnvelope());
        envelope.addProperty("ownerGroupId", state.group().groupId());
        envelope.addProperty("anchorId", string(placement.anchor(), "anchorId"));
        envelope.addProperty("arrayId", string(placement.anchor(), "arrayId"));
        JsonObject body = placement.anchor().has("actualFootprint")
                && placement.anchor().get("actualFootprint").isJsonObject()
                ? placement.anchor().getAsJsonObject("actualFootprint").deepCopy()
                : placement.collisionEnvelope().deepCopy();
        envelope.add("bodyBounds", body);
        occupied.add(envelope);
        BlockBounds committedBounds = bounds(placement.collisionEnvelope());
        state.commit(placement.structureRef(), placement.collisionEnvelope(), placement.required(),
                placement.phase(), placement.connectivity(), placement.anchor(),
                groupLayoutPlanner.claimedArea(committedBounds, state.layoutParameters()));
        for (JsonElement element : array(placement.anchor(), "sourcePatchIds")) {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                String patchRef = element.getAsString();
                if (state.patchByRef().containsKey(patchRef)) state.claimPatch(patchRef);
            }
        }
    }

    private JsonArray streetSkeletonBands(Map<String, GroupState> states,
                                          JsonArray anchors,
                                          CatalogIndex catalog) {
        JsonArray streetBands = new JsonArray();
        List<JsonObject> anchorObjects = anchors.asList().stream()
                .map(JsonElement::getAsJsonObject).toList();
        states.values().forEach(state -> {
            if (!state.landscapeGapCirculation()) {
                internalStreetPlanner.planSkeleton(state.group().groupId(), state.layoutAlgorithm(),
                                state.layoutParameters(), anchorObjects,
                                catalog.centerAxisStreetEnabled(state.group().algorithmProfileRef()),
                                state.spatialDemand().plannedStructureCount(),
                                "LINEAR".equals(state.layoutAlgorithm())
                                        ? state.spatialDemand().formationLengthBlocks()
                                        : state.spatialDemand().formationSpanBlocks(),
                                state.layoutFrame())
                        .forEach(streetBands::add);
            }
        });
        return streetBands;
    }

    private static Set<String> landscapeGapCirculationGroups(CityBlueprint blueprint) {
        Set<String> result = new LinkedHashSet<>();
        for (CityBlueprint.Landscape landscape : blueprint.outdoorPlan().landscapes()) {
            if (landscape.owner() == null || landscape.purpose() != CityBlueprint.LandscapePurpose.FUNCTIONAL) {
                continue;
            }
            boolean hasCorridorSeparator = landscape.fillSelection().variants().stream()
                    .flatMap(variant -> variant.roleShares().stream())
                    .anyMatch(role -> role.growthForm() == CityBlueprint.RegionGrowthForm.CORRIDOR);
            if (hasCorridorSeparator) result.add(landscape.owner().groupId());
        }
        return Set.copyOf(result);
    }

    private static void addRoadBandsToOccupied(List<JsonObject> bands, JsonArray occupied) {
        for (JsonObject band : bands) {
            JsonObject bounds = requiredObject(band, "bounds").deepCopy();
            JsonObject envelope = new JsonObject();
            envelope.add("blockBounds", bounds);
            envelope.add("bodyBounds", bounds.deepCopy());
            envelope.addProperty("ownerGroupId", "city_main_road");
            envelope.addProperty("anchorId", string(band, "streetBandId"));
            envelope.addProperty("arrayId", string(band, "roadNetworkId"));
            occupied.add(envelope);
        }
    }

    private static void addLandscapeCapacityToOccupied(JsonObject plan, JsonArray occupied) {
        for (JsonElement instanceElement : array(plan, "instances")) {
            JsonObject instance = instanceElement.getAsJsonObject();
            for (JsonElement parcelElement : array(instance, "parcelReservations")) {
                JsonObject parcel = parcelElement.getAsJsonObject();
                JsonArray spans = array(parcel, "reservationSpans");
                if (spans.isEmpty()) continue;
                int minX = Integer.MAX_VALUE;
                int minZ = Integer.MAX_VALUE;
                int maxX = Integer.MIN_VALUE;
                int maxZ = Integer.MIN_VALUE;
                for (JsonElement spanElement : spans) {
                    JsonObject span = spanElement.getAsJsonObject();
                    minX = Math.min(minX, intValue(span, "minX", minX));
                    maxX = Math.max(maxX, intValue(span, "maxX", maxX));
                    int z = intValue(span, "z", 0);
                    minZ = Math.min(minZ, z);
                    maxZ = Math.max(maxZ, z);
                }
                JsonObject bounds = new JsonObject();
                bounds.addProperty("minX", minX);
                bounds.addProperty("minZ", minZ);
                bounds.addProperty("maxX", maxX);
                bounds.addProperty("maxZ", maxZ);
                JsonObject envelope = new JsonObject();
                envelope.add("blockBounds", bounds);
                envelope.add("bodyBounds", bounds.deepCopy());
                envelope.addProperty("ownerGroupId", "landscape_capacity");
                envelope.addProperty("anchorId", string(parcel, "parcelId"));
                envelope.addProperty("arrayId", string(parcel, "parcelId"));
                occupied.add(envelope);
            }
        }
    }

    private static Map<String, GroupState> freshStates(Map<String, GroupState> source) {
        Map<String, GroupState> result = new LinkedHashMap<>();
        source.forEach((groupId, state) -> result.put(groupId, state.fresh()));
        return result;
    }

    private static boolean incrementRanks(int[] ranks, int[] limits) {
        for (int index = ranks.length - 1; index >= 0; index--) {
            if (ranks[index] + 1 < limits[index]) {
                ranks[index]++;
                for (int reset = index + 1; reset < ranks.length; reset++) ranks[reset] = 0;
                return true;
            }
        }
        return false;
    }

    private static List<CityBlueprint.Group> orderGroups(List<CityBlueprint.Group> source,
                                                         List<CityBlueprint.Relation> relations,
                                                         List<CityBlueprint.ArrayComposition> compositions,
                                                         CatalogIndex catalog) {
        Comparator<CityBlueprint.Group> stable = Comparator
                .comparingInt((CityBlueprint.Group group) ->
                        "CENTER_SYMMETRIC".equals(catalog.algorithm(group.algorithmProfileRef())) ? 0 : 1)
                .thenComparingInt(group -> priorityRank(group.priority()))
                .thenComparing(CityBlueprint.Group::groupId);
        Map<String, CityBlueprint.Group> byId = new LinkedHashMap<>();
        Map<String, Integer> incoming = new LinkedHashMap<>();
        Map<String, Set<String>> outgoing = new LinkedHashMap<>();
        source.forEach(group -> {
            byId.put(group.groupId(), group);
            incoming.put(group.groupId(), 0);
            outgoing.put(group.groupId(), new LinkedHashSet<>());
        });
        for (CityBlueprint.Relation relation : relations) {
            if (relation.relationKind() != CityBlueprint.RelationKind.HIERARCHY) continue;
            addOrderingEdge(outgoing, incoming, relation.fromGroupId(), relation.toGroupId());
        }
        for (CityBlueprint.Group group : source) {
            CityBlueprint.PlacementRelation placement = group.placementRelation();
            if (placement == null
                    || placement.kind() != CityBlueprint.PlacementRelationKind.BETWEEN_GROUPS) continue;
            for (String dependency : placement.groupRefs()) {
                addOrderingEdge(outgoing, incoming, dependency, group.groupId());
            }
        }
        for (CityBlueprint.ArrayComposition composition : compositions) {
            for (String member : composition.memberGroupIds()) {
                addOrderingEdge(outgoing, incoming, composition.centerGroupId(), member);
            }
        }
        List<CityBlueprint.Group> ordered = new ArrayList<>();
        Set<String> remaining = new LinkedHashSet<>(byId.keySet());
        while (!remaining.isEmpty()) {
            CityBlueprint.Group next = remaining.stream()
                    .filter(id -> incoming.getOrDefault(id, 0) == 0)
                    .map(byId::get)
                    .min(stable)
                    .orElseThrow(() -> fail("CITY_BLUEPRINT_HIERARCHY_CYCLE",
                            "HIERARCHY, placement, and parent-array ordering dependencies must be acyclic."));
            ordered.add(next);
            remaining.remove(next.groupId());
            for (String child : outgoing.get(next.groupId())) {
                incoming.computeIfPresent(child, (ignored, count) -> count - 1);
            }
        }
        return List.copyOf(ordered);
    }

    private static void addOrderingEdge(Map<String, Set<String>> outgoing,
                                        Map<String, Integer> incoming,
                                        String from,
                                        String to) {
        Set<String> targets = outgoing.get(from);
        if (targets != null && incoming.containsKey(to) && targets.add(to)) {
            incoming.computeIfPresent(to, (ignored, count) -> count + 1);
        }
    }

    private Map<String, CityGroupSpatialDemand> planGroupSpatialDemands(
            List<CityBlueprint.Group> groups,
            CityScale cityScale,
            int cellStepBlocks,
            CatalogIndex catalog,
            CityTemplateCatalog templates,
            BlockBounds planningBounds,
            int buildingParcelMargin) {
        Map<String, CityGroupSpatialDemand> result = new LinkedHashMap<>();
        for (CityBlueprint.Group group : groups) {
            String algorithm = catalog.algorithm(group.algorithmProfileRef());
            CityBlueprintGroupLayoutPlanner.Parameters parameters =
                    groupLayoutPlanner.parameters(algorithm, group.densityClass());
            int minimumCount = minimumGroupStructureCount(cityScale, group.extentClass(), algorithm);
            List<String> plannedRefs = new ArrayList<>(orderedRequiredStructureRefs(group, catalog));
            List<String> fillPool = catalog.pool(group.fillPoolRef());
            for (int cursor = 0; plannedRefs.size() < minimumCount && !fillPool.isEmpty(); cursor++) {
                plannedRefs.add(fillPool.get(cursor % fillPool.size()));
            }

            int templateArea = 0;
            int claimedArea = 0;
            int discreteClaimedArea = 0;
            int cellArea = cellStepBlocks * cellStepBlocks;
            int maximumWidth = 1;
            int maximumDepth = 1;
            for (String structureRef : plannedRefs) {
                TemplateDemand template = templateDemand(structureRef, catalog, templates,
                        buildingParcelMargin);
                templateArea += template.widthBlocks() * template.depthBlocks();
                maximumWidth = Math.max(maximumWidth, template.widthBlocks());
                maximumDepth = Math.max(maximumDepth, template.depthBlocks());
                int structureClaimedArea = groupLayoutPlanner.claimedArea(
                        new BlockBounds(0, 0, template.widthBlocks() - 1, template.depthBlocks() - 1),
                        parameters);
                claimedArea += structureClaimedArea;
                discreteClaimedArea += (structureClaimedArea + cellArea - 1) / cellArea * cellArea;
            }

            int streetArea = 0;
            int algorithmicSpan = 0;
            int formationWidth = 0;
            int formationLength = 0;
            String primaryAxisDirection = "";
            if ("LINEAR".equals(algorithm)) {
                int secondaryRows = Math.max(0, plannedRefs.size() / 2);
                int length = maximumDepth + secondaryRows
                        * (maximumDepth + parameters.targetEdgeGapBlocks());
                int width = Math.max(maximumWidth,
                        maximumWidth * 2 + parameters.streetBandWidthBlocks());
                formationWidth = width;
                formationLength = length;
                primaryAxisDirection = primaryAxisDirection(plannedRefs.get(0), catalog, templates);
                streetArea = parameters.streetBandWidthBlocks()
                        * Math.max(0, length - maximumDepth / 2);
                algorithmicSpan = Math.max(width, length);
            } else if ("GRID".equals(algorithm)) {
                int columns = (int) Math.ceil(Math.sqrt(plannedRefs.size()));
                int rows = (plannedRefs.size() + columns - 1) / columns;
                int width = columns * maximumWidth
                        + Math.max(0, columns - 1) * parameters.targetEdgeGapBlocks();
                int depth = rows * maximumDepth
                        + Math.max(0, rows - 1) * parameters.targetEdgeGapBlocks();
                algorithmicSpan = Math.max(width, depth);
                int narrowWidth = Math.max(1, parameters.streetBandWidthBlocks() / 2);
                streetArea = Math.max(0, columns - 1) * narrowWidth * depth
                        + Math.max(0, rows - 1) * parameters.streetBandWidthBlocks() * width;
            } else if ("COURTYARD".equals(algorithm)) {
                int maximumTemplateSpan = Math.max(maximumWidth, maximumDepth);
                int pitch = maximumTemplateSpan + parameters.targetEdgeGapBlocks();
                int rings = (plannedRefs.size() - 1) / 7 + 1;
                algorithmicSpan = Math.max(maximumTemplateSpan, rings * pitch * 2 + maximumTemplateSpan);
                int ringSide = rings * pitch * 2;
                streetArea = ringSide * parameters.streetBandWidthBlocks() * 4;
            } else if ("COMPACT".equals(algorithm)) {
                int maximumTemplateSpan = Math.max(maximumWidth, maximumDepth);
                int pitch = maximumTemplateSpan + parameters.targetEdgeGapBlocks();
                int positiveRanks = plannedRefs.size() / 2;
                int negativeRanks = (plannedRefs.size() - 1) / 2;
                int length = (positiveRanks + negativeRanks) * pitch + maximumTemplateSpan;
                int width = maximumTemplateSpan * 2 + parameters.streetBandWidthBlocks()
                        + parameters.targetEdgeGapBlocks();
                formationWidth = width;
                formationLength = length;
                algorithmicSpan = Math.max(width, length);
                streetArea = Math.max(1, length - maximumTemplateSpan)
                        * parameters.streetBandWidthBlocks();
            } else if ("CENTER_SYMMETRIC".equals(algorithm)) {
                TemplateDemand centerTemplate = templateDemand(plannedRefs.get(0), catalog, templates,
                        buildingParcelMargin);
                int centerSpan = Math.max(centerTemplate.widthBlocks(), centerTemplate.depthBlocks());
                int memberSpan = plannedRefs.stream().skip(1)
                        .map(structureRef -> templateDemand(structureRef, catalog, templates,
                                buildingParcelMargin))
                        .mapToInt(template -> Math.max(template.widthBlocks(), template.depthBlocks()))
                        .max().orElse(1);
                int pairCount = Math.max(1, (plannedRefs.size() - 1) / 2);
                int outerPairIndex = pairCount - 1;
                int ring = outerPairIndex / 2;
                int firstRadius = Math.max(1, (centerSpan + memberSpan + 1) / 2
                        + parameters.targetEdgeGapBlocks());
                int outerRadius = firstRadius
                        + ring * (memberSpan + parameters.targetEdgeGapBlocks());
                algorithmicSpan = outerRadius * 2 + memberSpan;
            }

            int minimumArea = Math.max(templateArea, Math.max(claimedArea, discreteClaimedArea) + streetArea);
            int targetArea = minimumArea;
            long planningArea = (long) planningBounds.widthBlocks() * planningBounds.heightBlocks();
            int maximumArea = (int) Math.min(Integer.MAX_VALUE, Math.max(targetArea, planningArea));
            int areaSpan = (int) Math.ceil(Math.sqrt(Math.max(1, targetArea)));
            int formationSpan = Math.max(
                    Math.max(maximumWidth, maximumDepth),
                    Math.max(areaSpan + parameters.jitterBlocks(), algorithmicSpan));
            // Layout proposals are structure-start anchors rather than footprint centers. A square
            // slot therefore needs one maximum template excursion on either side of its origin.
            int maximumTemplateSpan = Math.max(maximumWidth, maximumDepth);
            if (maximumTemplateSpan * 2 > formationSpan) {
                formationSpan += maximumTemplateSpan - 1;
            }
            if (formationWidth == 0) formationWidth = formationSpan;
            if (formationLength == 0) formationLength = formationSpan;
            result.put(group.groupId(), new CityGroupSpatialDemand(
                    minimumArea, targetArea, maximumArea, 0, Math.max(maximumWidth, maximumDepth), formationSpan,
                    formationWidth, formationLength, primaryAxisDirection,
                    plannedRefs.size(), templateArea, streetArea));
        }
        return Map.copyOf(result);
    }

    private static TemplateDemand templateDemand(String structureRef,
                                                   CatalogIndex catalog,
                                                   CityTemplateCatalog templates,
                                                   int buildingParcelMargin) {
        int width = 0;
        int depth = 0;
        for (TemplateCandidate candidate : catalog.templates(structureRef)) {
            CityTemplateCatalog.Template template = templates.requireTemplate(
                    candidate.templateId(), candidate.variantId());
            width = Math.max(width, template.width()
                    + (template.clearanceBlocks() + buildingParcelMargin) * 2);
            depth = Math.max(depth, template.depth()
                    + (template.clearanceBlocks() + buildingParcelMargin) * 2);
        }
        return new TemplateDemand(width, depth);
    }

    private static String primaryAxisDirection(String structureRef,
                                               CatalogIndex catalog,
                                               CityTemplateCatalog templates) {
        for (TemplateCandidate candidate : catalog.templates(structureRef)) {
            CityTemplateCatalog.Template template = templates.requireTemplate(
                    candidate.templateId(), candidate.variantId());
            CityTemplatePlacementGeometry geometry = template.geometry(
                    template.allowedRotations().get(0), template.allowedMirrors().get(0));
            if (!geometry.roadEntrances().isEmpty()) {
                return geometry.roadEntrances().get(0).direction().name();
            }
        }
        return "";
    }

    private static List<String> orderedRequiredStructureRefs(CityBlueprint.Group group,
                                                              CatalogIndex catalog) {
        List<String> result = new ArrayList<>(group.requiredStructureRefs());
        result.sort(Comparator.comparingInt(ref -> catalog.primaryStructure(ref) ? 0 : 1));
        return List.copyOf(result);
    }

    private static CityGroupSpatialDemand spatialDemand(
            CityBlueprint.Group group,
            Map<String, CityGroupSpatialDemand> spatialDemands) {
        CityGroupSpatialDemand demand = spatialDemands.get(group.groupId());
        if (demand == null) {
            throw fail("CITY_BLUEPRINT_GROUP_SPATIAL_DEMAND_MISSING", group.groupId());
        }
        return demand;
    }

    private Map<String, CompositionSlot> planArrayCompositions(
            CityBlueprint blueprint,
            Map<String, CityBlueprint.Group> groupsById,
            Map<String, LandformPatchSummary> patches,
            int patchStepBlocks,
            BlockBounds cityPlanningBounds,
            CatalogIndex catalog,
            Map<String, CityGroupSpatialDemand> spatialDemands,
            int interGroupRoadReserveBlocks) {
        Map<String, CompositionSlot> result = new LinkedHashMap<>();
        List<BlockBounds> reserved = new ArrayList<>();
        for (CityBlueprint.ArrayComposition composition : blueprint.arrayCompositions()) {
            CityBlueprint.Group centerGroup = groupsById.get(composition.centerGroupId());
            BlockPoint centerOrigin = patchPlacementOrigin(centerGroup, patches, patchStepBlocks,
                    cityPlanningBounds);
            if (centerOrigin == null) {
                PatchMemberCell centerCell = preferredZoneCell(preferredPatches(centerGroup, patches),
                        centerGroup.preferredPatchZone(), patchStepBlocks, cityPlanningBounds);
                centerOrigin = centerCell == null ? null : cellCenter(centerCell, patchStepBlocks);
            }
            if (centerOrigin == null) {
                throw fail("CITY_BLUEPRINT_ARRAY_COMPOSITION_CENTER_UNAVAILABLE",
                        composition.compositionId() + " has no legal center origin.");
            }

            CityGroupSpatialDemand centerDemand = spatialDemand(centerGroup, spatialDemands);
            int centerSpan = centerDemand.formationSpanBlocks();
            BlockPoint centerPlacementOrigin = slotPlacementOrigin(centerOrigin, centerDemand);
            BlockBounds centerBounds = slotBounds(centerPlacementOrigin, centerDemand);
            if (!within(centerBounds, cityPlanningBounds) || overlapsAny(centerBounds, reserved)) {
                throw fail("CITY_BLUEPRINT_ARRAY_COMPOSITION_SLOT_UNAVAILABLE",
                        composition.compositionId() + " center Group cannot reserve its declared extent.");
            }
            CompositionSlot centerSlot = new CompositionSlot(composition.compositionId(),
                    catalog.algorithm(composition.algorithmProfileRef()), composition.centerGroupId(),
                    0, -1, centerSpan, centerOrigin, centerPlacementOrigin, centerBounds);
            result.put(centerGroup.groupId(), centerSlot);
            reserved.add(centerBounds);

            String parentAlgorithm = catalog.algorithm(composition.algorithmProfileRef());
            List<String> members = composition.memberGroupIds();
            int parentFixedSpan = members.stream()
                    .map(groupsById::get)
                    .map(group -> spatialDemand(group, spatialDemands))
                    .mapToInt(CityGroupSpatialDemand::formationSpanBlocks)
                    .max().orElse(centerSpan);
            parentFixedSpan = Math.max(parentFixedSpan, centerSpan);
            parentFixedSpan += interGroupRoadReserveBlocks;
            CityBlueprintGroupLayoutPlanner.Frame frame = groupLayoutPlanner.worldAxisLocked(parentAlgorithm)
                    ? groupLayoutPlanner.worldFrame(centerOrigin)
                    : groupLayoutPlanner.frame(centerOrigin, null, blueprint.generationSeed(),
                    composition.compositionId());
            if ("CENTER_SYMMETRIC".equals(parentAlgorithm)) {
                for (int memberIndex = 0; memberIndex < members.size(); memberIndex += 2) {
                    CityBlueprint.Group firstGroup = groupsById.get(members.get(memberIndex));
                    CityBlueprint.Group oppositeGroup = groupsById.get(members.get(memberIndex + 1));
                    CityGroupSpatialDemand firstDemand = spatialDemand(firstGroup, spatialDemands);
                    CityGroupSpatialDemand oppositeDemand = spatialDemand(oppositeGroup, spatialDemands);
                    int firstSpan = firstDemand.formationSpanBlocks();
                    int oppositeSpan = oppositeDemand.formationSpanBlocks();
                    int memberSpan = Math.max(firstSpan, oppositeSpan);
                    BlockPoint selectedFirstOrigin = null;
                    BlockPoint selectedOppositeOrigin = null;
                    BlockPoint selectedFirstPlacementOrigin = null;
                    BlockPoint selectedOppositePlacementOrigin = null;
                    BlockBounds firstBounds = null;
                    BlockBounds oppositeBounds = null;
                        for (CityBlueprintGroupLayoutPlanner.SymmetricPair option :
                            groupLayoutPlanner.symmetricPairOptions(centerGroup.densityClass(), frame,
                                    memberIndex / 2, parentFixedSpan, parentFixedSpan)) {
                        BlockPoint firstPlacement = slotPlacementOrigin(option.first(), firstDemand);
                        BlockPoint oppositePlacement = slotPlacementOrigin(option.opposite(), oppositeDemand);
                        BlockBounds firstCandidate = slotBounds(firstPlacement, firstDemand);
                        BlockBounds oppositeCandidate = slotBounds(oppositePlacement, oppositeDemand);
                        boolean directAvailable = within(firstCandidate, cityPlanningBounds)
                                && within(oppositeCandidate, cityPlanningBounds)
                                && !firstCandidate.overlaps(oppositeCandidate)
                                && !overlapsAny(firstCandidate, reserved)
                                && !overlapsAny(oppositeCandidate, reserved);
                        if (directAvailable && originInsidePreferredPatch(firstPlacement, firstGroup, patches,
                                patchStepBlocks, cityPlanningBounds)
                                && originInsidePreferredPatch(oppositePlacement, oppositeGroup, patches,
                                patchStepBlocks, cityPlanningBounds)) {
                            selectedFirstOrigin = option.first();
                            selectedOppositeOrigin = option.opposite();
                            selectedFirstPlacementOrigin = firstPlacement;
                            selectedOppositePlacementOrigin = oppositePlacement;
                            firstBounds = firstCandidate;
                            oppositeBounds = oppositeCandidate;
                            break;
                        }
                        BlockPoint swappedFirstPlacement = slotPlacementOrigin(option.opposite(), firstDemand);
                        BlockPoint swappedOppositePlacement = slotPlacementOrigin(option.first(), oppositeDemand);
                        BlockBounds swappedFirst = slotBounds(swappedFirstPlacement, firstDemand);
                        BlockBounds swappedOpposite = slotBounds(swappedOppositePlacement, oppositeDemand);
                        boolean swappedAvailable = within(swappedFirst, cityPlanningBounds)
                                && within(swappedOpposite, cityPlanningBounds)
                                && !swappedFirst.overlaps(swappedOpposite)
                                && !overlapsAny(swappedFirst, reserved)
                                && !overlapsAny(swappedOpposite, reserved);
                        if (swappedAvailable && originInsidePreferredPatch(swappedFirstPlacement, firstGroup, patches,
                                patchStepBlocks, cityPlanningBounds)
                                && originInsidePreferredPatch(swappedOppositePlacement, oppositeGroup, patches,
                                patchStepBlocks, cityPlanningBounds)) {
                            selectedFirstOrigin = option.opposite();
                            selectedOppositeOrigin = option.first();
                            selectedFirstPlacementOrigin = swappedFirstPlacement;
                            selectedOppositePlacementOrigin = swappedOppositePlacement;
                            firstBounds = swappedFirst;
                            oppositeBounds = swappedOpposite;
                            break;
                        }
                    }
                    if (selectedFirstOrigin == null) {
                        throw fail("CITY_BLUEPRINT_ARRAY_COMPOSITION_SLOT_UNAVAILABLE",
                                composition.compositionId() + " cannot reserve symmetric child Group pair "
                                        + members.get(memberIndex) + "/" + members.get(memberIndex + 1) + ".");
                    }
                    int pairIndex = memberIndex / 2;
                    CompositionSlot firstSlot = new CompositionSlot(composition.compositionId(),
                            parentAlgorithm, composition.centerGroupId(), memberIndex + 1, pairIndex,
                            firstSpan, selectedFirstOrigin, selectedFirstPlacementOrigin, firstBounds);
                    CompositionSlot oppositeSlot = new CompositionSlot(composition.compositionId(),
                            parentAlgorithm, composition.centerGroupId(), memberIndex + 2, pairIndex,
                            oppositeSpan, selectedOppositeOrigin, selectedOppositePlacementOrigin, oppositeBounds);
                    result.put(firstGroup.groupId(), firstSlot);
                    result.put(oppositeGroup.groupId(), oppositeSlot);
                    reserved.add(firstBounds);
                    reserved.add(oppositeBounds);
                }
            } else {
                for (int memberIndex = 0; memberIndex < members.size(); memberIndex++) {
                    CityBlueprint.Group member = groupsById.get(members.get(memberIndex));
                    CityGroupSpatialDemand memberDemand = spatialDemand(member, spatialDemands);
                    int span = memberDemand.formationSpanBlocks();
                    CityBlueprintGroupLayoutPlanner.Proposal proposal = groupLayoutPlanner.propose(
                            parentAlgorithm, centerGroup.densityClass(), blueprint.generationSeed(),
                            composition.compositionId(), memberIndex + 1, frame, centerOrigin,
                            null, false, groupLayoutPlanner.exactInternalGuides(parentAlgorithm)
                                    ? parentFixedSpan : Math.max(centerSpan, span));
                    BlockPoint selectedOrigin = null;
                    BlockPoint selectedPlacementOrigin = null;
                    BlockBounds selectedBounds = null;
                    for (BlockPoint guide : proposal.guides()) {
                        BlockPoint placementOrigin = slotPlacementOrigin(guide, memberDemand);
                        BlockBounds candidate = slotBounds(placementOrigin, memberDemand);
                        if (within(candidate, cityPlanningBounds)
                                && !overlapsAny(candidate, reserved)
                                && originInsidePreferredPatch(placementOrigin, member, patches,
                                patchStepBlocks, cityPlanningBounds)) {
                            selectedOrigin = guide;
                            selectedPlacementOrigin = placementOrigin;
                            selectedBounds = candidate;
                            break;
                        }
                    }
                    if (selectedOrigin == null) {
                        List<BlockPoint> patchOrigins = new ArrayList<>();
                        BlockPoint relationOrigin = patchPlacementOrigin(member, patches, patchStepBlocks,
                                cityPlanningBounds);
                        if (relationOrigin != null) patchOrigins.add(relationOrigin);
                        for (PatchMemberCell patchCell : preferredZoneCells(preferredPatches(member, patches),
                                member.preferredPatchZone(), patchStepBlocks, cityPlanningBounds)) {
                            BlockPoint patchOrigin = cellCenter(patchCell, patchStepBlocks);
                            if (!patchOrigins.contains(patchOrigin)) patchOrigins.add(patchOrigin);
                        }
                        for (BlockPoint patchOrigin : patchOrigins) {
                            BlockPoint placementOrigin = slotPlacementOrigin(patchOrigin, memberDemand);
                            BlockBounds candidate = slotBounds(placementOrigin, memberDemand);
                            if (within(candidate, cityPlanningBounds)
                                    && !overlapsAny(candidate, reserved)
                                    && originInsidePreferredPatch(placementOrigin, member, patches,
                                    patchStepBlocks, cityPlanningBounds)) {
                                selectedOrigin = patchOrigin;
                                selectedPlacementOrigin = placementOrigin;
                                selectedBounds = candidate;
                                break;
                            }
                        }
                    }
                    if (selectedOrigin == null) {
                        throw fail("CITY_BLUEPRINT_ARRAY_COMPOSITION_SLOT_UNAVAILABLE",
                                composition.compositionId() + " cannot reserve child Group "
                                        + member.groupId() + ".");
                    }
                    CompositionSlot slot = new CompositionSlot(composition.compositionId(), parentAlgorithm,
                            composition.centerGroupId(), memberIndex + 1, -1,
                            span, selectedOrigin, selectedPlacementOrigin, selectedBounds);
                    result.put(member.groupId(), slot);
                    reserved.add(selectedBounds);
                }
            }
        }
        return Map.copyOf(result);
    }

    private static BlockPoint slotPlacementOrigin(BlockPoint slotCenter,
                                                  CityGroupSpatialDemand demand) {
        String direction = demand.primaryAxisDirection();
        if (direction.isBlank()) return slotCenter;
        int halfWidth = demand.formationWidthBlocks() / 2;
        int shift = Math.max(0, (demand.formationLengthBlocks() - 1 - halfWidth) / 2);
        return switch (direction) {
            case "NORTH" -> new BlockPoint(slotCenter.x(), slotCenter.z() + shift);
            case "EAST" -> new BlockPoint(slotCenter.x() - shift, slotCenter.z());
            case "SOUTH" -> new BlockPoint(slotCenter.x(), slotCenter.z() - shift);
            case "WEST" -> new BlockPoint(slotCenter.x() + shift, slotCenter.z());
            default -> slotCenter;
        };
    }

    private static boolean originInsidePreferredPatch(BlockPoint origin,
                                                      CityBlueprint.Group group,
                                                      Map<String, LandformPatchSummary> patches,
                                                      int patchStepBlocks,
                                                      BlockBounds cityPlanningBounds) {
        if (origin.x() < cityPlanningBounds.minX() || origin.x() > cityPlanningBounds.maxX()
                || origin.z() < cityPlanningBounds.minZ() || origin.z() > cityPlanningBounds.maxZ()) {
            return false;
        }
        for (String patchRef : group.preferredPatchRefs()) {
            LandformPatchSummary patch = patches.get(patchRef);
            if (patch == null) continue;
            for (PatchMemberCell cell : patch.memberCells()) {
                if (origin.x() >= cell.blockMinX() && origin.x() < cell.blockMinX() + patchStepBlocks
                        && origin.z() >= cell.blockMinZ() && origin.z() < cell.blockMinZ() + patchStepBlocks) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<LandformPatchSummary> preferredPatches(CityBlueprint.Group group,
                                                               Map<String, LandformPatchSummary> patches) {
        List<LandformPatchSummary> result = new ArrayList<>();
        for (String ref : group.preferredPatchRefs()) {
            LandformPatchSummary patch = patches.get(ref);
            if (patch != null) result.add(patch);
        }
        return List.copyOf(result);
    }

    private static BlockPoint patchPlacementOrigin(CityBlueprint.Group group,
                                                   Map<String, LandformPatchSummary> patches,
                                                   int patchStepBlocks,
                                                   BlockBounds planningBounds) {
        CityBlueprint.PlacementRelation placement = group.placementRelation();
        if (placement == null
                || placement.kind() == CityBlueprint.PlacementRelationKind.BETWEEN_GROUPS) return null;
        LandformPatchSummary first = patches.get(placement.patchRefs().get(0));
        LandformPatchSummary second = patches.get(placement.patchRefs().get(1));
        PatchCellPair pair = nearestPatchCellPair(first, second, patchStepBlocks, planningBounds);
        if (pair == null) return null;
        return placement.kind() == CityBlueprint.PlacementRelationKind.ALONG_PATCH_BOUNDARY
                ? pair.firstCenter()
                : new BlockPoint((pair.firstCenter().x() + pair.secondCenter().x()) / 2,
                (pair.firstCenter().z() + pair.secondCenter().z()) / 2);
    }

    private static PatchCellPair nearestPatchCellPair(LandformPatchSummary first,
                                                      LandformPatchSummary second,
                                                      int step,
                                                      BlockBounds planningBounds) {
        PatchCellPair best = null;
        long bestDistance = Long.MAX_VALUE;
        long bestCentrality = Long.MAX_VALUE;
        long targetTwiceX = (long) first.centerBlock().x() + second.centerBlock().x();
        long targetTwiceZ = (long) first.centerBlock().z() + second.centerBlock().z();
        for (PatchMemberCell firstCell : first.memberCells()) {
            BlockBounds firstBounds = new BlockBounds(firstCell.blockMinX(), firstCell.blockMinZ(),
                    firstCell.blockMinX() + step - 1, firstCell.blockMinZ() + step - 1);
            if (!within(firstBounds, planningBounds)) continue;
            BlockPoint firstCenter = cellCenter(firstCell, step);
            for (PatchMemberCell secondCell : second.memberCells()) {
                BlockBounds secondBounds = new BlockBounds(secondCell.blockMinX(), secondCell.blockMinZ(),
                        secondCell.blockMinX() + step - 1, secondCell.blockMinZ() + step - 1);
                if (!within(secondBounds, planningBounds)) continue;
                BlockPoint secondCenter = cellCenter(secondCell, step);
                long dx = (long) firstCenter.x() - secondCenter.x();
                long dz = (long) firstCenter.z() - secondCenter.z();
                long distance = dx * dx + dz * dz;
                long centerDx = (long) firstCenter.x() + secondCenter.x() - targetTwiceX;
                long centerDz = (long) firstCenter.z() + secondCenter.z() - targetTwiceZ;
                long centrality = centerDx * centerDx + centerDz * centerDz;
                if (distance < bestDistance
                        || (distance == bestDistance && centrality < bestCentrality)) {
                    bestDistance = distance;
                    bestCentrality = centrality;
                    best = new PatchCellPair(firstCell, secondCell, firstCenter, secondCenter);
                }
            }
        }
        return best;
    }

    private static BlockBounds slotBounds(BlockPoint center, int span) {
        int minX = center.x() - span / 2;
        int minZ = center.z() - span / 2;
        return new BlockBounds(minX, minZ, minX + span - 1, minZ + span - 1);
    }

    private static BlockBounds slotBounds(BlockPoint origin,
                                          CityGroupSpatialDemand demand) {
        String direction = demand.primaryAxisDirection();
        if (direction.isBlank()) return slotBounds(origin, demand.formationSpanBlocks());
        int halfWidth = demand.formationWidthBlocks() / 2;
        int length = demand.formationLengthBlocks();
        return switch (direction) {
            case "NORTH" -> new BlockBounds(origin.x() - halfWidth, origin.z() - length + 1,
                    origin.x() + halfWidth, origin.z() + halfWidth);
            case "EAST" -> new BlockBounds(origin.x() - halfWidth, origin.z() - halfWidth,
                    origin.x() + length - 1, origin.z() + halfWidth);
            case "SOUTH" -> new BlockBounds(origin.x() - halfWidth, origin.z() - halfWidth,
                    origin.x() + halfWidth, origin.z() + length - 1);
            case "WEST" -> new BlockBounds(origin.x() - length + 1, origin.z() - halfWidth,
                    origin.x() + halfWidth, origin.z() + halfWidth);
            default -> slotBounds(origin, demand.formationSpanBlocks());
        };
    }

    private static boolean overlapsAny(BlockBounds candidate, List<BlockBounds> occupied) {
        return occupied.stream().anyMatch(candidate::overlaps);
    }

    private ConnectivityPlan buildConnectivityPlan(CityBlueprint blueprint,
                                                    Map<String, GroupState> states) {
        List<ConnectivityLink> links = new ArrayList<>();
        Set<String> pairs = new LinkedHashSet<>();
        List<CityBlueprint.Relation> explicit = blueprint.relations().stream()
                .filter(relation -> relation.relationKind() == CityBlueprint.RelationKind.CONNECTION
                        || relation.relationKind() == CityBlueprint.RelationKind.ADJACENCY
                        || relation.relationKind() == CityBlueprint.RelationKind.HIERARCHY)
                .sorted(Comparator.comparingInt((CityBlueprint.Relation relation) -> switch (relation.relationKind()) {
                            case CONNECTION -> 0;
                            case ADJACENCY -> 1;
                            case HIERARCHY -> 2;
                            default -> 3;
                        })
                        .thenComparing(CityBlueprint.Relation::fromGroupId)
                        .thenComparing(CityBlueprint.Relation::toGroupId))
                .toList();
        for (CityBlueprint.Relation relation : explicit) {
            String key = pairKey(relation.fromGroupId(), relation.toGroupId());
            if (!pairs.add(key)) continue;
            GroupState from = states.get(relation.fromGroupId());
            GroupState to = states.get(relation.toGroupId());
            if (from == null || to == null || !from.group().expansionPolicy().allowRelationConnection()
                    || !to.group().expansionPolicy().allowRelationConnection()
                    || !from.group().expansionPolicy().allowOutwardExpansion()
                    || !to.group().expansionPolicy().allowOutwardExpansion()) {
                continue;
            }
            links.add(new ConnectivityLink(from.group().groupId(), to.group().groupId(),
                    "EXPLICIT", relation.relationKind().name(),
                    relation.strength() == CityBlueprint.RelationStrength.HARD, handoffThreshold(from, to),
                    nearestGap(from, to)));
        }

        return new ConnectivityPlan(links);
    }

    private void growConnectivity(Path runDir,
                                    CityLandformReviewPackage review,
                                    JsonObject structureSource,
                                    JsonObject templateCatalog,
                                    CityTemplateCatalog templates,
                                    CityBlueprint blueprint,
                                    Map<String, GroupState> states,
                                    JsonArray occupied,
                                    JsonArray anchors,
                                     JsonArray selections,
                                     CatalogIndex catalog,
                                     ConnectivityPlan plan,
                                     CityStructureTerrainGate terrainGate) throws IOException {
        for (ConnectivityLink link : plan.links) {
            GroupState first = states.get(link.fromGroupId);
            GroupState second = states.get(link.toGroupId);
            boolean firstTurn = true;
            while (nearestGap(first, second) > link.handoffGapBlocks) {
                if (first.anchorCount() >= INTERNAL_MAX_ANCHORS_PER_GROUP
                        && second.anchorCount() >= INTERNAL_MAX_ANCHORS_PER_GROUP) {
                    link.finalGapBlocks = nearestGap(first, second);
                    link.status = "SKIPPED_SAFETY_LIMIT";
                    break;
                }
                GroupState source = firstTurn ? first : second;
                GroupState target = firstTurn ? second : first;
                firstTurn = !firstTurn;
                ConnectionBatch batch = chooseConnectivityBatchWithFallback(runDir, review, structureSource,
                        templateCatalog, templates, blueprint, source, target, occupied, catalog,
                        states, selections, terrainGate);
                if (batch == null) {
                    batch = chooseConnectivityBatchWithFallback(runDir, review, structureSource,
                            templateCatalog, templates, blueprint, target, source, occupied, catalog,
                            states, selections, terrainGate);
                }
                if (batch == null) {
                    link.finalGapBlocks = nearestGap(first, second);
                    link.status = "SKIPPED_NO_LEGAL_PATH";
                    break;
                }
                GroupState owner = states.get(batch.ownerGroupId());
                for (Placement placement : batch.placements()) {
                    commit(placement, anchors, occupied, owner);
                }
                owner.advanceConnectionCursor(batch.placements().size());
                link.connectionStructureCount += batch.placements().size();
                link.connectionBatchCount++;
                link.finalGapBlocks = nearestGap(first, second);
            }
            link.finalGapBlocks = nearestGap(first, second);
            if (link.finalGapBlocks <= link.handoffGapBlocks) {
                link.status = "HANDOFF_READY";
            }
        }
    }

    private ConnectionConfiguration resolveConnectionConfiguration(CityBlueprint.Group group,
                                                                   CatalogIndex catalog) {
        CityBlueprint.ConnectionPlan plan = group.connectionPlan();
        boolean inheritedPool = plan == null || plan.structurePoolRef() == null;
        boolean inheritedAlgorithm = plan == null || plan.algorithmProfileRef() == null;
        boolean inheritedDensity = plan == null || plan.densityClass() == null;
        String poolRef = inheritedPool ? group.fillPoolRef() : plan.structurePoolRef();
        String algorithmProfileRef = inheritedAlgorithm
                ? group.algorithmProfileRef() : plan.algorithmProfileRef();
        CityBlueprint.DensityClass density = inheritedDensity
                ? group.densityClass() : plan.densityClass();
        String algorithm = catalog.algorithm(algorithmProfileRef);
        String plannerType = "LINEAR".equals(algorithm)
                ? "guide_line_dual_side" : "compound_cluster";
        CityBlueprint.ConnectionParameters parameters = plan == null
                ? CityBlueprint.ConnectionParameters.empty() : plan.parameters();
        catalog.pool(poolRef);
        return new ConnectionConfiguration(poolRef, algorithmProfileRef, algorithm, plannerType, density,
                parameters, inheritedPool, inheritedAlgorithm, inheritedDensity,
                groupLayoutPlanner.parameters(algorithm, density));
    }

    private ConnectionBatch chooseConnectivityBatch(Path runDir,
                                                     CityLandformReviewPackage review,
                                                     JsonObject structureSource,
                                                     JsonObject templateCatalog,
                                                     CityTemplateCatalog templates,
                                                     CityBlueprint blueprint,
                                                     GroupState source,
                                                     GroupState target,
                                                      JsonArray occupied,
                                                      CatalogIndex catalog,
                                                      Map<String, GroupState> states,
                                                      JsonArray selections,
                                                      CityStructureTerrainGate terrainGate,
                                                      int requestedBatchSize) throws IOException {
        int available = INTERNAL_MAX_ANCHORS_PER_GROUP - source.anchorCount();
        if (available < 1) return null;
        ConnectionConfiguration configuration = source.connectionConfiguration();
        List<String> pool = catalog.pool(configuration.structurePoolRef());
        if (pool.isEmpty()) return null;
        CommittedArray focus = source.nearestArray(target);
        if (focus == null) return null;
        int requested = Math.min(available, Math.max(1, requestedBatchSize));
        double remainingGap = nearestGap(source, target);
        int terminalWindow = configuration.layoutParameters().landUseHandoffGapBlocks()
                + configuration.layoutParameters().maximumEdgeGapBlocks();
        boolean terminalBatch = remainingGap <= terminalWindow && requested == 1;
        List<ConnectionItem> connectionItems = connectionItems(blueprint, source, pool, catalog, requested);
        if (connectionItems.isEmpty()) return null;
        String direction = connectionDirection(focus.bodyBounds(), target.bodyEnvelopes());
        String arrayId = source.group().groupId() + "_connectivity_array_"
                + String.format("%03d", source.connectionBatchCount() + 1);
        JsonObject loopState = automaticLoopState(blueprint, templateCatalog, occupied);
        JsonObject request = automaticConnectionRequest(source, configuration, focus, direction,
                arrayId, connectionItems, terminalBatch);
        JsonObject event = new JsonObject();
        event.addProperty("sequence", selections.size() + 1);
        event.addProperty("phase", PlacementPhase.CONNECTIVITY.traceName);
        event.addProperty("groupId", source.group().groupId());
        event.addProperty("connectivityTargetGroupId", target.group().groupId());
        event.addProperty("status", "planning_complete_array");
        event.addProperty("arrayId", arrayId);
        event.addProperty("plannerType", configuration.plannerType());
        event.addProperty("focusArrayId", focus.arrayId());
        event.add("focusBodyEnvelope", CityStructureCandidateEnvelope.boundsJson(focus.bodyBounds()));
        event.addProperty("direction", direction);
        event.add("resolvedConnectionPlan", configuration.asJson());
        event.add("semanticParameters", connectionParametersJson(configuration.parameters()));
        event.addProperty("actualBodyGapMin", terminalBatch
                ? 0 : configuration.layoutParameters().targetEdgeGapBlocks());
        event.addProperty("actualBodyGapMax", configuration.layoutParameters().maximumEdgeGapBlocks());
        event.addProperty("requestedBatchSize", requested);
        event.addProperty("terminalBatch", terminalBatch);
        try {
            CityStructureArrayLayoutLoopPlanner.ExpansionCandidateSetResult result =
                    continuousArrayPlanner.planExpansionCandidates(runDir, review, structureSource,
                            loopState, request);
            JsonArray candidates = array(result.candidateSet(), "arrayCandidates");
            List<AutomaticCandidate> legal = new ArrayList<>();
            JsonArray terrainGateRejections = new JsonArray();
            double currentTargetGap = nearestGap(source, target);
            for (JsonElement element : candidates) {
                if (!element.isJsonObject()) continue;
                JsonObject candidate = element.getAsJsonObject();
                AutomaticCandidate accepted = automaticCandidate(candidate, source, target,
                        states, connectionItems, currentTargetGap, terrainGate, terrainGateRejections);
                if (accepted != null) legal.add(accepted);
            }
            legal.sort(Comparator.comparingDouble(AutomaticCandidate::targetGapBlocks)
                    .thenComparing(Comparator.comparingDouble(AutomaticCandidate::engineScore).reversed())
                    .thenComparing(candidate -> string(candidate.candidate(), "candidateId")));
            event.addProperty("candidateCount", candidates.size());
            event.addProperty("legalCandidateCount", legal.size());
            event.add("terrainGateRejections", terrainGateRejections);
            event.add("frontierSearchTrace", array(result.candidateSet(), "frontierSearchTrace").deepCopy());
            if (legal.isEmpty()) {
                event.addProperty("status", "no_legal_candidate");
                event.addProperty("reasonCode", "CITY_BLUEPRINT_CONNECTIVITY_ARRAY_UNAVAILABLE");
                selections.add(event);
                return null;
            }
            AutomaticCandidate chosen = legal.get(0);
            event.addProperty("status", "committed");
            event.addProperty("candidateId", string(chosen.candidate(), "candidateId"));
            event.addProperty("frontierRing", string(chosen.candidate(), "frontierRing"));
            event.addProperty("engineScore", chosen.engineScore());
            event.addProperty("targetGapBeforeBlocks", currentTargetGap);
            event.addProperty("targetGapAfterBlocks", chosen.targetGapBlocks());
            event.addProperty("connectionStructureCount", chosen.placements().size());
            JsonArray anchorIds = new JsonArray();
            chosen.placements().forEach(placement -> anchorIds.add(string(placement.anchor(), "anchorId")));
            event.add("committedAnchorIds", anchorIds);
            selections.add(event);
            return new ConnectionBatch(source.group().groupId(), chosen.placements());
        } catch (IllegalArgumentException exception) {
            event.addProperty("status", "no_legal_candidate");
            event.addProperty("reasonCode", "CITY_BLUEPRINT_CONNECTIVITY_ARRAY_UNAVAILABLE");
            event.addProperty("plannerMessage", exception.getMessage());
            selections.add(event);
            return null;
        }
    }

    private ConnectionBatch chooseConnectivityBatchWithFallback(Path runDir,
                                                                 CityLandformReviewPackage review,
                                                                 JsonObject structureSource,
                                                                 JsonObject templateCatalog,
                                                                 CityTemplateCatalog templates,
                                                                 CityBlueprint blueprint,
                                                                 GroupState source,
                                                                 GroupState target,
                                                                 JsonArray occupied,
                                                                 CatalogIndex catalog,
                                                                 Map<String, GroupState> states,
                                                                 JsonArray selections,
                                                                 CityStructureTerrainGate terrainGate) throws IOException {
        for (int batchSize : connectionBatchSizes(source, target)) {
            ConnectionBatch batch = chooseConnectivityBatch(runDir, review, structureSource,
                    templateCatalog, templates, blueprint, source, target, occupied, catalog,
                    states, selections, terrainGate, batchSize);
            if (batch != null) {
                return batch;
            }
        }
        return null;
    }

    private ConnectionBatch choosePercentageExpansionBatch(Path runDir,
                                                            CityLandformReviewPackage review,
                                                            JsonObject structureSource,
                                                            JsonObject templateCatalog,
                                                            CityTemplateCatalog templates,
                                                            CityBlueprint blueprint,
                                                            GroupState source,
                                                            JsonArray occupied,
                                                            CatalogIndex catalog,
                                                            Map<String, GroupState> states,
                                                            JsonArray selections,
                                                            CityStructureTerrainGate terrainGate,
                                                            int requestedBatchSize) throws IOException {
        ConnectionConfiguration configuration = percentageExpansionConfiguration(source);
        List<String> pool = catalog.pool(source.group().fillPoolRef());
        if (pool.isEmpty()) return null;
        int requested = Math.min(INTERNAL_MAX_ANCHORS_PER_GROUP - source.anchorCount(),
                Math.max(1, requestedBatchSize));
        if (requested < 1) return null;
        List<ConnectionItem> items = connectionItems(blueprint, source, pool, catalog, requested,
                source.percentageFillCursor());
        for (String direction : source.percentageExpansionDirections()) {
            CommittedArray focus = source.outwardArray(direction);
            if (focus == null) continue;
            String arrayId = source.group().groupId() + "_percentage_array_"
                    + String.format("%03d", source.percentageBatchCount() + 1);
            JsonObject request = automaticConnectionRequest(source, configuration, focus, direction,
                    arrayId, items, false);
            JsonObject event = new JsonObject();
            event.addProperty("sequence", selections.size() + 1);
            event.addProperty("phase", PlacementPhase.PERCENTAGE.traceName);
            event.addProperty("groupId", source.group().groupId());
            event.addProperty("status", "planning_complete_array");
            event.addProperty("arrayId", arrayId);
            event.addProperty("focusArrayId", focus.arrayId());
            event.addProperty("direction", direction);
            event.addProperty("requestedBatchSize", requested);
            try {
                CityStructureArrayLayoutLoopPlanner.ExpansionCandidateSetResult result =
                        continuousArrayPlanner.planExpansionCandidates(runDir, review, structureSource,
                                automaticLoopState(blueprint, templateCatalog, occupied), request);
                JsonArray candidates = array(result.candidateSet(), "arrayCandidates");
                List<AutomaticCandidate> legal = new ArrayList<>();
                JsonArray terrainGateRejections = new JsonArray();
                for (JsonElement element : candidates) {
                    if (!element.isJsonObject()) continue;
                    AutomaticCandidate accepted = automaticPercentageCandidate(element.getAsJsonObject(),
                            source, states, items, direction, terrainGate, terrainGateRejections);
                    if (accepted != null) legal.add(accepted);
                }
                legal.sort(Comparator.comparingDouble(AutomaticCandidate::engineScore).reversed()
                        .thenComparing(candidate -> string(candidate.candidate(), "candidateId")));
                event.addProperty("candidateCount", candidates.size());
                event.addProperty("legalCandidateCount", legal.size());
                event.add("terrainGateRejections", terrainGateRejections);
                if (legal.isEmpty()) {
                    event.addProperty("status", "no_legal_candidate");
                    event.addProperty("reasonCode", "CITY_BLUEPRINT_PERCENTAGE_ARRAY_DIRECTION_UNAVAILABLE");
                    selections.add(event);
                    continue;
                }
                AutomaticCandidate chosen = legal.get(0);
                event.addProperty("status", "committed");
                event.addProperty("candidateId", string(chosen.candidate(), "candidateId"));
                event.addProperty("percentageStructureCount", chosen.placements().size());
                JsonArray anchorIds = new JsonArray();
                chosen.placements().forEach(placement -> anchorIds.add(
                        string(placement.anchor(), "anchorId")));
                event.add("committedAnchorIds", anchorIds);
                selections.add(event);
                return new ConnectionBatch(source.group().groupId(), chosen.placements());
            } catch (IllegalArgumentException exception) {
                event.addProperty("status", "no_legal_candidate");
                event.addProperty("reasonCode", "CITY_BLUEPRINT_PERCENTAGE_ARRAY_DIRECTION_UNAVAILABLE");
                event.addProperty("plannerMessage", exception.getMessage());
                selections.add(event);
            }
        }
        return null;
    }

    private ConnectionConfiguration percentageExpansionConfiguration(GroupState source) {
        String plannerType = "LINEAR".equals(source.layoutAlgorithm())
                ? "guide_line_dual_side" : "compound_cluster";
        return new ConnectionConfiguration(source.group().fillPoolRef(),
                source.group().algorithmProfileRef(), source.layoutAlgorithm(), plannerType,
                source.group().densityClass(), CityBlueprint.ConnectionParameters.empty(),
                true, true, true, source.layoutParameters());
    }

    private List<Integer> connectionBatchSizes(GroupState source, GroupState target) {
        int available = INTERNAL_MAX_ANCHORS_PER_GROUP - source.anchorCount();
        if (available < 1) {
            return List.of();
        }
        ConnectionConfiguration configuration = source.connectionConfiguration();
        int maximum = Math.min(available, connectionBatchSize(configuration));
        int terminalWindow = configuration.layoutParameters().landUseHandoffGapBlocks()
                + configuration.layoutParameters().maximumEdgeGapBlocks();
        if (nearestGap(source, target) <= terminalWindow) {
            return List.of(1);
        }
        List<Integer> sizes = new ArrayList<>();
        for (int size = maximum; size >= 1; size--) {
            sizes.add(size);
        }
        return List.copyOf(sizes);
    }

    private static int connectionBatchSize(ConnectionConfiguration configuration) {
        CityBlueprint.WidthClass width = configuration.parameters().widthClass();
        return switch (width == null ? CityBlueprint.WidthClass.MEDIUM : width) {
            case NARROW -> 4;
            case MEDIUM -> 6;
            case WIDE -> 8;
        };
    }

    private static JsonObject automaticLoopState(CityBlueprint blueprint, JsonObject templateCatalog,
                                                 JsonArray occupied) {
        JsonObject state = new JsonObject();
        state.addProperty("schema", CityStructureArrayLayoutLoopPlanner.STATE_SCHEMA);
        state.addProperty("planningMode", CityStructureArrayLayoutLoopPlanner.PLANNING_MODE);
        state.addProperty("cityId", blueprint.cityId());
        state.addProperty("stateId", "blueprint_automatic_connectivity");
        state.addProperty("iteration", 0);
        state.addProperty("maxArrayPlans", 1);
        state.add("executedArrayIds", new JsonArray());
        state.add("occupiedEnvelopes", occupied.deepCopy());
        state.add("templateCatalog", templateCatalog.deepCopy());
        return state;
    }

    private JsonObject automaticConnectionRequest(GroupState source,
                                                  ConnectionConfiguration configuration,
                                                  CommittedArray focus,
                                                  String direction,
                                                  String arrayId,
                                                  List<ConnectionItem> items,
                                                  boolean terminalBatch) {
        JsonObject request = new JsonObject();
        request.addProperty("candidateCount", 5);
        request.addProperty("structureTerrainGateOwnsLegality", true);
        JsonObject focusRef = new JsonObject();
        focusRef.addProperty("arrayId", focus.arrayId());
        request.add("focusRef", focusRef);
        request.addProperty("direction", direction);
        JsonObject policy = new JsonObject();
        policy.addProperty("actualBodyGapMin", terminalBatch
                ? 0 : configuration.layoutParameters().targetEdgeGapBlocks());
        policy.addProperty("actualBodyGapMax",
                configuration.layoutParameters().maximumEdgeGapBlocks());
        policy.addProperty("frontierExpansionStepBlocks", 4);
        policy.addProperty("frontierMaxExpansionRounds", 1);
        request.add("expansionPolicy", policy);

        JsonObject item = new JsonObject();
        item.addProperty("arrayId", arrayId);
        item.addProperty("role", source.group().role());
        item.addProperty("plannerType", configuration.plannerType());
        item.addProperty("priority", priorityRank(source.group().priority()) * 1000 + source.anchorCount() + 1);
        item.addProperty("targetCount", items.size());
        item.addProperty("minCount", items.size());
        item.addProperty("maxCount", items.size());
        item.addProperty("variantSelectionMode", "round_robin");
        JsonArray required = new JsonArray();
        for (int index = 0; index < items.size(); index++) {
            ConnectionItem connection = items.get(index);
            JsonObject value = new JsonObject();
            value.addProperty("itemId", "connection_" + String.format("%03d", index + 1));
            value.addProperty("templateId", connection.template().templateId());
            value.addProperty("variantId", connection.template().variantId());
            value.addProperty("failurePolicy", "hard_block");
            required.add(value);
        }
        item.add("requiredItems", required);
        applyConnectionParameters(item, configuration);
        request.add("nextArrayLayoutPlanItem", item);
        return request;
    }

    private static void applyConnectionParameters(JsonObject item, ConnectionConfiguration configuration) {
        CityBlueprint.ConnectionParameters parameters = configuration.parameters();
        if ("compound_cluster".equals(configuration.plannerType())) {
            CityBlueprint.ClusterShape shape = parameters.clusterShape();
            if (shape == null) {
                shape = switch (configuration.algorithm()) {
                    case "GRID" -> CityBlueprint.ClusterShape.GRID;
                    case "COURTYARD" -> CityBlueprint.ClusterShape.COURTYARD;
                    case "CENTER_SYMMETRIC" -> CityBlueprint.ClusterShape.COURTYARD;
                    default -> CityBlueprint.ClusterShape.ORGANIC_COMPACT;
                };
            }
            item.addProperty("clusterShape", shape.name().toLowerCase());
            return;
        }
        item.addProperty("sideMode", (parameters.sideMode() == null
                ? CityBlueprint.SideMode.BOTH : parameters.sideMode()).name());
        item.addProperty("stagger", parameters.stagger() == null || parameters.stagger());
        item.addProperty("widthClass", (parameters.widthClass() == null
                ? CityBlueprint.WidthClass.MEDIUM : parameters.widthClass()).name());
    }

    private List<ConnectionItem> connectionItems(CityBlueprint blueprint, GroupState source,
                                                 List<String> pool, CatalogIndex catalog, int requested) {
        return connectionItems(blueprint, source, pool, catalog, requested, source.connectionFillCursor());
    }

    private List<ConnectionItem> connectionItems(CityBlueprint blueprint, GroupState source,
                                                 List<String> pool, CatalogIndex catalog, int requested,
                                                 int fillCursor) {
        List<ConnectionItem> result = new ArrayList<>();
        for (int index = 0; index < requested; index++) {
            int itemIndex = index;
            String structureRef = pool.get(Math.floorMod(fillCursor + index, pool.size()));
            List<TemplateCandidate> templates = new ArrayList<>(catalog.templates(structureRef));
            templates.sort(Comparator.comparingLong(candidate -> tieKey(blueprint.generationSeed(),
                    source.group().groupId(), "connection_array", Integer.toString(itemIndex), structureRef,
                    candidate.templateId(), candidate.variantId())));
            result.add(new ConnectionItem(structureRef, templates.get(0)));
        }
        return List.copyOf(result);
    }

    private AutomaticCandidate automaticPercentageCandidate(JsonObject candidate,
                                                             GroupState source,
                                                             Map<String, GroupState> states,
                                                             List<ConnectionItem> items,
                                                             String direction,
                                                             CityStructureTerrainGate terrainGate,
                                                             JsonArray terrainGateRejections) {
        JsonArray candidateAnchors = array(candidate, "anchors");
        JsonArray candidateItems = array(candidate, "items");
        if (candidateAnchors.size() != items.size() || candidateItems.size() != items.size()) return null;
        BlockBounds candidateUnion = null;
        List<Placement> placements = new ArrayList<>();
        for (int index = 0; index < candidateAnchors.size(); index++) {
            if (!candidateAnchors.get(index).isJsonObject() || !candidateItems.get(index).isJsonObject()) {
                return null;
            }
            JsonObject anchor = candidateAnchors.get(index).getAsJsonObject().deepCopy();
            JsonObject item = candidateItems.get(index).getAsJsonObject();
            JsonObject collisionJson = requiredObject(item, "estimatedCollisionEnvelope").deepCopy();
            BlockBounds collision = bounds(collisionJson);
            if (violatesGroupSeparation(collision, source, states)) return null;
            ConnectionItem content = items.get(index);
            TerrainCandidateEvaluation terrain = anchorTerrainEvaluation(anchor, collision, source,
                    content.structureRef(), terrainGate);
            if (!terrain.passed()) {
                if (terrainGateRejections.size() < 16) terrainGateRejections.add(terrain.trace());
                return null;
            }
            anchor.addProperty("resolvedTerrainMode", terrain.resolvedTerrainMode());
            candidateUnion = union(candidateUnion, collision);
            anchor.addProperty("anchorId", source.group().groupId() + "_percentage_"
                    + String.format("%03d", source.anchorCount() + index + 1));
            anchor.addProperty("placementGroupId", source.group().groupId());
            anchor.addProperty("blueprintStructureRef", content.structureRef());
            anchor.addProperty("blueprintRequired", false);
            anchor.addProperty("blueprintPlacementPhase", PlacementPhase.PERCENTAGE.traceName);
            anchor.addProperty("selectionReason", "Programmatic percentage outward array selection");
            JsonObject layout = new JsonObject();
            layout.addProperty("plannerType", string(candidate, "plannerType"));
            layout.addProperty("frontierRing", string(candidate, "frontierRing"));
            layout.addProperty("actualBodyGapBlocks", intValue(candidate, "actualBodyGapBlocks", 0));
            layout.addProperty("focusArrayId", string(requiredObject(candidate, "focusRef"), "arrayId"));
            layout.addProperty("direction", direction);
            layout.addProperty("arrayBatchSize", candidateAnchors.size());
            layout.addProperty("outwardGuided", true);
            layout.addProperty("growthPurpose", "PERCENTAGE_AREA_FILL");
            anchor.add("blueprintLayout", layout);
            placements.add(new Placement(anchor, collisionJson, content.structureRef(), false,
                    PlacementPhase.PERCENTAGE, ConnectivityFit.allowed(1.0, 0.0, null)));
        }
        if (candidateUnion == null || source.extent() == null
                || !extendsInDirection(candidateUnion, source.extent(), direction)) return null;
        Nearest nearestSource = nearest(candidateUnion, source.envelopes(), source.group().groupId());
        if (nearestSource == null || nearestSource.gapBlocks()
                > source.layoutParameters().maximumEdgeGapBlocks()) return null;
        return new AutomaticCandidate(candidate.deepCopy(), List.copyOf(placements), 0.0,
                doubleValue(candidate, "score", 0.0));
    }

    private static boolean extendsInDirection(BlockBounds candidate, BlockBounds extent, String direction) {
        return switch (direction) {
            case "north" -> candidate.minZ() < extent.minZ();
            case "south" -> candidate.maxZ() > extent.maxZ();
            case "east" -> candidate.maxX() > extent.maxX();
            case "west" -> candidate.minX() < extent.minX();
            case "northeast" -> candidate.minZ() < extent.minZ() || candidate.maxX() > extent.maxX();
            case "northwest" -> candidate.minZ() < extent.minZ() || candidate.minX() < extent.minX();
            case "southeast" -> candidate.maxZ() > extent.maxZ() || candidate.maxX() > extent.maxX();
            case "southwest" -> candidate.maxZ() > extent.maxZ() || candidate.minX() < extent.minX();
            default -> false;
        };
    }

    private AutomaticCandidate automaticCandidate(JsonObject candidate, GroupState source,
                                                   GroupState target, Map<String, GroupState> states,
                                                   List<ConnectionItem> connectionItems,
                                                   double currentTargetGap,
                                                   CityStructureTerrainGate terrainGate,
                                                   JsonArray terrainGateRejections) {
        JsonArray anchors = array(candidate, "anchors");
        JsonArray items = array(candidate, "items");
        if (anchors.size() != connectionItems.size() || items.size() != connectionItems.size()) return null;
        BlockBounds candidateUnion = null;
        List<Placement> placements = new ArrayList<>();
        for (int index = 0; index < anchors.size(); index++) {
            if (!anchors.get(index).isJsonObject() || !items.get(index).isJsonObject()) return null;
            JsonObject anchor = anchors.get(index).getAsJsonObject().deepCopy();
            JsonObject item = items.get(index).getAsJsonObject();
            JsonObject collisionJson = requiredObject(item, "estimatedCollisionEnvelope").deepCopy();
            BlockBounds collision = bounds(collisionJson);
            if (violatesGroupSeparation(collision, source, states)) return null;
            ConnectionItem connection = connectionItems.get(index);
            TerrainCandidateEvaluation terrain = anchorTerrainEvaluation(anchor, collision, source,
                    connection.structureRef(), terrainGate);
            if (!terrain.passed()) {
                if (terrainGateRejections.size() < 16) terrainGateRejections.add(terrain.trace());
                return null;
            }
            anchor.addProperty("resolvedTerrainMode", terrain.resolvedTerrainMode());
            candidateUnion = union(candidateUnion, collision);
            anchor.addProperty("anchorId", source.group().groupId() + "_connectivity_"
                    + String.format("%03d", source.anchorCount() + index + 1));
            anchor.addProperty("placementGroupId", source.group().groupId());
            anchor.addProperty("blueprintStructureRef", connection.structureRef());
            anchor.addProperty("blueprintRequired", false);
            anchor.addProperty("blueprintPlacementPhase", PlacementPhase.CONNECTIVITY.traceName);
            anchor.addProperty("selectionReason", "Programmatic CityBlueprint continuous array selection");
            JsonObject layout = new JsonObject();
            layout.addProperty("plannerType", string(candidate, "plannerType"));
            layout.addProperty("frontierRing", string(candidate, "frontierRing"));
            layout.addProperty("actualBodyGapBlocks", intValue(candidate, "actualBodyGapBlocks", 0));
            layout.add("resolvedConnectionPlan", source.connectionConfiguration().asJson());
            layout.add("semanticParameters",
                    connectionParametersJson(source.connectionConfiguration().parameters()));
            layout.addProperty("focusArrayId", string(requiredObject(candidate, "focusRef"), "arrayId"));
            layout.addProperty("direction", string(candidate, "direction"));
            layout.addProperty("arrayBatchSize", anchors.size());
            layout.addProperty("outwardGuided", true);
            layout.addProperty("growthPurpose", "RELATION_CONNECTION");
            anchor.add("blueprintLayout", layout);
            placements.add(new Placement(anchor, collisionJson, connection.structureRef(), false,
                    PlacementPhase.CONNECTIVITY, ConnectivityFit.allowed(1.0, 0.0, null)));
        }
        if (candidateUnion == null) return null;
        Nearest nearestSource = nearest(candidateUnion, source.envelopes(), source.group().groupId());
        if (nearestSource == null
                || nearestSource.gapBlocks() > source.connectionConfiguration()
                .layoutParameters().maximumEdgeGapBlocks()) return null;
        Nearest nearestTarget = nearest(candidateUnion, target.envelopes(), target.group().groupId());
        if (nearestTarget == null || nearestTarget.gapBlocks() >= currentTargetGap) return null;
        return new AutomaticCandidate(candidate.deepCopy(), List.copyOf(placements),
                nearestTarget.gapBlocks(), doubleValue(candidate, "score", 0.0));
    }

    private static String connectionDirection(BlockBounds focusBody, List<BlockBounds> targetBodies) {
        Nearest nearest = nearest(focusBody, targetBodies, "target");
        BlockPoint target = nearest == null || nearest.edge() == null
                ? targetBodies.get(0).center()
                : new BlockPoint(nearest.edge().toX(), nearest.edge().toZ());
        int dx = target.x() - centerX(focusBody);
        int dz = target.z() - centerZ(focusBody);
        double ax = Math.abs(dx);
        double az = Math.abs(dz);
        if (ax > az * 1.8) return dx >= 0 ? "east" : "west";
        if (az > ax * 1.8) return dz >= 0 ? "south" : "north";
        return (dz >= 0 ? "south" : "north") + (dx >= 0 ? "east" : "west");
    }

    private static JsonObject connectionParametersJson(CityBlueprint.ConnectionParameters parameters) {
        JsonObject value = new JsonObject();
        if (parameters.clusterShape() != null) value.addProperty("clusterShape", parameters.clusterShape().name());
        if (parameters.sideMode() != null) value.addProperty("sideMode", parameters.sideMode().name());
        if (parameters.stagger() != null) value.addProperty("stagger", parameters.stagger());
        if (parameters.widthClass() != null) value.addProperty("widthClass", parameters.widthClass().name());
        return value;
    }

    private static int handoffThreshold(GroupState first, GroupState second) {
        return Math.min(first.connectionConfiguration().layoutParameters().landUseHandoffGapBlocks(),
                second.connectionConfiguration().layoutParameters().landUseHandoffGapBlocks());
    }

    private static double nearestGap(GroupState first, GroupState second) {
        Nearest nearest = nearest(first.extent(), second.envelopes(), second.group().groupId());
        return nearest == null ? Double.POSITIVE_INFINITY : nearest.gapBlocks();
    }

    private static String pairKey(String first, String second) {
        return first.compareTo(second) <= 0 ? first + "\u0000" + second : second + "\u0000" + first;
    }

    private JsonObject candidatePlan(CityBlueprint blueprint, GroupState state, String structureRef,
                                     PlacementPhase phase, int ordinal, TemplateCandidate template,
                                     JsonObject templateCatalog, CityTemplateCatalog templates,
                                     Map<String, GroupState> states,
                                     GroupState connectivityTarget) {
        return candidatePlan(blueprint, state, structureRef, phase, ordinal, template,
                templateCatalog, templates, states, connectivityTarget, null, "blueprint_preferred");
    }

    private JsonObject candidatePlan(CityBlueprint blueprint, GroupState state, String structureRef,
                                     PlacementPhase phase, int ordinal, TemplateCandidate template,
                                     JsonObject templateCatalog, CityTemplateCatalog templates,
                                     Map<String, GroupState> states,
                                     GroupState connectivityTarget,
                                     List<LandformPatchSummary> overridePatches,
                                     String patchSelectionScope) {
        JsonObject plan = new JsonObject();
        plan.addProperty("schema", CityStructureArrayCandidatePlanner.PLAN_SCHEMA);
        plan.addProperty("cityId", blueprint.cityId());
        plan.addProperty("arrayId", state.group().groupId() + "_" + phase.anchorLabel
                + "_" + String.format("%03d", ordinal));
        plan.addProperty("displayRole", state.group().role());
        JsonArray patches = new JsonArray();
        List<LandformPatchSummary> candidatePatches = overridePatches != null ? overridePatches
                : phase == PlacementPhase.CONNECTIVITY
                ? state.connectionPatches() : state.formationPatches();
        candidatePatches.forEach(patch -> patches.add(patch.landformPatchId()));
        plan.add("candidatePatchRefs", patches);
        JsonArray templateIds = new JsonArray();
        templateIds.add(template.templateId());
        plan.add("templateIds", templateIds);
        plan.addProperty("variantId", template.variantId());
        plan.addProperty("arrayCount", 1);
        plan.addProperty("variantSelectionMode", "round_robin");
        JsonArray patterns = new JsonArray();
        patterns.add(pattern(state.layoutAlgorithm()));
        plan.add("patterns", patterns);
        plan.addProperty("priority", priorityRank(state.group().priority()) * 1000 + ordinal);
        plan.add("templateCatalog", templateCatalog.deepCopy());
        BlockPoint requestedOrigin = phase == PlacementPhase.CONNECTIVITY || state.anchorCount() > 0
                ? null : initialPlacementOrigin(state, states);
        PatchMemberCell seedCell = phase == PlacementPhase.CONNECTIVITY ? null
                : requestedOrigin == null ? frontierCell(state, candidatePatches)
                : nearestMemberCellToPointFromCells(memberCells(candidatePatches), requestedOrigin,
                state.patchStepBlocks(),
                state.formationBounds());
        BlockPoint seedPoint = phase == PlacementPhase.CONNECTIVITY
                ? frontierAnchorCenterToward(state, connectivityTarget)
                : requestedOrigin != null ? requestedOrigin
                : seedCell == null ? state.patches().get(0).centerBlock()
                : cellCenter(seedCell, state.patchStepBlocks());
        OutwardTarget outward = phase == PlacementPhase.CONNECTIVITY && connectivityTarget != null
                ? outwardTarget(state, connectivityTarget, seedPoint) : OutwardTarget.none();
        CityBlueprintGroupLayoutPlanner.Frame proposedFrame = groupLayoutPlanner.worldAxisLocked(
                state.layoutAlgorithm())
                ? groupLayoutPlanner.worldFrame(seedPoint)
                : groupLayoutPlanner.frame(seedPoint, outward.point(), blueprint.generationSeed(),
                state.group().groupId());
        state.ensureLayoutFrame(proposedFrame);
        CityTemplateCatalog.Template physicalTemplate = templates.requireTemplate(
                template.templateId(), template.variantId());
        int physicalSpan = Math.max(physicalTemplate.width(), physicalTemplate.depth())
                + physicalTemplate.clearanceBlocks() * 2;
        physicalSpan = Math.max(physicalSpan, state.spatialDemand().maximumTemplateSpanBlocks());
        int footprintSpan = state.fixedInternalSpacing()
                ? state.spatialDemand().maximumTemplateSpanBlocks() : physicalSpan;
        CityBlueprintGroupLayoutPlanner.Proposal layout = groupLayoutPlanner.propose(
                state.layoutAlgorithm(), state.group().densityClass(), blueprint.generationSeed(),
                state.group().groupId(), state.layoutSlotIndex(), state.layoutFrame(), seedPoint,
                outward.point(), outward.pending(), footprintSpan);
        if (("COMPACT".equals(state.layoutAlgorithm()) || "COURTYARD".equals(state.layoutAlgorithm()))
                && phase != PlacementPhase.CONNECTIVITY) {
            layout = layoutWithLegalCardinalFrontage(blueprint, state, physicalTemplate, seedPoint,
                    outward, footprintSpan, layout);
        }
        plan.addProperty("spacingBlocks", layout.spacingBlocks());
        plan.add("candidateOrigins", layout.guidesJson());
        JsonObject layoutTrace = layout.traceJson();
        if (state.exactInternalGuides() && phase != PlacementPhase.CONNECTIVITY) {
            plan.addProperty("exactCandidateOriginsOnly", true);
        }
        if ("LINEAR".equals(state.layoutAlgorithm()) && phase != PlacementPhase.CONNECTIVITY) {
            if (state.anchorCount() > 0) {
                BlockPoint candidate = layout.guides().get(0);
                BlockPoint streetTarget = projectOntoAxis(state.layoutFrame(), candidate);
                applyFrontage(plan, layoutTrace, physicalTemplate, candidate, streetTarget,
                        "linear_street_band:" + state.group().groupId());
                plan.addProperty("linearFrontageSatisfied", plan.get("frontageSatisfied").getAsBoolean());
                if (layoutTrace.has("frontageFailure")) {
                    layoutTrace.add("linearFrontageFailure", layoutTrace.get("frontageFailure").deepCopy());
                }
            }
        } else if (phase != PlacementPhase.CONNECTIVITY && layout.frontageTarget() != null) {
            applyFrontage(plan, layoutTrace, physicalTemplate, layout.guides().get(0),
                    layout.frontageTarget(), state.layoutAlgorithm().toLowerCase(java.util.Locale.ROOT)
                            + "_internal_road:" + state.group().groupId());
        }
        layoutTrace.addProperty("preferredPatchZone", state.group().preferredPatchZone().name());
        layoutTrace.addProperty("patchSelectionScope", patchSelectionScope);
        if (phase == PlacementPhase.PERCENTAGE) {
            layoutTrace.addProperty("outwardGuided", true);
            layoutTrace.addProperty("growthPurpose", "PERCENTAGE_AREA_FILL");
        }
        if (state.group().placementRelation() != null) {
            layoutTrace.addProperty("placementRelation", state.group().placementRelation().kind().name());
        }
        if (state.compositionSlot() != null) {
            layoutTrace.add("arrayCompositionSlot", state.compositionSlot().asJson());
        }
        if (state.anchorCount() == 0 && seedCell != null) {
            JsonObject coreSeedCell = new JsonObject();
            coreSeedCell.addProperty("cellX", seedCell.cellX());
            coreSeedCell.addProperty("cellZ", seedCell.cellZ());
            coreSeedCell.addProperty("blockMinX", seedCell.blockMinX());
            coreSeedCell.addProperty("blockMinZ", seedCell.blockMinZ());
            layoutTrace.add("coreSeedCell", coreSeedCell);
        }
        plan.add("blueprintLayout", layoutTrace);
        PatchMemberCell guideCell = nearestMemberCellToPoint(candidatePatches, layout.guides().get(0),
                state.patchStepBlocks(), state.legalBounds(phase == PlacementPhase.CONNECTIVITY
                        || phase == PlacementPhase.PERCENTAGE));
        PatchMemberCell legalRegionGuide = phase == PlacementPhase.REQUIRED && state.anchorCount() == 0
                ? null : guideCell;
        plan.add("candidateLegalRegion", legalRegion(state, candidatePatches, legalRegionGuide,
                phase == PlacementPhase.CONNECTIVITY || phase == PlacementPhase.PERCENTAGE));
        return plan;
    }

    private CityBlueprintGroupLayoutPlanner.Proposal layoutWithLegalCardinalFrontage(
            CityBlueprint blueprint,
            GroupState state,
            CityTemplateCatalog.Template template,
            BlockPoint seedPoint,
            OutwardTarget outward,
            int footprintSpan,
            CityBlueprintGroupLayoutPlanner.Proposal initial) {
        CityBlueprintGroupLayoutPlanner.Frame frame = state.layoutFrame();
        List<CityBlueprintGroupLayoutPlanner.Frame> frames = List.of(
                frame,
                frame.reorient(-frame.axisZ(), frame.axisX()),
                frame.reorient(-frame.axisX(), -frame.axisZ()),
                frame.reorient(frame.axisZ(), -frame.axisX()));
        for (int index = 0; index < frames.size(); index++) {
            CityBlueprintGroupLayoutPlanner.Proposal proposal = index == 0 ? initial
                    : groupLayoutPlanner.propose(state.layoutAlgorithm(), state.group().densityClass(),
                    blueprint.generationSeed(), state.group().groupId(), state.layoutSlotIndex(),
                    frames.get(index), seedPoint, outward.point(), outward.pending(), footprintSpan);
            try {
                CityTemplateOrientationSolver.RotationScore frontage = orientationSolver.rank(
                        template, template.allowedMirrors().get(0), "", proposal.guides().get(0),
                        CityTemplateOrientationSolver.FacingTarget.point(
                                state.layoutAlgorithm().toLowerCase(java.util.Locale.ROOT)
                                        + "_internal_road:" + state.group().groupId(),
                                proposal.frontageTarget())).get(0);
                if (frontage.alignmentScore() >= 0.70) return proposal;
            } catch (IllegalArgumentException ignored) {
                // Try the next cardinal local lane frame.
            }
        }
        return initial;
    }

    private void applyFrontage(JsonObject plan,
                               JsonObject layoutTrace,
                               CityTemplateCatalog.Template template,
                               BlockPoint candidate,
                               BlockPoint target,
                               String targetRef) {
        try {
            CityTemplateOrientationSolver.RotationScore frontage = orientationSolver.rank(
                    template, template.allowedMirrors().get(0), "", candidate,
                    CityTemplateOrientationSolver.FacingTarget.point(targetRef, target)).get(0);
            double minimumAlignment = targetRef.startsWith("compact_internal_road:")
                    || targetRef.startsWith("courtyard_internal_road:") ? 0.70 : 0.999;
            boolean aligned = frontage.alignmentScore() >= minimumAlignment;
            plan.addProperty("rotation", frontage.rotation().name());
            plan.addProperty("frontageSatisfied", aligned);
            layoutTrace.addProperty("frontageRotation", frontage.rotation().name());
            layoutTrace.addProperty("frontageEntranceId", frontage.entranceId());
            layoutTrace.addProperty("frontageDirection", frontage.frontageDirection().name());
            layoutTrace.addProperty("frontageAlignmentScore", frontage.alignmentScore());
            layoutTrace.addProperty("frontageMinimumAlignmentScore", minimumAlignment);
            layoutTrace.addProperty("frontageTargetRef", targetRef);
            if (!aligned) {
                layoutTrace.addProperty("frontageFailure", "CITY_BLUEPRINT_INTERNAL_FRONTAGE_UNAVAILABLE: "
                        + template.templateId() + " cannot face " + targetRef
                        + " within allowedRotations.");
            }
        } catch (IllegalArgumentException failure) {
            plan.addProperty("frontageSatisfied", false);
            layoutTrace.addProperty("frontageFailure", failure.getMessage());
        }
    }

    private static BlockPoint initialPlacementOrigin(GroupState state,
                                                     Map<String, GroupState> states) {
        if (state.preferredOrigin() != null) return state.preferredOrigin();
        CityBlueprint.PlacementRelation placement = state.group().placementRelation();
        if (placement == null
                || placement.kind() != CityBlueprint.PlacementRelationKind.BETWEEN_GROUPS) return null;
        GroupState first = states.get(placement.groupRefs().get(0));
        GroupState second = states.get(placement.groupRefs().get(1));
        if (first == null || second == null || first.extent() == null || second.extent() == null) {
            throw fail("CITY_BLUEPRINT_PLACEMENT_RELATION_UNRESOLVED",
                    state.group().groupId() + " requires two completed Group extents.");
        }
        return new BlockPoint((centerX(first.extent()) + centerX(second.extent())) / 2,
                (centerZ(first.extent()) + centerZ(second.extent())) / 2);
    }

    private static BlockPoint cellCenter(PatchMemberCell cell, int step) {
        return new BlockPoint(cell.blockMinX() + step / 2, cell.blockMinZ() + step / 2);
    }

    private static BlockPoint projectOntoAxis(CityBlueprintGroupLayoutPlanner.Frame frame,
                                               BlockPoint point) {
        double dx = point.x() - frame.center().x();
        double dz = point.z() - frame.center().z();
        double along = dx * frame.axisX() + dz * frame.axisZ();
        return new BlockPoint(frame.center().x() + (int) Math.round(frame.axisX() * along),
                frame.center().z() + (int) Math.round(frame.axisZ() * along));
    }

    private static BlockPoint point(JsonObject value) {
        return new BlockPoint(intValue(value, "x", 0), intValue(value, "z", 0));
    }

    private static OutwardTarget outwardTarget(GroupState state,
                                                Map<String, GroupState> states,
                                                BlockPoint seedPoint) {
        BlockBounds sourceExtent = state.extent();
        BlockPoint source = sourceExtent == null
                ? seedPoint : new BlockPoint(centerX(sourceExtent), centerZ(sourceExtent));
        BlockPoint bestPoint = null;
        String bestGroupId = "";
        double bestGap = Double.POSITIVE_INFINITY;
        for (GroupState other : states.values()) {
            if (other == state || other.anchorCount() == 0) continue;
            for (BlockBounds target : other.envelopes()) {
                double gap = sourceExtent == null
                        ? Math.hypot(source.x() - centerX(target), source.z() - centerZ(target))
                        : edgeGap(sourceExtent, target);
                if (gap < bestGap) {
                    bestGap = gap;
                    bestPoint = new BlockPoint(centerX(target), centerZ(target));
                    bestGroupId = other.group().groupId();
                }
            }
        }
        if (bestPoint != null) {
            return new OutwardTarget(bestPoint,
                    sourceExtent == null || bestGap > state.layoutParameters().landUseHandoffGapBlocks(),
                    bestGroupId, bestGap);
        }

        long bestDistance = Long.MAX_VALUE;
        for (GroupState other : states.values()) {
            if (other == state) continue;
            for (LandformPatchSummary patch : other.patches()) {
                BlockPoint target = patch.centerBlock();
                long dx = (long) target.x() - source.x();
                long dz = (long) target.z() - source.z();
                long distance = dx * dx + dz * dz;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestPoint = target;
                    bestGroupId = other.group().groupId();
                }
            }
        }
        return bestPoint == null ? OutwardTarget.none()
                : new OutwardTarget(bestPoint, true, bestGroupId, Math.sqrt(bestDistance));
    }

    private static OutwardTarget outwardTarget(GroupState state, GroupState target,
                                                BlockPoint seedPoint) {
        BlockBounds sourceExtent = state.extent();
        BlockPoint source = sourceExtent == null ? seedPoint
                : new BlockPoint(centerX(sourceExtent), centerZ(sourceExtent));
        Nearest nearest = sourceExtent == null ? null : nearest(sourceExtent,
                target.envelopes(), target.group().groupId());
        BlockPoint point;
        double gap;
        if (nearest != null && nearest.edge() != null) {
            point = new BlockPoint(nearest.edge().toX(), nearest.edge().toZ());
            gap = nearest.gapBlocks();
        } else if (target.extent() != null) {
            point = new BlockPoint(centerX(target.extent()), centerZ(target.extent()));
            gap = Math.hypot(source.x() - point.x(), source.z() - point.z());
        } else {
            point = target.patches().get(0).centerBlock();
            gap = Math.hypot(source.x() - point.x(), source.z() - point.z());
        }
        return new OutwardTarget(point, true, target.group().groupId(), gap);
    }

    private static PatchMemberCell frontierCell(GroupState state, List<LandformPatchSummary> patches) {
        if (state.extent() != null) {
            return nearestMemberCellFromCells(memberCells(patches), state.envelopes(), state.patchStepBlocks(),
                    state.formationBounds());
        }
        return preferredZoneCell(patches, state.group().preferredPatchZone(),
                state.patchStepBlocks(), state.formationBounds());
    }

    private static List<PatchMemberCell> memberCells(List<LandformPatchSummary> patches) {
        Map<String, PatchMemberCell> cells = new LinkedHashMap<>();
        patches.forEach(patch -> patch.memberCells().forEach(cell ->
                cells.putIfAbsent(cell.cellX() + ":" + cell.cellZ(), cell)));
        return List.copyOf(cells.values());
    }

    private static PatchMemberCell preferredZoneCell(List<LandformPatchSummary> patches,
                                                       CityBlueprint.PreferredPatchZone zone,
                                                       int step,
                                                       BlockBounds planningBounds) {
        return preferredZoneCellFromCells(memberCells(patches), zone, step, planningBounds);
    }

    private static PatchMemberCell preferredZoneCellFromCells(List<PatchMemberCell> cells,
                                                       CityBlueprint.PreferredPatchZone zone,
                                                       int step,
                                                       BlockBounds planningBounds) {
        List<PatchMemberCell> ordered = preferredZoneCellsFromCells(cells, zone, step, planningBounds);
        return ordered.isEmpty() ? null : ordered.get(0);
    }

    private static List<PatchMemberCell> preferredZoneCells(List<LandformPatchSummary> patches,
                                                             CityBlueprint.PreferredPatchZone zone,
                                                             int step,
                                                             BlockBounds planningBounds) {
        return preferredZoneCellsFromCells(memberCells(patches), zone, step, planningBounds);
    }

    private static List<PatchMemberCell> preferredZoneCellsFromCells(List<PatchMemberCell> cells,
                                                                      CityBlueprint.PreferredPatchZone zone,
                                                                      int step,
                                                                      BlockBounds planningBounds) {
        List<PatchMemberCell> legalCells = new ArrayList<>();
        for (PatchMemberCell cell : cells) {
            BlockBounds bounds = new BlockBounds(cell.blockMinX(), cell.blockMinZ(),
                    cell.blockMinX() + step - 1, cell.blockMinZ() + step - 1);
            if (within(bounds, planningBounds)) legalCells.add(cell);
        }
        cells = legalCells;
        if (cells.isEmpty()) return List.of();

        double meanX = cells.stream().mapToInt(cell -> cellCenter(cell, step).x()).average().orElse(0.0);
        double meanZ = cells.stream().mapToInt(cell -> cellCenter(cell, step).z()).average().orElse(0.0);
        int minX = cells.stream().mapToInt(cell -> cellCenter(cell, step).x()).min().orElse(0);
        int maxX = cells.stream().mapToInt(cell -> cellCenter(cell, step).x()).max().orElse(0);
        int minZ = cells.stream().mapToInt(cell -> cellCenter(cell, step).z()).min().orElse(0);
        int maxZ = cells.stream().mapToInt(cell -> cellCenter(cell, step).z()).max().orElse(0);
        double targetX = switch (zone) {
            case WEST -> minX + (maxX - minX) * 0.25;
            case EAST -> minX + (maxX - minX) * 0.75;
            default -> meanX;
        };
        double targetZ = switch (zone) {
            case NORTH -> minZ + (maxZ - minZ) * 0.25;
            case SOUTH -> minZ + (maxZ - minZ) * 0.75;
            default -> meanZ;
        };
        return cells.stream().sorted(Comparator
                .comparingDouble((PatchMemberCell cell) -> {
                    BlockPoint center = cellCenter(cell, step);
                    double dx = center.x() - targetX;
                    double dz = center.z() - targetZ;
                    return dx * dx + dz * dz;
                })
                .thenComparingInt(PatchMemberCell::cellX)
                .thenComparingInt(PatchMemberCell::cellZ)).toList();
    }

    private static BlockPoint frontierAnchorCenterToward(GroupState state, GroupState target) {
        if (state.extent() == null) return state.patches().get(0).centerBlock();
        if (target == null || target.extent() == null) {
            return new BlockPoint(centerX(state.extent()), centerZ(state.extent()));
        }
        BlockBounds nearestSource = null;
        double nearestGap = Double.POSITIVE_INFINITY;
        for (BlockBounds source : state.envelopes()) {
            Nearest candidate = nearest(source, target.envelopes(), target.group().groupId());
            if (candidate != null && candidate.gapBlocks() < nearestGap) {
                nearestGap = candidate.gapBlocks();
                nearestSource = source;
            }
        }
        return nearestSource == null
                ? new BlockPoint(centerX(state.extent()), centerZ(state.extent()))
                : new BlockPoint(centerX(nearestSource), centerZ(nearestSource));
    }

    private static JsonObject legalRegion(GroupState state, List<LandformPatchSummary> patches,
                                          PatchMemberCell frontierCell, boolean connectivityExpansion) {
        List<String> patchRefs = patches.stream().map(LandformPatchSummary::landformPatchId).toList();
        int step = state.patchStepBlocks();
        JsonObject region = new JsonObject();
        region.addProperty("schema", CityD4CandidateLegalRegion.SCHEMA);
        region.addProperty("patchSelectionRef", connectivityExpansion
                ? "city_blueprint_connectivity_grid:" + state.group().groupId()
                : "city_blueprint_preferred:" + String.join("+", patchRefs));
        region.addProperty("cellStepBlocks", step);
        JsonArray cells = new JsonArray();
        Set<String> seenCells = new LinkedHashSet<>();
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        BlockBounds legalBounds = state.legalBounds(connectivityExpansion);
        BlockBounds growthWindow = growthWindow(state, frontierCell, step, connectivityExpansion);
        List<PatchMemberCell> sourceCells = memberCells(patches);
        if (sourceCells.isEmpty()) {
            throw fail("CITY_BLUEPRINT_PATCH_MEMBER_CELLS_REQUIRED",
                    "Blueprint compiler requires exact D3 memberCells for Group " + state.group().groupId() + ".");
        }
        for (PatchMemberCell cell : sourceCells) {
            BlockBounds cellBounds = new BlockBounds(cell.blockMinX(), cell.blockMinZ(),
                    cell.blockMinX() + step - 1, cell.blockMinZ() + step - 1);
            if (!within(cellBounds, legalBounds)) continue;
            if (growthWindow != null && !cellBounds.overlaps(growthWindow)) continue;
            if (!seenCells.add(cell.cellX() + ":" + cell.cellZ())) continue;
            JsonObject value = new JsonObject();
            value.addProperty("gridX", cell.cellX());
            value.addProperty("gridZ", cell.cellZ());
            value.addProperty("blockMinX", cell.blockMinX());
            value.addProperty("blockMinZ", cell.blockMinZ());
            value.addProperty("blockMaxX", cell.blockMinX() + step - 1);
            value.addProperty("blockMaxZ", cell.blockMinZ() + step - 1);
            cells.add(value);
            minX = Math.min(minX, cell.blockMinX());
            minZ = Math.min(minZ, cell.blockMinZ());
            maxX = Math.max(maxX, cell.blockMinX() + step - 1);
            maxZ = Math.max(maxZ, cell.blockMinZ() + step - 1);
        }
        if (cells.isEmpty()) {
            for (PatchMemberCell cell : sourceCells) {
                if (!seenCells.add(cell.cellX() + ":" + cell.cellZ())) continue;
                JsonObject value = new JsonObject();
                value.addProperty("gridX", cell.cellX());
                value.addProperty("gridZ", cell.cellZ());
                value.addProperty("blockMinX", cell.blockMinX());
                value.addProperty("blockMinZ", cell.blockMinZ());
                value.addProperty("blockMaxX", cell.blockMinX() + step - 1);
                value.addProperty("blockMaxZ", cell.blockMinZ() + step - 1);
                cells.add(value);
                minX = Math.min(minX, cell.blockMinX());
                minZ = Math.min(minZ, cell.blockMinZ());
                maxX = Math.max(maxX, cell.blockMinX() + step - 1);
                maxZ = Math.max(maxZ, cell.blockMinZ() + step - 1);
            }
        }
        if (cells.isEmpty()) {
            region.addProperty("capacityGapRecorded", true);
            region.addProperty("capacityGapReason", "NO_LEGAL_TERRAIN_CELL");
            region.add("memberCells", new JsonArray());
            JsonObject bounds = new JsonObject();
            bounds.addProperty("minX", legalBounds.minX());
            bounds.addProperty("minZ", legalBounds.minZ());
            bounds.addProperty("maxX", legalBounds.maxX());
            bounds.addProperty("maxZ", legalBounds.maxZ());
            region.add("bounds", bounds);
            return region;
        }
        region.add("memberCells", cells);
        JsonObject bounds = new JsonObject();
        bounds.addProperty("minX", minX);
        bounds.addProperty("minZ", minZ);
        bounds.addProperty("maxX", maxX);
        bounds.addProperty("maxZ", maxZ);
        region.add("bounds", bounds);
        return region;
    }

    private static PatchMemberCell nearestMemberCell(List<LandformPatchSummary> patches,
                                                      List<BlockBounds> targets, int step,
                                                      BlockBounds planningBounds) {
        return nearestMemberCellFromCells(memberCells(patches), targets, step, planningBounds);
    }

    private static PatchMemberCell nearestMemberCellFromCells(List<PatchMemberCell> cells,
                                                      List<BlockBounds> targets, int step,
                                                      BlockBounds planningBounds) {
        if (targets.isEmpty()) return null;
        PatchMemberCell nearest = null;
        double nearestGap = Double.POSITIVE_INFINITY;
        for (PatchMemberCell cell : cells) {
            BlockBounds cellBounds = new BlockBounds(cell.blockMinX(), cell.blockMinZ(),
                    cell.blockMinX() + step - 1, cell.blockMinZ() + step - 1);
            if (!within(cellBounds, planningBounds)) continue;
            for (BlockBounds target : targets) {
                double gap = edgeGap(cellBounds, target);
                if (gap < nearestGap) {
                    nearestGap = gap;
                    nearest = cell;
                }
            }
        }
        return nearest;
    }

    private static PatchMemberCell nearestMemberCellToPoint(List<LandformPatchSummary> patches,
                                                            BlockPoint target,
                                                            int step,
                                                            BlockBounds planningBounds) {
        return nearestMemberCellToPointFromCells(memberCells(patches), target, step, planningBounds);
    }

    private static PatchMemberCell nearestMemberCellToPointFromCells(List<PatchMemberCell> cells,
                                                            BlockPoint target,
                                                            int step,
                                                            BlockBounds planningBounds) {
        PatchMemberCell nearest = null;
        long nearestDistance = Long.MAX_VALUE;
        for (PatchMemberCell cell : cells) {
            BlockBounds cellBounds = new BlockBounds(cell.blockMinX(), cell.blockMinZ(),
                    cell.blockMinX() + step - 1, cell.blockMinZ() + step - 1);
            if (!within(cellBounds, planningBounds)) continue;
            BlockPoint center = cellCenter(cell, step);
            long dx = (long) center.x() - target.x();
            long dz = (long) center.z() - target.z();
            long distance = dx * dx + dz * dz;
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = cell;
            }
        }
        return nearest;
    }

    private static boolean within(BlockBounds candidate, BlockBounds container) {
        return candidate.minX() >= container.minX() && candidate.minZ() >= container.minZ()
                && candidate.maxX() <= container.maxX() && candidate.maxZ() <= container.maxZ();
    }

    private static BlockBounds growthWindow(GroupState state, PatchMemberCell frontierCell, int step,
                                            boolean connectivityExpansion) {
        if (frontierCell == null) return null;
        int padding = state.extent() == null ? step * 2
                : state.layoutParameters().maximumEdgeGapBlocks() / 2 + step;
        int centerX = frontierCell.blockMinX() + step / 2;
        int centerZ = frontierCell.blockMinZ() + step / 2;
        if (state.extent() == null) {
            return new BlockBounds(centerX - padding, centerZ - padding,
                    centerX + padding, centerZ + padding);
        }
        BlockBounds result = new BlockBounds(Math.min(state.extent().minX(), centerX) - padding,
                Math.min(state.extent().minZ(), centerZ) - padding,
                Math.max(state.extent().maxX(), centerX) + padding,
                Math.max(state.extent().maxZ(), centerZ) + padding);
        return connectivityExpansion ? intersection(result, state.planningBounds()) : result;
    }

    private static JsonObject candidateAttemptSummary(TemplateCandidate template,
                                                       JsonObject plan,
                                                       CityStructureArrayCandidatePlanner.Result result) {
        JsonObject summary = new JsonObject();
        summary.addProperty("templateId", template.templateId());
        summary.addProperty("variantId", template.variantId());
        summary.addProperty("passed", booleanValue(result.qualityReport(), "passed", false));
        summary.addProperty("candidateCount", array(result.arrayCandidateSet(), "arrayCandidates").size());
        JsonArray generationReports = array(result.arrayCandidateSet(), "generationReports");
        summary.add("filterReasonCounts", filterReasonCounts(generationReports));
        summary.add("failedAttemptPositions", failedAttemptPositions(template.templateId(), plan, generationReports));
        summary.add("hardBlocks", array(result.qualityReport(), "hardBlocks").deepCopy());
        return summary;
    }

    private static JsonArray failedAttemptPositions(String templateId, JsonObject plan, JsonArray reports) {
        JsonArray positions = new JsonArray();
        for (JsonElement reportElement : reports) {
            if (!reportElement.isJsonObject()) continue;
            JsonObject report = reportElement.getAsJsonObject();
            for (JsonElement rejectedElement : array(report, "rejectedPoints")) {
                if (!rejectedElement.isJsonObject()) continue;
                JsonObject position = rejectedElement.getAsJsonObject().deepCopy();
                position.addProperty("pattern", string(report, "pattern"));
                position.addProperty("pivotPatchId", string(report, "pivotPatchId"));
                positions.add(position);
            }
        }
        boolean hasFailedReport = reports.isEmpty();
        for (JsonElement reportElement : reports) {
            if (reportElement.isJsonObject()
                    && !"accepted".equals(string(reportElement.getAsJsonObject(), "status"))) {
                hasFailedReport = true;
                break;
            }
        }
        if (positions.isEmpty() && hasFailedReport) {
            JsonObject layout = requiredObject(plan, "blueprintLayout");
            JsonObject theoreticalAnchor = layout.has("theoreticalAnchor")
                    && layout.get("theoreticalAnchor").isJsonObject()
                    ? layout.getAsJsonObject("theoreticalAnchor") : new JsonObject();
            if (theoreticalAnchor.size() > 0) {
                JsonObject position = new JsonObject();
                position.addProperty("itemIndex", intValue(layout, "slotIndex", 0) + 1);
                position.addProperty("templateId", templateId);
                position.add("anchorBlock", theoreticalAnchor.deepCopy());
                position.addProperty("reasonCode", reports.isEmpty()
                        ? "NO_LEGAL_CANDIDATE"
                        : string(reports.get(0).getAsJsonObject(), "status"));
                position.addProperty("pattern", string(plan, "arrayPattern"));
                positions.add(position);
            }
        }
        return positions;
    }

    private static void recordCompilerRejectedPositions(JsonObject attempt, JsonObject candidate,
                                                        String reasonCode) {
        JsonArray positions = attempt.has("failedAttemptPositions")
                && attempt.get("failedAttemptPositions").isJsonArray()
                ? attempt.getAsJsonArray("failedAttemptPositions") : new JsonArray();
        if (!attempt.has("failedAttemptPositions")) attempt.add("failedAttemptPositions", positions);
        for (JsonElement itemElement : array(candidate, "items")) {
            if (!itemElement.isJsonObject()) continue;
            JsonObject item = itemElement.getAsJsonObject();
            JsonObject position = new JsonObject();
            position.addProperty("itemIndex", intValue(item, "itemIndex", 0));
            String templateId = string(item, "templateId");
            position.addProperty("templateId", templateId.isBlank() ? string(item, "templateRef") : templateId);
            position.add("anchorBlock", requiredObject(item, "anchorBlock").deepCopy());
            position.addProperty("reasonCode", reasonCode);
            position.addProperty("arrayCandidateId", string(candidate, "arrayCandidateId"));
            for (String key : List.of("plannedFootprint", "estimatedCollisionEnvelope", "estimatedMaskEnvelope")) {
                if (item.has(key) && item.get(key).isJsonObject()) {
                    position.add(key, item.getAsJsonObject(key).deepCopy());
                }
            }
            positions.add(position);
        }
    }

    private static JsonObject filterReasonCounts(JsonArray reports) {
        JsonObject counts = new JsonObject();
        for (JsonElement reportElement : reports) {
            if (!reportElement.isJsonObject()) continue;
            JsonObject report = reportElement.getAsJsonObject();
            String status = string(report, "status");
            if (!status.isBlank() && !"accepted".equals(status)) increment(counts, status);
            for (JsonElement rejected : array(report, "rejectedPoints")) {
                if (rejected.isJsonObject()) increment(counts,
                        string(rejected.getAsJsonObject(), "reasonCode"));
            }
        }
        return counts;
    }

    private static void increment(JsonObject counts, String key) {
        if (!key.isBlank()) counts.addProperty(key, intValue(counts, key, 0) + 1);
    }

    private static JsonObject terrainFailureReasonCounts(JsonObject event) {
        JsonObject result = new JsonObject();
        if (event.has("compilerFilterReasonCounts")
                && event.get("compilerFilterReasonCounts").isJsonObject()) {
            if (!mergeExclusiveTerrainReasons(result,
                    event.getAsJsonObject("compilerFilterReasonCounts"))) return new JsonObject();
        }
        if (event.has("attempts") && event.get("attempts").isJsonArray()) {
            for (JsonElement attemptElement : event.getAsJsonArray("attempts")) {
                if (!attemptElement.isJsonObject()) continue;
                JsonObject attempt = attemptElement.getAsJsonObject();
                if (attempt.has("filterReasonCounts") && attempt.get("filterReasonCounts").isJsonObject()) {
                    if (!mergeExclusiveTerrainReasons(result,
                            attempt.getAsJsonObject("filterReasonCounts"))) return new JsonObject();
                }
            }
        }
        return result;
    }

    private static boolean mergeExclusiveTerrainReasons(JsonObject target, JsonObject source) {
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            String reason = entry.getKey();
            boolean terrainReason = reason.startsWith("CITY_STRUCTURE_TERRAIN_")
                    || reason.startsWith("CITY_STRUCTURE_SURFACE_");
            if (!terrainReason) {
                if ("D4_ARRAY_COUNT_UNSATISFIED".equals(reason)) continue;
                return false;
            }
            int count = entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isNumber()
                    ? entry.getValue().getAsInt() : 1;
            target.addProperty(reason, intValue(target, reason, 0) + count);
        }
        return true;
    }

    private static TerrainCandidateEvaluation candidateTerrainEvaluation(JsonObject candidate, GroupState state,
                                                                          String structureRef,
                                                                          CityStructureTerrainGate terrainGate) {
        JsonArray anchors = array(requiredObject(candidate, "expandedStructureAnchorPlan"), "anchors");
        if (anchors.isEmpty() || !anchors.get(0).isJsonObject()) {
            return TerrainCandidateEvaluation.rejected("CITY_STRUCTURE_TERRAIN_ANCHOR_MISSING",
                    structureRef, new JsonObject());
        }
        BlockBounds footprint = bounds(requiredObject(candidate, "groupCollisionEnvelope"));
        return anchorTerrainEvaluation(anchors.get(0).getAsJsonObject(), footprint, state,
                structureRef, terrainGate);
    }

    private static TerrainCandidateEvaluation anchorTerrainEvaluation(JsonObject anchor, BlockBounds footprint,
                                                                       GroupState state, String structureRef,
                                                                       CityStructureTerrainGate terrainGate) {
        JsonArray sourcePatchIds = array(anchor, "sourcePatchIds");
        JsonArray patchPreferences = new JsonArray();
        for (JsonElement element : sourcePatchIds) {
            LandformPatchSummary patch = state.patchByRef().get(element.getAsString());
            if (patch == null) continue;
            JsonObject preference = new JsonObject();
            preference.addProperty("patchRef", patch.landformPatchId());
            preference.addProperty("meanSlope", patch.metricsSummary().meanSlope());
            preference.addProperty("preferredByGroupTerrainPolicy",
                    terrainAllowed(state.group().terrainPolicy(), patch.metricsSummary().meanSlope()));
            patchPreferences.add(preference);
        }
        CityStructureTerrainGate.Evaluation evaluation = terrainGate.evaluate(
                structureRef, footprint, state.group().terrainPolicy());
        JsonObject trace = evaluation.trace().deepCopy();
        trace.addProperty("groupTerrainPolicy", state.group().terrainPolicy().name());
        trace.addProperty("groupTerrainPolicyRole", "hard_footprint_gate");
        trace.add("sourcePatchPreferences", patchPreferences);
        return evaluation.passed()
                ? new TerrainCandidateEvaluation(true, "", evaluation.resolvedTerrainMode(), trace)
                : TerrainCandidateEvaluation.rejected(evaluation.reasonCode(), structureRef, trace);
    }

    private static String candidateFootprintRejectionReason(CityStructureTerrainGate terrainGate,
                                                            GroupState state,
                                                            Map<String, GroupState> states,
                                                            String structureRef,
                                                            PlacementPhase phase,
                                                            BlockBounds footprint,
                                                            JsonObject layout) {
        CityStructureTerrainGate.Evaluation evaluation = terrainGate.evaluate(
                structureRef, footprint, state.group().terrainPolicy());
        if (!evaluation.passed()) return evaluation.reasonCode();
        if (!within(footprint, state.legalBounds(phase == PlacementPhase.CONNECTIVITY
                || phase == PlacementPhase.PERCENTAGE))) {
            return phase == PlacementPhase.CONNECTIVITY
                    ? "CITY_PLANNING_BOUNDS_EXCEEDED" : "ARRAY_COMPOSITION_SLOT_EXCEEDED";
        }
        if (violatesGroupSeparation(footprint, state, states)) {
            return "GROUP_DISTRICT_BUFFER_VIOLATED";
        }
        if (phase != PlacementPhase.CONNECTIVITY) {
            String gridReservationFailure = state.gridStreetReservationFailure(footprint, layout);
            if (!gridReservationFailure.isBlank()) return gridReservationFailure;
        }
        BlockBounds proposed = union(state.extent(), footprint);
        if (phase != PlacementPhase.CONNECTIVITY && !state.areaExpansionActive()
                && state.compositionSlot() != null
                && (width(proposed) > state.formationBounds().widthBlocks()
                || depth(proposed) > state.formationBounds().heightBlocks())) {
            return "GROUP_EXTENT_LIMIT_EXCEEDED";
        }
        if (state.anchorCount() > 0) {
            Nearest nearest = nearest(footprint, state.envelopes(), state.group().groupId());
            if (state.requiresInternalRoadGap()
                    && (nearest == null
                    || nearest.gapBlocks() < state.layoutParameters().targetEdgeGapBlocks())) {
                return "HARD_SKELETON_GAP_BELOW_TARGET";
            }
            if (!state.hardSkeletonUsesExactGuides()
                    && (nearest == null
                    || nearest.gapBlocks() > state.layoutParameters().maximumEdgeGapBlocks())) {
                return "GROUP_CONNECTIVITY_GAP_EXCEEDED";
            }
            if (phase != PlacementPhase.CONNECTIVITY && "ORGANIC_COMPACT".equals(state.layoutAlgorithm())
                    && nearest.gapBlocks() < 1.0) {
                return "ORGANIC_GAP_BELOW_ONE_BLOCK";
            }
        }
        return "";
    }

    private static boolean violatesGroupSeparation(BlockBounds footprint, GroupState state,
                                                       Map<String, GroupState> states) {
        for (GroupState other : states.values()) {
            if (other == state) continue;
            if (state.groupSeparationExemptGroupIds().contains(other.group().groupId())
                    || other.groupSeparationExemptGroupIds().contains(state.group().groupId())) continue;
            for (BlockBounds envelope : other.envelopes()) {
                if (edgeGap(footprint, envelope) < MINIMUM_GROUP_SEPARATION_BLOCKS) return true;
            }
        }
        return false;
    }

    private static Map<String, Set<String>> groupSeparationExemptions(CityBlueprint blueprint) {
        Map<String, Set<String>> exemptions = new LinkedHashMap<>();
        blueprint.groups().forEach(group -> exemptions.put(group.groupId(), new LinkedHashSet<>()));
        Map<String, Set<String>> compositionPeers = new LinkedHashMap<>();
        blueprint.groups().forEach(group -> compositionPeers.put(group.groupId(), Set.of(group.groupId())));
        for (CityBlueprint.ArrayComposition composition : blueprint.arrayCompositions()) {
            List<String> members = new ArrayList<>();
            members.add(composition.centerGroupId());
            members.addAll(composition.memberGroupIds());
            Set<String> peerSet = Set.copyOf(members);
            members.forEach(member -> compositionPeers.put(member, peerSet));
            addGroupSeparationExemptions(exemptions, peerSet, peerSet);
        }
        for (CityBlueprint.Relation relation : blueprint.relations()) {
            boolean explicitAdjacency = relation.relationKind() == CityBlueprint.RelationKind.ADJACENCY;
            boolean hardStructuralRelation = relation.strength() == CityBlueprint.RelationStrength.HARD
                    && (relation.relationKind() == CityBlueprint.RelationKind.CONNECTION
                    || relation.relationKind() == CityBlueprint.RelationKind.HIERARCHY);
            if (!explicitAdjacency && !hardStructuralRelation) continue;
            addGroupSeparationExemptions(exemptions,
                    compositionPeers.getOrDefault(relation.fromGroupId(), Set.of(relation.fromGroupId())),
                    compositionPeers.getOrDefault(relation.toGroupId(), Set.of(relation.toGroupId())));
        }
        for (CityBlueprint.Group group : blueprint.groups()) {
            CityBlueprint.PlacementRelation placement = group.placementRelation();
            if (placement == null
                    || placement.kind() != CityBlueprint.PlacementRelationKind.BETWEEN_GROUPS) continue;
            placement.groupRefs().forEach(reference ->
                    addGroupSeparationExemptions(exemptions,
                            compositionPeers.getOrDefault(group.groupId(), Set.of(group.groupId())),
                            compositionPeers.getOrDefault(reference, Set.of(reference))));
        }
        Map<String, Set<String>> frozen = new LinkedHashMap<>();
        exemptions.forEach((groupId, groupExemptions) ->
                frozen.put(groupId, Set.copyOf(groupExemptions)));
        return Map.copyOf(frozen);
    }

    private static void addGroupSeparationExemptions(Map<String, Set<String>> exemptions,
                                                     Set<String> firstGroups, Set<String> secondGroups) {
        firstGroups.forEach(first -> secondGroups.forEach(second ->
                addGroupSeparationExemption(exemptions, first, second)));
    }

    private static void addGroupSeparationExemption(Map<String, Set<String>> exemptions,
                                                    String first, String second) {
        Set<String> firstExemptions = exemptions.get(first);
        Set<String> secondExemptions = exemptions.get(second);
        if (firstExemptions == null || secondExemptions == null || first.equals(second)) return;
        firstExemptions.add(second);
        secondExemptions.add(first);
    }

    private static ConnectivityFit connectivityFit(JsonObject candidate, GroupState state,
                                                     Map<String, GroupState> states,
                                                     boolean connectivityExpansion) {
        BlockBounds bounds = bounds(requiredObject(candidate, "groupCollisionEnvelope"));
        BlockBounds proposed = union(state.extent(), bounds);
        int maxSpan = state.spatialDemand().formationSpanBlocks();
        if (!connectivityExpansion && (width(proposed) > maxSpan || depth(proposed) > maxSpan)) {
            return ConnectivityFit.rejected("GROUP_EXTENT_LIMIT_EXCEEDED");
        }
        if (state.anchorCount() > 0) {
            Nearest nearest = nearest(bounds, state.envelopes(), state.group().groupId());
            if (state.hardSkeletonUsesExactGuides()) {
                return ConnectivityFit.allowed(1.0, nearest == null ? 0.0 : nearest.gapBlocks(),
                        nearest == null ? null : nearest.edge());
            }
            int maximumGap = state.layoutParameters().maximumEdgeGapBlocks();
            if (nearest == null || nearest.gapBlocks() > maximumGap) {
                return ConnectivityFit.rejected("GROUP_CONNECTIVITY_GAP_EXCEEDED");
            }
            return ConnectivityFit.allowed(clamp01(1.0 - nearest.gapBlocks() / maximumGap),
                    nearest.gapBlocks(), nearest.edge());
        }

        Nearest nearestCity = null;
        for (GroupState other : states.values()) {
            if (other == state || other.anchorCount() == 0) continue;
            Nearest candidateNearest = nearest(bounds, other.envelopes(), other.group().groupId());
            if (candidateNearest != null && (nearestCity == null
                    || candidateNearest.gapBlocks() < nearestCity.gapBlocks())) {
                nearestCity = candidateNearest;
            }
        }
        if (nearestCity == null) return ConnectivityFit.allowed(1.0, 0.0, null);
        return ConnectivityFit.allowed(0.5, nearestCity.gapBlocks(), nearestCity.edge());
    }

    private static double targetFit(JsonObject candidate, GroupState target,
                                    BlockBounds planningBounds) {
        BlockBounds bounds = bounds(requiredObject(candidate, "groupCollisionEnvelope"));
        Nearest nearest = nearest(bounds, target.envelopes(), target.group().groupId());
        if (nearest == null) return 0.0;
        double diagonal = Math.hypot(width(planningBounds), depth(planningBounds));
        return clamp01(1.0 - nearest.gapBlocks() / Math.max(1.0, diagonal));
    }

    private static Nearest nearest(BlockBounds source, List<BlockBounds> targets, String ownerGroupId) {
        if (source == null) return null;
        Nearest nearest = null;
        for (BlockBounds target : targets) {
            if (target == null) continue;
            double gap = edgeGap(source, target);
            if (nearest == null || gap < nearest.gapBlocks()) {
                nearest = new Nearest(gap, ownerGroupId, connectionEdge(source, target));
            }
        }
        return nearest;
    }

    private static double edgeGap(BlockBounds first, BlockBounds second) {
        if (first == null || second == null) return Double.POSITIVE_INFINITY;
        int dx = axisGap(first.minX(), first.maxX(), second.minX(), second.maxX());
        int dz = axisGap(first.minZ(), first.maxZ(), second.minZ(), second.maxZ());
        return Math.hypot(dx, dz);
    }

    private static int axisGap(int firstMin, int firstMax, int secondMin, int secondMax) {
        if (firstMax < secondMin) return Math.max(0, secondMin - firstMax - 1);
        if (secondMax < firstMin) return Math.max(0, firstMin - secondMax - 1);
        return 0;
    }

    private static BlockBounds expandWithin(BlockBounds source, int margin, BlockBounds limit) {
        return new BlockBounds(
                Math.max(limit.minX(), source.minX() - margin),
                Math.max(limit.minZ(), source.minZ() - margin),
                Math.min(limit.maxX(), source.maxX() + margin),
                Math.min(limit.maxZ(), source.maxZ() + margin));
    }

    private static ConnectionEdge connectionEdge(BlockBounds source, BlockBounds target) {
        int sourceX = clamp(centerX(target), source.minX(), source.maxX());
        int sourceZ = clamp(centerZ(target), source.minZ(), source.maxZ());
        int targetX = clamp(sourceX, target.minX(), target.maxX());
        int targetZ = clamp(sourceZ, target.minZ(), target.maxZ());
        sourceX = clamp(targetX, source.minX(), source.maxX());
        sourceZ = clamp(targetZ, source.minZ(), source.maxZ());
        return new ConnectionEdge(sourceX, sourceZ, targetX, targetZ);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int centerX(BlockBounds bounds) { return (bounds.minX() + bounds.maxX()) / 2; }
    private static int centerZ(BlockBounds bounds) { return (bounds.minZ() + bounds.maxZ()) / 2; }
    private static int width(BlockBounds bounds) { return bounds.maxX() - bounds.minX() + 1; }
    private static int depth(BlockBounds bounds) { return bounds.maxZ() - bounds.minZ() + 1; }
    private static int area(BlockBounds bounds) { return width(bounds) * depth(bounds); }
    private static int expansionBeyond(BlockBounds core, BlockBounds expanded) {
        if (core == null || expanded == null) return 0;
        int west = Math.max(0, core.minX() - expanded.minX());
        int east = Math.max(0, expanded.maxX() - core.maxX());
        int north = Math.max(0, core.minZ() - expanded.minZ());
        int south = Math.max(0, expanded.maxZ() - core.maxZ());
        return Math.max(Math.max(west, east), Math.max(north, south));
    }
    private static BlockBounds bounds(JsonObject value) {
        return new BlockBounds(intValue(value, "minX", 0), intValue(value, "minZ", 0),
                intValue(value, "maxX", 0), intValue(value, "maxZ", 0));
    }
    private static BlockBounds union(BlockBounds first, BlockBounds second) {
        if (first == null) return second;
        return new BlockBounds(Math.min(first.minX(), second.minX()), Math.min(first.minZ(), second.minZ()),
                Math.max(first.maxX(), second.maxX()), Math.max(first.maxZ(), second.maxZ()));
    }

    private static BlockBounds intersection(BlockBounds first, BlockBounds second) {
        return new BlockBounds(Math.max(first.minX(), second.minX()),
                Math.max(first.minZ(), second.minZ()), Math.min(first.maxX(), second.maxX()),
                Math.min(first.maxZ(), second.maxZ()));
    }

    private static double relationFit(JsonObject candidate,
                                      CityBlueprint.Group group,
                                      List<CityBlueprint.Relation> relations,
                                      Map<String, GroupState> states) {
        JsonObject bounds = requiredObject(candidate, "groupCollisionEnvelope");
        double x = (intValue(bounds, "minX", 0) + intValue(bounds, "maxX", 0)) / 2.0;
        double z = (intValue(bounds, "minZ", 0) + intValue(bounds, "maxZ", 0)) / 2.0;
        double total = 0.0;
        int count = 0;
        for (CityBlueprint.Relation relation : relations) {
            boolean from = relation.fromGroupId().equals(group.groupId());
            boolean to = relation.toGroupId().equals(group.groupId());
            if (!from && !to) continue;
            String otherId = from ? relation.toGroupId() : relation.fromGroupId();
            GroupState other = states.get(otherId);
            if (other == null || other.extent() == null) continue;
            double ox = (other.extent().minX() + other.extent().maxX()) / 2.0;
            double oz = (other.extent().minZ() + other.extent().maxZ()) / 2.0;
            double distance = Math.hypot(x - ox, z - oz);
            double score = switch (relation.relationKind()) {
                case ADJACENCY, CONNECTION -> clamp01(1.0 - distance / 256.0);
                case BUFFER -> clamp01(distance / 256.0);
                case DISTANCE -> relation.distancePreference() == CityBlueprint.DistancePreference.FAR
                        ? clamp01(distance / 256.0) : clamp01(1.0 - distance / 256.0);
                case DIRECTION -> directionFit(from ? x - ox : ox - x, from ? z - oz : oz - z,
                        relation.directionPreference());
                case HIERARCHY -> 0.75;
            };
            total += relation.strength() == CityBlueprint.RelationStrength.HARD ? score * 1.5 : score;
            count += relation.strength() == CityBlueprint.RelationStrength.HARD ? 2 : 1;
        }
        return count == 0 ? 0.65 : total / count;
    }

    private static boolean hardRelationsAllow(JsonObject candidate,
                                              CityBlueprint.Group group,
                                              List<CityBlueprint.Relation> relations,
                                              Map<String, GroupState> states) {
        JsonObject bounds = requiredObject(candidate, "groupCollisionEnvelope");
        double x = (intValue(bounds, "minX", 0) + intValue(bounds, "maxX", 0)) / 2.0;
        double z = (intValue(bounds, "minZ", 0) + intValue(bounds, "maxZ", 0)) / 2.0;
        for (CityBlueprint.Relation relation : relations) {
            if (relation.strength() != CityBlueprint.RelationStrength.HARD) continue;
            if (relation.relationKind() == CityBlueprint.RelationKind.ADJACENCY
                    || relation.relationKind() == CityBlueprint.RelationKind.CONNECTION
                    || relation.relationKind() == CityBlueprint.RelationKind.HIERARCHY) continue;
            boolean from = relation.fromGroupId().equals(group.groupId());
            boolean to = relation.toGroupId().equals(group.groupId());
            if (!from && !to) continue;
            GroupState other = states.get(from ? relation.toGroupId() : relation.fromGroupId());
            if (other == null || other.extent() == null) continue;
            if (!relationSatisfied(relation, from, x, z, other.extent())) return false;
        }
        return true;
    }

    private static String hardRelationFailure(List<CityBlueprint.Relation> relations,
                                              Map<String, GroupState> states,
                                              ConnectivityPlan connectivityPlan) {
        for (CityBlueprint.Relation relation : relations) {
            if (relation.strength() != CityBlueprint.RelationStrength.HARD) continue;
            if ((relation.relationKind() == CityBlueprint.RelationKind.ADJACENCY
                    || relation.relationKind() == CityBlueprint.RelationKind.CONNECTION)
                    && connectivityPlan.degradedOrBridgeDelegated(
                    relation.fromGroupId(), relation.toGroupId())) {
                continue;
            }
            GroupState from = states.get(relation.fromGroupId());
            GroupState to = states.get(relation.toGroupId());
            if (from == null || to == null || from.extent() == null || to.extent() == null) continue;
            if ((relation.relationKind() == CityBlueprint.RelationKind.ADJACENCY
                    || relation.relationKind() == CityBlueprint.RelationKind.CONNECTION)
                    && (!from.group().expansionPolicy().allowRelationConnection()
                    || !from.group().expansionPolicy().allowOutwardExpansion()
                    || !to.group().expansionPolicy().allowRelationConnection()
                    || !to.group().expansionPolicy().allowOutwardExpansion())) {
                continue;
            }
            double x = (from.extent().minX() + from.extent().maxX()) / 2.0;
            double z = (from.extent().minZ() + from.extent().maxZ()) / 2.0;
            if ((relation.relationKind() == CityBlueprint.RelationKind.ADJACENCY
                    || relation.relationKind() == CityBlueprint.RelationKind.CONNECTION)
                    ? !frontiersWithinHandoff(from, to)
                    : !relationSatisfied(relation, true, x, z, to.extent())) {
                return relation.fromGroupId() + " -> " + relation.toGroupId() + " "
                        + relation.relationKind() + " is unsatisfied.";
            }
        }
        return "";
    }

    private static boolean hierarchicalRoadProfile(CityBlueprint blueprint,
                                                   CityBlueprintReferenceCatalog references) {
        for (JsonElement element : array(references.json(), "roadProfiles")) {
            if (!element.isJsonObject()) continue;
            JsonObject profile = element.getAsJsonObject();
            if (blueprint.roadProfile().profileRef().equals(string(profile, "profileRef"))) {
                return "HIERARCHICAL".equals(string(profile, "hierarchy"));
            }
        }
        return false;
    }

    private int derivedMainRoadWidth(List<CityBlueprint.Group> groups, CatalogIndex catalog) {
        int internal = groups.stream().mapToInt(group -> groupLayoutPlanner.parameters(
                catalog.algorithm(group.algorithmProfileRef()), group.densityClass())
                .streetBandWidthBlocks()).max().orElse(1);
        int width = Math.max(7, internal + 2);
        return (width & 1) == 0 ? width + 1 : width;
    }

    private static boolean frontiersWithinHandoff(GroupState first, GroupState second) {
        Nearest nearest = nearest(first.extent(), second.envelopes(), second.group().groupId());
        int threshold = handoffThreshold(first, second);
        return nearest != null && nearest.gapBlocks() <= threshold;
    }

    private static boolean relationSatisfied(CityBlueprint.Relation relation, boolean from,
                                             double x, double z, BlockBounds other) {
        double ox = (other.minX() + other.maxX()) / 2.0;
        double oz = (other.minZ() + other.maxZ()) / 2.0;
        double distance = Math.hypot(x - ox, z - oz);
        return switch (relation.relationKind()) {
            case ADJACENCY, CONNECTION -> distance <= 256.0;
            case BUFFER -> distance >= 64.0;
            case DISTANCE -> relation.distancePreference() == CityBlueprint.DistancePreference.FAR
                    ? distance >= 128.0 : distance <= 256.0;
            case DIRECTION -> directionFit(from ? x - ox : ox - x, from ? z - oz : oz - z,
                    relation.directionPreference()) >= 1.0;
            case HIERARCHY -> true;
        };
    }

    private static double directionFit(double dx, double dz, CityBlueprint.DirectionPreference direction) {
        return switch (direction) {
            case NORTH -> dz < 0 ? 1.0 : 0.0;
            case SOUTH -> dz > 0 ? 1.0 : 0.0;
            case EAST -> dx > 0 ? 1.0 : 0.0;
            case WEST -> dx < 0 ? 1.0 : 0.0;
            case NONE -> 0.65;
        };
    }

    private static JsonObject trace(CityBlueprint blueprint, JsonObject context, JsonArray selections,
                                    Map<String, GroupState> states, ConnectivityPlan connectivityPlan,
                                    String status, String reasonCode) {
        JsonObject trace = new JsonObject();
        trace.addProperty("schema", TRACE_SCHEMA);
        trace.addProperty("cityId", blueprint.cityId());
        trace.addProperty("status", status);
        trace.addProperty("reasonCode", reasonCode);
        trace.addProperty("generationSeed", blueprint.generationSeed());
        trace.addProperty("selectionMode", "programmatic_no_ai_no_manual_candidate_selection");
        trace.addProperty("aiCandidateSelectionCount", 0);
        trace.addProperty("manualCandidateSelectionCount", 0);
        trace.add("sourceD3Ref", requiredObject(context, "sourceD3Ref").deepCopy());
        trace.add("catalogSnapshotRef", requiredObject(context, "catalogSnapshotRef").deepCopy());
        JsonObject frozenSnapshot = requiredObject(context, "catalogSnapshot");
        trace.add("terrainFieldRef", requiredObject(frozenSnapshot, "terrainFieldRef").deepCopy());
        trace.addProperty("terrainGateEvaluationScope", "all_intersecting_terrain_field_cells");
        trace.add("selections", selections.deepCopy());
        trace.add("connectivityPlan", connectivityPlan.asJson());
        trace.add("arrayCompositionSlots", arrayCompositionSlots(states));
        JsonArray groupResults = new JsonArray();
        states.values().forEach(state -> groupResults.add(state.asJson()));
        trace.add("groupResults", groupResults);
        return trace;
    }

    private static JsonObject extentMap(CityBlueprint blueprint, Map<String, GroupState> states,
                                        ConnectivityPlan connectivityPlan,
                                        JsonObject functionAreaFormationPlan) {
        JsonObject map = new JsonObject();
        map.addProperty("schema", EXTENT_SCHEMA);
        map.addProperty("cityId", blueprint.cityId());
        map.addProperty("generationSeed", blueprint.generationSeed());
        map.addProperty("connectivityPolicy", "RELATION_GRAPH_ARRAY_GROWTH_THEN_LAND_USE");
        map.addProperty("connectionSemantics", "STRUCTURE_FRONTIER_FOR_LAND_USE");
        map.addProperty("cityBoundaryPolicy", "D3_REVIEW_GRID_HARD_BOUNDARY");
        map.addProperty("functionAreaPolicy", "COMMITTED_BUILDINGS_THEN_RELATION_AND_PERCENTAGE_EXPANSION");
        map.add("functionAreaFormationPlan", functionAreaFormationPlan.deepCopy());
        map.addProperty("handoffThresholdPolicy", "STRICT_BILATERAL_MINIMUM");
        map.add("arrayCompositionSlots", arrayCompositionSlots(states));
        JsonArray groups = new JsonArray();
        states.values().forEach(state -> groups.add(state.extentJson()));
        map.add("groups", groups);
        JsonArray connections = new JsonArray();
        connectivityPlan.links.forEach(link -> connections.add(link.asJson(states)));
        boolean connected = !states.isEmpty() && states.values().stream()
                .allMatch(state -> state.anchorCount() > 0)
                && connectivityPlan.connected(states.keySet());
        map.addProperty("structureGraphConnected", connected);
        map.addProperty("landUseConnected", false);
        map.addProperty("landUseConnectionStatus", "PENDING_LAND_USE_COMPILE");
        map.add("connections", connections);
        return map;
    }

    private static JsonObject functionAreaFormationPlan(Map<String, GroupState> states,
                                                        BlockBounds planningBounds) {
        JsonObject plan = new JsonObject();
        plan.addProperty("schema", "city_function_area_formation_plan");
        plan.addProperty("status", "formed_from_committed_structures");
        plan.addProperty("policy", "COMMIT_BUILDINGS_BEFORE_FORMING_FUNCTION_AREAS");
        plan.addProperty("preallocatedAreaCount", 0);
        plan.addProperty("formedBeforeDynamicTargetFreeze", true);
        plan.add("planningBounds", CityStructureCandidateEnvelope.boundsJson(planningBounds));
        JsonArray groups = new JsonArray();
        states.values().forEach(state -> groups.add(state.functionAreaJson()));
        plan.add("groups", groups);
        return plan;
    }

    private static JsonArray arrayCompositionSlots(Map<String, GroupState> states) {
        JsonArray slots = new JsonArray();
        states.values().stream()
                .filter(state -> state.compositionSlot() != null)
                .forEach(state -> {
                    JsonObject value = state.compositionSlot().asJson();
                    value.addProperty("groupId", state.group().groupId());
                    slots.add(value);
                });
        return slots;
    }

    private static void refreshConnections(ConnectivityPlan plan, Map<String, GroupState> states) {
        for (ConnectivityLink link : plan.links) {
            GroupState first = states.get(link.fromGroupId);
            GroupState second = states.get(link.toGroupId);
            link.finalGapBlocks = nearestGap(first, second);
            link.connectionEdge = nearestConnectionEdge(first, second);
        }
    }

    private static ConnectionEdge nearestConnectionEdge(GroupState first, GroupState second) {
        Nearest best = null;
        for (BlockBounds source : first.envelopes()) {
            Nearest candidate = nearest(source, second.envelopes(), second.group().groupId());
            if (candidate != null && (best == null || candidate.gapBlocks() < best.gapBlocks())) {
                best = candidate;
            }
        }
        return best == null ? null : best.edge();
    }

    private static String nextFillRef(List<String> pool, Set<String> blockedRefs, int cursor) {
        if (pool.isEmpty()) return null;
        for (int offset = 0; offset < pool.size(); offset++) {
            String ref = pool.get(Math.floorMod(cursor + offset, pool.size()));
            if (blockedRefs.contains(ref)) continue;
            return ref;
        }
        return null;
    }

    private static int extentTargetArea(CityBlueprint.ExtentClass extentClass) {
        return switch (extentClass) {
            case SMALL -> 4_096;
            case MEDIUM -> 16_384;
            case LARGE -> 36_864;
        };
    }

    private static int extentMaxSpan(CityBlueprint.ExtentClass extentClass) {
        return switch (extentClass) {
            case SMALL -> 96;
            case MEDIUM -> 160;
            case LARGE -> 240;
        };
    }

    static int minimumGroupStructureCount(CityScale cityScale,
                                          CityBlueprint.ExtentClass extentClass) {
        return switch (cityScale) {
            case HAMLET -> switch (extentClass) {
                case SMALL -> 2;
                case MEDIUM -> 3;
                case LARGE -> 4;
            };
            case VILLAGE -> switch (extentClass) {
                case SMALL -> 3;
                case MEDIUM -> 4;
                case LARGE -> 6;
            };
            case TOWN -> switch (extentClass) {
                case SMALL -> 3;
                case MEDIUM -> 6;
                case LARGE -> 9;
            };
            case CITY -> switch (extentClass) {
                case SMALL -> 4;
                case MEDIUM -> 8;
                case LARGE -> 12;
            };
        };
    }

    static int minimumGroupStructureCount(CityScale cityScale,
                                          CityBlueprint.ExtentClass extentClass,
                                          String algorithm) {
        int minimum = minimumGroupStructureCount(cityScale, extentClass);
        if ("COURTYARD".equals(algorithm)) return Math.max(5, minimum);
        // The scale/extent table describes two-dimensional groups. LINEAR spends
        // the same extent on one street axis and frontage clearance, so derive a
        // smaller floor without weakening the required primary structure.
        if ("LINEAR".equals(algorithm)) return Math.max(2, minimum - 2);
        if (!"CENTER_SYMMETRIC".equals(algorithm) || (minimum & 1) == 1) return minimum;
        return Math.max(3, minimum - 1);
    }

    private static int priorityRank(CityBlueprint.GroupPriority priority) {
        return switch (priority) {
            case CORE -> 0;
            case STANDARD -> 1;
            case PERIPHERAL -> 2;
        };
    }

    private static boolean terrainAllowed(CityBlueprint.TerrainPolicy policy, double meanSlope) {
        double maximum = switch (policy) {
            case CONFORM -> 6.0;
            case BALANCED -> 12.0;
            case ASSERTIVE -> 18.0;
        };
        return meanSlope <= maximum;
    }

    private static void validateTerrainField(CityLandformReviewPackage review, LandUseTerrainField field) {
        BlockBounds expected = new BlockBounds(review.grid().blockMinX(), review.grid().blockMinZ(),
                review.grid().blockMaxX() - 1, review.grid().blockMaxZ() - 1);
        if (!review.cityId().equals(field.cityId())
                || review.grid().cellStepBlocks() != field.cellStepBlocks()
                || !expected.equals(field.planningBounds()) || field.cells().isEmpty()) {
            throw fail("CITY_BLUEPRINT_TERRAIN_FIELD_STALE",
                    "Frozen D3 terrain field identity does not match the D3 review grid.");
        }
    }

    private static String pattern(String algorithm) {
        return switch (algorithm) {
            case "GRID" -> "grid";
            case "LINEAR" -> "patch_axis_band";
            case "COURTYARD" -> "courtyard";
            case "ORGANIC_COMPACT" -> "organic_compact";
            case "CENTER_SYMMETRIC" -> "courtyard";
            default -> "loose_cluster";
        };
    }

    private static Set<String> patchRefs(CityLandformReviewPackage review) {
        Set<String> refs = new LinkedHashSet<>();
        review.landformPatches().forEach(patch -> refs.add(patch.landformPatchId()));
        return Set.copyOf(refs);
    }

    private static long tieKey(long seed, String... values) {
        StringBuilder input = new StringBuilder(Long.toString(seed));
        for (String value : values) input.append('\n').append(value);
        String hex = sha256(input.toString()).substring("sha256:".length(), "sha256:".length() + 16);
        return Long.parseUnsignedLong(hex, 16);
    }

    private static String contextIdentity(JsonObject context) {
        JsonObject core = context.deepCopy();
        core.remove("contextId");
        core.remove("preparedAt");
        return sha256(CityJson.GSON.toJson(core));
    }

    private static void requireArtifactCurrent(Path debugRoot, JsonObject artifact, String reason)
            throws IOException {
        Path path = resolveArtifact(debugRoot, artifact);
        String raw = requireFile(path, reason);
        if (!sha256(raw).equals(string(artifact, "contentHash"))) {
            throw fail(reason, "Artifact content hash changed: " + string(artifact, "path"));
        }
    }

    private static Path resolveArtifact(Path debugRoot, JsonObject artifact) {
        Path path = debugRoot.resolve(string(artifact, "path")).normalize();
        if (!path.startsWith(debugRoot.normalize())) throw fail("CITY_BLUEPRINT_ARTIFACT_PATH_INVALID",
                "Artifact escapes debug root.");
        return path;
    }

    private static CityBlueprint.ArtifactRef artifactRef(JsonObject object) {
        return new CityBlueprint.ArtifactRef(string(object, "path"), string(object, "schema"),
                string(object, "contentHash"));
    }

    private static Path requireRunDirectory(Path debugRoot, String runId) {
        Path runDir = debugRoot.resolve(runId).normalize();
        if (!runDir.startsWith(debugRoot.normalize()) || !Files.isDirectory(runDir)) {
            throw fail("CITY_BLUEPRINT_RUN_NOT_FOUND", runId);
        }
        return runDir;
    }

    private static JsonObject readObject(Path path, String reason) throws IOException {
        return JsonParser.parseString(requireFile(path, reason)).getAsJsonObject();
    }

    /**
     * Keeps already accepted pre-schema-migration artifacts readable without rewriting them or
     * weakening their content-hash checks. Normalization is applied only to in-memory copies after
     * the original files have passed identity validation.
     */
    public static JsonObject normalizeLegacySchemasForRead(JsonObject source) {
        JsonObject copy = source.deepCopy();
        normalizeLegacySchemas((JsonElement) copy);
        return copy;
    }

    private static void normalizeLegacySchemas(JsonElement element) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(CityBlueprintCompilerService::normalizeLegacySchemas);
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        if (!object.has("schema") && object.has("schemaVersion")
                && object.get("schemaVersion").isJsonPrimitive()) {
            object.addProperty("schema", currentSchemaName(object.get("schemaVersion").getAsString()));
        }
        object.remove("schemaVersion");
        object.entrySet().forEach(entry -> normalizeLegacySchemas(entry.getValue()));
    }

    private static String currentSchemaName(String value) {
        int versionMarker = value.lastIndexOf(".v");
        if (versionMarker > 0 && value.substring(versionMarker + 2).matches("[0-9]+(?:\\.[0-9]+)*")) {
            return value.substring(0, versionMarker);
        }
        return value;
    }

    private static String requireFile(Path path, String reason) throws IOException {
        if (!Files.isRegularFile(path)) throw fail(reason, path.toString());
        return Files.readString(path);
    }

    private static void writeAtomic(Path path, JsonObject value) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "." + path.getFileName(), ".tmp");
        try {
            Files.writeString(temporary, CityJson.GSON.toJson(value));
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String sha256(String raw) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String ref(Path debugRoot, Path path) {
        return debugRoot.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    private static IllegalArgumentException fail(String code, String message) {
        return new IllegalArgumentException(code + ": " + message);
    }

    private static String safe(String raw) {
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static JsonObject requiredObject(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonObject()) {
            throw fail("CITY_BLUEPRINT_COMPILER_INPUT_INVALID", key + " object is required.");
        }
        return object.getAsJsonObject(key);
    }

    private static JsonArray array(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonArray()
                ? object.getAsJsonArray(key) : new JsonArray();
    }

    private static String string(JsonObject object, String key) {
        if (object != null && "schema".equals(key) && !object.has(key)
                && object.has("schemaVersion") && !object.get("schemaVersion").isJsonNull()) {
            return currentSchemaName(object.get("schemaVersion").getAsString());
        }
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString() : "";
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsInt() : fallback;
    }

    private static double doubleValue(JsonObject object, String key, double fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsDouble() : fallback;
    }

    private static boolean booleanValue(JsonObject object, String key, boolean fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsBoolean() : fallback;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    public record CompilationResult(boolean ok, JsonObject structureAnchorPlan, JsonObject compileTrace,
                                    JsonObject groupExtentMap, JsonObject terraSenseProfileSource,
                                    JsonObject templateCatalog, JsonObject landscapeCapacityReservationPlan,
                                    String reasonCode, String message) {
        static CompilationResult compiled(JsonObject plan, JsonObject trace, JsonObject extent,
                                          JsonObject structureSource, JsonObject templateCatalog,
                                          JsonObject landscapeCapacityReservationPlan) {
            return new CompilationResult(true, plan, trace, extent, structureSource.deepCopy(),
                    templateCatalog.deepCopy(), landscapeCapacityReservationPlan.deepCopy(), "", "");
        }

        static CompilationResult failed(JsonObject trace, String reasonCode, String message) {
            return new CompilationResult(false, null, trace, null, null, null, null, reasonCode, message);
        }
    }

    private record TemplateCandidate(String templateId, String variantId) {
    }

    private record TemplateDemand(int widthBlocks, int depthBlocks) {
    }

    private record PatchScope(String name, List<LandformPatchSummary> patches) {
    }

    private record RequiredRequest(String groupId, String structureRef, int ordinal) {
    }

    private record Placement(JsonObject anchor, JsonObject collisionEnvelope, String structureRef,
                             boolean required, PlacementPhase phase, ConnectivityFit connectivity) {
    }

    private record TerrainCandidateEvaluation(boolean passed, String reasonCode,
                                              String resolvedTerrainMode, JsonObject trace) {
        static TerrainCandidateEvaluation rejected(String reasonCode, String structureRef, JsonObject trace) {
            JsonObject value = trace.deepCopy();
            if (!value.has("structureRef")) value.addProperty("structureRef", structureRef);
            if (!value.has("status")) value.addProperty("status", "rejected");
            if (!value.has("reasonCode")) value.addProperty("reasonCode", reasonCode);
            return new TerrainCandidateEvaluation(false, reasonCode, "", value);
        }
    }

    private record CandidateChoice(JsonObject candidate, TemplateCandidate template,
                                   JsonObject blueprintLayout, double baseScore,
                                   double relationFit, ConnectivityFit connectivity,
                                   TerrainCandidateEvaluation terrain, double targetFit,
                                   double total, long tieKey) {
    }

    private record CenterSymmetricChoice(JsonObject candidate, TemplateCandidate template,
                                         JsonObject blueprintLayout,
                                         CityBlueprintGroupLayoutPlanner.SymmetricPair pair,
                                         double baseScore, double relationFit,
                                         ConnectivityFit connectivity,
                                         TerrainCandidateEvaluation terrain,
                                         double total, long tieKey) {
    }

    private record ConnectionItem(String structureRef, TemplateCandidate template) {
    }

    private record ConnectionBatch(String ownerGroupId, List<Placement> placements) {
        private ConnectionBatch {
            placements = List.copyOf(placements);
        }
    }

    private record AutomaticCandidate(JsonObject candidate, List<Placement> placements,
                                      double targetGapBlocks, double engineScore) {
        private AutomaticCandidate {
            candidate = candidate.deepCopy();
            placements = List.copyOf(placements);
        }
    }

    private record ConnectionConfiguration(String structurePoolRef,
                                           String algorithmProfileRef,
                                           String algorithm,
                                           String plannerType,
                                           CityBlueprint.DensityClass densityClass,
                                           CityBlueprint.ConnectionParameters parameters,
                                           boolean inheritedStructurePool,
                                           boolean inheritedAlgorithm,
                                           boolean inheritedDensity,
                                           CityBlueprintGroupLayoutPlanner.Parameters layoutParameters) {
        JsonObject asJson() {
            JsonObject value = new JsonObject();
            value.addProperty("structurePoolRef", structurePoolRef);
            value.addProperty("algorithmProfileRef", algorithmProfileRef);
            value.addProperty("algorithm", algorithm);
            value.addProperty("plannerType", plannerType);
            value.addProperty("densityClass", densityClass.name());
            value.addProperty("structurePoolInheritedFromFillPool", inheritedStructurePool);
            value.addProperty("algorithmInheritedFromGroup", inheritedAlgorithm);
            value.addProperty("densityInheritedFromGroup", inheritedDensity);
            value.add("semanticParameters", connectionParametersJson(parameters));
            value.add("derivedLayoutParameters", layoutParameters.asJson());
            value.addProperty("frontierRingPolicy", "NEAR_ONLY_WITH_LATERAL_VARIANTS");
            return value;
        }
    }

    private static final class CommittedArray {
        private final String arrayId;
        private BlockBounds bodyBounds;
        private BlockBounds collisionBounds;
        private int structureCount;

        private CommittedArray(String arrayId, BlockBounds bodyBounds, BlockBounds collisionBounds) {
            this.arrayId = arrayId;
            this.bodyBounds = bodyBounds;
            this.collisionBounds = collisionBounds;
            this.structureCount = 1;
        }

        String arrayId() { return arrayId; }
        BlockBounds bodyBounds() { return bodyBounds; }
        BlockBounds collisionBounds() { return collisionBounds; }
        void add(BlockBounds body, BlockBounds collision) {
            bodyBounds = union(bodyBounds, body);
            collisionBounds = union(collisionBounds, collision);
            structureCount++;
        }
    }

    private record ConnectionEdge(int fromX, int fromZ, int toX, int toZ) {
        JsonObject asJson() {
            JsonObject value = new JsonObject();
            value.addProperty("fromX", fromX);
            value.addProperty("fromZ", fromZ);
            value.addProperty("toX", toX);
            value.addProperty("toZ", toZ);
            return value;
        }
    }

    private record Nearest(double gapBlocks, String ownerGroupId, ConnectionEdge edge) {
    }

    private record OutwardTarget(BlockPoint point, boolean pending, String targetGroupId, double gapBlocks) {
        static OutwardTarget none() {
            return new OutwardTarget(null, false, "", 0.0);
        }
    }

    private record PatchCellPair(PatchMemberCell first, PatchMemberCell second,
                                 BlockPoint firstCenter, BlockPoint secondCenter) {
    }

    private record CompositionSlot(String compositionId, String parentAlgorithm,
                                   String centerGroupId, int slotIndex, int pairIndex,
                                   int plannedSpanBlocks, BlockPoint origin, BlockPoint placementOrigin,
                                   BlockBounds slotBounds) {
        JsonObject asJson() {
            JsonObject value = new JsonObject();
            value.addProperty("compositionId", compositionId);
            value.addProperty("parentAlgorithm", parentAlgorithm);
            value.addProperty("centerGroupId", centerGroupId);
            value.addProperty("slotIndex", slotIndex);
            if (pairIndex >= 0) value.addProperty("pairIndex", pairIndex);
            value.addProperty("plannedSpanBlocks", plannedSpanBlocks);
            value.addProperty("spanSource", "TEMPLATE_ARRAY_DEMAND");
            JsonObject originJson = new JsonObject();
            originJson.addProperty("x", origin.x());
            originJson.addProperty("z", origin.z());
            value.add("origin", originJson);
            value.add("placementOrigin", placementOrigin.asJson());
            value.add("slotBounds", CityStructureCandidateEnvelope.boundsJson(slotBounds));
            return value;
        }
    }

    private record ConnectivityFit(boolean allowed, double score, double gapBlocks, String reasonCode,
                                   ConnectionEdge edge) {
        static ConnectivityFit rejected(String reasonCode) {
            return new ConnectivityFit(false, 0.0, Double.POSITIVE_INFINITY, reasonCode, null);
        }
        static ConnectivityFit allowed(double score, double gapBlocks, ConnectionEdge edge) {
            return new ConnectivityFit(true, score, gapBlocks, "", edge);
        }
    }

    private enum PlacementPhase {
        REQUIRED("required", "required"),
        CONNECTIVITY("connectivity_growth", "connectivity"),
        PERCENTAGE("percentage_growth", "percentage"),
        FILL("fill", "fill");

        private final String traceName;
        private final String anchorLabel;

        PlacementPhase(String traceName, String anchorLabel) {
            this.traceName = traceName;
            this.anchorLabel = anchorLabel;
        }
    }

    private static final class ConnectivityPlan {
        private final List<ConnectivityLink> links;

        private ConnectivityPlan(List<ConnectivityLink> links) {
            this.links = List.copyOf(links);
        }

        static ConnectivityPlan empty() {
            return new ConnectivityPlan(List.of());
        }

        JsonObject asJson() {
            JsonObject value = new JsonObject();
            value.addProperty("topologyPolicy", "EXPLICIT_RELATIONS_ONLY_NO_UNRELATED_FALLBACK");
            value.addProperty("blockedEdgePolicy", "HARD_RELATION_FAILS_ACCEPTANCE_SOFT_RELATION_WARNS");
            value.addProperty("handoffThresholdPolicy", "STRICT_BILATERAL_MINIMUM");
            value.addProperty("edgeCount", links.size());
            JsonArray edges = new JsonArray();
            links.forEach(link -> edges.add(link.traceJson()));
            value.add("edges", edges);
            value.addProperty("fallbackEdgeCount", links.stream()
                    .filter(link -> "FALLBACK".equals(link.source)).count());
            value.addProperty("skippedEdgeCount", links.stream()
                    .filter(link -> link.status.startsWith("SKIPPED_")).count());
            value.addProperty("bridgeDelegatedEdgeCount", links.stream()
                    .filter(link -> "BRIDGE_DELEGATED".equals(link.status)).count());
            value.addProperty("connectivityDegraded", links.stream()
                    .anyMatch(link -> link.status.startsWith("SKIPPED_")));
            return value;
        }

        boolean connected(Set<String> groupIds) {
            if (groupIds.isEmpty()) return false;
            Components components = new Components(groupIds);
            links.forEach(link -> {
                if (link.satisfied() && groupIds.contains(link.fromGroupId)
                        && groupIds.contains(link.toGroupId)) {
                    components.union(link.fromGroupId, link.toGroupId);
                }
            });
            return components.componentCount() == 1;
        }

        boolean degradedOrBridgeDelegated(String firstGroupId, String secondGroupId) {
            String key = pairKey(firstGroupId, secondGroupId);
            return links.stream().anyMatch(link -> pairKey(link.fromGroupId, link.toGroupId).equals(key)
                    && (link.status.startsWith("SKIPPED_")
                    || "BRIDGE_DELEGATED".equals(link.status)));
        }

        Set<String> skippedPairKeys() {
            return links.stream()
                    .filter(link -> link.status.startsWith("SKIPPED_"))
                    .map(link -> pairKey(link.fromGroupId, link.toGroupId))
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }

        void markBridgeDelegated(JsonObject mainRoadPlan) {
            for (JsonElement element : array(mainRoadPlan, "bridgeConnections")) {
                if (!element.isJsonObject()) continue;
                JsonObject bridge = element.getAsJsonObject();
                String key = pairKey(string(bridge, "fromGroupId"), string(bridge, "toGroupId"));
                links.stream()
                        .filter(link -> pairKey(link.fromGroupId, link.toGroupId).equals(key))
                        .forEach(link -> link.status = "BRIDGE_DELEGATED");
            }
        }
    }

    private static final class ConnectivityLink {
        private final String fromGroupId;
        private final String toGroupId;
        private final String source;
        private final String sourceReason;
        private final boolean required;
        private final int handoffGapBlocks;
        private final double initialGapBlocks;
        private double finalGapBlocks;
        private int connectionStructureCount;
        private int connectionBatchCount;
        private String status = "PLANNED";
        private ConnectionEdge connectionEdge;

        private ConnectivityLink(String fromGroupId, String toGroupId, String source,
                                 String sourceReason, boolean required, int handoffGapBlocks,
                                 double initialGapBlocks) {
            this.fromGroupId = fromGroupId;
            this.toGroupId = toGroupId;
            this.source = source;
            this.sourceReason = sourceReason;
            this.required = required;
            this.handoffGapBlocks = handoffGapBlocks;
            this.initialGapBlocks = initialGapBlocks;
            this.finalGapBlocks = initialGapBlocks;
        }

        String describe() {
            return fromGroupId + " -> " + toGroupId;
        }

        boolean satisfied() {
            return finalGapBlocks <= handoffGapBlocks || "BRIDGE_DELEGATED".equals(status);
        }

        JsonObject traceJson() {
            JsonObject value = baseJson();
            value.addProperty("status", status);
            value.addProperty("initialGapBlocks", initialGapBlocks);
            value.addProperty("finalGapBlocks", finalGapBlocks);
            value.addProperty("connectionStructureCount", connectionStructureCount);
            value.addProperty("connectionBatchCount", connectionBatchCount);
            return value;
        }

        JsonObject asJson(Map<String, GroupState> states) {
            JsonObject value = traceJson();
            GroupState from = states.get(fromGroupId);
            GroupState to = states.get(toGroupId);
            value.addProperty("fromHandoffGapBlocks",
                    from.connectionConfiguration().layoutParameters().landUseHandoffGapBlocks());
            value.addProperty("toHandoffGapBlocks",
                    to.connectionConfiguration().layoutParameters().landUseHandoffGapBlocks());
            value.addProperty("landUseHandoffReady", finalGapBlocks <= handoffGapBlocks);
            value.addProperty("gapBlocks", finalGapBlocks);
            if (connectionEdge != null) value.add("connectionEdge", connectionEdge.asJson());
            return value;
        }

        private JsonObject baseJson() {
            JsonObject value = new JsonObject();
            value.addProperty("fromGroupId", fromGroupId);
            value.addProperty("toGroupId", toGroupId);
            value.addProperty("topologySource", source);
            value.addProperty("topologyReason", sourceReason);
            value.addProperty("required", required);
            value.addProperty("handoffGapBlocks", handoffGapBlocks);
            return value;
        }
    }

    private static final class Components {
        private final Map<String, String> parent = new LinkedHashMap<>();

        private Components(Iterable<String> ids) {
            ids.forEach(id -> parent.put(id, id));
        }

        String find(String id) {
            String value = parent.get(id);
            if (value == null || value.equals(id)) return value;
            String root = find(value);
            parent.put(id, root);
            return root;
        }

        boolean connected(String first, String second) {
            return find(first).equals(find(second));
        }

        void union(String first, String second) {
            String firstRoot = find(first);
            String secondRoot = find(second);
            if (firstRoot.equals(secondRoot)) return;
            if (firstRoot.compareTo(secondRoot) <= 0) parent.put(secondRoot, firstRoot);
            else parent.put(firstRoot, secondRoot);
        }

        int componentCount() {
            return (int) parent.keySet().stream().map(this::find).distinct().count();
        }
    }

    private static final class GroupState {
        private final CityBlueprint.Group group;
        private final List<LandformPatchSummary> patches;
        private final List<LandformPatchSummary> connectionPatches;
        private final Map<String, LandformPatchSummary> patchByRef;
        private final int patchStepBlocks;
        private final BlockBounds planningBounds;
        private final BlockBounds formationBounds;
        private final BlockPoint preferredOrigin;
        private final CompositionSlot compositionSlot;
        private final String layoutAlgorithm;
        private final CityBlueprintGroupLayoutPlanner.Parameters layoutParameters;
        private final ConnectionConfiguration connectionConfiguration;
        private final int minimumStructureCount;
        private final Set<String> groupSeparationExemptGroupIds;
        private final CityGroupSpatialDemand spatialDemand;
        private int dynamicTargetAreaBlocks;
        private boolean areaExpansionActive;
        private final Set<String> blockedRefs = new LinkedHashSet<>();
        private final List<TerrainPlacementFailure> terrainPlacementFailures = new ArrayList<>();
        private final Set<String> claimedPatchRefs = new LinkedHashSet<>();
        private final Map<String, Integer> structureCounts = new LinkedHashMap<>();
        private final Map<String, Integer> requiredStructureCounts = new LinkedHashMap<>();
        private final List<BlockBounds> envelopes = new ArrayList<>();
        private final List<CityGridStreetReservation.Placement> gridStreetReservations = new ArrayList<>();
        private final Map<String, CommittedArray> committedArrays = new LinkedHashMap<>();
        private int anchorCount;
        private int requiredCount;
        private int builtCollisionAreaBlocks;
        private int spatialDemandBlocks;
        private String stopReason = "";
        private BlockBounds extent;
        private CityBlueprintGroupLayoutPlanner.Frame layoutFrame;
        private int outwardGuidedPlacementCount;
        private int connectionStructureCount;
        private int connectionSpatialDemandBlocks;
        private int connectionExpansionBlocks;
        private boolean extentExpandedForConnectivity;
        private BlockBounds coreExtent;
        private List<BlockBounds> initialFormationEnvelopes = List.of();
        private int initialFormationAreaBlocks;
        private List<BlockBounds> landscapeFormationSpans = List.of();
        private List<BlockBounds> initialLandscapeFormationSpans = List.of();
        private int landscapeAreaBlocks;
        private int initialLandscapeAreaBlocks;
        private int connectionFillCursor;
        private int connectionBatchCount;
        private int percentageFillCursor;
        private int percentageBatchCount;
        private int percentageStructureCount;
        private int exactSlotCursor;
        private int linearFrontierSlot;
        private String exactSearchStructureRef = "";
        private BlockPoint streetBandStart;
        private BlockPoint streetBandEnd;
        private int streetBandMaxProjection;
        private final boolean landscapeGapCirculation;

        private GroupState(CityBlueprint.Group group, List<LandformPatchSummary> patches,
                           List<LandformPatchSummary> connectionPatches,
                           int patchStepBlocks, BlockBounds planningBounds, BlockBounds formationBounds,
                           BlockPoint preferredOrigin, CompositionSlot compositionSlot,
                           String layoutAlgorithm,
                           CityBlueprintGroupLayoutPlanner.Parameters layoutParameters,
                           ConnectionConfiguration connectionConfiguration,
                           int minimumStructureCount,
                           Set<String> groupSeparationExemptGroupIds,
                           CityGroupSpatialDemand spatialDemand,
                           boolean landscapeGapCirculation) {
            this.group = group;
            this.patches = List.copyOf(patches);
            this.connectionPatches = List.copyOf(connectionPatches);
            Map<String, LandformPatchSummary> byRef = new LinkedHashMap<>();
            connectionPatches.forEach(patch -> byRef.put(patch.landformPatchId(), patch));
            patches.forEach(patch -> byRef.put(patch.landformPatchId(), patch));
            this.patchByRef = Map.copyOf(byRef);
            this.patchStepBlocks = patchStepBlocks;
            this.planningBounds = planningBounds;
            this.formationBounds = formationBounds;
            this.preferredOrigin = preferredOrigin;
            this.compositionSlot = compositionSlot;
            this.layoutAlgorithm = layoutAlgorithm;
            this.layoutParameters = layoutParameters;
            this.connectionConfiguration = connectionConfiguration;
            this.minimumStructureCount = minimumStructureCount;
            this.groupSeparationExemptGroupIds = Set.copyOf(groupSeparationExemptGroupIds);
            this.spatialDemand = java.util.Objects.requireNonNull(spatialDemand, "spatialDemand");
            this.landscapeGapCirculation = landscapeGapCirculation;
            this.dynamicTargetAreaBlocks = spatialDemand.targetAreaBlocks();
        }

        CityBlueprint.Group group() { return group; }
        List<LandformPatchSummary> patches() { return patches; }
        List<LandformPatchSummary> connectionPatches() { return connectionPatches; }
        List<LandformPatchSummary> formationPatches() {
            // The selected Patch anchors the first committed structure only. Once the Group has
            // started, its array grows continuously across adjacent D3 Patch labels; the real
            // function area is derived from the claims that actually commit.
            if (extent != null) return connectionPatches;
            CityBlueprint.PlacementRelation placement = group.placementRelation();
            if (placement != null) {
                if (placement.kind() == CityBlueprint.PlacementRelationKind.BETWEEN_GROUPS) {
                    return connectionPatches;
                }
                List<String> refs = placement.kind() == CityBlueprint.PlacementRelationKind.ALONG_PATCH_BOUNDARY
                        ? placement.patchRefs().subList(0, 1) : placement.patchRefs();
                List<LandformPatchSummary> relationPatches = refs.stream()
                        .map(patchByRef::get)
                        .filter(java.util.Objects::nonNull)
                        .toList();
                if (!relationPatches.isEmpty()) return relationPatches;
            }
            if (claimedPatchRefs.isEmpty()) return patches;
            Map<String, LandformPatchSummary> formation = new LinkedHashMap<>();
            patches.forEach(patch -> formation.put(patch.landformPatchId(), patch));
            connectionPatches.stream()
                    .filter(patch -> claimedPatchRefs.contains(patch.landformPatchId()))
                    .forEach(patch -> formation.putIfAbsent(patch.landformPatchId(), patch));
            return List.copyOf(formation.values());
        }
        Map<String, LandformPatchSummary> patchByRef() { return patchByRef; }
        int patchStepBlocks() { return patchStepBlocks; }
        BlockBounds planningBounds() { return planningBounds; }
        BlockBounds formationBounds() { return formationBounds; }
        BlockBounds legalBounds(boolean connectivityExpansion) {
            return connectivityExpansion || compositionSlot == null ? planningBounds : formationBounds;
        }
        BlockPoint preferredOrigin() { return preferredOrigin; }
        CompositionSlot compositionSlot() { return compositionSlot; }
        boolean hasExplicitPlacementRelation() {
            return group.placementRelation() != null;
        }
        String initialPatchSelectionScope() {
            if (anchorCount > 0) return "continuous_growth_from_selected_patch";
            if (compositionSlot != null) return "array_composition_slot";
            if (group.placementRelation() != null) {
                return "placement_relation_" + group.placementRelation().kind().name().toLowerCase(java.util.Locale.ROOT);
            }
            return "blueprint_preferred";
        }
        String layoutAlgorithm() { return layoutAlgorithm; }
        CityBlueprintGroupLayoutPlanner.Parameters layoutParameters() { return layoutParameters; }
        ConnectionConfiguration connectionConfiguration() { return connectionConfiguration; }
        CityBlueprintGroupLayoutPlanner.Frame layoutFrame() { return layoutFrame; }
        int anchorCount() { return anchorCount; }
        int layoutSlotIndex() { return exactInternalGuides() ? exactSlotCursor : anchorCount; }
        void beginExactSlotSearch(String structureRef) {
            if (!exactInternalGuides()) return;
            if (!structureRef.equals(exactSearchStructureRef)) {
                exactSlotCursor = "LINEAR".equals(layoutAlgorithm) ? linearFrontierSlot : 0;
            }
            exactSearchStructureRef = structureRef;
        }
        boolean advancePastIllegalExactSlot() {
            if (!skipIllegalExactSlots()) return false;
            int limit = switch (layoutAlgorithm) {
                // A LINEAR group must grow from its current street frontier. Try the opposite
                // frontage and one next-rank slot, but do not scan an empty street far ahead.
                case "LINEAR" -> linearFrontierSlot + 2;
                case "ORGANIC_COMPACT" -> Math.max(32, spatialDemand.plannedStructureCount() * 12);
                default -> Math.max(8, spatialDemand.plannedStructureCount() * 4);
            };
            int nextSlotIndex = exactSlotCursor + 1;
            if ("COMPACT".equals(layoutAlgorithm)
                    && !CityBlueprintGroupLayoutPlanner.compactOuterRingHasLocalFrontier(
                    nextSlotIndex, anchorCount)) return false;
            if (nextSlotIndex >= limit) return false;
            exactSlotCursor++;
            return true;
        }
        boolean compactLocalFrontierExhausted() {
            return "COMPACT".equals(layoutAlgorithm)
                    && anchorCount == 1
                    && exactSlotCursor + 1 >= 8;
        }
        boolean exactInternalGuides() { return true; }
        boolean fixedInternalSpacing() { return !"ORGANIC_COMPACT".equals(layoutAlgorithm); }
        private boolean skipIllegalExactSlots() {
            return "GRID".equals(layoutAlgorithm) || "COURTYARD".equals(layoutAlgorithm)
                    || "LINEAR".equals(layoutAlgorithm) || "COMPACT".equals(layoutAlgorithm)
                    || "ORGANIC_COMPACT".equals(layoutAlgorithm);
        }
        int requiredCount() { return requiredCount; }
        int minimumStructureCount() { return minimumStructureCount; }
        Set<String> groupSeparationExemptGroupIds() { return groupSeparationExemptGroupIds; }
        boolean hardSkeletonUsesExactGuides() {
            return "GRID".equals(layoutAlgorithm) || "COURTYARD".equals(layoutAlgorithm)
                    || "LINEAR".equals(layoutAlgorithm) || "CENTER_SYMMETRIC".equals(layoutAlgorithm);
        }
        boolean requiresInternalRoadGap() {
            return !landscapeGapCirculation && ("GRID".equals(layoutAlgorithm) || "COURTYARD".equals(layoutAlgorithm)
                    || "LINEAR".equals(layoutAlgorithm));
        }
        String gridStreetReservationFailure(BlockBounds footprint, JsonObject layout) {
            if (landscapeGapCirculation || !"GRID".equals(layoutAlgorithm) || layout == null
                    || !layout.has("gridRow") || !layout.has("gridColumn")) return "";
            CityGridStreetReservation.Placement candidate = new CityGridStreetReservation.Placement(
                    layout.get("gridRow").getAsInt(), layout.get("gridColumn").getAsInt(), footprint);
            return CityGridStreetReservation.rejectionReason(
                    gridStreetReservations, candidate, layoutParameters);
        }
        JsonObject streetBandPlan() {
            if (!"LINEAR".equals(layoutAlgorithm) || streetBandStart == null || streetBandEnd == null) {
                return null;
            }
            BlockPoint resolvedStart = streetBandStart;
            BlockPoint resolvedEnd = streetBandEnd;
            if (envelopes.size() > 2) {
                boolean horizontal = Math.abs(layoutFrame.axisX()) >= Math.abs(layoutFrame.axisZ());
                int reference = horizontal ? streetBandStart.z() : streetBandStart.x();
                List<BlockBounds> left = envelopes.subList(1, envelopes.size()).stream()
                        .filter(bounds -> (horizontal ? centerZ(bounds) : centerX(bounds)) < reference)
                        .toList();
                List<BlockBounds> right = envelopes.subList(1, envelopes.size()).stream()
                        .filter(bounds -> (horizontal ? centerZ(bounds) : centerX(bounds)) > reference)
                        .toList();
                if (!left.isEmpty() && !right.isEmpty()) {
                    int leftEdge = horizontal
                            ? left.stream().mapToInt(BlockBounds::maxZ).max().orElse(reference)
                            : left.stream().mapToInt(BlockBounds::maxX).max().orElse(reference);
                    int rightEdge = horizontal
                            ? right.stream().mapToInt(BlockBounds::minZ).min().orElse(reference)
                            : right.stream().mapToInt(BlockBounds::minX).min().orElse(reference);
                    int gapCenter = Math.floorDiv(leftEdge + rightEdge, 2);
                    resolvedStart = horizontal
                            ? new BlockPoint(streetBandStart.x(), gapCenter)
                            : new BlockPoint(gapCenter, streetBandStart.z());
                    resolvedEnd = horizontal
                            ? new BlockPoint(streetBandEnd.x(), gapCenter)
                            : new BlockPoint(gapCenter, streetBandEnd.z());
                }
            }
            int lowerHalfWidth = (layoutParameters.streetBandWidthBlocks() - 1) / 2;
            int upperHalfWidth = layoutParameters.streetBandWidthBlocks() / 2;
            boolean horizontalStreet = resolvedStart.z() == resolvedEnd.z();
            BlockBounds streetBounds = horizontalStreet
                    ? new BlockBounds(Math.min(resolvedStart.x(), resolvedEnd.x()),
                    resolvedStart.z() - lowerHalfWidth,
                    Math.max(resolvedStart.x(), resolvedEnd.x()),
                    resolvedStart.z() + upperHalfWidth)
                    : new BlockBounds(resolvedStart.x() - lowerHalfWidth,
                    Math.min(resolvedStart.z(), resolvedEnd.z()),
                    resolvedStart.x() + upperHalfWidth,
                    Math.max(resolvedStart.z(), resolvedEnd.z()));
            int platformHalfWidth = spatialDemand.formationWidthBlocks() / 2;
            boolean horizontal = Math.abs(layoutFrame.axisX()) >= Math.abs(layoutFrame.axisZ());
            BlockBounds platformBounds = horizontal
                    ? new BlockBounds(streetBounds.minX(),
                    Math.min(resolvedStart.z(), resolvedEnd.z()) - platformHalfWidth,
                    streetBounds.maxX(), Math.max(resolvedStart.z(), resolvedEnd.z()) + platformHalfWidth)
                    : new BlockBounds(Math.min(resolvedStart.x(), resolvedEnd.x()) - platformHalfWidth,
                    streetBounds.minZ(), Math.max(resolvedStart.x(), resolvedEnd.x()) + platformHalfWidth,
                    streetBounds.maxZ());
            JsonObject value = new JsonObject();
            value.addProperty("schema", "city_internal_street_band");
            value.addProperty("streetBandId", group.groupId() + "::internal_street");
            value.addProperty("roadNetworkId", group.groupId() + "::LINEAR_STREET_BAND");
            value.addProperty("roadKind", "LINEAR_STREET_BAND");
            value.addProperty("segmentIndex", 0);
            value.addProperty("groupId", group.groupId());
            value.addProperty("geometryMode", "STRAIGHT_AXIS_CLIPPED_BY_TERRAIN");
            value.addProperty("widthBlocks", layoutParameters.streetBandWidthBlocks());
            value.addProperty("surfacePolicy", "FOLLOW_TERRAIN_STEP_GRADED");
            value.addProperty("crossSectionProfile", "STAIR_SLAB_STAIR");
            value.addProperty("hardSkeleton", true);
            value.addProperty("axisX", layoutFrame.axisX());
            value.addProperty("axisZ", layoutFrame.axisZ());
            value.add("start", resolvedStart.asJson());
            value.add("end", resolvedEnd.asJson());
            value.add("bounds", CityStructureCandidateEnvelope.boundsJson(streetBounds));
            value.add("platformBounds", CityStructureCandidateEnvelope.boundsJson(platformBounds));
            value.addProperty("platformPolicy", "LOCAL_HARD_SKELETON");
            return value;
        }
        int internalStructureCount() { return anchorCount - connectionStructureCount; }
        boolean landscapeGapCirculation() { return landscapeGapCirculation; }
        CityGroupSpatialDemand spatialDemand() { return spatialDemand; }
        int targetAreaBlocks() { return dynamicTargetAreaBlocks; }
        int buildingTargetAreaBlocks() {
            return Math.max(0, (int) Math.ceil(dynamicTargetAreaBlocks
                    * group.spaceComposition().buildingShare()));
        }
        int landscapeTargetAreaBlocks() {
            return Math.max(0, (int) Math.ceil(dynamicTargetAreaBlocks
                    * group.spaceComposition().landscapeShare()));
        }
        double targetAreaShare() { return group.targetAreaShare(); }
        void setDynamicTargetAreaBlocks(int value) {
            dynamicTargetAreaBlocks = Math.max(0, value);
        }
        int dynamicTargetAreaBlocks() { return dynamicTargetAreaBlocks; }
        void beginAreaExpansion() {
            areaExpansionActive = true;
            stopReason = "";
            blockedRefs.clear();
            if (exactInternalGuides()) {
                exactSlotCursor = Math.max(exactSlotCursor, anchorCount);
                linearFrontierSlot = Math.max(linearFrontierSlot, exactSlotCursor);
            }
        }
        boolean areaExpansionActive() { return areaExpansionActive; }
        int spatialDemandBlocks() { return spatialDemandBlocks; }
        int internalSpatialDemandBlocks() { return spatialDemandBlocks - connectionSpatialDemandBlocks; }
        int ownedAreaBlocks() { return spatialDemandBlocks + landscapeAreaBlocks; }
        int internalOwnedAreaBlocks() { return internalSpatialDemandBlocks() + landscapeAreaBlocks; }
        String stopReason() { return stopReason; }
        Set<String> blockedRefs() { return blockedRefs; }
        List<BlockBounds> envelopes() { return List.copyOf(envelopes); }
        BlockBounds centerStructureExtent() {
            if (requiredCount != 1 || envelopes.isEmpty()) {
                throw fail("CITY_BLUEPRINT_CENTER_SYMMETRIC_STATE_INVALID",
                        "CENTER_SYMMETRIC requires exactly one committed center structure.");
            }
            return envelopes.get(0);
        }
        List<BlockBounds> bodyEnvelopes() {
            return committedArrays.values().stream().map(CommittedArray::bodyBounds).toList();
        }
        int connectionFillCursor() { return connectionFillCursor; }
        int connectionBatchCount() { return connectionBatchCount; }
        int percentageFillCursor() { return percentageFillCursor; }
        int percentageBatchCount() { return percentageBatchCount; }
        GroupState fresh() {
            return new GroupState(group, patches, connectionPatches, patchStepBlocks, planningBounds,
                    formationBounds, preferredOrigin, compositionSlot, layoutAlgorithm, layoutParameters,
                    connectionConfiguration, minimumStructureCount, groupSeparationExemptGroupIds,
                    spatialDemand, landscapeGapCirculation);
        }
        void advanceConnectionCursor(int count) {
            connectionFillCursor += count;
            connectionBatchCount++;
        }
        void advancePercentageCursor(int count) {
            percentageFillCursor += count;
            percentageBatchCount++;
        }
        List<String> percentageExpansionDirections() {
            List<String> directions = new ArrayList<>(List.of(
                    "north", "east", "south", "west",
                    "northeast", "southeast", "southwest", "northwest"));
            directions.sort(Comparator.comparingInt(this::directionalCapacity).reversed()
                    .thenComparing(direction -> direction));
            return List.copyOf(directions);
        }
        private int directionalCapacity(String direction) {
            if (extent == null) return 0;
            int north = extent.minZ() - planningBounds.minZ();
            int south = planningBounds.maxZ() - extent.maxZ();
            int east = planningBounds.maxX() - extent.maxX();
            int west = extent.minX() - planningBounds.minX();
            return switch (direction) {
                case "north" -> north;
                case "south" -> south;
                case "east" -> east;
                case "west" -> west;
                case "northeast" -> Math.min(north, east);
                case "northwest" -> Math.min(north, west);
                case "southeast" -> Math.min(south, east);
                case "southwest" -> Math.min(south, west);
                default -> 0;
            };
        }
        CommittedArray outwardArray(String direction) {
            return committedArrays.values().stream().max(Comparator
                    .comparingInt((CommittedArray array) -> outwardScore(array.bodyBounds(), direction))
                    .thenComparing(CommittedArray::arrayId)).orElse(null);
        }
        private static int outwardScore(BlockBounds bounds, String direction) {
            return switch (direction) {
                case "north" -> -bounds.minZ();
                case "south" -> bounds.maxZ();
                case "east" -> bounds.maxX();
                case "west" -> -bounds.minX();
                case "northeast" -> bounds.maxX() - bounds.minZ();
                case "northwest" -> -bounds.minX() - bounds.minZ();
                case "southeast" -> bounds.maxX() + bounds.maxZ();
                case "southwest" -> bounds.maxZ() - bounds.minX();
                default -> 0;
            };
        }
        CommittedArray nearestArray(GroupState target) {
            CommittedArray best = null;
            double bestGap = Double.POSITIVE_INFINITY;
            for (CommittedArray array : committedArrays.values()) {
                Nearest nearest = nearest(array.bodyBounds(), target.bodyEnvelopes(), target.group().groupId());
                if (nearest != null && nearest.gapBlocks() < bestGap) {
                    best = array;
                    bestGap = nearest.gapBlocks();
                }
            }
            return best;
        }
        BlockBounds extent() { return extent; }
        void blockRef(String ref) { blockedRefs.add(ref); }
        void claimPatch(String ref) { if (ref != null && !ref.isBlank()) claimedPatchRefs.add(ref); }
        void stop(String reason) { stopReason = reason; }
        void recordTerrainPlacementFailure(String structureRef, JsonObject reasonCounts) {
            terrainPlacementFailures.add(new TerrainPlacementFailure(structureRef, reasonCounts.deepCopy()));
        }
        boolean hasTerrainPlacementFailures() { return !terrainPlacementFailures.isEmpty(); }
        List<RequiredStructureGap> missingRequiredStructures() {
            return missingStructureCounts(group.requiredStructureRefs(), requiredStructureCounts).entrySet()
                    .stream().map(entry -> new RequiredStructureGap(entry.getKey(), entry.getValue())).toList();
        }
        List<RequiredStructureGap> missingRequiredContent() {
            return missingStructureCounts(group.requiredStructureRefs(), structureCounts).entrySet()
                    .stream().map(entry -> new RequiredStructureGap(entry.getKey(), entry.getValue())).toList();
        }
        void freezeCoreExtent() {
            coreExtent = extent;
            initialFormationEnvelopes = List.copyOf(envelopes);
            initialLandscapeFormationSpans = List.copyOf(landscapeFormationSpans);
            initialLandscapeAreaBlocks = landscapeAreaBlocks;
            initialFormationAreaBlocks = internalOwnedAreaBlocks();
        }
        void setLandscapeFormationClaims(List<BlockBounds> spans, int areaBlocks) {
            landscapeFormationSpans = List.copyOf(spans);
            landscapeAreaBlocks = Math.max(0, areaBlocks);
        }
        void ensureLayoutFrame(CityBlueprintGroupLayoutPlanner.Frame proposed) {
            if (layoutFrame == null) layoutFrame = proposed;
        }
        void commit(String structureRef, JsonObject boundsJson, boolean required,
                    PlacementPhase phase, ConnectivityFit connectivity, JsonObject anchor,
                    int claimedAreaBlocks) {
            anchorCount++;
            if (required) {
                requiredCount++;
                requiredStructureCounts.merge(structureRef, 1, Integer::sum);
            }
            structureCounts.put(structureRef, structureCounts.getOrDefault(structureRef, 0) + 1);
            BlockBounds next = bounds(boundsJson);
            JsonObject bodyJson = anchor.has("actualFootprint") && anchor.get("actualFootprint").isJsonObject()
                    ? anchor.getAsJsonObject("actualFootprint") : boundsJson;
            BlockBounds body = bounds(bodyJson);
            String arrayId = string(anchor, "arrayId");
            if (arrayId.isBlank()) arrayId = string(anchor, "anchorId");
            CommittedArray committedArray = committedArrays.get(arrayId);
            if (committedArray == null) {
                committedArrays.put(arrayId, new CommittedArray(arrayId, body, next));
            } else {
                committedArray.add(body, next);
            }
            if (layoutFrame != null && anchorCount == 1
                    && !"COURTYARD".equals(layoutAlgorithm)
                    // Their first member is offset from the layout center; recentering translates all remaining slots.
                    && !"COMPACT".equals(layoutAlgorithm)
                    && anchor.has("anchorBlock")
                    && anchor.get("anchorBlock").isJsonObject()) {
                JsonObject block = anchor.getAsJsonObject("anchorBlock");
                layoutFrame = layoutFrame.recenter(new BlockPoint(
                        intValue(block, "x", layoutFrame.center().x()),
                        intValue(block, "z", layoutFrame.center().z())));
                if ("LINEAR".equals(layoutAlgorithm)) {
                    layoutFrame = switch (firstRoadEntranceDirection(anchor)) {
                        case "NORTH", "SOUTH" -> layoutFrame.reorient(1.0, 0.0);
                        case "EAST", "WEST" -> layoutFrame.reorient(0.0, 1.0);
                        default -> layoutFrame;
                    };
                }
            }
            if (exactInternalGuides() && phase != PlacementPhase.CONNECTIVITY) {
                if ("LINEAR".equals(layoutAlgorithm) && anchor.has("blueprintLayout")
                        && anchor.get("blueprintLayout").isJsonObject()) {
                    int committedSlot = intValue(anchor.getAsJsonObject("blueprintLayout"),
                            "slotIndex", exactSlotCursor);
                    linearFrontierSlot = Math.max(linearFrontierSlot, committedSlot + 1);
                    exactSlotCursor = linearFrontierSlot;
                } else {
                    exactSlotCursor++;
                }
            }
            if ("LINEAR".equals(layoutAlgorithm) && phase != PlacementPhase.CONNECTIVITY) {
                updateStreetBand(anchor);
            }
            if ("GRID".equals(layoutAlgorithm) && phase != PlacementPhase.CONNECTIVITY
                    && anchor.has("blueprintLayout")
                    && anchor.get("blueprintLayout").isJsonObject()) {
                JsonObject layout = anchor.getAsJsonObject("blueprintLayout");
                if (layout.has("gridRow") && layout.has("gridColumn")) {
                    gridStreetReservations.add(new CityGridStreetReservation.Placement(
                            layout.get("gridRow").getAsInt(), layout.get("gridColumn").getAsInt(), next));
                }
            }
            if (anchor.has("blueprintLayout") && anchor.get("blueprintLayout").isJsonObject()
                    && booleanValue(anchor.getAsJsonObject("blueprintLayout"), "outwardGuided", false)) {
                outwardGuidedPlacementCount++;
            }
            if (phase == PlacementPhase.PERCENTAGE) percentageStructureCount++;
            envelopes.add(next);
            builtCollisionAreaBlocks += area(next);
            spatialDemandBlocks += claimedAreaBlocks;
            extent = union(extent, next);
            if (phase == PlacementPhase.CONNECTIVITY) {
                connectionStructureCount++;
                connectionSpatialDemandBlocks += claimedAreaBlocks;
                connectionExpansionBlocks = Math.max(connectionExpansionBlocks,
                        expansionBeyond(coreExtent, extent));
                extentExpandedForConnectivity |= width(extent) > maxExtentSpanBlocks()
                        || depth(extent) > maxExtentSpanBlocks()
                        || spatialDemandBlocks > targetAreaBlocks();
            }
        }
        JsonObject functionAreaJson() {
            JsonObject value = new JsonObject();
            value.addProperty("schema", "city_function_area");
            value.addProperty("groupId", group.groupId());
            value.addProperty("status", envelopes.isEmpty() && landscapeFormationSpans.isEmpty()
                    ? "EMPTY_NO_COMMITTED_CLAIM" : "FORMED_FROM_COMMITTED_CLAIMS");
            value.addProperty("source", "COMMITTED_STRUCTURE_AND_LANDSCAPE_CLAIMS");
            value.addProperty("initialAreaBlocks", Math.max(0, initialFormationAreaBlocks));
            value.addProperty("actualAreaBlocks", Math.max(0, internalOwnedAreaBlocks()));
            value.addProperty("initialLandscapeAreaBlocks", initialLandscapeAreaBlocks);
            value.addProperty("actualLandscapeAreaBlocks", landscapeAreaBlocks);
            value.addProperty("initialStructureCount", initialFormationEnvelopes.size());
            value.addProperty("actualStructureCount", internalStructureCount());
            value.add("initialFormationSpans", boundsSpans(combinedBounds(
                    initialFormationEnvelopes, initialLandscapeFormationSpans)));
            value.add("formationSpans", boundsSpans(combinedBounds(envelopes, landscapeFormationSpans)));
            value.add("initialLandscapeFormationSpans", boundsSpans(initialLandscapeFormationSpans));
            value.add("landscapeFormationSpans", boundsSpans(landscapeFormationSpans));
            return value;
        }
        private static List<BlockBounds> combinedBounds(List<BlockBounds> first, List<BlockBounds> second) {
            List<BlockBounds> result = new ArrayList<>(first.size() + second.size());
            result.addAll(first);
            result.addAll(second);
            return List.copyOf(result);
        }
        private static JsonArray boundsSpans(List<BlockBounds> bounds) {
            JsonArray spans = new JsonArray();
            bounds.forEach(value -> spans.add(CityStructureCandidateEnvelope.boundsJson(value)));
            return spans;
        }
        BlockBounds functionAreaExtent() {
            BlockBounds result = extent;
            for (BlockBounds span : landscapeFormationSpans) result = union(result, span);
            return result;
        }
        JsonObject asJson() {
            JsonObject value = new JsonObject();
            value.addProperty("groupId", group.groupId());
            value.addProperty("requestedExtentClass", group.extentClass().name());
            value.addProperty("densityClass", group.densityClass().name());
            value.addProperty("terrainPolicy", group.terrainPolicy().name());
            value.addProperty("preferredPatchZone", group.preferredPatchZone().name());
            value.add("functionArea", functionAreaJson());
            value.add("spatialDemand", spatialDemand.asJson());
            if (group.placementRelation() != null) {
                value.addProperty("placementRelation", group.placementRelation().kind().name());
            }
            if (compositionSlot != null) value.add("arrayCompositionSlot", compositionSlot.asJson());
            value.add("formationBounds", CityStructureCandidateEnvelope.boundsJson(formationBounds));
            value.addProperty("targetAreaBlocks", dynamicTargetAreaBlocks);
            value.addProperty("buildingTargetAreaBlocks", buildingTargetAreaBlocks());
            value.addProperty("landscapeTargetAreaBlocks", landscapeTargetAreaBlocks());
            value.addProperty("targetAreaShare", group.targetAreaShare());
            value.addProperty("maxExtentSpanBlocks", maxExtentSpanBlocks());
            value.addProperty("targetAreaFrozenAfterConnectivity", areaExpansionActive);
            value.addProperty("densityParameterization", "ALGORITHM_SPECIFIC");
            value.addProperty("layoutAlgorithm", layoutAlgorithm);
            value.addProperty("placementMode", layoutParameters.placementMode().name());
            value.add("layoutParameters", layoutParameters.asJson());
            JsonObject streetBand = streetBandPlan();
            if (streetBand != null) value.add("streetBandPlan", streetBand);
            value.addProperty("maxIntraGroupGapBlocks", layoutParameters.maximumEdgeGapBlocks());
            JsonArray separationExemptions = new JsonArray();
            groupSeparationExemptGroupIds.stream().sorted().forEach(separationExemptions::add);
            value.add("groupSeparationExemptGroupIds", separationExemptions);
            value.addProperty("outwardGuidedPlacementCount", outwardGuidedPlacementCount);
            value.addProperty("connectionStructureCount", connectionStructureCount);
            value.addProperty("connectionBatchCount", connectionBatchCount);
            value.addProperty("percentageStructureCount", percentageStructureCount);
            value.addProperty("percentageBatchCount", percentageBatchCount);
            value.add("resolvedConnectionPlan", connectionConfiguration.asJson());
            value.addProperty("connectionSpatialDemandBlocks", connectionSpatialDemandBlocks);
            value.addProperty("connectionExpansionBlocks", connectionExpansionBlocks);
            value.addProperty("extentExpandedForConnectivity", extentExpandedForConnectivity);
            value.addProperty("actualStructureCount", anchorCount);
            value.addProperty("requiredStructureCount", requiredCount);
            JsonObject requiredCounts = new JsonObject();
            requiredStructureCounts.forEach(requiredCounts::addProperty);
            value.add("requiredStructureCounts", requiredCounts);
            JsonArray missingRequired = new JsonArray();
            missingRequiredStructures().forEach(gap -> missingRequired.add(gap.asJson()));
            value.add("missingRequiredStructures", missingRequired);
            value.addProperty("allRequiredStructuresCommitted", missingRequired.isEmpty());
            JsonArray missingContent = new JsonArray();
            missingRequiredContent().forEach(gap -> missingContent.add(gap.asJson()));
            value.add("missingRequiredContent", missingContent);
            value.addProperty("allRequiredContentPresent", missingContent.isEmpty());
            value.addProperty("derivedMinimumStructureCount", minimumStructureCount);
            value.addProperty("internalStructureCount", internalStructureCount());
            value.addProperty("minimumStructureCountReached",
                    internalStructureCount() >= minimumStructureCount);
            value.addProperty("builtCollisionAreaBlocks", builtCollisionAreaBlocks);
            value.addProperty("actualSpatialDemandBlocks", spatialDemandBlocks);
            value.addProperty("internalSpatialDemandBlocks", internalSpatialDemandBlocks());
            value.addProperty("landscapeAreaBlocks", landscapeAreaBlocks);
            value.addProperty("actualOwnedAreaBlocks", ownedAreaBlocks());
            value.addProperty("internalOwnedAreaBlocks", internalOwnedAreaBlocks());
            value.addProperty("areaGapBlocks", Math.max(0, dynamicTargetAreaBlocks - ownedAreaBlocks()));
            value.addProperty("estimatedCoverageRatio", spatialDemandBlocks == 0 ? 0.0
                    : builtCollisionAreaBlocks / (double) spatialDemandBlocks);
            value.addProperty("stopReason", stopReason);
            JsonArray terrainFailures = new JsonArray();
            terrainPlacementFailures.forEach(failure -> terrainFailures.add(failure.asJson(group)));
            value.add("terrainPlacementFailures", terrainFailures);
            JsonArray preferred = new JsonArray();
            group.preferredPatchRefs().forEach(preferred::add);
            value.add("preferredPatchRefs", preferred);
            JsonArray claimed = new JsonArray();
            claimedPatchRefs.forEach(claimed::add);
            value.add("claimedPatchRefs", claimed);
            JsonObject counts = new JsonObject();
            structureCounts.forEach(counts::addProperty);
            value.add("structureCounts", counts);
            return value;
        }

        private int maxExtentSpanBlocks() {
            return compositionSlot == null
                    ? Math.max(planningBounds.widthBlocks(), planningBounds.heightBlocks())
                    : Math.max(formationBounds.widthBlocks(), formationBounds.heightBlocks());
        }

        private void updateStreetBand(JsonObject anchor) {
            if (layoutFrame == null || !anchor.has("anchorBlock")
                    || !anchor.get("anchorBlock").isJsonObject()) return;
            BlockPoint anchorPoint = point(anchor.getAsJsonObject("anchorBlock"));
            if (streetBandStart == null) {
                JsonObject envelopeJson = anchor.has("collisionEnvelope")
                        && anchor.get("collisionEnvelope").isJsonObject()
                        ? anchor.getAsJsonObject("collisionEnvelope") : anchor;
                BlockBounds envelope = bounds(envelopeJson);
                BlockPoint frameCenter = layoutFrame.center();
                if (Math.abs(layoutFrame.axisX()) >= Math.abs(layoutFrame.axisZ())) {
                    streetBandStart = layoutFrame.axisX() >= 0.0
                            ? new BlockPoint(envelope.maxX() + 1, frameCenter.z())
                            : new BlockPoint(envelope.minX() - 1, frameCenter.z());
                } else {
                    streetBandStart = layoutFrame.axisZ() >= 0.0
                            ? new BlockPoint(frameCenter.x(), envelope.maxZ() + 1)
                            : new BlockPoint(frameCenter.x(), envelope.minZ() - 1);
                }
                streetBandEnd = streetBandStart;
                layoutFrame = layoutFrame.recenter(streetBandStart);
            }
            double dx = anchorPoint.x() - streetBandStart.x();
            double dz = anchorPoint.z() - streetBandStart.z();
            int projection = Math.max(0, (int) Math.round(
                    dx * layoutFrame.axisX() + dz * layoutFrame.axisZ()));
            streetBandMaxProjection = Math.max(streetBandMaxProjection,
                    projection + Math.max(1, layoutParameters.targetEdgeGapBlocks() / 2));
            streetBandEnd = new BlockPoint(
                    streetBandStart.x() + (int) Math.round(layoutFrame.axisX() * streetBandMaxProjection),
                    streetBandStart.z() + (int) Math.round(layoutFrame.axisZ() * streetBandMaxProjection));
        }
        JsonObject extentJson() {
            JsonObject value = asJson();
            if (extent != null) {
                JsonObject bounds = new JsonObject();
                bounds.addProperty("minX", extent.minX());
                bounds.addProperty("minZ", extent.minZ());
                bounds.addProperty("maxX", extent.maxX());
                bounds.addProperty("maxZ", extent.maxZ());
                value.add("collisionExtent", bounds);
            }
            BlockBounds functionAreaExtent = functionAreaExtent();
            if (functionAreaExtent != null) {
                value.add("functionAreaEnvelope", CityStructureCandidateEnvelope.boundsJson(functionAreaExtent));
                value.addProperty("functionAreaEnvelopePolicy", "COMMITTED_CLAIM_EXTENT");
            }
            return value;
        }
    }

    private record TerrainPlacementFailure(String structureRef, JsonObject reasonCounts) {
        private JsonObject asJson(CityBlueprint.Group group) {
            JsonObject value = new JsonObject();
            value.addProperty("structureRef", structureRef);
            value.addProperty("reasonCode", "CITY_BLUEPRINT_SELECTED_PATCH_TERRAIN_UNFIT");
            JsonArray patches = new JsonArray();
            group.preferredPatchRefs().forEach(patches::add);
            value.add("selectedPatchRefs", patches);
            value.add("terrainFailureReasonCounts", reasonCounts.deepCopy());
            return value;
        }
    }

    static Map<String, Integer> missingStructureCounts(List<String> requiredRefs, Map<String, Integer> committed) {
        Map<String, Integer> missing = new LinkedHashMap<>();
        requiredRefs.forEach(ref -> missing.merge(ref, 1, Integer::sum));
        missing.replaceAll((ref, count) -> count - committed.getOrDefault(ref, 0));
        missing.values().removeIf(count -> count <= 0);
        return missing;
    }

    private record RequiredStructureGap(String structureRef, int missingCount) {
        private JsonObject asJson() {
            JsonObject value = new JsonObject();
            value.addProperty("structureRef", structureRef);
            value.addProperty("missingCount", missingCount);
            value.addProperty("reasonCode", "CITY_BLUEPRINT_REQUIRED_STRUCTURE_MISSING");
            return value;
        }
    }

    private record CatalogIndex(Map<String, List<TemplateCandidate>> structures,
                                Map<String, List<String>> pools,
                                Map<String, String> algorithms,
                                Map<String, Boolean> centerAxisStreets,
                                Set<String> compositions,
                                Set<String> primaryStructures,
                                Map<String, GreenCapability> greenCapabilities,
                                Map<String, Integer> foundationMargins) {
        static CatalogIndex parse(JsonObject root, JsonObject semanticCatalog) {
            Map<String, List<TemplateCandidate>> structures = new LinkedHashMap<>();
            Map<String, GreenCapability> greenCapabilities = new LinkedHashMap<>();
            for (JsonElement element : array(root, "structureRefs")) {
                JsonObject item = element.getAsJsonObject();
                List<TemplateCandidate> templates = new ArrayList<>();
                for (JsonElement candidate : array(item, "templateCandidates")) {
                    JsonObject value = candidate.getAsJsonObject();
                    templates.add(new TemplateCandidate(string(value, "templateId"),
                            string(value, "variantId")));
                }
                structures.put(string(item, "structureRef"), List.copyOf(templates));
                if (item.has("greenParcel") && item.get("greenParcel").isJsonObject()) {
                    JsonObject green = item.getAsJsonObject("greenParcel");
                    greenCapabilities.put(string(item, "structureRef"), new GreenCapability(
                            CityBlueprintReferenceCatalog.GreenParcelPattern.valueOf(string(green, "pattern")),
                            CityBlueprintReferenceCatalog.GreenParcelDensity.valueOf(string(green, "density"))));
                }
            }
            Map<String, List<String>> pools = new LinkedHashMap<>();
            for (JsonElement element : array(root, "fillPools")) {
                JsonObject item = element.getAsJsonObject();
                List<String> refs = new ArrayList<>();
                for (JsonElement ref : array(item, "structureRefs")) refs.add(ref.getAsString());
                pools.put(string(item, "poolRef"), List.copyOf(refs));
            }
            Map<String, String> algorithms = new LinkedHashMap<>();
            Map<String, Boolean> centerAxisStreets = new LinkedHashMap<>();
            for (JsonElement element : array(root, "algorithmProfiles")) {
                JsonObject item = element.getAsJsonObject();
                String ref = string(item, "algorithmProfileRef");
                algorithms.put(ref, string(item, "algorithm"));
                centerAxisStreets.put(ref, booleanValue(item, "centerAxisStreetEnabled", false));
            }
            Set<String> compositions = new LinkedHashSet<>();
            for (JsonElement element : array(root, "compositionProfiles")) {
                JsonObject item = element.getAsJsonObject();
                compositions.add(string(item, "compositionProfileRef"));
            }
            Set<String> primaryStructures = new LinkedHashSet<>();
            for (JsonElement element : array(semanticCatalog, "semanticProfiles")) {
                JsonObject profile = element.getAsJsonObject();
                boolean primary = false;
                for (JsonElement term : array(profile, "planningRoleTerms")) {
                    String value = term.getAsString().toLowerCase(java.util.Locale.ROOT);
                    primary |= value.equals("planning_role.anchor") || value.equals("planning_role.key");
                }
                if (primary) primaryStructures.add(string(profile, "semanticProfileId"));
            }
            Map<String, Integer> foundationMargins = new LinkedHashMap<>();
            for (JsonElement element : array(root, "foundationProfiles")) {
                JsonObject item = element.getAsJsonObject();
                foundationMargins.put(string(item, "foundationProfileRef"),
                        intValue(item, "structureMarginBlocks", 0));
            }
            return new CatalogIndex(Map.copyOf(structures), Map.copyOf(pools), Map.copyOf(algorithms),
                    Map.copyOf(centerAxisStreets), Set.copyOf(compositions),
                    Set.copyOf(primaryStructures), Map.copyOf(greenCapabilities),
                    Map.copyOf(foundationMargins));
        }
        List<TemplateCandidate> templates(String ref) {
            List<TemplateCandidate> value = structures.get(ref);
            if (value == null || value.isEmpty()) throw fail("CITY_BLUEPRINT_STRUCTURE_REF_UNKNOWN", ref);
            return value;
        }
        List<String> pool(String ref) {
            List<String> value = pools.get(ref);
            if (value == null) throw fail("CITY_BLUEPRINT_FILL_POOL_UNKNOWN", ref);
            return value;
        }
        String algorithm(String ref) {
            String value = algorithms.get(ref);
            if (value == null) throw fail("CITY_BLUEPRINT_ALGORITHM_PROFILE_UNKNOWN", ref);
            return value;
        }
        boolean centerAxisStreetEnabled(String ref) {
            return centerAxisStreets.getOrDefault(ref, false);
        }
        void composition(String ref) {
            if (!compositions.contains(ref)) throw fail("CITY_BLUEPRINT_COMPOSITION_PROFILE_UNKNOWN", ref);
        }
        boolean primaryStructure(String ref) {
            return primaryStructures.contains(ref);
        }
        GreenCapability greenCapability(String ref) {
            return greenCapabilities.get(ref);
        }
        int foundationMargin(String ref) {
            Integer value = foundationMargins.get(ref);
            if (value == null) throw fail("CITY_BLUEPRINT_FOUNDATION_PROFILE_UNKNOWN", ref);
            return value;
        }
    }

    private record GreenCapability(CityBlueprintReferenceCatalog.GreenParcelPattern pattern,
                                   CityBlueprintReferenceCatalog.GreenParcelDensity density) {
    }
}
