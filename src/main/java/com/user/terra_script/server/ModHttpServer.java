package com.user.terra_script.server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpServer;
import com.user.terra_script.client.data.ScanResultHolder;
import com.user.terra_script.config.StructurePlan;
import com.user.terra_script.util.StructureDiscovery;
import com.user.terra_script.util.ScanDataIO;
import com.user.terra_script.server.mcp.TerritoryController;
import com.user.terra_script.server.mcp.WorldController;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Executors;
import com.user.terra_script.world.city.CityConfig;
import com.user.terra_script.world.city.CityManager;
import com.user.terra_script.world.city.CityInstance;
import com.user.terra_script.world.NationGenManager;
import com.user.terra_script.world.city.CityStage1BinaryIO;
import com.user.terra_script.world.city.CityProjectSnapshot;
import java.nio.file.Path;

@Mod.EventBusSubscriber(modid = "terra_script")
public class ModHttpServer {

    private static HttpServer server;
    private static MinecraftServer mcServer;
    private static final int PORT = 5000;
    private static final Gson gson = new Gson();
    private static final Object LOG_LOCK = new Object();
    private static final String MCP_LOG_FILE = "terra_script_mcp_log.jsonl";

    @SubscribeEvent
    public static void onServerStart(ServerStartedEvent event) {
        mcServer = event.getServer();
        ScanDataIO.setWorldRoot(mcServer.getWorldPath(LevelResource.ROOT));
        ScanDataIO.loadInto(ScanResultHolder.get());
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
                    JsonObject res = WorldController.buildContinents(ScanResultHolder.get());
                    sendResponse(exchange, 200, gson.toJson(res));
                } catch (Exception e) { handleError(exchange, e); }
            });

            // API 1.5: World Atlas (W3 atlas + W4 summary)
            server.createContext("/world_atlas", exchange -> {
                try {
                    JsonObject res = WorldController.buildWorldAtlas(mcServer);
                    sendResponse(exchange, 200, gson.toJson(res));
                } catch (Exception e) { handleError(exchange, e); }
            });

            // API 1.6: World Summary (W4_WorldSummary.json)
            server.createContext("/world_summary", exchange -> {
                try {
                    JsonObject res = WorldController.buildWorldSummary(mcServer);
                    sendResponse(exchange, 200, gson.toJson(res));
                } catch (Exception e) { handleError(exchange, e); }
            });

            // API 1.7: Terrain Summary (W4_TerrainSummary.json)
            server.createContext("/terrain_summary", exchange -> {
                try {
                    JsonObject res = WorldController.buildTerrainSummary(mcServer);
                    sendResponse(exchange, 200, gson.toJson(res));
                } catch (Exception e) { handleError(exchange, e); }
            });

            // API 2: Structures
            server.createContext("/structures", exchange -> {
                try {
                    var list = StructureDiscovery.scanAllStructures(mcServer.overworld());
                    sendResponse(exchange, 200, gson.toJson(list));
                } catch (Exception e) { handleError(exchange, e); }
            });

            // API T1: Submit Territory Blueprint
            server.createContext("/t1_blueprint", exchange -> {
                TerritoryController.handleT1Blueprint(exchange, mcServer);
            });

            // API 3: Query Region (双模：大�?国度 + 聚类 + ASCII 地图)
            server.createContext("/query_region", exchange -> {
                TerritoryController.handleQueryRegion(exchange, mcServer);
            });

            // API 4: Place
            server.createContext("/place", exchange -> {
                if ("POST".equals(exchange.getRequestMethod())) {
                    try {
                        String body = readRequestBody(exchange);
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
                } else { sendResponse(exchange, 405, "{\"error\": \"Only POST\"}"); }
            });

            // API 5: Create Territory
            server.createContext("/create_territory", exchange -> {
                if ("POST".equals(exchange.getRequestMethod())) {
                    try {
                        String body = readRequestBody(exchange);
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
                } else { sendResponse(exchange, 405, "{\"error\": \"Only POST\"}"); }
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
                    sendResponse(exchange, 405, "{\"error\": \"Only POST\"}");
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
                    sendResponse(exchange, 405, "{\"error\": \"Only POST\"}");
                    return;
                }
                try {
                    String body = readRequestBody(exchange);
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
                    sendResponse(exchange, 405, "{\"error\": \"Only POST\"}");
                    return;
                }
                try {
                    String body = readRequestBody(exchange);
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

            // API: Stage 2 data fetch
            server.createContext("/city_stage2_data", exchange -> {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    sendResponse(exchange, 405, "{\"error\": \"Only POST\"}");
                    return;
                }
                try {
                    String body = readRequestBody(exchange);
                    JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                    String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
                    if (cityId == null || cityId.isBlank()) {
                        sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                        return;
                    }

                    var data = NationGenManager.Stage2Manager.load(cityId);
                    if (data == null) {
                        sendResponse(exchange, 404, "{\"error\": \"Stage2 not found for: " + cityId + "\"}");
                        return;
                    }

                    sendResponse(exchange, 200, gson.toJson(data));
                } catch (Exception e) { handleError(exchange, e); }
            });

            // API: Stage 1 forbidden blocks
            server.createContext("/city_forbidden", exchange -> {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    sendResponse(exchange, 405, "{\"error\": \"Only POST\"}");
                    return;
                }
                try {
                    String body = readRequestBody(exchange);
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
                    sendResponse(exchange, 405, "{\"error\": \"Only POST\"}");
                    return;
                }
                try {
                    String body = readRequestBody(exchange);
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
                    String body = readRequestBody(exchange);
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
            } else { sendResponse(exchange, 405, "{\"error\": \"Only POST\"}"); }
        });
    }

    private static void stopHttpServer() { if (server != null) server.stop(0); }
    private static void handleError(com.sun.net.httpserver.HttpExchange exchange, Exception e) throws IOException {
        e.printStackTrace();
        sendResponse(exchange, 500, "{\"error\": \"" + e.getMessage() + "\"}");
    }
    private static void sendResponse(com.sun.net.httpserver.HttpExchange exchange, int code, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        } finally {
            logExchange(exchange, code, response);
        }
    }
    private static String readRequestBody(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        exchange.setAttribute("mcp_request_body", body);
        return body;
    }
    private static void logExchange(com.sun.net.httpserver.HttpExchange exchange, int code, String response) {
        try {
            JsonObject entry = new JsonObject();
            entry.addProperty("timestamp", System.currentTimeMillis());
            entry.addProperty("method", exchange.getRequestMethod());
            entry.addProperty("path", exchange.getRequestURI().getPath());
            String query = exchange.getRequestURI().getQuery();
            if (query != null && !query.isBlank()) entry.addProperty("query", query);
            Object body = exchange.getAttribute("mcp_request_body");
            entry.add("request", tryParseJson(body != null ? body.toString() : null));
            entry.addProperty("status", code);
            entry.add("response", tryParseJson(response));
            if (exchange.getRemoteAddress() != null) {
                entry.addProperty("remote", exchange.getRemoteAddress().toString());
            }
            entry.addProperty("source", "http");

            Path logPath = FMLPaths.GAMEDIR.get().resolve(MCP_LOG_FILE);
            String line = gson.toJson(entry) + System.lineSeparator();
            synchronized (LOG_LOCK) {
                Files.writeString(logPath, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    private static JsonElement tryParseJson(String text) {
        if (text == null || text.isBlank()) return JsonNull.INSTANCE;
        try { return JsonParser.parseString(text); } catch (Exception e) { return new JsonPrimitive(text); }
    }

    // 简单的筛选逻辑 (Territory模式下暂不强制校�?Slope/TPI 以保证性能和可用�?
    
}

