package com.user.terra_script.server.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.user.terra_script.core.artifact.ArtifactStore;
import com.user.terra_script.core.stage.StageContext;
import com.user.terra_script.core.workflow.FileStageStatusStore;
import com.user.terra_script.domain.territory.stage.TerritoryStageOrchestrator;
import com.user.terra_script.server.http.HttpUtil;
import com.user.terra_script.territory.io.TerritoryRepository;
import com.user.terra_script.territory.io.TerritoryResultRepository;
import com.user.terra_script.territory.model.TerritoryBlueprint;
import com.user.terra_script.world.TerritoryManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class TerritoryController {
    private static final Gson GSON = new Gson();

    public void handleT1Blueprint(HttpExchange exchange, MinecraftServer server) throws IOException {
        if ("POST".equals(exchange.getRequestMethod())) {
            try {
                String body = HttpUtil.readBody(exchange);
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                var result = TerritoryRepository.saveBlueprint(server, json);
                JsonObject res = new JsonObject();
                res.addProperty("step", "T1");
                res.addProperty("ok", result.ok);
                res.addProperty("message", result.message);
                if (result.blueprint != null) res.add("blueprint", GSON.toJsonTree(result.blueprint));
                HttpUtil.sendResponse(exchange, result.ok ? 200 : 400, GSON.toJson(res));
            } catch (Exception e) {
                HttpUtil.sendResponse(exchange, 500, "{\"error\": \"" + e.getMessage() + "\"}");
            }
        } else if ("GET".equals(exchange.getRequestMethod())) {
            try {
                var result = TerritoryRepository.listBlueprints(server);
                JsonObject res = new JsonObject();
                res.addProperty("step", "T1");
                res.addProperty("ok", result.ok);
                res.addProperty("message", result.message);
                if (result.list != null) res.add("blueprints", GSON.toJsonTree(result.list));
                HttpUtil.sendResponse(exchange, result.ok ? 200 : 500, GSON.toJson(res));
            } catch (Exception e) {
                HttpUtil.sendResponse(exchange, 500, "{\"error\": \"" + e.getMessage() + "\"}");
            }
        } else {
            HttpUtil.sendResponse(exchange, 405, "{\"error\": \"Only GET/POST\"}");
        }
    }

    public void handleCreateTerritory(HttpExchange exchange, MinecraftServer server) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();

            String id = json.get("id").getAsString();
            String name = json.get("name").getAsString();

            if (!json.has("region_id")) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing required parameter: region_id\"}");
                return;
            }

            int regionId = json.has("region_id") ? json.get("region_id").getAsInt() : 1;

            int x = json.get("capital_x").getAsInt();
            int z = json.get("capital_z").getAsInt();
            int power = json.has("power") ? json.get("power").getAsInt() : 100;
            double mCost = json.has("mountain_cost") ? json.get("mountain_cost").getAsDouble() : 2.0;
            double wCost = json.has("water_cost") ? json.get("water_cost").getAsDouble() : 5.0;
            int color = json.has("color") ? json.get("color").getAsInt() : 0xFF0000;

            com.user.terra_script.world.TerritoryManager.createTerritory(
                    id, name, regionId, x, z, power, mCost, wCost, color
            );

            var cfg = com.user.terra_script.world.TerritoryManager.getTerritoryConfig(id);
            if (cfg != null) {
                TerritoryResultRepository.writeT2Capital(server, cfg);
            }

            JsonObject res = new JsonObject();
            res.addProperty("status", "created");
            res.addProperty("step", "T2");
            res.addProperty("territory_id", id);
            res.addProperty("artifacts_exported", cfg != null);
            HttpUtil.sendResponse(exchange, 200, GSON.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleT1CandidatesForContinent(HttpExchange exchange, MinecraftServer server) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            JsonObject req = JsonParser.parseString(HttpUtil.readBody(exchange)).getAsJsonObject();
            int continentId = req.has("continent_id") ? req.get("continent_id").getAsInt() : -1;
            if (continentId <= 0) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\":\"continent_id is required\"}");
                return;
            }
            JsonObject res = TerritoryStageOrchestrator.getT1CandidatesForContinent(buildStageContext(server), continentId);
            HttpUtil.sendResponse(exchange, 200, GSON.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleT1SelectCluster(HttpExchange exchange, MinecraftServer server) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            JsonObject req = JsonParser.parseString(HttpUtil.readBody(exchange)).getAsJsonObject();
            String territoryId = req.has("territory_id") ? req.get("territory_id").getAsString() : null;
            int continentId = req.has("continent_id") ? req.get("continent_id").getAsInt() : -1;
            Integer clusterId = req.has("cluster_id") ? req.get("cluster_id").getAsInt() : null;
            String label = req.has("label") ? req.get("label").getAsString() : null;
            if (territoryId == null || territoryId.isBlank() || continentId <= 0 || (clusterId == null && (label == null || label.isBlank()))) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\":\"territory_id, continent_id, and cluster_id or label are required\"}");
                return;
            }
            var status = TerritoryStageOrchestrator.selectT1Cluster(buildStageContext(server), territoryId, continentId, clusterId, label);
            HttpUtil.sendResponse(exchange, 200, GSON.toJson(status));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleT2DirectionCandidates(HttpExchange exchange, MinecraftServer server) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            JsonObject req = JsonParser.parseString(HttpUtil.readBody(exchange)).getAsJsonObject();
            String territoryId = req.has("territory_id") ? req.get("territory_id").getAsString() : null;
            int continentId = req.has("continent_id") ? req.get("continent_id").getAsInt() : -1;
            if (territoryId == null || territoryId.isBlank() || continentId <= 0) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\":\"territory_id and continent_id are required\"}");
                return;
            }
            JsonObject res = TerritoryStageOrchestrator.getT2DirectionCandidates(buildStageContext(server), territoryId, continentId);
            HttpUtil.sendResponse(exchange, 200, GSON.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleT2SelectDirection(HttpExchange exchange, MinecraftServer server) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            JsonObject req = JsonParser.parseString(HttpUtil.readBody(exchange)).getAsJsonObject();
            String territoryId = req.has("territory_id") ? req.get("territory_id").getAsString() : null;
            int continentId = req.has("continent_id") ? req.get("continent_id").getAsInt() : -1;
            String direction = req.has("direction") ? req.get("direction").getAsString() : null;
            if (territoryId == null || territoryId.isBlank() || continentId <= 0 || direction == null || direction.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\":\"territory_id, continent_id, and direction are required\"}");
                return;
            }
            var status = TerritoryStageOrchestrator.selectT2Direction(buildStageContext(server), territoryId, continentId, direction);
            HttpUtil.sendResponse(exchange, 200, GSON.toJson(status));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleT3RunContinent(HttpExchange exchange, MinecraftServer server) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            JsonObject req = JsonParser.parseString(HttpUtil.readBody(exchange)).getAsJsonObject();
            int continentId = req.has("continent_id") ? req.get("continent_id").getAsInt() : -1;
            if (continentId <= 0) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\":\"continent_id is required\"}");
                return;
            }
            var results = TerritoryStageOrchestrator.runT3ForContinent(buildStageContext(server), continentId);
            T3RunContinentResponse response = buildT3RunContinentResponse(continentId, results);
            if (response.statusCode == 409) {
                JsonArray preserved = new JsonArray();
                if (results != null) {
                    for (TerritoryManager.TerritoryResult result : results) {
                        if (result == null || result.config == null || result.config.id == null) continue;
                        int claimed = result.claimedChunks != null ? result.claimedChunks.size() : 0;
                        int wild = result.wildChunks != null ? result.wildChunks.size() : 0;
                        if (claimed + wild > 0) continue;
                        if (!TerritoryResultRepository.hasNonZeroT3(server, result.config.id)) continue;
                        JsonObject kept = new JsonObject();
                        kept.addProperty("territory_instance_id", result.config.id);
                        kept.addProperty("territory_id", result.config.territoryId);
                        kept.addProperty("canonical_overwrite_skipped", true);
                        kept.addProperty("reason", "zero_area_result_preserved_existing_nonzero_t3");
                        preserved.add(kept);
                    }
                }
                if (preserved.size() > 0) {
                    response.body.add("preserved_existing_t3", preserved);
                }
            }
            HttpUtil.sendResponse(exchange, response.statusCode, GSON.toJson(response.body));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleTerritoryT3Import(HttpExchange exchange, MinecraftServer server) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            JsonObject req = JsonParser.parseString(HttpUtil.readBody(exchange)).getAsJsonObject();
            String sourceTerritoryId = req.has("source_territory_id") ? req.get("source_territory_id").getAsString() : null;
            String targetTerritoryId = req.has("target_territory_id") ? req.get("target_territory_id").getAsString() : null;
            boolean dryRun = req.has("dry_run") && req.get("dry_run").getAsBoolean();

            if (sourceTerritoryId == null || sourceTerritoryId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\":\"source_territory_id is required\"}");
                return;
            }
            if (targetTerritoryId == null || targetTerritoryId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\":\"target_territory_id is required\"}");
                return;
            }

            TerritoryManager.ensureLoaded();
            TerritoryManager.TerritoryConfig targetConfig = TerritoryManager.getTerritoryConfig(targetTerritoryId);
            TerritoryResultRepository.T3ImportResult result =
                    TerritoryResultRepository.importT3(server, sourceTerritoryId, targetConfig, dryRun);

            JsonObject res = new JsonObject();
            res.addProperty("step", "T3_IMPORT");
            res.addProperty("ok", result.ok);
            res.addProperty("dry_run", result.dryRun);
            res.addProperty("message", result.message);
            res.addProperty("source_territory_id", result.sourceTerritoryId);
            res.addProperty("target_territory_id", result.targetTerritoryId);
            res.addProperty("claimed_chunks", result.claimedChunks);
            res.addProperty("wild_chunks", result.wildChunks);
            res.addProperty("canonical_written", result.canonicalWritten);
            if (result.importedResult != null) {
                res.add("summary", TerritoryResultRepository.buildSummary(result.importedResult));
            }
            if (result.ok && !result.dryRun && result.importedResult != null) {
                TerritoryManager.applyStoredResult(result.importedResult);
                res.addProperty("runtime_restored", true);
            } else {
                res.addProperty("runtime_restored", false);
            }

            int status = result.ok ? 200 : 409;
            if (!result.ok && "target territory config not found".equals(result.message)) {
                status = 404;
            }
            HttpUtil.sendResponse(exchange, status, GSON.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    static T3RunContinentResponse buildT3RunContinentResponse(
            int continentId,
            Collection<TerritoryManager.TerritoryResult> results
    ) {
        JsonObject body = new JsonObject();
        JsonArray territories = new JsonArray();
        long claimedTotal = 0L;
        long wildTotal = 0L;

        List<TerritoryManager.TerritoryResult> ordered = new ArrayList<>();
        if (results != null) {
            for (TerritoryManager.TerritoryResult result : results) {
                if (result != null) ordered.add(result);
            }
        }
        ordered.sort(Comparator.comparing(TerritoryController::territorySortKey));

        for (TerritoryManager.TerritoryResult result : ordered) {
            int claimedChunks = result.claimedChunks != null ? result.claimedChunks.size() : 0;
            int wildChunks = result.wildChunks != null ? result.wildChunks.size() : 0;
            claimedTotal += claimedChunks;
            wildTotal += wildChunks;

            JsonObject territory = new JsonObject();
            if (result.config != null) {
                territory.addProperty("territory_id", result.config.territoryId);
                territory.addProperty("territory_instance_id", result.config.id);
                territory.addProperty("continent_id", result.config.selectedContinentId);
            }
            territory.addProperty("claimed_chunks", claimedChunks);
            territory.addProperty("wild_chunks", wildChunks);
            territories.add(territory);
        }

        body.addProperty("continent_id", continentId);
        body.addProperty("exported_count", ordered.size());
        body.addProperty("claimed_total", claimedTotal);
        body.addProperty("wild_total", wildTotal);
        body.add("territories", territories);

        if (claimedTotal + wildTotal == 0L) {
            body.addProperty("error", "T3 produced zero claimed chunks for continent " + continentId);
            body.addProperty(
                    "hint",
                    "Likely causes: missing/invalid W3-W4 scan cache, missing cluster map, " +
                            "or territory region_id does not match current W3 continent ids."
            );
            return new T3RunContinentResponse(409, body);
        }
        return new T3RunContinentResponse(200, body);
    }

    private static String territorySortKey(TerritoryManager.TerritoryResult result) {
        if (result == null || result.config == null || result.config.id == null) return "";
        return result.config.id;
    }

    static final class T3RunContinentResponse {
        final int statusCode;
        final JsonObject body;

        T3RunContinentResponse(int statusCode, JsonObject body) {
            this.statusCode = statusCode;
            this.body = body;
        }
    }

    public void handleTerritoryStatus(HttpExchange exchange, MinecraftServer server) throws IOException {
        try {
            com.user.terra_script.world.TerritoryManager.ensureLoaded();
            TerritoryManager.restoreT3ResultsFromDisk(server);
            JsonObject root = new JsonObject();
            var allResults = com.user.terra_script.world.TerritoryManager.getAllResults();

            if (allResults == null || allResults.isEmpty()) {
                for (var cfg : com.user.terra_script.world.TerritoryManager.getRegisteredFactions()) {
                    JsonObject tObj = new JsonObject();
                    tObj.addProperty("id", cfg.id);
                    tObj.addProperty("territory_id", cfg.territoryId);
                    tObj.addProperty("name", cfg.name);
                    JsonObject cap = new JsonObject();
                    cap.addProperty("x", cfg.capitalX);
                    cap.addProperty("z", cfg.capitalZ);
                    tObj.add("capital", cap);
                    tObj.addProperty("region_id", cfg.regionId);
                    tObj.addProperty("continent_id", cfg.selectedContinentId);
                    tObj.addProperty("base_power", cfg.maxPower);
                    tObj.addProperty("land_power", cfg.landPower);
                    tObj.addProperty("expansion_executed", false);
                    root.add(cfg.id, tObj);
                }
                HttpUtil.sendResponse(exchange, 200, GSON.toJson(root));
                return;
            }

            for (var res : allResults) {
                JsonObject tObj = new JsonObject();
                tObj.addProperty("id", res.config.id);
                tObj.addProperty("territory_id", res.config.territoryId);
                tObj.addProperty("name", res.config.name);

                JsonObject cap = new JsonObject();
                cap.addProperty("x", res.config.capitalX);
                cap.addProperty("z", res.config.capitalZ);
                tObj.add("capital", cap);

                tObj.addProperty("continent_id", res.config.selectedContinentId);
                tObj.addProperty("base_power", res.config.maxPower);
                tObj.addProperty("land_power", res.config.landPower);

                if (res.stats != null) {
                    tObj.addProperty("total_area", res.stats.area_pixels);

                    JsonObject bbox = new JsonObject();
                    bbox.addProperty("min_x", res.stats.minX);
                    bbox.addProperty("max_x", res.stats.maxX);
                    bbox.addProperty("min_z", res.stats.minZ);
                    bbox.addProperty("max_z", res.stats.maxZ);
                    tObj.add("bounds", bbox);

                    JsonObject cont = new JsonObject();
                    res.stats.continent_distribution.forEach((rid, pct) -> cont.addProperty(String.valueOf(rid), pct));
                    tObj.add("continent_distribution", cont);

                    JsonObject neighbors = new JsonObject();
                    for (String nid : res.stats.neighborIds) {
                        var neighborRes = com.user.terra_script.world.TerritoryManager.getAllResults().stream()
                                .filter(r -> r.config.id.equals(nid)).findFirst().orElse(null);

                        String dir = "Unknown";
                        if (neighborRes != null) {
                            double dx = neighborRes.config.capitalX - res.config.capitalX;
                            double dz = neighborRes.config.capitalZ - res.config.capitalZ;

                            if (Math.abs(dx) > Math.abs(dz)) dir = dx > 0 ? "East" : "West";
                            else dir = dz > 0 ? "South" : "North";
                        }
                        neighbors.addProperty(nid, dir);
                    }
                    tObj.add("neighbors", neighbors);

                    JsonObject biomes = new JsonObject();
                    res.stats.biome_composition.forEach(biomes::addProperty);
                    tObj.add("biomes", biomes);

                    JsonObject competition = new JsonObject();
                    competition.addProperty("land_power_initial", res.stats.land_power_initial);
                    competition.addProperty("land_power_remaining", res.stats.land_power_remaining);
                    competition.addProperty("land_power_spent", res.stats.land_power_spent);
                    competition.addProperty("conflict_cells", res.stats.conflict_cells);
                    tObj.add("competition", competition);
                }
                root.add(res.config.id, tObj);
            }
            HttpUtil.sendResponse(exchange, 200, GSON.toJson(root));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleTerritorySummary(HttpExchange exchange, MinecraftServer server) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "GET")) return;
        try {
            TerritoryManager.restoreT3ResultsFromDisk(server);
            String territoryId = getQueryParam(exchange, "territoryId");
            String continentIdRaw = getQueryParam(exchange, "continentId");
            if (territoryId == null || territoryId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"territoryId query parameter is required\"}");
                return;
            }

            String lookupId = resolveSummaryLookupId(territoryId, continentIdRaw);
            Optional<JsonObject> stored = TerritoryResultRepository.readSummary(server, lookupId);
            if (stored.isEmpty()) {
                var live = com.user.terra_script.world.TerritoryManager.getAllResults().stream()
                        .filter(r -> r != null && r.config != null && lookupId.equals(r.config.id))
                        .findFirst();
                if (live.isPresent()) {
                    JsonObject summary = TerritoryResultRepository.buildSummary(live.get());
                    JsonObject res = new JsonObject();
                    String stage = summary.has("stage") ? summary.get("stage").getAsString() : "T3";
                    res.addProperty("step", stage);
                    res.addProperty("ok", true);
                    res.addProperty("source", "memory");
                    res.add("summary", summary);
                    HttpUtil.sendResponse(exchange, 200, GSON.toJson(res));
                    return;
                }
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"T2 summary not found for territoryId: " + territoryId + "\"}");
                return;
            }

            JsonObject res = new JsonObject();
            String stage = stored.get().has("stage") ? stored.get().get("stage").getAsString() : "T2";
            res.addProperty("step", stage);
            res.addProperty("ok", true);
            res.addProperty("source", "artifact");
            res.add("summary", stored.get());
            HttpUtil.sendResponse(exchange, 200, GSON.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleTerritoryT4Window(HttpExchange exchange, MinecraftServer server) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            TerritoryManager.restoreT3ResultsFromDisk(server);
            String body = HttpUtil.readBody(exchange);
            JsonObject req = JsonParser.parseString(body).getAsJsonObject();

            String territoryId = req.has("territory_id") ? req.get("territory_id").getAsString() : null;
            if (territoryId == null || territoryId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"territory_id is required\"}");
                return;
            }

            boolean explicitCenter = req.has("center_x") && req.has("center_z");
            int centerX;
            int centerZ;
            if (explicitCenter) {
                centerX = req.get("center_x").getAsInt();
                centerZ = req.get("center_z").getAsInt();
            } else {
                var live = com.user.terra_script.world.TerritoryManager.getAllResults().stream()
                        .filter(r -> r != null && r.config != null && territoryId.equals(r.config.id))
                        .findFirst();
                if (live.isEmpty()) {
                    HttpUtil.sendResponse(exchange, 400, "{\"error\": \"center_x/center_z missing and territory not found in memory\"}");
                    return;
                }
                centerX = live.get().config.capitalX;
                centerZ = live.get().config.capitalZ;
            }

            int radiusBlocks = req.has("radius_blocks") ? req.get("radius_blocks").getAsInt() : 256;
            int maxPoints = req.has("max_points") ? req.get("max_points").getAsInt() : 160;
            int maxBiomeSamples = req.has("max_biome_samples") ? req.get("max_biome_samples").getAsInt() : 3000;
            radiusBlocks = Math.max(16, Math.min(4096, radiusBlocks));
            maxPoints = Math.max(0, Math.min(2000, maxPoints));
            maxBiomeSamples = Math.max(0, Math.min(20000, maxBiomeSamples));

            Optional<byte[]> datOpt = TerritoryResultRepository.readT4Dat(server, territoryId);
            if (datOpt.isEmpty()) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"T4 dat not found for territory_id: " + territoryId + "\"}");
                return;
            }

            DecodedT4 decoded = decodeT4Dat(datOpt.get());
            WindowSelection selection = selectWindow(decoded.records, centerX, centerZ, radiusBlocks, !explicitCenter);
            List<CellRecord> inWindow = selection.records;

            JsonObject res = new JsonObject();
            res.addProperty("step", "T4");
            res.addProperty("ok", true);
            res.addProperty("source", "artifact_t4_dat");
            res.addProperty("territory_id", territoryId);
            if (selection.reanchored) {
                JsonArray warnings = new JsonArray();
                warnings.add("requested_center_outside_t4_coverage");
                warnings.add("center_reanchored_to_nearest_t4_cell");
                res.add("warnings", warnings);
            }

            JsonObject window = new JsonObject();
            window.addProperty("center_x", selection.centerX);
            window.addProperty("center_z", selection.centerZ);
            window.addProperty("radius_blocks", radiusBlocks);
            window.addProperty("sample_step", decoded.step);
            window.addProperty("matched_cells", inWindow.size());
            window.addProperty("estimated_block_count", (long) inWindow.size() * decoded.step * decoded.step);
            window.addProperty("reanchored", selection.reanchored);
            if (selection.reanchored) {
                window.addProperty("requested_center_x", selection.requestedCenterX);
                window.addProperty("requested_center_z", selection.requestedCenterZ);
                window.addProperty("nearest_distance_blocks", round3(selection.nearestDistanceBlocks));
            }
            res.add("window", window);

            JsonObject terrain = buildWindowTerrain(inWindow);
            res.add("terrain", terrain);

            JsonObject biomeComp = buildWindowBiomeComposition(server, inWindow, maxBiomeSamples);
            res.add("biome_composition_window", biomeComp);

            if (maxPoints > 0) {
                JsonArray samplePoints = new JsonArray();
                inWindow.stream()
                        .sorted(Comparator.comparingInt((CellRecord c) -> c.strategic).reversed())
                        .limit(maxPoints)
                        .forEach(c -> {
                            JsonObject p = new JsonObject();
                            p.addProperty("x", c.x);
                            p.addProperty("z", c.z);
                            p.addProperty("height", c.height);
                            p.addProperty("slope", c.slope);
                            p.addProperty("temperature", c.temperature);
                            p.addProperty("strategic", c.strategic);
                            samplePoints.add(p);
                        });
                res.add("samples", samplePoints);
            }

            HttpUtil.sendResponse(exchange, 200, GSON.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    private static String getQueryParam(HttpExchange exchange, String key) {
        String raw = exchange.getRequestURI() != null ? exchange.getRequestURI().getQuery() : null;
        if (raw == null || raw.isBlank()) return null;
        String[] pairs = raw.split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 0) continue;
            if (key.equals(kv[0])) {
                return kv.length > 1 ? kv[1] : "";
            }
        }
        return null;
    }

    private static StageContext buildStageContext(MinecraftServer server) {
        ArtifactStore artifacts = new ArtifactStore();
        FileStageStatusStore statusStore = new FileStageStatusStore(artifacts);
        return StageContext.forServer(server, artifacts, statusStore);
    }

    private static String resolveSummaryLookupId(String territoryId, String continentIdRaw) {
        if (continentIdRaw != null && !continentIdRaw.isBlank()) {
            try {
                int continentId = Integer.parseInt(continentIdRaw);
                if (continentId > 0) return TerritoryBlueprint.instanceId(territoryId, continentId);
            } catch (Exception ignored) {
            }
        }
        for (var cfg : com.user.terra_script.world.TerritoryManager.getRegisteredFactions()) {
            if (cfg != null && territoryId.equals(cfg.territoryId)) {
                return cfg.id;
            }
        }
        return territoryId;
    }

    private static JsonObject buildWindowTerrain(List<CellRecord> records) {
        JsonObject terrain = new JsonObject();
        if (records == null || records.isEmpty()) {
            terrain.addProperty("empty", true);
            return terrain;
        }
        int minH = Integer.MAX_VALUE, maxH = Integer.MIN_VALUE;
        double sumH = 0.0, sumS = 0.0, sumT = 0.0;
        for (CellRecord r : records) {
            if (r.height < minH) minH = r.height;
            if (r.height > maxH) maxH = r.height;
            sumH += r.height;
            sumS += r.slope;
            sumT += r.temperature;
        }
        int n = records.size();
        terrain.addProperty("avg_height", round3(sumH / n));
        terrain.addProperty("avg_slope", round3(sumS / n));
        terrain.addProperty("avg_temperature", round3(sumT / n));
        terrain.addProperty("min_height", minH);
        terrain.addProperty("max_height", maxH);
        terrain.addProperty("relief", maxH - minH);
        return terrain;
    }

    private static JsonObject buildWindowBiomeComposition(MinecraftServer server, List<CellRecord> records, int maxSamples) {
        JsonObject out = new JsonObject();
        if (server == null || records == null || records.isEmpty() || maxSamples <= 0) return out;
        ServerLevel level = server.overworld();
        if (level == null) return out;

        int sampleCount = Math.min(records.size(), maxSamples);
        int stride = Math.max(1, records.size() / sampleCount);
        Map<String, Integer> counts = new HashMap<>();
        int used = 0;
        for (int i = 0; i < records.size() && used < sampleCount; i += stride) {
            CellRecord r = records.get(i);
            BlockPos pos = new BlockPos(r.x, r.height, r.z);
            String biomeId = level.getBiome(pos).unwrapKey()
                    .map(k -> k.location().toString())
                    .orElse("unknown");
            counts.merge(biomeId, 1, Integer::sum);
            used++;
        }
        if (used <= 0) return out;

        final int usedSamples = used;
        counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(8)
                .forEach(e -> out.addProperty(e.getKey(), round3((double) e.getValue() / usedSamples)));
        return out;
    }

    private static DecodedT4 decodeT4Dat(byte[] bytes) throws Exception {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int version = in.readInt();
            String territoryId = in.readUTF();
            int step = in.readInt();
            int count = in.readInt();
            List<CellRecord> records = new ArrayList<>(Math.max(0, count));
            for (int i = 0; i < count; i++) {
                int x = in.readInt();
                int z = in.readInt();
                int h = in.readShort();
                float slope = in.readFloat();
                float temperature = in.readFloat();
                int distBorder = in.readShort();
                int distCapital = in.readShort();
                int strategic = in.readUnsignedByte();
                records.add(new CellRecord(x, z, h, slope, temperature, distBorder, distCapital, strategic));
            }
            return new DecodedT4(version, territoryId, step, records);
        }
    }

    static WindowSelection selectWindow(
            List<CellRecord> records,
            int requestedCenterX,
            int requestedCenterZ,
            int radiusBlocks,
            boolean allowReanchor
    ) {
        List<CellRecord> initial = filterWindowRecords(records, requestedCenterX, requestedCenterZ, radiusBlocks);
        if (!initial.isEmpty() || !allowReanchor || records == null || records.isEmpty()) {
            return new WindowSelection(requestedCenterX, requestedCenterZ, requestedCenterX, requestedCenterZ, false, 0.0, initial);
        }
        CellRecord nearest = findNearestRecord(records, requestedCenterX, requestedCenterZ);
        if (nearest == null) {
            return new WindowSelection(requestedCenterX, requestedCenterZ, requestedCenterX, requestedCenterZ, false, 0.0, initial);
        }
        List<CellRecord> reanchored = filterWindowRecords(records, nearest.x, nearest.z, radiusBlocks);
        double distance = Math.sqrt(distanceSq(requestedCenterX, requestedCenterZ, nearest.x, nearest.z));
        return new WindowSelection(
                requestedCenterX,
                requestedCenterZ,
                nearest.x,
                nearest.z,
                true,
                distance,
                reanchored
        );
    }

    private static List<CellRecord> filterWindowRecords(List<CellRecord> records, int centerX, int centerZ, int radiusBlocks) {
        List<CellRecord> inWindow = new ArrayList<>();
        if (records == null || records.isEmpty()) return inWindow;
        int minX = centerX - radiusBlocks;
        int maxX = centerX + radiusBlocks;
        int minZ = centerZ - radiusBlocks;
        int maxZ = centerZ + radiusBlocks;
        for (CellRecord r : records) {
            if (r.x >= minX && r.x <= maxX && r.z >= minZ && r.z <= maxZ) {
                inWindow.add(r);
            }
        }
        return inWindow;
    }

    private static CellRecord findNearestRecord(List<CellRecord> records, int centerX, int centerZ) {
        if (records == null || records.isEmpty()) return null;
        CellRecord best = null;
        long bestDistance = Long.MAX_VALUE;
        for (CellRecord r : records) {
            long d = distanceSq(centerX, centerZ, r.x, r.z);
            if (d < bestDistance) {
                bestDistance = d;
                best = r;
            }
        }
        return best;
    }

    private static long distanceSq(int x1, int z1, int x2, int z2) {
        long dx = (long) x2 - x1;
        long dz = (long) z2 - z1;
        return dx * dx + dz * dz;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static final class DecodedT4 {
        final int version;
        final String territoryId;
        final int step;
        final List<CellRecord> records;

        DecodedT4(int version, String territoryId, int step, List<CellRecord> records) {
            this.version = version;
            this.territoryId = territoryId;
            this.step = step;
            this.records = records;
        }
    }

    static final class WindowSelection {
        final int requestedCenterX;
        final int requestedCenterZ;
        final int centerX;
        final int centerZ;
        final boolean reanchored;
        final double nearestDistanceBlocks;
        final List<CellRecord> records;

        WindowSelection(
                int requestedCenterX,
                int requestedCenterZ,
                int centerX,
                int centerZ,
                boolean reanchored,
                double nearestDistanceBlocks,
                List<CellRecord> records
        ) {
            this.requestedCenterX = requestedCenterX;
            this.requestedCenterZ = requestedCenterZ;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.reanchored = reanchored;
            this.nearestDistanceBlocks = nearestDistanceBlocks;
            this.records = records;
        }
    }

    static final class CellRecord {
        final int x;
        final int z;
        final int height;
        final float slope;
        final float temperature;
        final int distBorder;
        final int distCapital;
        final int strategic;

        CellRecord(int x, int z, int height, float slope, float temperature, int distBorder, int distCapital, int strategic) {
            this.x = x;
            this.z = z;
            this.height = height;
            this.slope = slope;
            this.temperature = temperature;
            this.distBorder = distBorder;
            this.distCapital = distCapital;
            this.strategic = strategic;
        }
    }
}
