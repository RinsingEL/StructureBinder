package com.rinsing.geomantia.systems.realm_planning;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import java.util.Set;
import java.util.UUID;

public final class RealmPlanningService {
    public static final String SCHEMA_VERSION = "realm_planning.v1.1";
    private static final int DEFAULT_SNAP_RADIUS_CELLS = 2;
    private static final int MIN_SEED_DISTANCE_CELLS = 2;
    private static final Map<String, RealmRun> RUNS = new LinkedHashMap<>();

    private final Path debugRoot;

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
        Files.createDirectories(runDirectory);

        RealmRun run = new RealmRun(runId, runDirectory, refreshResult);
        run.worldTheme = worldTheme == null || worldTheme.isJsonNull() ? null : worldTheme.deepCopy();
        buildWorld(run);
        exportWorld(run);
        RUNS.put(runId, run);

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
        response.add("artifacts", run.artifactsJson());
        response.add("realmProfiles", profilesJson(run.profiles));
        response.add("candidatePackages", candidatePackagesJson(run.candidatePackages.values()));
        response.add("nextActions", arrayOf("realm_t2_select_coordinate"));
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
        CapitalCitySeed capital = CapitalCitySeed.from(profile, selection, attempt.cell);
        run.seeds.put(profile.realmId, seed);
        run.capitals.put(profile.realmId, capital);
        exportT2(run);

        JsonObject response = baseResponse("T2", run.runId);
        response.addProperty("status", "completed");
        response.add("artifacts", run.artifactsJson());
        response.add("selection", selection.asJson());
        response.add("realmSeed", seed.asJson());
        response.add("capitalCitySeed", capital.asJson());
        response.add("nextActions", arrayOf(run.seeds.size() == run.profiles.size()
                ? "realm_t3_expand" : "realm_t2_select_coordinate"));
        return response;
    }

    public JsonObject expandT3(String runId, String normalizationGroup, boolean allowUnclaimedLand) throws IOException {
        RealmRun run = requireRun(runId);
        if (run.profiles.isEmpty() || run.seeds.size() != run.profiles.size()) {
            throw new IllegalArgumentException("All realms must complete T2 before T3.");
        }
        String group = normalizationGroup == null || normalizationGroup.isBlank()
                ? run.profiles.get(0).scalePlan.normalizationGroup : normalizationGroup.trim();
        run.territory = buildTerritory(run, group, allowUnclaimedLand);
        exportTerritory(run);

        JsonObject response = baseResponse("T3", run.runId);
        response.addProperty("status", "completed");
        response.add("artifacts", run.artifactsJson());
        response.add("territoryMap", run.territory.asJson());
        response.add("nextActions", arrayOf("realm_t4_build_registry"));
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
        response.add("artifacts", run.artifactsJson());
        response.add("citySeedRegistry", run.registry.asJson());
        response.add("nextActions", arrayOf("review_acceptance_report"));
        return response;
    }

    public JsonObject runAcceptance(RefreshResult refreshResult, String requestedRunId, int realmCount,
            JsonArray realmProfiles, boolean autoSelectCoordinates) throws IOException {
        long startedAt = System.nanoTime();
        JsonObject w = runW(refreshResult, requestedRunId, null);
        String runId = w.get("runId").getAsString();
        RealmRun run = requireRun(runId);
        String continent = resolveTargetContinent(run, "");
        prepareT1(runId, realmProfiles, realmCount <= 0 ? 3 : realmCount, continent, true);
        if (autoSelectCoordinates) {
            for (RealmProfile profile : run.profiles) {
                CandidatePackage pack = run.candidatePackages.get(profile.realmId);
                GridPoint point = pack.suggestedPoint;
                selectT2(runId, profile.realmId, point.x, point.z, null,
                        "auto acceptance coordinate from candidate package", "debug", true);
            }
        }
        expandT3(runId, continent, false);
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

    public GridPoint suggestedPoint(String runId, String realmId) {
        RealmRun run = requireRun(runId);
        CandidatePackage pack = run.candidatePackages.get(realmId);
        if (pack == null) {
            throw new IllegalArgumentException("No candidate package for realmId: " + realmId);
        }
        return pack.suggestedPoint;
    }

    private void buildWorld(RealmRun run) {
        AtlasRegion region = run.refreshResult.region();
        Map<String, WorldCell> cells = new LinkedHashMap<>();
        for (AtlasCell cell : region.cells()) {
            if (!cell.hasFlag(CellStateFlag.LANDFORM_READY)) {
                continue;
            }
            String landWater = landWater(cell);
            WorldCell worldCell = new WorldCell(cell.globalCellX(), cell.globalCellZ(), cell.blockMinX(),
                    cell.blockMinZ(), "", cell.patchId(), landWater, cell.landformType().contractName(),
                    cell.elevation(), cell.slope(), finiteWaterDistance(cell.waterDistance()), flagsFor(cell));
            cells.put(key(worldCell.gridX, worldCell.gridZ), worldCell);
        }
        assignContinents(cells);
        run.worldCells.clear();
        run.worldCells.addAll(cells.values());
        run.worldCellsByKey.clear();
        for (WorldCell cell : run.worldCells) {
            run.worldCellsByKey.put(key(cell.gridX, cell.gridZ), cell);
        }
        run.patchSummaries = buildPatchSummaries(run, run.refreshResult.patches());
        run.continentSummaries = buildContinentSummaries(run);
        if (run.continentSummaries.isEmpty()) {
            throw new IllegalArgumentException("W refresh did not produce assignable land continents.");
        }
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
            PatchSummary summary = accumulator.toSummary(run.refreshResult.region().cellStepBlocks());
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
                .min(Comparator.comparingDouble(cell -> candidateScore(profile, cell)))
                .orElse(candidates.get(0));
        String packageId = "candidate_" + profile.realmId;
        AtlasRegion region = run.refreshResult.region();
        return new CandidatePackage(packageId, profile.realmId, "survey_" + run.runId, allowed, List.of(), List.of(),
                new GridPoint(region.blockMinX(), region.blockMinZ()), region.cellStepBlocks(),
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
        List<Claim> claims = new ArrayList<>();
        for (WorldCell cell : landCells) {
            for (RealmProfile profile : profiles) {
                RealmSeed seed = run.seeds.get(profile.realmId);
                claims.add(new Claim(cell, profile.realmId, expansionCost(profile, seed, cell)));
            }
        }
        claims.sort(Comparator.comparingDouble(claim -> claim.cost));
        Map<String, String> ownership = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (RealmProfile profile : profiles) {
            counts.put(profile.realmId, 0);
        }
        for (Claim claim : claims) {
            String cellKey = key(claim.cell.gridX, claim.cell.gridZ);
            if (ownership.containsKey(cellKey)) {
                continue;
            }
            int current = counts.getOrDefault(claim.realmId, 0);
            if (current < quotas.getOrDefault(claim.realmId, 0)) {
                ownership.put(cellKey, claim.realmId);
                counts.put(claim.realmId, current + 1);
            }
        }
        if (!allowUnclaimedLand) {
            for (WorldCell cell : landCells) {
                String cellKey = key(cell.gridX, cell.gridZ);
                if (ownership.containsKey(cellKey)) {
                    continue;
                }
                Claim best = claims.stream()
                        .filter(claim -> claim.cell == cell)
                        .min(Comparator.comparingDouble(claim -> claim.cost))
                        .orElseThrow();
                ownership.put(cellKey, best.realmId);
                counts.put(best.realmId, counts.getOrDefault(best.realmId, 0) + 1);
            }
        }
        return RealmTerritoryMap.from(run.runId, group, landCells, ownership, run, scales);
    }

    private CitySeedRegistry buildRegistry(RealmRun run) {
        List<CitySeed> seeds = new ArrayList<>();
        for (RealmProfile profile : run.profiles) {
            CapitalCitySeed capital = run.capitals.get(profile.realmId);
            if (capital != null) {
                seeds.add(CitySeed.capital(capital));
            }
            RealmStats stats = run.territory.stats.get(profile.realmId);
            if (stats == null) {
                continue;
            }
            WorldCell portCell = firstOwnedCell(run, profile.realmId, cell -> "shore".equals(cell.landWater));
            if (portCell != null && stats.coastalRatio > 0.05) {
                seeds.add(CitySeed.from("city_" + profile.realmId + "_port", profile.realmId, "port",
                        "town", portCell, 6, List.of("land", "near_water", "inside_realm"),
                        List.of("harbor", "market", "storage"), "player_nearby", "coastal territory"));
            }
            WorldCell miningCell = firstOwnedCell(run, profile.realmId,
                    cell -> cell.landform.equals("ridge") || cell.landform.equals("slope") || cell.landform.equals("cliff"));
            if (miningCell != null) {
                seeds.add(CitySeed.from("city_" + profile.realmId + "_mining", profile.realmId, "mining_town",
                        "town", miningCell, 5, List.of("land", "inside_realm", "near_mountain"),
                        List.of("industry", "storage", "worker_housing"), "realm_development", "mountain landform"));
            }
            WorldCell borderCell = firstBorderCell(run, profile.realmId);
            if (borderCell != null && !stats.neighbors.isEmpty()) {
                seeds.add(CitySeed.from("city_" + profile.realmId + "_border_fort", profile.realmId, "border_fort",
                        "town", borderCell, 4, List.of("land", "inside_realm", "near_border"),
                        List.of("defense", "barracks", "market"), "story_stage", "realm border"));
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

    private void exportWorld(RealmRun run) throws IOException {
        writeJson(run.runDirectory.resolve("world_survey_context.json"), surveyJson(run));
        writeJson(run.runDirectory.resolve("world_patch_map.json"), worldPatchMapJson(run));
        exportWorldPreview(run, run.runDirectory.resolve("world_patch_preview.png"), false, null);
        exportWorldPreview(run, run.runDirectory.resolve("grid_overlay_preview.png"), true, null);
        JsonObject manifest = new JsonObject();
        manifest.addProperty("runId", run.runId);
        manifest.addProperty("cellStepBlocks", run.refreshResult.job().cellStepBlocks());
        manifest.addProperty("gridOriginBlockX", run.refreshResult.region().blockMinX());
        manifest.addProperty("gridOriginBlockZ", run.refreshResult.region().blockMinZ());
        manifest.addProperty("gridSizeWidth", run.refreshResult.region().cellsPerSide());
        manifest.addProperty("gridSizeHeight", run.refreshResult.region().cellsPerSide());
        manifest.add("continents", continentsJson(run.continentSummaries.values()));
        writeJson(run.runDirectory.resolve("w_manifest.json"), manifest);
        run.artifacts.put("worldSurveyContext", "world_survey_context.json");
        run.artifacts.put("worldPatchMap", "world_patch_map.json");
        run.artifacts.put("worldPatchPreview", "world_patch_preview.png");
        run.artifacts.put("gridOverlayPreview", "grid_overlay_preview.png");
        run.artifacts.put("wManifest", "w_manifest.json");
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
        writeJson(run.runDirectory.resolve("capital_city_seeds.json"), capitalsJson(run.capitals.values()));
        JsonObject report = new JsonObject();
        report.addProperty("runId", run.runId);
        report.addProperty("completedSelections", run.seeds.size());
        report.addProperty("totalRealms", run.profiles.size());
        writeJson(run.runDirectory.resolve("t2_report.json"), report);
        run.artifacts.put("realmCoordinateSelections", "realm_coordinate_selections.json");
        run.artifacts.put("realmSeeds", "realm_seeds.json");
        run.artifacts.put("capitalCitySeeds", "capital_city_seeds.json");
        run.artifacts.put("t2Report", "t2_report.json");
    }

    private void exportTerritory(RealmRun run) throws IOException {
        writeJson(run.runDirectory.resolve("realm_territory_map.json"), run.territory.asJson());
        writeJson(run.runDirectory.resolve("t3_report.json"), run.territory.reportJson());
        exportTerritoryPreview(run, run.runDirectory.resolve("territory_preview.png"));
        run.artifacts.put("realmTerritoryMap", "realm_territory_map.json");
        run.artifacts.put("territoryPreview", "territory_preview.png");
        run.artifacts.put("t3Report", "t3_report.json");
    }

    private void exportRegistry(RealmRun run) throws IOException {
        writeJson(run.runDirectory.resolve("city_seed_registry.json"), run.registry.asJson());
        writeJson(run.runDirectory.resolve("t4_report.json"), run.registry.reportJson());
        exportCitySeedPreview(run, run.runDirectory.resolve("city_seed_preview.png"));
        run.artifacts.put("citySeedRegistry", "city_seed_registry.json");
        run.artifacts.put("citySeedPreview", "city_seed_preview.png");
        run.artifacts.put("t4Report", "t4_report.json");
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
                Color color = colorForLandform(cell.landform, cell.landWater);
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
                g.setColor(colors.getOrDefault(territoryCell.realmId, Color.GRAY));
                g.fillRect(x, z, scale, scale);
            }
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
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
        report.addProperty("caseId", "realm_v1_1_smoke");
        report.addProperty("runId", run.runId);
        report.addProperty("durationMs", durationMs);
        boolean passed = run.registry != null && run.territory != null && run.registry.citySeeds.stream()
                .anyMatch(seed -> "capital".equals(seed.role));
        report.addProperty("passed", passed);
        report.add("stageResults", stageResults(run));
        report.add("artifacts", run.artifactsJson());
        report.add("coordinateChecks", coordinateChecks(run));
        report.add("ratioChecks", ratioChecks(run));
        JsonArray visualChecks = new JsonArray();
        for (String key : List.of("worldPatchPreview", "gridOverlayPreview", "territoryPreview", "citySeedPreview")) {
            if (run.artifacts.containsKey(key)) {
                visualChecks.add(run.artifacts.get(key));
            }
        }
        report.add("visualChecks", visualChecks);
        JsonArray failures = new JsonArray();
        if (!passed) {
            failures.add("W/T acceptance did not reach CitySeedRegistry with a capital city.");
        }
        report.add("failures", failures);
        return report;
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
        AtlasRegion region = run.refreshResult.region();
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", SCHEMA_VERSION);
        json.addProperty("surveyId", "survey_" + run.runId);
        json.addProperty("dimensionId", region.dimensionId());
        json.addProperty("worldSeed", "unknown");
        json.addProperty("cellStepBlocks", region.cellStepBlocks());
        JsonObject origin = new JsonObject();
        origin.addProperty("x", region.blockMinX());
        origin.addProperty("z", region.blockMinZ());
        json.add("gridOriginBlock", origin);
        JsonObject size = new JsonObject();
        size.addProperty("width", region.cellsPerSide());
        size.addProperty("height", region.cellsPerSide());
        json.add("gridSize", size);
        JsonObject source = new JsonObject();
        source.addProperty("gisRefreshJobId", run.refreshResult.job().jobId());
        source.addProperty("sampleMode", run.refreshResult.job().sampleMode().contractName());
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
        return json;
    }

    private JsonObject worldSummary(RealmRun run) {
        JsonObject summary = new JsonObject();
        summary.addProperty("cellCount", run.worldCells.size());
        summary.addProperty("patchCount", run.patchSummaries.size());
        summary.addProperty("continentCount", run.continentSummaries.size());
        summary.add("continents", continentsJson(run.continentSummaries.values()));
        return summary;
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

    private static JsonArray capitalsJson(Iterable<CapitalCitySeed> capitals) {
        JsonArray array = new JsonArray();
        for (CapitalCitySeed capital : capitals) {
            array.add(capital.asJson());
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

    private double candidateScore(RealmProfile profile, WorldCell cell) {
        double score = 0.0;
        if (profile.landformPreferences.contains(cell.landform)) {
            score -= 10.0;
        }
        if (profile.avoidLandforms.contains(cell.landform)) {
            score += 10.0;
        }
        score += cell.waterDistanceBlocks / 128.0 * (1.0 - profile.expansionStyle.waterAffinity);
        if ("shore".equals(cell.landWater)) {
            score -= profile.expansionStyle.coastalBias * 5.0;
        }
        score += Math.abs(cell.slopeAvg) * (profile.expansionStyle.mountainAffinity < 0 ? 3.0 : -1.0);
        return score;
    }

    private static double resourceHint(WorldCell cell) {
        return switch (cell.landform) {
            case "ridge", "slope", "cliff" -> 1.0;
            case "plain", "terrace", "shore" -> 0.5;
            default -> 0.0;
        };
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

    private RealmRun requireRun(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId is required.");
        }
        RealmRun run = RUNS.get(runId.trim());
        if (run == null) {
            throw new IllegalArgumentException("Unknown realm runId: " + runId);
        }
        return run;
    }

    private String latestRunId() {
        String latest = "";
        for (String runId : RUNS.keySet()) {
            latest = runId;
        }
        return latest;
    }

    private JsonArray knownRuns() {
        JsonArray array = new JsonArray();
        for (RealmRun run : RUNS.values()) {
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

    private static Color colorForLandform(String landform, String landWater) {
        if ("water".equals(landWater)) {
            return new Color(42, 96, 164);
        }
        if ("shore".equals(landWater)) {
            return new Color(214, 197, 128);
        }
        return switch (landform) {
            case "plain" -> new Color(104, 168, 86);
            case "terrace" -> new Color(142, 178, 96);
            case "slope" -> new Color(148, 132, 86);
            case "cliff" -> new Color(120, 112, 108);
            case "ridge" -> new Color(155, 155, 150);
            case "valley" -> new Color(82, 148, 92);
            case "basin" -> new Color(98, 132, 106);
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

    private interface CellPredicate {
        boolean test(WorldCell cell);
    }

    private static final class RealmRun {
        final String runId;
        final Path runDirectory;
        final RefreshResult refreshResult;
        final List<WorldCell> worldCells = new ArrayList<>();
        final Map<String, WorldCell> worldCellsByKey = new LinkedHashMap<>();
        final List<RealmProfile> profiles = new ArrayList<>();
        final Map<String, CandidatePackage> candidatePackages = new LinkedHashMap<>();
        final Map<String, RealmSelection> selections = new LinkedHashMap<>();
        final Map<String, RealmSeed> seeds = new LinkedHashMap<>();
        final Map<String, CapitalCitySeed> capitals = new LinkedHashMap<>();
        final Map<String, String> artifacts = new LinkedHashMap<>();
        Map<String, PatchSummary> patchSummaries = new LinkedHashMap<>();
        Map<String, ContinentSummary> continentSummaries = new LinkedHashMap<>();
        JsonElement worldTheme;
        RealmTerritoryMap territory;
        CitySeedRegistry registry;

        RealmRun(String runId, Path runDirectory, RefreshResult refreshResult) {
            this.runId = runId;
            this.runDirectory = runDirectory;
            this.refreshResult = refreshResult;
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

        WorldCell(int gridX, int gridZ, int blockX, int blockZ, String continentId, String patchId,
                String landWater, String landform, double heightAvg, double slopeAvg, double waterDistanceBlocks,
                List<String> flags) {
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
            json.addProperty("heightAvg", heightAvg);
            json.addProperty("slopeAvg", slopeAvg);
            json.addProperty("waterDistanceBlocks", waterDistanceBlocks);
            json.add("flags", stringArray(flags));
            return json;
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

    private record CapitalCitySeed(String citySeedId, String realmId, String cityRole, GridPoint anchorGrid,
            GridPoint anchorBlock, String theoreticalScale, String growthAnchor, boolean mustExist) {
        static CapitalCitySeed from(RealmProfile profile, RealmSelection selection, WorldCell cell) {
            String scale = switch (profile.scalePlan.priority) {
                case "empire", "major" -> "capital";
                case "minor" -> "town";
                default -> "large_city";
            };
            return new CapitalCitySeed("city_" + profile.realmId + "_capital", profile.realmId, "capital",
                    new GridPoint(cell.gridX, cell.gridZ), new GridPoint(cell.blockX, cell.blockZ),
                    scale, cell.landform, true);
        }

        JsonObject asJson() {
            JsonObject json = new JsonObject();
            json.addProperty("citySeedId", citySeedId);
            json.addProperty("realmId", realmId);
            json.addProperty("cityRole", cityRole);
            json.add("anchorGrid", anchorGrid.asJson());
            json.add("anchorBlock", anchorBlock.asJson());
            json.addProperty("theoreticalScale", theoreticalScale);
            json.addProperty("growthAnchor", growthAnchor);
            json.addProperty("mustExist", mustExist);
            return json;
        }
    }

    private record Claim(WorldCell cell, String realmId, double cost) {
    }

    private record TerritoryCell(int gridX, int gridZ, String realmId, double claimStrength) {
        JsonObject asJson() {
            JsonObject json = new JsonObject();
            json.addProperty("gridX", gridX);
            json.addProperty("gridZ", gridZ);
            json.addProperty("realmId", realmId);
            json.addProperty("claimStrength", claimStrength);
            return json;
        }
    }

    private record RealmStats(String realmId, int areaCells, double coastalRatio, List<String> primaryLandforms,
            Set<String> neighbors) {
        JsonObject asJson() {
            JsonObject json = new JsonObject();
            json.addProperty("realmId", realmId);
            json.addProperty("areaCells", areaCells);
            json.addProperty("coastalRatio", coastalRatio);
            json.add("primaryLandforms", stringArray(primaryLandforms));
            json.add("neighbors", stringArray(neighbors));
            return json;
        }
    }

    private static final class RealmTerritoryMap {
        final String territoryMapId;
        final String runId;
        final String normalizationGroup;
        final List<TerritoryCell> cells;
        final Map<String, RealmStats> stats;
        final Map<String, NormalizedScale> scales;
        final List<String> warnings;

        RealmTerritoryMap(String territoryMapId, String runId, String normalizationGroup, List<TerritoryCell> cells,
                Map<String, RealmStats> stats, Map<String, NormalizedScale> scales, List<String> warnings) {
            this.territoryMapId = territoryMapId;
            this.runId = runId;
            this.normalizationGroup = normalizationGroup;
            this.cells = List.copyOf(cells);
            this.stats = Map.copyOf(stats);
            this.scales = Map.copyOf(scales);
            this.warnings = List.copyOf(warnings);
        }

        static RealmTerritoryMap from(String runId, String group, List<WorldCell> landCells, Map<String, String> ownership,
                RealmRun run, Map<String, NormalizedScale> scales) {
            List<TerritoryCell> territoryCells = new ArrayList<>();
            for (WorldCell cell : landCells) {
                String owner = ownership.get(key(cell.gridX, cell.gridZ));
                if (owner != null) {
                    territoryCells.add(new TerritoryCell(cell.gridX, cell.gridZ, owner, 1.0));
                }
            }
            Map<String, Set<String>> neighbors = new LinkedHashMap<>();
            Map<String, List<WorldCell>> byRealm = new LinkedHashMap<>();
            Map<String, String> owners = new LinkedHashMap<>();
            for (TerritoryCell cell : territoryCells) {
                owners.put(key(cell.gridX, cell.gridZ), cell.realmId);
                WorldCell worldCell = run.worldCellsByKey.get(key(cell.gridX, cell.gridZ));
                if (worldCell != null) {
                    byRealm.computeIfAbsent(cell.realmId, ignored -> new ArrayList<>()).add(worldCell);
                }
            }
            for (TerritoryCell cell : territoryCells) {
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
                stats.put(entry.getKey(), new RealmStats(entry.getKey(), owned.size(), coastal, primary,
                        neighbors.getOrDefault(entry.getKey(), Set.of())));
            }
            List<String> warnings = new ArrayList<>();
            for (RealmProfile profile : run.profiles) {
                if (!stats.containsKey(profile.realmId)) {
                    warnings.add("Realm has no territory cells: " + profile.realmId);
                }
            }
            return new RealmTerritoryMap("territory_" + runId, runId, group, territoryCells, stats, scales, warnings);
        }

        Map<String, String> ownershipByKey() {
            Map<String, String> owners = new LinkedHashMap<>();
            for (TerritoryCell cell : cells) {
                owners.put(key(cell.gridX, cell.gridZ), cell.realmId);
            }
            return owners;
        }

        JsonObject asJson() {
            JsonObject json = new JsonObject();
            json.addProperty("territoryMapId", territoryMapId);
            json.addProperty("surveyId", "survey_" + runId);
            json.addProperty("normalizationGroup", normalizationGroup);
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
            return json;
        }

        JsonObject reportJson() {
            JsonObject json = new JsonObject();
            json.addProperty("territoryMapId", territoryMapId);
            json.addProperty("normalizationGroup", normalizationGroup);
            json.add("realmStats", asJson().get("realmStats"));
            json.add("normalizedScales", scalesJson());
            json.add("warnings", stringArray(warnings));
            return json;
        }

        JsonObject scalesJson() {
            JsonObject json = new JsonObject();
            for (Map.Entry<String, NormalizedScale> entry : scales.entrySet()) {
                json.add(entry.getKey(), entry.getValue().asJson());
            }
            return json;
        }
    }

    private record CitySeed(String citySeedId, String realmId, String role, String theoreticalScale,
            GridPoint anchorGrid, GridPoint anchorBlock, int candidateRangeCells, List<String> requiredConditions,
            List<String> coreFunctions, String trigger, String source) {
        static CitySeed capital(CapitalCitySeed capital) {
            return new CitySeed(capital.citySeedId, capital.realmId, "capital", capital.theoreticalScale,
                    capital.anchorGrid, capital.anchorBlock, 8, List.of("land", "inside_realm"),
                    List.of("administration", "market", "defense"), "always", "capital_city_seed");
        }

        static CitySeed from(String id, String realmId, String role, String scale, WorldCell cell,
                int range, List<String> conditions, List<String> functions, String trigger, String source) {
            return new CitySeed(id, realmId, role, scale, new GridPoint(cell.gridX, cell.gridZ),
                    new GridPoint(cell.blockX, cell.blockZ), range, conditions, functions, trigger, source);
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
            json.add("requiredConditions", stringArray(requiredConditions));
            json.add("coreFunctions", stringArray(coreFunctions));
            json.addProperty("trigger", trigger);
            JsonObject sourceJson = new JsonObject();
            sourceJson.addProperty("reason", source);
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
            return json;
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

        int width() {
            return maxX - minX + 1;
        }

        int height() {
            return maxZ - minZ + 1;
        }
    }
}
