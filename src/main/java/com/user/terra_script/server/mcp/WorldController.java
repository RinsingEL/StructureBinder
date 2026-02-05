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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

            List<ScanPixel> rawCandidates = new ArrayList<>();
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
                            if (p != null && checkCriteria(p, minSlope, maxSlope, minTpi, maxTpi)) {
                                rawCandidates.add(p);
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
                        if (pixels[i][j] == null || !pixels[i][j].isLand()) continue;
                        double s = slopes[i][j];
                        double t = tpis[i][j];
                        if (s >= minSlope && s <= maxSlope && t >= minTpi && t <= maxTpi) {
                            rawCandidates.add(pixels[i][j]);
                        }
                    }
                }
            } else {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Must provide either 'region_id' or 'territory_id'.\"}");
                return;
            }

            List<List<DBSCAN.Point>> clusters = DBSCAN.cluster(rawCandidates, step * 4.0, 5);

            JsonObject response = new JsonObject();
            JsonObject meta = new JsonObject();
            if (territoryId != null) meta.addProperty("target_territory", territoryId);
            else meta.addProperty("target_region", regionId);
            response.add("metadata", meta);

            JsonArray candidatesArr = new JsonArray();
            clusters.sort((c1, c2) -> Integer.compare(c2.size(), c1.size()));

            int count = 0;
            for (List<DBSCAN.Point> cluster : clusters) {
                if (count >= limit) break;

                JsonObject clusterJson = AsciiMapGenerator.generate(count + 1, cluster);

                double avgSlope = 0;
                double avgTpi = 0;

                if (refCache != null) {
                    final RegionCache finalCache = refCache;

                    avgSlope = cluster.stream().mapToDouble(p -> {
                        int gx = (p.x - finalCache.minX) / finalCache.step;
                        int gz = (p.z - finalCache.minZ) / finalCache.step;
                        if (gx >= 0 && gx < finalCache.slopeData.length && gz >= 0 && gz < finalCache.slopeData[0].length) {
                            return finalCache.slopeData[gx][gz];
                        }
                        return 0.0;
                    }).average().orElse(0.0);

                    avgTpi = cluster.stream().mapToDouble(p -> {
                        int gx = (p.x - finalCache.minX) / finalCache.step;
                        int gz = (p.z - finalCache.minZ) / finalCache.step;
                        if (gx >= 0 && gx < finalCache.tpiData.length && gz >= 0 && gz < finalCache.tpiData[0].length) {
                            return finalCache.tpiData[gx][gz];
                        }
                        return 0.0;
                    }).average().orElse(0.0);
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
                metrics.addProperty("biome", cluster.get(0).data.biomeId());
                clusterJson.add("metrics", metrics);

                candidatesArr.add(clusterJson);
                count++;
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

