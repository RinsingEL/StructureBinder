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
                    if (holder.lastClusters == null) {
                        sendResponse(exchange, 200, "[]");
                        return;
                    }
                    JsonArray list = new JsonArray();
                    for (ScanRegion r : holder.lastClusters) {
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
                        JsonArray grid = new JsonArray();
                        if (r.climateGrid != null) {
                            for(float[] row : r.climateGrid) for(float val : row) grid.add(val);
                        }
                        climate.add("grid_4x4", grid);
                        obj.add("climate", climate);

                        JsonObject eco = new JsonObject();
                        JsonArray dom = new JsonArray();
                        if (r.dominantBiomes != null) r.dominantBiomes.forEach(dom::add);
                        eco.add("dominant", dom);
                        obj.add("ecology", eco);

                        // 标记该区域是否有地形详情缓存
                        obj.addProperty("has_detail", holder.regionCacheMap.containsKey(r.id));

                        list.add(obj);
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

            // API 3: Query Region
            server.createContext("/query_region", exchange -> {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    sendResponse(exchange, 405, "Method Not Allowed");
                    return;
                }
                try {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    JsonObject req = JsonParser.parseString(body).getAsJsonObject();

                    int regionId = req.get("region_id").getAsInt();
                    double minSlope = req.has("min_slope") ? req.get("min_slope").getAsDouble() : -1;
                    double maxSlope = req.has("max_slope") ? req.get("max_slope").getAsDouble() : 999;
                    double minTpi = req.has("min_tpi") ? req.get("min_tpi").getAsDouble() : -999;
                    double maxTpi = req.has("max_tpi") ? req.get("max_tpi").getAsDouble() : 999;
                    int limit = req.has("limit") ? req.get("limit").getAsInt() : 5;

                    var holder = ScanResultHolder.get();
                    RegionCache cache = holder.regionCacheMap.get(regionId);

                    if (cache == null) {
                        sendResponse(exchange, 404, "{\"error\": \"Region " + regionId + " data not in cache. Please open Region Editor for this region.\"}");
                        return;
                    }

                    // 如果数据缺失，直接报错，让用户去游戏里点一下
                    if (cache.slopeData == null || cache.tpiData == null) {
                        sendResponse(exchange, 400, "{\"error\": \"Terrain features (Slope/TPI) missing for Region " + regionId + ". Please open 'Region Editor' in-game and click 'View: Slope' and 'View: TPI' to generate them.\"}");
                        return;
                    }

                    JsonArray results = new JsonArray();
                    ScanPixel[][] pixels = cache.detailData;
                    double[][] slopes = cache.slopeData;
                    double[][] tpis = cache.tpiData;
                    int w = pixels.length;
                    int h = pixels[0].length;

                    // 动态步长优化：点太多时跳着采样
                    int step = Math.max(1, (w * h) / 10000);
                    List<JsonObject> candidates = new ArrayList<>();
                    int foundCount = 0;

                    for (int i = 0; i < w; i += step) {
                        for (int j = 0; j < h; j += step) {
                            if (pixels[i][j] == null || !pixels[i][j].isLand()) continue;
                            double s = slopes[i][j];
                            double t = tpis[i][j];

                            // 筛选符合条件的点
                            if (s >= minSlope && s <= maxSlope && t >= minTpi && t <= maxTpi) {
                                JsonObject p = new JsonObject();
                                p.addProperty("x", pixels[i][j].x());
                                p.addProperty("z", pixels[i][j].z());
                                p.addProperty("y", pixels[i][j].height());
                                p.addProperty("slope", s);
                                p.addProperty("tpi", t);
                                candidates.add(p);
                                foundCount++;
                            }
                            if (foundCount > 500) break; // 性能熔断
                        }
                        if (foundCount > 500) break;
                    }

                    Collections.shuffle(candidates);
                    candidates.stream().limit(limit).forEach(results::add);
                    sendResponse(exchange, 200, gson.toJson(results));

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

                        // 1. 必填参数检查：如果没有 region_id，直接拒绝
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

            server.setExecutor(Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName("TerraScript-API");
                return t;
            }));
            server.start();
            System.out.println("[TerraScript] API Server started on port " + PORT);
        } catch (IOException e) { e.printStackTrace(); }
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
}