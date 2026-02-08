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
import com.user.terra_script.server.http.HttpUtil;
import com.user.terra_script.util.AsciiMapGenerator;
import com.user.terra_script.util.DBSCAN;
import com.user.terra_script.util.StructureDiscovery;
import com.user.terra_script.world.StructureInjector;
import com.user.terra_script.config.StructurePlan;
import com.user.terra_script.world.io.WorldRepository;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;
import java.util.*;

public class WorldController {
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

    public void handleQueryRegion(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject req = JsonParser.parseString(body).getAsJsonObject();

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
            boolean hasInterestGroups = interestGroups != null && !interestGroups.isEmpty();
            CandidateCriteria defaultCriteria = new CandidateCriteria(minSlope, maxSlope, minTpi, maxTpi);

            List<ScanPixel> basePixels = new ArrayList<>();
            int step = 1;
            RegionCache refCache = null;

            if (territoryId != null) {
                var holder = ScanResultHolder.get();
                String[][] owners = com.user.terra_script.world.TerritoryManager.globalOwnershipMap;
                ScanPixel[][] globalPixels = holder.lastScanData;

                if (owners == null || globalPixels == null) {
                    HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Territory data not ready. Please run 'establish_territory' first.\"}");
                    return;
                }

                var allRes = com.user.terra_script.world.TerritoryManager.getAllResults();
                var targetRes = allRes.stream().filter(r -> r.config.id.equals(territoryId)).findFirst().orElse(null);
                if (targetRes == null) {
                    HttpUtil.sendResponse(exchange, 404, "{\"error\": \"Territory ID not found: " + territoryId + "\"}");
                    return;
                }

                step = holder.scanStep;
                int w = globalPixels.length;
                int h = globalPixels[0].length;
                int radiusBlocks = holder.scanRadiusChunks * 16;
                int globalMinX = -radiusBlocks;
                int globalMinZ = -radiusBlocks;

                int gMinX = (targetRes.stats.minX - globalMinX) / step;
                int gMaxX = (targetRes.stats.maxX - globalMinX) / step;
                int gMinZ = (targetRes.stats.minZ - globalMinZ) / step;
                int gMaxZ = (targetRes.stats.maxZ - globalMinZ) / step;

                gMinX = Math.max(0, gMinX);
                gMaxX = Math.min(w - 1, gMaxX);
                gMinZ = Math.max(0, gMinZ);
                gMaxZ = Math.min(h - 1, gMaxZ);

                for (int i = gMinX; i <= gMaxX; i++) {
                    for (int j = gMinZ; j <= gMaxZ; j++) {
                        if (territoryId.equals(owners[i][j])) {
                            ScanPixel p = globalPixels[i][j];
                            if (p != null && p.isLand()) {
                                basePixels.add(p);
                            }
                        }
                    }
                }
            } else if (regionId != -1) {
                var holder = ScanResultHolder.get();
                RegionCache cache = holder.regionCacheMap.get(regionId);
                if (cache == null || cache.detailData == null) {
                    HttpUtil.sendResponse(exchange, 404, "{\"error\": \"Region " + regionId + " not cached.\"}");
                    return;
                }
                if (cache.slopeData == null || cache.tpiData == null) {
                    HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Terrain features missing for Region " + regionId + ". Please generate them in-game.\"}");
                    return;
                }

                refCache = cache;
                step = cache.step;
                ScanPixel[][] pixels = cache.detailData;
                double[][] slopes = cache.slopeData;
                double[][] tpis = cache.tpiData;
                int w = pixels.length;
                int h = pixels[0].length;

                int sampleStep = 1;
                if (w * h > 250000) sampleStep = 2;

                for (int i = 0; i < w; i += sampleStep) {
                    for (int j = 0; j < h; j += sampleStep) {
                        if (pixels[i][j] != null && pixels[i][j].isLand()) {
                            basePixels.add(pixels[i][j]);
                        }
                    }
                }
            } else {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Must provide either 'region_id' or 'territory_id'.\"}");
                return;
            }

            JsonObject response = new JsonObject();
            JsonObject meta = new JsonObject();
            if (territoryId != null) meta.addProperty("target_territory", territoryId);
            else meta.addProperty("target_region", regionId);
            meta.addProperty("interest_groups_enabled", hasInterestGroups);
            response.add("metadata", meta);

            JsonArray candidatesArr = new JsonArray();
            JsonArray candidatesMetadata = new JsonArray();
            JsonArray groupAsciiMaps = new JsonArray();
            List<OverlayCluster> overlayClusters = new ArrayList<>();
            int globalClusterId = 0;

            if (hasInterestGroups) {
                Map<String, Integer> groupCounter = new LinkedHashMap<>();
                for (int gi = 0; gi < interestGroups.size(); gi++) {
                    JsonElement groupEl = interestGroups.get(gi);
                    if (!groupEl.isJsonObject()) continue;
                    JsonObject group = groupEl.getAsJsonObject();
                    String groupId = group.has("id") ? group.get("id").getAsString() : String.valueOf((char) ('A' + (gi % 26)));
                    JsonObject criteriaObj = group.has("criteria") && group.get("criteria").isJsonObject()
                            ? group.getAsJsonObject("criteria")
                            : group;
                    CandidateCriteria criteria = parseCriteria(criteriaObj, defaultCriteria);
                    int groupLimit = group.has("limit") ? Math.max(1, group.get("limit").getAsInt()) : Math.max(1, limitPerGroup);
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

                        JsonObject clusterJson = buildClusterJson(globalClusterId, cluster, refCache);
                        clusterJson.addProperty("group_id", groupId);
                        clusterJson.addProperty("label", label);
                        clusterJson.addProperty("symbol", String.valueOf(symbol));
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

                        overlayClusters.add(new OverlayCluster(symbol, cluster));
                    }
                }
                response.add("candidates_metadata", candidatesMetadata);
                response.add("group_ascii_maps", groupAsciiMaps);
                response.add("visual_map", buildVisualMap(overlayClusters));
            } else {
                List<ScanPixel> filtered = filterCandidates(basePixels, refCache, defaultCriteria);
                List<List<DBSCAN.Point>> filteredClusters = DBSCAN.cluster(filtered, step * 4.0, 5);
                filteredClusters.sort((c1, c2) -> Integer.compare(c2.size(), c1.size()));

                int count = 0;
                for (List<DBSCAN.Point> cluster : filteredClusters) {
                    if (count >= limit) break;
                    globalClusterId++;
                    JsonObject clusterJson = buildClusterJson(globalClusterId, cluster, refCache);
                    candidatesArr.add(clusterJson);
                    count++;
                }
            }

            response.add("candidates", candidatesArr);
            HttpUtil.sendResponse(exchange, 200, gson.toJson(response));

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

    private static boolean checkCriteria(ScanPixel p, double minS, double maxS, double minT, double maxT) {
        return true;
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
            if (cache == null) {
                if (checkCriteria(p, criteria.minSlope, criteria.maxSlope, criteria.minTpi, criteria.maxTpi)) {
                    filtered.add(p);
                }
                continue;
            }
            double s = sample(cache.slopeData, cache, p.x(), p.z());
            double t = sample(cache.tpiData, cache, p.x(), p.z());
            if (s >= criteria.minSlope && s <= criteria.maxSlope && t >= criteria.minTpi && t <= criteria.maxTpi) {
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

    private static final class CandidateCriteria {
        final double minSlope;
        final double maxSlope;
        final double minTpi;
        final double maxTpi;

        CandidateCriteria(double minSlope, double maxSlope, double minTpi, double maxTpi) {
            this.minSlope = minSlope;
            this.maxSlope = maxSlope;
            this.minTpi = minTpi;
            this.maxTpi = maxTpi;
        }
    }

    private static final class OverlayCluster {
        final char symbol;
        final List<DBSCAN.Point> points;

        OverlayCluster(char symbol, List<DBSCAN.Point> points) {
            this.symbol = symbol;
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

