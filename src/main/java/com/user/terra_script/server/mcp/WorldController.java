package com.user.terra_script.server.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.user.terra_script.client.data.ScanResultHolder;
import com.user.terra_script.client.data.ScanResultHolder.RegionCache;
import com.user.terra_script.domain.world.scan.ScanPixel;
import com.user.terra_script.domain.world.scan.ScanRegion;
import com.user.terra_script.domain.world.stage.QueryRegionPreviewExporter;
import com.user.terra_script.domain.world.stage.T1PreviewExporter;
import com.user.terra_script.server.http.HttpUtil;
import com.user.terra_script.util.AsciiMapGenerator;
import com.user.terra_script.util.DBSCAN;
import com.user.terra_script.util.StructureDiscovery;
import com.user.terra_script.world.StructureInjector;
import com.user.terra_script.world.city.stage.StructureTemplateQueryService;
import com.user.terra_script.config.StructurePlan;
import com.user.terra_script.world.io.WorldRepository;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class WorldController {
    private static final String STEP_Q1 = "Q1_SCAN_PREVIEW_PENDING_PICK";
    private static final String STEP_Q2 = "Q2_PICK_CLUSTER_POINT";
    private final MinecraftServer mcServer;
    private final Gson gson = new Gson();

    public WorldController(MinecraftServer mcServer) {
        this.mcServer = mcServer;
    }

    public void handleContinents(HttpExchange exchange) throws IOException {
        try {
            var holder = ScanResultHolder.get();
            JsonArray list = new JsonArray();

            if (holder.lastClusters != null) {
                for (ScanRegion r : holder.lastClusters) {
                    JsonObject obj = serializeRegion(r, holder);
                    obj.addProperty("type", "LAND");
                    list.add(obj);
                }
            }

            if (holder.lastOceanRegions != null) {
                for (ScanRegion r : holder.lastOceanRegions) {
                    JsonObject obj = serializeRegion(r, holder);
                    obj.addProperty("type", "OCEAN");
                    list.add(obj);
                }
            }

            JsonObject res = new JsonObject();
            res.addProperty("step", "W3");
            res.add("continents", list);
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (IllegalArgumentException e) {
            HttpUtil.sendResponse(exchange, 400, "{\"error\": \"" + e.getMessage() + "\"}");
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleWorldAtlas(HttpExchange exchange) throws IOException {
        try {
            JsonObject res = new JsonObject();
            res.addProperty("step", "W4");
            Optional<JsonElement> atlas = WorldRepository.readWorldAtlas(mcServer);
            Optional<JsonElement> summary = WorldRepository.readWorldSummary(mcServer);
            res.add("atlas", atlas.orElse(JsonNull.INSTANCE));
            res.add("summary", summary.orElse(JsonNull.INSTANCE));
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleWorldSummary(HttpExchange exchange) throws IOException {
        try {
            JsonObject res = new JsonObject();
            res.addProperty("step", "W4");
            Optional<JsonElement> summary = WorldRepository.readWorldSummary(mcServer);
            res.add("summary", summary.orElse(JsonNull.INSTANCE));
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleT1PreviewMaps(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject req = JsonParser.parseString(body).getAsJsonObject();
            int regionId = req.has("region_id") ? req.get("region_id").getAsInt() : -1;
            if (regionId <= 0) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"region_id is required\"}");
                return;
            }

            JsonObject preview = T1PreviewExporter.export(mcServer, ScanResultHolder.get(), regionId);
            JsonObject res = new JsonObject();
            res.addProperty("step", "T1");
            res.add("preview", preview);
            int status = preview.has("generated") && preview.get("generated").getAsBoolean() ? 200 : 400;
            HttpUtil.sendResponse(exchange, status, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleTerrainSummary(HttpExchange exchange) throws IOException {
        try {
            JsonObject res = new JsonObject();
            res.addProperty("step", "W4");
            Optional<JsonElement> summary = WorldRepository.readTerrainSummary(mcServer);
            res.add("summary", summary.orElse(JsonNull.INSTANCE));
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleStructures(HttpExchange exchange) throws IOException {
        try {
            var list = StructureDiscovery.scanAllStructures(mcServer.overworld());
            HttpUtil.sendResponse(exchange, 200, gson.toJson(list));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleStructureTemplatesQuery(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject req = JsonParser.parseString(body).getAsJsonObject();
            StructureTemplateQueryService.QueryRequest query = new StructureTemplateQueryService.QueryRequest();
            query.function_tag = req.has("function_tag") ? req.get("function_tag").getAsString() : null;
            query.size_tier = req.has("size_tier") ? req.get("size_tier").getAsString() : null;
            query.arrangement_type = req.has("arrangement_type") ? req.get("arrangement_type").getAsString() : null;
            query.require_connector = req.has("require_connector") && req.get("require_connector").getAsBoolean();
            query.strict_tag_source = !req.has("strict_tag_source") || req.get("strict_tag_source").getAsBoolean();

            StructureTemplateQueryService.QueryResult result = StructureTemplateQueryService.queryTemplates(query);
            HttpUtil.sendResponse(exchange, result.ok ? 200 : 422, gson.toJson(result));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleQueryRegion(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject req = JsonParser.parseString(body).getAsJsonObject();

            JsonObject response = buildQueryRegionBundle(mcServer, req);
            String territoryId = req.has("territory_id") ? req.get("territory_id").getAsString() : null;
            int regionId = req.has("region_id") ? req.get("region_id").getAsInt() : -1;
            String targetType = territoryId == null ? "region" : "territory";
            String targetId = territoryId == null ? String.valueOf(regionId) : territoryId;
            JsonObject pending = persistPendingSelection(targetType, targetId, response);
            response.add("selection_pending", pending);
            HttpUtil.sendResponse(exchange, 200, gson.toJson(response));

        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public static JsonObject buildRegionCandidateBundle(
            MinecraftServer mcServer,
            String targetType,
            String targetId,
            int regionId,
            int limit
    ) {
        JsonObject req = new JsonObject();
        req.addProperty("region_id", regionId);
        req.addProperty("limit", Math.max(1, limit));
        JsonObject bundle = buildQueryRegionBundle(mcServer, req);
        if (targetType != null && !targetType.isBlank() && targetId != null && !targetId.isBlank()) {
            JsonObject metadata = bundle.has("metadata") && bundle.get("metadata").isJsonObject()
                    ? bundle.getAsJsonObject("metadata")
                    : new JsonObject();
            metadata.addProperty("target_type", targetType);
            metadata.addProperty("target_id", targetId);
            bundle.add("metadata", metadata);
        }
        return bundle;
    }

    public static JsonObject pickPointFromBundle(
            JsonObject pending,
            Integer clusterId,
            String label,
            String pointMode
    ) {
        if (pending == null || !pending.has("candidates") || !pending.get("candidates").isJsonArray()) return null;
        JsonObject req = new JsonObject();
        if (clusterId != null) req.addProperty("cluster_id", clusterId);
        if (label != null && !label.isBlank()) req.addProperty("label", label);
        JsonObject selected = pickCandidate(pending.getAsJsonArray("candidates"), req);
        if (selected == null) return null;
        JsonObject keyPoints = selected.has("key_points") && selected.get("key_points").isJsonObject()
                ? selected.getAsJsonObject("key_points")
                : null;
        if (keyPoints == null) return null;

        JsonObject point = resolvePointFromMode(keyPoints, normalizePointMode(pointMode));
        if (point == null) return null;

        JsonObject result = new JsonObject();
        JsonObject selectedMeta = new JsonObject();
        if (selected.has("cluster_id")) selectedMeta.add("cluster_id", selected.get("cluster_id"));
        if (selected.has("label")) selectedMeta.add("label", selected.get("label"));
        if (selected.has("preview_label")) selectedMeta.add("preview_label", selected.get("preview_label"));
        result.add("selected_cluster", selectedMeta);
        result.add("selected_point", point.deepCopy());
        result.addProperty("point_mode_applied", normalizePointMode(pointMode));
        return result;
    }

    public static JsonObject buildQueryRegionBundle(MinecraftServer mcServer, JsonObject req) {
        QueryRegionInput input = QueryRegionInput.from(req);
        return buildQueryRegionBundle(mcServer, ScanResultHolder.get(), input);
    }

    static JsonObject buildQueryRegionBundle(MinecraftServer mcServer, ScanResultHolder holder, QueryRegionInput input) {
        if (input == null) throw new IllegalArgumentException("query_region request is required");

        List<ScanPixel> basePixels = new ArrayList<>();
        RegionCache refCache = null;
        int step;

        if (input.territoryId != null) {
            String[][] owners = com.user.terra_script.world.TerritoryManager.globalOwnershipMap;
            ScanPixel[][] globalPixels = holder.lastScanData;
            if (owners == null || globalPixels == null) {
                throw new IllegalArgumentException("Territory data not ready. Please run 'establish_territory' first.");
            }
            var allRes = com.user.terra_script.world.TerritoryManager.getAllResults();
            var targetRes = allRes.stream()
                    .filter(r -> r != null && r.config != null && input.territoryId.equals(r.config.id))
                    .findFirst()
                    .orElse(null);
            if (targetRes == null) {
                throw new IllegalArgumentException("Territory ID not found: " + input.territoryId);
            }
            refCache = holder.regionCacheMap.get(targetRes.config.regionId);
            if (refCache == null || refCache.slopeData == null || refCache.tpiData == null) {
                throw new IllegalArgumentException("Terrain features missing for territory region " + targetRes.config.regionId + ".");
            }

            step = holder.scanStep;
            int radiusBlocks = holder.scanRadiusChunks * 16;
            int globalMinX = -radiusBlocks;
            int globalMinZ = -radiusBlocks;
            int w = globalPixels.length;
            int h = globalPixels[0].length;

            int gMinX = Math.max(0, (targetRes.stats.minX - globalMinX) / step);
            int gMaxX = Math.min(w - 1, (targetRes.stats.maxX - globalMinX) / step);
            int gMinZ = Math.max(0, (targetRes.stats.minZ - globalMinZ) / step);
            int gMaxZ = Math.min(h - 1, (targetRes.stats.maxZ - globalMinZ) / step);

            for (int i = gMinX; i <= gMaxX; i++) {
                for (int j = gMinZ; j <= gMaxZ; j++) {
                    if (!input.territoryId.equals(owners[i][j])) continue;
                    ScanPixel p = globalPixels[i][j];
                    if (p != null && p.isLand()) basePixels.add(p);
                }
            }
        } else if (input.regionId > 0) {
            refCache = holder.regionCacheMap.get(input.regionId);
            if (refCache == null || refCache.detailData == null) {
                throw new IllegalArgumentException("Region " + input.regionId + " not cached.");
            }
            if (refCache.slopeData == null || refCache.tpiData == null) {
                throw new IllegalArgumentException("Terrain features missing for Region " + input.regionId + ".");
            }
            step = refCache.step;
            int sampleStep = refCache.detailData.length * refCache.detailData[0].length > 250000 ? 2 : 1;
            for (int i = 0; i < refCache.detailData.length; i += sampleStep) {
                for (int j = 0; j < refCache.detailData[0].length; j += sampleStep) {
                    ScanPixel p = refCache.detailData[i][j];
                    if (p != null && p.isLand()) basePixels.add(p);
                }
            }
        } else {
            throw new IllegalArgumentException("Must provide either 'region_id' or 'territory_id'.");
        }

        JsonObject response = new JsonObject();
        response.addProperty("step", STEP_Q1);
        JsonObject meta = new JsonObject();
        if (input.territoryId != null) meta.addProperty("target_territory", input.territoryId);
        else meta.addProperty("target_region", input.regionId);
        meta.addProperty("interest_groups_enabled", input.hasInterestGroups());
        response.add("metadata", meta);

        JsonArray candidatesArr = new JsonArray();
        JsonArray candidatesMetadata = new JsonArray();
        JsonArray groupAsciiMaps = new JsonArray();
        List<OverlayCluster> overlayClusters = new ArrayList<>();
        int globalClusterId = 0;

        if (input.hasInterestGroups()) {
            Map<String, Integer> groupCounter = new LinkedHashMap<>();
            for (int gi = 0; gi < input.interestGroups.size(); gi++) {
                JsonElement groupEl = input.interestGroups.get(gi);
                if (!groupEl.isJsonObject()) continue;
                JsonObject group = groupEl.getAsJsonObject();
                String groupId = group.has("id") ? group.get("id").getAsString() : String.valueOf((char) ('A' + (gi % 26)));
                JsonObject criteriaObj = group.has("criteria") && group.get("criteria").isJsonObject()
                        ? group.getAsJsonObject("criteria")
                        : group;
                CandidateCriteria criteria = parseCriteria(criteriaObj, input.criteria);
                int groupLimit = group.has("limit") ? Math.max(1, group.get("limit").getAsInt()) : Math.max(1, input.limitPerGroup);
                char symbol = pickGroupSymbol(groupId, gi);
                List<ScanPixel> filtered = filterCandidates(basePixels, refCache, criteria);
                List<List<DBSCAN.Point>> groupedClusters = DBSCAN.cluster(filtered, step * 4.0, 5);
                groupedClusters.sort((c1, c2) -> Integer.compare(c2.size(), c1.size()));
                for (int idx = 0; idx < groupedClusters.size() && idx < groupLimit; idx++) {
                    List<DBSCAN.Point> cluster = groupedClusters.get(idx);
                    if (cluster.isEmpty()) continue;
                    globalClusterId++;
                    int serial = groupCounter.merge(groupId, 1, Integer::sum);
                    String label = groupId + serial;
                    char previewLabel = pickPreviewLabel(globalClusterId - 1);
                    JsonObject clusterJson = buildClusterJson(globalClusterId, cluster, refCache);
                    clusterJson.addProperty("group_id", groupId);
                    clusterJson.addProperty("label", label);
                    clusterJson.addProperty("symbol", String.valueOf(symbol));
                    clusterJson.addProperty("preview_label", String.valueOf(previewLabel));
                    candidatesArr.add(clusterJson);

                    JsonObject metaEntry = new JsonObject();
                    metaEntry.addProperty("label", label);
                    metaEntry.addProperty("group", groupId);
                    metaEntry.addProperty("cluster_id", globalClusterId);
                    if (clusterJson.has("description")) metaEntry.addProperty("desc", clusterJson.get("description").getAsString());
                    if (clusterJson.has("key_points") && clusterJson.getAsJsonObject("key_points").has("center")) {
                        metaEntry.add("center", clusterJson.getAsJsonObject("key_points").get("center"));
                    }
                    candidatesMetadata.add(metaEntry);

                    JsonObject mapEntry = new JsonObject();
                    mapEntry.addProperty("label", label);
                    mapEntry.addProperty("group", groupId);
                    mapEntry.addProperty("cluster_id", globalClusterId);
                    mapEntry.add("ascii_map", clusterJson.getAsJsonArray("ascii_map"));
                    groupAsciiMaps.add(mapEntry);
                    overlayClusters.add(new OverlayCluster(symbol, previewLabel, label, globalClusterId, cluster));
                }
            }
            response.add("candidates_metadata", candidatesMetadata);
            response.add("group_ascii_maps", groupAsciiMaps);
            response.add("visual_map", buildVisualMap(overlayClusters));
        } else {
            List<ScanPixel> filtered = filterCandidates(basePixels, refCache, input.criteria);
            List<List<DBSCAN.Point>> filteredClusters = DBSCAN.cluster(filtered, step * 4.0, 5);
            filteredClusters.sort((c1, c2) -> Integer.compare(c2.size(), c1.size()));
            int count = 0;
            for (List<DBSCAN.Point> cluster : filteredClusters) {
                if (count >= input.limit) break;
                globalClusterId++;
                char previewLabel = pickPreviewLabel(globalClusterId - 1);
                JsonObject clusterJson = buildClusterJson(globalClusterId, cluster, refCache);
                clusterJson.addProperty("label", String.valueOf(previewLabel));
                clusterJson.addProperty("symbol", String.valueOf(previewLabel));
                clusterJson.addProperty("preview_label", String.valueOf(previewLabel));
                candidatesArr.add(clusterJson);
                overlayClusters.add(new OverlayCluster(previewLabel, previewLabel, String.valueOf(previewLabel), globalClusterId, cluster));
                count++;
            }
        }

        response.add("candidates", candidatesArr);
        List<QueryRegionPreviewExporter.OverlayInput> previewOverlays = new ArrayList<>();
        for (OverlayCluster overlay : overlayClusters) {
            previewOverlays.add(new QueryRegionPreviewExporter.OverlayInput(
                    overlay.previewLabel,
                    overlay.label,
                    overlay.clusterId,
                    overlay.points
            ));
        }

        ScanPixel[][] mapData;
        int mapWorldMinX;
        int mapWorldMinZ;
        int mapStep;
        if (input.territoryId != null) {
            mapData = holder.lastScanData;
            mapStep = holder.scanStep;
            int radiusBlocks = holder.scanRadiusChunks * 16;
            mapWorldMinX = -radiusBlocks;
            mapWorldMinZ = -radiusBlocks;
        } else {
            mapData = refCache.detailData;
            mapStep = refCache.step;
            mapWorldMinX = refCache.minX;
            mapWorldMinZ = refCache.minZ;
        }

        JsonObject preview = QueryRegionPreviewExporter.export(
                mcServer,
                input.territoryId == null ? "region" : "territory",
                input.territoryId == null ? String.valueOf(input.regionId) : input.territoryId,
                mapData,
                mapWorldMinX,
                mapWorldMinZ,
                mapStep,
                basePixels,
                previewOverlays
        );
        response.add("preview_overlay", preview);
        return response;
    }

    public void handleQueryRegionPick(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject req = JsonParser.parseString(body).getAsJsonObject();

            String queryId = req.has("query_id") ? req.get("query_id").getAsString() : null;
            String targetType = req.has("target_type") ? req.get("target_type").getAsString() : null;
            String targetId = req.has("target_id") ? req.get("target_id").getAsString() : null;
            String pointMode = req.has("point_mode") ? req.get("point_mode").getAsString() : "center";

            JsonObject pending = loadPendingSelection(queryId, targetType, targetId);
            if (pending == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\":\"No pending query_region selection found.\"}");
                return;
            }
            if (!pending.has("candidates") || !pending.get("candidates").isJsonArray()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\":\"Pending selection cache has no candidates.\"}");
                return;
            }

            JsonArray candidates = pending.getAsJsonArray("candidates");
            JsonObject selected = pickCandidate(candidates, req);
            if (selected == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\":\"Candidate not found. Provide cluster_id or label/preview_label.\"}");
                return;
            }

            JsonObject keyPoints = selected.has("key_points") && selected.get("key_points").isJsonObject()
                    ? selected.getAsJsonObject("key_points")
                    : null;
            if (keyPoints == null || !keyPoints.has("center")) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\":\"Selected candidate missing key_points.\"}");
                return;
            }

            String appliedMode = normalizePointMode(pointMode);
            JsonObject point = resolvePointFromMode(keyPoints, appliedMode);
            if (point == null) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\":\"Failed to resolve point from mode.\"}");
                return;
            }

            JsonObject res = new JsonObject();
            res.addProperty("step", STEP_Q2);
            res.addProperty("query_id", pending.has("query_id") ? pending.get("query_id").getAsString() : "");
            res.addProperty("point_mode_requested", pointMode);
            res.addProperty("point_mode_applied", appliedMode);

            JsonObject selectedMeta = new JsonObject();
            selectedMeta.addProperty("cluster_id", selected.has("cluster_id") ? selected.get("cluster_id").getAsInt() : -1);
            if (selected.has("label")) selectedMeta.addProperty("label", selected.get("label").getAsString());
            if (selected.has("preview_label")) selectedMeta.addProperty("preview_label", selected.get("preview_label").getAsString());
            if (selected.has("group_id")) selectedMeta.addProperty("group_id", selected.get("group_id").getAsString());
            res.add("selected_cluster", selectedMeta);
            res.add("selected_point", point);

            JsonObject pendingInfo = new JsonObject();
            if (pending.has("target_type")) pendingInfo.addProperty("target_type", pending.get("target_type").getAsString());
            if (pending.has("target_id")) pendingInfo.addProperty("target_id", pending.get("target_id").getAsString());
            if (pending.has("preview_overlay")) pendingInfo.add("preview_overlay", pending.get("preview_overlay"));
            res.add("from_pending", pendingInfo);

            persistPickResult(res);
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handlePlace(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            int x = json.get("x").getAsInt();
            int z = json.get("z").getAsInt();
            String id = json.get("id").getAsString();

            StructurePlan.get().addStructure(x, z, id);
            mcServer.execute(() -> {
                ServerLevel level = mcServer.overworld();
                net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(x, z);
                if (level.hasChunk(cp.x, cp.z)) {
                    StructureInjector.spawnStructure(level, cp, id);
                    StructurePlan.get().removeStructure(x, z);
                }
            });
            HttpUtil.sendResponse(exchange, 200, "{\"status\": \"planned\"}");
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    static boolean checkCriteria(double slope, double tpi, double minS, double maxS, double minT, double maxT) {
        return slope >= minS && slope <= maxS && tpi >= minT && tpi <= maxT;
    }

    private static CandidateCriteria parseCriteria(JsonObject criteriaObj, CandidateCriteria fallback) {
        if (criteriaObj == null) return fallback;
        double minSlope = getDouble(criteriaObj, "min_slope", fallback.minSlope);
        double maxSlope = getDouble(criteriaObj, "max_slope", fallback.maxSlope);
        double minTpi = getDouble(criteriaObj, "min_tpi", fallback.minTpi);
        double maxTpi = getDouble(criteriaObj, "max_tpi", fallback.maxTpi);
        return new CandidateCriteria(minSlope, maxSlope, minTpi, maxTpi);
    }

    private static double getDouble(JsonObject obj, String key, double defaultValue) {
        if (obj == null || !obj.has(key)) return defaultValue;
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return defaultValue;
        try {
            return el.getAsDouble();
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static List<ScanPixel> filterCandidates(List<ScanPixel> source, RegionCache cache, CandidateCriteria criteria) {
        List<ScanPixel> filtered = new ArrayList<>();
        for (ScanPixel p : source) {
            if (p == null || !p.isLand()) continue;
            if (cache == null) continue;
            double s = sample(cache.slopeData, cache, p.x(), p.z());
            double t = sample(cache.tpiData, cache, p.x(), p.z());
            if (checkCriteria(s, t, criteria.minSlope, criteria.maxSlope, criteria.minTpi, criteria.maxTpi)) {
                filtered.add(p);
            }
        }
        return filtered;
    }

    private static double sample(double[][] grid, RegionCache cache, int x, int z) {
        if (grid == null || cache == null || cache.step <= 0) return 0.0;
        int gx = (x - cache.minX) / cache.step;
        int gz = (z - cache.minZ) / cache.step;
        if (gx < 0 || gx >= grid.length || gz < 0 || gz >= grid[0].length) return 0.0;
        return grid[gx][gz];
    }

    private static JsonObject buildClusterJson(int clusterId, List<DBSCAN.Point> cluster, RegionCache cache) {
        JsonObject clusterJson = AsciiMapGenerator.generate(clusterId, cluster);

        double avgSlope = 0.0;
        double avgTpi = 0.0;
        if (cache != null) {
            avgSlope = cluster.stream().mapToDouble(p -> sample(cache.slopeData, cache, p.x, p.z)).average().orElse(0.0);
            avgTpi = cluster.stream().mapToDouble(p -> sample(cache.tpiData, cache, p.x, p.z)).average().orElse(0.0);
        }

        String desc = "Area";
        if (avgTpi > 1.0) desc = "Ridge/Highland";
        else if (avgTpi < -1.0) desc = "Valley/Basin";
        else desc = "Plain";
        if (avgSlope > 1.5) desc += " (Rugged)";
        else if (avgSlope < 0.5) desc += " (Flat)";
        clusterJson.addProperty("description", desc);

        JsonObject metrics = new JsonObject();
        metrics.addProperty("size", cluster.size());
        if (!cluster.isEmpty()) metrics.addProperty("biome", cluster.get(0).data.biomeId());
        clusterJson.add("metrics", metrics);
        return clusterJson;
    }

    private static char pickGroupSymbol(String groupId, int groupIndex) {
        if (groupId != null) {
            for (int i = 0; i < groupId.length(); i++) {
                char c = Character.toUpperCase(groupId.charAt(i));
                if (c >= 'A' && c <= 'Z') return c;
            }
        }
        return (char) ('A' + (groupIndex % 26));
    }

    private static char pickPreviewLabel(int index) {
        return (char) ('A' + (Math.max(0, index) % 26));
    }

    private JsonObject persistPendingSelection(String targetType, String targetId, JsonObject response) {
        JsonObject pending = new JsonObject();
        try {
            String queryId = buildQueryId(targetType, targetId);
            Path dir = queryRegionCacheDir();
            Files.createDirectories(dir);

            JsonObject content = new JsonObject();
            content.addProperty("step", STEP_Q1);
            content.addProperty("query_id", queryId);
            content.addProperty("created_at_epoch_ms", System.currentTimeMillis());
            content.addProperty("target_type", targetType);
            content.addProperty("target_id", targetId);
            content.addProperty("selection_pending", true);
            content.add("metadata", response.get("metadata"));
            content.add("candidates", response.get("candidates"));
            if (response.has("candidates_metadata")) content.add("candidates_metadata", response.get("candidates_metadata"));
            if (response.has("group_ascii_maps")) content.add("group_ascii_maps", response.get("group_ascii_maps"));
            if (response.has("visual_map")) content.add("visual_map", response.get("visual_map"));
            if (response.has("preview_overlay")) content.add("preview_overlay", response.get("preview_overlay"));

            JsonArray modes = new JsonArray();
            modes.add("center");
            modes.add("north");
            modes.add("south");
            modes.add("east");
            modes.add("west");
            modes.add("random_cardinal");
            content.add("supported_point_modes", modes);

            String fileName = "query_region_selection_" + queryId + ".json";
            Path file = dir.resolve(fileName);
            Files.writeString(file, gson.toJson(content), StandardCharsets.UTF_8);

            JsonObject latest = new JsonObject();
            latest.addProperty("query_id", queryId);
            latest.addProperty("target_type", targetType);
            latest.addProperty("target_id", targetId);
            latest.addProperty("file", "cache/query_region/" + fileName);
            latest.addProperty("updated_at_epoch_ms", System.currentTimeMillis());
            Path latestFile = dir.resolve("query_region_selection_latest_" + sanitize(targetType + "_" + targetId) + ".json");
            Files.writeString(latestFile, gson.toJson(latest), StandardCharsets.UTF_8);

            pending.addProperty("query_id", queryId);
            pending.addProperty("status", "pending");
            pending.addProperty("step", STEP_Q1);
            pending.addProperty("cache", "cache/query_region/" + fileName);
            pending.addProperty("latest_pointer", "cache/query_region/" + latestFile.getFileName());
            pending.addProperty("pick_api", "/query_region_pick");
        } catch (Exception e) {
            pending.addProperty("status", "pending_cache_failed");
            pending.addProperty("error", e.getMessage() == null ? "unknown" : e.getMessage());
        }
        return pending;
    }

    private JsonObject loadPendingSelection(String queryId, String targetType, String targetId) {
        try {
            Path dir = queryRegionCacheDir();
            if (queryId != null && !queryId.isBlank()) {
                Path file = dir.resolve("query_region_selection_" + sanitize(queryId) + ".json");
                if (Files.exists(file)) return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                return null;
            }
            if (targetType == null || targetType.isBlank() || targetId == null || targetId.isBlank()) return null;
            Path latest = dir.resolve("query_region_selection_latest_" + sanitize(targetType + "_" + targetId) + ".json");
            if (!Files.exists(latest)) return null;
            JsonObject latestObj = JsonParser.parseString(Files.readString(latest, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!latestObj.has("query_id")) return null;
            String latestQueryId = latestObj.get("query_id").getAsString();
            Path file = dir.resolve("query_region_selection_" + sanitize(latestQueryId) + ".json");
            if (!Files.exists(file)) return null;
            return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JsonObject pickCandidate(JsonArray candidates, JsonObject req) {
        Integer clusterId = req.has("cluster_id") ? req.get("cluster_id").getAsInt() : null;
        String label = req.has("label") ? req.get("label").getAsString() : null;
        String previewLabel = req.has("preview_label") ? req.get("preview_label").getAsString() : null;

        for (JsonElement el : candidates) {
            if (!el.isJsonObject()) continue;
            JsonObject c = el.getAsJsonObject();
            if (clusterId != null && c.has("cluster_id") && c.get("cluster_id").getAsInt() == clusterId) return c;
            if (label != null && c.has("label") && label.equalsIgnoreCase(c.get("label").getAsString())) return c;
            if (previewLabel != null && c.has("preview_label") && previewLabel.equalsIgnoreCase(c.get("preview_label").getAsString())) return c;
        }
        return null;
    }

    private static String normalizePointMode(String raw) {
        String mode = raw == null ? "center" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "north", "south", "east", "west", "random_cardinal", "center" -> mode;
            default -> "center";
        };
    }

    private static JsonObject resolvePointFromMode(JsonObject keyPoints, String mode) {
        if ("random_cardinal".equals(mode)) {
            List<String> keys = List.of("north_tip", "south_tip", "east_tip", "west_tip");
            String selected = keys.get(ThreadLocalRandom.current().nextInt(keys.size()));
            JsonObject point = keyPoints.has(selected) ? keyPoints.getAsJsonObject(selected) : null;
            if (point != null) return point;
            return keyPoints.getAsJsonObject("center");
        }
        String key = switch (mode) {
            case "north" -> "north_tip";
            case "south" -> "south_tip";
            case "east" -> "east_tip";
            case "west" -> "west_tip";
            default -> "center";
        };
        if (keyPoints.has(key) && keyPoints.get(key).isJsonObject()) return keyPoints.getAsJsonObject(key);
        if (keyPoints.has("center") && keyPoints.get("center").isJsonObject()) return keyPoints.getAsJsonObject("center");
        return null;
    }

    private void persistPickResult(JsonObject result) {
        try {
            Path dir = queryRegionCacheDir();
            Files.createDirectories(dir);
            String queryId = result.has("query_id") ? result.get("query_id").getAsString() : "unknown";
            int clusterId = result.has("selected_cluster") && result.getAsJsonObject("selected_cluster").has("cluster_id")
                    ? result.getAsJsonObject("selected_cluster").get("cluster_id").getAsInt()
                    : -1;
            String fileName = "query_region_pick_" + sanitize(queryId) + "_c" + clusterId + ".json";
            Files.writeString(dir.resolve(fileName), gson.toJson(result), StandardCharsets.UTF_8);
        } catch (Exception ignored) { }
    }

    private Path queryRegionCacheDir() {
        return mcServer.getWorldPath(LevelResource.ROOT).resolve("terra_script").resolve("cache").resolve("query_region");
    }

    private static String buildQueryId(String targetType, String targetId) {
        return sanitize(targetType + "_" + targetId) + "_" + System.currentTimeMillis();
    }

    private static String sanitize(String input) {
        if (input == null || input.isBlank()) return "unknown";
        return input.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static JsonArray buildVisualMap(List<OverlayCluster> overlays) {
        JsonArray arr = new JsonArray();
        if (overlays == null || overlays.isEmpty()) return arr;

        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (OverlayCluster overlay : overlays) {
            for (DBSCAN.Point p : overlay.points) {
                minX = Math.min(minX, p.x);
                maxX = Math.max(maxX, p.x);
                minZ = Math.min(minZ, p.z);
                maxZ = Math.max(maxZ, p.z);
            }
        }

        if (minX > maxX || minZ > maxZ) return arr;

        int width = maxX - minX + 1;
        int height = maxZ - minZ + 1;
        int maxDim = Math.max(width, height);
        int scale = Math.max(1, (int) Math.ceil(maxDim / 32.0));
        int gridW = Math.max(1, (int) Math.ceil((double) width / scale));
        int gridH = Math.max(1, (int) Math.ceil((double) height / scale));

        char[][] canvas = new char[gridH][gridW];
        for (char[] row : canvas) Arrays.fill(row, '~');

        for (OverlayCluster overlay : overlays) {
            for (DBSCAN.Point p : overlay.points) {
                int ix = (p.x - minX) / scale;
                int iz = (p.z - minZ) / scale;
                if (ix >= 0 && ix < gridW && iz >= 0 && iz < gridH) {
                    canvas[iz][ix] = overlay.symbol;
                }
            }
        }

        for (char[] row : canvas) {
            arr.add(new String(row));
        }
        return arr;
    }

    public static final class CandidateCriteria {
        final double minSlope;
        final double maxSlope;
        final double minTpi;
        final double maxTpi;

        public CandidateCriteria(double minSlope, double maxSlope, double minTpi, double maxTpi) {
            this.minSlope = minSlope;
            this.maxSlope = maxSlope;
            this.minTpi = minTpi;
            this.maxTpi = maxTpi;
        }
    }

    static final class QueryRegionInput {
        final String territoryId;
        final int regionId;
        final CandidateCriteria criteria;
        final int limit;
        final int limitPerGroup;
        final JsonArray interestGroups;

        QueryRegionInput(
                String territoryId,
                int regionId,
                CandidateCriteria criteria,
                int limit,
                int limitPerGroup,
                JsonArray interestGroups
        ) {
            this.territoryId = territoryId;
            this.regionId = regionId;
            this.criteria = criteria;
            this.limit = Math.max(1, limit);
            this.limitPerGroup = Math.max(1, limitPerGroup);
            this.interestGroups = interestGroups;
        }

        static QueryRegionInput from(JsonObject req) {
            String territoryId = req.has("territory_id") ? req.get("territory_id").getAsString() : null;
            int regionId = req.has("region_id") ? req.get("region_id").getAsInt() : -1;
            double minSlope = req.has("min_slope") ? req.get("min_slope").getAsDouble() : -1;
            double maxSlope = req.has("max_slope") ? req.get("max_slope").getAsDouble() : 999;
            double minTpi = req.has("min_tpi") ? req.get("min_tpi").getAsDouble() : -999;
            double maxTpi = req.has("max_tpi") ? req.get("max_tpi").getAsDouble() : 999;
            int limit = req.has("limit") ? req.get("limit").getAsInt() : 5;
            int limitPerGroup = req.has("limit_per_group") ? req.get("limit_per_group").getAsInt() : Math.max(1, limit);
            JsonArray interestGroups = req.has("interest_groups") && req.get("interest_groups").isJsonArray()
                    ? req.getAsJsonArray("interest_groups")
                    : null;
            return new QueryRegionInput(
                    territoryId,
                    regionId,
                    new CandidateCriteria(minSlope, maxSlope, minTpi, maxTpi),
                    limit,
                    limitPerGroup,
                    interestGroups
            );
        }

        boolean hasInterestGroups() {
            return interestGroups != null && !interestGroups.isEmpty();
        }
    }

    private static final class OverlayCluster {
        final char symbol;
        final char previewLabel;
        final String label;
        final int clusterId;
        final List<DBSCAN.Point> points;

        OverlayCluster(char symbol, char previewLabel, String label, int clusterId, List<DBSCAN.Point> points) {
            this.symbol = symbol;
            this.previewLabel = previewLabel;
            this.label = label;
            this.clusterId = clusterId;
            this.points = points;
        }
    }

    private static JsonObject serializeRegion(ScanRegion r, ScanResultHolder holder) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", r.id);
        JsonObject center = new JsonObject();
        center.addProperty("x", r.centerX);
        center.addProperty("z", r.centerZ);
        obj.add("center", center);
        obj.addProperty("area", r.area);

        JsonObject terrain = new JsonObject();
        terrain.addProperty("avg_height", Math.round(r.avgHeight));
        terrain.addProperty("roughness", String.format("%.2f", r.roughness));
        obj.add("terrain", terrain);

        JsonObject climate = new JsonObject();
        climate.addProperty("avg_temp", r.avgTemp);
        obj.add("climate", climate);

        JsonObject eco = new JsonObject();
        obj.add("ecology", eco);

        obj.addProperty("has_detail", holder.regionCacheMap.containsKey(r.id));
        return obj;
    }
}
