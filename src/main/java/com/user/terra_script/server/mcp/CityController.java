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

import java.io.IOException;

public class CityController {
    private final Gson gson = new Gson();

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

            if (config.territoryId == null || config.targetChunkCount <= 0) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Invalid parameters (territoryId, targetChunkCount)\"}");
                return;
            }

            CityInstance city = CityManager.get().createCity(config);

            JsonObject res = new JsonObject();
            res.addProperty("status", "created");
            res.addProperty("city_id", city.id);
            res.addProperty("actual_size", city.claimedChunks.size());

            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }
}
