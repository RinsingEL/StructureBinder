package com.rinsing.geomantia.systems.realm_planning;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.gis.GisClassifierConfig;
import com.rinsing.geomantia.systems.gis.application.sample.AtlasSampler;
import com.rinsing.geomantia.systems.gis.application.sample.SampledCell;
import com.rinsing.geomantia.systems.gis.application.refresh.RefreshResult;
import com.rinsing.geomantia.systems.gis.domain.cell.AtlasCell;
import com.rinsing.geomantia.systems.gis.domain.cell.CellStateFlag;
import com.rinsing.geomantia.systems.gis.domain.cell.LandformType;
import com.rinsing.geomantia.systems.gis.domain.landform.LandformPatch;
import com.rinsing.geomantia.systems.gis.domain.region.AtlasRegion;
import com.rinsing.geomantia.systems.gis.preview.AtlasJson;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

public final class RealmPlanningService {
    public static final String SCHEMA_VERSION = "realm_planning.v1.2";
    private static final int DEFAULT_SNAP_RADIUS_CELLS = 2;
    private static final int MIN_SEED_DISTANCE_CELLS = 2;
    public static final int DEFAULT_MICRO_SAMPLE_STRIDE_BLOCKS = 32;
    private static final int ACTION_BUDGET_STRICT_MIN_OWNED_CELLS = 24;
    private static final double ACTION_BUDGET_STRICT_MIN_OWNED_RATIO = 0.0008;
    private static final double QUOTA_URGENCY_WEIGHT = 6.0;
    private static final double STRICT_LARGEST_COMPONENT_RATIO = 0.90;
    private static final double STRICT_DETACHED_AREA_RATIO = 0.05;
    private static final double CONTESTED_COST_EPSILON = 2.5;
    private static final int HIGH_STEP_LOCAL_METRICS_MIN_STEP_BLOCKS = 64;
    private static final double STEEP_SLOPE_P90_THRESHOLD = 14.0;
    private static final double STEEP_FRACTION_THRESHOLD = 0.25;
    private static final double CLIFF_SLOPE_P90_THRESHOLD = 18.0;
    private static final double CLIFF_FRACTION_THRESHOLD = 0.55;
    private static final double COASTAL_CLIFF_FRACTION_THRESHOLD = 0.65;
    private static final double FLAT_SLOPE_P90_THRESHOLD = 12.0;
    private static final double FLAT_STEEP_FRACTION_THRESHOLD = 0.125;
    private static final double FLAT_RELIEF_THRESHOLD = 24.0;
    private static final double VERY_FLAT_SLOPE_P90_THRESHOLD = 8.0;
    private static final double VERY_FLAT_STEEP_FRACTION_THRESHOLD = 0.0625;
    private static final double VERY_FLAT_RELIEF_THRESHOLD = 16.0;
    private static final double RIDGE_RELIEF_THRESHOLD = 34.0;
    private static final double RIDGE_HIGH_RELIEF_THRESHOLD = 46.0;
    private static final double PLATEAU_LOCAL_HEIGHT_RANK_THRESHOLD = 0.50;
    private static final double PLATEAU_REGIONAL_HEIGHT_RANK_THRESHOLD = 0.80;
    private static final double PLATEAU_PROMINENCE_THRESHOLD = 18.0;
    private static final double PLATEAU_CORE_FLAT_SUPPORT_THRESHOLD = 0.65;
    private static final double LOWLAND_HEIGHT_RANK_THRESHOLD = 0.55;
    private static final double LOWLAND_MID_FLAT_HEIGHT_RANK_THRESHOLD = 0.70;
    private static final double UPLAND_HEIGHT_RANK_THRESHOLD = 0.72;
    private static final double UPLAND_SLOPE_P90_THRESHOLD = 14.0;
    private static final double UPLAND_RELIEF_THRESHOLD = 30.0;
    private static final double WATER_COMPONENT_MIN_FRACTION = 0.65;
    private static final int SEACOAST_COMPONENT_MIN_CELLS = 64;
    private static final int RIVERBANK_COMPONENT_MAX_CELLS = 32;
    private static final int RIVERBANK_COMPONENT_MAX_SPAN_CELLS = 3;
    private static final int NEAR_WATER_COMPONENT_MAX_DISTANCE_CELLS = 2;
    public static final int LOCAL_TERRAIN_SCALE_BLOCKS = 512;
    public static final int REGIONAL_TERRAIN_SCALE_BLOCKS = 2048;
    public static final int PLATEAU_CORE_SCALE_BLOCKS = 512;
    public static final int PLATEAU_OUTER_SCALE_BLOCKS = 1536;
    private static final double TERRAIN_RANK_DRIFT_THRESHOLD = 0.25;
    private static final double MIXED_CELL_SUPPORT_THRESHOLD = 0.65;
    private final Path debugRoot;
    private final Map<String, RealmRun> runs = new LinkedHashMap<>();

    public RealmPlanningService(Path debugRoot) {
        this.debugRoot = Objects.requireNonNull(debugRoot, "debugRoot");
    }

    public JsonObject status() {
        JsonObject response = baseResponse("status", latestRunId());
        response.addProperty("serviceReady", true);
        response.addProperty("debugRoot", debugRoot.toAbsolutePath().toString());
        JsonArray stages = new JsonArray();
        for (String stage : List.of("W", "T1", "T2", "T3", "T4", "acceptance")) {
            stages.add(stage);
        }
        response.add("availableStages", stages);
        response.addProperty("gisReady", true);
        response.add("knownRuns", knownRuns());
        return response;
    }

    public JsonObject runW(RefreshResult refreshResult, String requestedRunId, JsonElement worldTheme)
            throws IOException {
        Objects.requireNonNull(refreshResult, "refreshResult");
        String runId = normalizeRunId(requestedRunId, refreshResult.job().jobId());
        Path runDirectory = debugRoot.resolve(runId);
        WorldSurveyResult surveyResult = WorldSurveyResult.fromRefreshResult(runId, runDirectory, refreshResult);
        return runW(surveyResult, worldTheme);
    }

    public JsonObject runW(WorldSurveyResult surveyResult, JsonElement worldTheme)
            throws IOException {
        Objects.requireNonNull(surveyResult, "surveyResult");
        if (!surveyResult.sealed()) {
            throw new IllegalArgumentException("World survey must be sealed before W can be consumed.");
        }
        String runId = surveyResult.runId();
        Path runDirectory = surveyResult.runDirectory();
        Files.createDirectories(runDirectory);

        RealmRun run = new RealmRun(runId, runDirectory, surveyResult);
        run.worldTheme = worldTheme == null || worldTheme.isJsonNull() ? null : worldTheme.deepCopy();
        buildWorld(run);
        exportWorld(run);
        runs.put(runId, run);

        JsonObject response = baseResponse("W", runId);
        response.addProperty("status", "completed");
        response.add("artifacts", run.artifactsJson());
        response.add("summary", worldSummary(run));
        response.add("nextActions", arrayOf("realm_t1_prepare"));
        return response;
    }

    public JsonObject prepareT1(String runId, JsonArray realmProfiles, int realmCount, String targetContinentId,
            boolean allowAiDraftProfile) throws IOException {
        RealmRun run = requireRun(runId);
        if (run.worldCells.isEmpty()) {
            throw new IllegalArgumentException("W must be completed before T1.");
        }
        String continentId = resolveTargetContinent(run, targetContinentId);
        run.profiles.clear();
        run.candidatePackages.clear();
        if (realmProfiles != null && !realmProfiles.isEmpty()) {
            int index = 0;
            for (JsonElement element : realmProfiles) {
                if (!element.isJsonObject()) {
                    throw new IllegalArgumentException("realmProfiles must contain objects.");
                }
                run.profiles.add(RealmProfile.fromJson(element.getAsJsonObject(), continentId, index++));
            }
        } else {
            int count = realmCount > 0 ? realmCount : 3;
            run.profiles.addAll(defaultProfiles(continentId, count, allowAiDraftProfile));
        }
        validateProfiles(run, continentId);
        for (RealmProfile profile : run.profiles) {
            CandidatePackage pack = buildCandidatePackage(run, profile);
            run.candidatePackages.put(profile.realmId, pack);
            exportCandidateMap(run, pack);
        }
        exportT1(run);

        JsonObject response = baseResponse("T1", run.runId);
        response.addProperty("status", "completed");
        response.addProperty("selectionMode", "patch_explorer_primary");
        response.addProperty("candidateMapRole", "continent_scope_reference");
        response.add("artifacts", run.artifactsJson());
        response.add("realmProfiles", profilesJson(run.profiles));
        response.add("candidatePackages", candidatePackagesJson(run.candidatePackages.values()));
        response.add("nextActions", arrayOf("patch_explorer_open"));
        response.add("compatibilityActions", arrayOf("realm_t2_select_coordinate"));
        return response;
    }

    public JsonObject selectT2(String runId, String realmId, int gridX, int gridZ, JsonArray alternates,
            String reason, String selectedBy, boolean allowSnap) throws IOException {
        RealmRun run = requireRun(runId);
        RealmProfile profile = run.profile(realmId);
        CandidatePackage pack = run.candidatePackages.get(profile.realmId);
        if (pack == null) {
            throw new IllegalArgumentException("T1 candidate package is missing for realmId: " + realmId);
        }
        SelectionAttempt attempt = validateSelection(run, profile, pack, new GridPoint(gridX, gridZ), allowSnap);
        if (!attempt.accepted && alternates != null) {
            for (JsonElement element : alternates) {
                GridPoint alternate = gridPointFromJson(element);
                SelectionAttempt altAttempt = validateSelection(run, profile, pack, alternate, allowSnap);
                if (altAttempt.accepted) {
                    attempt = altAttempt.withOriginal(new GridPoint(gridX, gridZ));
                    break;
                }
            }
        }

        RealmSelection selection = RealmSelection.from(profile.realmId, pack.packageId, selectedBy, new GridPoint(gridX, gridZ),
                attempt, reason);
        run.selections.put(profile.realmId, selection);
        if (!attempt.accepted) {
            exportT2(run);
            JsonObject response = baseResponse("T2", run.runId);
            response.addProperty("status", "failed");
            response.add("errors", stringArray(attempt.errors));
            response.add("selection", selection.asJson());
            return response;
        }

        RealmSeed seed = RealmSeed.from(profile, selection, attempt.cell);
        CapitalCityIntent capitalIntent = CapitalCityIntent.from(profile, selection);
        run.seeds.put(profile.realmId, seed);
        run.capitalIntents.put(profile.realmId, capitalIntent);
        exportT2(run);

        JsonObject response = baseResponse("T2", run.runId);
        response.addProperty("status", "completed");
        response.add("artifacts", run.artifactsJson());
        response.add("selection", selection.asJson());
        response.add("realmSeed", seed.asJson());
        response.add("capitalCityIntent", capitalIntent.asJson());
        response.add("nextActions", arrayOf(run.seeds.size() == run.profiles.size()
                ? "realm_t3_expand" : "realm_t2_select_coordinate"));
        return response;
    }

    public JsonObject expandT3(String runId, String normalizationGroup, boolean allowUnclaimedLand) throws IOException {
        return expandT3(runId, normalizationGroup, allowUnclaimedLand, "strict", "");
    }

    public JsonObject expandT3(String runId, String normalizationGroup, boolean allowUnclaimedLand,
            String qualityMode) throws IOException {
        return expandT3(runId, normalizationGroup, allowUnclaimedLand, qualityMode, "");
    }

    public JsonObject expandT3(String runId, String normalizationGroup, boolean allowUnclaimedLand,
            String qualityMode, String expansionModel) throws IOException {
        RealmRun run = requireRun(runId);
        if (run.profiles.isEmpty() || run.seeds.size() != run.profiles.size()) {
            throw new IllegalArgumentException("All realms must complete T2 before T3.");
        }
        run.qualityMode = normalizeQualityMode(qualityMode);
        run.expansionModel = normalizeExpansionModel(expansionModel, run.qualityMode);
        String group = normalizationGroup == null || normalizationGroup.isBlank()
                ? run.profiles.get(0).scalePlan.normalizationGroup : normalizationGroup.trim();
        resetT3DerivedState(run);
        run.territory = buildTerritory(run, group, allowUnclaimedLand);
        exportTerritory(run);

        JsonObject response = baseResponse("T3", run.runId);
        response.addProperty("status", "completed");
        response.add("artifacts", run.artifactsJson());
        response.add("territoryMap", run.territory.asJson());
        response.add("nextActions", arrayOf("realm_t4_patch_planning_create"));
        response.add("compatibilityActions", arrayOf("realm_t4_build_registry"));
        return response;
    }

    public JsonObject buildT4(String runId) throws IOException {
        RealmRun run = requireRun(runId);
        if (run.territory == null) {
            throw new IllegalArgumentException("T3 must be completed before T4.");
        }
        run.registry = buildRegistry(run);
        exportRegistry(run);

        JsonObject response = baseResponse("T4", run.runId);
        response.addProperty("status", "completed");
        response.addProperty("selectionMode", "rule_fixture");
        response.addProperty("formalAiPath", false);
        response.add("artifacts", run.artifactsJson());
        response.add("citySeedRegistry", run.registry.asJson());
        response.add("nextActions", arrayOf("review_acceptance_report"));
        return response;
    }

    /** Rebuilds every T4-derived artifact after an external planner replaces the registry. */
    public JsonObject synchronizeT4RegistryArtifacts(String runId, JsonObject registryJson) throws IOException {
        RealmRun run = requireRun(runId);
        if (run.territory == null) {
            throw new IllegalArgumentException("T3 must be completed before synchronizing T4.");
        }
        String territoryMapId = stringValue(registryJson, "territoryMapId", "");
        if (!run.territory.territoryMapId.equals(territoryMapId)) {
            throw new IllegalArgumentException("T4 registry territoryMapId does not match the current T3 checkpoint.");
        }

        Path registryPath = run.runDirectory.resolve("city_seed_registry.json");
        JsonArray seedArray = registryJson.has("citySeeds") && registryJson.get("citySeeds").isJsonArray()
                ? registryJson.getAsJsonArray("citySeeds") : new JsonArray();
        List<CitySeed> seeds = new ArrayList<>();
        for (JsonElement element : seedArray) {
            seeds.add(citySeedFromJson(requireCheckpointObject(element, registryPath)));
        }
        run.registry = new CitySeedRegistry(stringValue(registryJson, "registryId", "registry_" + run.runId),
                run.runId, territoryMapId, seeds);

        // Keep Patch Explorer provenance in the canonical registry while rebuilding derived data from its model.
        exportRegistry(run, registryJson.deepCopy());
        invalidateAcceptanceReport(run);

        JsonObject response = baseResponse("T4", run.runId);
        response.addProperty("status", "completed");
        response.add("artifacts", run.artifactsJson());
        response.add("citySeedRegistry", registryJson.deepCopy());
        response.add("nextActions", arrayOf("rerun_acceptance"));
        return response;
    }

    private void resetT3DerivedState(RealmRun run) {
        run.expansionBudgets.clear();
        run.terrainCostProfiles.clear();
        run.stopReasons.clear();
        run.terrainCostBreakdowns.clear();
        run.realmClaimCostSums.clear();
        run.realmMaxClaimCosts.clear();
        run.realmClaimCounts.clear();
        run.territoryCellStatuses.clear();
        run.territoryClaimCosts.clear();
        run.territory = null;
        run.registry = null;
        run.scoreManifest = null;
        for (String artifact : List.of("citySeedRegistry", "citySeedPreview", "t4Report",
                "realmCityCandidatePackages", "scoreManifest")) {
            run.artifacts.remove(artifact);
        }
    }

    public JsonObject runAcceptance(RefreshResult refreshResult, String requestedRunId, int realmCount,
            JsonArray realmProfiles, boolean autoSelectCoordinates) throws IOException {
        String runId = normalizeRunId(requestedRunId, refreshResult.job().jobId());
        WorldSurveyResult surveyResult = WorldSurveyResult.fromRefreshResult(runId, debugRoot.resolve(runId), refreshResult);
        return runAcceptance(surveyResult, realmCount, realmProfiles, autoSelectCoordinates);
    }

    public JsonObject runAcceptance(WorldSurveyResult surveyResult, int realmCount,
            JsonArray realmProfiles, boolean autoSelectCoordinates) throws IOException {
        return runAcceptance(surveyResult, realmCount, realmProfiles, autoSelectCoordinates, "strict");
    }

    public JsonObject runAcceptance(WorldSurveyResult surveyResult, int realmCount,
            JsonArray realmProfiles, boolean autoSelectCoordinates, String qualityMode) throws IOException {
        return runAcceptance(surveyResult, realmCount, realmProfiles, autoSelectCoordinates, qualityMode, "");
    }

    public JsonObject runAcceptance(WorldSurveyResult surveyResult, int realmCount,
            JsonArray realmProfiles, boolean autoSelectCoordinates, String qualityMode, String expansionModel) throws IOException {
        long startedAt = System.nanoTime();
        JsonObject w = runW(surveyResult, null);
        String runId = w.get("runId").getAsString();
        RealmRun run = requireRun(runId);
        run.qualityMode = normalizeQualityMode(qualityMode);
        run.expansionModel = normalizeExpansionModel(expansionModel, run.qualityMode);
        String continent = resolveTargetContinent(run, "");
        prepareT1(runId, realmProfiles, realmCount <= 0 ? 3 : realmCount, continent, true);
        if (autoSelectCoordinates) {
            for (RealmProfile profile : run.profiles) {
                CandidatePackage pack = run.candidatePackages.get(profile.realmId);
                GridPoint point = autoAcceptancePoint(run, profile, pack);
                selectT2(runId, profile.realmId, point.x, point.z, null,
                        "auto acceptance coordinate from candidate package", "debug", true);
            }
        }
        expandT3(runId, continent, false, run.qualityMode, run.expansionModel);
        buildT4(runId);
        long durationMs = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
        JsonObject report = acceptanceReport(run, durationMs);
        writeJson(run.runDirectory.resolve("acceptance_report.json"), report);

        JsonObject response = baseResponse("acceptance", runId);
        response.addProperty("status", "completed");
        response.addProperty("passed", report.get("passed").getAsBoolean());
        response.add("acceptanceReport", report);
        response.add("artifacts", run.artifactsJson());
        return response;
    }

    public JsonObject runTagAudit(String runId, AtlasSampler sampler, int requestedSampleCount,
            int requestedRadiusBlocks, int requestedStrideBlocks, int requestedSlopeRadiusBlocks) throws IOException {
        return runTagAudit(runId, sampler, requestedSampleCount, requestedRadiusBlocks, requestedStrideBlocks,
                requestedSlopeRadiusBlocks, "");
    }

    public JsonObject runTagAudit(String runId, AtlasSampler sampler, int requestedSampleCount,
            int requestedRadiusBlocks, int requestedStrideBlocks, int requestedSlopeRadiusBlocks,
            String requestedSampleSeed) throws IOException {
        RealmRun run = ensureRunForTagAudit(runId);
        Objects.requireNonNull(sampler, "sampler");
        int sampleCount = requestedSampleCount > 0 ? requestedSampleCount : 120;
        int radiusBlocks = requestedRadiusBlocks > 0 ? requestedRadiusBlocks : 32;
        int strideBlocks = requestedStrideBlocks > 0 ? requestedStrideBlocks : 4;
        int slopeRadiusBlocks = requestedSlopeRadiusBlocks > 0 ? requestedSlopeRadiusBlocks : 4;
        String sampleSeed = requestedSampleSeed == null || requestedSampleSeed.isBlank()
                ? run.runId : requestedSampleSeed.trim();
        List<TagAuditSample> samples = buildTagAuditSamples(run, sampler, sampleCount, radiusBlocks,
                strideBlocks, slopeRadiusBlocks, sampleSeed);
        JsonArray sampleJson = new JsonArray();
        for (TagAuditSample sample : samples) {
            sampleJson.add(sample.asJson());
        }
        JsonObject report = tagAuditReport(run, samples, sampleCount, radiusBlocks, strideBlocks, slopeRadiusBlocks,
                sampleSeed);
        writeJson(run.runDirectory.resolve("tag_audit_samples.json"), sampleJson);
        writeJson(run.runDirectory.resolve("tag_audit_report.json"), report);
        run.artifacts.put("tagAuditSamples", "tag_audit_samples.json");
        run.artifacts.put("tagAuditReport", "tag_audit_report.json");

        JsonObject response = baseResponse("tag_audit", run.runId);
        response.addProperty("status", "completed");
        response.add("artifacts", run.artifactsJson());
        response.add("tagAuditReport", report);
        return response;
    }

    public GridPoint suggestedPoint(String runId, String realmId) throws IOException {
        RealmRun run = requireRun(runId);
        CandidatePackage pack = run.candidatePackages.get(realmId);
        if (pack == null) {
            throw new IllegalArgumentException("No candidate package for realmId: " + realmId);
        }
        return pack.suggestedPoint;
    }

    private void buildWorld(RealmRun run) {
        Map<String, WorldCell> cells = new LinkedHashMap<>();
        for (AtlasRegion region : run.surveyResult.regions()) {
            for (AtlasCell cell : region.cells()) {
                if (!cell.hasFlag(CellStateFlag.LANDFORM_READY)) {
                    continue;
                }
                if (!run.surveyResult.containsBlock(cell.blockMinX(), cell.blockMinZ())) {
                    continue;
                }
                String landWater = landWater(cell);
                WorldFeatureCell feature = run.surveyResult.featureCells().get(key(cell.globalCellX(), cell.globalCellZ()));
                WorldCell worldCell = new WorldCell(cell.globalCellX(), cell.globalCellZ(), cell.blockMinX(),
                        cell.blockMinZ(), "", cell.patchId(), landWater, cell.landformType().contractName(),
                        cell.elevation(), cell.slope(), finiteWaterDistance(cell.waterDistance()), flagsFor(cell), feature,
                        run.surveyResult.cellStepBlocks());
                cells.put(key(worldCell.gridX, worldCell.gridZ), worldCell);
            }
        }
        assignContinents(cells);
        assignWaterEdgeTypes(cells);
        run.worldCells.clear();
        run.worldCells.addAll(cells.values());
        assignRelativeHeightRanks(run.worldCells);
        assignMultiScaleTerrainMetrics(cells, run.surveyResult.cellStepBlocks());
        run.worldCellsByKey.clear();
        for (WorldCell cell : run.worldCells) {
            run.worldCellsByKey.put(key(cell.gridX, cell.gridZ), cell);
        }
        run.patchSummaries = buildPatchSummaries(run, run.surveyResult.patches());
        run.continentSummaries = buildContinentSummaries(run);
        if (run.continentSummaries.isEmpty()) {
            throw new IllegalArgumentException("W refresh did not produce assignable land continents.");
        }
    }

    private void assignWaterEdgeTypes(Map<String, WorldCell> cells) {
        WaterBounds bounds = waterBounds(cells.values());
        Map<String, WaterComponent> components = buildWaterComponents(cells, bounds);
        Map<String, WaterComponent> componentsByCell = new HashMap<>();
        for (WaterComponent component : components.values()) {
            for (String cellKey : component.cellKeys) {
                componentsByCell.put(cellKey, component);
            }
        }
        for (WorldCell cell : cells.values()) {
            if (!cell.assignableLand()) {
                continue;
            }
            if (!"shore".equals(cell.landWater) && !coastalMix(cell.waterFrac())) {
                continue;
            }
            WaterComponent component = nearestWaterComponent(cells, componentsByCell, cell);
            if (component == null) {
                cell.waterEdgeType = localWaterEdgeType(cell);
            } else {
                cell.waterEdgeType = component.edgeType();
                cell.waterComponentId = component.id;
                cell.waterComponentAreaCells = component.size();
                cell.waterBoundaryConfidence = component.waterBoundaryConfidence();
                cell.waterComponentType = component.componentType();
            }
        }
    }

    private Map<String, WaterComponent> buildWaterComponents(Map<String, WorldCell> cells, WaterBounds bounds) {
        Map<String, WaterComponent> components = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        int index = 0;
        List<WorldCell> ordered = new ArrayList<>(cells.values());
        ordered.sort(Comparator.comparingInt((WorldCell c) -> c.gridZ).thenComparingInt(c -> c.gridX));
        for (WorldCell start : ordered) {
            String startKey = key(start.gridX, start.gridZ);
            if (!waterComponentCell(start) || visited.contains(startKey)) {
                continue;
            }
            WaterComponent component = new WaterComponent("water_" + index++, bounds);
            ArrayDeque<WorldCell> queue = new ArrayDeque<>();
            queue.add(start);
            visited.add(startKey);
            while (!queue.isEmpty()) {
                WorldCell current = queue.removeFirst();
                component.add(current);
                for (int[] offset : DIRECTIONS) {
                    WorldCell next = cells.get(key(current.gridX + offset[0], current.gridZ + offset[1]));
                    if (next == null || !waterComponentCell(next)) {
                        continue;
                    }
                    String nextKey = key(next.gridX, next.gridZ);
                    if (visited.add(nextKey)) {
                        queue.addLast(next);
                    }
                }
            }
            components.put(component.id, component);
        }
        return components;
    }

    private static WaterBounds waterBounds(Iterable<WorldCell> cells) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        boolean any = false;
        for (WorldCell cell : cells) {
            if (!waterComponentCell(cell)) {
                continue;
            }
            any = true;
            minX = Math.min(minX, cell.gridX);
            maxX = Math.max(maxX, cell.gridX);
            minZ = Math.min(minZ, cell.gridZ);
            maxZ = Math.max(maxZ, cell.gridZ);
        }
        if (!any) {
            return new WaterBounds(0, 0, 0, 0);
        }
        return new WaterBounds(minX, maxX, minZ, maxZ);
    }

    private static boolean waterComponentCell(WorldCell cell) {
        return "water".equals(cell.landWater) || cell.waterFrac() >= WATER_COMPONENT_MIN_FRACTION;
    }

    private static WaterComponent nearestWaterComponent(Map<String, WorldCell> cells,
            Map<String, WaterComponent> componentsByCell, WorldCell origin) {
        WaterComponent best = null;
        int bestDistance = Integer.MAX_VALUE;
        ArrayDeque<GridDistance> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(new GridDistance(origin.gridX, origin.gridZ, 0));
        visited.add(key(origin.gridX, origin.gridZ));
        while (!queue.isEmpty()) {
            GridDistance current = queue.removeFirst();
            if (current.distance > NEAR_WATER_COMPONENT_MAX_DISTANCE_CELLS) {
                continue;
            }
            WorldCell cell = cells.get(key(current.x, current.z));
            if (cell != null && waterComponentCell(cell)) {
                WaterComponent component = componentsByCell.get(key(cell.gridX, cell.gridZ));
                if (component != null && current.distance < bestDistance) {
                    best = component;
                    bestDistance = current.distance;
                }
            }
            if (current.distance >= NEAR_WATER_COMPONENT_MAX_DISTANCE_CELLS) {
                continue;
            }
            for (int[] offset : DIRECTIONS) {
                int nx = current.x + offset[0];
                int nz = current.z + offset[1];
                String nextKey = key(nx, nz);
                if (visited.add(nextKey) && cells.containsKey(nextKey)) {
                    queue.addLast(new GridDistance(nx, nz, current.distance + 1));
                }
            }
        }
        return best;
    }

    private static String localWaterEdgeType(WorldCell cell) {
        double water = cell.waterFrac();
        if (water < 0.25 && cell.robustRelief() <= FLAT_RELIEF_THRESHOLD) {
            return "riverbank";
        }
        if (water >= 0.45 || "shore".equals(cell.landWater)) {
            return "seacoast";
        }
        return "lakeshore";
    }

    private void assignContinents(Map<String, WorldCell> cells) {
        Set<String> visited = new HashSet<>();
        int continentIndex = 0;
        List<WorldCell> ordered = new ArrayList<>(cells.values());
        ordered.sort(Comparator.comparingInt((WorldCell c) -> c.gridZ).thenComparingInt(c -> c.gridX));
        for (WorldCell start : ordered) {
            if (!start.assignableLand() || visited.contains(key(start.gridX, start.gridZ))) {
                continue;
            }
            String continentId = "continent_" + continentIndex++;
            ArrayDeque<WorldCell> queue = new ArrayDeque<>();
            queue.add(start);
            visited.add(key(start.gridX, start.gridZ));
            while (!queue.isEmpty()) {
                WorldCell current = queue.removeFirst();
                current.continentId = continentId;
                for (int[] offset : DIRECTIONS) {
                    WorldCell next = cells.get(key(current.gridX + offset[0], current.gridZ + offset[1]));
                    if (next != null && next.assignableLand() && visited.add(key(next.gridX, next.gridZ))) {
                        queue.addLast(next);
                    }
                }
            }
        }
    }

    private void assignRelativeHeightRanks(List<WorldCell> cells) {
        List<WorldCell> land = cells.stream()
                .filter(cell -> cell.assignableLand() && !"shore".equals(cell.landWater) && cell.waterFrac() < 0.65)
                .sorted(Comparator.comparingDouble(WorldCell::heightP50))
                .toList();
        if (land.isEmpty()) {
            return;
        }
        int maxIndex = Math.max(1, land.size() - 1);
        for (int i = 0; i < land.size(); i++) {
            land.get(i).relativeHeightRank = i / (double) maxIndex;
        }
    }

    private void assignMultiScaleTerrainMetrics(Map<String, WorldCell> cells, int cellStepBlocks) {
        int localRadiusCells = Math.max(1, Math.round(LOCAL_TERRAIN_SCALE_BLOCKS / (float) Math.max(1, cellStepBlocks)));
        int regionalRadiusCells = Math.max(localRadiusCells + 1,
                Math.round(REGIONAL_TERRAIN_SCALE_BLOCKS / (float) Math.max(1, cellStepBlocks)));
        int plateauCoreRadiusCells = Math.max(1,
                Math.round(PLATEAU_CORE_SCALE_BLOCKS / (float) Math.max(1, cellStepBlocks)));
        int plateauOuterRadiusCells = Math.max(plateauCoreRadiusCells + 1,
                Math.round(PLATEAU_OUTER_SCALE_BLOCKS / (float) Math.max(1, cellStepBlocks)));
        for (WorldCell cell : cells.values()) {
            if (!cell.assignableLand() || waterComponentCell(cell)) {
                cell.localHeightRank = cell.relativeHeightRank;
                cell.regionalHeightRank = cell.relativeHeightRank;
                cell.heightRankStability = 1.0;
                cell.landformConfidence = 0.35;
                continue;
            }
            TerrainWindowMetrics local = terrainWindowMetrics(cells, cell, localRadiusCells);
            TerrainWindowMetrics regional = terrainWindowMetrics(cells, cell, regionalRadiusCells);
            PlateauContextMetrics plateau = plateauContextMetrics(cells, cell, plateauCoreRadiusCells,
                    plateauOuterRadiusCells);
            cell.localScaleBlocks = localRadiusCells * cellStepBlocks;
            cell.regionalScaleBlocks = regionalRadiusCells * cellStepBlocks;
            cell.plateauCoreScaleBlocks = plateauCoreRadiusCells * cellStepBlocks;
            cell.plateauOuterScaleBlocks = plateauOuterRadiusCells * cellStepBlocks;
            cell.localHeightRank = local.heightRank;
            cell.regionalHeightRank = regional.heightRank;
            cell.tpiLocal = local.tpi;
            cell.tpiRegional = regional.tpi;
            cell.devLocal = local.dev;
            cell.devRegional = regional.dev;
            cell.roughnessLocal = local.roughness;
            cell.roughnessRegional = regional.roughness;
            cell.reliefLocalP90P10 = local.reliefP90P10;
            cell.reliefRegionalP90P10 = regional.reliefP90P10;
            cell.plateauCoreMeanHeight = plateau.coreMeanHeight;
            cell.plateauOuterMeanHeight = plateau.outerMeanHeight;
            cell.plateauProminence = plateau.prominence;
            cell.plateauCoreFlatSupport = plateau.coreFlatSupport;
            cell.heightRankStability = 1.0 - Math.min(1.0, Math.abs(cell.localHeightRank - cell.regionalHeightRank)
                    + Math.abs(cell.regionalHeightRank - cell.relativeHeightRank) * 0.5);
            cell.devMaxMagnitude = Math.max(Math.abs(cell.devLocal), Math.abs(cell.devRegional));
            cell.devMaxScaleBlocks = Math.abs(cell.devLocal) >= Math.abs(cell.devRegional)
                    ? cell.localScaleBlocks : cell.regionalScaleBlocks;
            cell.geomorphonClass = simplifiedGeomorphon(cells, cell, Math.max(1, localRadiusCells / 2));
            cell.landformConfidence = landformConfidence(cell);
        }
    }

    private static TerrainWindowMetrics terrainWindowMetrics(Map<String, WorldCell> cells, WorldCell origin,
            int radiusCells) {
        List<Double> heights = new ArrayList<>();
        double sum = 0.0;
        int lower = 0;
        int equal = 0;
        int comparable = 0;
        for (int z = origin.gridZ - radiusCells; z <= origin.gridZ + radiusCells; z++) {
            for (int x = origin.gridX - radiusCells; x <= origin.gridX + radiusCells; x++) {
                WorldCell cell = cells.get(key(x, z));
                if (cell == null || !cell.assignableLand() || waterComponentCell(cell)) {
                    continue;
                }
                double height = cell.heightP50();
                heights.add(height);
                sum += height;
                if (height < origin.heightP50()) {
                    lower++;
                } else if (Math.abs(height - origin.heightP50()) <= 0.0001) {
                    equal++;
                }
                comparable++;
            }
        }
        if (heights.isEmpty()) {
            return new TerrainWindowMetrics(origin.relativeHeightRank, 0.0, 0.0, 0.0, 0.0);
        }
        heights.sort(Double::compareTo);
        double mean = sum / heights.size();
        double variance = 0.0;
        for (double height : heights) {
            double diff = height - mean;
            variance += diff * diff;
        }
        double stdDev = Math.sqrt(variance / Math.max(1, heights.size()));
        double rank = comparable <= 1 ? 0.5 : (lower + equal * 0.5) / comparable;
        double tpi = origin.heightP50() - mean;
        double dev = stdDev <= 0.0001 ? 0.0 : tpi / stdDev;
        double p10 = percentile(heights, 0.10);
        double p90 = percentile(heights, 0.90);
        double roughness = stdDev + Math.max(0.0, p90 - p10) * 0.25;
        return new TerrainWindowMetrics(clamp(rank, 0.0, 1.0), tpi, dev, roughness, p90 - p10);
    }

    private static PlateauContextMetrics plateauContextMetrics(Map<String, WorldCell> cells, WorldCell origin,
            int coreRadiusCells, int outerRadiusCells) {
        double coreSum = 0.0;
        double outerSum = 0.0;
        int coreCount = 0;
        int outerCount = 0;
        int flatSupportCount = 0;
        for (int z = origin.gridZ - outerRadiusCells; z <= origin.gridZ + outerRadiusCells; z++) {
            for (int x = origin.gridX - outerRadiusCells; x <= origin.gridX + outerRadiusCells; x++) {
                WorldCell cell = cells.get(key(x, z));
                if (cell == null || !cell.assignableLand() || waterComponentCell(cell)) {
                    continue;
                }
                int distance = Math.max(Math.abs(x - origin.gridX), Math.abs(z - origin.gridZ));
                double height = cell.heightP50();
                if (distance <= coreRadiusCells) {
                    coreSum += height;
                    coreCount++;
                    if (cell.flatLocalSurface() && height >= origin.heightP50() - 8.0) {
                        flatSupportCount++;
                    }
                } else {
                    outerSum += height;
                    outerCount++;
                }
            }
        }
        double coreMean = coreCount == 0 ? origin.heightP50() : coreSum / coreCount;
        double outerMean = outerCount == 0 ? coreMean : outerSum / outerCount;
        double support = coreCount == 0 ? 0.0 : flatSupportCount / (double) coreCount;
        double prominence = outerCount == 0 ? 0.0 : coreMean - outerMean;
        return new PlateauContextMetrics(coreMean, outerMean, prominence, support, coreCount, outerCount);
    }

    private static String simplifiedGeomorphon(Map<String, WorldCell> cells, WorldCell cell, int radiusCells) {
        int highDirections = 0;
        int lowDirections = 0;
        int missing = 0;
        double threshold = Math.max(6.0, cell.robustRelief() * 0.25);
        for (int[] offset : EIGHT_DIRECTIONS) {
            WorldCell neighbor = cells.get(key(cell.gridX + offset[0] * radiusCells,
                    cell.gridZ + offset[1] * radiusCells));
            if (neighbor == null || !neighbor.assignableLand()) {
                missing++;
                continue;
            }
            double delta = neighbor.heightP50() - cell.heightP50();
            if (delta >= threshold) {
                highDirections++;
            } else if (delta <= -threshold) {
                lowDirections++;
            }
        }
        if (missing >= 5) {
            return "unknown";
        }
        if (highDirections == 0 && lowDirections == 0) {
            return "flat";
        }
        if (lowDirections >= 6) {
            return "peak";
        }
        if (highDirections >= 6) {
            return "pit";
        }
        if (lowDirections >= 4 && highDirections <= 2) {
            return "ridge";
        }
        if (highDirections >= 4 && lowDirections <= 2) {
            return "valley";
        }
        if (lowDirections > highDirections) {
            return "shoulder";
        }
        if (highDirections > lowDirections) {
            return "footslope";
        }
        return "slope";
    }

    private static double landformConfidence(WorldCell cell) {
        double confidence = 0.55;
        confidence += cell.heightRankStability * 0.20;
        if (cell.flatLocalSurface() && (cell.localHeightRank <= 0.55 || cell.localHeightRank >= 0.65)) {
            confidence += 0.10;
        }
        if (cell.localSteep() || cell.devMaxMagnitude >= 1.0) {
            confidence += 0.10;
        }
        if ("flat".equals(cell.geomorphonClass) && cell.flatLocalSurface()) {
            confidence += 0.05;
        }
        if (Math.abs(cell.localHeightRank - cell.regionalHeightRank) > TERRAIN_RANK_DRIFT_THRESHOLD) {
            confidence -= 0.20;
        }
        if (cell.waterEdgeType.equals("boundary_truncated")) {
            confidence -= 0.15;
        }
        return clamp(confidence, 0.0, 1.0);
    }

    private Map<String, PatchSummary> buildPatchSummaries(RealmRun run, List<LandformPatch> patches) {
        Map<String, PatchAccumulator> accumulators = new LinkedHashMap<>();
        for (WorldCell cell : run.worldCells) {
            if (cell.patchId == null || cell.patchId.isBlank()) {
                cell.patchId = "patch_" + cell.gridX + "_" + cell.gridZ;
            }
            accumulators.computeIfAbsent(cell.patchId, PatchAccumulator::new).add(cell);
        }
        for (LandformPatch patch : patches) {
            PatchAccumulator accumulator = accumulators.get(patch.patchId());
            if (accumulator != null) {
                accumulator.patchLandform = patch.landformType().contractName();
                accumulator.touchesWater = patch.touchesWater();
            }
        }
        Map<String, PatchSummary> summaries = new LinkedHashMap<>();
        for (PatchAccumulator accumulator : accumulators.values()) {
            PatchSummary summary = accumulator.toSummary(run.surveyResult.cellStepBlocks());
            summaries.put(summary.patchId, summary);
        }
        return summaries;
    }

    private Map<String, ContinentSummary> buildContinentSummaries(RealmRun run) {
        Map<String, ContinentAccumulator> accumulators = new LinkedHashMap<>();
        for (WorldCell cell : run.worldCells) {
            if (!cell.assignableLand()) {
                continue;
            }
            accumulators.computeIfAbsent(cell.continentId, ContinentAccumulator::new).add(cell);
        }
        Map<String, ContinentSummary> summaries = new LinkedHashMap<>();
        for (ContinentAccumulator accumulator : accumulators.values()) {
            ContinentSummary summary = accumulator.toSummary();
            summaries.put(summary.continentId, summary);
        }
        return summaries;
    }

    private CandidatePackage buildCandidatePackage(RealmRun run, RealmProfile profile) {
        Set<String> allowed = new LinkedHashSet<>();
        List<WorldCell> candidates = new ArrayList<>();
        for (PatchSummary patch : run.patchSummaries.values()) {
            if (profile.targetContinentId.equals(patch.continentId) && patch.assignableLand) {
                allowed.add(patch.patchId);
            }
        }
        for (WorldCell cell : run.worldCells) {
            if (cell.assignableLand() && profile.targetContinentId.equals(cell.continentId)
                    && allowed.contains(cell.patchId)) {
                candidates.add(cell);
            }
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("No candidate cells for realmId: " + profile.realmId);
        }
        WorldCell suggested = candidates.stream()
                .min(Comparator.comparingDouble((WorldCell cell) -> candidateScore(run, profile, cell))
                        .thenComparingInt(cell -> cell.gridZ)
                        .thenComparingInt(cell -> cell.gridX))
                .orElse(candidates.get(0));
        String packageId = "candidate_" + profile.realmId;
        return new CandidatePackage(packageId, profile.realmId, "survey_" + run.runId, allowed, List.of(), List.of(),
                new GridPoint(run.surveyResult.gridOriginBlockX(), run.surveyResult.gridOriginBlockZ()),
                run.surveyResult.cellStepBlocks(),
                new GridPoint(suggested.gridX, suggested.gridZ), "candidates/" + profile.realmId + "_candidate_map.png");
    }

    private SelectionAttempt validateSelection(RealmRun run, RealmProfile profile, CandidatePackage pack, GridPoint point,
            boolean allowSnap) {
        List<String> errors = new ArrayList<>();
        WorldCell cell = run.worldCellsByKey.get(key(point.x, point.z));
        if (isLegalSeedCell(profile, pack, cell, run, errors)) {
            return SelectionAttempt.accepted(point, point, cell, false, List.of(), List.of());
        }
        if (!allowSnap) {
            return SelectionAttempt.rejected(point, errors);
        }
        SelectionAttempt snapped = findSnap(run, profile, pack, point);
        if (snapped.accepted) {
            List<String> warnings = new ArrayList<>();
            warnings.add("snapApplied from " + point.x + "," + point.z + " to "
                    + snapped.finalPoint.x + "," + snapped.finalPoint.z);
            return SelectionAttempt.accepted(point, snapped.finalPoint, snapped.cell, true, warnings, List.of());
        }
        return SelectionAttempt.rejected(point, errors.isEmpty() ? List.of("No legal cell within snap radius.") : errors);
    }

    private boolean isLegalSeedCell(RealmProfile profile, CandidatePackage pack, WorldCell cell, RealmRun run,
            List<String> errors) {
        if (cell == null) {
            errors.add("Selected grid coordinate is outside the W map.");
            return false;
        }
        if (!cell.assignableLand()) {
            errors.add("Selected grid coordinate is not assignable land.");
            return false;
        }
        if (!profile.targetContinentId.equals(cell.continentId)) {
            errors.add("Selected grid coordinate is outside target continent.");
            return false;
        }
        if (!pack.allowedPatches.contains(cell.patchId)) {
            errors.add("Selected grid coordinate is outside allowed patches.");
            return false;
        }
        for (RealmSeed seed : run.seeds.values()) {
            double distance = distanceCells(cell.gridX, cell.gridZ, seed.seedGrid.x, seed.seedGrid.z);
            if (distance < MIN_SEED_DISTANCE_CELLS) {
                errors.add("Selected grid coordinate is too close to existing realm seed: " + seed.realmId);
                return false;
            }
        }
        return true;
    }

    private SelectionAttempt findSnap(RealmRun run, RealmProfile profile, CandidatePackage pack, GridPoint point) {
        WorldCell best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int dz = -DEFAULT_SNAP_RADIUS_CELLS; dz <= DEFAULT_SNAP_RADIUS_CELLS; dz++) {
            for (int dx = -DEFAULT_SNAP_RADIUS_CELLS; dx <= DEFAULT_SNAP_RADIUS_CELLS; dx++) {
                WorldCell candidate = run.worldCellsByKey.get(key(point.x + dx, point.z + dz));
                if (candidate == null) {
                    continue;
                }
                List<String> ignored = new ArrayList<>();
                if (!isLegalSeedCell(profile, pack, candidate, run, ignored)) {
                    continue;
                }
                double distance = distanceCells(point.x, point.z, candidate.gridX, candidate.gridZ);
                if (distance < bestDistance) {
                    best = candidate;
                    bestDistance = distance;
                }
            }
        }
        if (best == null) {
            return SelectionAttempt.rejected(point, List.of("No legal snap target within " + DEFAULT_SNAP_RADIUS_CELLS + " cells."));
        }
        return SelectionAttempt.accepted(point, new GridPoint(best.gridX, best.gridZ), best, true, List.of(), List.of());
    }

    private RealmTerritoryMap buildTerritory(RealmRun run, String group, boolean allowUnclaimedLand) {
        List<RealmProfile> profiles = run.profiles.stream()
                .filter(profile -> group.equals(profile.scalePlan.normalizationGroup))
                .toList();
        if (profiles.isEmpty()) {
            throw new IllegalArgumentException("No profiles for normalizationGroup: " + group);
        }
        List<WorldCell> landCells = run.worldCells.stream()
                .filter(cell -> cell.assignableLand() && group.equals(cell.continentId))
                .toList();
        if (landCells.isEmpty()) {
            throw new IllegalArgumentException("No assignable land cells for normalizationGroup: " + group);
        }
        Map<String, NormalizedScale> scales = normalizeScales(profiles);
        Map<String, Integer> quotas = quotas(scales, landCells.size(), allowUnclaimedLand);
        TerritoryBuildResult result = "action_budget".equals(run.expansionModel)
                ? buildActionBudgetTerritory(run, profiles, landCells, quotas, allowUnclaimedLand)
                : buildFrontierTerritory(run, profiles, landCells, quotas, allowUnclaimedLand);
        return RealmTerritoryMap.from(run.runId, group, landCells, result.ownership, run, scales, quotas, result.repairs);
    }

    private TerritoryBuildResult buildActionBudgetTerritory(RealmRun run, List<RealmProfile> profiles,
            List<WorldCell> landCells, Map<String, Integer> quotas, boolean allowUnclaimedLand) {
        Map<String, WorldCell> landByKey = new LinkedHashMap<>();
        for (WorldCell cell : landCells) {
            landByKey.put(key(cell.gridX, cell.gridZ), cell);
        }
        Map<String, String> ownership = new LinkedHashMap<>();
        Map<String, String> statusByKey = new LinkedHashMap<>();
        Map<String, Double> bestCosts = new LinkedHashMap<>();
        Map<String, Double> secondBestCosts = new LinkedHashMap<>();
        List<TerritoryRepair> repairs = new ArrayList<>();
        PriorityQueue<ActionFrontierClaim> frontier = new PriorityQueue<>(Comparator
                .comparingDouble(ActionFrontierClaim::cumulativeCost)
                .thenComparingLong(ActionFrontierClaim::sequence));
        long sequence = 0L;

        for (RealmProfile profile : profiles) {
            RealmSeed seed = run.seeds.get(profile.realmId);
            ExpansionBudget budget = expansionBudget(profile, quotas.getOrDefault(profile.realmId, 1), landCells.size());
            run.expansionBudgets.put(profile.realmId, budget);
            run.terrainCostProfiles.put(profile.realmId, terrainCostProfile(profile));
            if (seed == null) {
                continue;
            }
            WorldCell seedCell = landByKey.get(key(seed.seedGrid.x, seed.seedGrid.z));
            if (seedCell == null) {
                repairs.add(new TerritoryRepair("missing_seed_cell", profile.realmId,
                        "Seed is outside assignable land and cannot initialize action frontier.", 0));
                continue;
            }
            frontier.add(new ActionFrontierClaim(profile.realmId, seedCell, null, 0.0, 0, sequence++));
        }

        while (!frontier.isEmpty()) {
            ActionFrontierClaim claim = frontier.poll();
            String cellKey = key(claim.cell.gridX, claim.cell.gridZ);
            ExpansionBudget budget = run.expansionBudgets.get(claim.realmId);
            if (budget == null || claim.cumulativeCost > budget.hardStopThreshold) {
                recordStopReason(run, claim.realmId, "action_budget_exhausted", 1);
                continue;
            }
            if (claim.parent != null) {
                String parentKey = key(claim.parent.gridX, claim.parent.gridZ);
                if (!claim.realmId.equals(ownership.get(parentKey)) || !"owned".equals(statusByKey.get(parentKey))) {
                    recordStopReason(run, claim.realmId, "stale_frontier_parent", 1);
                    continue;
                }
            }
            double previousBest = bestCosts.getOrDefault(cellKey, Double.POSITIVE_INFINITY);
            if (previousBest < Double.POSITIVE_INFINITY && Math.abs(previousBest - claim.cumulativeCost) <= CONTESTED_COST_EPSILON) {
                String previousOwner = ownership.get(cellKey);
                if (!claim.realmId.equals(previousOwner)) {
                    if (previousOwner != null && (isSeedCell(run, previousOwner, claim.cell)
                            || !canReleaseCellWithoutDisconnecting(previousOwner, claim.cell, ownership))) {
                        recordStopReason(run, claim.realmId, "competition_failed", 1);
                        continue;
                    }
                    statusByKey.put(cellKey, "contested");
                    secondBestCosts.put(cellKey, Math.min(secondBestCosts.getOrDefault(cellKey, Double.POSITIVE_INFINITY),
                            claim.cumulativeCost));
                    recordStopReason(run, claim.realmId, "competition_contested", 1);
                }
                continue;
            }
            if (claim.cumulativeCost >= previousBest) {
                recordStopReason(run, claim.realmId, "competition_failed", 1);
                continue;
            }
            String previousOwner = ownership.get(cellKey);
            if (previousOwner != null && !claim.realmId.equals(previousOwner)
                    && (isSeedCell(run, previousOwner, claim.cell)
                            || !canReleaseCellWithoutDisconnecting(previousOwner, claim.cell, ownership))) {
                recordStopReason(run, claim.realmId, "competition_failed", 1);
                continue;
            }
            if (claim.parent != null && edgeBlocked(run, claim.realmId, claim.parent, claim.cell)) {
                statusByKey.putIfAbsent(cellKey, "blocked");
                recordStopReason(run, claim.realmId, "barrier_blocked", 1);
                continue;
            }
            ownership.put(cellKey, claim.realmId);
            statusByKey.put(cellKey, "owned");
            bestCosts.put(cellKey, claim.cumulativeCost);
            secondBestCosts.remove(cellKey);
            run.realmClaimCostSums.put(claim.realmId,
                    run.realmClaimCostSums.getOrDefault(claim.realmId, 0.0) + claim.cumulativeCost);
            run.realmMaxClaimCosts.put(claim.realmId,
                    Math.max(run.realmMaxClaimCosts.getOrDefault(claim.realmId, 0.0), claim.cumulativeCost));
            run.realmClaimCounts.put(claim.realmId, run.realmClaimCounts.getOrDefault(claim.realmId, 0) + 1);
            recordTerrainCost(run, claim.realmId, claim.cell.baseLandform(), Math.max(0.0, claim.cumulativeCost));

            for (int[] offset : DIRECTIONS) {
                WorldCell next = landByKey.get(key(claim.cell.gridX + offset[0], claim.cell.gridZ + offset[1]));
                if (next == null) {
                    continue;
                }
                double edgeCost = actionEdgeCost(run, claim.realmId, claim.cell, next, claim.pathLength + 1);
                if (!Double.isFinite(edgeCost)) {
                    statusByKey.putIfAbsent(key(next.gridX, next.gridZ), "blocked");
                    recordStopReason(run, claim.realmId, "edge_blocked", 1);
                    continue;
                }
                double nextCost = claim.cumulativeCost + edgeCost;
                if (nextCost > budget.hardStopThreshold || edgeCost > budget.maxClaimCost) {
                    statusByKey.putIfAbsent(key(next.gridX, next.gridZ), "unreachable");
                    recordStopReason(run, claim.realmId, "action_budget_exhausted", 1);
                    continue;
                }
                frontier.add(new ActionFrontierClaim(claim.realmId, next, claim.cell, nextCost,
                        claim.pathLength + 1, sequence++));
            }
        }

        for (WorldCell cell : landCells) {
            String cellKey = key(cell.gridX, cell.gridZ);
            statusByKey.putIfAbsent(cellKey, ownership.containsKey(cellKey) ? "owned" : "wild");
        }
        run.territoryCellStatuses = statusByKey;
        run.territoryClaimCosts = bestCosts;
        return new TerritoryBuildResult(ownership, repairs);
    }

    private TerritoryBuildResult buildFrontierTerritory(RealmRun run, List<RealmProfile> profiles, List<WorldCell> landCells,
            Map<String, Integer> quotas, boolean allowUnclaimedLand) {
        Map<String, WorldCell> landByKey = new LinkedHashMap<>();
        for (WorldCell cell : landCells) {
            landByKey.put(key(cell.gridX, cell.gridZ), cell);
        }
        Map<String, RealmProfile> profilesById = new LinkedHashMap<>();
        Map<String, String> ownership = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, PriorityQueue<FrontierClaim>> frontiers = new LinkedHashMap<>();
        List<TerritoryRepair> repairs = new ArrayList<>();
        long sequence = 0L;

        for (RealmProfile profile : profiles) {
            profilesById.put(profile.realmId, profile);
            counts.put(profile.realmId, 0);
            frontiers.put(profile.realmId, new PriorityQueue<>(Comparator
                    .comparingDouble(FrontierClaim::cost)
                    .thenComparingLong(FrontierClaim::sequence)));
            RealmSeed seed = run.seeds.get(profile.realmId);
            if (seed == null) {
                continue;
            }
            WorldCell seedCell = landByKey.get(key(seed.seedGrid.x, seed.seedGrid.z));
            if (seedCell == null) {
                repairs.add(new TerritoryRepair("missing_seed_cell", profile.realmId,
                        "Seed is outside assignable land and cannot initialize frontier.", 0));
                continue;
            }
            String seedKey = key(seedCell.gridX, seedCell.gridZ);
            if (!ownership.containsKey(seedKey)) {
                ownership.put(seedKey, profile.realmId);
                counts.put(profile.realmId, 1);
                sequence = enqueueFrontier(run, profile, seedCell, seedCell, 0.0, landByKey, ownership,
                        frontiers.get(profile.realmId), sequence);
            }
        }

        int assigned = ownership.size();
        int targetAssigned = allowUnclaimedLand
                ? Math.min(landCells.size(), quotas.values().stream().mapToInt(Integer::intValue).sum())
                : landCells.size();
        while (assigned < targetAssigned) {
            String nextRealm = selectNextFrontierRealm(profiles, counts, quotas, frontiers);
            if (nextRealm == null) {
                break;
            }
            PriorityQueue<FrontierClaim> queue = frontiers.get(nextRealm);
            FrontierClaim claim = pollValidClaim(queue, ownership, landByKey, nextRealm);
            if (claim == null) {
                continue;
            }
            int quota = quotas.getOrDefault(nextRealm, 0);
            if (allowUnclaimedLand && counts.getOrDefault(nextRealm, 0) >= quota) {
                continue;
            }
            String claimKey = key(claim.cell.gridX, claim.cell.gridZ);
            ownership.put(claimKey, nextRealm);
            counts.put(nextRealm, counts.getOrDefault(nextRealm, 0) + 1);
            assigned++;
            RealmProfile profile = profilesById.get(nextRealm);
            sequence = enqueueFrontier(run, profile, claim.cell, claim.parent, claim.cost, landByKey, ownership,
                    queue, sequence);
        }

        if (!allowUnclaimedLand) {
            assigned += attachUnclaimedCells(run, profilesById, landCells, ownership, counts, repairs);
        }
        rebalanceAreaQuotas(run, profiles, landByKey, ownership, quotas, repairs);
        repairDetachedComponents(run, profiles, landByKey, ownership, repairs);
        rebalanceAreaQuotas(run, profiles, landByKey, ownership, quotas, repairs);
        repairDetachedComponents(run, profiles, landByKey, ownership, repairs);
        Map<String, String> statusByKey = new LinkedHashMap<>();
        Map<String, Double> claimCosts = new LinkedHashMap<>();
        for (WorldCell cell : landCells) {
            String cellKey = key(cell.gridX, cell.gridZ);
            statusByKey.put(cellKey, ownership.containsKey(cellKey) ? "owned" : "wild");
            claimCosts.put(cellKey, ownership.containsKey(cellKey) ? 1.0 : 0.0);
        }
        run.territoryCellStatuses = statusByKey;
        run.territoryClaimCosts = claimCosts;
        return new TerritoryBuildResult(ownership, repairs);
    }

    private long enqueueFrontier(RealmRun run, RealmProfile profile, WorldCell from, WorldCell parent, double parentCost,
            Map<String, WorldCell> landByKey, Map<String, String> ownership, PriorityQueue<FrontierClaim> queue,
            long sequence) {
        if (profile == null || queue == null) {
            return sequence;
        }
        RealmSeed seed = run.seeds.get(profile.realmId);
        for (int[] offset : DIRECTIONS) {
            WorldCell next = landByKey.get(key(from.gridX + offset[0], from.gridZ + offset[1]));
            if (next == null || ownership.containsKey(key(next.gridX, next.gridZ))) {
                continue;
            }
            double cost = frontierMoveCost(profile, seed, from, next, parentCost);
            queue.add(new FrontierClaim(next, from, profile.realmId, cost, sequence++));
        }
        return sequence;
    }

    private String selectNextFrontierRealm(List<RealmProfile> profiles, Map<String, Integer> counts,
            Map<String, Integer> quotas, Map<String, PriorityQueue<FrontierClaim>> frontiers) {
        String bestRealm = null;
        double bestDeficit = Double.NEGATIVE_INFINITY;
        double bestCost = Double.POSITIVE_INFINITY;
        for (RealmProfile profile : profiles) {
            int quota = Math.max(1, quotas.getOrDefault(profile.realmId, 1));
            int count = counts.getOrDefault(profile.realmId, 0);
            if (count >= quota) {
                continue;
            }
            PriorityQueue<FrontierClaim> queue = frontiers.get(profile.realmId);
            FrontierClaim claim = queue == null ? null : queue.peek();
            if (claim == null) {
                continue;
            }
            double deficit = (quota - count) / (double) quota;
            double claimCost = claim.cost / QUOTA_URGENCY_WEIGHT;
            if (deficit > bestDeficit || (Math.abs(deficit - bestDeficit) < 0.0001 && claimCost < bestCost)) {
                bestDeficit = deficit;
                bestCost = claimCost;
                bestRealm = profile.realmId;
            }
        }
        return bestRealm;
    }

    private FrontierClaim pollValidClaim(PriorityQueue<FrontierClaim> queue, Map<String, String> ownership,
            Map<String, WorldCell> landByKey, String realmId) {
        while (queue != null && !queue.isEmpty()) {
            FrontierClaim claim = queue.poll();
            if (!landByKey.containsKey(key(claim.cell.gridX, claim.cell.gridZ))) {
                continue;
            }
            if (ownership.containsKey(key(claim.cell.gridX, claim.cell.gridZ))) {
                continue;
            }
            if (claim.parent != null && realmId.equals(ownership.get(key(claim.parent.gridX, claim.parent.gridZ)))) {
                return claim;
            }
            if (hasOwnedNeighbor(claim.cell, realmId, ownership)) {
                return claim;
            }
        }
        return null;
    }

    private int attachUnclaimedCells(RealmRun run, Map<String, RealmProfile> profilesById, List<WorldCell> landCells,
            Map<String, String> ownership, Map<String, Integer> counts, List<TerritoryRepair> repairs) {
        int attached = 0;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (WorldCell cell : landCells) {
                String cellKey = key(cell.gridX, cell.gridZ);
                if (ownership.containsKey(cellKey)) {
                    continue;
                }
                String owner = bestAdjacentOwner(run, profilesById, cell, ownership);
                if (owner == null) {
                    continue;
                }
                ownership.put(cellKey, owner);
                counts.put(owner, counts.getOrDefault(owner, 0) + 1);
                attached++;
                changed = true;
            }
        }
        for (WorldCell cell : landCells) {
            String cellKey = key(cell.gridX, cell.gridZ);
            if (ownership.containsKey(cellKey)) {
                continue;
            }
            String owner = nearestSeedOwner(run, profilesById.keySet(), cell);
            if (owner == null) {
                continue;
            }
            ownership.put(cellKey, owner);
            counts.put(owner, counts.getOrDefault(owner, 0) + 1);
            attached++;
            repairs.add(new TerritoryRepair("nearest_seed_fill", owner,
                    "Filled an unreachable unclaimed cell by nearest seed fallback.", 1));
        }
        if (attached > 0) {
            repairs.add(new TerritoryRepair("attach_unclaimed_cells", "all",
                    "Attached unclaimed cells after frontier quotas were exhausted.", attached));
        }
        return attached;
    }

    private String bestAdjacentOwner(RealmRun run, Map<String, RealmProfile> profilesById, WorldCell cell,
            Map<String, String> ownership) {
        String best = null;
        double bestCost = Double.POSITIVE_INFINITY;
        Set<String> adjacentOwners = new LinkedHashSet<>();
        for (int[] offset : DIRECTIONS) {
            String owner = ownership.get(key(cell.gridX + offset[0], cell.gridZ + offset[1]));
            if (owner != null) {
                adjacentOwners.add(owner);
            }
        }
        for (String owner : adjacentOwners) {
            RealmProfile profile = profilesById.get(owner);
            RealmSeed seed = run.seeds.get(owner);
            if (profile == null || seed == null) {
                continue;
            }
            double cost = expansionCost(profile, seed, cell);
            if (cost < bestCost) {
                bestCost = cost;
                best = owner;
            }
        }
        return best;
    }

    private String nearestSeedOwner(RealmRun run, Set<String> realmIds, WorldCell cell) {
        String best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (String realmId : realmIds) {
            RealmSeed seed = run.seeds.get(realmId);
            if (seed == null) {
                continue;
            }
            double distance = distanceCells(seed.seedGrid.x, seed.seedGrid.z, cell.gridX, cell.gridZ);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = realmId;
            }
        }
        return best;
    }

    private void repairDetachedComponents(RealmRun run, List<RealmProfile> profiles, Map<String, WorldCell> landByKey,
            Map<String, String> ownership, List<TerritoryRepair> repairs) {
        for (RealmProfile profile : profiles) {
            List<Set<String>> components = componentsForRealm(profile.realmId, landByKey, ownership);
            if (components.size() <= 1) {
                continue;
            }
            components.sort(Comparator.<Set<String>>comparingInt(Set::size).reversed());
            Set<String> main = components.get(0);
            int changed = 0;
            for (int i = 1; i < components.size(); i++) {
                for (String cellKey : components.get(i)) {
                    WorldCell cell = landByKey.get(cellKey);
                    String replacement = bestAdjacentOwnerExcluding(run, profile.realmId, cell, ownership);
                    if (replacement == null) {
                        replacement = nearestMainComponentOwner(run, profile.realmId, cell, main, ownership);
                    }
                    if (replacement != null && !profile.realmId.equals(replacement)) {
                        ownership.put(cellKey, replacement);
                        changed++;
                    }
                }
            }
            if (changed > 0) {
                repairs.add(new TerritoryRepair("detach_component_reassigned", profile.realmId,
                        "Reassigned detached components to adjacent or nearest main territories.", changed));
            }
        }
    }

    private void rebalanceAreaQuotas(RealmRun run, List<RealmProfile> profiles, Map<String, WorldCell> landByKey,
            Map<String, String> ownership, Map<String, Integer> quotas, List<TerritoryRepair> repairs) {
        Map<String, RealmProfile> profilesById = new LinkedHashMap<>();
        for (RealmProfile profile : profiles) {
            profilesById.put(profile.realmId, profile);
        }
        int changed = 0;
        int maxIterations = Math.max(1, landByKey.size() * 4);
        for (int i = 0; i < maxIterations; i++) {
            Map<String, Integer> counts = territoryCounts(profiles, ownership);
            RebalanceCandidate candidate = bestRebalanceCandidate(run, profiles, profilesById, landByKey, ownership,
                    counts, quotas);
            if (candidate == null) {
                break;
            }
            ownership.put(candidate.cellKey, candidate.receiverRealmId);
            changed++;
        }
        if (changed > 0) {
            repairs.add(new TerritoryRepair("quota_rebalanced", "all",
                    "Moved border cells from over-target realms to adjacent under-target realms.", changed));
        }
    }

    private Map<String, Integer> territoryCounts(List<RealmProfile> profiles, Map<String, String> ownership) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (RealmProfile profile : profiles) {
            counts.put(profile.realmId, 0);
        }
        for (String owner : ownership.values()) {
            if (counts.containsKey(owner)) {
                counts.put(owner, counts.get(owner) + 1);
            }
        }
        return counts;
    }

    private RebalanceCandidate bestRebalanceCandidate(RealmRun run, List<RealmProfile> profiles,
            Map<String, RealmProfile> profilesById,
            Map<String, WorldCell> landByKey, Map<String, String> ownership, Map<String, Integer> counts,
            Map<String, Integer> quotas) {
        RebalanceCandidate best = null;
        for (RealmProfile receiverProfile : profiles) {
            String receiver = receiverProfile.realmId;
            int receiverQuota = Math.max(1, quotas.getOrDefault(receiver, 1));
            int receiverCount = counts.getOrDefault(receiver, 0);
            if (receiverCount >= Math.ceil(receiverQuota * 1.2)) {
                continue;
            }
            RealmSeed receiverSeed = run.seeds.get(receiver);
            if (receiverSeed == null) {
                continue;
            }
            double receiverDeficit = (receiverQuota - receiverCount) / (double) receiverQuota;
            for (WorldCell cell : landByKey.values()) {
                String cellKey = key(cell.gridX, cell.gridZ);
                String donor = ownership.get(cellKey);
                if (donor == null || donor.equals(receiver)) {
                    continue;
                }
                int donorQuota = Math.max(1, quotas.getOrDefault(donor, 1));
                if (counts.getOrDefault(donor, 0) <= donorQuota || isSeedCell(run, donor, cell)) {
                    continue;
                }
                if (!hasOwnedNeighbor(cell, receiver, ownership)) {
                    continue;
                }
                if (!canReleaseCellWithoutDisconnecting(donor, cell, ownership)) {
                    continue;
                }
                double donorExcess = (counts.getOrDefault(donor, 0) - donorQuota) / (double) donorQuota;
                double score = expansionCost(receiverProfile, receiverSeed, cell) - donorExcess * 4.0
                        - receiverDeficit * 8.0;
                if (best == null || score < best.score) {
                    best = new RebalanceCandidate(cellKey, receiver, donor, score);
                }
            }
        }
        return best;
    }

    private boolean canReleaseCellWithoutDisconnecting(String donor, WorldCell cell, Map<String, String> ownership) {
        List<String> remainingNeighbors = new ArrayList<>();
        for (int[] offset : DIRECTIONS) {
            String neighborKey = key(cell.gridX + offset[0], cell.gridZ + offset[1]);
            if (donor.equals(ownership.get(neighborKey))) {
                remainingNeighbors.add(neighborKey);
            }
        }
        if (remainingNeighbors.size() <= 1) {
            return true;
        }
        String removedKey = key(cell.gridX, cell.gridZ);
        Set<String> targets = new HashSet<>(remainingNeighbors);
        String start = remainingNeighbors.get(0);
        ArrayDeque<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(start);
        visited.add(start);
        while (!queue.isEmpty()) {
            String currentKey = queue.removeFirst();
            targets.remove(currentKey);
            if (targets.isEmpty()) {
                return true;
            }
            String[] parts = currentKey.split(",", 2);
            int x = Integer.parseInt(parts[0]);
            int z = Integer.parseInt(parts[1]);
            for (int[] offset : DIRECTIONS) {
                String nextKey = key(x + offset[0], z + offset[1]);
                if (nextKey.equals(removedKey) || !visited.add(nextKey) || !donor.equals(ownership.get(nextKey))) {
                    continue;
                }
                queue.addLast(nextKey);
            }
        }
        return false;
    }

    private boolean isSeedCell(RealmRun run, String realmId, WorldCell cell) {
        RealmSeed seed = run.seeds.get(realmId);
        return seed != null && seed.seedGrid.x == cell.gridX && seed.seedGrid.z == cell.gridZ;
    }

    private List<Set<String>> componentsForRealm(String realmId, Map<String, WorldCell> landByKey,
            Map<String, String> ownership) {
        List<Set<String>> components = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (String cellKey : landByKey.keySet()) {
            if (visited.contains(cellKey) || !realmId.equals(ownership.get(cellKey))) {
                continue;
            }
            Set<String> component = new LinkedHashSet<>();
            ArrayDeque<String> queue = new ArrayDeque<>();
            queue.add(cellKey);
            visited.add(cellKey);
            while (!queue.isEmpty()) {
                String currentKey = queue.removeFirst();
                component.add(currentKey);
                WorldCell current = landByKey.get(currentKey);
                for (int[] offset : DIRECTIONS) {
                    String nextKey = key(current.gridX + offset[0], current.gridZ + offset[1]);
                    if (!visited.contains(nextKey) && realmId.equals(ownership.get(nextKey))) {
                        visited.add(nextKey);
                        queue.addLast(nextKey);
                    }
                }
            }
            components.add(component);
        }
        return components;
    }

    private String bestAdjacentOwnerExcluding(RealmRun run, String excludedRealm, WorldCell cell,
            Map<String, String> ownership) {
        String best = null;
        double bestCost = Double.POSITIVE_INFINITY;
        Set<String> adjacentOwners = new LinkedHashSet<>();
        for (int[] offset : DIRECTIONS) {
            String owner = ownership.get(key(cell.gridX + offset[0], cell.gridZ + offset[1]));
            if (owner != null && !excludedRealm.equals(owner)) {
                adjacentOwners.add(owner);
            }
        }
        for (String owner : adjacentOwners) {
            RealmProfile profile = run.profile(owner);
            RealmSeed seed = run.seeds.get(owner);
            double cost = expansionCost(profile, seed, cell);
            if (cost < bestCost) {
                bestCost = cost;
                best = owner;
            }
        }
        return best;
    }

    private String nearestMainComponentOwner(RealmRun run, String excludedRealm, WorldCell cell, Set<String> main,
            Map<String, String> ownership) {
        String best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Map.Entry<String, RealmSeed> entry : run.seeds.entrySet()) {
            String realmId = entry.getKey();
            if (excludedRealm.equals(realmId)) {
                continue;
            }
            double distance = distanceCells(entry.getValue().seedGrid.x, entry.getValue().seedGrid.z, cell.gridX, cell.gridZ);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = realmId;
            }
        }
        if (best != null) {
            return best;
        }
        for (String key : main) {
            String owner = ownership.get(key);
            if (owner != null && !excludedRealm.equals(owner)) {
                return owner;
            }
        }
        return null;
    }

    private CitySeedRegistry buildRegistry(RealmRun run) {
        List<CitySeed> seeds = new ArrayList<>();
        for (RealmProfile profile : run.profiles) {
            CapitalCityIntent capitalIntent = run.capitalIntents.get(profile.realmId);
            RealmSeed realmSeed = run.seeds.get(profile.realmId);
            RealmStats stats = run.territory.stats.get(profile.realmId);
            if (capitalIntent != null && realmSeed != null && stats != null) {
                seeds.add(CitySeed.fixtureCapital(capitalIntent, realmSeed).withCandidateMetadata(
                        "capital_core", "capital_" + profile.realmId, nearestCityDistance(run, profile.realmId,
                                realmSeed.seedGrid, seeds), ""));
            }
            if (stats == null) {
                continue;
            }
            WorldCell portCell = bestCityCell(run, profile.realmId, "port", "town",
                    cell -> "shore".equals(cell.landWater),
                    cell -> cell.waterDistanceBlocks / 128.0 + distanceToNearestCity(run, profile.realmId, cell, seeds) * -0.15,
                    seeds);
            if (portCell != null && stats.coastalRatio > 0.05) {
                addCitySeed(run, seeds, CitySeed.from("city_" + profile.realmId + "_port", profile.realmId, "port",
                        "town", portCell, 6, List.of("land", "near_water", "inside_realm"),
                        List.of("harbor", "market", "storage"), "player_nearby", "rule_fixture:coastal territory")
                        .withCandidateMetadata(subregionIdFor(run, profile.realmId, portCell),
                                "port_" + profile.realmId + "_" + portCell.gridX + "_" + portCell.gridZ,
                                nearestCityDistance(run, profile.realmId, new GridPoint(portCell.gridX, portCell.gridZ), seeds),
                                ""));
            }
            WorldCell miningCell = bestCityCell(run, profile.realmId, "mining_town", "town",
                    cell -> cell.landform.equals("ridge") || cell.landform.equals("slope") || cell.landform.equals("cliff"),
                    cell -> -resourceHint(cell) * 4.0 + distanceToNearestCity(run, profile.realmId, cell, seeds) * -0.1,
                    seeds);
            if (miningCell != null) {
                addCitySeed(run, seeds, CitySeed.from("city_" + profile.realmId + "_mining", profile.realmId, "mining_town",
                        "town", miningCell, 5, List.of("land", "inside_realm", "near_mountain"),
                        List.of("industry", "storage", "worker_housing"), "realm_development", "rule_fixture:mountain landform")
                        .withCandidateMetadata(subregionIdFor(run, profile.realmId, miningCell),
                                "mining_" + profile.realmId + "_" + miningCell.gridX + "_" + miningCell.gridZ,
                                nearestCityDistance(run, profile.realmId, new GridPoint(miningCell.gridX, miningCell.gridZ), seeds),
                                ""));
            }
            WorldCell borderCell = bestCityCell(run, profile.realmId, "border_fort", "town",
                    cell -> isBorderCell(run, profile.realmId, cell),
                    cell -> -profile.expansionStyle.borderPressure * 3.0
                            + distanceToNearestCity(run, profile.realmId, cell, seeds) * -0.08,
                    seeds);
            if (borderCell != null && !stats.neighbors.isEmpty()) {
                addCitySeed(run, seeds, CitySeed.from("city_" + profile.realmId + "_border_fort", profile.realmId, "border_fort",
                        "town", borderCell, 4, List.of("land", "inside_realm", "near_border"),
                        List.of("defense", "barracks", "market"), "story_stage", "rule_fixture:realm border")
                        .withCandidateMetadata(subregionIdFor(run, profile.realmId, borderCell),
                                "border_" + profile.realmId + "_" + borderCell.gridX + "_" + borderCell.gridZ,
                                nearestCityDistance(run, profile.realmId, new GridPoint(borderCell.gridX, borderCell.gridZ), seeds),
                                ""));
            }
        }
        return new CitySeedRegistry("registry_" + run.runId, run.runId, run.territory.territoryMapId, seeds);
    }

    private WorldCell firstOwnedCell(RealmRun run, String realmId, CellPredicate predicate) {
        if (run.territory == null) {
            return null;
        }
        for (TerritoryCell cell : run.territory.cells) {
            if (realmId.equals(cell.realmId)) {
                WorldCell worldCell = run.worldCellsByKey.get(key(cell.gridX, cell.gridZ));
                if (worldCell != null && predicate.test(worldCell)) {
                    return worldCell;
                }
            }
        }
        return null;
    }

    private WorldCell bestCityCell(RealmRun run, String realmId, String role, String scale, CellPredicate predicate,
            ToDoubleFunction<WorldCell> scorer, List<CitySeed> selectedSeeds) {
        if (run.territory == null) {
            return null;
        }
        int planningRadius = planningRadiusCells(role, scale);
        return run.territory.cells.stream()
                .filter(cell -> realmId.equals(cell.realmId))
                .map(cell -> run.worldCellsByKey.get(key(cell.gridX, cell.gridZ)))
                .filter(Objects::nonNull)
                .filter(predicate::test)
                .filter(cell -> citySpacingOk(run, realmId, new GridPoint(cell.gridX, cell.gridZ), planningRadius,
                        role, selectedSeeds))
                .min(Comparator.comparingDouble(scorer)
                        .thenComparingInt(cell -> cell.gridZ)
                        .thenComparingInt(cell -> cell.gridX))
                .orElse(null);
    }

    private void addCitySeed(RealmRun run, List<CitySeed> seeds, CitySeed candidate) {
        if (citySpacingOk(run, candidate.realmId, candidate.anchorGrid, candidate.planningRadiusCells,
                candidate.role, seeds)) {
            seeds.add(candidate);
        }
    }

    private boolean citySpacingOk(RealmRun run, String realmId, GridPoint point, int planningRadius, String role,
            List<CitySeed> selectedSeeds) {
        for (CitySeed seed : selectedSeeds) {
            if (!realmId.equals(seed.realmId) || !seed.satelliteOf.isBlank() || isSatelliteRole(role)) {
                continue;
            }
            double distance = graphDistanceWithinRealm(run, realmId, point, seed.anchorGrid,
                    Math.max(32, planningRadius + seed.planningRadiusCells + 8));
            if (!Double.isFinite(distance)) {
                distance = distanceCells(point.x, point.z, seed.anchorGrid.x, seed.anchorGrid.z);
            }
            if (distance < planningRadius + seed.planningRadiusCells) {
                return false;
            }
        }
        return true;
    }

    private double nearestCityDistance(RealmRun run, String realmId, GridPoint point, List<CitySeed> selectedSeeds) {
        double best = Double.POSITIVE_INFINITY;
        for (CitySeed seed : selectedSeeds) {
            if (!realmId.equals(seed.realmId)) {
                continue;
            }
            double distance = graphDistanceWithinRealm(run, realmId, point, seed.anchorGrid, 96);
            if (!Double.isFinite(distance)) {
                distance = distanceCells(point.x, point.z, seed.anchorGrid.x, seed.anchorGrid.z);
            }
            best = Math.min(best, distance);
        }
        return Double.isFinite(best) ? best : -1.0;
    }

    private double distanceToNearestCity(RealmRun run, String realmId, WorldCell cell, List<CitySeed> selectedSeeds) {
        double distance = nearestCityDistance(run, realmId, new GridPoint(cell.gridX, cell.gridZ), selectedSeeds);
        return distance < 0.0 ? 999.0 : distance;
    }

    private double graphDistanceWithinRealm(RealmRun run, String realmId, GridPoint from, GridPoint to, int maxDistance) {
        if (from.equals(to)) {
            return 0.0;
        }
        if (run.territory == null) {
            return Double.POSITIVE_INFINITY;
        }
        Map<String, String> owners = run.territory.ownershipByKey();
        String startKey = key(from.x, from.z);
        String targetKey = key(to.x, to.z);
        if (!realmId.equals(owners.get(startKey)) || !realmId.equals(owners.get(targetKey))) {
            return Double.POSITIVE_INFINITY;
        }
        ArrayDeque<GridDistance> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(new GridDistance(from.x, from.z, 0));
        visited.add(startKey);
        while (!queue.isEmpty()) {
            GridDistance current = queue.removeFirst();
            if (current.distance >= maxDistance) {
                continue;
            }
            for (int[] offset : DIRECTIONS) {
                int x = current.x + offset[0];
                int z = current.z + offset[1];
                String nextKey = key(x, z);
                if (!visited.add(nextKey) || !realmId.equals(owners.get(nextKey))) {
                    continue;
                }
                int nextDistance = current.distance + 1;
                if (nextKey.equals(targetKey)) {
                    return nextDistance;
                }
                queue.addLast(new GridDistance(x, z, nextDistance));
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    private String subregionIdFor(RealmRun run, String realmId, WorldCell cell) {
        RealmSeed realmSeed = run.seeds.get(realmId);
        if (realmSeed == null) {
            return realmId + "_region";
        }
        int dx = cell.gridX - realmSeed.seedGrid.x;
        int dz = cell.gridZ - realmSeed.seedGrid.z;
        if (Math.abs(dx) <= 2 && Math.abs(dz) <= 2) {
            return realmId + "_capital_core";
        }
        String ns = dz < 0 ? "north" : "south";
        String ew = dx < 0 ? "west" : "east";
        return realmId + "_" + ns + "_" + ew;
    }

    private static boolean isSatelliteRole(String role) {
        return "watchtower".equals(role) || "outpost".equals(role) || "satellite".equals(role);
    }

    private static int planningRadiusCells(String role, String scale) {
        if ("border_fort".equals(role)) {
            return 1;
        }
        return switch (scale) {
            case "capital" -> 4;
            case "large_city" -> 3;
            case "city" -> 3;
            case "town" -> 2;
            default -> 1;
        };
    }

    private WorldCell firstBorderCell(RealmRun run, String realmId) {
        if (run.territory == null) {
            return null;
        }
        Map<String, String> owners = run.territory.ownershipByKey();
        for (TerritoryCell cell : run.territory.cells) {
            if (!realmId.equals(cell.realmId)) {
                continue;
            }
            for (int[] offset : DIRECTIONS) {
                String neighbor = owners.get(key(cell.gridX + offset[0], cell.gridZ + offset[1]));
                if (neighbor != null && !realmId.equals(neighbor)) {
                    return run.worldCellsByKey.get(key(cell.gridX, cell.gridZ));
                }
            }
        }
        return null;
    }

    private boolean isBorderCell(RealmRun run, String realmId, WorldCell cell) {
        if (run.territory == null || cell == null) {
            return false;
        }
        Map<String, String> owners = run.territory.ownershipByKey();
        if (!realmId.equals(owners.get(key(cell.gridX, cell.gridZ)))) {
            return false;
        }
        for (int[] offset : DIRECTIONS) {
            String neighbor = owners.get(key(cell.gridX + offset[0], cell.gridZ + offset[1]));
            if (neighbor != null && !realmId.equals(neighbor)) {
                return true;
            }
        }
        return false;
    }

    private void exportWorld(RealmRun run) throws IOException {
        writeJson(run.runDirectory.resolve("world_survey_context.json"), surveyJson(run));
        writeJson(run.runDirectory.resolve("world_patch_map.json"), worldPatchMapJson(run));
        exportWorldPreview(run, run.runDirectory.resolve("world_patch_preview.png"), false, null);
        exportWorldPreview(run, run.runDirectory.resolve("grid_overlay_preview.png"), true, null);
        JsonObject manifest = new JsonObject();
        manifest.addProperty("runId", run.runId);
        manifest.addProperty("surveyId", run.surveyResult.surveyId());
        manifest.addProperty("sealed", run.surveyResult.sealed());
        manifest.addProperty("cellStepBlocks", run.surveyResult.cellStepBlocks());
        manifest.addProperty("microSampleStrideBlocks", run.surveyResult.microSampleStrideBlocks());
        manifest.addProperty("metricSampleStrideBlocks", run.surveyResult.microSampleStrideBlocks());
        manifest.addProperty("localSlopeRadiusBlocks", run.surveyResult.localSlopeRadiusBlocks());
        manifest.add("metricScales", metricScalesJson());
        manifest.addProperty("microSamplingImplemented", run.surveyResult.microSamplingImplemented());
        manifest.addProperty("microSampleBudget", microSampleBudget(run.surveyResult));
        manifest.addProperty("microSampleBudgetPerCell", microSampleBudgetPerCell(run.surveyResult));
        manifest.addProperty("microSampleCount", run.surveyResult.microSampleCount());
        manifest.addProperty("adaptiveSampling", false);
        manifest.addProperty("configHash", run.surveyResult.configHash());
        manifest.addProperty("gridOriginBlockX", run.surveyResult.gridOriginBlockX());
        manifest.addProperty("gridOriginBlockZ", run.surveyResult.gridOriginBlockZ());
        manifest.addProperty("gridSizeWidth", run.surveyResult.gridSizeWidth());
        manifest.addProperty("gridSizeHeight", run.surveyResult.gridSizeHeight());
        manifest.add("scanBounds", scanBoundsJson(run.surveyResult));
        manifest.add("surveyStats", surveyStatsJson(run.surveyResult));
        manifest.add("continents", continentsJson(run.continentSummaries.values()));
        writeJson(run.runDirectory.resolve("w_manifest.json"), manifest);
        run.artifacts.put("worldSurveyContext", "world_survey_context.json");
        run.artifacts.put("worldPatchMap", "world_patch_map.json");
        run.artifacts.put("worldPatchPreview", "world_patch_preview.png");
        run.artifacts.put("gridOverlayPreview", "grid_overlay_preview.png");
        run.artifacts.put("wManifest", "w_manifest.json");
        if (Files.exists(run.runDirectory.resolve("world_feature_grid.json"))) {
            run.artifacts.put("worldFeatureGrid", "world_feature_grid.json");
        }
        if (Files.exists(run.surveyResult.manifestPath())) {
            run.artifacts.put("worldSurveyManifest", run.runDirectory.relativize(run.surveyResult.manifestPath()).toString());
        }
    }

    private void exportT1(RealmRun run) throws IOException {
        writeJson(run.runDirectory.resolve("realm_profiles.json"), profilesJson(run.profiles));
        writeJson(run.runDirectory.resolve("candidate_map_packages.json"), candidatePackagesJson(run.candidatePackages.values()));
        JsonObject manifest = new JsonObject();
        manifest.addProperty("runId", run.runId);
        manifest.addProperty("realmCount", run.profiles.size());
        manifest.add("realmIds", stringArray(run.profiles.stream().map(profile -> profile.realmId).toList()));
        writeJson(run.runDirectory.resolve("t1_manifest.json"), manifest);
        run.artifacts.put("realmProfiles", "realm_profiles.json");
        run.artifacts.put("candidateMapPackages", "candidate_map_packages.json");
        run.artifacts.put("t1Manifest", "t1_manifest.json");
    }

    private void exportT2(RealmRun run) throws IOException {
        writeJson(run.runDirectory.resolve("realm_coordinate_selections.json"), selectionsJson(run.selections.values()));
        writeJson(run.runDirectory.resolve("realm_seeds.json"), seedsJson(run.seeds.values()));
        writeJson(run.runDirectory.resolve("capital_city_intents.json"), capitalIntentsJson(run.capitalIntents.values()));
        JsonObject report = new JsonObject();
        report.addProperty("runId", run.runId);
        report.addProperty("completedSelections", run.seeds.size());
        report.addProperty("totalRealms", run.profiles.size());
        writeJson(run.runDirectory.resolve("t2_report.json"), report);
        run.artifacts.put("realmCoordinateSelections", "realm_coordinate_selections.json");
        run.artifacts.put("realmSeeds", "realm_seeds.json");
        run.artifacts.put("capitalCityIntents", "capital_city_intents.json");
        run.artifacts.put("t2Report", "t2_report.json");
    }

    private void exportTerritory(RealmRun run) throws IOException {
        writeJson(run.runDirectory.resolve("realm_territory_map.json"), run.territory.asJson());
        writeJson(run.runDirectory.resolve("t3_report.json"), run.territory.reportJson());
        writeJson(run.runDirectory.resolve("territory_repair_log.json"), run.territory.repairLogJson());
        exportTerritoryPreview(run, run.runDirectory.resolve("territory_preview.png"));
        run.artifacts.put("realmTerritoryMap", "realm_territory_map.json");
        run.artifacts.put("territoryPreview", "territory_preview.png");
        run.artifacts.put("t3Report", "t3_report.json");
        run.artifacts.put("territoryRepairLog", "territory_repair_log.json");
    }

    private void exportRegistry(RealmRun run) throws IOException {
        exportRegistry(run, run.registry.asJson());
    }

    private void exportRegistry(RealmRun run, JsonObject registryJson) throws IOException {
        writeJson(run.runDirectory.resolve("city_seed_registry.json"), registryJson);
        writeJson(run.runDirectory.resolve("t4_report.json"), t4ReportJson(run));
        exportRealmCityCandidateMaps(run);
        writeJson(run.runDirectory.resolve("realm_city_candidate_packages.json"), cityCandidatePackagesJson(run));
        exportCitySeedPreview(run, run.runDirectory.resolve("city_seed_preview.png"));
        run.artifacts.put("citySeedRegistry", "city_seed_registry.json");
        run.artifacts.put("citySeedPreview", "city_seed_preview.png");
        run.artifacts.put("t4Report", "t4_report.json");
        run.artifacts.put("realmCityCandidatePackages", "realm_city_candidate_packages.json");
        exportScoreManifest(run);
    }

    private void invalidateAcceptanceReport(RealmRun run) throws IOException {
        Path path = run.runDirectory.resolve("acceptance_report.json");
        if (!Files.isRegularFile(path)) {
            return;
        }
        JsonObject report;
        try {
            report = readJsonObject(path, "acceptance_report");
        } catch (IllegalArgumentException exception) {
            report = new JsonObject();
            report.addProperty("runId", run.runId);
        }
        report.addProperty("passed", false);
        report.addProperty("status", "stale");
        report.addProperty("stale", true);
        report.addProperty("staleReason", "t4_registry_replaced_by_patch_planning");
        report.addProperty("staleAt", Instant.now().toString());
        report.addProperty("currentRegistryId", run.registry.registryId);
        report.addProperty("currentCitySeedCount", run.registry.citySeeds.size());
        writeJson(path, report);
        run.artifacts.put("acceptanceReport", "acceptance_report.json");
    }

    private void exportCandidateMap(RealmRun run, CandidatePackage pack) throws IOException {
        Path path = run.runDirectory.resolve(pack.candidateMapImage);
        Files.createDirectories(path.getParent());
        Set<String> allowed = pack.allowedPatches;
        exportWorldPreview(run, path, true, cell -> allowed.contains(cell.patchId));
    }

    private void exportWorldPreview(RealmRun run, Path path, boolean grid, CellPredicate highlight) throws IOException {
        GridBounds bounds = GridBounds.from(run.worldCells);
        int scale = Math.max(8, Math.min(24, 512 / Math.max(1, Math.max(bounds.width(), bounds.height()))));
        BufferedImage image = new BufferedImage(Math.max(1, bounds.width() * scale),
                Math.max(1, bounds.height() * scale), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new Color(20, 22, 28));
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            for (WorldCell cell : run.worldCells) {
                int x = (cell.gridX - bounds.minX) * scale;
                int z = (cell.gridZ - bounds.minZ) * scale;
                Color color = colorForCell(cell);
                if (highlight != null && !highlight.test(cell)) {
                    color = new Color(color.getRed() / 4, color.getGreen() / 4, color.getBlue() / 4);
                }
                g.setColor(color);
                g.fillRect(x, z, scale, scale);
            }
            if (grid) {
                g.setColor(new Color(255, 255, 255, 80));
                g.setStroke(new BasicStroke(1f));
                for (int x = 0; x <= bounds.width(); x++) {
                    g.drawLine(x * scale, 0, x * scale, image.getHeight());
                }
                for (int z = 0; z <= bounds.height(); z++) {
                    g.drawLine(0, z * scale, image.getWidth(), z * scale);
                }
            }
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
    }

    private void exportTerritoryPreview(RealmRun run, Path path) throws IOException {
        GridBounds bounds = GridBounds.from(run.worldCells);
        int scale = Math.max(8, Math.min(24, 512 / Math.max(1, Math.max(bounds.width(), bounds.height()))));
        BufferedImage image = new BufferedImage(Math.max(1, bounds.width() * scale),
                Math.max(1, bounds.height() * scale), BufferedImage.TYPE_INT_ARGB);
        Map<String, Color> colors = realmColors(run.profiles);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new Color(16, 18, 22));
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            for (TerritoryCell territoryCell : run.territory.cells) {
                int x = (territoryCell.gridX - bounds.minX) * scale;
                int z = (territoryCell.gridZ - bounds.minZ) * scale;
                g.setColor(territoryPreviewColor(territoryCell, colors));
                g.fillRect(x, z, scale, scale);
            }
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
    }

    private static Color territoryPreviewColor(TerritoryCell territoryCell, Map<String, Color> realmColors) {
        if ("owned".equals(territoryCell.status)) {
            return realmColors.getOrDefault(territoryCell.realmId, Color.GRAY);
        }
        return switch (territoryCell.status) {
            case "contested" -> new Color(236, 196, 73);
            case "blocked" -> new Color(52, 56, 64);
            case "unreachable" -> new Color(78, 99, 132);
            default -> new Color(74, 112, 79);
        };
    }

    private void exportRealmCityCandidateMaps(RealmRun run) throws IOException {
        if (run.registry == null || run.territory == null) {
            return;
        }
        Path directory = run.runDirectory.resolve("city_candidates");
        Files.createDirectories(directory);
        for (RealmProfile profile : run.profiles) {
            exportRealmCityCandidateMap(run, profile.realmId,
                    directory.resolve(profile.realmId + "_city_candidate_map.png"));
        }
    }

    private void exportRealmCityCandidateMap(RealmRun run, String realmId, Path path) throws IOException {
        GridBounds bounds = GridBounds.fromTerritory(run.territory.cells, realmId, 2);
        int scale = Math.max(10, Math.min(28, 512 / Math.max(1, Math.max(bounds.width(), bounds.height()))));
        BufferedImage image = new BufferedImage(Math.max(1, bounds.width() * scale),
                Math.max(1, bounds.height() * scale), BufferedImage.TYPE_INT_ARGB);
        Map<String, Color> colors = realmColors(run.profiles);
        Color realmColor = colors.getOrDefault(realmId, new Color(160, 160, 160));
        Map<String, TerritoryCell> territoryByKey = run.territory.cells.stream()
                .collect(Collectors.toMap(cell -> key(cell.gridX, cell.gridZ), cell -> cell, (left, right) -> left));
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new Color(18, 20, 24));
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            for (int z = bounds.minZ; z <= bounds.maxZ; z++) {
                for (int x = bounds.minX; x <= bounds.maxX; x++) {
                    TerritoryCell territoryCell = territoryByKey.get(key(x, z));
                    if (territoryCell == null) {
                        continue;
                    }
                    int px = (x - bounds.minX) * scale;
                    int pz = (z - bounds.minZ) * scale;
                    if (realmId.equals(territoryCell.realmId) && "owned".equals(territoryCell.status)) {
                        WorldCell worldCell = run.worldCellsByKey.get(key(x, z));
                        Color terrain = worldCell == null ? realmColor : colorForCell(worldCell);
                        g.setColor(blend(terrain, realmColor, 0.30));
                        g.fillRect(px, pz, scale, scale);
                    } else if ("owned".equals(territoryCell.status)) {
                        g.setColor(new Color(42, 44, 48));
                        g.fillRect(px, pz, scale, scale);
                    } else if ("contested".equals(territoryCell.status)) {
                        g.setColor(new Color(88, 72, 34));
                        g.fillRect(px, pz, scale, scale);
                    }
                }
            }
            g.setColor(new Color(255, 255, 255, 52));
            g.setStroke(new BasicStroke(1f));
            for (int x = 0; x <= bounds.width(); x++) {
                g.drawLine(x * scale, 0, x * scale, image.getHeight());
            }
            for (int z = 0; z <= bounds.height(); z++) {
                g.drawLine(0, z * scale, image.getWidth(), z * scale);
            }
            for (CitySeed seed : run.registry.citySeeds) {
                if (!realmId.equals(seed.realmId) || !bounds.contains(seed.anchorGrid.x, seed.anchorGrid.z)) {
                    continue;
                }
                int x = (seed.anchorGrid.x - bounds.minX) * scale + scale / 2;
                int z = (seed.anchorGrid.z - bounds.minZ) * scale + scale / 2;
                int radius = "capital".equals(seed.role) ? 5 : 4;
                g.setColor("capital".equals(seed.role) ? new Color(255, 245, 180) : Color.WHITE);
                g.fillOval(x - radius, z - radius, radius * 2, radius * 2);
                g.setColor(new Color(16, 18, 22, 190));
                g.drawOval(x - radius, z - radius, radius * 2, radius * 2);
            }
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
    }

    private static Color blend(Color base, Color overlay, double overlayWeight) {
        double weight = clamp(overlayWeight, 0.0, 1.0);
        double baseWeight = 1.0 - weight;
        return new Color(
                (int) Math.round(base.getRed() * baseWeight + overlay.getRed() * weight),
                (int) Math.round(base.getGreen() * baseWeight + overlay.getGreen() * weight),
                (int) Math.round(base.getBlue() * baseWeight + overlay.getBlue() * weight)
        );
    }

    private void exportCitySeedPreview(RealmRun run, Path path) throws IOException {
        exportTerritoryPreview(run, path);
        BufferedImage image = ImageIO.read(path.toFile());
        GridBounds bounds = GridBounds.from(run.worldCells);
        int scale = image.getWidth() / Math.max(1, bounds.width());
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            for (CitySeed seed : run.registry.citySeeds) {
                int x = (seed.anchorGrid.x - bounds.minX) * scale + scale / 2;
                int z = (seed.anchorGrid.z - bounds.minZ) * scale + scale / 2;
                g.fillOval(x - 3, z - 3, 6, 6);
            }
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
    }

    private JsonObject acceptanceReport(RealmRun run, long durationMs) {
        JsonObject report = new JsonObject();
        report.addProperty("caseId", "realm_v1_2_quality_acceptance");
        report.addProperty("runId", run.runId);
        report.addProperty("durationMs", durationMs);
        JsonObject scoreManifest = run.scoreManifest == null ? scoreManifest(run) : run.scoreManifest;
        boolean structurallyPassed = run.registry != null && run.territory != null && run.registry.citySeeds.stream()
                .anyMatch(seed -> "capital".equals(seed.role));
        boolean passed = structurallyPassed && scoreManifest.get("passed").getAsBoolean();
        report.addProperty("passed", passed);
        report.add("stageResults", stageResults(run));
        report.add("artifacts", run.artifactsJson());
        report.add("surveyStats", surveyStatsJson(run.surveyResult));
        report.add("scanBounds", scanBoundsJson(run.surveyResult));
        report.add("coordinateChecks", coordinateChecks(run));
        report.add("ratioChecks", ratioChecks(run));
        report.add("scoreManifest", scoreManifest);
        JsonArray visualChecks = new JsonArray();
        for (String key : List.of("worldPatchPreview", "gridOverlayPreview", "territoryPreview", "citySeedPreview")) {
            if (run.artifacts.containsKey(key)) {
                visualChecks.add(run.artifacts.get(key));
            }
        }
        report.add("visualChecks", visualChecks);
        JsonArray failures = new JsonArray();
        if (!structurallyPassed) {
            failures.add("W/T acceptance did not reach CitySeedRegistry with a capital city.");
        }
        for (JsonElement block : scoreManifest.getAsJsonArray("hardBlocks")) {
            failures.add(block.getAsString());
        }
        report.add("failures", failures);
        return report;
    }

    private void exportScoreManifest(RealmRun run) throws IOException {
        JsonObject score = scoreManifest(run);
        run.scoreManifest = score;
        writeJson(run.runDirectory.resolve("score_manifest.json"), score);
        run.artifacts.put("scoreManifest", "score_manifest.json");
    }

    private JsonObject scoreManifest(RealmRun run) {
        JsonArray hardBlocks = new JsonArray();
        JsonArray warnings = new JsonArray();
        JsonObject subScores = new JsonObject();

        JsonObject wScore = wQualityScore(run, warnings);
        JsonObject t3Score = t3QualityScore(run, hardBlocks);
        JsonObject t4Score = t4QualityScore(run, hardBlocks);
        subScores.add("W", wScore);
        subScores.add("T3", t3Score);
        subScores.add("T4", t4Score);

        double totalScore = wScore.get("score").getAsDouble() * 0.25
                + t3Score.get("score").getAsDouble() * 0.45
                + t4Score.get("score").getAsDouble() * 0.30;
        boolean passed = hardBlocks.size() == 0 && totalScore >= 70.0;

        JsonObject manifest = new JsonObject();
        manifest.addProperty("schemaVersion", SCHEMA_VERSION);
        manifest.addProperty("runId", run.runId);
        manifest.addProperty("surveyId", run.surveyResult.surveyId());
        manifest.addProperty("qualityMode", run.qualityMode);
        manifest.addProperty("expansionModel", run.expansionModel);
        manifest.addProperty("totalScore", Math.round(totalScore * 100.0) / 100.0);
        manifest.addProperty("passed", passed);
        manifest.add("subScores", subScores);
        manifest.add("hardBlocks", hardBlocks);
        manifest.add("warnings", warnings);
        manifest.add("manualReviewChecklist", arrayOf(
                "检查 world_patch_preview.png 中 cliff 是否只作为陡坡标签而非主大陆色块。",
                "检查 territory_preview.png 中每个非海洋国度是否连成主块，边界是否可读。",
                "逐国查看 realm_city_candidate_packages.json，确认城市候选点覆盖首都、港口、矿业、边境功能。"));
        JsonArray previews = new JsonArray();
        for (String key : List.of("worldPatchPreview", "gridOverlayPreview", "territoryPreview", "citySeedPreview")) {
            if (run.artifacts.containsKey(key)) {
                previews.add(run.artifacts.get(key));
            }
        }
        manifest.add("previewSet", previews);
        manifest.addProperty("createdAt", Instant.now().toString());
        return manifest;
    }

    private JsonObject wQualityScore(RealmRun run, JsonArray warnings) {
        long assignable = run.worldCells.stream().filter(WorldCell::assignableLand).count();
        long cliff = run.worldCells.stream().filter(cell -> cell.assignableLand() && cell.landformTags().contains("cliff")).count();
        long steep = run.worldCells.stream().filter(cell -> cell.assignableLand() && cell.landformTags().contains("steep")).count();
        long coarseCliff = run.worldCells.stream().filter(cell -> cell.assignableLand() && "cliff".equals(cell.landform)).count();
        long microContradiction = run.worldCells.stream()
                .filter(cell -> cell.assignableLand() && cell.landformTags().contains("micro_contradiction")).count();
        double cliffRatio = assignable == 0 ? 0.0 : cliff / (double) assignable;
        double steepTagRatio = assignable == 0 ? 0.0 : steep / (double) assignable;
        double coarseCliffCandidateRatio = assignable == 0 ? 0.0 : coarseCliff / (double) assignable;
        double microContradictionRatio = assignable == 0 ? 0.0 : microContradiction / (double) assignable;
        long singletonPatches = run.patchSummaries.values().stream().filter(patch -> patch.areaCells <= 1).count();
        double singletonPatchRatio = run.patchSummaries.isEmpty() ? 0.0 : singletonPatches / (double) run.patchSummaries.size();
        double score = 100.0;
        score -= Math.min(25.0, cliffRatio * 80.0);
        score -= Math.min(20.0, singletonPatchRatio * 30.0);
        if (!run.surveyResult.microSamplingImplemented()) {
            warnings.add("W micro-sampling is represented as quality metadata in this build; detailed 16/32 block sub-sampling is pending GIS sampler work.");
        }
        JsonObject json = new JsonObject();
        json.addProperty("score", Math.round(Math.max(0.0, score) * 100.0) / 100.0);
        json.addProperty("cellStepBlocks", run.surveyResult.cellStepBlocks());
        json.addProperty("microSampleStrideBlocks", run.surveyResult.microSampleStrideBlocks());
        json.addProperty("metricSampleStrideBlocks", run.surveyResult.microSampleStrideBlocks());
        json.addProperty("localSlopeRadiusBlocks", run.surveyResult.localSlopeRadiusBlocks());
        json.add("metricScales", metricScalesJson());
        json.addProperty("microSamplingImplemented", run.surveyResult.microSamplingImplemented());
        json.addProperty("microSampleCount", run.surveyResult.microSampleCount());
        json.addProperty("cliffRatio", cliffRatio);
        json.addProperty("cliffTagRatio", cliffRatio);
        json.addProperty("steepTagRatio", steepTagRatio);
        json.addProperty("coarseCliffCandidateRatio", coarseCliffCandidateRatio);
        json.addProperty("microContradictionRatio", microContradictionRatio);
        json.add("baseLandformDistribution", distribution(run.worldCells.stream()
                .filter(WorldCell::assignableLand)
                .map(WorldCell::baseLandform)
                .toList()));
        json.add("landformTagDistribution", tagDistribution(run.worldCells.stream()
                .filter(WorldCell::assignableLand)
                .toList()));
        json.addProperty("singletonPatchRatio", singletonPatchRatio);
        return json;
    }

    private static JsonObject metricScalesJson() {
        JsonObject json = new JsonObject();
        json.addProperty("scanHeightRank", "scan_bounds");
        json.addProperty("localScaleBlocks", LOCAL_TERRAIN_SCALE_BLOCKS);
        json.addProperty("regionalScaleBlocks", REGIONAL_TERRAIN_SCALE_BLOCKS);
        json.addProperty("plateauCoreScaleBlocks", PLATEAU_CORE_SCALE_BLOCKS);
        json.addProperty("plateauOuterScaleBlocks", PLATEAU_OUTER_SCALE_BLOCKS);
        json.addProperty("plateauProminenceThreshold", PLATEAU_PROMINENCE_THRESHOLD);
        json.addProperty("plateauCoreFlatSupportThreshold", PLATEAU_CORE_FLAT_SUPPORT_THRESHOLD);
        json.addProperty("plateauRegionalHeightRankThreshold", PLATEAU_REGIONAL_HEIGHT_RANK_THRESHOLD);
        json.addProperty("rankDriftThreshold", TERRAIN_RANK_DRIFT_THRESHOLD);
        json.addProperty("geomorphonApproximation", "eight_direction_height_pattern");
        return json;
    }

    private JsonObject t3QualityScore(RealmRun run, JsonArray hardBlocks) {
        JsonObject json = new JsonObject();
        if (run.territory == null) {
            hardBlocks.add("T3 territory map is missing.");
            json.addProperty("score", 0.0);
            return json;
        }
        double minLargest = 1.0;
        double maxDetached = 0.0;
        double maxAreaDeviation = 0.0;
        double avgBoundary = 0.0;
        double avgBudget = 0.0;
        double avgTerrainIdentity = 0.0;
        double maxOverExpansion = 0.0;
        int count = 0;
        JsonArray badRealms = new JsonArray();
        for (RealmProfile profile : run.profiles) {
            if (!run.territory.stats.containsKey(profile.realmId)) {
                String message = "T3 territory hard block: realm has no owned territory cells: " + profile.realmId;
                hardBlocks.add(message);
                badRealms.add(profile.realmId);
            }
        }
        for (RealmStats stats : run.territory.stats.values()) {
            minLargest = Math.min(minLargest, stats.largestComponentRatio);
            maxDetached = Math.max(maxDetached, stats.detachedAreaRatio);
            double areaDeviation = stats.targetAreaCells <= 0 ? 0.0
                    : Math.abs(stats.areaCells - stats.targetAreaCells) / (double) stats.targetAreaCells;
            maxAreaDeviation = Math.max(maxAreaDeviation, areaDeviation);
            avgBoundary += stats.naturalBoundaryFit;
            avgBudget += Math.min(1.0, stats.budgetUsedRatio);
            avgTerrainIdentity += terrainIdentityScore(run, stats.realmId);
            maxOverExpansion = Math.max(maxOverExpansion, Math.max(0.0, stats.budgetUsedRatio - 1.0));
            count++;
            if (stats.largestComponentRatio < STRICT_LARGEST_COMPONENT_RATIO
                    || stats.detachedAreaRatio > STRICT_DETACHED_AREA_RATIO) {
                String message = "T3 topology hard block: " + stats.realmId
                        + " largestComponentRatio=" + round(stats.largestComponentRatio)
                        + ", detachedAreaRatio=" + round(stats.detachedAreaRatio);
                hardBlocks.add(message);
                badRealms.add(stats.realmId);
            }
            if ("strict".equals(run.qualityMode)
                    && !"action_budget".equals(run.expansionModel)
                    && (stats.actualAreaRatio < stats.scaleMinAreaRatio || stats.actualAreaRatio > stats.scaleMaxAreaRatio)) {
                String message = "T3 area quota hard block: " + stats.realmId
                        + " areaCells=" + stats.areaCells
                        + ", targetAreaCells=" + stats.targetAreaCells
                        + ", actualAreaRatio=" + round(stats.actualAreaRatio)
                        + ", allowed=[" + round(stats.scaleMinAreaRatio) + "," + round(stats.scaleMaxAreaRatio) + "]";
                hardBlocks.add(message);
                badRealms.add(stats.realmId);
            }
            if ("strict".equals(run.qualityMode) && "action_budget".equals(run.expansionModel)) {
                int minPlayableCells = Math.min(
                        Math.max(ACTION_BUDGET_STRICT_MIN_OWNED_CELLS,
                                (int) Math.ceil(stats.targetAreaCells * ACTION_BUDGET_STRICT_MIN_OWNED_RATIO)),
                        Math.max(1, (int) Math.floor(stats.targetAreaCells * 0.50)));
                if (stats.areaCells < minPlayableCells) {
                    String message = "T3 action budget hard block: " + stats.realmId
                            + " ownedCells=" + stats.areaCells
                            + ", minimumPlayableCells=" + minPlayableCells;
                    hardBlocks.add(message);
                    badRealms.add(stats.realmId);
                }
            }
        }
        avgBoundary = count == 0 ? 0.0 : avgBoundary / count;
        avgBudget = count == 0 ? 0.0 : avgBudget / count;
        avgTerrainIdentity = count == 0 ? 0.0 : avgTerrainIdentity / count;
        JsonObject status = run.territory.statusSummaryJson();
        double wildlandRatio = status.get("wildRatio").getAsDouble();
        double contestedRatio = status.get("contestedRatio").getAsDouble();
        double blockedRatio = status.get("blockedRatio").getAsDouble();
        double ownedAreaRatio = status.get("ownedRatio").getAsDouble();
        double wildlandScore = "action_budget".equals(run.expansionModel)
                ? 100.0 - Math.abs(wildlandRatio - 0.18) * 180.0 : 70.0;
        double contestedScore = 100.0 - Math.min(100.0, contestedRatio * 240.0);
        double budgetCoherenceScore = 100.0 - Math.abs(0.82 - avgBudget) * 80.0 - maxOverExpansion * 120.0;
        double terrainIdentityScore = avgTerrainIdentity * 100.0;
        double score = 100.0;
        score -= Math.max(0.0, STRICT_LARGEST_COMPONENT_RATIO - minLargest) * 160.0;
        score -= Math.max(0.0, maxDetached - STRICT_DETACHED_AREA_RATIO) * 240.0;
        if (!"action_budget".equals(run.expansionModel)) {
            score -= Math.max(0.0, maxAreaDeviation - 0.15) * 120.0;
        }
        score -= Math.max(0.0, 0.25 - avgBoundary) * 40.0;
        if ("action_budget".equals(run.expansionModel)) {
            score = score * 0.45 + budgetCoherenceScore * 0.20 + terrainIdentityScore * 0.20
                    + wildlandScore * 0.10 + contestedScore * 0.05;
        }
        json.addProperty("score", Math.round(Math.max(0.0, score) * 100.0) / 100.0);
        json.addProperty("expansionModel", run.expansionModel);
        json.addProperty("minLargestComponentRatio", minLargest);
        json.addProperty("maxDetachedAreaRatio", maxDetached);
        json.addProperty("maxAreaQuotaDeviation", maxAreaDeviation);
        json.addProperty("avgNaturalBoundaryFit", avgBoundary);
        json.addProperty("budgetCoherenceScore", Math.round(Math.max(0.0, budgetCoherenceScore) * 100.0) / 100.0);
        json.addProperty("terrainIdentityScore", Math.round(Math.max(0.0, terrainIdentityScore) * 100.0) / 100.0);
        json.addProperty("wildlandScore", Math.round(Math.max(0.0, wildlandScore) * 100.0) / 100.0);
        json.addProperty("contestedReasonabilityScore", Math.round(Math.max(0.0, contestedScore) * 100.0) / 100.0);
        json.addProperty("overExpansionPenalty", maxOverExpansion);
        json.addProperty("wildlandRatio", wildlandRatio);
        json.addProperty("contestedRatio", contestedRatio);
        json.addProperty("blockedRatio", blockedRatio);
        json.addProperty("ownedAreaRatio", ownedAreaRatio);
        json.addProperty("repairCount", run.territory.repairs.size());
        json.add("badRealms", badRealms);
        return json;
    }

    private JsonObject t4QualityScore(RealmRun run, JsonArray hardBlocks) {
        JsonObject json = new JsonObject();
        if (run.registry == null) {
            hardBlocks.add("T4 city seed registry is missing.");
            json.addProperty("score", 0.0);
            return json;
        }
        int duplicateAnchors = duplicateAnchorCount(run);
        int spacingViolations = citySpacingViolationCount(run);
        int offTerritoryAnchors = offTerritoryAnchorCount(run);
        long capitals = run.registry.citySeeds.stream()
                .filter(seed -> currentRunRealm(run, seed.realmId))
                .filter(seed -> "capital".equals(seed.role))
                .count();
        if (duplicateAnchors > 0) {
            hardBlocks.add("T4 city anchor hard block: duplicate non-satellite anchors=" + duplicateAnchors);
        }
        if (spacingViolations > 0) {
            hardBlocks.add("T4 city spacing hard block: overlapping non-satellite city seeds=" + spacingViolations);
        }
        if (offTerritoryAnchors > 0) {
            hardBlocks.add("T4 city territory hard block: city seeds outside owned territory=" + offTerritoryAnchors);
        }
        if (capitals < run.profiles.size()) {
            hardBlocks.add("T4 city registry hard block: not every realm has a capital city seed.");
        }
        double score = 100.0 - duplicateAnchors * 20.0 - spacingViolations * 15.0
                - offTerritoryAnchors * 25.0 - Math.max(0, run.profiles.size() - capitals) * 25.0;
        json.addProperty("score", Math.round(Math.max(0.0, score) * 100.0) / 100.0);
        json.addProperty("citySeedCount", run.registry.citySeeds.size());
        json.addProperty("capitalCount", capitals);
        json.addProperty("duplicateAnchorCount", duplicateAnchors);
        json.addProperty("spacingViolationCount", spacingViolations);
        json.addProperty("offTerritoryAnchorCount", offTerritoryAnchors);
        json.addProperty("allAnchorsInOwnedTerritory", offTerritoryAnchors == 0);
        return json;
    }

    private double terrainIdentityScore(RealmRun run, String realmId) {
        if (run.territory == null) {
            return 0.0;
        }
        RealmProfile profile = run.profile(realmId);
        int owned = 0;
        double fit = 0.0;
        for (TerritoryCell territoryCell : run.territory.cells) {
            if (!"owned".equals(territoryCell.status) || !realmId.equals(territoryCell.realmId)) {
                continue;
            }
            WorldCell cell = run.worldCellsByKey.get(key(territoryCell.gridX, territoryCell.gridZ));
            if (cell == null) {
                continue;
            }
            owned++;
            fit += terrainFit(profile, cell);
        }
        return owned == 0 ? 0.0 : clamp(fit / owned, 0.0, 1.0);
    }

    private double terrainFit(RealmProfile profile, WorldCell cell) {
        double score = 0.55;
        if (profile.landformPreferences.contains(cell.landform) || profile.landformPreferences.contains(cell.baseLandform())) {
            score += 0.20;
        }
        if (profile.avoidLandforms.contains(cell.landform) || profile.avoidLandforms.contains(cell.baseLandform())) {
            score -= 0.25;
        }
        boolean mountain = "ridge".equals(cell.baseLandform()) || "upland".equals(cell.baseLandform())
                || "plateau".equals(cell.baseLandform())
                || cell.landformTags().contains("mountain_front");
        if (mountain) {
            score += profile.expansionStyle.mountainAffinity * 0.20;
        }
        if ("shore".equals(cell.baseLandform()) || cell.landformTags().contains("seacoast")) {
            score += profile.expansionStyle.coastalBias * 0.16;
        }
        if (cell.waterDistanceBlocks <= 512.0 || cell.landformTags().contains("riverbank")
                || cell.landformTags().contains("lakeshore")) {
            score += profile.expansionStyle.waterAffinity * 0.08;
        }
        if (cell.landformTags().contains("steep") && profile.expansionStyle.mountainAffinity < 0.0) {
            score -= 0.15;
        }
        return clamp(score, 0.0, 1.0);
    }

    private JsonObject t4ReportJson(RealmRun run) {
        JsonObject json = new JsonObject();
        json.addProperty("registryId", run.registry.registryId);
        json.addProperty("citySeedCount", run.registry.citySeeds.size());
        long capitals = run.registry.citySeeds.stream().filter(seed -> "capital".equals(seed.role)).count();
        json.addProperty("capitalCount", capitals);
        long uniqueIds = run.registry.citySeeds.stream().map(seed -> seed.citySeedId).distinct().count();
        json.addProperty("uniqueCitySeedIds", uniqueIds);
        json.addProperty("allCitySeedIdsUnique", uniqueIds == run.registry.citySeeds.size());
        json.addProperty("duplicateAnchorCount", duplicateAnchorCount(run));
        json.addProperty("spacingViolationCount", citySpacingViolationCount(run));
        int offTerritoryAnchors = offTerritoryAnchorCount(run);
        json.addProperty("offTerritoryAnchorCount", offTerritoryAnchors);
        json.addProperty("allAnchorsInOwnedTerritory", offTerritoryAnchors == 0);
        return json;
    }

    private int offTerritoryAnchorCount(RealmRun run) {
        if (run.registry == null || run.territory == null) {
            return 0;
        }
        Map<String, String> owners = run.territory.ownershipByKey();
        int violations = 0;
        for (CitySeed seed : run.registry.citySeeds) {
            if (!currentRunRealm(run, seed.realmId)) {
                continue;
            }
            String owner = owners.get(key(seed.anchorGrid.x, seed.anchorGrid.z));
            if (!seed.realmId.equals(owner)) {
                violations++;
            }
        }
        return violations;
    }

    private int citySpacingViolationCount(RealmRun run) {
        if (run.registry == null) {
            return 0;
        }
        int violations = 0;
        for (int i = 0; i < run.registry.citySeeds.size(); i++) {
            CitySeed left = run.registry.citySeeds.get(i);
            if (!currentRunRealm(run, left.realmId) || !left.satelliteOf.isBlank()) {
                continue;
            }
            for (int j = i + 1; j < run.registry.citySeeds.size(); j++) {
                CitySeed right = run.registry.citySeeds.get(j);
                if (!left.realmId.equals(right.realmId) || !right.satelliteOf.isBlank()) {
                    continue;
                }
                int required = left.planningRadiusCells + right.planningRadiusCells;
                double distance = graphDistanceWithinRealm(run, left.realmId, left.anchorGrid, right.anchorGrid,
                        Math.max(64, required + 16));
                if (!Double.isFinite(distance)) {
                    distance = distanceCells(left.anchorGrid.x, left.anchorGrid.z, right.anchorGrid.x, right.anchorGrid.z);
                }
                if (distance < required) {
                    violations++;
                }
            }
        }
        return violations;
    }

    private int duplicateAnchorCount(RealmRun run) {
        if (run.registry == null) {
            return 0;
        }
        Map<String, Integer> anchors = new HashMap<>();
        int duplicates = 0;
        for (CitySeed seed : run.registry.citySeeds) {
            if (!currentRunRealm(run, seed.realmId) || !seed.satelliteOf.isBlank()) {
                continue;
            }
            String anchor = seed.realmId + ":" + seed.anchorGrid.x + "," + seed.anchorGrid.z;
            int count = anchors.getOrDefault(anchor, 0) + 1;
            anchors.put(anchor, count);
            if (count == 2) {
                duplicates++;
            }
        }
        return duplicates;
    }

    private boolean currentRunRealm(RealmRun run, String realmId) {
        return run.profiles.stream().anyMatch(profile -> profile.realmId.equals(realmId));
    }

    private JsonArray cityCandidatePackagesJson(RealmRun run) {
        JsonArray packages = new JsonArray();
        if (run.registry == null || run.territory == null) {
            return packages;
        }
        for (RealmProfile profile : run.profiles) {
            JsonObject pack = new JsonObject();
            pack.addProperty("packageId", "city_candidates_" + profile.realmId);
            pack.addProperty("realmId", profile.realmId);
            pack.addProperty("surveyId", run.surveyResult.surveyId());
            pack.addProperty("territoryMapId", run.territory.territoryMapId);
            String candidateMapImage = "city_candidates/" + profile.realmId + "_city_candidate_map.png";
            pack.addProperty("candidateMapImage", candidateMapImage);
            pack.addProperty("mapScope", "realm_owned_territory");
            pack.addProperty("contextPolicy", "crop_to_realm_owned_bounds_with_small_neighbor_context");
            pack.add("mapBounds", GridBounds.fromTerritory(run.territory.cells, profile.realmId, 2).asJson());
            JsonObject legend = new JsonObject();
            legend.addProperty("coordinateFormat", "gridX,gridZ");
            legend.addProperty("cellStepBlocks", run.surveyResult.cellStepBlocks());
            legend.add("originBlock", new GridPoint(run.surveyResult.gridOriginBlockX(),
                    run.surveyResult.gridOriginBlockZ()).asJson());
            pack.add("gridLegend", legend);
            JsonArray selected = new JsonArray();
            for (CitySeed seed : run.registry.citySeeds) {
                if (profile.realmId.equals(seed.realmId)) {
                    selected.add(seed.asJson());
                }
            }
            pack.add("selectedCitySeeds", selected);
            JsonObject rules = new JsonObject();
            rules.addProperty("spacingRule", "non-satellite distance must be >= sum(planningRadiusCells)");
            rules.addProperty("chunkPregenerationRule", "city exists only at declared anchor/candidate ids; chunk order must not create new finite cities");
            pack.add("rules", rules);
            pack.add("rolePools", rolePoolSummary(run, profile.realmId));
            packages.add(pack);
        }
        return packages;
    }

    private JsonArray rolePoolSummary(RealmRun run, String realmId) {
        JsonArray pools = new JsonArray();
        pools.add(rolePool(run, realmId, "port", cell -> "shore".equals(cell.landWater)));
        pools.add(rolePool(run, realmId, "mining_town",
                cell -> cell.landform.equals("ridge") || cell.landform.equals("slope") || cell.landform.equals("cliff")));
        pools.add(rolePool(run, realmId, "border_fort", cell -> isBorderCell(run, realmId, cell)));
        return pools;
    }

    private JsonObject rolePool(RealmRun run, String realmId, String role, CellPredicate predicate) {
        JsonObject json = new JsonObject();
        json.addProperty("role", role);
        int count = 0;
        JsonArray examples = new JsonArray();
        if (run.territory != null) {
            for (TerritoryCell territoryCell : run.territory.cells) {
                if (!realmId.equals(territoryCell.realmId)) {
                    continue;
                }
                WorldCell cell = run.worldCellsByKey.get(key(territoryCell.gridX, territoryCell.gridZ));
                if (cell == null || !predicate.test(cell)) {
                    continue;
                }
                count++;
                if (examples.size() < 8) {
                    JsonObject example = new JsonObject();
                    example.addProperty("candidateId", role + "_" + realmId + "_" + cell.gridX + "_" + cell.gridZ);
                    example.add("grid", new GridPoint(cell.gridX, cell.gridZ).asGridJson());
                    example.addProperty("subregionId", subregionIdFor(run, realmId, cell));
                    examples.add(example);
                }
            }
        }
        json.addProperty("candidateCount", count);
        json.add("examples", examples);
        return json;
    }

    private JsonObject stageResults(RealmRun run) {
        JsonObject stages = new JsonObject();
        stages.addProperty("W", !run.worldCells.isEmpty());
        stages.addProperty("T1", !run.profiles.isEmpty() && !run.candidatePackages.isEmpty());
        stages.addProperty("T2", run.seeds.size() == run.profiles.size());
        stages.addProperty("T3", run.territory != null);
        stages.addProperty("T4", run.registry != null);
        return stages;
    }

    private JsonObject coordinateChecks(RealmRun run) {
        JsonObject checks = new JsonObject();
        checks.addProperty("selectionCount", run.selections.size());
        checks.addProperty("seedCount", run.seeds.size());
        checks.addProperty("allAccepted", run.seeds.size() == run.profiles.size());
        return checks;
    }

    private JsonObject ratioChecks(RealmRun run) {
        JsonObject checks = new JsonObject();
        if (run.territory != null) {
            checks.add("normalizedScales", run.territory.scalesJson());
        }
        return checks;
    }

    private JsonObject surveyJson(RealmRun run) {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", SCHEMA_VERSION);
        json.addProperty("surveyId", run.surveyResult.surveyId());
        json.addProperty("dimensionId", run.surveyResult.dimensionId());
        json.addProperty("worldSeed", run.surveyResult.worldSeed());
        json.addProperty("worldBorderSizeBlocks", run.surveyResult.worldBorderSizeBlocks());
        json.addProperty("cellStepBlocks", run.surveyResult.cellStepBlocks());
        json.addProperty("microSampleStrideBlocks", run.surveyResult.microSampleStrideBlocks());
        json.addProperty("metricSampleStrideBlocks", run.surveyResult.microSampleStrideBlocks());
        json.addProperty("localSlopeRadiusBlocks", run.surveyResult.localSlopeRadiusBlocks());
        json.addProperty("microSamplingImplemented", run.surveyResult.microSamplingImplemented());
        json.addProperty("microSampleBudget", microSampleBudget(run.surveyResult));
        json.addProperty("microSampleBudgetPerCell", microSampleBudgetPerCell(run.surveyResult));
        json.addProperty("microSampleCount", run.surveyResult.microSampleCount());
        json.addProperty("adaptiveSampling", false);
        json.addProperty("configHash", run.surveyResult.configHash());
        json.addProperty("sealed", run.surveyResult.sealed());
        JsonObject origin = new JsonObject();
        origin.addProperty("x", run.surveyResult.gridOriginBlockX());
        origin.addProperty("z", run.surveyResult.gridOriginBlockZ());
        json.add("gridOriginBlock", origin);
        JsonObject size = new JsonObject();
        size.addProperty("width", run.surveyResult.gridSizeWidth());
        size.addProperty("height", run.surveyResult.gridSizeHeight());
        size.addProperty("cellCount", run.surveyResult.gridSizeWidth() * run.surveyResult.gridSizeHeight());
        json.add("gridSize", size);
        json.add("scanBounds", scanBoundsJson(run.surveyResult));
        json.add("surveyStats", surveyStatsJson(run.surveyResult));
        JsonObject source = new JsonObject();
        source.addProperty("sampleMode", run.surveyResult.sampleMode().contractName());
        source.addProperty("sourceType", run.surveyResult.tileCount() == 1 ? "single_region_refresh" : "world_survey_tiles");
        source.addProperty("microSamplingImplemented", run.surveyResult.microSamplingImplemented());
        if (Files.exists(run.surveyResult.manifestPath())) {
            source.addProperty("worldSurveyManifest", run.surveyResult.manifestPath().toAbsolutePath().toString());
        }
        json.add("source", source);
        json.addProperty("createdAt", Instant.now().toString());
        if (run.worldTheme != null) {
            json.add("worldTheme", run.worldTheme);
        }
        return json;
    }

    private JsonObject worldPatchMapJson(RealmRun run) {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", SCHEMA_VERSION);
        json.addProperty("surveyId", "survey_" + run.runId);
        JsonArray cells = new JsonArray();
        for (WorldCell cell : run.worldCells) {
            cells.add(cell.asJson());
        }
        json.add("cells", cells);
        json.add("patches", patchesJson(run.patchSummaries.values()));
        json.add("continents", continentsJson(run.continentSummaries.values()));
        JsonObject cleaning = new JsonObject();
        cleaning.addProperty("baseLandformField", "baseLandform");
        cleaning.addProperty("tagField", "landformTags");
        cleaning.addProperty("cliffAsTag", true);
        cleaning.addProperty("globalPatchMergeImplemented", false);
        cleaning.addProperty("microSamplingImplemented", run.surveyResult.microSamplingImplemented());
        cleaning.addProperty("microSampleCount", run.surveyResult.microSampleCount());
        json.add("cleaningSummary", cleaning);
        return json;
    }

    private JsonObject worldSummary(RealmRun run) {
        JsonObject summary = new JsonObject();
        summary.addProperty("cellCount", run.worldCells.size());
        summary.addProperty("patchCount", run.patchSummaries.size());
        summary.addProperty("continentCount", run.continentSummaries.size());
        summary.add("scanBounds", scanBoundsJson(run.surveyResult));
        summary.add("surveyStats", surveyStatsJson(run.surveyResult));
        summary.add("continents", continentsJson(run.continentSummaries.values()));
        return summary;
    }

    private static JsonObject scanBoundsJson(WorldSurveyResult result) {
        JsonObject bounds = new JsonObject();
        bounds.addProperty("centerBlockX", result.centerBlockX());
        bounds.addProperty("centerBlockZ", result.centerBlockZ());
        bounds.addProperty("planningRadiusBlocks", result.planningRadiusBlocks());
        bounds.addProperty("minBlockX", result.scanMinBlockX());
        bounds.addProperty("minBlockZ", result.scanMinBlockZ());
        bounds.addProperty("maxBlockX", result.scanMaxBlockX());
        bounds.addProperty("maxBlockZ", result.scanMaxBlockZ());
        bounds.addProperty("diameterBlocksX", result.scanMaxBlockX() - result.scanMinBlockX() + 1);
        bounds.addProperty("diameterBlocksZ", result.scanMaxBlockZ() - result.scanMinBlockZ() + 1);
        return bounds;
    }

    private static JsonObject surveyStatsJson(WorldSurveyResult result) {
        JsonObject stats = new JsonObject();
        stats.addProperty("durationMs", result.durationMs());
        stats.addProperty("tileCount", result.tileCount());
        stats.addProperty("scannedTileCount", result.scannedTileCount());
        stats.addProperty("cachedTileCount", result.cachedTileCount());
        stats.addProperty("failedTileCount", result.failedTileCount());
        stats.addProperty("artifactBytes", result.artifactBytes());
        stats.addProperty("sealed", result.sealed());
        stats.addProperty("metricSampleStrideBlocks", result.microSampleStrideBlocks());
        stats.addProperty("localSlopeRadiusBlocks", result.localSlopeRadiusBlocks());
        stats.addProperty("microSamplingImplemented", result.microSamplingImplemented());
        stats.addProperty("microSampleBudget", microSampleBudget(result));
        stats.addProperty("microSampleBudgetPerCell", microSampleBudgetPerCell(result));
        stats.addProperty("microSampleCount", result.microSampleCount());
        stats.addProperty("adaptiveSampling", false);
        stats.addProperty("configHash", result.configHash());
        return stats;
    }

    private static long microSampleBudget(WorldSurveyResult result) {
        return (long) result.gridSizeWidth() * result.gridSizeHeight() * microSampleBudgetPerCell(result);
    }

    private static int microSampleBudgetPerCell(WorldSurveyResult result) {
        if (!result.microSamplingImplemented()
                || result.cellStepBlocks() <= 0
                || result.microSampleStrideBlocks() <= 0
                || result.microSampleStrideBlocks() >= result.cellStepBlocks()) {
            return 0;
        }
        int samplesPerAxis = Math.max(1, result.cellStepBlocks() / result.microSampleStrideBlocks());
        return samplesPerAxis * samplesPerAxis;
    }

    private static JsonArray profilesJson(Iterable<RealmProfile> profiles) {
        JsonArray array = new JsonArray();
        for (RealmProfile profile : profiles) {
            array.add(profile.asJson());
        }
        return array;
    }

    private static JsonArray candidatePackagesJson(Iterable<CandidatePackage> packs) {
        JsonArray array = new JsonArray();
        for (CandidatePackage pack : packs) {
            array.add(pack.asJson());
        }
        return array;
    }

    private static JsonArray selectionsJson(Iterable<RealmSelection> selections) {
        JsonArray array = new JsonArray();
        for (RealmSelection selection : selections) {
            array.add(selection.asJson());
        }
        return array;
    }

    private static JsonArray seedsJson(Iterable<RealmSeed> seeds) {
        JsonArray array = new JsonArray();
        for (RealmSeed seed : seeds) {
            array.add(seed.asJson());
        }
        return array;
    }

    private static JsonArray capitalIntentsJson(Iterable<CapitalCityIntent> capitalIntents) {
        JsonArray array = new JsonArray();
        for (CapitalCityIntent capitalIntent : capitalIntents) {
            array.add(capitalIntent.asJson());
        }
        return array;
    }

    private static JsonArray patchesJson(Iterable<PatchSummary> patches) {
        JsonArray array = new JsonArray();
        for (PatchSummary patch : patches) {
            array.add(patch.asJson());
        }
        return array;
    }

    private static JsonArray continentsJson(Iterable<ContinentSummary> continents) {
        JsonArray array = new JsonArray();
        for (ContinentSummary continent : continents) {
            array.add(continent.asJson());
        }
        return array;
    }

    private Map<String, NormalizedScale> normalizeScales(List<RealmProfile> profiles) {
        double total = profiles.stream().mapToDouble(profile -> profile.scalePlan.targetAreaRatio).sum();
        if (total <= 0.0) {
            total = profiles.size();
        }
        Map<String, Double> clamped = new LinkedHashMap<>();
        for (RealmProfile profile : profiles) {
            double raw = total <= 0.0 ? 1.0 / profiles.size() : profile.scalePlan.targetAreaRatio / total;
            clamped.put(profile.realmId, clamp(raw, profile.scalePlan.minAreaRatio, profile.scalePlan.maxAreaRatio));
        }
        double clampedTotal = clamped.values().stream().mapToDouble(Double::doubleValue).sum();
        Map<String, NormalizedScale> result = new LinkedHashMap<>();
        for (RealmProfile profile : profiles) {
            double normalized = clampedTotal <= 0.0 ? 1.0 / profiles.size() : clamped.get(profile.realmId) / clampedTotal;
            result.put(profile.realmId, new NormalizedScale(profile.scalePlan, normalized));
        }
        return result;
    }

    private Map<String, Integer> quotas(Map<String, NormalizedScale> scales, int landCount, boolean allowUnclaimedLand) {
        Map<String, Integer> quotas = new LinkedHashMap<>();
        int assigned = 0;
        for (Map.Entry<String, NormalizedScale> entry : scales.entrySet()) {
            int quota = Math.max(1, (int) Math.floor(entry.getValue().normalizedTargetAreaRatio * landCount));
            quotas.put(entry.getKey(), quota);
            assigned += quota;
        }
        int targetTotal = allowUnclaimedLand ? Math.min(landCount, assigned) : landCount;
        List<String> ids = new ArrayList<>(quotas.keySet());
        int index = 0;
        while (assigned < targetTotal && !ids.isEmpty()) {
            String id = ids.get(index++ % ids.size());
            quotas.put(id, quotas.get(id) + 1);
            assigned++;
        }
        while (assigned > targetTotal && !ids.isEmpty()) {
            String id = ids.get(index++ % ids.size());
            if (quotas.get(id) > 1) {
                quotas.put(id, quotas.get(id) - 1);
                assigned--;
            } else if (quotas.values().stream().allMatch(value -> value <= 1)) {
                break;
            }
        }
        return quotas;
    }

    private double expansionCost(RealmProfile profile, RealmSeed seed, WorldCell cell) {
        double distance = distanceCells(seed.seedGrid.x, seed.seedGrid.z, cell.gridX, cell.gridZ);
        ExpansionStyle style = profile.expansionStyle;
        double cost = distance * (0.8 + style.compactness * 0.5);
        cost += (cell.waterDistanceBlocks / 256.0) * (0.6 - style.waterAffinity * 0.5);
        if ("shore".equals(cell.landWater)) {
            cost -= style.coastalBias * 2.0;
        }
        if (cell.landform.equals("ridge") || cell.landform.equals("slope") || cell.landform.equals("cliff")) {
            cost -= style.mountainAffinity * 1.5;
        }
        cost -= style.resourceSeeking * resourceHint(cell);
        return cost;
    }

    private double frontierMoveCost(RealmProfile profile, RealmSeed seed, WorldCell from, WorldCell to, double parentCost) {
        ExpansionStyle style = profile.expansionStyle;
        double move = 1.0 + Math.abs(to.slopeAvg - from.slopeAvg) * 1.5;
        move += Math.max(0.0, to.slopeAvg) * (0.35 - style.mountainAffinity * 0.25);
        move += Math.abs(to.heightAvg - from.heightAvg) / 80.0;
        if ("shore".equals(to.landWater)) {
            move -= style.coastalBias * 0.8;
        }
        move += (to.waterDistanceBlocks / 1024.0) * (0.6 - style.waterAffinity * 0.45);
        move += landformAffinityPenalty(profile, to);
        move -= style.resourceSeeking * resourceHint(to) * 0.25;
        double seedDistance = seed == null ? 0.0 : distanceCells(seed.seedGrid.x, seed.seedGrid.z, to.gridX, to.gridZ);
        move += seedDistance * (0.015 + style.compactness * 0.035);
        return parentCost + Math.max(0.05, move);
    }

    private ExpansionBudget expansionBudget(RealmProfile profile, int targetAreaCells, int landCellCount) {
        double priorityMultiplier = switch (profile.scalePlan.priority) {
            case "minor" -> 0.72;
            case "major" -> 1.25;
            case "empire" -> 1.65;
            default -> 1.0;
        };
        double styleMultiplier = 0.85 + profile.expansionStyle.borderPressure * 0.25
                + (1.0 - profile.expansionStyle.compactness) * 0.25;
        double base = Math.max(48.0, Math.sqrt(Math.max(1, targetAreaCells)) * 64.0);
        double effective = base * priorityMultiplier * styleMultiplier;
        double soft = effective * 0.72;
        double hard = effective;
        double maxClaim = 34.0 + priorityMultiplier * 8.0 + Math.max(0.0, profile.expansionStyle.mountainAffinity) * 5.0;
        double wildlandTolerance = clamp(0.18 + profile.expansionStyle.compactness * 0.20
                - profile.expansionStyle.borderPressure * 0.12, 0.05, 0.45);
        return new ExpansionBudget(base, priorityMultiplier * styleMultiplier, effective, soft, hard,
                Math.max(8.0, maxClaim), wildlandTolerance);
    }

    private TerrainCostProfile terrainCostProfile(RealmProfile profile) {
        ExpansionStyle style = profile.expansionStyle;
        Map<String, Double> baseCosts = new LinkedHashMap<>();
        baseCosts.put("lowland", 1.0 + Math.max(0.0, -style.mountainAffinity) * 0.25);
        baseCosts.put("valley", 1.05 - style.waterAffinity * 0.15);
        baseCosts.put("plateau", 1.25 - Math.max(0.0, style.mountainAffinity) * 0.25);
        baseCosts.put("upland", 1.45 - Math.max(0.0, style.mountainAffinity) * 0.45);
        baseCosts.put("ridge", 2.25 - Math.max(0.0, style.mountainAffinity) * 0.95);
        baseCosts.put("shore", 1.15 - style.coastalBias * 0.55);
        baseCosts.put("water", "allowed".equals(style.seaCrossingPolicy) ? 2.5
                : "limited".equals(style.seaCrossingPolicy) ? 4.0 : Double.POSITIVE_INFINITY);
        baseCosts.put("unknown", Double.POSITIVE_INFINITY);

        Map<String, Double> tagCosts = new LinkedHashMap<>();
        tagCosts.put("steep", 2.2 - Math.max(0.0, style.mountainAffinity) * 1.0);
        tagCosts.put("cliff", 5.0 - Math.max(0.0, style.mountainAffinity) * 2.0);
        tagCosts.put("coastal", -style.coastalBias * 0.35);
        tagCosts.put("seacoast", -style.coastalBias * 0.35);
        tagCosts.put("riverbank", -style.waterAffinity * 0.25);
        tagCosts.put("lakeshore", -style.waterAffinity * 0.18);
        tagCosts.put("water_edge", -style.waterAffinity * 0.12);
        tagCosts.put("mountain_front", -Math.max(0.0, style.mountainAffinity) * 0.35);
        return new TerrainCostProfile(baseCosts, tagCosts);
    }

    private double actionEdgeCost(RealmRun run, String realmId, WorldCell from, WorldCell to, int pathLength) {
        RealmProfile profile = run.profile(realmId);
        TerrainCostProfile terrain = run.terrainCostProfiles.getOrDefault(realmId, terrainCostProfile(profile));
        double base = terrain.costFor(to.baseLandform());
        if (!Double.isFinite(base)) {
            return Double.POSITIVE_INFINITY;
        }
        double tagCost = 0.0;
        for (String tag : to.landformTags()) {
            tagCost += terrain.tagCost(tag);
        }
        double barrier = Math.max(0.0, to.barrierCost() - 1.0);
        double supply = pathLength * (0.025 + profile.expansionStyle.compactness * 0.035);
        double compactness = from == null ? 0.0 : Math.abs(to.heightP50() - from.heightP50()) / 64.0;
        double waterBonus = "shore".equals(to.baseLandform()) ? -profile.expansionStyle.coastalBias * 0.45 : 0.0;
        return Math.max(0.05, base + tagCost + barrier + supply + compactness + waterBonus);
    }

    private boolean edgeBlocked(RealmRun run, String realmId, WorldCell from, WorldCell to) {
        RealmProfile profile = run.profile(realmId);
        if ("water".equals(to.baseLandform()) && !"allowed".equals(profile.expansionStyle.seaCrossingPolicy)) {
            return true;
        }
        if (to.barrierCost() >= 9.0 && profile.expansionStyle.mountainAffinity < 0.15) {
            return true;
        }
        return false;
    }

    private void recordStopReason(RealmRun run, String realmId, String reason, int count) {
        Map<String, Integer> reasons = run.stopReasons.computeIfAbsent(realmId, ignored -> new LinkedHashMap<>());
        reasons.put(reason, reasons.getOrDefault(reason, 0) + count);
    }

    private void recordTerrainCost(RealmRun run, String realmId, String landform, double cost) {
        Map<String, Double> costs = run.terrainCostBreakdowns.computeIfAbsent(realmId, ignored -> new LinkedHashMap<>());
        costs.put(landform, costs.getOrDefault(landform, 0.0) + cost);
    }

    private double landformAffinityPenalty(RealmProfile profile, WorldCell cell) {
        double penalty = 0.0;
        if (profile.landformPreferences.contains(cell.landform)) {
            penalty -= 0.9;
        }
        if (profile.avoidLandforms.contains(cell.landform)) {
            penalty += 1.6;
        }
        boolean mountain = cell.landform.equals("ridge") || cell.landform.equals("slope") || cell.landform.equals("cliff");
        if (mountain) {
            penalty -= profile.expansionStyle.mountainAffinity * 0.7;
        }
        return penalty;
    }

    private GridPoint autoAcceptancePoint(RealmRun run, RealmProfile profile, CandidatePackage pack) {
        WorldCell best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (WorldCell cell : run.worldCells) {
            if (!cell.assignableLand()
                    || !profile.targetContinentId.equals(cell.continentId)
                    || !pack.allowedPatches.contains(cell.patchId)) {
                continue;
            }
            List<String> errors = new ArrayList<>();
            if (!isLegalSeedCell(profile, pack, cell, run, errors)) {
                continue;
            }
            double score = candidateScore(run, profile, cell);
            if (best == null || score < bestScore
                    || (Math.abs(score - bestScore) < 0.0001
                            && (cell.gridZ < best.gridZ || (cell.gridZ == best.gridZ && cell.gridX < best.gridX)))) {
                best = cell;
                bestScore = score;
            }
        }
        return best == null ? pack.suggestedPoint : new GridPoint(best.gridX, best.gridZ);
    }

    private double candidateScore(RealmRun run, RealmProfile profile, WorldCell cell) {
        double score = 0.0;
        if (profile.landformPreferences.contains(cell.landform)) {
            score -= 10.0;
        }
        if (profile.avoidLandforms.contains(cell.landform)) {
            score += 10.0;
        }
        score += cell.waterDistanceBlocks / 128.0 * (1.0 - profile.expansionStyle.waterAffinity);
        if (cell.landformTags().contains("seacoast")) {
            score -= profile.expansionStyle.coastalBias * 5.0;
        }
        if (cell.landformTags().contains("riverbank") || cell.landformTags().contains("lakeshore")) {
            score -= profile.expansionStyle.waterAffinity * 1.5;
        }
        score += Math.abs(cell.slopeAvg) * (profile.expansionStyle.mountainAffinity < 0 ? 3.0 : -1.0);
        score -= localAssignableLandCount(run, profile, cell, 5) * 0.9;
        score -= localAssignableLandCount(run, profile, cell, 10) * 0.12;
        score += localWaterOrUnknownCount(run, cell, 3) * 0.7;
        score += cell.barrierCost() * 0.35;
        for (RealmSeed seed : run.seeds.values()) {
            double distance = distanceCells(cell.gridX, cell.gridZ, seed.seedGrid.x, seed.seedGrid.z);
            score += Math.max(0.0, 12.0 - distance) * 6.0;
            score -= Math.min(18.0, distance) * 0.04;
        }
        return score;
    }

    private int localAssignableLandCount(RealmRun run, RealmProfile profile, WorldCell center, int radiusCells) {
        int count = 0;
        int radiusSquared = radiusCells * radiusCells;
        for (int dz = -radiusCells; dz <= radiusCells; dz++) {
            for (int dx = -radiusCells; dx <= radiusCells; dx++) {
                if (dx * dx + dz * dz > radiusSquared) {
                    continue;
                }
                WorldCell cell = run.worldCellsByKey.get(key(center.gridX + dx, center.gridZ + dz));
                if (cell != null && cell.assignableLand() && profile.targetContinentId.equals(cell.continentId)) {
                    count++;
                }
            }
        }
        return count;
    }

    private int localWaterOrUnknownCount(RealmRun run, WorldCell center, int radiusCells) {
        int count = 0;
        int radiusSquared = radiusCells * radiusCells;
        for (int dz = -radiusCells; dz <= radiusCells; dz++) {
            for (int dx = -radiusCells; dx <= radiusCells; dx++) {
                if (dx * dx + dz * dz > radiusSquared) {
                    continue;
                }
                WorldCell cell = run.worldCellsByKey.get(key(center.gridX + dx, center.gridZ + dz));
                if (cell == null || !cell.assignableLand()) {
                    count++;
                }
            }
        }
        return count;
    }

    private static double resourceHint(WorldCell cell) {
        return switch (cell.landform) {
            case "ridge", "slope", "cliff" -> 1.0;
            case "plain", "terrace", "shore" -> 0.5;
            default -> 0.0;
        };
    }

    private static boolean hasOwnedNeighbor(WorldCell cell, String realmId, Map<String, String> ownership) {
        for (int[] offset : DIRECTIONS) {
            if (realmId.equals(ownership.get(key(cell.gridX + offset[0], cell.gridZ + offset[1])))) {
                return true;
            }
        }
        return false;
    }

    private List<TagAuditSample> buildTagAuditSamples(RealmRun run, AtlasSampler sampler, int requestedSampleCount,
            int radiusBlocks, int strideBlocks, int slopeRadiusBlocks, String sampleSeed) {
        Map<String, AuditCandidate> selected = new LinkedHashMap<>();
        int perLayer = Math.max(3, requestedSampleCount / 9);
        addAuditLayer(selected, run.worldCells, "confirmed_cliff", perLayer, sampleSeed,
                cell -> cell.assignableLand() && cell.landformTags().contains("cliff"));
        addAuditLayer(selected, run.worldCells, "confirmed_steep", perLayer, sampleSeed,
                cell -> cell.assignableLand() && cell.landformTags().contains("steep"));
        addAuditLayer(selected, run.worldCells, "coarse_cliff_micro_rejected", perLayer, sampleSeed,
                cell -> cell.assignableLand() && cell.landformTags().contains("micro_contradiction"));
        addAuditLayer(selected, run.worldCells, "water_edge", perLayer, sampleSeed,
                cell -> cell.assignableLand() && cell.landformTags().contains("water_edge"));
        addAuditLayer(selected, run.worldCells, "ridge", perLayer, sampleSeed,
                cell -> cell.assignableLand() && "ridge".equals(cell.baseLandform()));
        addAuditLayer(selected, run.worldCells, "plateau", perLayer, sampleSeed,
                cell -> cell.assignableLand() && "plateau".equals(cell.baseLandform()));
        addAuditLayer(selected, run.worldCells, "lowland", perLayer, sampleSeed,
                cell -> cell.assignableLand() && "lowland".equals(cell.baseLandform()));
        addAuditLayer(selected, run.worldCells, "valley", perLayer, sampleSeed,
                cell -> cell.assignableLand() && "valley".equals(cell.baseLandform()));
        addAuditLayer(selected, run.worldCells, "upland_macro", perLayer, sampleSeed,
                cell -> cell.assignableLand() && ("upland".equals(cell.baseLandform())
                        || cell.landformTags().contains("mountain_front")));
        addAuditLayer(selected, run.worldCells, "land_baseline", requestedSampleCount, sampleSeed,
                WorldCell::assignableLand);

        List<TagAuditSample> samples = new ArrayList<>();
        for (AuditCandidate candidate : selected.values()) {
            WorldCell cell = candidate.cell;
            RepresentativePoints points = representativePoints(run, sampler, cell, slopeRadiusBlocks);
            TagAuditMetrics pointMetrics = auditLocalMetrics(run, sampler, points.recommendedTpPoint().x(),
                    points.recommendedTpPoint().z(), radiusBlocks, strideBlocks, slopeRadiusBlocks);
            TagAuditMetrics cellMetrics = auditCellMetrics(run, sampler, cell, slopeRadiusBlocks);
            samples.add(TagAuditSample.from(cell, candidate.layer, points, pointMetrics, cellMetrics));
            if (samples.size() >= requestedSampleCount) {
                break;
            }
        }
        return samples;
    }

    private void addAuditLayer(Map<String, AuditCandidate> selected, List<WorldCell> cells, String layer,
            int limit, String sampleSeed, CellPredicate predicate) {
        int added = 0;
        List<WorldCell> ordered = cells.stream()
                .filter(predicate::test)
                .sorted(Comparator.comparingInt((WorldCell cell) -> auditOrder(sampleSeed, layer, cell))
                        .thenComparingInt(cell -> cell.gridZ)
                        .thenComparingInt(cell -> cell.gridX))
                .toList();
        for (WorldCell cell : ordered) {
            if (selected.putIfAbsent(key(cell.gridX, cell.gridZ), new AuditCandidate(cell, layer)) == null
                    && ++added >= limit) {
                return;
            }
        }
    }

    private int auditOrder(String sampleSeed, String layer, WorldCell cell) {
        return Math.floorMod(Objects.hash(sampleSeed, layer, cell.gridX, cell.gridZ, cell.blockX, cell.blockZ),
                1_000_000);
    }

    private RepresentativePoints representativePoints(RealmRun run, AtlasSampler sampler, WorldCell cell,
            int slopeRadiusBlocks) {
        AuditPoint center = new AuditPoint(cell.blockX + cell.cellStepBlocks / 2,
                cell.blockZ + cell.cellStepBlocks / 2);
        AuditPoint highest = center;
        AuditPoint lowest = center;
        AuditPoint maxSlope = center;
        double highestElevation = Double.NEGATIVE_INFINITY;
        double lowestElevation = Double.POSITIVE_INFINITY;
        double maxSlopeValue = Double.NEGATIVE_INFINITY;
        int stride = Math.max(1, run.surveyResult.microSampleStrideBlocks());
        int samplesPerAxis = Math.max(1, cell.cellStepBlocks / stride);
        for (int zIndex = 0; zIndex < samplesPerAxis; zIndex++) {
            for (int xIndex = 0; xIndex < samplesPerAxis; xIndex++) {
                int blockX = cell.blockX + Math.min(cell.cellStepBlocks - 1, xIndex * stride + stride / 2);
                int blockZ = cell.blockZ + Math.min(cell.cellStepBlocks - 1, zIndex * stride + stride / 2);
                SampledCell sample = sampleAtBlock(run, sampler, blockX, blockZ);
                double slope = localAuditSlope(run, sampler, blockX, blockZ, sample.elevation(), slopeRadiusBlocks);
                if (sample.elevation() > highestElevation) {
                    highestElevation = sample.elevation();
                    highest = new AuditPoint(blockX, blockZ);
                }
                if (sample.elevation() < lowestElevation) {
                    lowestElevation = sample.elevation();
                    lowest = new AuditPoint(blockX, blockZ);
                }
                if (slope > maxSlopeValue) {
                    maxSlopeValue = slope;
                    maxSlope = new AuditPoint(blockX, blockZ);
                }
            }
        }
        AuditPoint recommended = recommendedPoint(cell, center, highest, lowest, maxSlope);
        return new RepresentativePoints(center, highest, lowest, maxSlope, recommended);
    }

    private static AuditPoint recommendedPoint(WorldCell cell, AuditPoint center, AuditPoint highest,
            AuditPoint lowest, AuditPoint maxSlope) {
        List<String> tags = cell.landformTags();
        String base = cell.baseLandform();
        if (tags.contains("cliff") || tags.contains("steep") || tags.contains("mountain_front")
                || "ridge".equals(base)) {
            return maxSlope;
        }
        if ("plateau".equals(base) || "upland".equals(base)) {
            return highest;
        }
        if ("valley".equals(base) || "lowland".equals(base) || tags.contains("water_edge")) {
            return lowest;
        }
        return center;
    }

    private TagAuditMetrics auditCellMetrics(RealmRun run, AtlasSampler sampler, WorldCell cell,
            int slopeRadiusBlocks) {
        int stride = Math.max(4, Math.min(8, Math.max(1, cell.cellStepBlocks / 16)));
        int centerX = cell.blockX + cell.cellStepBlocks / 2;
        int centerZ = cell.blockZ + cell.cellStepBlocks / 2;
        return auditLocalMetrics(run, sampler, centerX, centerZ, cell.cellStepBlocks / 2,
                stride, slopeRadiusBlocks);
    }

    private TagAuditMetrics auditLocalMetrics(RealmRun run, AtlasSampler sampler, int centerBlockX,
            int centerBlockZ, int radiusBlocks, int strideBlocks, int slopeRadiusBlocks) {
        List<Double> heights = new ArrayList<>();
        List<Double> slopes = new ArrayList<>();
        Map<String, Integer> biomeHist = new LinkedHashMap<>();
        int water = 0;
        int total = 0;
        int startX = centerBlockX - radiusBlocks;
        int startZ = centerBlockZ - radiusBlocks;
        int endX = centerBlockX + radiusBlocks;
        int endZ = centerBlockZ + radiusBlocks;
        for (int z = startZ; z <= endZ; z += Math.max(1, strideBlocks)) {
            for (int x = startX; x <= endX; x += Math.max(1, strideBlocks)) {
                SampledCell sample = sampleAtBlock(run, sampler, x, z);
                double slope = localAuditSlope(run, sampler, x, z, sample.elevation(), slopeRadiusBlocks);
                heights.add(sample.elevation());
                slopes.add(slope);
                if (sample.water()) {
                    water++;
                }
                biomeHist.put(sample.biomeId(), biomeHist.getOrDefault(sample.biomeId(), 0) + 1);
                total++;
            }
        }
        heights.sort(Double::compareTo);
        slopes.sort(Double::compareTo);
        double p05 = percentile(heights, 0.05);
        double p50 = percentile(heights, 0.50);
        double p95 = percentile(heights, 0.95);
        double slopeP90 = percentile(slopes, 0.90);
        double slopeP95 = percentile(slopes, 0.95);
        double slopeMax = slopes.isEmpty() ? 0.0 : slopes.get(slopes.size() - 1);
        long steepCount = slopes.stream().filter(value -> value >= STEEP_SLOPE_P90_THRESHOLD).count();
        double waterFrac = total == 0 ? 0.0 : water / (double) total;
        double steepFrac = total == 0 ? 0.0 : steepCount / (double) total;
        List<String> referenceTags = referenceTags(slopeP90, slopeP95, steepFrac, waterFrac, p95 - p05);
        String dominantBiome = biomeHist.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("unknown");
        return new TagAuditMetrics(total, p05, p50, p95, p95 - p05, slopeP90, slopeP95, slopeMax,
                steepFrac, waterFrac, shoreMixScore(waterFrac), dominantBiome, biomeHist, referenceTags);
    }

    private SampledCell sampleAtBlock(RealmRun run, AtlasSampler sampler, int blockX, int blockZ) {
        AtlasCell audit = new AtlasCell("tag_audit", Math.floorDiv(blockX, Math.max(1, run.surveyResult.cellStepBlocks())),
                Math.floorDiv(blockZ, Math.max(1, run.surveyResult.cellStepBlocks())), 0, 0, blockX, blockZ);
        return sampler.sampleFeature(audit, run.surveyResult.sampleMode());
    }

    private double localAuditSlope(RealmRun run, AtlasSampler sampler, int blockX, int blockZ,
            double centerElevation, int radiusBlocks) {
        int radius = Math.max(1, radiusBlocks);
        double east = sampleElevationAtBlock(run, sampler, blockX + radius, blockZ);
        double west = sampleElevationAtBlock(run, sampler, blockX - radius, blockZ);
        double south = sampleElevationAtBlock(run, sampler, blockX, blockZ + radius);
        double north = sampleElevationAtBlock(run, sampler, blockX, blockZ - radius);
        return Math.max(Math.max(Math.abs(east - centerElevation), Math.abs(west - centerElevation)),
                Math.max(Math.abs(south - centerElevation), Math.abs(north - centerElevation)));
    }

    private double sampleElevationAtBlock(RealmRun run, AtlasSampler sampler, int blockX, int blockZ) {
        AtlasCell audit = new AtlasCell("tag_audit", Math.floorDiv(blockX, Math.max(1, run.surveyResult.cellStepBlocks())),
                Math.floorDiv(blockZ, Math.max(1, run.surveyResult.cellStepBlocks())), 0, 0, blockX, blockZ);
        return sampler.sampleElevation(audit, run.surveyResult.sampleMode());
    }

    private List<String> referenceTags(double slopeP90, double slopeP95, double steepFrac, double waterFrac,
            double robustRelief) {
        Set<String> tags = new LinkedHashSet<>();
        if (slopeP90 >= STEEP_SLOPE_P90_THRESHOLD || steepFrac >= STEEP_FRACTION_THRESHOLD) {
            tags.add("steep");
        }
        double cliffFractionThreshold = coastalMix(waterFrac) ? COASTAL_CLIFF_FRACTION_THRESHOLD : CLIFF_FRACTION_THRESHOLD;
        if (slopeP95 >= CLIFF_SLOPE_P90_THRESHOLD && steepFrac >= cliffFractionThreshold) {
            tags.add("cliff");
        }
        if (coastalMix(waterFrac)) {
            tags.add("water_edge");
            if (waterFrac >= 0.45) {
                tags.add("seacoast");
                tags.add("coastal");
            } else if (waterFrac < 0.25 && robustRelief <= FLAT_RELIEF_THRESHOLD) {
                tags.add("riverbank");
            } else {
                tags.add("lakeshore");
            }
        }
        return List.copyOf(tags);
    }

    private static double shoreMixScore(double waterFrac) {
        return waterFrac <= 0.0 || waterFrac >= 1.0 ? 0.0 : 1.0 - Math.abs(0.5 - waterFrac) * 2.0;
    }

    private static boolean coastalMix(double waterFrac) {
        return waterFrac > 0.05 && waterFrac < 0.95;
    }

    private JsonObject tagAuditReport(RealmRun run, List<TagAuditSample> samples, int requestedSampleCount,
            int radiusBlocks, int strideBlocks, int slopeRadiusBlocks, String sampleSeed) {
        JsonObject report = new JsonObject();
        report.addProperty("schemaVersion", SCHEMA_VERSION);
        report.addProperty("runId", run.runId);
        report.addProperty("sampleSeed", sampleSeed);
        report.addProperty("requestedSampleCount", requestedSampleCount);
        report.addProperty("sampleCount", samples.size());
        report.addProperty("auditRadiusBlocks", radiusBlocks);
        report.addProperty("auditStrideBlocks", strideBlocks);
        report.addProperty("centerSlopeRadiusBlocks", slopeRadiusBlocks);
        report.addProperty("samplingUnit", "w_coarse_cell");
        report.addProperty("responseDesign", "cell_reference_and_representative_point");
        report.addProperty("referenceCellStrideBlocks", Math.max(4, Math.min(8,
                Math.max(1, run.surveyResult.cellStepBlocks() / 16))));
        report.addProperty("referenceCellRadiusBlocks", run.surveyResult.cellStepBlocks() / 2);
        report.addProperty("sampleMode", run.surveyResult.sampleMode().contractName());
        report.add("sampleLayerCounts", tagAuditLayerCountsJson(samples));
        List<String> reportTags = List.of("cliff", "steep", "water_edge", "seacoast", "riverbank",
                "lakeshore", "coastal", "ridge", "plateau", "lowland", "valley", "boundary_truncated");
        report.add("tagMetrics", tagAuditMetricsJson(samples, reportTags));
        report.add("cellTagMetrics", tagAuditCellMetricsJson(samples, reportTags));
        report.add("baseLandformMetrics", baseLandformMetricsJson(samples));
        report.add("confusionMatrix", tagAuditConfusionJson(samples, reportTags));
        report.add("cellConfusionMatrix", tagAuditCellConfusionJson(samples, reportTags));
        JsonArray falsePositives = new JsonArray();
        samples.stream()
                .filter(sample -> sample.wTags.contains("cliff") && !sample.cellReferenceTags.contains("cliff"))
                .limit(10)
                .forEach(sample -> falsePositives.add(sample.summaryJson()));
        report.add("cliffFalsePositiveExamples", falsePositives);
        JsonArray falseNegatives = new JsonArray();
        samples.stream()
                .filter(sample -> !sample.wTags.contains("cliff") && sample.cellReferenceTags.contains("cliff"))
                .limit(10)
                .forEach(sample -> falseNegatives.add(sample.summaryJson()));
        report.add("cliffFalseNegativeExamples", falseNegatives);
        long contradicted = samples.stream().filter(sample -> sample.wTags.contains("micro_contradiction")).count();
        long contradictedAccepted = samples.stream()
                .filter(sample -> sample.wTags.contains("micro_contradiction") && sample.cellReferenceTags.contains("cliff"))
                .count();
        report.addProperty("microContradictionAcceptedRate", contradicted == 0 ? 0.0 : contradictedAccepted / (double) contradicted);
        long mixedCells = samples.stream().filter(TagAuditSample::mixedCell).count();
        long pointMismatch = samples.stream().filter(sample -> !sample.pointReferenceTags.equals(sample.cellReferenceTags)).count();
        long rankDrift = samples.stream().filter(sample -> sample.wTags.contains("rank_drift")).count();
        report.addProperty("mixedCellRate", samples.isEmpty() ? 0.0 : mixedCells / (double) samples.size());
        report.addProperty("representativePointMismatchRate", samples.isEmpty() ? 0.0 : pointMismatch / (double) samples.size());
        report.addProperty("rankDriftRate", samples.isEmpty() ? 0.0 : rankDrift / (double) samples.size());
        report.addProperty("overallAccuracy", overallBaseAccuracy(samples));
        report.addProperty("areaAdjustedAccuracy", overallBaseAccuracy(samples));
        report.addProperty("createdAt", Instant.now().toString());
        return report;
    }

    private JsonObject tagAuditLayerCountsJson(List<TagAuditSample> samples) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (TagAuditSample sample : samples) {
            counts.put(sample.auditLayer, counts.getOrDefault(sample.auditLayer, 0) + 1);
        }
        JsonObject json = new JsonObject();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            json.addProperty(entry.getKey(), entry.getValue());
        }
        return json;
    }

    private JsonObject tagAuditMetricsJson(List<TagAuditSample> samples, List<String> tags) {
        JsonObject json = new JsonObject();
        for (String tag : tags) {
            int tp = 0;
            int fp = 0;
            int fn = 0;
            int tn = 0;
            for (TagAuditSample sample : samples) {
                boolean w = sample.wTags.contains(tag);
                boolean reference = sample.referenceTags.contains(tag);
                if (w && reference) {
                    tp++;
                } else if (w) {
                    fp++;
                } else if (reference) {
                    fn++;
                } else {
                    tn++;
                }
            }
            JsonObject item = new JsonObject();
            item.addProperty("truePositive", tp);
            item.addProperty("falsePositive", fp);
            item.addProperty("falseNegative", fn);
            item.addProperty("trueNegative", tn);
            item.addProperty("precision", tp + fp == 0 ? 1.0 : tp / (double) (tp + fp));
            item.addProperty("recall", tp + fn == 0 ? 1.0 : tp / (double) (tp + fn));
            json.add(tag, item);
        }
        return json;
    }

    private JsonObject tagAuditCellMetricsJson(List<TagAuditSample> samples, List<String> tags) {
        JsonObject json = new JsonObject();
        for (String tag : tags) {
            int tp = 0;
            int fp = 0;
            int fn = 0;
            int tn = 0;
            for (TagAuditSample sample : samples) {
                boolean w = tagMatchesSample(sample, tag);
                boolean reference = sample.cellReferenceTags.contains(tag);
                if (w && reference) {
                    tp++;
                } else if (w) {
                    fp++;
                } else if (reference) {
                    fn++;
                } else {
                    tn++;
                }
            }
            JsonObject item = new JsonObject();
            item.addProperty("truePositive", tp);
            item.addProperty("falsePositive", fp);
            item.addProperty("falseNegative", fn);
            item.addProperty("trueNegative", tn);
            item.addProperty("userAccuracy", tp + fp == 0 ? 1.0 : tp / (double) (tp + fp));
            item.addProperty("producerAccuracy", tp + fn == 0 ? 1.0 : tp / (double) (tp + fn));
            item.addProperty("precision", tp + fp == 0 ? 1.0 : tp / (double) (tp + fp));
            item.addProperty("recall", tp + fn == 0 ? 1.0 : tp / (double) (tp + fn));
            json.add(tag, item);
        }
        return json;
    }

    private static boolean tagMatchesSample(TagAuditSample sample, String tag) {
        return sample.wTags.contains(tag) || sample.baseLandform.equals(tag);
    }

    private JsonObject baseLandformMetricsJson(List<TagAuditSample> samples) {
        JsonObject json = new JsonObject();
        List<String> bases = List.of("water", "shore", "lowland", "plateau", "upland", "ridge", "valley", "unknown");
        for (String base : bases) {
            int tp = 0;
            int fp = 0;
            int fn = 0;
            int tn = 0;
            for (TagAuditSample sample : samples) {
                boolean w = sample.baseLandform.equals(base);
                boolean reference = sample.cellReferenceBaseLandform.equals(base);
                if (w && reference) {
                    tp++;
                } else if (w) {
                    fp++;
                } else if (reference) {
                    fn++;
                } else {
                    tn++;
                }
            }
            if (tp + fp + fn == 0) {
                continue;
            }
            JsonObject item = new JsonObject();
            item.addProperty("truePositive", tp);
            item.addProperty("falsePositive", fp);
            item.addProperty("falseNegative", fn);
            item.addProperty("trueNegative", tn);
            item.addProperty("userAccuracy", tp + fp == 0 ? 1.0 : tp / (double) (tp + fp));
            item.addProperty("producerAccuracy", tp + fn == 0 ? 1.0 : tp / (double) (tp + fn));
            json.add(base, item);
        }
        return json;
    }

    private JsonObject tagAuditConfusionJson(List<TagAuditSample> samples, List<String> tags) {
        JsonObject matrix = new JsonObject();
        for (String wTag : tags) {
            JsonObject row = new JsonObject();
            for (String referenceTag : tags) {
                int count = 0;
                for (TagAuditSample sample : samples) {
                    if (sample.wTags.contains(wTag) && sample.referenceTags.contains(referenceTag)) {
                        count++;
                    }
                }
                row.addProperty(referenceTag, count);
            }
            matrix.add(wTag, row);
        }
        return matrix;
    }

    private JsonObject tagAuditCellConfusionJson(List<TagAuditSample> samples, List<String> tags) {
        JsonObject matrix = new JsonObject();
        for (String wTag : tags) {
            JsonObject row = new JsonObject();
            for (String referenceTag : tags) {
                int count = 0;
                for (TagAuditSample sample : samples) {
                    if (tagMatchesSample(sample, wTag) && sample.cellReferenceTags.contains(referenceTag)) {
                        count++;
                    }
                }
                row.addProperty(referenceTag, count);
            }
            matrix.add(wTag, row);
        }
        return matrix;
    }

    private double overallBaseAccuracy(List<TagAuditSample> samples) {
        if (samples.isEmpty()) {
            return 1.0;
        }
        long matches = samples.stream()
                .filter(sample -> sample.baseLandform.equals(sample.cellReferenceBaseLandform)
                        || sample.mixedCell)
                .count();
        return matches / (double) samples.size();
    }

    private String resolveTargetContinent(RealmRun run, String targetContinentId) {
        if (targetContinentId != null && !targetContinentId.isBlank()) {
            String id = targetContinentId.trim();
            if (!run.continentSummaries.containsKey(id)) {
                throw new IllegalArgumentException("Unknown targetContinentId: " + id);
            }
            return id;
        }
        return run.continentSummaries.values().stream()
                .max(Comparator.comparingInt(continent -> continent.areaCells))
                .map(continent -> continent.continentId)
                .orElseThrow(() -> new IllegalArgumentException("No land continent available."));
    }

    private List<RealmProfile> defaultProfiles(String continentId, int count, boolean allowAiDraftProfile) {
        List<RealmProfile> profiles = new ArrayList<>();
        String[] names = {"salt_kingdom", "stone_march", "green_court", "river_league"};
        for (int i = 0; i < count; i++) {
            double target = 1.0 / count;
            ScalePlan scale = new ScalePlan(i == 0 ? "major" : "normal", target, Math.max(0.05, target * 0.5),
                    Math.min(0.8, target * 1.6), continentId);
            ExpansionStyle style = switch (i % 4) {
                case 0 -> new ExpansionStyle(0.85, -0.25, 0.0, 0.45, 0.8, 0.45, 0.35, "limited");
                case 1 -> new ExpansionStyle(0.25, 0.65, -0.2, 0.75, 0.1, 0.8, 0.45, "none");
                case 2 -> new ExpansionStyle(0.45, -0.15, 0.65, 0.55, 0.2, 0.45, 0.25, "none");
                default -> new ExpansionStyle(0.7, -0.1, 0.2, 0.35, 0.5, 0.55, 0.55, "limited");
            };
            String id = "realm_" + names[i % names.length] + "_" + i;
            profiles.add(new RealmProfile(id, displayName(id), continentId, allowAiDraftProfile ? "AI draft realm" : "debug realm",
                    List.of("debug", "v1_1"), List.of(), List.of(), List.of("plain", "shore", "terrace"),
                    List.of("water", "unknown"), scale, style));
        }
        return profiles;
    }

    private void validateProfiles(RealmRun run, String defaultContinent) {
        for (RealmProfile profile : run.profiles) {
            if (!run.continentSummaries.containsKey(profile.targetContinentId)) {
                throw new IllegalArgumentException("Profile target continent is not available: " + profile.realmId);
            }
            if (profile.scalePlan.normalizationGroup == null || profile.scalePlan.normalizationGroup.isBlank()) {
                profile.scalePlan = profile.scalePlan.withGroup(defaultContinent);
            }
        }
    }

    private RealmRun requireRun(String runId) throws IOException {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId is required.");
        }
        String normalizedRunId = runId.trim();
        RealmRun run = runs.get(normalizedRunId);
        if (run != null) {
            return run;
        }

        run = restorePersistedRun(normalizedRunId);
        runs.put(run.runId, run);
        return run;
    }

    private RealmRun ensureRunForTagAudit(String runId) throws IOException {
        return requireRun(runId);
    }

    private RealmRun restorePersistedRun(String runId) throws IOException {
        WorldSurveyResult surveyResult = new WorldSurveyRunner(debugRoot, GisClassifierConfig.defaults())
                .loadSealedResult(runId);
        RealmRun run = new RealmRun(surveyResult.runId(), surveyResult.runDirectory(), surveyResult);
        buildWorld(run);
        restoreT1Checkpoint(run);
        restoreT2Checkpoint(run);
        restoreT3Checkpoint(run);
        restoreT4Checkpoint(run);
        registerExistingArtifacts(run);
        return run;
    }

    private void restoreT1Checkpoint(RealmRun run) throws IOException {
        Path profilesPath = run.runDirectory.resolve("realm_profiles.json");
        Path packagesPath = run.runDirectory.resolve("candidate_map_packages.json");
        Path manifestPath = run.runDirectory.resolve("t1_manifest.json");
        if (!Files.isRegularFile(profilesPath) || !Files.isRegularFile(packagesPath)
                || !Files.isRegularFile(manifestPath)) {
            return;
        }

        JsonArray profiles = readJsonArray(profilesPath, "realm_profiles");
        int index = 0;
        for (JsonElement element : profiles) {
            if (!element.isJsonObject()) {
                throw invalidCheckpoint(profilesPath, "realm_profiles must contain objects");
            }
            RealmProfile profile = RealmProfile.fromJson(element.getAsJsonObject(), "", index++);
            run.profiles.add(profile);
        }
        if (run.profiles.isEmpty()) {
            throw invalidCheckpoint(profilesPath, "at least one realm profile is required");
        }
        validateProfiles(run, run.profiles.get(0).targetContinentId);
        for (RealmProfile profile : run.profiles) {
            run.candidatePackages.put(profile.realmId, buildCandidatePackage(run, profile));
        }

        JsonArray persistedPackages = readJsonArray(packagesPath, "candidate_map_packages");
        Set<String> persistedRealmIds = new HashSet<>();
        for (JsonElement element : persistedPackages) {
            JsonObject object = requireCheckpointObject(element, packagesPath);
            String realmId = stringValue(object, "realmId", "");
            CandidatePackage expected = run.candidatePackages.get(realmId);
            if (expected == null || !persistedRealmIds.add(realmId)
                    || !expected.packageId.equals(stringValue(object, "packageId", ""))
                    || !expected.surveyId.equals(stringValue(object, "surveyId", ""))) {
                throw invalidCheckpoint(packagesPath,
                        "candidate package identity does not match the restored realm profiles and sealed W survey");
            }
        }
        if (!persistedRealmIds.equals(run.candidatePackages.keySet())) {
            throw invalidCheckpoint(packagesPath, "candidate packages do not cover every restored realm");
        }

        JsonObject manifest = readJsonObject(manifestPath, "t1_manifest");
        if (!run.runId.equals(stringValue(manifest, "runId", ""))
                || intValue(manifest, "realmCount", -1) != run.profiles.size()
                || !new HashSet<>(stringList(manifest, "realmIds")).equals(run.candidatePackages.keySet())) {
            throw invalidCheckpoint(manifestPath, "runId, realmCount, or realmIds do not match restored profiles");
        }
    }

    private void restoreT2Checkpoint(RealmRun run) throws IOException {
        if (run.profiles.isEmpty()) {
            return;
        }
        Path selectionsPath = run.runDirectory.resolve("realm_coordinate_selections.json");
        Path seedsPath = run.runDirectory.resolve("realm_seeds.json");
        Path capitalIntentsPath = run.runDirectory.resolve("capital_city_intents.json");
        Path legacyCapitalsPath = run.runDirectory.resolve("capital_city_seeds.json");
        Path reportPath = run.runDirectory.resolve("t2_report.json");
        if (!Files.isRegularFile(selectionsPath) || !Files.isRegularFile(seedsPath)
                || (!Files.isRegularFile(capitalIntentsPath) && !Files.isRegularFile(legacyCapitalsPath))
                || !Files.isRegularFile(reportPath)) {
            return;
        }

        for (JsonElement element : readJsonArray(selectionsPath, "realm_coordinate_selections")) {
            JsonObject object = requireCheckpointObject(element, selectionsPath);
            RealmSelection selection = realmSelectionFromJson(object);
            CandidatePackage pack = run.candidatePackages.get(selection.realmId);
            if (pack == null || !pack.packageId.equals(selection.packageId)) {
                throw invalidCheckpoint(selectionsPath, "selection references an unknown realm or candidate package");
            }
            run.selections.put(selection.realmId, selection);
        }
        for (JsonElement element : readJsonArray(seedsPath, "realm_seeds")) {
            JsonObject object = requireCheckpointObject(element, seedsPath);
            RealmSeed seed = realmSeedFromJson(object);
            RealmSelection selection = run.selections.get(seed.realmId);
            if (selection == null || "rejected".equals(selection.validationStatus)
                    || !seed.selectionId.equals(selection.selectionId)
                    || !seed.seedGrid.equals(selection.finalGrid)) {
                throw invalidCheckpoint(seedsPath, "realm seed does not match an accepted coordinate selection");
            }
            validateRestoredGrid(run, seed.realmId, seed.seedGrid, seed.continentId, seed.patchId, seedsPath);
            run.seeds.put(seed.realmId, seed);
        }
        Path capitalSourcePath = Files.isRegularFile(capitalIntentsPath) ? capitalIntentsPath : legacyCapitalsPath;
        String capitalSourceName = Files.isRegularFile(capitalIntentsPath)
                ? "capital_city_intents" : "capital_city_seeds";
        for (JsonElement element : readJsonArray(capitalSourcePath, capitalSourceName)) {
            JsonObject object = requireCheckpointObject(element, capitalSourcePath);
            CapitalCityIntent capitalIntent = Files.isRegularFile(capitalIntentsPath)
                    ? capitalCityIntentFromJson(object) : capitalCityIntentFromLegacySeed(object);
            if (!run.seeds.containsKey(capitalIntent.realmId)) {
                throw invalidCheckpoint(capitalSourcePath, "capital intent references an unknown realm seed");
            }
            run.capitalIntents.put(capitalIntent.realmId, capitalIntent);
        }
        if (!run.seeds.keySet().equals(run.capitalIntents.keySet())) {
            throw invalidCheckpoint(reportPath, "realm seeds and capital city intents do not cover the same realms");
        }
        JsonObject report = readJsonObject(reportPath, "t2_report");
        if (!run.runId.equals(stringValue(report, "runId", ""))
                || intValue(report, "completedSelections", -1) != run.seeds.size()
                || intValue(report, "totalRealms", -1) != run.profiles.size()) {
            throw invalidCheckpoint(reportPath, "T2 report does not match the restored selections and realm profiles");
        }
    }

    private void restoreT3Checkpoint(RealmRun run) throws IOException {
        if (run.profiles.isEmpty() || run.seeds.size() != run.profiles.size()) {
            return;
        }
        Path territoryPath = run.runDirectory.resolve("realm_territory_map.json");
        Path reportPath = run.runDirectory.resolve("t3_report.json");
        if (!Files.isRegularFile(territoryPath) || !Files.isRegularFile(reportPath)) {
            return;
        }

        JsonObject territory = readJsonObject(territoryPath, "realm_territory_map");
        JsonObject report = readJsonObject(reportPath, "t3_report");
        String expectedSurveyId = run.surveyResult.surveyId();
        if (!expectedSurveyId.equals(stringValue(territory, "surveyId", ""))) {
            throw invalidCheckpoint(territoryPath, "surveyId does not match the sealed W survey");
        }

        List<TerritoryCell> cells = new ArrayList<>();
        JsonArray cellArray = territory.has("territoryCells") && territory.get("territoryCells").isJsonArray()
                ? territory.getAsJsonArray("territoryCells") : new JsonArray();
        for (JsonElement element : cellArray) {
            JsonObject object = requireCheckpointObject(element, territoryPath);
            TerritoryCell cell = new TerritoryCell(intValue(object, "gridX", 0), intValue(object, "gridZ", 0),
                    stringValue(object, "realmId", ""), stringValue(object, "status", "wild"),
                    doubleValue(object, "claimStrength", 0.0), doubleValue(object, "claimCost", 0.0));
            if (!run.worldCellsByKey.containsKey(key(cell.gridX, cell.gridZ))) {
                throw invalidCheckpoint(territoryPath, "territory cell is outside the sealed W grid");
            }
            cells.add(cell);
        }

        Map<String, RealmStats> stats = new LinkedHashMap<>();
        JsonArray statsArray = territory.has("realmStats") && territory.get("realmStats").isJsonArray()
                ? territory.getAsJsonArray("realmStats") : new JsonArray();
        for (JsonElement element : statsArray) {
            RealmStats stat = realmStatsFromJson(requireCheckpointObject(element, territoryPath));
            stats.put(stat.realmId, stat);
        }

        Map<String, NormalizedScale> scales = new LinkedHashMap<>();
        if (territory.has("normalizedScales") && territory.get("normalizedScales").isJsonObject()) {
            JsonObject scaleObject = territory.getAsJsonObject("normalizedScales");
            for (String realmId : scaleObject.keySet()) {
                JsonObject value = scaleObject.getAsJsonObject(realmId);
                scales.put(realmId, new NormalizedScale(ScalePlan.fromJson(value,
                        stringValue(territory, "normalizationGroup", "")),
                        doubleValue(value, "normalizedTargetAreaRatio", 0.0)));
            }
        }

        List<TerritoryRepair> repairs = new ArrayList<>();
        if (territory.has("repairs") && territory.get("repairs").isJsonArray()) {
            for (JsonElement element : territory.getAsJsonArray("repairs")) {
                JsonObject object = requireCheckpointObject(element, territoryPath);
                repairs.add(new TerritoryRepair(stringValue(object, "type", ""),
                        stringValue(object, "realmId", ""), stringValue(object, "description", ""),
                        intValue(object, "affectedCells", 0)));
            }
        }

        Map<String, ExpansionBudget> budgets = expansionBudgetsFromJson(report);
        Map<String, TerrainCostProfile> costProfiles = terrainCostProfilesFromJson(report);
        run.expansionModel = stringValue(territory, "expansionModel", "action_budget");
        run.expansionBudgets = budgets;
        run.terrainCostProfiles = costProfiles;
        run.territory = new RealmTerritoryMap(stringValue(territory, "territoryMapId", "territory_" + run.runId),
                run.runId, stringValue(territory, "normalizationGroup", ""), cells, stats, scales,
                stringList(territory, "warnings"), repairs, run.expansionModel, budgets, costProfiles);
    }

    private void restoreT4Checkpoint(RealmRun run) throws IOException {
        if (run.territory == null) {
            return;
        }
        Path registryPath = run.runDirectory.resolve("city_seed_registry.json");
        Path reportPath = run.runDirectory.resolve("t4_report.json");
        if (!Files.isRegularFile(registryPath) || !Files.isRegularFile(reportPath)) {
            return;
        }
        JsonObject registry = readJsonObject(registryPath, "city_seed_registry");
        if (!run.territory.territoryMapId.equals(stringValue(registry, "territoryMapId", ""))) {
            throw invalidCheckpoint(registryPath, "territoryMapId does not match the restored T3 checkpoint");
        }
        List<CitySeed> seeds = new ArrayList<>();
        JsonArray seedArray = registry.has("citySeeds") && registry.get("citySeeds").isJsonArray()
                ? registry.getAsJsonArray("citySeeds") : new JsonArray();
        for (JsonElement element : seedArray) {
            seeds.add(citySeedFromJson(requireCheckpointObject(element, registryPath)));
        }
        run.registry = new CitySeedRegistry(stringValue(registry, "registryId", "registry_" + run.runId),
                run.runId, run.territory.territoryMapId, seeds);
        Path scorePath = run.runDirectory.resolve("score_manifest.json");
        if (Files.isRegularFile(scorePath)) {
            run.scoreManifest = readJsonObject(scorePath, "score_manifest");
        }
    }

    private static RealmSelection realmSelectionFromJson(JsonObject object) {
        JsonObject validation = object.has("validation") && object.get("validation").isJsonObject()
                ? object.getAsJsonObject("validation") : new JsonObject();
        return new RealmSelection(stringValue(object, "selectionId", ""), stringValue(object, "realmId", ""),
                stringValue(object, "packageId", ""), stringValue(object, "selectedBy", "ai"),
                gridPointFromJson(object.get("primaryGrid")), gridPointFromJson(object.get("finalGrid")),
                gridPointFromJson(object.get("primaryBlock")), stringValue(object, "reason", ""),
                stringValue(validation, "status", "rejected"),
                booleanValue(validation, "snapApplied", false), stringList(validation, "warnings"),
                stringList(validation, "errors"), stringValue(validation, "continentId", ""),
                stringValue(validation, "patchId", ""));
    }

    private static RealmSeed realmSeedFromJson(JsonObject object) {
        return new RealmSeed(stringValue(object, "realmId", ""), stringValue(object, "selectionId", ""),
                gridPointFromJson(object.get("seedGrid")), gridPointFromJson(object.get("seedBlock")),
                stringValue(object, "continentId", ""), stringValue(object, "patchId", ""),
                ScalePlan.fromJson(object.getAsJsonObject("scalePlan"), stringValue(object, "continentId", "")),
                ExpansionStyle.fromJson(object.getAsJsonObject("expansionStyle")));
    }

    private static CapitalCityIntent capitalCityIntentFromJson(JsonObject object) {
        return new CapitalCityIntent(stringValue(object, "citySeedId", ""), stringValue(object, "realmId", ""),
                stringValue(object, "cityRole", "capital"), stringValue(object, "theoreticalScale", "capital"),
                booleanValue(object, "mustExist", true), stringList(object, "requiredConditions"),
                stringList(object, "coreFunctions"), stringValue(object, "realmCoreSelectionId", ""),
                stringValue(object, "sourceMode", "t2_realm_core_intent"));
    }

    private static CapitalCityIntent capitalCityIntentFromLegacySeed(JsonObject object) {
        return new CapitalCityIntent(stringValue(object, "citySeedId", ""), stringValue(object, "realmId", ""),
                "capital", stringValue(object, "theoreticalScale", "capital"),
                booleanValue(object, "mustExist", true), List.of("land", "inside_realm"),
                List.of("administration", "market", "defense"), "", "legacy_realm_core_migration");
    }

    private static RealmStats realmStatsFromJson(JsonObject object) {
        return new RealmStats(stringValue(object, "realmId", ""), intValue(object, "areaCells", 0),
                intValue(object, "targetAreaCells", 0), intValue(object, "areaDeltaCells", 0),
                doubleValue(object, "targetAreaRatio", 0.0), doubleValue(object, "actualAreaRatio", 0.0),
                doubleValue(object, "scaleMinAreaRatio", 0.0), doubleValue(object, "scaleMaxAreaRatio", 1.0),
                doubleValue(object, "coastalRatio", 0.0), stringList(object, "primaryLandforms"),
                new LinkedHashSet<>(stringList(object, "neighbors")), intValue(object, "componentCount", 0),
                intValue(object, "largestComponentCells", 0), doubleValue(object, "largestComponentRatio", 0.0),
                doubleValue(object, "detachedAreaRatio", 0.0), doubleValue(object, "holeAreaRatio", 0.0),
                doubleValue(object, "naturalBoundaryFit", 0.0), doubleValue(object, "budgetUsedRatio", 0.0),
                doubleValue(object, "averageClaimCost", 0.0), doubleValue(object, "maxClaimCost", 0.0),
                doubleMap(object, "terrainCostBreakdown"), integerMap(object, "stopReasonSummary"));
    }

    private static Map<String, ExpansionBudget> expansionBudgetsFromJson(JsonObject report) {
        Map<String, ExpansionBudget> budgets = new LinkedHashMap<>();
        if (!report.has("expansionBudgets") || !report.get("expansionBudgets").isJsonObject()) {
            return budgets;
        }
        JsonObject values = report.getAsJsonObject("expansionBudgets");
        for (String realmId : values.keySet()) {
            JsonObject value = values.getAsJsonObject(realmId);
            budgets.put(realmId, new ExpansionBudget(doubleValue(value, "baseActionBudget", 0.0),
                    doubleValue(value, "budgetMultiplier", 0.0), doubleValue(value, "effectiveActionBudget", 0.0),
                    doubleValue(value, "softStopThreshold", 0.0), doubleValue(value, "hardStopThreshold", 0.0),
                    doubleValue(value, "maxClaimCost", 0.0), doubleValue(value, "wildlandTolerance", 0.0)));
        }
        return budgets;
    }

    private static Map<String, TerrainCostProfile> terrainCostProfilesFromJson(JsonObject report) {
        Map<String, TerrainCostProfile> profiles = new LinkedHashMap<>();
        if (!report.has("terrainCostProfiles") || !report.get("terrainCostProfiles").isJsonObject()) {
            return profiles;
        }
        JsonObject values = report.getAsJsonObject("terrainCostProfiles");
        for (String realmId : values.keySet()) {
            JsonObject value = values.getAsJsonObject(realmId);
            profiles.put(realmId, new TerrainCostProfile(doubleMapAllowBlocked(value, "baseCosts"),
                    doubleMap(value, "tagCosts")));
        }
        return profiles;
    }

    private static CitySeed citySeedFromJson(JsonObject object) {
        JsonObject source = object.has("source") && object.get("source").isJsonObject()
                ? object.getAsJsonObject("source") : new JsonObject();
        return new CitySeed(stringValue(object, "citySeedId", ""), stringValue(object, "realmId", ""),
                stringValue(object, "role", ""), stringValue(object, "theoreticalScale", ""),
                gridPointFromJson(object.get("anchorGrid")), gridPointFromJson(object.get("anchorBlock")),
                intValue(object, "candidateRangeCells", 0), intValue(object, "planningRadiusCells", 0),
                stringValue(object, "subregionId", ""), stringValue(object, "candidateId", ""),
                doubleValue(object, "graphDistanceToNearestCity", -1.0), stringValue(object, "satelliteOf", ""),
                stringList(object, "requiredConditions"), stringList(object, "coreFunctions"),
                stringValue(object, "trigger", ""), stringValue(source, "reason", ""));
    }

    private static void validateRestoredGrid(RealmRun run, String realmId, GridPoint grid, String continentId,
                                             String patchId, Path sourcePath) {
        if (run.profiles.stream().noneMatch(profile -> profile.realmId.equals(realmId))) {
            throw invalidCheckpoint(sourcePath, "checkpoint references an unknown realmId: " + realmId);
        }
        WorldCell cell = run.worldCellsByKey.get(key(grid.x, grid.z));
        if (cell == null || (!continentId.isBlank() && !continentId.equals(cell.continentId))
                || (!patchId.isBlank() && !patchId.equals(cell.patchId))) {
            throw invalidCheckpoint(sourcePath, "checkpoint grid does not match the sealed W cell");
        }
    }

    private static JsonObject requireCheckpointObject(JsonElement element, Path sourcePath) {
        if (element == null || !element.isJsonObject()) {
            throw invalidCheckpoint(sourcePath, "array must contain objects");
        }
        return element.getAsJsonObject();
    }

    private static JsonObject readJsonObject(Path path, String label) throws IOException {
        JsonElement root = readJson(path, label);
        if (!root.isJsonObject()) {
            throw invalidCheckpoint(path, label + " root must be an object");
        }
        return root.getAsJsonObject();
    }

    private static JsonArray readJsonArray(Path path, String label) throws IOException {
        JsonElement root = readJson(path, label);
        if (!root.isJsonArray()) {
            throw invalidCheckpoint(path, label + " root must be an array");
        }
        return root.getAsJsonArray();
    }

    private static JsonElement readJson(Path path, String label) throws IOException {
        try {
            return JsonParser.parseString(Files.readString(path));
        } catch (RuntimeException exception) {
            throw invalidCheckpoint(path, label + " is not valid JSON", exception);
        }
    }

    private static IllegalArgumentException invalidCheckpoint(Path path, String message) {
        return invalidCheckpoint(path, message, null);
    }

    private static IllegalArgumentException invalidCheckpoint(Path path, String message, Throwable cause) {
        return new IllegalArgumentException("REALM_CHECKPOINT_INVALID: " + path + ": " + message, cause);
    }

    private static Map<String, Double> doubleMap(JsonObject object, String key) {
        return doubleMap(object, key, false);
    }

    private static Map<String, Double> doubleMapAllowBlocked(JsonObject object, String key) {
        return doubleMap(object, key, true);
    }

    private static Map<String, Double> doubleMap(JsonObject object, String key, boolean allowBlocked) {
        Map<String, Double> values = new LinkedHashMap<>();
        if (!object.has(key) || !object.get(key).isJsonObject()) {
            return values;
        }
        for (Map.Entry<String, JsonElement> entry : object.getAsJsonObject(key).entrySet()) {
            if (allowBlocked && entry.getValue().isJsonPrimitive()
                    && "blocked".equals(entry.getValue().getAsString())) {
                values.put(entry.getKey(), Double.POSITIVE_INFINITY);
            } else {
                values.put(entry.getKey(), entry.getValue().getAsDouble());
            }
        }
        return values;
    }

    private static Map<String, Integer> integerMap(JsonObject object, String key) {
        Map<String, Integer> values = new LinkedHashMap<>();
        if (!object.has(key) || !object.get(key).isJsonObject()) {
            return values;
        }
        for (Map.Entry<String, JsonElement> entry : object.getAsJsonObject(key).entrySet()) {
            values.put(entry.getKey(), entry.getValue().getAsInt());
        }
        return values;
    }

    private static boolean booleanValue(JsonObject object, String key, boolean defaultValue) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return defaultValue;
        }
        return object.get(key).getAsBoolean();
    }

    private static void registerExistingArtifacts(RealmRun run) {
        registerArtifact(run, "worldSurveyContext", "world_survey_context.json");
        registerArtifact(run, "worldPatchMap", "world_patch_map.json");
        registerArtifact(run, "worldPatchPreview", "world_patch_preview.png");
        registerArtifact(run, "gridOverlayPreview", "grid_overlay_preview.png");
        registerArtifact(run, "wManifest", "w_manifest.json");
        registerArtifact(run, "worldFeatureGrid", "world_feature_grid.json");
        registerArtifact(run, "worldSurveyManifest", "world_survey_manifest.json");
        registerArtifact(run, "realmProfiles", "realm_profiles.json");
        registerArtifact(run, "candidateMapPackages", "candidate_map_packages.json");
        registerArtifact(run, "t1Manifest", "t1_manifest.json");
        registerArtifact(run, "realmCoordinateSelections", "realm_coordinate_selections.json");
        registerArtifact(run, "realmSeeds", "realm_seeds.json");
        registerArtifact(run, "capitalCityIntents", "capital_city_intents.json");
        registerArtifact(run, "legacyCapitalCitySeeds", "capital_city_seeds.json");
        registerArtifact(run, "t2Report", "t2_report.json");
        registerArtifact(run, "realmTerritoryMap", "realm_territory_map.json");
        registerArtifact(run, "territoryPreview", "territory_preview.png");
        registerArtifact(run, "t3Report", "t3_report.json");
        registerArtifact(run, "territoryRepairLog", "territory_repair_log.json");
        registerArtifact(run, "citySeedRegistry", "city_seed_registry.json");
        registerArtifact(run, "citySeedPreview", "city_seed_preview.png");
        registerArtifact(run, "t4Report", "t4_report.json");
        registerArtifact(run, "realmCityCandidatePackages", "realm_city_candidate_packages.json");
        registerArtifact(run, "scoreManifest", "score_manifest.json");
        registerArtifact(run, "acceptanceReport", "acceptance_report.json");
    }

    private static void registerArtifact(RealmRun run, String key, String relativePath) {
        if (Files.exists(run.runDirectory.resolve(relativePath))) {
            run.artifacts.put(key, relativePath);
        }
    }

    private String latestRunId() {
        String latest = "";
        for (String runId : runs.keySet()) {
            latest = runId;
        }
        return latest;
    }

    private JsonArray knownRuns() {
        JsonArray array = new JsonArray();
        for (RealmRun run : runs.values()) {
            JsonObject item = new JsonObject();
            item.addProperty("runId", run.runId);
            item.addProperty("runDirectory", run.runDirectory.toAbsolutePath().toString());
            item.add("artifacts", run.artifactsJson());
            array.add(item);
        }
        return array;
    }

    private static JsonObject baseResponse(String stage, String runId) {
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.addProperty("stage", stage);
        response.addProperty("runId", runId == null ? "" : runId);
        response.add("warnings", new JsonArray());
        response.add("errors", new JsonArray());
        return response;
    }

    private static void writeJson(Path path, JsonElement json) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, AtlasJson.GSON.toJson(json));
    }

    private static String normalizeRunId(String requestedRunId, String fallback) {
        if (requestedRunId != null && !requestedRunId.isBlank()) {
            return safeId(requestedRunId.trim());
        }
        return "realm_" + safeId(fallback == null || fallback.isBlank()
                ? UUID.randomUUID().toString().substring(0, 8) : fallback);
    }

    private static String safeId(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]+", "_");
    }

    private static String normalizeQualityMode(String value) {
        if (value == null || value.isBlank()) {
            return "strict";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("smoke".equals(normalized) || "strict".equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("qualityMode must be one of: smoke, strict.");
    }

    private static String normalizeExpansionModel(String value, String qualityMode) {
        if (value == null || value.isBlank()) {
            return "smoke".equals(qualityMode) ? "quota_frontier" : "action_budget";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("quota_frontier".equals(normalized) || "action_budget".equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("expansionModel must be one of: quota_frontier, action_budget.");
    }

    private static String displayName(String id) {
        String trimmed = id.startsWith("realm_") ? id.substring("realm_".length()) : id;
        return trimmed.replace('_', ' ');
    }

    private static GridPoint gridPointFromJson(JsonElement element) {
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("alternates must contain objects.");
        }
        JsonObject object = element.getAsJsonObject();
        return new GridPoint(intValue(object, "gridX", intValue(object, "x", 0)),
                intValue(object, "gridZ", intValue(object, "z", 0)));
    }

    private static String landWater(AtlasCell cell) {
        if (cell.landformType() == LandformType.WATER || cell.isWater()) {
            return "water";
        }
        if (cell.landformType() == LandformType.SHORE) {
            return "shore";
        }
        if (cell.landformType() == LandformType.UNKNOWN) {
            return "unknown";
        }
        return "land";
    }

    private static double finiteWaterDistance(double value) {
        return Double.isFinite(value) ? Math.max(0.0, value) : 999999.0;
    }

    private static List<String> flagsFor(AtlasCell cell) {
        List<String> flags = new ArrayList<>();
        if (cell.landformType() == LandformType.SHORE || cell.waterDistance() <= 2.0) {
            flags.add("coastal");
        }
        if (cell.slope() < 0.2 && !cell.isWater()) {
            flags.add("lowland");
        }
        if (cell.landformType() == LandformType.RIDGE || cell.landformType() == LandformType.CLIFF) {
            flags.add("mountain_edge");
        }
        return flags;
    }

    private static String key(int gridX, int gridZ) {
        return gridX + "," + gridZ;
    }

    private static double distanceCells(int ax, int az, int bx, int bz) {
        int dx = ax - bx;
        int dz = az - bz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private static double percentile(List<Double> sorted, double fraction) {
        if (sorted.isEmpty()) {
            return 0.0;
        }
        int index = (int) Math.round((sorted.size() - 1) * fraction);
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static JsonObject distribution(List<String> values) {
        JsonObject json = new JsonObject();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String value : values) {
            counts.put(value, counts.getOrDefault(value, 0) + 1);
        }
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            json.addProperty(entry.getKey(), entry.getValue());
        }
        return json;
    }

    private static JsonObject tagDistribution(List<WorldCell> cells) {
        JsonObject json = new JsonObject();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (WorldCell cell : cells) {
            for (String tag : cell.landformTags()) {
                counts.put(tag, counts.getOrDefault(tag, 0) + 1);
            }
        }
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            json.addProperty(entry.getKey(), entry.getValue());
        }
        return json;
    }

    private static int intValue(JsonObject object, String key, int defaultValue) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return defaultValue;
        }
        return object.get(key).getAsInt();
    }

    private static double doubleValue(JsonObject object, String key, double defaultValue) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return defaultValue;
        }
        return object.get(key).getAsDouble();
    }

    private static String stringValue(JsonObject object, String key, String defaultValue) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return defaultValue;
        }
        return object.get(key).getAsString();
    }

    private static List<String> stringList(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonElement element : object.getAsJsonArray(key)) {
            values.add(element.getAsString());
        }
        return values;
    }

    private static JsonArray stringArray(Iterable<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static JsonArray arrayOf(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static Color colorForCell(WorldCell cell) {
        Color color = colorForLandform(cell.baseLandform(), cell.landWater);
        if (cell.landformTags().contains("cliff")) {
            return new Color(176, 72, 78);
        }
        return color;
    }

    private static Color colorForLandform(String landform, String landWater) {
        if ("water".equals(landWater) || "water".equals(landform)) {
            return new Color(42, 96, 164);
        }
        if ("shore".equals(landWater) || "shore".equals(landform)) {
            return new Color(214, 197, 128);
        }
        return switch (landform) {
            case "lowland", "plain" -> new Color(104, 168, 86);
            case "plateau", "terrace" -> new Color(142, 178, 96);
            case "upland", "slope" -> new Color(172, 156, 92);
            case "cliff" -> new Color(120, 112, 108);
            case "ridge" -> new Color(146, 154, 156);
            case "valley" -> new Color(82, 148, 118);
            case "basin" -> new Color(98, 132, 106);
            case "unknown" -> new Color(54, 58, 64);
            default -> new Color(68, 70, 74);
        };
    }

    private static Map<String, Color> realmColors(List<RealmProfile> profiles) {
        Color[] palette = {
                new Color(220, 96, 82),
                new Color(76, 145, 205),
                new Color(114, 178, 105),
                new Color(204, 162, 74),
                new Color(158, 116, 190)
        };
        Map<String, Color> colors = new HashMap<>();
        for (int i = 0; i < profiles.size(); i++) {
            colors.put(profiles.get(i).realmId, palette[i % palette.length]);
        }
        return colors;
    }

    private static final int[][] DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };

    private static final int[][] EIGHT_DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    public record GridPoint(int x, int z) {
        JsonObject asJson() {
            JsonObject json = new JsonObject();
            json.addProperty("x", x);
            json.addProperty("z", z);
            return json;
        }

        JsonObject asGridJson() {
            JsonObject json = new JsonObject();
            json.addProperty("gridX", x);
            json.addProperty("gridZ", z);
            return json;
        }
    }

    private record GridDistance(int x, int z, int distance) {
    }

    private record WaterBounds(int minX, int maxX, int minZ, int maxZ) {
    }

    private static final class WaterComponent {
        final String id;
        final WaterBounds bounds;
        final Set<String> cellKeys = new LinkedHashSet<>();
        final Map<String, Integer> biomeHistogram = new LinkedHashMap<>();
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        boolean touchesScanEdge;

        WaterComponent(String id, WaterBounds bounds) {
            this.id = id;
            this.bounds = bounds;
        }

        void add(WorldCell cell) {
            cellKeys.add(key(cell.gridX, cell.gridZ));
            minX = Math.min(minX, cell.gridX);
            maxX = Math.max(maxX, cell.gridX);
            minZ = Math.min(minZ, cell.gridZ);
            maxZ = Math.max(maxZ, cell.gridZ);
            touchesScanEdge = touchesScanEdge
                    || cell.gridX == bounds.minX || cell.gridX == bounds.maxX
                    || cell.gridZ == bounds.minZ || cell.gridZ == bounds.maxZ;
            if (cell.feature != null) {
                for (Map.Entry<String, Integer> entry : cell.feature.biomeHistogram().entrySet()) {
                    biomeHistogram.merge(entry.getKey(), entry.getValue(), Integer::sum);
                }
            }
        }

        int size() {
            return cellKeys.size();
        }

        int spanX() {
            return maxX < minX ? 0 : maxX - minX + 1;
        }

        int spanZ() {
            return maxZ < minZ ? 0 : maxZ - minZ + 1;
        }

        String edgeType() {
            if (hasBiomeToken("ocean") || size() >= SEACOAST_COMPONENT_MIN_CELLS) {
                return "seacoast";
            }
            if (touchesScanEdge) {
                return "boundary_truncated";
            }
            if (hasBiomeToken("river")
                    || size() <= RIVERBANK_COMPONENT_MAX_CELLS
                    || Math.min(spanX(), spanZ()) <= RIVERBANK_COMPONENT_MAX_SPAN_CELLS) {
                return "riverbank";
            }
            return "lakeshore";
        }

        String componentType() {
            if (hasBiomeToken("ocean") || size() >= SEACOAST_COMPONENT_MIN_CELLS) {
                return "open_water";
            }
            if (touchesScanEdge) {
                return "boundary_truncated";
            }
            if (hasBiomeToken("river")
                    || size() <= RIVERBANK_COMPONENT_MAX_CELLS
                    || Math.min(spanX(), spanZ()) <= RIVERBANK_COMPONENT_MAX_SPAN_CELLS) {
                return "river_like";
            }
            return "lake_like";
        }

        double waterBoundaryConfidence() {
            return touchesScanEdge && !hasBiomeToken("ocean") && size() < SEACOAST_COMPONENT_MIN_CELLS ? 0.45 : 0.90;
        }

        private boolean hasBiomeToken(String token) {
            return biomeHistogram.keySet().stream().anyMatch(id -> id.toLowerCase(Locale.ROOT).contains(token));
        }
    }

    private record ComponentMetrics(int componentCount, int largestComponentCells,
            double largestComponentRatio, double detachedAreaRatio) {
    }

    private interface CellPredicate {
        boolean test(WorldCell cell);
    }

    private static final class RealmRun {
        final String runId;
        final Path runDirectory;
        final WorldSurveyResult surveyResult;
        final List<WorldCell> worldCells = new ArrayList<>();
        final Map<String, WorldCell> worldCellsByKey = new LinkedHashMap<>();
        final List<RealmProfile> profiles = new ArrayList<>();
        final Map<String, CandidatePackage> candidatePackages = new LinkedHashMap<>();
        final Map<String, RealmSelection> selections = new LinkedHashMap<>();
        final Map<String, RealmSeed> seeds = new LinkedHashMap<>();
        final Map<String, CapitalCityIntent> capitalIntents = new LinkedHashMap<>();
        final Map<String, String> artifacts = new LinkedHashMap<>();
        Map<String, PatchSummary> patchSummaries = new LinkedHashMap<>();
        Map<String, ContinentSummary> continentSummaries = new LinkedHashMap<>();
        Map<String, ExpansionBudget> expansionBudgets = new LinkedHashMap<>();
        Map<String, TerrainCostProfile> terrainCostProfiles = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> stopReasons = new LinkedHashMap<>();
        Map<String, Map<String, Double>> terrainCostBreakdowns = new LinkedHashMap<>();
        Map<String, Double> realmClaimCostSums = new LinkedHashMap<>();
        Map<String, Double> realmMaxClaimCosts = new LinkedHashMap<>();
        Map<String, Integer> realmClaimCounts = new LinkedHashMap<>();
        Map<String, String> territoryCellStatuses = new LinkedHashMap<>();
        Map<String, Double> territoryClaimCosts = new LinkedHashMap<>();
        JsonElement worldTheme;
        String qualityMode = "strict";
        String expansionModel = "action_budget";
        RealmTerritoryMap territory;
        CitySeedRegistry registry;
        JsonObject scoreManifest;

        RealmRun(String runId, Path runDirectory, WorldSurveyResult surveyResult) {
            this.runId = runId;
            this.runDirectory = runDirectory;
            this.surveyResult = surveyResult;
        }

        RealmProfile profile(String realmId) {
            return profiles.stream()
                    .filter(profile -> profile.realmId.equals(realmId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown realmId: " + realmId));
        }

        JsonObject artifactsJson() {
            JsonObject json = new JsonObject();
            json.addProperty("runDirectory", runDirectory.toAbsolutePath().toString());
            for (Map.Entry<String, String> entry : artifacts.entrySet()) {
                json.addProperty(entry.getKey(), runDirectory.resolve(entry.getValue()).toAbsolutePath().toString());
            }
            return json;
        }
    }

    private static final class WorldCell {
        final int gridX;
        final int gridZ;
        final int blockX;
        final int blockZ;
        String continentId;
        String patchId;
        final String landWater;
        final String landform;
        final double heightAvg;
        final double slopeAvg;
        final double waterDistanceBlocks;
        final List<String> flags;
        final WorldFeatureCell feature;
        final int cellStepBlocks;
        double relativeHeightRank = 0.5;
        double localHeightRank = 0.5;
        double regionalHeightRank = 0.5;
        double heightRankStability = 1.0;
        double tpiLocal = 0.0;
        double tpiRegional = 0.0;
        double devLocal = 0.0;
        double devRegional = 0.0;
        double devMaxMagnitude = 0.0;
        int devMaxScaleBlocks = 0;
        int localScaleBlocks = 0;
        int regionalScaleBlocks = 0;
        double roughnessLocal = 0.0;
        double roughnessRegional = 0.0;
        double reliefLocalP90P10 = 0.0;
        double reliefRegionalP90P10 = 0.0;
        int plateauCoreScaleBlocks = 0;
        int plateauOuterScaleBlocks = 0;
        double plateauCoreMeanHeight = 0.0;
        double plateauOuterMeanHeight = 0.0;
        double plateauProminence = 0.0;
        double plateauCoreFlatSupport = 0.0;
        String geomorphonClass = "unknown";
        double landformConfidence = 0.5;
        String waterEdgeType = "";
        String waterComponentId = "";
        String waterComponentType = "";
        double waterBoundaryConfidence = 1.0;
        int waterComponentAreaCells = 0;

        WorldCell(int gridX, int gridZ, int blockX, int blockZ, String continentId, String patchId,
                String landWater, String landform, double heightAvg, double slopeAvg, double waterDistanceBlocks,
                List<String> flags, WorldFeatureCell feature, int cellStepBlocks) {
            this.gridX = gridX;
            this.gridZ = gridZ;
            this.blockX = blockX;
            this.blockZ = blockZ;
            this.continentId = continentId == null ? "" : continentId;
            this.patchId = patchId == null ? "" : patchId;
            this.landWater = landWater;
            this.landform = landform;
            this.heightAvg = heightAvg;
            this.slopeAvg = slopeAvg;
            this.waterDistanceBlocks = waterDistanceBlocks;
            this.flags = List.copyOf(flags);
            this.feature = feature;
            this.cellStepBlocks = cellStepBlocks;
        }

        boolean assignableLand() {
            return !"water".equals(landWater) && !"unknown".equals(landWater);
        }

        JsonObject asJson() {
            JsonObject json = new JsonObject();
            json.addProperty("gridX", gridX);
            json.addProperty("gridZ", gridZ);
            json.addProperty("blockX", blockX);
            json.addProperty("blockZ", blockZ);
            if (!continentId.isBlank()) {
                json.addProperty("continentId", continentId);
            }
            if (!patchId.isBlank()) {
                json.addProperty("patchId", patchId);
            }
            json.addProperty("landWater", landWater);
            json.addProperty("landform", landform);
            json.addProperty("baseLandform", baseLandform());
            json.add("landformTags", stringArray(landformTags()));
            json.addProperty("heightAvg", heightAvg);
            json.addProperty("slopeAvg", slopeAvg);
            json.addProperty("relativeHeightRank", relativeHeightRank);
            json.addProperty("scanHeightRank", relativeHeightRank);
            json.addProperty("localHeightRank", localHeightRank);
            json.addProperty("regionalHeightRank", regionalHeightRank);
            json.addProperty("heightRankStability", heightRankStability);
            json.addProperty("landformConfidence", landformConfidence);
            json.add("landformEvidence", terrainEvidenceJson());
            json.add("overlayTags", stringArray(overlayTags()));
            json.add("debugReasons", stringArray(debugReasons()));
            json.add("terrainMetrics", terrainMetricsJson());
            json.addProperty("waterFrac", waterFrac());
            if (!waterEdgeType.isBlank()) {
                json.addProperty("waterEdgeType", waterEdgeType);
                if (!waterComponentType.isBlank()) {
                    json.addProperty("waterComponentType", waterComponentType);
                }
                json.addProperty("waterBoundaryConfidence", waterBoundaryConfidence);
                if (!waterComponentId.isBlank()) {
                    json.addProperty("waterComponentId", waterComponentId);
                }
                if (waterComponentAreaCells > 0) {
                    json.addProperty("waterComponentAreaCells", waterComponentAreaCells);
                }
            }
            json.addProperty("microSampleCount", microSampleCount());
            JsonObject heightStats = new JsonObject();
            heightStats.addProperty("p10", heightP10());
            heightStats.addProperty("p50", heightP50());
            heightStats.addProperty("p90", heightP90());
            heightStats.addProperty("robustRelief", robustRelief());
            json.add("heightStats", heightStats);
            JsonObject slopeStats = new JsonObject();
            slopeStats.addProperty("mean", slopeMean());
            slopeStats.addProperty("p90", slopeP90());
            slopeStats.addProperty("steepFrac", steepFrac());
            json.add("slopeStats", slopeStats);
            json.addProperty("barrierCost", barrierCost());
            json.addProperty("waterDistanceBlocks", waterDistanceBlocks);
            JsonObject biomeHist = new JsonObject();
            if (feature != null) {
                for (Map.Entry<String, Integer> entry : feature.biomeHistogram().entrySet()) {
                    biomeHist.addProperty(entry.getKey(), entry.getValue());
                }
            }
            json.add("biomeHist", biomeHist);
            json.add("flags", stringArray(flags));
            return json;
        }

        String baseLandform() {
            if (waterFrac() >= 0.65 || "water".equals(landWater)) {
                return "water";
            }
            if ("unknown".equals(landWater) || "shore".equals(landWater)) {
                return landWater;
            }
            if (highStepLocalMetrics()) {
                return highStepBaseLandform();
            }
            if (robustRelief() >= 28.0 || slopeP90() >= 16.0) {
                return "ridge";
            }
            if (robustRelief() >= 12.0 || slopeP90() >= 8.0) {
                return "upland";
            }
            return switch (landform) {
                case "plain", "terrace" -> "lowland";
                case "valley", "basin" -> "valley";
                case "ridge" -> "ridge";
                case "slope", "cliff" -> "upland";
                default -> landform;
            };
        }

        String highStepBaseLandform() {
            boolean flat = flatLocalSurface();
            boolean veryFlat = veryFlatLocalSurface();
            boolean relativeHigh = localHeightRank >= 0.65 || regionalHeightRank >= 0.65;
            boolean relativeLow = localHeightRank <= 0.45 || regionalHeightRank <= 0.45;
            boolean ridgeShape = "ridge".equals(geomorphonClass) || "shoulder".equals(geomorphonClass)
                    || "peak".equals(geomorphonClass);
            boolean valleyShape = "valley".equals(geomorphonClass) || "footslope".equals(geomorphonClass)
                    || "pit".equals(geomorphonClass);
            boolean ridge = relativeHigh && ridgeShape
                    && (slopeP90() >= 18.0 || steepFrac() >= STEEP_FRACTION_THRESHOLD
                    || robustRelief() >= RIDGE_RELIEF_THRESHOLD || devMaxMagnitude >= 1.0);
            if (ridge) {
                return "ridge";
            }
            if ((("valley".equals(landform) || "basin".equals(landform)) || valleyShape)
                    && relativeLow && slopeP90() < STEEP_SLOPE_P90_THRESHOLD) {
                return "valley";
            }
            if (strictPlateau()) {
                return "plateau";
            }
            if ((flat && localHeightRank <= LOWLAND_HEIGHT_RANK_THRESHOLD && regionalHeightRank <= 0.65)
                    || (veryFlat && localHeightRank <= LOWLAND_MID_FLAT_HEIGHT_RANK_THRESHOLD
                    && devMaxMagnitude < 1.0)) {
                return "lowland";
            }
            if (localHeightRank >= UPLAND_HEIGHT_RANK_THRESHOLD
                    || regionalHeightRank >= UPLAND_HEIGHT_RANK_THRESHOLD
                    || relativeHeightRank >= 0.82
                    || slopeP90() >= UPLAND_SLOPE_P90_THRESHOLD
                    || robustRelief() >= UPLAND_RELIEF_THRESHOLD
                    || steepFrac() >= STEEP_FRACTION_THRESHOLD) {
                return "upland";
            }
            return "lowland";
        }

        boolean strictPlateau() {
            return flatLocalSurface()
                    && localHeightRank >= PLATEAU_LOCAL_HEIGHT_RANK_THRESHOLD
                    && regionalHeightRank >= PLATEAU_REGIONAL_HEIGHT_RANK_THRESHOLD
                    && plateauProminence >= PLATEAU_PROMINENCE_THRESHOLD
                    && plateauCoreFlatSupport >= PLATEAU_CORE_FLAT_SUPPORT_THRESHOLD
                    && roughnessLocal <= 18.0;
        }

        List<String> landformTags() {
            Set<String> tags = new LinkedHashSet<>();
            String base = baseLandform();
            if (highStepLocalMetrics()) {
                if (!landform.equals(base)) {
                    if ("cliff".equals(landform)) {
                        tags.add("cliff_candidate");
                    } else if ("slope".equals(landform)) {
                        tags.add("slope_context");
                    } else {
                        tags.add(landform);
                    }
                }
                if (localSteep()) {
                    tags.add("steep");
                }
                if (localCliff()) {
                    tags.add("cliff");
                } else if ("cliff".equals(landform) && !localSteep()) {
                    tags.add("micro_contradiction");
                }
            } else {
                if (!landform.equals(base)) {
                    tags.add(landform);
                }
                if ("cliff".equals(landform) || localSteep()) {
                    tags.add("steep");
                }
                if ("cliff".equals(landform) && (steepFrac() >= CLIFF_FRACTION_THRESHOLD
                        || slopeP90() >= CLIFF_SLOPE_P90_THRESHOLD)) {
                    tags.add("cliff");
                }
            }
            if ((slopeP90() >= UPLAND_SLOPE_P90_THRESHOLD || steepFrac() >= STEEP_FRACTION_THRESHOLD
                    || robustRelief() >= UPLAND_RELIEF_THRESHOLD)
                    && ("ridge".equals(landform) || "slope".equals(landform) || "cliff".equals(landform)
                    || "ridge".equals(base) || "upland".equals(base))) {
                tags.add("mountain_front");
            }
            if (landformConfidence < MIXED_CELL_SUPPORT_THRESHOLD) {
                tags.add("low_confidence");
            }
            if (Math.abs(localHeightRank - regionalHeightRank) > TERRAIN_RANK_DRIFT_THRESHOLD) {
                tags.add("rank_drift");
            }
            if (supportConfidence() < MIXED_CELL_SUPPORT_THRESHOLD) {
                tags.add("mixed_cell");
            }
            tags.addAll(waterEdgeTags());
            return List.copyOf(tags);
        }

        List<String> waterEdgeTags() {
            double water = waterFrac();
            if ("water".equals(landWater) || "unknown".equals(landWater) || water >= WATER_COMPONENT_MIN_FRACTION
                    || (!"shore".equals(landWater) && !coastalMix(water))) {
                return List.of();
            }
            Set<String> tags = new LinkedHashSet<>();
            tags.add("water_edge");
            String edgeType = waterEdgeType.isBlank() ? localWaterEdgeType(this) : waterEdgeType;
            if ("seacoast".equals(edgeType)) {
                tags.add("seacoast");
                tags.add("coastal");
            } else if ("riverbank".equals(edgeType)) {
                tags.add("riverbank");
            } else if ("boundary_truncated".equals(edgeType)) {
                tags.add("boundary_truncated");
                tags.add("open_water_unknown");
            } else {
                tags.add("lakeshore");
            }
            return List.copyOf(tags);
        }

        List<String> overlayTags() {
            List<String> tags = landformTags();
            return tags.stream()
                    .filter(tag -> List.of("cliff", "steep", "water_edge", "seacoast", "riverbank",
                            "lakeshore", "coastal", "boundary_truncated", "open_water_unknown",
                            "mixed_cell", "low_confidence", "rank_drift", "mountain_front").contains(tag))
                    .toList();
        }

        JsonArray terrainEvidenceJson() {
            JsonArray array = new JsonArray();
            addEvidence(array, "scan_height_rank", relativeHeightRank, "本次扫描内高度分位");
            addEvidence(array, "local_height_rank", localHeightRank, "局部物理尺度高度分位");
            addEvidence(array, "regional_height_rank", regionalHeightRank, "区域物理尺度高度分位");
            addEvidence(array, "dev_local", devLocal, "局部 DEV 相对位置");
            addEvidence(array, "dev_regional", devRegional, "区域 DEV 相对位置");
            addEvidence(array, "roughness_local", roughnessLocal, "局部粗糙度");
            addEvidence(array, "plateau_prominence", plateauProminence, "核心面域相对外圈平均高度抬升");
            addEvidence(array, "plateau_core_flat_support", plateauCoreFlatSupport, "核心面域内连续平顶支持率");
            addEvidence(array, "geomorphon", geomorphonClass, "简化 8 方向形态");
            return array;
        }

        private void addEvidence(JsonArray array, String name, double value, String reason) {
            JsonObject item = new JsonObject();
            item.addProperty("name", name);
            item.addProperty("value", value);
            item.addProperty("reason", reason);
            array.add(item);
        }

        private void addEvidence(JsonArray array, String name, String value, String reason) {
            JsonObject item = new JsonObject();
            item.addProperty("name", name);
            item.addProperty("value", value);
            item.addProperty("reason", reason);
            array.add(item);
        }

        JsonObject terrainMetricsJson() {
            JsonObject json = new JsonObject();
            json.addProperty("localScaleBlocks", localScaleBlocks);
            json.addProperty("regionalScaleBlocks", regionalScaleBlocks);
            json.addProperty("plateauCoreScaleBlocks", plateauCoreScaleBlocks);
            json.addProperty("plateauOuterScaleBlocks", plateauOuterScaleBlocks);
            json.addProperty("scanHeightRank", relativeHeightRank);
            json.addProperty("localHeightRank", localHeightRank);
            json.addProperty("regionalHeightRank", regionalHeightRank);
            json.addProperty("heightRankStability", heightRankStability);
            json.addProperty("tpiLocal", tpiLocal);
            json.addProperty("tpiRegional", tpiRegional);
            json.addProperty("devLocal", devLocal);
            json.addProperty("devRegional", devRegional);
            json.addProperty("devMaxMagnitude", devMaxMagnitude);
            json.addProperty("devMaxScaleBlocks", devMaxScaleBlocks);
            json.addProperty("roughnessLocal", roughnessLocal);
            json.addProperty("roughnessRegional", roughnessRegional);
            json.addProperty("reliefLocalP90P10", reliefLocalP90P10);
            json.addProperty("reliefRegionalP90P10", reliefRegionalP90P10);
            json.addProperty("plateauCoreMeanHeight", plateauCoreMeanHeight);
            json.addProperty("plateauOuterMeanHeight", plateauOuterMeanHeight);
            json.addProperty("plateauProminence", plateauProminence);
            json.addProperty("plateauCoreFlatSupport", plateauCoreFlatSupport);
            json.addProperty("plateauProminenceThreshold", PLATEAU_PROMINENCE_THRESHOLD);
            json.addProperty("plateauCoreFlatSupportThreshold", PLATEAU_CORE_FLAT_SUPPORT_THRESHOLD);
            json.addProperty("geomorphonClass", geomorphonClass);
            json.addProperty("supportConfidence", supportConfidence());
            return json;
        }

        List<String> debugReasons() {
            List<String> reasons = new ArrayList<>();
            reasons.add("base=" + baseLandform());
            reasons.add("scanRank=" + round(relativeHeightRank));
            reasons.add("localRank=" + round(localHeightRank));
            reasons.add("regionalRank=" + round(regionalHeightRank));
            reasons.add("devMax=" + round(devMaxMagnitude) + "@" + devMaxScaleBlocks + "b");
            reasons.add("roughLocal=" + round(roughnessLocal));
            reasons.add("plateauProminence=" + round(plateauProminence));
            reasons.add("plateauSupport=" + round(plateauCoreFlatSupport));
            reasons.add("geomorphon=" + geomorphonClass);
            reasons.add("confidence=" + round(landformConfidence));
            if (!waterEdgeType.isBlank()) {
                reasons.add("waterEdge=" + waterEdgeType + "/" + waterComponentType
                        + "/confidence=" + round(waterBoundaryConfidence));
            }
            return reasons;
        }

        double supportConfidence() {
            if (waterFrac() > 0.05 && waterFrac() < 0.95) {
                return Math.max(0.35, 1.0 - shoreMixScore(waterFrac()) * 0.45);
            }
            if (robustRelief() >= UPLAND_RELIEF_THRESHOLD && steepFrac() < STEEP_FRACTION_THRESHOLD) {
                return 0.60;
            }
            if (Math.abs(localHeightRank - regionalHeightRank) > TERRAIN_RANK_DRIFT_THRESHOLD) {
                return 0.65;
            }
            return 0.90;
        }

        boolean highStepLocalMetrics() {
            return cellStepBlocks >= HIGH_STEP_LOCAL_METRICS_MIN_STEP_BLOCKS && feature != null && microSampleCount() > 0;
        }

        boolean localSteep() {
            return steepFrac() >= STEEP_FRACTION_THRESHOLD || slopeP90() >= STEEP_SLOPE_P90_THRESHOLD;
        }

        boolean localCliff() {
            double threshold = coastalMix(waterFrac()) ? COASTAL_CLIFF_FRACTION_THRESHOLD : CLIFF_FRACTION_THRESHOLD;
            return steepFrac() >= threshold && slopeP90() >= CLIFF_SLOPE_P90_THRESHOLD;
        }

        boolean flatLocalSurface() {
            return slopeP90() <= FLAT_SLOPE_P90_THRESHOLD
                    && steepFrac() <= FLAT_STEEP_FRACTION_THRESHOLD
                    && robustRelief() <= FLAT_RELIEF_THRESHOLD;
        }

        boolean veryFlatLocalSurface() {
            return slopeP90() <= VERY_FLAT_SLOPE_P90_THRESHOLD
                    && steepFrac() <= VERY_FLAT_STEEP_FRACTION_THRESHOLD
                    && robustRelief() <= VERY_FLAT_RELIEF_THRESHOLD;
        }

        double barrierCost() {
            double cost = 1.0 + Math.max(0.0, slopeP90()) / 8.0 + steepFrac() * 3.0;
            if (landformTags().contains("cliff")) {
                cost += 2.0;
            }
            if ("ridge".equals(baseLandform())) {
                cost += 1.0;
            }
            return cost;
        }

        double heightP10() {
            return feature == null ? heightAvg : feature.heightP10();
        }

        double heightP50() {
            return feature == null ? heightAvg : feature.heightP50();
        }

        double heightP90() {
            return feature == null ? heightAvg : feature.heightP90();
        }

        double robustRelief() {
            return feature == null ? 0.0 : feature.robustRelief();
        }

        double slopeMean() {
            return feature == null ? slopeAvg : feature.slopeMean();
        }

        double slopeP90() {
            return feature == null ? slopeAvg : feature.slopeP90();
        }

        double steepFrac() {
            return feature == null ? (slopeAvg >= 14.0 ? 1.0 : 0.0) : feature.steepFrac();
        }

        double waterFrac() {
            return feature == null ? ("water".equals(landWater) ? 1.0 : 0.0) : feature.waterFrac();
        }

        int microSampleCount() {
            return feature == null ? 0 : feature.microSampleCount();
        }
    }

    private static final class PatchAccumulator {
        final String patchId;
        final List<WorldCell> cells = new ArrayList<>();
        String patchLandform = "";
        boolean touchesWater;

        PatchAccumulator(String patchId) {
            this.patchId = patchId;
        }

        void add(WorldCell cell) {
            cells.add(cell);
            if ("shore".equals(cell.landWater)) {
                touchesWater = true;
            }
        }

        PatchSummary toSummary(int cellStepBlocks) {
            int minX = cells.stream().mapToInt(cell -> cell.gridX).min().orElse(0);
            int minZ = cells.stream().mapToInt(cell -> cell.gridZ).min().orElse(0);
            int maxX = cells.stream().mapToInt(cell -> cell.gridX).max().orElse(0);
            int maxZ = cells.stream().mapToInt(cell -> cell.gridZ).max().orElse(0);
            double centerX = cells.stream().mapToInt(cell -> cell.gridX).average().orElse(minX);
            double centerZ = cells.stream().mapToInt(cell -> cell.gridZ).average().orElse(minZ);
            String continentId = cells.stream().map(cell -> cell.continentId).filter(id -> !id.isBlank()).findFirst().orElse("");
            String landform = patchLandform.isBlank() ? cells.get(0).landform : patchLandform;
            boolean assignable = cells.stream().anyMatch(WorldCell::assignableLand);
            double coastalRatio = cells.stream().filter(cell -> "shore".equals(cell.landWater)).count() / (double) cells.size();
            double flatRatio = cells.stream().filter(cell -> cell.slopeAvg < 0.25).count() / (double) cells.size();
            return new PatchSummary(patchId, continentId, landform, assignable, cells.size(),
                    (int) Math.round(centerX), (int) Math.round(centerZ),
                    (int) Math.round(centerX * cellStepBlocks), (int) Math.round(centerZ * cellStepBlocks),
                    minX, minZ, maxX, maxZ, coastalRatio, flatRatio, touchesWater);
        }
    }

    private record PatchSummary(String patchId, String continentId, String landform, boolean assignableLand,
            int areaCells, int centerGridX, int centerGridZ, int centerBlockX, int centerBlockZ,
            int minGridX, int minGridZ, int maxGridX, int maxGridZ, double coastalRatio, double flatRatio,
            boolean touchesWater) {
        JsonObject asJson() {
            JsonObject json = new JsonObject();
            json.addProperty("patchId", patchId);
            if (!continentId.isBlank()) {
                json.addProperty("continentId", continentId);
            }
            json.addProperty("landform", landform);
            json.addProperty("areaCells", areaCells);
            JsonObject centerGrid = new JsonObject();
            centerGrid.addProperty("x", centerGridX);
            centerGrid.addProperty("z", centerGridZ);
            json.add("centerGrid", centerGrid);
            JsonObject centerBlock = new JsonObject();
            centerBlock.addProperty("x", centerBlockX);
            centerBlock.addProperty("z", centerBlockZ);
            json.add("centerBlock", centerBlock);
            JsonObject bounds = new JsonObject();
            bounds.addProperty("minX", minGridX);
            bounds.addProperty("minZ", minGridZ);
            bounds.addProperty("maxX", maxGridX);
            bounds.addProperty("maxZ", maxGridZ);
            json.add("boundsGrid", bounds);
            JsonObject summary = new JsonObject();
            summary.addProperty("coastalRatio", coastalRatio);
            summary.addProperty("flatRatio", flatRatio);
            summary.addProperty("waterAccess", touchesWater ? "good" : "unknown");
            json.add("summary", summary);
            return json;
        }
    }

    private static final class ContinentAccumulator {
        final String continentId;
        final List<WorldCell> cells = new ArrayList<>();

        ContinentAccumulator(String continentId) {
            this.continentId = continentId;
        }

        void add(WorldCell cell) {
            cells.add(cell);
        }

        ContinentSummary toSummary() {
            double centerBlockX = cells.stream().mapToInt(cell -> cell.blockX).average().orElse(0.0);
            double centerBlockZ = cells.stream().mapToInt(cell -> cell.blockZ).average().orElse(0.0);
            Map<String, Integer> landforms = new LinkedHashMap<>();
            for (WorldCell cell : cells) {
                landforms.put(cell.landform, landforms.getOrDefault(cell.landform, 0) + 1);
            }
            List<String> primary = landforms.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(4)
                    .map(Map.Entry::getKey)
                    .toList();
            return new ContinentSummary(continentId, cells.size(), (int) Math.round(centerBlockX),
                    (int) Math.round(centerBlockZ), primary);
        }
    }

    private record ContinentSummary(String continentId, int areaCells, int centerBlockX, int centerBlockZ,
            List<String> primaryLandforms) {
        JsonObject asJson() {
            JsonObject json = new JsonObject();
            json.addProperty("continentId", continentId);
            json.addProperty("areaCells", areaCells);
            JsonObject center = new JsonObject();
            center.addProperty("x", centerBlockX);
            center.addProperty("z", centerBlockZ);
            json.add("centerBlock", center);
            json.add("primaryLandforms", stringArray(primaryLandforms));
            return json;
        }
    }

    private static final class RealmProfile {
        final String realmId;
        final String name;
        final String targetContinentId;
        final String theme;
        final List<String> cultureTags;
        final List<String> industryTags;
        final List<String> materialTags;
        final List<String> landformPreferences;
        final List<String> avoidLandforms;
        ScalePlan scalePlan;
        final ExpansionStyle expansionStyle;

        RealmProfile(String realmId, String name, String targetContinentId, String theme, List<String> cultureTags,
                List<String> industryTags, List<String> materialTags, List<String> landformPreferences,
                List<String> avoidLandforms, ScalePlan scalePlan, ExpansionStyle expansionStyle) {
            this.realmId = realmId;
            this.name = name;
            this.targetContinentId = targetContinentId;
            this.theme = theme;
            this.cultureTags = List.copyOf(cultureTags);
            this.industryTags = List.copyOf(industryTags);
            this.materialTags = List.copyOf(materialTags);
            this.landformPreferences = List.copyOf(landformPreferences);
            this.avoidLandforms = List.copyOf(avoidLandforms);
            this.scalePlan = scalePlan;
            this.expansionStyle = expansionStyle;
        }

        static RealmProfile fromJson(JsonObject object, String defaultContinent, int index) {
            String realmId = safeId(stringValue(object, "realmId", "realm_" + index));
            String name = stringValue(object, "name", displayName(realmId));
            String continent = stringValue(object, "targetContinentId", defaultContinent);
            String theme = stringValue(object, "theme", "debug realm");
            ScalePlan scale = ScalePlan.fromJson(object.has("scalePlan") && object.get("scalePlan").isJsonObject()
                    ? object.getAsJsonObject("scalePlan") : new JsonObject(), defaultContinent);
            ExpansionStyle style = ExpansionStyle.fromJson(object.has("expansionStyle") && object.get("expansionStyle").isJsonObject()
                    ? object.getAsJsonObject("expansionStyle") : new JsonObject());
            return new RealmProfile(realmId, name, continent, theme, stringList(object, "cultureTags"),
                    stringList(object, "industryTags"), stringList(object, "materialTags"),
                    stringList(object, "landformPreferences"), stringList(object, "avoidLandforms"), scale, style);
        }

        JsonObject asJson() {
            JsonObject json = new JsonObject();
            json.addProperty("realmId", realmId);
            json.addProperty("name", name);
            json.addProperty("targetContinentId", targetContinentId);
            json.addProperty("theme", theme);
            json.add("cultureTags", stringArray(cultureTags));
            json.add("industryTags", stringArray(industryTags));
            json.add("materialTags", stringArray(materialTags));
            json.add("landformPreferences", stringArray(landformPreferences));
            json.add("avoidLandforms", stringArray(avoidLandforms));
            json.add("scalePlan", scalePlan.asJson());
            json.add("expansionStyle", expansionStyle.asJson());
            return json;
        }
    }

    private static final class ScalePlan {
        final String priority;
        final double targetAreaRatio;
        final double minAreaRatio;
        final double maxAreaRatio;
        final String normalizationGroup;

        ScalePlan(String priority, double targetAreaRatio, double minAreaRatio, double maxAreaRatio,
                String normalizationGroup) {
            this.priority = priority;
            this.targetAreaRatio = clamp(targetAreaRatio, 0.0, 1.0);
            this.minAreaRatio = clamp(minAreaRatio, 0.0, 1.0);
            this.maxAreaRatio = clamp(Math.max(maxAreaRatio, this.minAreaRatio), 0.0, 1.0);
            this.normalizationGroup = normalizationGroup;
        }

        static ScalePlan fromJson(JsonObject object, String defaultGroup) {
            return new ScalePlan(stringValue(object, "priority", "normal"),
                    doubleValue(object, "targetAreaRatio", 0.33),
                    doubleValue(object, "minAreaRatio", 0.05),
                    doubleValue(object, "maxAreaRatio", 0.8),
                    stringValue(object, "normalizationGroup", defaultGroup));
        }

        ScalePlan withGroup(String group) {
            return new ScalePlan(priority, targetAreaRatio, minAreaRatio, maxAreaRatio, group);
        }

        JsonObject asJson() {
            JsonObject json = new JsonObject();
            json.addProperty("priority", priority);
            json.addProperty("targetAreaRatio", targetAreaRatio);
            json.addProperty("minAreaRatio", minAreaRatio);
            json.addProperty("maxAreaRatio", maxAreaRatio);
            json.addProperty("normalizationGroup", normalizationGroup);
            return json;
        }
    }

    private record NormalizedScale(ScalePlan raw, double normalizedTargetAreaRatio) {
        JsonObject asJson() {
            JsonObject json = raw.asJson();
            json.addProperty("normalizedTargetAreaRatio", normalizedTargetAreaRatio);
            return json;
        }
    }

    private record ExpansionStyle(double waterAffinity, double mountainAffinity, double forestAffinity,
            double compactness, double coastalBias, double resourceSeeking, double borderPressure,
            String seaCrossingPolicy) {
        static ExpansionStyle fromJson(JsonObject object) {
            return new ExpansionStyle(
                    clamp(doubleValue(object, "waterAffinity", 0.5), 0.0, 1.0),
                    clamp(doubleValue(object, "mountainAffinity", 0.0), -1.0, 1.0),
                    clamp(doubleValue(object, "forestAffinity", 0.0), -1.0, 1.0),
                    clamp(doubleValue(object, "compactness", 0.5), 0.0, 1.0),
                    clamp(doubleValue(object, "coastalBias", 0.3), 0.0, 1.0),
                    clamp(doubleValue(object, "resourceSeeking", 0.4), 0.0, 1.0),
                    clamp(doubleValue(object, "borderPressure", 0.3), 0.0, 1.0),
                    stringValue(object, "seaCrossingPolicy", "none"));
        }

        JsonObject asJson() {
            JsonObject json = new JsonObject();
            json.addProperty("waterAffinity", waterAffinity);
            json.addProperty("mountainAffinity", mountainAffinity);
            json.addProperty("forestAffinity", forestAffinity);
            json.addProperty("compactness", compactness);
            json.addProperty("coastalBias", coastalBias);
            json.addProperty("resourceSeeking", resourceSeeking);
            json.addProperty("borderPressure", borderPressure);
            json.addProperty("seaCrossingPolicy", seaCrossingPolicy);
            return json;
        }
    }

    private record CandidatePackage(String packageId, String realmId, String surveyId, Set<String> allowedPatches,
            List<GridPoint> blockedCells, List<GridPoint> occupiedSeeds, GridPoint originBlock, int cellStepBlocks,
            GridPoint suggestedPoint, String candidateMapImage) {
        JsonObject asJson() {
            JsonObject json = new JsonObject();
            json.addProperty("packageId", packageId);
            json.addProperty("realmId", realmId);
            json.addProperty("surveyId", surveyId);
            json.addProperty("candidateMapImage", candidateMapImage);
            json.addProperty("mapRole", "continent_scope_reference");
            json.addProperty("scopeBasis", "target_continent_assignable_land");
            json.addProperty("profileDifferentiated", false);
            json.addProperty("selectionMode", "patch_explorer_primary");
            JsonObject legend = new JsonObject();
            legend.add("originBlock", originBlock.asJson());
            legend.addProperty("cellStepBlocks", cellStepBlocks);
            legend.addProperty("coordinateFormat", "gridX,gridZ");
            json.add("gridLegend", legend);
            json.add("allowedPatches", stringArray(allowedPatches));
            JsonArray blocked = new JsonArray();
            for (GridPoint point : blockedCells) {
                blocked.add(point.asGridJson());
            }
            json.add("blockedCells", blocked);
            JsonArray occupied = new JsonArray();
            for (GridPoint point : occupiedSeeds) {
                occupied.add(point.asGridJson());
            }
            json.add("occupiedSeeds", occupied);
            json.add("suggestedPoint", suggestedPoint.asGridJson());
            JsonObject rules = new JsonObject();
            rules.addProperty("primaryFlow",
                    "patch_explorer_open -> patch_explorer_show_candidates -> patch_explorer_select_candidate -> realm_t2_select_coordinate");
            rules.addProperty("directGridSubmission", "compatibility_only");
            rules.addProperty("returnFormat", "{ primary: { gridX, gridZ }, alternates: [], reason: string }");
            json.add("selectionRules", rules);
            return json;
        }
    }

    private record SelectionAttempt(GridPoint originalPoint, GridPoint finalPoint, WorldCell cell, boolean accepted,
            boolean snapApplied, List<String> warnings, List<String> errors) {
        static SelectionAttempt accepted(GridPoint original, GridPoint point, WorldCell cell, boolean snapApplied,
                List<String> warnings, List<String> errors) {
            return new SelectionAttempt(original, point, cell, true, snapApplied, List.copyOf(warnings), List.copyOf(errors));
        }

        static SelectionAttempt rejected(GridPoint original, List<String> errors) {
            return new SelectionAttempt(original, original, null, false, false, List.of(), List.copyOf(errors));
        }

        SelectionAttempt withOriginal(GridPoint original) {
            return new SelectionAttempt(original, finalPoint, cell, accepted, snapApplied, warnings, errors);
        }
    }

    private record RealmSelection(String selectionId, String realmId, String packageId, String selectedBy,
            GridPoint primaryGrid, GridPoint finalGrid, GridPoint primaryBlock, String reason,
            String validationStatus, boolean snapApplied, List<String> warnings, List<String> errors,
            String continentId, String patchId) {
        static RealmSelection from(String realmId, String packageId, String selectedBy, GridPoint submitted,
                SelectionAttempt attempt, String reason) {
            WorldCell cell = attempt.cell;
            GridPoint block = cell == null ? new GridPoint(0, 0) : new GridPoint(cell.blockX, cell.blockZ);
            String status = attempt.accepted ? (attempt.snapApplied ? "accepted_with_snap" : "accepted") : "rejected";
            return new RealmSelection("selection_" + realmId, realmId, packageId,
                    selectedBy == null || selectedBy.isBlank() ? "ai" : selectedBy, submitted, attempt.finalPoint,
                    block, reason == null ? "" : reason, status, attempt.snapApplied, attempt.warnings, attempt.errors,
                    cell == null ? "" : cell.continentId, cell == null ? "" : cell.patchId);
        }

        JsonObject asJson() {
            JsonObject json = new JsonObject();
            json.addProperty("selectionId", selectionId);
            json.addProperty("realmId", realmId);
            json.addProperty("packageId", packageId);
            json.addProperty("selectedBy", selectedBy);
            json.add("primaryGrid", primaryGrid.asJson());
            json.add("finalGrid", finalGrid.asJson());
            json.add("primaryBlock", primaryBlock.asJson());
            json.addProperty("reason", reason);
            JsonObject validation = new JsonObject();
            validation.addProperty("status", validationStatus);
            validation.addProperty("continentId", continentId);
            validation.addProperty("patchId", patchId);
            validation.addProperty("snapApplied", snapApplied);
            validation.add("warnings", stringArray(warnings));
            validation.add("errors", stringArray(errors));
            json.add("validation", validation);
            return json;
        }
    }

    private record RealmSeed(String realmId, String selectionId, GridPoint seedGrid, GridPoint seedBlock,
            String continentId, String patchId, ScalePlan scalePlan, ExpansionStyle expansionStyle) {
        static RealmSeed from(RealmProfile profile, RealmSelection selection, WorldCell cell) {
            return new RealmSeed(profile.realmId, selection.selectionId, new GridPoint(cell.gridX, cell.gridZ),
                    new GridPoint(cell.blockX, cell.blockZ), cell.continentId, cell.patchId,
                    profile.scalePlan, profile.expansionStyle);
        }

        JsonObject asJson() {
            JsonObject json = new JsonObject();
            json.addProperty("realmId", realmId);
            json.addProperty("selectionId", selectionId);
            json.add("seedGrid", seedGrid.asJson());
            json.add("seedBlock", seedBlock.asJson());
            json.addProperty("continentId", continentId);
            json.addProperty("patchId", patchId);
            json.add("scalePlan", scalePlan.asJson());
            json.add("expansionStyle", expansionStyle.asJson());
            return json;
        }
    }

    private record CapitalCityIntent(String citySeedId, String realmId, String cityRole,
            String theoreticalScale, boolean mustExist, List<String> requiredConditions,
            List<String> coreFunctions, String realmCoreSelectionId, String sourceMode) {
        static CapitalCityIntent from(RealmProfile profile, RealmSelection selection) {
            String scale = switch (profile.scalePlan.priority) {
                case "empire", "major" -> "capital";
                case "minor" -> "town";
                default -> "large_city";
            };
            return new CapitalCityIntent("city_" + profile.realmId + "_capital", profile.realmId, "capital",
                    scale, true, List.of("land", "inside_realm"),
                    List.of("administration", "market", "defense"), selection.selectionId,
                    "t2_realm_core_intent");
        }

        JsonObject asJson() {
            JsonObject json = new JsonObject();
            json.addProperty("citySeedId", citySeedId);
            json.addProperty("realmId", realmId);
            json.addProperty("cityRole", cityRole);
            json.addProperty("theoreticalScale", theoreticalScale);
            json.addProperty("mustExist", mustExist);
            json.add("requiredConditions", stringArray(requiredConditions));
            json.add("coreFunctions", stringArray(coreFunctions));
            json.addProperty("realmCoreSelectionId", realmCoreSelectionId);
            json.addProperty("sourceMode", sourceMode);
            return json;
        }
    }

    private record FrontierClaim(WorldCell cell, WorldCell parent, String realmId, double cost, long sequence) {
    }

    private record ActionFrontierClaim(String realmId, WorldCell cell, WorldCell parent,
            double cumulativeCost, int pathLength, long sequence) {
    }

    private record TerritoryBuildResult(Map<String, String> ownership, List<TerritoryRepair> repairs) {
        TerritoryBuildResult {
            ownership = Map.copyOf(ownership);
            repairs = List.copyOf(repairs);
        }
    }

    private record TerritoryRepair(String type, String realmId, String description, int affectedCells) {
        JsonObject asJson() {
            JsonObject json = new JsonObject();
            json.addProperty("type", type);
            json.addProperty("realmId", realmId);
            json.addProperty("description", description);
            json.addProperty("affectedCells", affectedCells);
            return json;
        }
    }

    private record RebalanceCandidate(String cellKey, String receiverRealmId, String donorRealmId, double score) {
    }

    private record ExpansionBudget(double baseActionBudget, double budgetMultiplier, double effectiveActionBudget,
            double softStopThreshold, double hardStopThreshold, double maxClaimCost, double wildlandTolerance) {
        JsonObject asJson() {
            JsonObject json = new JsonObject();
            json.addProperty("baseActionBudget", baseActionBudget);
            json.addProperty("budgetMultiplier", budgetMultiplier);
            json.addProperty("effectiveActionBudget", effectiveActionBudget);
            json.addProperty("softStopThreshold", softStopThreshold);
            json.addProperty("hardStopThreshold", hardStopThreshold);
            json.addProperty("maxClaimCost", maxClaimCost);
            json.addProperty("wildlandTolerance", wildlandTolerance);
            return json;
        }
    }

    private record TerrainCostProfile(Map<String, Double> baseCosts, Map<String, Double> tagCosts) {
        TerrainCostProfile {
            baseCosts = Map.copyOf(baseCosts);
            tagCosts = Map.copyOf(tagCosts);
        }

        double costFor(String baseLandform) {
            return baseCosts.getOrDefault(baseLandform, baseCosts.getOrDefault("lowland", 1.0));
        }

        double tagCost(String tag) {
            return tagCosts.getOrDefault(tag, 0.0);
        }

        JsonObject asJson() {
            JsonObject json = new JsonObject();
            JsonObject base = new JsonObject();
            for (Map.Entry<String, Double> entry : baseCosts.entrySet()) {
                if (Double.isFinite(entry.getValue())) {
                    base.addProperty(entry.getKey(), entry.getValue());
                } else {
                    base.addProperty(entry.getKey(), "blocked");
                }
            }
            JsonObject tags = new JsonObject();
            for (Map.Entry<String, Double> entry : tagCosts.entrySet()) {
                tags.addProperty(entry.getKey(), entry.getValue());
            }
            json.add("baseCosts", base);
            json.add("tagCosts", tags);
            return json;
        }
    }

    private record TagAuditMetrics(
            int sampleCount,
            double heightP05,
            double heightP50,
            double heightP95,
            double reliefP95P05,
            double slopeP90,
            double slopeP95,
            double slopeMax,
            double steepFrac,
            double waterFrac,
            double shoreMixScore,
            String dominantBiome,
            Map<String, Integer> biomeHist,
            List<String> referenceTags
    ) {
        TagAuditMetrics {
            biomeHist = Map.copyOf(biomeHist);
            referenceTags = List.copyOf(referenceTags);
        }

        JsonObject asJson() {
            JsonObject json = new JsonObject();
            json.addProperty("sampleCount", sampleCount);
            json.addProperty("heightP05", heightP05);
            json.addProperty("heightP50", heightP50);
            json.addProperty("heightP95", heightP95);
            json.addProperty("reliefP95P05", reliefP95P05);
            json.addProperty("slopeP90", slopeP90);
            json.addProperty("slopeP95", slopeP95);
            json.addProperty("slopeMax", slopeMax);
            json.addProperty("steepFrac", steepFrac);
            json.addProperty("waterFrac", waterFrac);
            json.addProperty("shoreMixScore", shoreMixScore);
            json.addProperty("dominantBiome", dominantBiome);
            JsonObject biomes = new JsonObject();
            for (Map.Entry<String, Integer> entry : biomeHist.entrySet()) {
                biomes.addProperty(entry.getKey(), entry.getValue());
            }
            json.add("biomeHist", biomes);
            json.add("referenceTags", stringArray(referenceTags));
            return json;
        }
    }

    private record AuditPoint(int x, int z) {
        JsonObject asJson() {
            JsonObject json = new JsonObject();
            json.addProperty("blockX", x);
            json.addProperty("blockZ", z);
            json.addProperty("tpCommand", "/tp @s " + x + " ~ " + z);
            return json;
        }
    }

    private record RepresentativePoints(
            AuditPoint cellCenter,
            AuditPoint highestMicroPoint,
            AuditPoint lowestMicroPoint,
            AuditPoint maxSlopeMicroPoint,
            AuditPoint recommendedTpPoint
    ) {
        JsonObject asJson() {
            JsonObject json = new JsonObject();
            json.add("cellCenter", cellCenter.asJson());
            json.add("highestMicroPoint", highestMicroPoint.asJson());
            json.add("lowestMicroPoint", lowestMicroPoint.asJson());
            json.add("maxSlopeMicroPoint", maxSlopeMicroPoint.asJson());
            json.add("recommendedTpPoint", recommendedTpPoint.asJson());
            return json;
        }
    }

    private record AuditCandidate(WorldCell cell, String layer) {
    }

    private record TerrainWindowMetrics(
            double heightRank,
            double tpi,
            double dev,
            double roughness,
            double reliefP90P10
    ) {
    }

    private record PlateauContextMetrics(
            double coreMeanHeight,
            double outerMeanHeight,
            double prominence,
            double coreFlatSupport,
            int coreCount,
            int outerCount
    ) {
    }

    private record TagAuditSample(
            int gridX,
            int gridZ,
            int blockX,
            int blockZ,
            int cellMinBlockX,
            int cellMinBlockZ,
            String auditLayer,
            String baseLandform,
            String coarseLandform,
            List<String> wTags,
            RepresentativePoints representativePoints,
            TagAuditMetrics pointMetrics,
            TagAuditMetrics cellMetrics,
            List<String> referenceTags,
            List<String> pointReferenceTags,
            List<String> cellReferenceTags,
            String cellReferenceBaseLandform,
            boolean mixedCell,
            boolean representativePointMismatch,
            boolean cliffMatch,
            boolean steepMatch,
            boolean coastalMatch
    ) {
        TagAuditSample {
            wTags = List.copyOf(wTags);
            referenceTags = List.copyOf(referenceTags);
            pointReferenceTags = List.copyOf(pointReferenceTags);
            cellReferenceTags = List.copyOf(cellReferenceTags);
        }

        static TagAuditSample from(WorldCell cell, String auditLayer, RepresentativePoints representativePoints,
                TagAuditMetrics pointMetrics, TagAuditMetrics cellMetrics) {
            List<String> wTags = cell.landformTags();
            List<String> pointReferenceTags = pointMetrics.referenceTags();
            List<String> cellReferenceTags = cellMetrics.referenceTags();
            String cellReferenceBase = referenceBaseLandform(cell, cellMetrics);
            boolean mixed = mixedCell(cellMetrics);
            boolean pointMismatch = !pointReferenceTags.equals(cellReferenceTags);
            return new TagAuditSample(cell.gridX, cell.gridZ, representativePoints.recommendedTpPoint.x,
                    representativePoints.recommendedTpPoint.z,
                    cell.blockX, cell.blockZ, auditLayer,
                    cell.baseLandform(), cell.landform, wTags, representativePoints, pointMetrics, cellMetrics,
                    pointReferenceTags, pointReferenceTags, cellReferenceTags, cellReferenceBase, mixed, pointMismatch,
                    wTags.contains("cliff") == cellReferenceTags.contains("cliff"),
                    wTags.contains("steep") == cellReferenceTags.contains("steep"),
                    wTags.contains("coastal") == cellReferenceTags.contains("coastal"));
        }

        JsonObject asJson() {
            JsonObject json = summaryJson();
            json.add("representativePoints", representativePoints.asJson());
            json.add("auditMetrics", pointMetrics.asJson());
            json.add("pointReferenceMetrics", pointMetrics.asJson());
            json.add("cellReferenceMetrics", cellMetrics.asJson());
            return json;
        }

        JsonObject summaryJson() {
            JsonObject json = new JsonObject();
            json.addProperty("gridX", gridX);
            json.addProperty("gridZ", gridZ);
            json.addProperty("blockX", blockX);
            json.addProperty("blockZ", blockZ);
            json.addProperty("cellMinBlockX", cellMinBlockX);
            json.addProperty("cellMinBlockZ", cellMinBlockZ);
            json.addProperty("auditLayer", auditLayer);
            json.addProperty("tpCommand", "/tp @s " + blockX + " ~ " + blockZ);
            json.addProperty("baseLandform", baseLandform);
            json.addProperty("coarseLandform", coarseLandform);
            json.addProperty("cellReferenceBaseLandform", cellReferenceBaseLandform);
            json.addProperty("mixedCell", mixedCell);
            json.addProperty("representativePointMismatch", representativePointMismatch);
            json.add("wTags", stringArray(wTags));
            json.add("referenceTags", stringArray(referenceTags));
            json.add("pointReferenceTags", stringArray(pointReferenceTags));
            json.add("cellReferenceTags", stringArray(cellReferenceTags));
            json.addProperty("cliffMatch", cliffMatch);
            json.addProperty("steepMatch", steepMatch);
            json.addProperty("coastalMatch", coastalMatch);
            return json;
        }

        private static String referenceBaseLandform(WorldCell cell, TagAuditMetrics metrics) {
            if (metrics.waterFrac >= 0.65) {
                return "water";
            }
            if (coastalMix(metrics.waterFrac)) {
                return "shore";
            }
            boolean flat = metrics.slopeP90 <= FLAT_SLOPE_P90_THRESHOLD
                    && metrics.steepFrac <= FLAT_STEEP_FRACTION_THRESHOLD
                    && metrics.reliefP95P05 <= FLAT_RELIEF_THRESHOLD;
            boolean rugged = metrics.slopeP90 >= UPLAND_SLOPE_P90_THRESHOLD
                    || metrics.steepFrac >= STEEP_FRACTION_THRESHOLD
                    || metrics.reliefP95P05 >= UPLAND_RELIEF_THRESHOLD;
            if (metrics.referenceTags.contains("cliff") || (rugged && metrics.reliefP95P05 >= RIDGE_RELIEF_THRESHOLD)) {
                return "ridge";
            }
            if (flat && cell.strictPlateau()) {
                return "plateau";
            }
            if (flat) {
                return "lowland";
            }
            return rugged ? "upland" : "lowland";
        }

        private static boolean mixedCell(TagAuditMetrics metrics) {
            double dominantSurface = Math.max(metrics.waterFrac, 1.0 - metrics.waterFrac);
            boolean mixedWater = metrics.waterFrac > 0.05 && metrics.waterFrac < 0.95;
            boolean mixedRelief = metrics.reliefP95P05 >= UPLAND_RELIEF_THRESHOLD
                    && metrics.steepFrac < STEEP_FRACTION_THRESHOLD;
            return mixedWater || mixedRelief || dominantSurface < MIXED_CELL_SUPPORT_THRESHOLD;
        }
    }

    private record TerritoryCell(int gridX, int gridZ, String realmId, String status, double claimStrength,
            double claimCost) {
        JsonObject asJson() {
            JsonObject json = new JsonObject();
            json.addProperty("gridX", gridX);
            json.addProperty("gridZ", gridZ);
            json.addProperty("realmId", realmId);
            json.addProperty("status", status);
            json.addProperty("claimStrength", claimStrength);
            json.addProperty("claimCost", claimCost);
            return json;
        }
    }

    private record RealmStats(String realmId, int areaCells, int targetAreaCells, int areaDeltaCells,
            double targetAreaRatio, double actualAreaRatio, double scaleMinAreaRatio, double scaleMaxAreaRatio,
            double coastalRatio, List<String> primaryLandforms,
            Set<String> neighbors, int componentCount, int largestComponentCells, double largestComponentRatio,
            double detachedAreaRatio, double holeAreaRatio, double naturalBoundaryFit,
            double budgetUsedRatio, double averageClaimCost, double maxClaimCost,
            Map<String, Double> terrainCostBreakdown, Map<String, Integer> stopReasonSummary) {
        JsonObject asJson() {
            JsonObject json = new JsonObject();
            json.addProperty("realmId", realmId);
            json.addProperty("areaCells", areaCells);
            json.addProperty("targetAreaCells", targetAreaCells);
            json.addProperty("areaDeltaCells", areaDeltaCells);
            json.addProperty("targetAreaRatio", targetAreaRatio);
            json.addProperty("actualAreaRatio", actualAreaRatio);
            json.addProperty("scaleMinAreaRatio", scaleMinAreaRatio);
            json.addProperty("scaleMaxAreaRatio", scaleMaxAreaRatio);
            json.addProperty("coastalRatio", coastalRatio);
            json.add("primaryLandforms", stringArray(primaryLandforms));
            json.add("neighbors", stringArray(neighbors));
            json.addProperty("componentCount", componentCount);
            json.addProperty("largestComponentCells", largestComponentCells);
            json.addProperty("largestComponentRatio", largestComponentRatio);
            json.addProperty("detachedAreaRatio", detachedAreaRatio);
            json.addProperty("holeAreaRatio", holeAreaRatio);
            json.addProperty("naturalBoundaryFit", naturalBoundaryFit);
            json.addProperty("budgetUsedRatio", budgetUsedRatio);
            json.addProperty("averageClaimCost", averageClaimCost);
            json.addProperty("maxClaimCost", maxClaimCost);
            JsonObject terrain = new JsonObject();
            for (Map.Entry<String, Double> entry : terrainCostBreakdown.entrySet()) {
                terrain.addProperty(entry.getKey(), entry.getValue());
            }
            json.add("terrainCostBreakdown", terrain);
            JsonObject reasons = new JsonObject();
            for (Map.Entry<String, Integer> entry : stopReasonSummary.entrySet()) {
                reasons.addProperty(entry.getKey(), entry.getValue());
            }
            json.add("stopReasonSummary", reasons);
            return json;
        }
    }

    private static final class RealmTerritoryMap {
        final String territoryMapId;
        final String runId;
        final String normalizationGroup;
        final String expansionModel;
        final List<TerritoryCell> cells;
        final Map<String, RealmStats> stats;
        final Map<String, NormalizedScale> scales;
        final List<String> warnings;
        final List<TerritoryRepair> repairs;
        final Map<String, ExpansionBudget> expansionBudgets;
        final Map<String, TerrainCostProfile> terrainCostProfiles;

        RealmTerritoryMap(String territoryMapId, String runId, String normalizationGroup, List<TerritoryCell> cells,
                Map<String, RealmStats> stats, Map<String, NormalizedScale> scales, List<String> warnings,
                List<TerritoryRepair> repairs, String expansionModel, Map<String, ExpansionBudget> expansionBudgets,
                Map<String, TerrainCostProfile> terrainCostProfiles) {
            this.territoryMapId = territoryMapId;
            this.runId = runId;
            this.normalizationGroup = normalizationGroup;
            this.expansionModel = expansionModel;
            this.cells = List.copyOf(cells);
            this.stats = Map.copyOf(stats);
            this.scales = Map.copyOf(scales);
            this.warnings = List.copyOf(warnings);
            this.repairs = List.copyOf(repairs);
            this.expansionBudgets = Map.copyOf(expansionBudgets);
            this.terrainCostProfiles = Map.copyOf(terrainCostProfiles);
        }

        static RealmTerritoryMap from(String runId, String group, List<WorldCell> landCells, Map<String, String> ownership,
                RealmRun run, Map<String, NormalizedScale> scales, Map<String, Integer> quotas,
                List<TerritoryRepair> repairs) {
            List<TerritoryCell> territoryCells = new ArrayList<>();
            for (WorldCell cell : landCells) {
                String cellKey = key(cell.gridX, cell.gridZ);
                String owner = ownership.get(cellKey);
                String status = run.territoryCellStatuses.getOrDefault(cellKey, owner == null ? "wild" : "owned");
                if (owner != null || !"owned".equals(status)) {
                    territoryCells.add(new TerritoryCell(cell.gridX, cell.gridZ, owner == null ? "" : owner,
                            status, owner == null ? 0.0 : 1.0,
                            run.territoryClaimCosts.getOrDefault(cellKey, 0.0)));
                }
            }
            Map<String, Set<String>> neighbors = new LinkedHashMap<>();
            Map<String, List<WorldCell>> byRealm = new LinkedHashMap<>();
            Map<String, String> owners = new LinkedHashMap<>();
            for (TerritoryCell cell : territoryCells) {
                if (!"owned".equals(cell.status)) {
                    continue;
                }
                owners.put(key(cell.gridX, cell.gridZ), cell.realmId);
                WorldCell worldCell = run.worldCellsByKey.get(key(cell.gridX, cell.gridZ));
                if (worldCell != null) {
                    byRealm.computeIfAbsent(cell.realmId, ignored -> new ArrayList<>()).add(worldCell);
                }
            }
            for (TerritoryCell cell : territoryCells) {
                if (!"owned".equals(cell.status)) {
                    continue;
                }
                for (int[] offset : DIRECTIONS) {
                    String other = owners.get(key(cell.gridX + offset[0], cell.gridZ + offset[1]));
                    if (other != null && !other.equals(cell.realmId)) {
                        neighbors.computeIfAbsent(cell.realmId, ignored -> new LinkedHashSet<>()).add(other);
                    }
                }
            }
            Map<String, RealmStats> stats = new LinkedHashMap<>();
            for (Map.Entry<String, List<WorldCell>> entry : byRealm.entrySet()) {
                List<WorldCell> owned = entry.getValue();
                double coastal = owned.stream().filter(cell -> "shore".equals(cell.landWater)).count() / (double) owned.size();
                Map<String, Integer> landforms = new LinkedHashMap<>();
                for (WorldCell cell : owned) {
                    landforms.put(cell.landform, landforms.getOrDefault(cell.landform, 0) + 1);
                }
                List<String> primary = landforms.entrySet().stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                        .limit(4)
                        .map(Map.Entry::getKey)
                        .toList();
                ComponentMetrics components = componentMetrics(entry.getKey(), owned, owners);
                int target = quotas.getOrDefault(entry.getKey(), owned.size());
                NormalizedScale scale = scales.get(entry.getKey());
                double minRatio = scale == null ? 0.0 : scale.raw.minAreaRatio;
                double maxRatio = scale == null ? 1.0 : scale.raw.maxAreaRatio;
                double targetRatio = landCells.isEmpty() ? 0.0 : target / (double) landCells.size();
                double actualRatio = landCells.isEmpty() ? 0.0 : owned.size() / (double) landCells.size();
                ExpansionBudget budget = run.expansionBudgets.get(entry.getKey());
                double budgetUsedRatio = budget == null || budget.effectiveActionBudget <= 0.0
                        ? 0.0 : run.realmMaxClaimCosts.getOrDefault(entry.getKey(), 0.0) / budget.effectiveActionBudget;
                int claimCount = Math.max(1, run.realmClaimCounts.getOrDefault(entry.getKey(), owned.size()));
                double avgClaimCost = run.realmClaimCostSums.getOrDefault(entry.getKey(), 0.0) / claimCount;
                stats.put(entry.getKey(), new RealmStats(entry.getKey(), owned.size(), target, owned.size() - target,
                        targetRatio, actualRatio, minRatio, maxRatio, coastal, primary,
                        neighbors.getOrDefault(entry.getKey(), Set.of()),
                        components.componentCount, components.largestComponentCells,
                        components.largestComponentRatio, components.detachedAreaRatio, 0.0,
                        naturalBoundaryFit(entry.getKey(), owned, owners, run.worldCellsByKey),
                        budgetUsedRatio, avgClaimCost, run.realmMaxClaimCosts.getOrDefault(entry.getKey(), 0.0),
                        run.terrainCostBreakdowns.getOrDefault(entry.getKey(), Map.of()),
                        run.stopReasons.getOrDefault(entry.getKey(), Map.of())));
            }
            List<String> warnings = new ArrayList<>();
            for (RealmProfile profile : run.profiles) {
                if (!stats.containsKey(profile.realmId)) {
                    warnings.add("Realm has no territory cells: " + profile.realmId);
                    continue;
                }
                RealmStats realmStats = stats.get(profile.realmId);
                if (realmStats.largestComponentRatio < STRICT_LARGEST_COMPONENT_RATIO
                        || realmStats.detachedAreaRatio > STRICT_DETACHED_AREA_RATIO) {
                    warnings.add("Realm topology below strict threshold: " + profile.realmId);
                }
            }
            return new RealmTerritoryMap("territory_" + runId, runId, group, territoryCells, stats, scales, warnings,
                    repairs, run.expansionModel, run.expansionBudgets, run.terrainCostProfiles);
        }

        Map<String, String> ownershipByKey() {
            Map<String, String> owners = new LinkedHashMap<>();
            for (TerritoryCell cell : cells) {
                if (!"owned".equals(cell.status)) {
                    continue;
                }
                owners.put(key(cell.gridX, cell.gridZ), cell.realmId);
            }
            return owners;
        }

        JsonObject asJson() {
            JsonObject json = new JsonObject();
            json.addProperty("territoryMapId", territoryMapId);
            json.addProperty("surveyId", "survey_" + runId);
            json.addProperty("normalizationGroup", normalizationGroup);
            json.addProperty("expansionModel", expansionModel);
            JsonArray cellArray = new JsonArray();
            for (TerritoryCell cell : cells) {
                cellArray.add(cell.asJson());
            }
            json.add("territoryCells", cellArray);
            JsonArray statsArray = new JsonArray();
            for (RealmStats stat : stats.values()) {
                statsArray.add(stat.asJson());
            }
            json.add("realmStats", statsArray);
            json.add("warnings", stringArray(warnings));
            json.add("normalizedScales", scalesJson());
            json.add("repairs", repairsJson());
            return json;
        }

        JsonObject reportJson() {
            JsonObject json = new JsonObject();
            json.addProperty("territoryMapId", territoryMapId);
            json.addProperty("normalizationGroup", normalizationGroup);
            json.addProperty("expansionModel", expansionModel);
            json.add("realmStats", asJson().get("realmStats"));
            json.add("normalizedScales", scalesJson());
            json.add("expansionBudgets", expansionBudgetsJson());
            json.add("terrainCostProfiles", terrainCostProfilesJson());
            json.add("warnings", stringArray(warnings));
            json.add("repairs", repairsJson());
            JsonObject statusSummary = statusSummaryJson();
            json.add("territoryStatusSummary", statusSummary);
            json.addProperty("ownedAreaRatio", statusSummary.get("ownedRatio").getAsDouble());
            json.addProperty("wildlandRatio", statusSummary.get("wildRatio").getAsDouble());
            json.addProperty("contestedRatio", statusSummary.get("contestedRatio").getAsDouble());
            json.addProperty("blockedRatio", statusSummary.get("blockedRatio").getAsDouble());
            json.addProperty("unreachableRatio", statusSummary.get("unreachableRatio").getAsDouble());
            return json;
        }

        JsonObject scalesJson() {
            JsonObject json = new JsonObject();
            for (Map.Entry<String, NormalizedScale> entry : scales.entrySet()) {
                json.add(entry.getKey(), entry.getValue().asJson());
            }
            return json;
        }

        JsonArray repairsJson() {
            JsonArray array = new JsonArray();
            for (TerritoryRepair repair : repairs) {
                array.add(repair.asJson());
            }
            return array;
        }

        JsonObject expansionBudgetsJson() {
            JsonObject json = new JsonObject();
            for (Map.Entry<String, ExpansionBudget> entry : expansionBudgets.entrySet()) {
                json.add(entry.getKey(), entry.getValue().asJson());
            }
            return json;
        }

        JsonObject terrainCostProfilesJson() {
            JsonObject json = new JsonObject();
            for (Map.Entry<String, TerrainCostProfile> entry : terrainCostProfiles.entrySet()) {
                json.add(entry.getKey(), entry.getValue().asJson());
            }
            return json;
        }

        JsonObject repairLogJson() {
            JsonObject json = new JsonObject();
            json.addProperty("territoryMapId", territoryMapId);
            json.addProperty("runId", runId);
            json.add("repairs", repairsJson());
            return json;
        }

        JsonObject statusSummaryJson() {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (TerritoryCell cell : cells) {
                counts.put(cell.status, counts.getOrDefault(cell.status, 0) + 1);
            }
            int total = Math.max(1, cells.size());
            JsonObject json = new JsonObject();
            for (String status : List.of("owned", "wild", "contested", "blocked", "unreachable")) {
                int count = counts.getOrDefault(status, 0);
                json.addProperty(status + "Cells", count);
                json.addProperty(status + "Ratio", count / (double) total);
            }
            return json;
        }

        private static ComponentMetrics componentMetrics(String realmId, List<WorldCell> owned,
                Map<String, String> owners) {
            Set<String> ownedKeys = new LinkedHashSet<>();
            for (WorldCell cell : owned) {
                ownedKeys.add(key(cell.gridX, cell.gridZ));
            }
            Set<String> visited = new HashSet<>();
            int componentCount = 0;
            int largest = 0;
            for (String start : ownedKeys) {
                if (visited.contains(start)) {
                    continue;
                }
                componentCount++;
                int size = 0;
                ArrayDeque<String> queue = new ArrayDeque<>();
                queue.add(start);
                visited.add(start);
                while (!queue.isEmpty()) {
                    String currentKey = queue.removeFirst();
                    size++;
                    String[] parts = currentKey.split(",", 2);
                    int x = Integer.parseInt(parts[0]);
                    int z = Integer.parseInt(parts[1]);
                    for (int[] offset : DIRECTIONS) {
                        String nextKey = key(x + offset[0], z + offset[1]);
                        if (!visited.contains(nextKey) && realmId.equals(owners.get(nextKey))) {
                            visited.add(nextKey);
                            queue.addLast(nextKey);
                        }
                    }
                }
                largest = Math.max(largest, size);
            }
            double largestRatio = owned.isEmpty() ? 0.0 : largest / (double) owned.size();
            return new ComponentMetrics(componentCount, largest, largestRatio, 1.0 - largestRatio);
        }

        private static double naturalBoundaryFit(String realmId, List<WorldCell> owned, Map<String, String> owners,
                Map<String, WorldCell> worldCellsByKey) {
            int boundaryEdges = 0;
            int naturalEdges = 0;
            for (WorldCell cell : owned) {
                for (int[] offset : DIRECTIONS) {
                    String otherOwner = owners.get(key(cell.gridX + offset[0], cell.gridZ + offset[1]));
                    if (otherOwner == null || realmId.equals(otherOwner)) {
                        continue;
                    }
                    boundaryEdges++;
                    WorldCell other = worldCellsByKey.get(key(cell.gridX + offset[0], cell.gridZ + offset[1]));
                    if (isNaturalBoundaryEdge(cell, other)) {
                        naturalEdges++;
                    }
                }
            }
            return boundaryEdges == 0 ? 1.0 : naturalEdges / (double) boundaryEdges;
        }

        private static boolean isNaturalBoundaryEdge(WorldCell left, WorldCell right) {
            if (right == null) {
                return true;
            }
            if ("shore".equals(left.landWater) || "shore".equals(right.landWater)) {
                return true;
            }
            if (left.barrierCost() >= 3.0 || right.barrierCost() >= 3.0) {
                return true;
            }
            return Math.abs(left.heightAvg - right.heightAvg) >= 24.0;
        }
    }

    private record CitySeed(String citySeedId, String realmId, String role, String theoreticalScale,
            GridPoint anchorGrid, GridPoint anchorBlock, int candidateRangeCells, int planningRadiusCells,
            String subregionId, String candidateId, double graphDistanceToNearestCity, String satelliteOf,
            List<String> requiredConditions, List<String> coreFunctions, String trigger, String source) {
        static CitySeed fixtureCapital(CapitalCityIntent intent, RealmSeed realmSeed) {
            return new CitySeed(intent.citySeedId, intent.realmId, "capital", intent.theoreticalScale,
                    realmSeed.seedGrid, realmSeed.seedBlock, 8,
                    RealmPlanningService.planningRadiusCells("capital", intent.theoreticalScale),
                    intent.realmId + "_capital_core", "capital_" + intent.realmId, -1.0, "",
                    intent.requiredConditions, intent.coreFunctions,
                    "always", "rule_fixture_realm_core");
        }

        static CitySeed from(String id, String realmId, String role, String scale, WorldCell cell,
                int range, List<String> conditions, List<String> functions, String trigger, String source) {
            return new CitySeed(id, realmId, role, scale, new GridPoint(cell.gridX, cell.gridZ),
                    new GridPoint(cell.blockX, cell.blockZ), range,
                    RealmPlanningService.planningRadiusCells(role, scale), "",
                    id, -1.0, "", conditions, functions, trigger, source);
        }

        CitySeed withCandidateMetadata(String subregionId, String candidateId, double graphDistanceToNearestCity,
                String satelliteOf) {
            return new CitySeed(citySeedId, realmId, role, theoreticalScale, anchorGrid, anchorBlock,
                    candidateRangeCells, planningRadiusCells, subregionId == null ? "" : subregionId,
                    candidateId == null || candidateId.isBlank() ? this.candidateId : candidateId,
                    graphDistanceToNearestCity, satelliteOf == null ? "" : satelliteOf,
                    requiredConditions, coreFunctions, trigger, source);
        }

        JsonObject asJson() {
            JsonObject json = new JsonObject();
            json.addProperty("citySeedId", citySeedId);
            json.addProperty("realmId", realmId);
            json.addProperty("role", role);
            json.addProperty("theoreticalScale", theoreticalScale);
            json.add("anchorGrid", anchorGrid.asJson());
            json.add("anchorBlock", anchorBlock.asJson());
            json.addProperty("candidateRangeCells", candidateRangeCells);
            json.addProperty("planningRadiusCells", planningRadiusCells);
            json.addProperty("subregionId", subregionId);
            json.addProperty("candidateId", candidateId);
            json.addProperty("graphDistanceToNearestCity", graphDistanceToNearestCity);
            if (!satelliteOf.isBlank()) {
                json.addProperty("satelliteOf", satelliteOf);
            }
            json.add("requiredConditions", stringArray(requiredConditions));
            json.add("coreFunctions", stringArray(coreFunctions));
            json.addProperty("trigger", trigger);
            JsonObject sourceJson = new JsonObject();
            sourceJson.addProperty("reason", source);
            if (source.startsWith("rule_fixture")) {
                sourceJson.addProperty("selectionMode", "rule_fixture");
                sourceJson.addProperty("formalAiPath", false);
            }
            json.add("source", sourceJson);
            return json;
        }
    }

    private record CitySeedRegistry(String registryId, String runId, String territoryMapId, List<CitySeed> citySeeds) {
        JsonObject asJson() {
            JsonObject json = new JsonObject();
            json.addProperty("registryId", registryId);
            json.addProperty("surveyId", "survey_" + runId);
            json.addProperty("territoryMapId", territoryMapId);
            JsonArray array = new JsonArray();
            for (CitySeed seed : citySeeds) {
                array.add(seed.asJson());
            }
            json.add("citySeeds", array);
            return json;
        }

        JsonObject reportJson() {
            JsonObject json = new JsonObject();
            json.addProperty("registryId", registryId);
            json.addProperty("citySeedCount", citySeeds.size());
            long capitals = citySeeds.stream().filter(seed -> "capital".equals(seed.role)).count();
            json.addProperty("capitalCount", capitals);
            long uniqueIds = citySeeds.stream().map(seed -> seed.citySeedId).distinct().count();
            json.addProperty("uniqueCitySeedIds", uniqueIds);
            json.addProperty("allCitySeedIdsUnique", uniqueIds == citySeeds.size());
            json.addProperty("duplicateAnchorCount", duplicateAnchorCount());
            json.addProperty("spacingViolationCount", spacingViolationCount());
            return json;
        }

        int duplicateAnchorCount() {
            Map<String, Integer> anchors = new HashMap<>();
            int duplicates = 0;
            for (CitySeed seed : citySeeds) {
                if (!seed.satelliteOf.isBlank()) {
                    continue;
                }
                String key = seed.realmId + ":" + seed.anchorGrid.x + "," + seed.anchorGrid.z;
                int count = anchors.getOrDefault(key, 0) + 1;
                anchors.put(key, count);
                if (count == 2) {
                    duplicates++;
                }
            }
            return duplicates;
        }

        int spacingViolationCount() {
            int violations = 0;
            for (int i = 0; i < citySeeds.size(); i++) {
                CitySeed left = citySeeds.get(i);
                if (!left.satelliteOf.isBlank()) {
                    continue;
                }
                for (int j = i + 1; j < citySeeds.size(); j++) {
                    CitySeed right = citySeeds.get(j);
                    if (!left.realmId.equals(right.realmId) || !right.satelliteOf.isBlank()) {
                        continue;
                    }
                    double distance = distanceCells(left.anchorGrid.x, left.anchorGrid.z,
                            right.anchorGrid.x, right.anchorGrid.z);
                    if (distance < left.planningRadiusCells + right.planningRadiusCells) {
                        violations++;
                    }
                }
            }
            return violations;
        }
    }

    private record GridBounds(int minX, int minZ, int maxX, int maxZ) {
        static GridBounds from(List<WorldCell> cells) {
            int minX = cells.stream().mapToInt(cell -> cell.gridX).min().orElse(0);
            int minZ = cells.stream().mapToInt(cell -> cell.gridZ).min().orElse(0);
            int maxX = cells.stream().mapToInt(cell -> cell.gridX).max().orElse(minX);
            int maxZ = cells.stream().mapToInt(cell -> cell.gridZ).max().orElse(minZ);
            return new GridBounds(minX, minZ, maxX, maxZ);
        }

        static GridBounds fromTerritory(List<TerritoryCell> cells, String realmId, int paddingCells) {
            int minX = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (TerritoryCell cell : cells) {
                if (realmId.equals(cell.realmId) && "owned".equals(cell.status)) {
                    minX = Math.min(minX, cell.gridX);
                    minZ = Math.min(minZ, cell.gridZ);
                    maxX = Math.max(maxX, cell.gridX);
                    maxZ = Math.max(maxZ, cell.gridZ);
                }
            }
            if (minX == Integer.MAX_VALUE) {
                return new GridBounds(0, 0, 0, 0);
            }
            int padding = Math.max(0, paddingCells);
            return new GridBounds(minX - padding, minZ - padding, maxX + padding, maxZ + padding);
        }

        int width() {
            return maxX - minX + 1;
        }

        int height() {
            return maxZ - minZ + 1;
        }

        boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        JsonObject asJson() {
            JsonObject json = new JsonObject();
            json.addProperty("minGridX", minX);
            json.addProperty("minGridZ", minZ);
            json.addProperty("maxGridX", maxX);
            json.addProperty("maxGridZ", maxZ);
            json.addProperty("widthCells", width());
            json.addProperty("heightCells", height());
            return json;
        }
    }
}
