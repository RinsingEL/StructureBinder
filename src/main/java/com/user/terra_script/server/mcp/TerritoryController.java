package com.user.terra_script.server.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.user.terra_script.server.http.HttpUtil;
import com.user.terra_script.territory.io.TerritoryRepository;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;

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

    public void handleCreateTerritory(HttpExchange exchange) throws IOException {
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
            HttpUtil.sendResponse(exchange, 200, "{\"status\": \"created\"}");
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleTerritoryStatus(HttpExchange exchange) throws IOException {
        try {
            JsonObject root = new JsonObject();
            var allResults = com.user.terra_script.world.TerritoryManager.getAllResults();

            for (var res : allResults) {
                JsonObject tObj = new JsonObject();
                tObj.addProperty("id", res.config.id);
                tObj.addProperty("name", res.config.name);

                JsonObject cap = new JsonObject();
                cap.addProperty("x", res.config.capitalX);
                cap.addProperty("z", res.config.capitalZ);
                tObj.add("capital", cap);

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
                }
                root.add(res.config.id, tObj);
            }
            HttpUtil.sendResponse(exchange, 200, GSON.toJson(root));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }
}
