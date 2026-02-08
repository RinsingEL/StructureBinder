package com.user.terra_script.server.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.user.terra_script.server.http.HttpUtil;
import com.user.terra_script.territory.io.TerritoryRepository;
import com.user.terra_script.territory.io.TerritoryResultRepository;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
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

    public void handleTerritoryStatus(HttpExchange exchange) throws IOException {
        try {
            com.user.terra_script.world.TerritoryManager.ensureLoaded();
            JsonObject root = new JsonObject();
            var allResults = com.user.terra_script.world.TerritoryManager.getAllResults();

            if (allResults == null || allResults.isEmpty()) {
                for (var cfg : com.user.terra_script.world.TerritoryManager.getRegisteredFactions()) {
                    JsonObject tObj = new JsonObject();
                    tObj.addProperty("id", cfg.id);
                    tObj.addProperty("name", cfg.name);
                    JsonObject cap = new JsonObject();
                    cap.addProperty("x", cfg.capitalX);
                    cap.addProperty("z", cfg.capitalZ);
                    tObj.add("capital", cap);
                    tObj.addProperty("region_id", cfg.regionId);
                    tObj.addProperty("power", cfg.maxPower);
                    tObj.addProperty("expansion_executed", false);
                    root.add(cfg.id, tObj);
                }
                HttpUtil.sendResponse(exchange, 200, GSON.toJson(root));
                return;
            }

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

    public void handleTerritorySummary(HttpExchange exchange, MinecraftServer server) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "GET")) return;
        try {
            String territoryId = getQueryParam(exchange, "territoryId");
            if (territoryId == null || territoryId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"territoryId query parameter is required\"}");
                return;
            }

            Optional<JsonObject> stored = TerritoryResultRepository.readSummary(server, territoryId);
            if (stored.isEmpty()) {
                var live = com.user.terra_script.world.TerritoryManager.getAllResults().stream()
                        .filter(r -> r != null && r.config != null && territoryId.equals(r.config.id))
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
}
