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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class TerritoryStageOrchestrator {
    private TerritoryStageOrchestrator() {}

    public static void runT1(StageContext ctx) throws Exception {
        List<TerritoryBlueprint> blueprints = requireBlueprints(ctx);
        if (ScanResultHolder.get().regionCacheMap.isEmpty()) {
            throw new IllegalStateException("T1 requires region detail cache from W4 before candidate generation");
        }
        JsonObject stageStart = new JsonObject();
        stageStart.addProperty("territory_count", blueprints.size());
        TStageTraceLogger.stage(ctx, "T1", "stage_started", stageStart);

        Map<Integer, List<TerritoryStageArtifacts.ContinentIndexItem>> byContinent = new LinkedHashMap<>();
        long now = System.currentTimeMillis();

        for (TerritoryBlueprint blueprint : sortBlueprints(blueprints)) {
            JsonObject territoryStart = new JsonObject();
            territoryStart.addProperty("territory_id", blueprint.territory_id);
            territoryStart.addProperty("territory_name", blueprint.name);
            territoryStart.addProperty("continent_id", blueprint.target_continent_id);
            TStageTraceLogger.territory(ctx, "T1", blueprint.territory_id, "territory_started", territoryStart);

            TerritoryStageArtifacts.T1Status previous = TerritoryStageArtifacts
                    .readT1Status(ctx.server, ctx.worldId, blueprint.territory_id)
                    .orElse(null);

            JsonObject bundle = WorldController.buildRegionCandidateBundle(
                    ctx.server,
                    "territory_blueprint",
                    blueprint.territory_id,
                    blueprint.target_continent_id,
                    5
            );
            TerritoryStageArtifacts.writeT1Candidates(ctx.server, ctx.worldId, blueprint.territory_id, bundle);

            TerritoryStageArtifacts.T1Status status = new TerritoryStageArtifacts.T1Status();
            status.territoryId = blueprint.territory_id;
            status.territoryName = blueprint.name;
            status.continentId = blueprint.target_continent_id;
            status.previewGenerated = bundle.has("preview_overlay");
            status.candidatesGenerated = bundle.has("candidates") && bundle.get("candidates").isJsonArray();
            status.candidatesFile = "territory/" + blueprint.territory_id + "/T1/T1_Candidates.json";
            status.previewImage = extractPreviewPath(bundle);
            status.updatedAtEpochMs = now;

            JsonObject selected = resolveSelectedCluster(bundle, previous);
            if (selected != null) {
                status.clusterSelected = true;
                status.selectedClusterId = selected.has("cluster_id") ? selected.get("cluster_id").getAsInt() : null;
                status.selectedClusterLabel = selected.has("label") ? selected.get("label").getAsString() : null;
                status.selectionSource = previous != null && previous.clusterSelected ? previous.selectionSource : "auto_top_ranked";
                status.message = "T1 ready";
            } else {
                status.clusterSelected = false;
                status.selectionSource = "missing_candidate";
                status.message = "No T1 candidates found";
            }

            TerritoryStageArtifacts.writeT1Status(ctx.server, ctx.worldId, status);
            JsonObject territoryDone = new JsonObject();
            territoryDone.addProperty("territory_id", blueprint.territory_id);
            territoryDone.addProperty("continent_id", blueprint.target_continent_id);
            territoryDone.addProperty("preview_generated", status.previewGenerated);
            territoryDone.addProperty("candidates_generated", status.candidatesGenerated);
            territoryDone.addProperty("cluster_selected", status.clusterSelected);
            if (status.selectedClusterId != null) territoryDone.addProperty("selected_cluster_id", status.selectedClusterId);
            if (status.selectedClusterLabel != null) territoryDone.addProperty("selected_cluster_label", status.selectedClusterLabel);
            territoryDone.addProperty("selection_source", status.selectionSource);
            TStageTraceLogger.territory(ctx, "T1", blueprint.territory_id, "territory_completed", territoryDone);
            byContinent.computeIfAbsent(blueprint.target_continent_id, ignored -> new ArrayList<>())
                    .add(indexItem(status.clusterSelected, blueprint.territory_id, blueprint.name, status.message));
        }

        writeIndexes(ctx, "T1", byContinent, now);
        JsonObject stageDone = new JsonObject();
        stageDone.addProperty("continent_count", byContinent.size());
        stageDone.addProperty("territory_count", blueprints.size());
        TStageTraceLogger.stage(ctx, "T1", "stage_completed", stageDone);
    }

    public static void runT2(StageContext ctx) throws Exception {
        List<TerritoryBlueprint> blueprints = requireBlueprints(ctx);
        JsonObject stageStart = new JsonObject();
        stageStart.addProperty("territory_count", blueprints.size());
        TStageTraceLogger.stage(ctx, "T2", "stage_started", stageStart);
        Map<Integer, List<TerritoryStageArtifacts.ContinentIndexItem>> byContinent = new LinkedHashMap<>();
        long now = System.currentTimeMillis();

        for (TerritoryBlueprint blueprint : sortBlueprints(blueprints)) {
            JsonObject territoryStart = new JsonObject();
            territoryStart.addProperty("territory_id", blueprint.territory_id);
            territoryStart.addProperty("territory_name", blueprint.name);
            territoryStart.addProperty("continent_id", blueprint.target_continent_id);
            TStageTraceLogger.territory(ctx, "T2", blueprint.territory_id, "territory_started", territoryStart);
            TerritoryStageArtifacts.T1Status t1 = TerritoryStageArtifacts
                    .readT1Status(ctx.server, ctx.worldId, blueprint.territory_id)
                    .orElse(null);
            TerritoryStageArtifacts.T2Status previous = TerritoryStageArtifacts
                    .readT2Status(ctx.server, ctx.worldId, blueprint.territory_id)
                    .orElse(null);
            Optional<JsonObject> bundleOpt = TerritoryStageArtifacts.readT1Candidates(ctx.server, ctx.worldId, blueprint.territory_id);

            TerritoryStageArtifacts.T2Status status = new TerritoryStageArtifacts.T2Status();
            status.territoryId = blueprint.territory_id;
            status.territoryName = blueprint.name;
            status.continentId = blueprint.target_continent_id;
            status.updatedAtEpochMs = now;
            status.t1ClusterReady = t1 != null && t1.clusterSelected && bundleOpt.isPresent();
            status.selectedClusterId = t1 != null ? t1.selectedClusterId : null;
            status.selectedClusterLabel = t1 != null ? t1.selectedClusterLabel : null;

            if (!status.t1ClusterReady) {
                status.message = "T1 selected cluster missing";
                TerritoryStageArtifacts.writeT2Status(ctx.server, ctx.worldId, status);
                JsonObject blocked = new JsonObject();
                blocked.addProperty("territory_id", blueprint.territory_id);
                blocked.addProperty("continent_id", blueprint.target_continent_id);
                blocked.addProperty("reason", status.message);
                TStageTraceLogger.territory(ctx, "T2", blueprint.territory_id, "territory_blocked", blocked);
                byContinent.computeIfAbsent(blueprint.target_continent_id, ignored -> new ArrayList<>())
                        .add(indexItem(false, blueprint.territory_id, blueprint.name, status.message));
                continue;
            }

            String pointMode = previous != null && previous.pointMode != null && !previous.pointMode.isBlank()
                    ? previous.pointMode
                    : "center";
            JsonObject picked = WorldController.pickPointFromBundle(
                    bundleOpt.get(),
                    status.selectedClusterId,
                    status.selectedClusterLabel,
                    pointMode
            );
            if (picked == null || !picked.has("selected_point") || !picked.get("selected_point").isJsonObject()) {
                status.message = "Failed to resolve T2 point from selected cluster";
                TerritoryStageArtifacts.writeT2Status(ctx.server, ctx.worldId, status);
                JsonObject blocked = new JsonObject();
                blocked.addProperty("territory_id", blueprint.territory_id);
                blocked.addProperty("continent_id", blueprint.target_continent_id);
                blocked.addProperty("reason", status.message);
                TStageTraceLogger.territory(ctx, "T2", blueprint.territory_id, "territory_blocked", blocked);
                byContinent.computeIfAbsent(blueprint.target_continent_id, ignored -> new ArrayList<>())
                        .add(indexItem(false, blueprint.territory_id, blueprint.name, status.message));
                continue;
            }

            JsonObject point = picked.getAsJsonObject("selected_point");
            int x = point.get("x").getAsInt();
            int z = point.get("z").getAsInt();
            int color = parseBlueprintColor(blueprint);
            TerritoryManager.createTerritory(
                    blueprint.territory_id,
                    blueprint.name,
                    blueprint.target_continent_id,
                    x,
                    z,
                    resolvePower(blueprint),
                    resolveMountainCost(blueprint),
                    resolveWaterCost(blueprint),
                    color
            );

            TerritoryManager.TerritoryConfig cfg = TerritoryManager.getTerritoryConfig(blueprint.territory_id);
            if (cfg != null) {
                TerritoryResultRepository.writeT2Capital(ctx.server, cfg);
            }

            status.pointSelected = true;
            status.territoryConfigWritten = cfg != null;
            status.expansionReady = cfg != null;
            status.pointMode = pointMode;
            status.pointSelectionSource = previous != null && previous.pointSelected
                    ? previous.pointSelectionSource
                    : "default_center";
            status.message = cfg != null ? "T2 ready" : "Territory config write failed";
            TerritoryStageArtifacts.SelectedPoint selectedPoint = new TerritoryStageArtifacts.SelectedPoint();
            selectedPoint.x = x;
            selectedPoint.z = z;
            status.selectedPoint = selectedPoint;

            TerritoryStageArtifacts.writeT2Status(ctx.server, ctx.worldId, status);
            JsonObject territoryDone = new JsonObject();
            territoryDone.addProperty("territory_id", blueprint.territory_id);
            territoryDone.addProperty("continent_id", blueprint.target_continent_id);
            territoryDone.addProperty("point_mode", status.pointMode);
            territoryDone.addProperty("selected_x", x);
            territoryDone.addProperty("selected_z", z);
            territoryDone.addProperty("expansion_ready", status.expansionReady);
            TStageTraceLogger.territory(ctx, "T2", blueprint.territory_id, "territory_completed", territoryDone);
            byContinent.computeIfAbsent(blueprint.target_continent_id, ignored -> new ArrayList<>())
                    .add(indexItem(status.expansionReady, blueprint.territory_id, blueprint.name, status.message));
        }

        writeIndexes(ctx, "T2", byContinent, now);
        JsonObject stageDone = new JsonObject();
        stageDone.addProperty("continent_count", byContinent.size());
        stageDone.addProperty("territory_count", blueprints.size());
        TStageTraceLogger.stage(ctx, "T2", "stage_completed", stageDone);
    }

    public static void assertT3Ready(StageContext ctx) throws Exception {
        List<TerritoryBlueprint> blueprints = requireBlueprints(ctx);
        JsonObject stageStart = new JsonObject();
        stageStart.addProperty("territory_count", blueprints.size());
        TStageTraceLogger.stage(ctx, "T3", "gate_check_started", stageStart);
        for (TerritoryBlueprint blueprint : sortBlueprints(blueprints)) {
            TerritoryStageArtifacts.T1Status t1 = TerritoryStageArtifacts
                    .readT1Status(ctx.server, ctx.worldId, blueprint.territory_id)
                    .orElse(null);
            TerritoryStageArtifacts.T2Status t2 = TerritoryStageArtifacts
                    .readT2Status(ctx.server, ctx.worldId, blueprint.territory_id)
                    .orElse(null);
            TerritoryManager.TerritoryConfig cfg = TerritoryManager.getTerritoryConfig(blueprint.territory_id);
            JsonObject territory = new JsonObject();
            territory.addProperty("territory_id", blueprint.territory_id);
            territory.addProperty("continent_id", blueprint.target_continent_id);
            territory.addProperty("t1_ready", t1 != null && t1.clusterSelected);
            territory.addProperty("t2_ready", t2 != null && t2.pointSelected);
            territory.addProperty("territory_config_ready", cfg != null);
            TStageTraceLogger.territory(ctx, "T3", blueprint.territory_id, "gate_checked", territory);
        }
        ReadinessReport report = evaluateReadiness(ctx, blueprints);
        if (!report.ready) {
            JsonObject blocked = new JsonObject();
            blocked.addProperty("message", report.message);
            TStageTraceLogger.stage(ctx, "T3", "gate_blocked", blocked);
            throw new IllegalStateException(report.message);
        }
        JsonObject passed = new JsonObject();
        passed.addProperty("territory_count", blueprints.size());
        TStageTraceLogger.stage(ctx, "T3", "gate_passed", passed);
    }

    static ReadinessReport evaluateReadiness(StageContext ctx, List<TerritoryBlueprint> blueprints) {
        List<GateRow> rows = new ArrayList<>();
        for (TerritoryBlueprint blueprint : sortBlueprints(blueprints)) {
            TerritoryStageArtifacts.T1Status t1 = TerritoryStageArtifacts
                    .readT1Status(ctx.server, ctx.worldId, blueprint.territory_id)
                    .orElse(null);
            TerritoryStageArtifacts.T2Status t2 = TerritoryStageArtifacts
                    .readT2Status(ctx.server, ctx.worldId, blueprint.territory_id)
                    .orElse(null);
            TerritoryManager.TerritoryConfig cfg = TerritoryManager.getTerritoryConfig(blueprint.territory_id);
            rows.add(new GateRow(
                    blueprint.target_continent_id,
                    blueprint.territory_id,
                    t1 != null && t1.clusterSelected,
                    t2 != null && t2.pointSelected,
                    cfg != null
            ));
        }
        return evaluateReadiness(rows);
    }

    static ReadinessReport evaluateReadiness(List<GateRow> rows) {
        Map<Integer, List<String>> missingByContinent = new LinkedHashMap<>();
        for (GateRow row : rows) {
            List<String> missing = new ArrayList<>();
            if (!row.t1Ready) missing.add("T1");
            if (!row.t2Ready) missing.add("T2");
            if (!row.territoryConfigReady) missing.add("territory_config");
            if (!missing.isEmpty()) {
                missingByContinent.computeIfAbsent(row.continentId, ignored -> new ArrayList<>())
                        .add(row.territoryId + " missing " + String.join("/", missing));
            }
        }

        if (missingByContinent.isEmpty()) {
            return new ReadinessReport(true, "ready");
        }

        StringBuilder sb = new StringBuilder("T3 blocked: incomplete T1/T2 prerequisites");
        for (Map.Entry<Integer, List<String>> entry : missingByContinent.entrySet()) {
            sb.append(" | region_id=").append(entry.getKey()).append(": ");
            int limit = Math.min(4, entry.getValue().size());
            for (int i = 0; i < limit; i++) {
                if (i > 0) sb.append(", ");
                sb.append(entry.getValue().get(i));
            }
            if (entry.getValue().size() > limit) {
                sb.append(", ...");
            }
        }
        return new ReadinessReport(false, sb.toString());
    }

    private static TerritoryStageArtifacts.ContinentIndexItem indexItem(
            boolean completed,
            String territoryId,
            String territoryName,
            String message
    ) {
        TerritoryStageArtifacts.ContinentIndexItem item = new TerritoryStageArtifacts.ContinentIndexItem();
        item.completed = completed;
        item.territoryId = territoryId;
        item.territoryName = territoryName;
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
            }
            index.pendingCount = Math.max(0, index.territoryCount - index.completedCount);
            TerritoryStageArtifacts.writeContinentIndex(ctx.server, ctx.worldId, entry.getKey(), stageId, index);
            JsonObject continent = new JsonObject();
            continent.addProperty("continent_id", entry.getKey());
            continent.addProperty("territory_count", index.territoryCount);
            continent.addProperty("completed_count", index.completedCount);
            continent.addProperty("pending_count", index.pendingCount);
            TStageTraceLogger.continent(ctx, stageId, entry.getKey(), "index_written", continent);
        }
    }

    private static JsonObject resolveSelectedCluster(JsonObject bundle, TerritoryStageArtifacts.T1Status previous) {
        if (bundle == null || !bundle.has("candidates") || !bundle.get("candidates").isJsonArray()) return null;
        JsonArray candidates = bundle.getAsJsonArray("candidates");
        if (previous != null && previous.clusterSelected) {
            for (JsonElement element : candidates) {
                if (!element.isJsonObject()) continue;
                JsonObject candidate = element.getAsJsonObject();
                Integer clusterId = candidate.has("cluster_id") ? candidate.get("cluster_id").getAsInt() : null;
                String label = candidate.has("label") ? candidate.get("label").getAsString() : null;
                if (previous.selectedClusterId != null && previous.selectedClusterId.equals(clusterId)) return candidate;
                if (previous.selectedClusterLabel != null && previous.selectedClusterLabel.equalsIgnoreCase(label)) return candidate;
            }
        }
        for (JsonElement element : candidates) {
            if (element.isJsonObject()) return element.getAsJsonObject();
        }
        return null;
    }

    private static String extractPreviewPath(JsonObject bundle) {
        if (bundle == null || !bundle.has("preview_overlay") || !bundle.get("preview_overlay").isJsonObject()) return null;
        JsonObject preview = bundle.getAsJsonObject("preview_overlay");
        if (preview.has("preview_image")) return preview.get("preview_image").getAsString();
        return null;
    }

    private static List<TerritoryBlueprint> requireBlueprints(StageContext ctx) {
        TerritoryRepository.Result result = TerritoryRepository.listBlueprints(ctx.server);
        if (!result.ok || result.list == null || result.list.isEmpty()) {
            throw new IllegalStateException("No T1 blueprints available");
        }
        return result.list;
    }

    private static List<TerritoryBlueprint> sortBlueprints(List<TerritoryBlueprint> source) {
        List<TerritoryBlueprint> copy = new ArrayList<>(source);
        copy.sort(Comparator
                .comparingInt((TerritoryBlueprint bp) -> bp.target_continent_id)
                .thenComparing(bp -> bp.territory_id == null ? "" : bp.territory_id.toLowerCase(Locale.ROOT)));
        return copy;
    }

    private static int resolvePower(TerritoryBlueprint blueprint) {
        if (blueprint != null && blueprint.expansion_policy != null && blueprint.expansion_policy.base_power > 0) {
            return blueprint.expansion_policy.base_power;
        }
        return 100;
    }

    private static double resolveMountainCost(TerritoryBlueprint blueprint) {
        if (blueprint != null
                && blueprint.expansion_policy != null
                && blueprint.expansion_policy.costs != null
                && blueprint.expansion_policy.costs.slope_penalty > 0) {
            return blueprint.expansion_policy.costs.slope_penalty;
        }
        return 2.0;
    }

    private static double resolveWaterCost(TerritoryBlueprint blueprint) {
        if (blueprint != null
                && blueprint.expansion_policy != null
                && blueprint.expansion_policy.costs != null
                && blueprint.expansion_policy.costs.water_penalty > 0) {
            return blueprint.expansion_policy.costs.water_penalty;
        }
        return 5.0;
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

        GateRow(int continentId, String territoryId, boolean t1Ready, boolean t2Ready, boolean territoryConfigReady) {
            this.continentId = continentId;
            this.territoryId = territoryId;
            this.t1Ready = t1Ready;
            this.t2Ready = t2Ready;
            this.territoryConfigReady = territoryConfigReady;
        }
    }
}
