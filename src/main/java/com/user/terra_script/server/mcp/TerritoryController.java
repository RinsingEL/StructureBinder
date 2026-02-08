package com.user.terra_script.server.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.user.terra_script.server.http.HttpUtil;
import com.user.terra_script.territory.io.TerritoryRepository;
import com.user.terra_script.territory.io.TerritoryResultRepository;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    public void handleTerritoryT4Window(HttpExchange exchange, MinecraftServer server) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject req = JsonParser.parseString(body).getAsJsonObject();

            String territoryId = req.has("territory_id") ? req.get("territory_id").getAsString() : null;
            if (territoryId == null || territoryId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"territory_id is required\"}");
                return;
            }

            int centerX;
            int centerZ;
            if (req.has("center_x") && req.has("center_z")) {
                centerX = req.get("center_x").getAsInt();
                centerZ = req.get("center_z").getAsInt();
            } else {
                var live = com.user.terra_script.world.TerritoryManager.getAllResults().stream()
                        .filter(r -> r != null && r.config != null && territoryId.equals(r.config.id))
                        .findFirst();
                if (live.isEmpty()) {
                    HttpUtil.sendResponse(exchange, 400, "{\"error\": \"center_x/center_z missing and territory not found in memory\"}");
                    return;
                }
                centerX = live.get().config.capitalX;
                centerZ = live.get().config.capitalZ;
            }

            int radiusBlocks = req.has("radius_blocks") ? req.get("radius_blocks").getAsInt() : 256;
            int maxPoints = req.has("max_points") ? req.get("max_points").getAsInt() : 160;
            int maxBiomeSamples = req.has("max_biome_samples") ? req.get("max_biome_samples").getAsInt() : 3000;
            radiusBlocks = Math.max(16, Math.min(4096, radiusBlocks));
            maxPoints = Math.max(0, Math.min(2000, maxPoints));
            maxBiomeSamples = Math.max(0, Math.min(20000, maxBiomeSamples));

            Optional<byte[]> datOpt = TerritoryResultRepository.readT4Dat(server, territoryId);
            if (datOpt.isEmpty()) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"T4 dat not found for territory_id: " + territoryId + "\"}");
                return;
            }

            DecodedT4 decoded = decodeT4Dat(datOpt.get());
            List<CellRecord> inWindow = new ArrayList<>();
            int minX = centerX - radiusBlocks;
            int maxX = centerX + radiusBlocks;
            int minZ = centerZ - radiusBlocks;
            int maxZ = centerZ + radiusBlocks;
            for (CellRecord r : decoded.records) {
                if (r.x >= minX && r.x <= maxX && r.z >= minZ && r.z <= maxZ) {
                    inWindow.add(r);
                }
            }

            JsonObject res = new JsonObject();
            res.addProperty("step", "T4");
            res.addProperty("ok", true);
            res.addProperty("source", "artifact_t4_dat");
            res.addProperty("territory_id", territoryId);

            JsonObject window = new JsonObject();
            window.addProperty("center_x", centerX);
            window.addProperty("center_z", centerZ);
            window.addProperty("radius_blocks", radiusBlocks);
            window.addProperty("sample_step", decoded.step);
            window.addProperty("matched_cells", inWindow.size());
            window.addProperty("estimated_block_count", (long) inWindow.size() * decoded.step * decoded.step);
            res.add("window", window);

            JsonObject terrain = buildWindowTerrain(inWindow);
            res.add("terrain", terrain);

            JsonObject biomeComp = buildWindowBiomeComposition(server, inWindow, maxBiomeSamples);
            res.add("biome_composition_window", biomeComp);

            if (maxPoints > 0) {
                JsonArray samplePoints = new JsonArray();
                inWindow.stream()
                        .sorted(Comparator.comparingInt((CellRecord c) -> c.strategic).reversed())
                        .limit(maxPoints)
                        .forEach(c -> {
                            JsonObject p = new JsonObject();
                            p.addProperty("x", c.x);
                            p.addProperty("z", c.z);
                            p.addProperty("height", c.height);
                            p.addProperty("slope", c.slope);
                            p.addProperty("temperature", c.temperature);
                            p.addProperty("strategic", c.strategic);
                            samplePoints.add(p);
                        });
                res.add("samples", samplePoints);
            }

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

    private static JsonObject buildWindowTerrain(List<CellRecord> records) {
        JsonObject terrain = new JsonObject();
        if (records == null || records.isEmpty()) {
            terrain.addProperty("empty", true);
            return terrain;
        }
        int minH = Integer.MAX_VALUE, maxH = Integer.MIN_VALUE;
        double sumH = 0.0, sumS = 0.0, sumT = 0.0;
        for (CellRecord r : records) {
            if (r.height < minH) minH = r.height;
            if (r.height > maxH) maxH = r.height;
            sumH += r.height;
            sumS += r.slope;
            sumT += r.temperature;
        }
        int n = records.size();
        terrain.addProperty("avg_height", round3(sumH / n));
        terrain.addProperty("avg_slope", round3(sumS / n));
        terrain.addProperty("avg_temperature", round3(sumT / n));
        terrain.addProperty("min_height", minH);
        terrain.addProperty("max_height", maxH);
        terrain.addProperty("relief", maxH - minH);
        return terrain;
    }

    private static JsonObject buildWindowBiomeComposition(MinecraftServer server, List<CellRecord> records, int maxSamples) {
        JsonObject out = new JsonObject();
        if (server == null || records == null || records.isEmpty() || maxSamples <= 0) return out;
        ServerLevel level = server.overworld();
        if (level == null) return out;

        int sampleCount = Math.min(records.size(), maxSamples);
        int stride = Math.max(1, records.size() / sampleCount);
        Map<String, Integer> counts = new HashMap<>();
        int used = 0;
        for (int i = 0; i < records.size() && used < sampleCount; i += stride) {
            CellRecord r = records.get(i);
            BlockPos pos = new BlockPos(r.x, r.height, r.z);
            String biomeId = level.getBiome(pos).unwrapKey()
                    .map(k -> k.location().toString())
                    .orElse("unknown");
            counts.merge(biomeId, 1, Integer::sum);
            used++;
        }
        if (used <= 0) return out;

        final int usedSamples = used;
        counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(8)
                .forEach(e -> out.addProperty(e.getKey(), round3((double) e.getValue() / usedSamples)));
        return out;
    }

    private static DecodedT4 decodeT4Dat(byte[] bytes) throws Exception {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int version = in.readInt();
            String territoryId = in.readUTF();
            int step = in.readInt();
            int count = in.readInt();
            List<CellRecord> records = new ArrayList<>(Math.max(0, count));
            for (int i = 0; i < count; i++) {
                int x = in.readInt();
                int z = in.readInt();
                int h = in.readShort();
                float slope = in.readFloat();
                float temperature = in.readFloat();
                int distBorder = in.readShort();
                int distCapital = in.readShort();
                int strategic = in.readUnsignedByte();
                records.add(new CellRecord(x, z, h, slope, temperature, distBorder, distCapital, strategic));
            }
            return new DecodedT4(version, territoryId, step, records);
        }
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static final class DecodedT4 {
        final int version;
        final String territoryId;
        final int step;
        final List<CellRecord> records;

        DecodedT4(int version, String territoryId, int step, List<CellRecord> records) {
            this.version = version;
            this.territoryId = territoryId;
            this.step = step;
            this.records = records;
        }
    }

    private static final class CellRecord {
        final int x;
        final int z;
        final int height;
        final float slope;
        final float temperature;
        final int distBorder;
        final int distCapital;
        final int strategic;

        CellRecord(int x, int z, int height, float slope, float temperature, int distBorder, int distCapital, int strategic) {
            this.x = x;
            this.z = z;
            this.height = height;
            this.slope = slope;
            this.temperature = temperature;
            this.distBorder = distBorder;
            this.distCapital = distCapital;
            this.strategic = strategic;
        }
    }
}
