package com.user.terra_script.server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import com.user.terra_script.client.data.ScanResultHolder;
import com.user.terra_script.client.data.ScanResultHolder.RegionCache;
import com.user.terra_script.config.StructurePlan;
import com.user.terra_script.scan.ScanPixel;
import com.user.terra_script.scan.ScanRegion;
import com.user.terra_script.util.AsciiMapGenerator;
import com.user.terra_script.util.DBSCAN;
import com.user.terra_script.util.StructureDiscovery;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import com.user.terra_script.world.city.CityConfig;
import com.user.terra_script.world.city.CityManager;
import com.user.terra_script.world.city.CityInstance;
import com.user.terra_script.world.NationGenManager;
import com.user.terra_script.world.city.CityStage1BinaryIO;
import com.user.terra_script.world.city.CityProjectSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;

@Mod.EventBusSubscriber(modid = "terra_script")
public class ModHttpServer {

    private static HttpServer server;
    private static MinecraftServer mcServer;
    private static final int PORT = 5000;
    private static final Gson gson = new Gson();

    @SubscribeEvent
    public static void onServerStart(ServerStartedEvent event) {
        mcServer = event.getServer();
        startHttpServer();
    }

    @SubscribeEvent
    public static void onServerStop(ServerStoppingEvent event) {
        stopHttpServer();
    }

    private static void startHttpServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(PORT), 0);

            // API 1: Continents
            server.createContext("/continents", exchange -> {
                try {
                    var holder = ScanResultHolder.get();
                    JsonArray list = new JsonArray();

                    // 1. 添加陆地
                    if (holder.lastClusters != null) {
                        for (ScanRegion r : holder.lastClusters) {
                            JsonObject obj = serializeRegion(r, holder); // 封装一个序列化方法
                            obj.addProperty("type", "LAND");
                            list.add(obj);
                        }
                    }

                    // 2. 添加海洋
                    if (holder.lastOceanRegions != null) {
                        for (ScanRegion r : holder.lastOceanRegions) {
                            JsonObject obj = serializeRegion(r, holder);
                            obj.addProperty("type", "OCEAN");
                            list.add(obj);
                        }
                    }

                    sendResponse(exchange, 200, gson.toJson(list));
                } catch (Exception e) { handleError(exchange, e); }
            });

            // API 2: Structures
            server.createContext("/structures", exchange -> {
                try {
                    var list = StructureDiscovery.scanAllStructures(mcServer.overworld());
                    sendResponse(exchange, 200, gson.toJson(list));
                } catch (Exception e) { handleError(exchange, e); }
            });

// API 3: Query Region (双模：大�?国度 + 聚类 + ASCII 地图)
            server.createContext("/query_region", exchange -> {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    sendResponse(exchange, 405, "Method Not Allowed");
                    return;
                }
                try {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    JsonObject req = JsonParser.parseString(body).getAsJsonObject();

                    // 参数解析
                    String territoryId = req.has("territory_id") ? req.get("territory_id").getAsString() : null;
                    int regionId = req.has("region_id") ? req.get("region_id").getAsInt() : -1;

                    double minSlope = req.has("min_slope") ? req.get("min_slope").getAsDouble() : -1;
                    double maxSlope = req.has("max_slope") ? req.get("max_slope").getAsDouble() : 999;
                    double minTpi = req.has("min_tpi") ? req.get("min_tpi").getAsDouble() : -999;
                    double maxTpi = req.has("max_tpi") ? req.get("max_tpi").getAsDouble() : 999;
                    int limit = req.has("limit") ? req.get("limit").getAsInt() : 5;

                    List<ScanPixel> rawCandidates = new ArrayList<>();
                    int step = 1;

                    // 用于后续计算 Description 的辅助变�?
                    // 如果�?Region 模式，我们可以用精确�?slopeData；如果是 Territory 模式，只能近�?
                    ScanResultHolder.RegionCache refCache = null;

                    // --- 分支 1: 国度模式 (Territory Mode) ---
                    if (territoryId != null) {
                        var holder = ScanResultHolder.get();
                        String[][] owners = com.user.terra_script.world.TerritoryManager.globalOwnershipMap;
                        ScanPixel[][] globalPixels = holder.lastScanData;

                        if (owners == null || globalPixels == null) {
                            sendResponse(exchange, 400, "{\"error\": \"Territory data not ready. Please run 'establish_territory' first.\"}");
                            return;
                        }

                        // 查找该国度的 BoundingBox 优化遍历
                        var allRes = com.user.terra_script.world.TerritoryManager.getAllResults();
                        var targetRes = allRes.stream().filter(r -> r.config.id.equals(territoryId)).findFirst().orElse(null);
                        if (targetRes == null) {
                            sendResponse(exchange, 404, "{\"error\": \"Territory ID not found: " + territoryId + "\"}");
                            return;
                        }

                        step = holder.scanStep;
                        int w = globalPixels.length;
                        int h = globalPixels[0].length;
                        int radiusBlocks = holder.scanRadiusChunks * 16;
                        int globalMinX = -radiusBlocks;
                        int globalMinZ = -radiusBlocks; // 假设扫描�?0,0 为中�?

                        // 计算网格范围 (Grid Range)
                        int gMinX = (targetRes.stats.minX - globalMinX) / step;
                        int gMaxX = (targetRes.stats.maxX - globalMinX) / step;
                        int gMinZ = (targetRes.stats.minZ - globalMinZ) / step;
                        int gMaxZ = (targetRes.stats.maxZ - globalMinZ) / step;

                        gMinX = Math.max(0, gMinX); gMaxX = Math.min(w-1, gMaxX);
                        gMinZ = Math.max(0, gMinZ); gMaxZ = Math.min(h-1, gMaxZ);

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
                    }

                    // --- 分支 2: 大陆模式 (Region Mode) ---
                    else if (regionId != -1) {
                        var holder = ScanResultHolder.get();
                        RegionCache cache = holder.regionCacheMap.get(regionId);
                        if (cache == null || cache.detailData == null) {
                            sendResponse(exchange, 404, "{\"error\": \"Region " + regionId + " not cached.\"}");
                            return;
                        }
                        if (cache.slopeData == null || cache.tpiData == null) {
                            sendResponse(exchange, 400, "{\"error\": \"Terrain features missing for Region " + regionId + ". Please generate them in-game.\"}");
                            return;
                        }

                        refCache = cache;
                        step = cache.step;
                        ScanPixel[][] pixels = cache.detailData;
                        double[][] slopes = cache.slopeData;
                        double[][] tpis = cache.tpiData;
                        int w = pixels.length;
                        int h = pixels[0].length;

                        // 动态采样步长，防止点太�?
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
                        sendResponse(exchange, 400, "{\"error\": \"Must provide either 'region_id' or 'territory_id'.\"}");
                        return;
                    }

                    // --- 后续处理：DBSCAN + ASCII ---
                    List<List<DBSCAN.Point>> clusters = DBSCAN.cluster(rawCandidates, step * 4.0, 5); // 稍微放宽点数要求

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

                        // 补充描述信息
                        // 如果�?refCache (Region模式)，用精确数据算均值；否则 (Territory模式)，用默认�?
                        double avgSlope = 0;
                        double avgTpi = 0;

                        // 只有�?Region 模式�?(refCache != null) 才能精确计算地形均�?
                        if (refCache != null) {
                            final RegionCache finalCache = refCache; // 显式声明�?final �?lambda 使用

                            avgSlope = cluster.stream().mapToDouble(p -> {
                                int gx = (p.x - finalCache.minX) / finalCache.step;
                                int gz = (p.z - finalCache.minZ) / finalCache.step;
                                // 边界检查防止越�?
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
                        if (avgTpi > 1.0) desc = "Ridge/Highland"; else if (avgTpi < -1.0) desc = "Valley/Basin"; else desc = "Plain";
                        if (avgSlope > 1.5) desc += " (Rugged)"; else if (avgSlope < 0.5) desc += " (Flat)";
                        clusterJson.addProperty("description", desc);

                        JsonObject metrics = new JsonObject();
                        metrics.addProperty("size", cluster.size());
                        metrics.addProperty("biome", cluster.get(0).data.biomeId());
                        clusterJson.add("metrics", metrics);

                        candidatesArr.add(clusterJson);
                        count++;
                    }

                    response.add("candidates", candidatesArr);
                    sendResponse(exchange, 200, gson.toJson(response));

                } catch (Exception e) { handleError(exchange, e); }
            });

            // API 4: Place
            server.createContext("/place", exchange -> {
                if ("POST".equals(exchange.getRequestMethod())) {
                    try {
                        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                        int x = json.get("x").getAsInt();
                        int z = json.get("z").getAsInt();
                        String id = json.get("id").getAsString();

                        StructurePlan.get().addStructure(x, z, id);
                        mcServer.execute(() -> {
                            ServerLevel level = mcServer.overworld();
                            net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(x, z);
                            if (level.hasChunk(cp.x, cp.z)) {
                                com.user.terra_script.world.StructureInjector.spawnStructure(level, cp, id);
                                StructurePlan.get().removeStructure(x, z);
                            }
                        });
                        sendResponse(exchange, 200, "{\"status\": \"planned\"}");
                    } catch (Exception e) { handleError(exchange, e); }
                } else { sendResponse(exchange, 405, "Only POST"); }
            });

            // API 5: Create Territory
            server.createContext("/create_territory", exchange -> {
                if ("POST".equals(exchange.getRequestMethod())) {
                    try {
                        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                        JsonObject json = JsonParser.parseString(body).getAsJsonObject();

                        String id = json.get("id").getAsString();
                        String name = json.get("name").getAsString();

                        // 1. 必填参数检查：如果没有 region_id，直接拒�?
                        if (!json.has("region_id")) {
                            sendResponse(exchange, 400, "{\"error\": \"Missing required parameter: region_id\"}");
                            return;
                        }

                        // 【新增】region_id
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
                        sendResponse(exchange, 200, "{\"status\": \"created\"}");
                    } catch (Exception e) { handleError(exchange, e); }
                }
            });

            // API 6: Get Territory Status
            server.createContext("/territory_status", exchange -> {
                try {
                    JsonObject root = new JsonObject();
                    var allResults = com.user.terra_script.world.TerritoryManager.getAllResults();

                    for (var res : allResults) {
                        JsonObject tObj = new JsonObject();
                        tObj.addProperty("id", res.config.id);
                        tObj.addProperty("name", res.config.name);

                        // 首都
                        JsonObject cap = new JsonObject();
                        cap.addProperty("x", res.config.capitalX);
                        cap.addProperty("z", res.config.capitalZ);
                        tObj.add("capital", cap);

                        if (res.stats != null) {
                            // 基础数据
                            tObj.addProperty("total_area", res.stats.area_pixels);

                            // Bounding Box
                            JsonObject bbox = new JsonObject();
                            bbox.addProperty("min_x", res.stats.minX);
                            bbox.addProperty("max_x", res.stats.maxX);
                            bbox.addProperty("min_z", res.stats.minZ);
                            bbox.addProperty("max_z", res.stats.maxZ);
                            tObj.add("bounds", bbox);

                            // 大陆分布
                            JsonObject cont = new JsonObject();
                            res.stats.continent_distribution.forEach((rid, pct) -> cont.addProperty(String.valueOf(rid), pct));
                            tObj.add("continent_distribution", cont);

                            // 邻国及方位计�?
                            JsonObject neighbors = new JsonObject();
                            for (String nid : res.stats.neighborIds) {
                                // 简单的方位计算：对方首都在我首都的哪个方向
                                var neighborRes = com.user.terra_script.world.TerritoryManager.getAllResults().stream()
                                        .filter(r -> r.config.id.equals(nid)).findFirst().orElse(null);

                                String dir = "Unknown";
                                if (neighborRes != null) {
                                    double dx = neighborRes.config.capitalX - res.config.capitalX;
                                    double dz = neighborRes.config.capitalZ - res.config.capitalZ;

                                    // 简单的 4 方向判断
                                    if (Math.abs(dx) > Math.abs(dz)) dir = dx > 0 ? "East" : "West";
                                    else dir = dz > 0 ? "South" : "North";
                                }
                                neighbors.addProperty(nid, dir);
                            }
                            tObj.add("neighbors", neighbors);

                            // 优势群系
                            JsonObject biomes = new JsonObject();
                            res.stats.biome_composition.forEach(biomes::addProperty);
                            tObj.add("biomes", biomes);
                        }
                        root.add(res.config.id, tObj);
                    }
                    sendResponse(exchange, 200, gson.toJson(root));
                } catch (Exception e) { handleError(exchange, e); }
            });

            
            // API: Freeze Status
            server.createContext("/freeze_status", exchange -> {
                try {
                    JsonObject res = new JsonObject();
                    res.addProperty("frozen", NationGenManager.SnapshotManager.hasSnapshot());
                    sendResponse(exchange, 200, gson.toJson(res));
                } catch (Exception e) { handleError(exchange, e); }
            });

            // API: Freeze Project (Stage 0)
            server.createContext("/freeze_project", exchange -> {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    sendResponse(exchange, 405, "Only POST");
                    return;
                }
                try {
                    CityProjectSnapshot.FreezeResult result = NationGenManager.SnapshotManager.freezeIfNotFrozen();
                    JsonObject res = new JsonObject();
                    res.addProperty("ok", result.ok);
                    res.addProperty("message", result.message);
                    int code = result.ok ? 200 : ("already frozen".equals(result.message) ? 409 : 400);
                    sendResponse(exchange, code, gson.toJson(res));
                } catch (Exception e) { handleError(exchange, e); }
            });
            // API: Stage 1 heightmap window
            server.createContext("/city_heightmap", exchange -> {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    sendResponse(exchange, 405, "Only POST");
                    return;
                }
                try {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                    String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
                    if (cityId == null || cityId.isBlank()) {
                        sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                        return;
                    }

                    int minX = json.has("min_x") ? json.get("min_x").getAsInt() : Integer.MIN_VALUE;
                    int minZ = json.has("min_z") ? json.get("min_z").getAsInt() : Integer.MIN_VALUE;
                    int w = json.has("width") ? json.get("width").getAsInt() : -1;
                    int h = json.has("height") ? json.get("height").getAsInt() : -1;

                    CityStage1BinaryIO.HeightData data = CityStage1BinaryIO.loadHeightData(cityId);
                    if (data == null) {
                        sendResponse(exchange, 404, "{\"error\": \"Heightmap not found for: " + cityId + "\"}");
                        return;
                    }

                    if (minX == Integer.MIN_VALUE || minZ == Integer.MIN_VALUE || w <= 0 || h <= 0) {
                        sendResponse(exchange, 400, "{\"error\": \"min_x/min_z/width/height required\"}");
                        return;
                    }

                    int startX = minX - data.originX;
                    int startZ = minZ - data.originZ;
                    if (startX < 0 || startZ < 0 || startX + w > data.width || startZ + h > data.height) {
                        sendResponse(exchange, 400, "{\"error\": \"Requested window out of bounds\"}");
                        return;
                    }

                    JsonObject res = new JsonObject();
                    res.addProperty("origin_x", minX);
                    res.addProperty("origin_z", minZ);
                    res.addProperty("width", w);
                    res.addProperty("height", h);

                    JsonArray rows = new JsonArray();
                    for (int z = 0; z < h; z++) {
                        JsonArray row = new JsonArray();
                        for (int x = 0; x < w; x++) {
                            row.add(data.heightMap[startX + x][startZ + z]);
                        }
                        rows.add(row);
                    }
                    res.add("heights", rows);

                    sendResponse(exchange, 200, gson.toJson(res));
                } catch (Exception e) { handleError(exchange, e); }
            });
            // API: Stage 1 data fetch
            server.createContext("/city_stage1_data", exchange -> {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    sendResponse(exchange, 405, "Only POST");
                    return;
                }
                try {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                    String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
                    if (cityId == null || cityId.isBlank()) {
                        sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                        return;
                    }

                    var data = NationGenManager.Stage1Manager.load(cityId);
                    if (data == null) {
                        sendResponse(exchange, 404, "{\"error\": \"Stage1 not found for: " + cityId + "\"}");
                        return;
                    }

                    sendResponse(exchange, 200, gson.toJson(data));
                } catch (Exception e) { handleError(exchange, e); }
            });

            // API: Stage 1 forbidden blocks
            server.createContext("/city_forbidden", exchange -> {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    sendResponse(exchange, 405, "Only POST");
                    return;
                }
                try {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                    String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
                    if (cityId == null || cityId.isBlank()) {
                        sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                        return;
                    }

                    var data = CityStage1BinaryIO.loadForbidden(cityId);
                    if (data == null) {
                        sendResponse(exchange, 404, "{\"error\": \"Forbidden data not found for: " + cityId + "\"}");
                        return;
                    }

                    sendResponse(exchange, 200, gson.toJson(data));
                } catch (Exception e) { handleError(exchange, e); }
            });

            // API: Stage 1 buildable groups
            server.createContext("/city_buildable_groups", exchange -> {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    sendResponse(exchange, 405, "Only POST");
                    return;
                }
                try {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                    String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
                    if (cityId == null || cityId.isBlank()) {
                        sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                        return;
                    }

                    var data = CityStage1BinaryIO.loadBuildableGroups(cityId);
                    if (data == null) {
                        sendResponse(exchange, 404, "{\"error\": \"Buildable groups not found for: " + cityId + "\"}");
                        return;
                    }

                    sendResponse(exchange, 200, gson.toJson(data));
                } catch (Exception e) { handleError(exchange, e); }
            });

            server.setExecutor(Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName("TerraScript-API");
                return t;
            }));
            server.start();
            System.out.println("[TerraScript] API Server started on port " + PORT);
        } catch (IOException e) { e.printStackTrace(); }

        // API 7: Create City
        server.createContext("/create_city", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    CityConfig config = gson.fromJson(body, CityConfig.class);

                    // 简单的校验
                    if (config.territoryId == null || config.targetChunkCount <= 0) {
                        sendResponse(exchange, 400, "{\"error\": \"Invalid parameters (territoryId, targetChunkCount)\"}");
                        return;
                    }

                    // 在主线程或通过 Manager 执行 (这里假设 Manager 内部处理了并发或就是纯数据计�?
                    CityInstance city = CityManager.get().createCity(config);

                    JsonObject res = new JsonObject();
                    res.addProperty("status", "created");
                    res.addProperty("city_id", city.id);
                    res.addProperty("actual_size", city.claimedChunks.size());

                    sendResponse(exchange, 200, gson.toJson(res));
                } catch (Exception e) { handleError(exchange, e); }
            }
        });
    }

    private static void stopHttpServer() { if (server != null) server.stop(0); }
    private static void handleError(com.sun.net.httpserver.HttpExchange exchange, Exception e) throws IOException {
        e.printStackTrace();
        sendResponse(exchange, 500, "{\"error\": \"" + e.getMessage() + "\"}");
    }
    private static void sendResponse(com.sun.net.httpserver.HttpExchange exchange, int code, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(bytes);
        os.close();
    }

    // 简单的筛选逻辑 (Territory模式下暂不强制校�?Slope/TPI 以保证性能和可用�?
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
        // ... grid ...
        obj.add("climate", climate);

        JsonObject eco = new JsonObject();
        // ... dom biomes ...
        obj.add("ecology", eco);

        // 注意：海洋通常没有 Detail 缓存，除非您特意�?scanRegion 编辑�?
        obj.addProperty("has_detail", holder.regionCacheMap.containsKey(r.id));

        return obj;
    }
}










