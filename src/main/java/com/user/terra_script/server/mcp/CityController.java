package com.user.terra_script.server.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.user.terra_script.server.http.HttpUtil;
import com.user.terra_script.world.NationGenManager;
import com.user.terra_script.world.city.CityConfig;
import com.user.terra_script.world.city.CityInstance;
import com.user.terra_script.world.city.CityManager;
import com.user.terra_script.world.city.CityStage1BinaryIO;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

import java.io.IOException;

public class CityController {
    private static final int CENTER_WATER_PROBE_RADIUS = 8;
    private static final int CENTER_WATER_PROBE_STEP = 2;
    private static final double CENTER_WATER_RATIO_THRESHOLD = 0.60;

    private final Gson gson = new Gson();
    private final MinecraftServer mcServer;

    public CityController(MinecraftServer mcServer) {
        this.mcServer = mcServer;
    }

    public void handleCityHeightmap(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }

            int minX = json.has("min_x") ? json.get("min_x").getAsInt() : Integer.MIN_VALUE;
            int minZ = json.has("min_z") ? json.get("min_z").getAsInt() : Integer.MIN_VALUE;
            int w = json.has("width") ? json.get("width").getAsInt() : -1;
            int h = json.has("height") ? json.get("height").getAsInt() : -1;

            CityStage1BinaryIO.HeightData data = CityStage1BinaryIO.loadHeightData(cityId);
            if (data == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"Heightmap not found for: " + cityId + "\"}");
                return;
            }

            if (minX == Integer.MIN_VALUE || minZ == Integer.MIN_VALUE || w <= 0 || h <= 0) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"min_x/min_z/width/height required\"}");
                return;
            }

            int startX = minX - data.originX;
            int startZ = minZ - data.originZ;
            if (startX < 0 || startZ < 0 || startX + w > data.width || startZ + h > data.height) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Requested window out of bounds\"}");
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

            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityStage1Data(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }

            var data = NationGenManager.Stage1Manager.load(cityId);
            if (data == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"Stage1 not found for: " + cityId + "\"}");
                return;
            }

            HttpUtil.sendResponse(exchange, 200, gson.toJson(data));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityStage2Data(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }

            var data = NationGenManager.Stage2Manager.load(cityId);
            if (data == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"Stage2 not found for: " + cityId + "\"}");
                return;
            }

            HttpUtil.sendResponse(exchange, 200, gson.toJson(data));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityForbidden(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }

            var data = CityStage1BinaryIO.loadForbidden(cityId);
            if (data == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"Forbidden data not found for: " + cityId + "\"}");
                return;
            }

            HttpUtil.sendResponse(exchange, 200, gson.toJson(data));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityBuildableGroups(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }

            var data = CityStage1BinaryIO.loadBuildableGroups(cityId);
            if (data == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"Buildable groups not found for: " + cityId + "\"}");
                return;
            }

            HttpUtil.sendResponse(exchange, 200, gson.toJson(data));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCreateCity(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            CityConfig config = gson.fromJson(body, CityConfig.class);

            if (config == null || config.territoryId == null || config.territoryId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Invalid parameters (territoryId)\"}");
                return;
            }

            if (!config.allowWaterCity) {
                ServerLevel level = mcServer != null ? mcServer.overworld() : null;
                if (level != null) {
                    CenterWaterProbe probe = probeCenterWater(level, config.centerX, config.centerZ);
                    if (probe.centerIsWater || probe.waterRatio >= CENTER_WATER_RATIO_THRESHOLD) {
                        String error = String.format(
                                "Center appears to be water-heavy (center_is_water=%s, water_ratio=%.2f). " +
                                        "Pick another center or set allow_water_city=true.",
                                probe.centerIsWater,
                                probe.waterRatio
                        );
                        HttpUtil.sendResponse(exchange, 400, "{\"error\": \"" + escapeJson(error) + "\"}");
                        return;
                    }
                }
            }

            CityInstance city = CityManager.get().createCity(config);

            JsonObject res = new JsonObject();
            res.addProperty("status", "created");
            res.addProperty("city_id", city.id);
            res.addProperty("actual_size", city.claimedChunks.size());
            res.addProperty("blocks_total", city.claimedChunks.size() * 256);

            CityConfig.LayerLayout layout = city.getLayerLayout();
            int weightSum = 0;
            for (CityConfig.LayerConfig layer : layout.layers) {
                weightSum += Math.max(1, layer.weight);
            }
            res.addProperty("weight_sum", weightSum);

            int[] chunkPerLayer = new int[layout.layers.size()];
            for (CityInstance.LayerAssignment assignment : city.claimedChunks.values()) {
                if (assignment == null) continue;
                int idx = Math.max(0, Math.min(layout.layers.size() - 1, assignment.layerIndex));
                chunkPerLayer[idx]++;
            }

            JsonArray layerStats = new JsonArray();
            int totalChunks = Math.max(1, city.claimedChunks.size());
            for (int i = 0; i < layout.layers.size(); i++) {
                CityConfig.LayerConfig layer = layout.layerAt(i);
                int chunks = chunkPerLayer[i];
                JsonObject obj = new JsonObject();
                obj.addProperty("id", layer.name != null ? layer.name.toLowerCase().replace(' ', '_') : ("layer_" + i));
                obj.addProperty("name", layer.name);
                obj.addProperty("type", layer.type);
                obj.addProperty("weight", Math.max(1, layer.weight));
                obj.addProperty("is_wall", layer.isWall || layer.wallLayer || layer.wall != null);
                obj.addProperty("chunks", chunks);
                obj.addProperty("blocks", chunks * 256);
                obj.addProperty("ratio", chunks / (double) totalChunks);
                layerStats.add(obj);
            }
            res.add("layers", layerStats);

            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    private static CenterWaterProbe probeCenterWater(ServerLevel level, int centerX, int centerZ) {
        int waterSamples = 0;
        int totalSamples = 0;
        boolean centerIsWater = isSurfaceWater(level, centerX, centerZ);

        for (int dx = -CENTER_WATER_PROBE_RADIUS; dx <= CENTER_WATER_PROBE_RADIUS; dx += CENTER_WATER_PROBE_STEP) {
            for (int dz = -CENTER_WATER_PROBE_RADIUS; dz <= CENTER_WATER_PROBE_RADIUS; dz += CENTER_WATER_PROBE_STEP) {
                int x = centerX + dx;
                int z = centerZ + dz;
                if (isSurfaceWater(level, x, z)) {
                    waterSamples++;
                }
                totalSamples++;
            }
        }
        double ratio = totalSamples <= 0 ? 0.0 : (waterSamples / (double) totalSamples);
        return new CenterWaterProbe(centerIsWater, waterSamples, totalSamples, ratio);
    }

    private static boolean isSurfaceWater(ServerLevel level, int x, int z) {
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos pos = new BlockPos(x, surfaceY - 1, z);
        return !level.getFluidState(pos).isEmpty();
    }

    private static String escapeJson(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class CenterWaterProbe {
        final boolean centerIsWater;
        final int waterSamples;
        final int totalSamples;
        final double waterRatio;

        CenterWaterProbe(boolean centerIsWater, int waterSamples, int totalSamples, double waterRatio) {
            this.centerIsWater = centerIsWater;
            this.waterSamples = waterSamples;
            this.totalSamples = totalSamples;
            this.waterRatio = waterRatio;
        }
    }
}
