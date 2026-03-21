package com.user.terra_script.domain.territory.stage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.user.terra_script.client.data.ScanResultHolder;
import com.user.terra_script.core.stage.StageContext;
import com.user.terra_script.server.mcp.WorldController;
import com.user.terra_script.territory.io.TerritoryRepository;
import com.user.terra_script.territory.io.TerritoryResultRepository;
import com.user.terra_script.territory.model.TerritoryBlueprint;
import com.user.terra_script.world.TerritoryManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class TerritoryStageOrchestrator {
    private TerritoryStageOrchestrator() {}

    public static void runT1(StageContext ctx) throws Exception {
        List<PlanEntry> entries = requirePlanEntries(ctx);
        if (ScanResultHolder.get().regionCacheMap.isEmpty()) {
            throw new IllegalStateException("T1 requires region detail cache from W4 before candidate generation");
        }

        JsonObject stageStart = new JsonObject();
        stageStart.addProperty("territory_count", entries.size());
        TStageTraceLogger.stage(ctx, "T1", "stage_started", stageStart);

        Map<Integer, List<TerritoryStageArtifacts.ContinentIndexItem>> byContinent = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<Integer, List<PlanEntry>> continentEntry : planByContinent(entries).entrySet()) {
            JsonObject continentStart = new JsonObject();
            continentStart.addProperty("continent_id", continentEntry.getKey());
            continentStart.addProperty("territory_count", continentEntry.getValue().size());
            TStageTraceLogger.continent(ctx, "T1", continentEntry.getKey(), "continent_started", continentStart);
            for (PlanEntry entry : continentEntry.getValue()) {
                TerritoryStageArtifacts.T1Status status = prepareT1(ctx, entry);
                byContinent.computeIfAbsent(entry.continentId, ignored -> new ArrayList<>())
                        .add(indexItem(status.clusterSelected, status.blocked, entry, status.message, status.workflowState));
            }
        }
        writeIndexes(ctx, "T1", byContinent, now);

        JsonObject stageDone = new JsonObject();
        stageDone.addProperty("continent_count", byContinent.size());
        stageDone.addProperty("territory_count", entries.size());
        TStageTraceLogger.stage(ctx, "T1", "stage_completed", stageDone);
    }

    public static void runT2(StageContext ctx) throws Exception {
        List<PlanEntry> entries = requirePlanEntries(ctx);
        JsonObject stageStart = new JsonObject();
        stageStart.addProperty("territory_count", entries.size());
        TStageTraceLogger.stage(ctx, "T2", "stage_started", stageStart);

        Map<Integer, List<TerritoryStageArtifacts.ContinentIndexItem>> byContinent = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<Integer, List<PlanEntry>> continentEntry : planByContinent(entries).entrySet()) {
            for (PlanEntry entry : continentEntry.getValue()) {
                TerritoryStageArtifacts.T2Status status = prepareT2(ctx, entry);
                byContinent.computeIfAbsent(entry.continentId, ignored -> new ArrayList<>())
                        .add(indexItem(status.pointSelected, status.blocked, entry, status.message, status.workflowState));
            }
        }
        writeIndexes(ctx, "T2", byContinent, now);

        JsonObject stageDone = new JsonObject();
        stageDone.addProperty("continent_count", byContinent.size());
        stageDone.addProperty("territory_count", entries.size());
        TStageTraceLogger.stage(ctx, "T2", "stage_completed", stageDone);
    }

    public static JsonObject getT1CandidatesForContinent(StageContext ctx, int continentId) throws Exception {
        List<PlanEntry> entries = requireContinentEntries(ctx, continentId);
        JsonObject result = new JsonObject();
        result.addProperty("continent_id", continentId);
        JsonArray territories = new JsonArray();
        for (PlanEntry entry : entries) {
            TerritoryStageArtifacts.T1Status status = prepareT1(ctx, entry);
            JsonObject territory = new JsonObject();
            territory.addProperty("territory_id", entry.territoryId);
            territory.addProperty("territory_instance_id", entry.instanceId);
            territory.addProperty("territory_name", entry.name);
            territory.addProperty("continent_id", entry.continentId);
            territory.addProperty("workflow_state", status.workflowState);
            territory.addProperty("blocked", status.blocked);
            territory.addProperty("cluster_selected", status.clusterSelected);
            if (status.blockedReason != null) territory.addProperty("blocked_reason", status.blockedReason);
            if (!status.recommendedAdjustments.isEmpty()) territory.add("recommended_adjustments", toJsonArray(status.recommendedAdjustments));
            if (status.selectedClusterId != null) territory.addProperty("selected_cluster_id", status.selectedClusterId);
            if (status.selectedClusterLabel != null) territory.addProperty("selected_cluster_label", status.selectedClusterLabel);
            TerritoryStageArtifacts.readT1Candidates(ctx.server, ctx.worldId, entry.territoryId, entry.continentId)
                    .ifPresent(bundle -> territory.add("candidates_bundle", bundle));
            territories.add(territory);
        }
        result.add("territories", territories);
        return result;
    }

    public static TerritoryStageArtifacts.T1Status selectT1Cluster(
            StageContext ctx,
            String territoryId,
            int continentId,
            Integer clusterId,
            String label
    ) throws Exception {
        PlanEntry entry = requirePlanEntry(ctx, territoryId, continentId);
        JsonObject bundle = TerritoryStageArtifacts.readT1Candidates(ctx.server, ctx.worldId, territoryId, continentId)
                .orElseThrow(() -> new IllegalStateException("T1 candidates not generated for " + territoryId + " continent " + continentId));
        JsonObject selected = findCandidate(bundle, clusterId, label)
                .orElseThrow(() -> new IllegalArgumentException("Requested cluster not found in filtered T1 candidates"));

        TerritoryManager.removeTerritory(entry.instanceId);
        TerritoryStageArtifacts.deleteT2Artifacts(ctx.server, ctx.worldId, territoryId, continentId);

        TerritoryStageArtifacts.T1Status status = baseT1Status(entry);
        status.previewGenerated = bundle.has("preview_overlay");
        status.candidatesGenerated = bundle.has("candidates") && bundle.get("candidates").isJsonArray();
        status.clusterSelected = true;
        status.blocked = false;
        status.selectedClusterId = selected.has("cluster_id") ? selected.get("cluster_id").getAsInt() : null;
        status.selectedClusterLabel = selected.has("label") ? selected.get("label").getAsString() : null;
        status.selectionSource = "ai_selected";
        status.previewImage = extractPreviewPath(bundle);
        status.candidatesFile = t1CandidatesRelativePath(entry);
        status.workflowState = "done";
        status.message = "T1 cluster selected";
        status.updatedAtEpochMs = System.currentTimeMillis();
        TerritoryStageArtifacts.writeT1Status(ctx.server, ctx.worldId, status);

        JsonObject event = new JsonObject();
        event.addProperty("territory_id", territoryId);
        event.addProperty("continent_id", continentId);
        if (status.selectedClusterId != null) event.addProperty("selected_cluster_id", status.selectedClusterId);
        if (status.selectedClusterLabel != null) event.addProperty("selected_cluster_label", status.selectedClusterLabel);
        TStageTraceLogger.territory(ctx, "T1", entry.instanceId, "territory_cluster_selected", event);
        return status;
    }

    public static JsonObject getT2DirectionCandidates(StageContext ctx, String territoryId, int continentId) throws Exception {
        PlanEntry entry = requirePlanEntry(ctx, territoryId, continentId);
        TerritoryStageArtifacts.T2Status status = prepareT2(ctx, entry);
        JsonObject result = new JsonObject();
        result.addProperty("territory_id", territoryId);
        result.addProperty("territory_instance_id", entry.instanceId);
        result.addProperty("continent_id", continentId);
        result.addProperty("workflow_state", status.workflowState);
        result.addProperty("blocked", status.blocked);
        if (status.blockedReason != null) result.addProperty("blocked_reason", status.blockedReason);
        if (!status.recommendedAdjustments.isEmpty()) result.add("recommended_adjustments", toJsonArray(status.recommendedAdjustments));
        TerritoryStageArtifacts.readT2DirectionCandidates(ctx.server, ctx.worldId, territoryId, continentId)
                .ifPresent(bundle -> result.add("direction_bundle", bundle));
        return result;
    }

    public static TerritoryStageArtifacts.T2Status selectT2Direction(
            StageContext ctx,
            String territoryId,
            int continentId,
            String direction
    ) throws Exception {
        PlanEntry entry = requirePlanEntry(ctx, territoryId, continentId);
        JsonObject bundle = TerritoryStageArtifacts.readT2DirectionCandidates(ctx.server, ctx.worldId, territoryId, continentId)
                .orElseThrow(() -> new IllegalStateException("T2 direction candidates not generated for " + territoryId + " continent " + continentId));
        JsonObject selected = findDirection(bundle, direction)
                .orElseThrow(() -> new IllegalArgumentException("Requested direction not available"));
        return applyDirectionSelection(ctx, entry, bundle, selected, "ai_selected");
    }

    public static void assertT3Ready(StageContext ctx) throws Exception {
        ReadinessReport report = evaluateReadiness(ctx, requireBlueprints(ctx));
        if (!report.ready) {
            JsonObject blocked = new JsonObject();
            blocked.addProperty("message", report.message);
            TStageTraceLogger.stage(ctx, "T3", "gate_blocked", blocked);
            throw new IllegalStateException(report.message);
        }
        JsonObject passed = new JsonObject();
        passed.addProperty("message", "ready");
        TStageTraceLogger.stage(ctx, "T3", "gate_passed", passed);
    }

    public static Collection<TerritoryManager.TerritoryResult> runT3ForContinent(StageContext ctx, int continentId) throws Exception {
        List<GateRow> rows = buildGateRows(ctx, requireContinentEntries(ctx, continentId));
        JsonObject gateStart = new JsonObject();
        gateStart.addProperty("continent_id", continentId);
        gateStart.addProperty("territory_count", rows.size());
        TStageTraceLogger.continent(ctx, "T3", continentId, "continent_gate_started", gateStart);

        ReadinessReport report = evaluateReadiness(rows);
        if (!report.ready) {
            JsonObject blocked = new JsonObject();
            blocked.addProperty("continent_id", continentId);
            blocked.addProperty("message", report.message);
            TStageTraceLogger.continent(ctx, "T3", continentId, "continent_gate_blocked", blocked);
            throw new IllegalStateException(report.message);
        }

        JsonObject stageStart = new JsonObject();
        stageStart.addProperty("continent_id", continentId);
        stageStart.addProperty("territory_count", rows.size());
        TStageTraceLogger.continent(ctx, "T3", continentId, "continent_competitive_expansion_started", stageStart);

        Collection<TerritoryManager.TerritoryResult> results = TerritoryManager.runExpansionForContinent(continentId);
        int exported = TerritoryResultRepository.exportAllT3(ctx.server, results);
        if (exported <= 0) {
            throw new IllegalStateException("No territory results available for continent " + continentId);
        }

        TerritoryManager.ContinentExpansionReport continentReport = TerritoryManager.getContinentReport(continentId);
        if (continentReport != null) {
            for (TerritoryManager.CellConflictRecord record : continentReport.conflicts) {
                JsonObject conflict = new JsonObject();
                conflict.addProperty("continent_id", continentId);
                conflict.addProperty("cell_x", record.cellWorldX);
                conflict.addProperty("cell_z", record.cellWorldZ);
                conflict.addProperty("winner_id", record.winnerId);
                conflict.addProperty("winner_territory_id", record.winnerTerritoryId);
                conflict.addProperty("winning_cost", record.winningCost);
                conflict.addProperty("winning_score", record.winningScore);
                conflict.addProperty("land_power_spent", record.landPowerSpent);
                TStageTraceLogger.continent(ctx, "T3", continentId, "cell_conflict_resolved", conflict);
            }
        }

        long claimedTotal = 0L;
        long wildTotal = 0L;
        for (TerritoryManager.TerritoryResult result : results) {
            if (result == null || result.config == null) continue;
            claimedTotal += result.claimedChunks != null ? result.claimedChunks.size() : 0;
            wildTotal += result.wildChunks != null ? result.wildChunks.size() : 0;
            JsonObject territory = new JsonObject();
            territory.addProperty("territory_id", result.config.territoryId);
            territory.addProperty("territory_instance_id", result.config.id);
            territory.addProperty("continent_id", result.config.selectedContinentId);
            territory.addProperty("claimed_chunks", result.claimedChunks != null ? result.claimedChunks.size() : 0);
            territory.addProperty("wild_chunks", result.wildChunks != null ? result.wildChunks.size() : 0);
            territory.addProperty("land_power_remaining", result.stats != null ? result.stats.land_power_remaining : 0);
            TStageTraceLogger.territory(ctx, "T3", result.config.id, "territory_expanded", territory);
        }

        JsonObject stageDone = new JsonObject();
        stageDone.addProperty("continent_id", continentId);
        stageDone.addProperty("exported_count", exported);
        stageDone.addProperty("claimed_total", claimedTotal);
        stageDone.addProperty("wild_total", wildTotal);
        TStageTraceLogger.continent(ctx, "T3", continentId, "continent_expansion_completed", stageDone);
        return results;
    }

    static ReadinessReport evaluateReadiness(StageContext ctx, List<TerritoryBlueprint> blueprints) {
        return evaluateReadiness(buildGateRows(ctx, buildPlanEntries(blueprints)));
    }

    static ReadinessReport evaluateReadiness(List<GateRow> rows) {
        Map<Integer, List<String>> missingByContinent = new LinkedHashMap<>();
        Map<Integer, List<String>> blockedByContinent = new LinkedHashMap<>();
        Map<Integer, Map<String, List<String>>> pointKeys = new LinkedHashMap<>();
        Map<Integer, Map<String, List<String>>> seedKeys = new LinkedHashMap<>();

        for (GateRow row : rows) {
            List<String> missing = new ArrayList<>();
            if (!row.t1Ready) missing.add("T1");
            if (!row.t2Ready) missing.add("T2");
            if (!row.territoryConfigReady) missing.add("territory_config");
            if (!missing.isEmpty()) {
                missingByContinent.computeIfAbsent(row.continentId, ignored -> new ArrayList<>())
                        .add(row.territoryId + " missing " + String.join("/", missing));
            }
            if (row.blocked) {
                blockedByContinent.computeIfAbsent(row.continentId, ignored -> new ArrayList<>())
                        .add(row.territoryId + " blocked " + row.blockedReason);
            }
            if (row.selectedPointKey != null) {
                pointKeys.computeIfAbsent(row.continentId, ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(row.selectedPointKey, ignored -> new ArrayList<>())
                        .add(row.territoryId);
            }
            if (row.seedCellKey != null) {
                seedKeys.computeIfAbsent(row.continentId, ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(row.seedCellKey, ignored -> new ArrayList<>())
                        .add(row.territoryId);
            }
        }

        List<String> parts = new ArrayList<>();
        appendProblemParts(parts, "incomplete T1/T2 prerequisites", missingByContinent);
        appendProblemParts(parts, "blocked territories", blockedByContinent);
        appendDuplicateParts(parts, "duplicate capitals", pointKeys);
        appendDuplicateParts(parts, "duplicate seed cells", seedKeys);
        if (parts.isEmpty()) {
            return new ReadinessReport(true, "ready");
        }
        return new ReadinessReport(false, "T3 blocked: " + String.join(" | ", parts));
    }

    private static void appendProblemParts(
            List<String> parts,
            String label,
            Map<Integer, List<String>> issuesByContinent
    ) {
        if (issuesByContinent.isEmpty()) return;
        for (Map.Entry<Integer, List<String>> entry : issuesByContinent.entrySet()) {
            parts.add(label + " region_id=" + entry.getKey() + ": " + String.join(", ", entry.getValue()));
        }
    }

    private static void appendDuplicateParts(
            List<String> parts,
            String label,
            Map<Integer, Map<String, List<String>>> keysByContinent
    ) {
        for (Map.Entry<Integer, Map<String, List<String>>> entry : keysByContinent.entrySet()) {
            List<String> duplicates = new ArrayList<>();
            for (Map.Entry<String, List<String>> keyEntry : entry.getValue().entrySet()) {
                if (keyEntry.getValue().size() > 1) {
                    duplicates.add(keyEntry.getKey() + " -> " + String.join("/", keyEntry.getValue()));
                }
            }
            if (!duplicates.isEmpty()) {
                parts.add(label + " region_id=" + entry.getKey() + ": " + String.join(", ", duplicates));
            }
        }
    }

    private static TerritoryStageArtifacts.T1Status prepareT1(StageContext ctx, PlanEntry entry) throws Exception {
        JsonObject raw = buildT1RawBundle(ctx, entry);
        List<ReservedPoint> reservedPoints = loadReservedPoints(ctx, entry.continentId, entry.instanceId);
        JsonArray filtered = new JsonArray();
        JsonArray disabled = new JsonArray();
        JsonArray rawCandidates = raw.has("candidates") && raw.get("candidates").isJsonArray()
                ? raw.getAsJsonArray("candidates")
                : new JsonArray();
        for (JsonElement element : rawCandidates) {
            if (!element.isJsonObject()) continue;
            JsonObject candidate = element.getAsJsonObject();
            ReservedPoint conflict = findClusterConflict(candidate, reservedPoints);
            if (conflict != null) {
                JsonObject removed = new JsonObject();
                if (candidate.has("cluster_id")) removed.add("cluster_id", candidate.get("cluster_id"));
                if (candidate.has("label")) removed.add("label", candidate.get("label"));
                removed.addProperty("reserved_by", conflict.territoryId);
                removed.addProperty("reserved_point_x", conflict.pointX);
                removed.addProperty("reserved_point_z", conflict.pointZ);
                disabled.add(removed);

                JsonObject trace = new JsonObject();
                trace.addProperty("territory_id", entry.territoryId);
                trace.addProperty("territory_instance_id", entry.instanceId);
                trace.addProperty("continent_id", entry.continentId);
                if (candidate.has("cluster_id")) trace.add("cluster_id", candidate.get("cluster_id"));
                trace.addProperty("reserved_by", conflict.territoryId);
                TStageTraceLogger.territory(ctx, "T1", entry.instanceId, "cluster_filtered_due_to_reserved_point", trace);
                continue;
            }
            filtered.add(candidate.deepCopy());
        }

        JsonObject stored = raw.deepCopy();
        stored.addProperty("selected_continent_id", entry.continentId);
        stored.add("raw_candidates", rawCandidates.deepCopy());
        stored.add("candidates", filtered);
        stored.add("disabled_candidates", disabled);
        stored.add("reserved_points", toReservedPointsJson(reservedPoints));
        TerritoryStageArtifacts.writeT1Candidates(ctx.server, ctx.worldId, entry.territoryId, entry.continentId, stored);

        TerritoryStageArtifacts.T1Status previous = TerritoryStageArtifacts.readT1Status(ctx.server, ctx.worldId, entry.territoryId, entry.continentId)
                .orElse(null);
        TerritoryStageArtifacts.T1Status status = baseT1Status(entry);
        status.previewGenerated = stored.has("preview_overlay");
        status.candidatesGenerated = true;
        status.previewImage = extractPreviewPath(stored);
        status.candidatesFile = t1CandidatesRelativePath(entry);
        status.updatedAtEpochMs = System.currentTimeMillis();

        Optional<JsonObject> preserved = previous != null && previous.clusterSelected
                ? findCandidate(stored, previous.selectedClusterId, previous.selectedClusterLabel)
                : Optional.empty();
        if (preserved.isPresent()) {
            status.clusterSelected = true;
            status.blocked = false;
            status.selectedClusterId = preserved.get().has("cluster_id") ? preserved.get().get("cluster_id").getAsInt() : null;
            status.selectedClusterLabel = preserved.get().has("label") ? preserved.get().get("label").getAsString() : null;
            status.selectionSource = previous.selectionSource != null ? previous.selectionSource : "persisted_selection";
            status.workflowState = "done";
            status.message = "T1 cluster preserved";
        } else if (filtered.size() == 0) {
            status.blocked = true;
            status.blockedReason = "no_available_cluster_after_conflict_filter";
            status.workflowState = "blocked";
            status.message = "No T1 candidates remain after continent conflict filtering";
            appendDefaultAdjustments(status.recommendedAdjustments, "loosen slope/tpi thresholds", "reduce top_k filter strictness", "switch target landform or biome preference");
            appendScanDiagnostics(status.recommendedAdjustments, entry.continentId);
            JsonObject blocked = new JsonObject();
            blocked.addProperty("territory_id", entry.territoryId);
            blocked.addProperty("territory_instance_id", entry.instanceId);
            blocked.addProperty("continent_id", entry.continentId);
            blocked.addProperty("reason", status.blockedReason);
            TStageTraceLogger.territory(ctx, "T1", entry.instanceId, "territory_blocked", blocked);
        } else {
            status.workflowState = "pending_ai";
            status.message = "Awaiting AI cluster selection";
            JsonObject generated = new JsonObject();
            generated.addProperty("territory_id", entry.territoryId);
            generated.addProperty("territory_instance_id", entry.instanceId);
            generated.addProperty("continent_id", entry.continentId);
            generated.addProperty("available_candidates", filtered.size());
            TStageTraceLogger.territory(ctx, "T1", entry.instanceId, "territory_candidates_generated", generated);
        }

        TerritoryStageArtifacts.writeT1Status(ctx.server, ctx.worldId, status);
        return status;
    }

    private static TerritoryStageArtifacts.T2Status prepareT2(StageContext ctx, PlanEntry entry) throws Exception {
        TerritoryStageArtifacts.T1Status t1 = TerritoryStageArtifacts.readT1Status(ctx.server, ctx.worldId, entry.territoryId, entry.continentId)
                .orElse(null);
        TerritoryStageArtifacts.T2Status previous = TerritoryStageArtifacts.readT2Status(ctx.server, ctx.worldId, entry.territoryId, entry.continentId)
                .orElse(null);
        TerritoryStageArtifacts.T2Status status = baseT2Status(entry);
        status.updatedAtEpochMs = System.currentTimeMillis();
        status.t1ClusterReady = t1 != null && t1.clusterSelected && !t1.blocked;
        status.selectedClusterId = t1 != null ? t1.selectedClusterId : null;
        status.selectedClusterLabel = t1 != null ? t1.selectedClusterLabel : null;

        if (!status.t1ClusterReady) {
            status.workflowState = "pending_ai";
            status.message = "Awaiting T1 cluster selection";
            TerritoryStageArtifacts.writeT2Status(ctx.server, ctx.worldId, status);
            return status;
        }

        JsonObject t1Bundle = TerritoryStageArtifacts.readT1Candidates(ctx.server, ctx.worldId, entry.territoryId, entry.continentId)
                .orElseThrow(() -> new IllegalStateException("T1 candidates missing for " + entry.instanceId));
        JsonObject selectedCluster = findCandidate(t1Bundle, status.selectedClusterId, status.selectedClusterLabel)
                .orElseThrow(() -> new IllegalStateException("Selected T1 cluster not found in stored candidates"));
        JsonObject directionBundle = buildDirectionBundle(ctx, entry, selectedCluster);
        TerritoryStageArtifacts.writeT2DirectionCandidates(ctx.server, ctx.worldId, entry.territoryId, entry.continentId, directionBundle);
        status.directionsGenerated = true;

        Optional<JsonObject> preserved = previous != null && previous.pointSelected && previous.selectedDirection != null
                ? findDirection(directionBundle, previous.selectedDirection)
                : Optional.empty();
        if (preserved.isPresent()) {
            return applyDirectionSelection(ctx, entry, directionBundle, preserved.get(), previous.pointSelectionSource != null ? previous.pointSelectionSource : "persisted_selection");
        }

        JsonArray directions = directionBundle.getAsJsonArray("directions");
        int availableCount = 0;
        for (JsonElement element : directions) {
            if (element.isJsonObject() && element.getAsJsonObject().has("available") && element.getAsJsonObject().get("available").getAsBoolean()) {
                availableCount++;
            }
        }
        if (availableCount == 0) {
            status.blocked = true;
            status.blockedReason = "no_available_direction_after_conflict_filter";
            status.workflowState = "blocked";
            status.message = "No valid T2 direction remains after point/seed conflict filtering";
            appendDefaultAdjustments(status.recommendedAdjustments, "return to T1 and pick another cluster", "adjust site preferences", "free or move conflicting territory on this continent");
            if (bundleHasOnlySeedUnavailable(directionBundle)) {
                appendDefaultAdjustments(
                        status.recommendedAdjustments,
                        "check whether continent " + entry.continentId + " was scanned in W4",
                        "check whether continent " + entry.continentId + " detail cache is loaded in memory",
                        "re-run continent scan/export for region " + entry.continentId + " before retrying T2"
                );
                appendScanDiagnostics(status.recommendedAdjustments, entry.continentId);
            }
            TerritoryStageArtifacts.writeT2Status(ctx.server, ctx.worldId, status);
            JsonObject blocked = new JsonObject();
            blocked.addProperty("territory_id", entry.territoryId);
            blocked.addProperty("territory_instance_id", entry.instanceId);
            blocked.addProperty("continent_id", entry.continentId);
            blocked.addProperty("reason", status.blockedReason);
            TStageTraceLogger.territory(ctx, "T2", entry.instanceId, "territory_blocked", blocked);
            return status;
        }

        status.workflowState = "pending_ai";
        status.message = "Awaiting AI direction selection";
        TerritoryStageArtifacts.writeT2Status(ctx.server, ctx.worldId, status);
        JsonObject generated = new JsonObject();
        generated.addProperty("territory_id", entry.territoryId);
        generated.addProperty("territory_instance_id", entry.instanceId);
        generated.addProperty("continent_id", entry.continentId);
        generated.addProperty("available_directions", availableCount);
        TStageTraceLogger.territory(ctx, "T2", entry.instanceId, "direction_candidates_generated", generated);
        return status;
    }

    private static TerritoryStageArtifacts.T2Status applyDirectionSelection(
            StageContext ctx,
            PlanEntry entry,
            JsonObject bundle,
            JsonObject selectedDirection,
            String selectionSource
    ) throws Exception {
        TerritoryBlueprint.ExpansionPolicy policy = entry.blueprint.normalizedExpansionPolicy();
        JsonObject point = selectedDirection.getAsJsonObject("point");
        int pointX = point.get("x").getAsInt();
        int pointZ = point.get("z").getAsInt();
        int seedCellX = selectedDirection.get("seed_cell_x").getAsInt();
        int seedCellZ = selectedDirection.get("seed_cell_z").getAsInt();

        TerritoryManager.createTerritory(
                entry.instanceId,
                entry.territoryId,
                entry.name,
                entry.continentId,
                pointX,
                pointZ,
                seedCellX,
                seedCellZ,
                policy.base_power,
                policy.land_power,
                entry.blueprintOrder,
                resolveMountainCost(entry.blueprint),
                resolveWaterCost(entry.blueprint),
                parseBlueprintColor(entry.blueprint)
        );

        TerritoryManager.TerritoryConfig cfg = TerritoryManager.getTerritoryConfig(entry.instanceId);
        if (cfg != null) {
            TerritoryResultRepository.writeT2Capital(ctx.server, cfg);
        }

        TerritoryStageArtifacts.T2Status status = baseT2Status(entry);
        status.updatedAtEpochMs = System.currentTimeMillis();
        status.t1ClusterReady = true;
        status.directionsGenerated = true;
        status.pointSelected = cfg != null;
        status.territoryConfigWritten = cfg != null;
        status.expansionReady = cfg != null;
        status.workflowState = cfg != null ? "done" : "blocked";
        status.message = cfg != null ? "T2 point selected" : "Failed to write territory config";
        status.selectedClusterId = bundle.has("selected_cluster_id") ? bundle.get("selected_cluster_id").getAsInt() : null;
        status.selectedClusterLabel = bundle.has("selected_cluster_label") ? bundle.get("selected_cluster_label").getAsString() : null;
        status.selectedDirection = selectedDirection.get("direction").getAsString();
        TerritoryStageArtifacts.SelectedPoint selectedPoint = new TerritoryStageArtifacts.SelectedPoint();
        selectedPoint.x = pointX;
        selectedPoint.z = pointZ;
        status.selectedPoint = selectedPoint;
        status.seedCellX = seedCellX;
        status.seedCellZ = seedCellZ;
        status.pointSelectionSource = selectionSource;
        TerritoryStageArtifacts.writeT2Status(ctx.server, ctx.worldId, status);

        JsonObject done = new JsonObject();
        done.addProperty("territory_id", entry.territoryId);
        done.addProperty("territory_instance_id", entry.instanceId);
        done.addProperty("continent_id", entry.continentId);
        done.addProperty("selected_direction", status.selectedDirection);
        done.addProperty("selected_x", pointX);
        done.addProperty("selected_z", pointZ);
        done.addProperty("seed_cell_x", seedCellX);
        done.addProperty("seed_cell_z", seedCellZ);
        TStageTraceLogger.territory(ctx, "T2", entry.instanceId, "territory_point_selected", done);
        return status;
    }

    private static JsonObject buildT1RawBundle(StageContext ctx, PlanEntry entry) {
        JsonObject req = new JsonObject();
        req.addProperty("region_id", entry.continentId);
        req.addProperty("limit", Math.max(5, entry.topK * 3));
        if (entry.preferences.min_slope != null) req.addProperty("min_slope", entry.preferences.min_slope);
        if (entry.preferences.max_slope != null) req.addProperty("max_slope", entry.preferences.max_slope);
        if (entry.preferences.min_tpi != null) req.addProperty("min_tpi", entry.preferences.min_tpi);
        if (entry.preferences.max_tpi != null) req.addProperty("max_tpi", entry.preferences.max_tpi);
        JsonObject bundle = WorldController.buildQueryRegionBundle(ctx.server, req);
        JsonObject metadata = bundle.has("metadata") && bundle.get("metadata").isJsonObject()
                ? bundle.getAsJsonObject("metadata")
                : new JsonObject();
        metadata.addProperty("target_type", "territory_blueprint");
        metadata.addProperty("target_id", entry.territoryId);
        metadata.addProperty("target_instance_id", entry.instanceId);
        metadata.addProperty("continent_id", entry.continentId);
        bundle.add("metadata", metadata);
        return bundle;
    }

    private static JsonObject buildDirectionBundle(StageContext ctx, PlanEntry entry, JsonObject selectedCluster) {
        JsonObject bundle = new JsonObject();
        bundle.addProperty("territory_id", entry.territoryId);
        bundle.addProperty("territory_instance_id", entry.instanceId);
        bundle.addProperty("continent_id", entry.continentId);
        if (selectedCluster.has("cluster_id")) bundle.add("selected_cluster_id", selectedCluster.get("cluster_id"));
        if (selectedCluster.has("label")) bundle.add("selected_cluster_label", selectedCluster.get("label"));
        JsonArray directions = new JsonArray();

        List<ReservedPoint> reserved = loadReservedPoints(ctx, entry.continentId, entry.instanceId);
        for (String direction : List.of("center", "north", "south", "east", "west")) {
            JsonObject row = new JsonObject();
            row.addProperty("direction", direction);
            JsonObject point = resolveDirectionPoint(selectedCluster, direction);
            if (point == null) {
                row.addProperty("available", false);
                row.addProperty("conflict_reason", "missing_direction_point");
                directions.add(row);
                continue;
            }

            int pointX = point.get("x").getAsInt();
            int pointZ = point.get("z").getAsInt();
            row.add("point", point.deepCopy());
            TerritoryManager.SeedCell seedCell = TerritoryManager.computeSeedCell(entry.continentId, pointX, pointZ);
            if (seedCell == null) {
                row.addProperty("available", false);
                row.addProperty("conflict_reason", "seed_cell_unavailable");
                row.add("recommended_adjustments", scanDiagnosticArray(entry.continentId));
                directions.add(row);
                continue;
            }
            row.addProperty("seed_cell_x", seedCell.worldX);
            row.addProperty("seed_cell_z", seedCell.worldZ);

            ReservedPoint conflict = findDirectionConflict(pointX, pointZ, seedCell.worldX, seedCell.worldZ, reserved);
            if (conflict != null) {
                row.addProperty("available", false);
                row.addProperty("conflict_reason", conflict.seedCellX == seedCell.worldX && conflict.seedCellZ == seedCell.worldZ
                        ? "seed_cell_conflict"
                        : "point_conflict");
                row.addProperty("conflict_with", conflict.territoryId);
                if ("seed_cell_conflict".equals(row.get("conflict_reason").getAsString())) {
                    JsonObject trace = new JsonObject();
                    trace.addProperty("territory_id", entry.territoryId);
                    trace.addProperty("territory_instance_id", entry.instanceId);
                    trace.addProperty("continent_id", entry.continentId);
                    trace.addProperty("direction", direction);
                    trace.addProperty("conflict_with", conflict.territoryId);
                    TStageTraceLogger.territory(ctx, "T2", entry.instanceId, "direction_filtered_due_to_seed_conflict", trace);
                } else {
                    JsonObject trace = new JsonObject();
                    trace.addProperty("territory_id", entry.territoryId);
                    trace.addProperty("territory_instance_id", entry.instanceId);
                    trace.addProperty("continent_id", entry.continentId);
                    trace.addProperty("direction", direction);
                    trace.addProperty("conflict_with", conflict.territoryId);
                    TStageTraceLogger.territory(ctx, "T2", entry.instanceId, "direction_filtered_due_to_point_conflict", trace);
                }
            } else {
                row.addProperty("available", true);
            }
            directions.add(row);
        }
        bundle.add("directions", directions);
        bundle.add("reserved_points", toReservedPointsJson(reserved));
        return bundle;
    }

    private static List<PlanEntry> requirePlanEntries(StageContext ctx) {
        return buildPlanEntries(requireBlueprints(ctx));
    }

    private static List<PlanEntry> requireContinentEntries(StageContext ctx, int continentId) {
        List<PlanEntry> matches = new ArrayList<>();
        for (PlanEntry entry : requirePlanEntries(ctx)) {
            if (entry.continentId == continentId) matches.add(entry);
        }
        if (matches.isEmpty()) throw new IllegalArgumentException("No blueprint entries found for continent " + continentId);
        return matches;
    }

    private static PlanEntry requirePlanEntry(StageContext ctx, String territoryId, int continentId) {
        for (PlanEntry entry : requirePlanEntries(ctx)) {
            if (entry.continentId == continentId && territoryId.equals(entry.territoryId)) return entry;
        }
        throw new IllegalArgumentException("No blueprint entry found for " + territoryId + " continent " + continentId);
    }

    private static List<TerritoryBlueprint> requireBlueprints(StageContext ctx) {
        TerritoryRepository.Result result = TerritoryRepository.listBlueprints(ctx.server);
        if (!result.ok || result.list == null || result.list.isEmpty()) {
            throw new IllegalStateException("No T1 blueprints available");
        }
        return result.list;
    }

    private static List<PlanEntry> buildPlanEntries(List<TerritoryBlueprint> blueprints) {
        List<PlanEntry> entries = new ArrayList<>();
        int blueprintOrder = 0;
        for (TerritoryBlueprint blueprint : blueprints) {
            if (blueprint == null) continue;
            for (TerritoryBlueprint.ContinentPlan continent : blueprint.normalizedContinents()) {
                entries.add(new PlanEntry(blueprint, continent, blueprintOrder++));
            }
        }
        return entries;
    }

    private static Map<Integer, List<PlanEntry>> planByContinent(List<PlanEntry> entries) {
        Map<Integer, List<PlanEntry>> byContinent = new LinkedHashMap<>();
        for (PlanEntry entry : entries) {
            byContinent.computeIfAbsent(entry.continentId, ignored -> new ArrayList<>()).add(entry);
        }
        for (List<PlanEntry> list : byContinent.values()) {
            list.sort(Comparator.comparingInt(value -> value.blueprintOrder));
        }
        return byContinent;
    }

    private static List<GateRow> buildGateRows(StageContext ctx, List<PlanEntry> entries) {
        List<GateRow> rows = new ArrayList<>();
        for (PlanEntry entry : entries) {
            TerritoryStageArtifacts.T1Status t1 = TerritoryStageArtifacts.readT1Status(ctx.server, ctx.worldId, entry.territoryId, entry.continentId).orElse(null);
            TerritoryStageArtifacts.T2Status t2 = TerritoryStageArtifacts.readT2Status(ctx.server, ctx.worldId, entry.territoryId, entry.continentId).orElse(null);
            TerritoryManager.TerritoryConfig cfg = TerritoryManager.getTerritoryConfig(entry.instanceId);
            rows.add(new GateRow(
                    entry.continentId,
                    entry.territoryId,
                    t1 != null && t1.clusterSelected && !t1.blocked,
                    t2 != null && t2.pointSelected && !t2.blocked,
                    cfg != null,
                    (t1 != null && t1.blocked) || (t2 != null && t2.blocked),
                    t2 != null && t2.blockedReason != null ? t2.blockedReason : (t1 != null ? t1.blockedReason : null),
                    t2 != null && t2.selectedPoint != null ? t2.selectedPoint.x + ":" + t2.selectedPoint.z : null,
                    t2 != null && t2.seedCellX != null && t2.seedCellZ != null ? t2.seedCellX + ":" + t2.seedCellZ : null
            ));
        }
        return rows;
    }

    private static TerritoryStageArtifacts.ContinentIndexItem indexItem(
            boolean completed,
            boolean blocked,
            PlanEntry entry,
            String message,
            String workflowState
    ) {
        TerritoryStageArtifacts.ContinentIndexItem item = new TerritoryStageArtifacts.ContinentIndexItem();
        item.completed = completed;
        item.blocked = blocked;
        item.workflowState = workflowState;
        item.territoryId = entry.territoryId;
        item.territoryInstanceId = entry.instanceId;
        item.territoryName = entry.name;
        item.message = message;
        return item;
    }

    private static void writeIndexes(
            StageContext ctx,
            String stageId,
            Map<Integer, List<TerritoryStageArtifacts.ContinentIndexItem>> byContinent,
            long now
    ) throws Exception {
        for (Map.Entry<Integer, List<TerritoryStageArtifacts.ContinentIndexItem>> entry : byContinent.entrySet()) {
            TerritoryStageArtifacts.ContinentIndex index = new TerritoryStageArtifacts.ContinentIndex();
            index.stage = stageId;
            index.continentId = entry.getKey();
            index.updatedAtEpochMs = now;
            index.territoryCount = entry.getValue().size();
            index.items.addAll(entry.getValue());
            for (TerritoryStageArtifacts.ContinentIndexItem item : entry.getValue()) {
                if (item.completed) index.completedCount++;
                if (item.blocked) index.blockedCount++;
            }
            index.pendingCount = Math.max(0, index.territoryCount - index.completedCount - index.blockedCount);
            TerritoryStageArtifacts.writeContinentIndex(ctx.server, ctx.worldId, entry.getKey(), stageId, index);
        }
    }

    private static TerritoryStageArtifacts.T1Status baseT1Status(PlanEntry entry) {
        TerritoryStageArtifacts.T1Status status = new TerritoryStageArtifacts.T1Status();
        status.territoryId = entry.territoryId;
        status.territoryName = entry.name;
        status.territoryInstanceId = entry.instanceId;
        status.selectedContinentId = entry.continentId;
        return status;
    }

    private static TerritoryStageArtifacts.T2Status baseT2Status(PlanEntry entry) {
        TerritoryStageArtifacts.T2Status status = new TerritoryStageArtifacts.T2Status();
        status.territoryId = entry.territoryId;
        status.territoryName = entry.name;
        status.territoryInstanceId = entry.instanceId;
        status.selectedContinentId = entry.continentId;
        return status;
    }

    private static Optional<JsonObject> findCandidate(JsonObject bundle, Integer clusterId, String label) {
        if (bundle == null || !bundle.has("candidates") || !bundle.get("candidates").isJsonArray()) return Optional.empty();
        for (JsonElement element : bundle.getAsJsonArray("candidates")) {
            if (!element.isJsonObject()) continue;
            JsonObject candidate = element.getAsJsonObject();
            Integer candidateId = candidate.has("cluster_id") ? candidate.get("cluster_id").getAsInt() : null;
            String candidateLabel = candidate.has("label") ? candidate.get("label").getAsString() : null;
            if (clusterId != null && clusterId.equals(candidateId)) return Optional.of(candidate);
            if (label != null && candidateLabel != null && label.equalsIgnoreCase(candidateLabel)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    private static Optional<JsonObject> findDirection(JsonObject bundle, String direction) {
        if (bundle == null || direction == null || !bundle.has("directions") || !bundle.get("directions").isJsonArray()) {
            return Optional.empty();
        }
        for (JsonElement element : bundle.getAsJsonArray("directions")) {
            if (!element.isJsonObject()) continue;
            JsonObject row = element.getAsJsonObject();
            if (!row.has("direction")) continue;
            if (!direction.equalsIgnoreCase(row.get("direction").getAsString())) continue;
            if (row.has("available") && row.get("available").getAsBoolean()) return Optional.of(row);
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static ReservedPoint findClusterConflict(JsonObject candidate, List<ReservedPoint> reservedPoints) {
        for (ReservedPoint reserved : reservedPoints) {
            if (candidateContainsPoint(candidate, reserved.pointX, reserved.pointZ)) return reserved;
        }
        return null;
    }

    static boolean candidateContainsPoint(JsonObject candidate, int worldX, int worldZ) {
        if (candidate == null || !candidate.has("transform") || !candidate.get("transform").isJsonObject()) return false;
        JsonObject transform = candidate.getAsJsonObject("transform");
        if (!transform.has("origin_x") || !transform.has("origin_z") || !transform.has("scale")) return false;
        int originX = transform.get("origin_x").getAsInt();
        int originZ = transform.get("origin_z").getAsInt();
        int scale = Math.max(1, transform.get("scale").getAsInt());
        int ix = Math.floorDiv(worldX - originX, scale);
        int iz = Math.floorDiv(worldZ - originZ, scale);
        if (!candidate.has("ascii_map") || !candidate.get("ascii_map").isJsonArray()) return false;
        JsonArray ascii = candidate.getAsJsonArray("ascii_map");
        if (iz < 0 || iz >= ascii.size()) return false;
        String row = ascii.get(iz).getAsString();
        if (ix < 0 || ix >= row.length()) return false;
        return row.charAt(ix) == '#';
    }

    private static ReservedPoint findDirectionConflict(int pointX, int pointZ, int seedCellX, int seedCellZ, List<ReservedPoint> reserved) {
        for (ReservedPoint point : reserved) {
            if (point.pointX == pointX && point.pointZ == pointZ) return point;
            if (point.seedCellX == seedCellX && point.seedCellZ == seedCellZ) return point;
        }
        return null;
    }

    private static List<ReservedPoint> loadReservedPoints(StageContext ctx, int continentId, String excludeInstanceId) {
        List<ReservedPoint> reserved = new ArrayList<>();
        for (PlanEntry entry : requirePlanEntries(ctx)) {
            if (entry.continentId != continentId || entry.instanceId.equals(excludeInstanceId)) continue;
            TerritoryStageArtifacts.T2Status t2 = TerritoryStageArtifacts.readT2Status(ctx.server, ctx.worldId, entry.territoryId, entry.continentId).orElse(null);
            if (t2 == null || !t2.pointSelected || t2.selectedPoint == null || t2.seedCellX == null || t2.seedCellZ == null) continue;
            reserved.add(new ReservedPoint(entry.instanceId, entry.territoryId, t2.selectedPoint.x, t2.selectedPoint.z, t2.seedCellX, t2.seedCellZ));
        }
        return reserved;
    }

    private static JsonArray toReservedPointsJson(List<ReservedPoint> reservedPoints) {
        JsonArray array = new JsonArray();
        for (ReservedPoint point : reservedPoints) {
            JsonObject row = new JsonObject();
            row.addProperty("territory_id", point.territoryId);
            row.addProperty("territory_instance_id", point.territoryInstanceId);
            row.addProperty("point_x", point.pointX);
            row.addProperty("point_z", point.pointZ);
            row.addProperty("seed_cell_x", point.seedCellX);
            row.addProperty("seed_cell_z", point.seedCellZ);
            array.add(row);
        }
        return array;
    }

    private static JsonObject resolveDirectionPoint(JsonObject candidate, String direction) {
        if (candidate == null || !candidate.has("key_points") || !candidate.get("key_points").isJsonObject()) return null;
        JsonObject keyPoints = candidate.getAsJsonObject("key_points");
        String key = switch (direction == null ? "center" : direction.trim().toLowerCase()) {
            case "north" -> "north_tip";
            case "south" -> "south_tip";
            case "east" -> "east_tip";
            case "west" -> "west_tip";
            default -> "center";
        };
        if (keyPoints.has(key) && keyPoints.get(key).isJsonObject()) return keyPoints.getAsJsonObject(key);
        return keyPoints.has("center") && keyPoints.get("center").isJsonObject() ? keyPoints.getAsJsonObject("center") : null;
    }

    private static String extractPreviewPath(JsonObject bundle) {
        if (bundle == null || !bundle.has("preview_overlay") || !bundle.get("preview_overlay").isJsonObject()) return null;
        JsonObject preview = bundle.getAsJsonObject("preview_overlay");
        return preview.has("preview_image") ? preview.get("preview_image").getAsString() : null;
    }

    private static String t1CandidatesRelativePath(PlanEntry entry) {
        return "territory/" + entry.territoryId + "/continents/region_" + entry.continentId + "/T1/T1_Candidates.json";
    }

    private static void appendDefaultAdjustments(List<String> target, String... values) {
        if (target == null || values == null) return;
        for (String value : values) {
            if (value != null && !value.isBlank()) target.add(value);
        }
    }

    private static void appendScanDiagnostics(List<String> target, int continentId) {
        ScanResultHolder holder = ScanResultHolder.get();
        boolean hasDetail = holder.regionCacheMap != null && holder.regionCacheMap.containsKey(continentId);
        boolean hasClusterMap = holder.lastClusterMap != null;
        if (!hasDetail) {
            appendDefaultAdjustments(
                    target,
                    "continent " + continentId + " has no loaded detail cache; check whether W4 scanned this continent",
                    "if the scan was done earlier, verify local continent cache/artifacts were loaded into memory"
            );
        }
        if (!hasClusterMap) {
            appendDefaultAdjustments(
                    target,
                    "global cluster map is not loaded; re-run W3/W4 or reload the current world scan cache"
            );
        }
    }

    private static JsonArray scanDiagnosticArray(int continentId) {
        List<String> messages = new ArrayList<>();
        appendDefaultAdjustments(
                messages,
                "check whether continent " + continentId + " was scanned in W4",
                "check whether continent " + continentId + " local detail cache is loaded in memory"
        );
        appendScanDiagnostics(messages, continentId);
        return toJsonArray(messages);
    }

    private static boolean bundleHasOnlySeedUnavailable(JsonObject directionBundle) {
        if (directionBundle == null || !directionBundle.has("directions") || !directionBundle.get("directions").isJsonArray()) {
            return false;
        }
        JsonArray directions = directionBundle.getAsJsonArray("directions");
        if (directions.size() == 0) return false;
        for (JsonElement element : directions) {
            if (!element.isJsonObject()) return false;
            JsonObject row = element.getAsJsonObject();
            if (!row.has("available") || row.get("available").getAsBoolean()) return false;
            if (!row.has("conflict_reason") || !"seed_cell_unavailable".equals(row.get("conflict_reason").getAsString())) {
                return false;
            }
        }
        return true;
    }

    private static JsonArray toJsonArray(List<String> values) {
        JsonArray array = new JsonArray();
        if (values == null) return array;
        for (String value : values) {
            if (value != null && !value.isBlank()) array.add(value);
        }
        return array;
    }

    private static double resolveMountainCost(TerritoryBlueprint blueprint) {
        return blueprint.normalizedExpansionPolicy().costs.slope_penalty;
    }

    private static double resolveWaterCost(TerritoryBlueprint blueprint) {
        return blueprint.normalizedExpansionPolicy().costs.water_penalty;
    }

    private static int parseBlueprintColor(TerritoryBlueprint blueprint) {
        String raw = blueprint != null && blueprint.narrative != null ? blueprint.narrative.color : null;
        if (raw == null || raw.isBlank()) return 0xFF0000;
        String normalized = raw.trim();
        if (normalized.startsWith("#")) normalized = normalized.substring(1);
        if (normalized.startsWith("0x") || normalized.startsWith("0X")) normalized = normalized.substring(2);
        try {
            return Integer.parseInt(normalized, 16);
        } catch (Exception ignored) {
            return 0xFF0000;
        }
    }

    static final class ReadinessReport {
        final boolean ready;
        final String message;

        ReadinessReport(boolean ready, String message) {
            this.ready = ready;
            this.message = message;
        }
    }

    static final class GateRow {
        final int continentId;
        final String territoryId;
        final boolean t1Ready;
        final boolean t2Ready;
        final boolean territoryConfigReady;
        final boolean blocked;
        final String blockedReason;
        final String selectedPointKey;
        final String seedCellKey;

        GateRow(int continentId, String territoryId, boolean t1Ready, boolean t2Ready, boolean territoryConfigReady) {
            this(continentId, territoryId, t1Ready, t2Ready, territoryConfigReady, false, null, null, null);
        }

        GateRow(
                int continentId,
                String territoryId,
                boolean t1Ready,
                boolean t2Ready,
                boolean territoryConfigReady,
                boolean blocked,
                String blockedReason,
                String selectedPointKey,
                String seedCellKey
        ) {
            this.continentId = continentId;
            this.territoryId = territoryId;
            this.t1Ready = t1Ready;
            this.t2Ready = t2Ready;
            this.territoryConfigReady = territoryConfigReady;
            this.blocked = blocked;
            this.blockedReason = blockedReason == null ? "unknown" : blockedReason;
            this.selectedPointKey = selectedPointKey;
            this.seedCellKey = seedCellKey;
        }
    }

    private static final class PlanEntry {
        final TerritoryBlueprint blueprint;
        final TerritoryBlueprint.SitePreferences preferences;
        final int blueprintOrder;
        final int continentId;
        final int topK;
        final String territoryId;
        final String instanceId;
        final String name;

        private PlanEntry(TerritoryBlueprint blueprint, TerritoryBlueprint.ContinentPlan continent, int blueprintOrder) {
            this.blueprint = blueprint;
            this.preferences = continent.site_preferences != null ? continent.site_preferences.normalized() : new TerritoryBlueprint.SitePreferences().normalized();
            this.blueprintOrder = blueprintOrder;
            this.continentId = continent.continent_id;
            this.topK = this.preferences.top_k != null && this.preferences.top_k > 0 ? this.preferences.top_k : 5;
            this.territoryId = blueprint.territory_id;
            this.instanceId = blueprint.instanceId(continent.continent_id);
            this.name = blueprint.name;
        }
    }

    private static final class ReservedPoint {
        final String territoryInstanceId;
        final String territoryId;
        final int pointX;
        final int pointZ;
        final int seedCellX;
        final int seedCellZ;

        private ReservedPoint(String territoryInstanceId, String territoryId, int pointX, int pointZ, int seedCellX, int seedCellZ) {
            this.territoryInstanceId = territoryInstanceId;
            this.territoryId = territoryId;
            this.pointX = pointX;
            this.pointZ = pointZ;
            this.seedCellX = seedCellX;
            this.seedCellZ = seedCellZ;
        }
    }
}
