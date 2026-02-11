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
import com.user.terra_script.world.city.stage.c4.CitySemanticStages;
import com.user.terra_script.world.city.stage.c1.CityStage1BinaryIO;
import com.user.terra_script.world.city.stage.c1.CityStage1Processor;
import com.user.terra_script.world.city.stage.c6.C6FillStyle;
import com.user.terra_script.world.city.stage.c6.CityC6Stages;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

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

    public void handleCityC4Generate(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }

            CityInstance city = CityManager.get().getCity(cityId);
            if (city == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"City not found: " + cityId + "\"}");
                return;
            }

            CitySemanticStages.C4Plan plan = CitySemanticStages.generateC4(city);
            Path cityDir = resolveCityDir(cityId);
            CitySemanticStages.saveC4(cityDir, plan);

            JsonObject res = new JsonObject();
            res.addProperty("status", "ok");
            res.addProperty("step", "C4");
            res.addProperty("city_id", cityId);
            res.addProperty("district_count", plan.district_functions != null ? plan.district_functions.size() : 0);
            res.addProperty("file", cityDir.resolve(CitySemanticStages.C4_FILE).toString());
            res.addProperty("validated_file", cityDir.resolve(CitySemanticStages.C4_VALIDATED_FILE).toString());
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC4Data(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }
            Path cityDir = resolveCityDir(cityId);
            CitySemanticStages.C4Plan plan = CitySemanticStages.loadC4(cityDir);
            if (plan == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"C4 data not found for: " + cityId + "\"}");
                return;
            }
            HttpUtil.sendResponse(exchange, 200, gson.toJson(plan));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC5Generate(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }
            CityInstance city = CityManager.get().getCity(cityId);
            if (city == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"City not found: " + cityId + "\"}");
                return;
            }

            Path cityDir = resolveCityDir(cityId);
            CitySemanticStages.C4Plan c4Plan = CitySemanticStages.loadC4(cityDir);
            if (c4Plan == null) {
                c4Plan = CitySemanticStages.generateC4(city);
                CitySemanticStages.saveC4(cityDir, c4Plan);
            }

            boolean crossLayerMerge = json.has("cross_layer_merge") && json.get("cross_layer_merge").getAsBoolean();
            CitySemanticStages.C5Groups groups = CitySemanticStages.generateC5(city, c4Plan, crossLayerMerge);
            CitySemanticStages.saveC5(cityDir, groups);

            JsonObject res = new JsonObject();
            res.addProperty("status", "ok");
            res.addProperty("step", "C5");
            res.addProperty("city_id", cityId);
            res.addProperty("group_count", groups.groups != null ? groups.groups.size() : 0);
            res.addProperty("cross_layer_merge", crossLayerMerge);
            res.addProperty("file", cityDir.resolve(CitySemanticStages.C5_FILE).toString());
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC5Data(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }
            Path cityDir = resolveCityDir(cityId);
            CitySemanticStages.C5Groups groups = CitySemanticStages.loadC5(cityDir);
            if (groups == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"C5 data not found for: " + cityId + "\"}");
                return;
            }
            HttpUtil.sendResponse(exchange, 200, gson.toJson(groups));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC6Generate(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }
            C6FillStyle fillStyle = C6FillStyle.parseOrDefault(
                    json.has("fill_style") ? json.get("fill_style").getAsString() : null,
                    C6FillStyle.PLAZA_RING
            );

            CityInstance city = CityManager.get().getCity(cityId);
            if (city == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"City not found: " + cityId + "\"}");
                return;
            }

            Path cityDir = resolveCityDir(cityId);
            CitySemanticStages.C5Groups c5 = CitySemanticStages.loadC5(cityDir);
            if (c5 == null) {
                CitySemanticStages.C4Plan c4 = CitySemanticStages.loadC4(cityDir);
                if (c4 == null) {
                    c4 = CitySemanticStages.generateC4(city);
                    CitySemanticStages.saveC4(cityDir, c4);
                }
                c5 = CitySemanticStages.generateC5(city, c4, false);
                CitySemanticStages.saveC5(cityDir, c5);
            }

            CityStage1BinaryIO.HeightData heightData = CityStage1BinaryIO.loadHeightData(cityId);
            List<List<CityStage1Processor.BlockCoord>> buildableGroups = CityStage1BinaryIO.loadBuildableGroups(cityId);
            if (heightData == null || buildableGroups == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"Stage1 binary data not found for: " + cityId + "\"}");
                return;
            }

            CityC6Stages.C6Bundle bundle = CityC6Stages.generate(city, c5, heightData, buildableGroups, fillStyle);
            CityC6Stages.save(cityDir, bundle);

            JsonObject res = new JsonObject();
            res.addProperty("status", "ok");
            res.addProperty("step", "C6");
            res.addProperty("city_id", cityId);
            res.addProperty("fill_style", fillStyle.name());
            res.addProperty("area_count", bundle.summary != null && bundle.summary.areas != null ? bundle.summary.areas.size() : 0);
            res.addProperty("plan_count", bundle.layout != null && bundle.layout.plans != null ? bundle.layout.plans.size() : 0);
            res.addProperty("indexed_block_count", bundle.indexed_block_count);
            res.addProperty("summary_file", cityDir.resolve(CityC6Stages.C6_SUMMARY_FILE).toString());
            res.addProperty("layout_file", cityDir.resolve(CityC6Stages.C6_LAYOUT_FILE).toString());
            res.addProperty("index_file", cityDir.resolve(CityC6Stages.C6_INDEX_FILE).toString());
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC6Data(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }
            Path cityDir = resolveCityDir(cityId);
            CityC6Stages.C6Summary summary = CityC6Stages.loadSummary(cityDir);
            CityC6Stages.C6Layout layout = CityC6Stages.loadLayout(cityDir);
            if (summary == null && layout == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"C6 data not found for: " + cityId + "\"}");
                return;
            }
            JsonObject res = new JsonObject();
            res.addProperty("step", "C6");
            res.addProperty("ok", true);
            res.add("summary", gson.toJsonTree(summary));
            res.add("layout", gson.toJsonTree(layout));
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC6PaveStone(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }

            String groupId = json.has("group_id") ? json.get("group_id").getAsString() : null;
            String buildAreaId = json.has("build_area_id") ? json.get("build_area_id").getAsString() : null;
            boolean squareOnly = json.has("square_only") && json.get("square_only").getAsBoolean();
            int squareSize = json.has("square_size") ? Math.max(3, Math.min(32, json.get("square_size").getAsInt())) : 8;
            int squareCount = json.has("square_count") ? Math.max(1, Math.min(128, json.get("square_count").getAsInt())) : 16;

            Path cityDir = resolveCityDir(cityId);
            CityC6Stages.C6Summary summary = CityC6Stages.loadSummary(cityDir);
            Map<Long, Integer> index = CityC6Stages.loadIndex(cityDir);
            if (summary == null || index == null || index.isEmpty()) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"C6 summary/index not found for: " + cityId + "\"}");
                return;
            }
            if (mcServer == null) {
                HttpUtil.sendResponse(exchange, 500, "{\"error\": \"Minecraft server unavailable\"}");
                return;
            }
            ServerLevel level = mcServer.overworld();
            if (level == null) {
                HttpUtil.sendResponse(exchange, 500, "{\"error\": \"Overworld unavailable\"}");
                return;
            }

            Set<Integer> targetAreaIds = new HashSet<>();
            for (CityC6Stages.BuildAreaSummary area : summary.areas) {
                if (area == null) continue;
                if (buildAreaId != null && !buildAreaId.isBlank() && !buildAreaId.equals(area.build_area_id)) continue;
                if (groupId != null && !groupId.isBlank() && !groupId.equals(area.group_id)) continue;
                targetAreaIds.add(area.build_area_numeric_id);
            }
            if (targetAreaIds.isEmpty()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"No matched C6 area by group_id/build_area_id\"}");
                return;
            }

            Map<Integer, CityC6Stages.BuildAreaSummary> areaSummaryById = new java.util.HashMap<>();
            for (CityC6Stages.BuildAreaSummary area : summary.areas) {
                if (area == null) continue;
                areaSummaryById.put(area.build_area_numeric_id, area);
            }

            Set<Long> targetBlocks = new HashSet<>();
            for (Map.Entry<Long, Integer> entry : index.entrySet()) {
                Integer areaId = entry.getValue();
                if (areaId == null || !targetAreaIds.contains(areaId)) continue;
                targetBlocks.add(entry.getKey());
            }
            if (targetBlocks.isEmpty()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"No target blocks found for matched area(s)\"}");
                return;
            }

            final int[] scanned = {0};
            final int[] changed = {0};
            final int[] placedSquares = {0};
            CountDownLatch latch = new CountDownLatch(1);
            mcServer.execute(() -> {
                try {
                    if (!squareOnly) {
                        for (long key : targetBlocks) {
                            scanned[0]++;
                            int x = (int) (key >> 32);
                            int z = (int) (long) key;
                            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                            if (y <= level.getMinBuildHeight()) continue;
                            BlockPos pos = new BlockPos(x, y, z);
                            if (!level.getBlockState(pos).is(Blocks.STONE)) {
                                level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
                                changed[0]++;
                            }
                        }
                    } else {
                        List<long[]> candidateCenters = new java.util.ArrayList<>();
                        for (Integer areaId : targetAreaIds) {
                            CityC6Stages.BuildAreaSummary area = areaSummaryById.get(areaId);
                            if (area == null || area.centroid == null) continue;
                            int cx = (int) Math.round(area.centroid.x);
                            int cz = (int) Math.round(area.centroid.z);
                            candidateCenters.add(new long[]{cx, cz});
                        }

                        int ring = 0;
                        int maxRing = 24;
                        int spacing = squareSize + 2;
                        while (placedSquares[0] < squareCount && ring <= maxRing) {
                            for (long[] base : candidateCenters) {
                                if (placedSquares[0] >= squareCount) break;
                                int bx = (int) base[0];
                                int bz = (int) base[1];
                                int[][] offsets = {
                                        {0, 0},
                                        {ring, 0}, {-ring, 0}, {0, ring}, {0, -ring},
                                        {ring, ring}, {ring, -ring}, {-ring, ring}, {-ring, -ring}
                                };
                                for (int[] off : offsets) {
                                    if (placedSquares[0] >= squareCount) break;
                                    int cx = bx + off[0] * spacing;
                                    int cz = bz + off[1] * spacing;
                                    if (!paintSquare(level, targetBlocks, cx, cz, squareSize, scanned, changed)) continue;
                                    placedSquares[0]++;
                                }
                            }
                            ring++;
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
            try {
                latch.await();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                HttpUtil.sendResponse(exchange, 500, "{\"error\": \"Interrupted while paving\"}");
                return;
            }

            JsonObject res = new JsonObject();
            res.addProperty("status", "ok");
            res.addProperty("step", "C6");
            res.addProperty("action", "pave_stone");
            res.addProperty("city_id", cityId);
            if (groupId != null && !groupId.isBlank()) res.addProperty("group_id", groupId);
            if (buildAreaId != null && !buildAreaId.isBlank()) res.addProperty("build_area_id", buildAreaId);
            res.addProperty("matched_area_count", targetAreaIds.size());
            res.addProperty("scanned_blocks", scanned[0]);
            res.addProperty("changed_blocks", changed[0]);
            res.addProperty("square_only", squareOnly);
            if (squareOnly) {
                res.addProperty("square_size", squareSize);
                res.addProperty("requested_square_count", squareCount);
                res.addProperty("placed_square_count", placedSquares[0]);
            }
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    private static boolean paintSquare(
            ServerLevel level,
            Set<Long> targetBlocks,
            int centerX,
            int centerZ,
            int size,
            int[] scanned,
            int[] changed
    ) {
        int half = size / 2;
        int minX = centerX - half;
        int minZ = centerZ - half;
        int maxX = minX + size - 1;
        int maxZ = minZ + size - 1;

        int total = 0;
        int inside = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                total++;
                if (targetBlocks.contains(packBlock(x, z))) inside++;
            }
        }
        if (inside < Math.max(1, (int) (total * 0.80))) return false;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!targetBlocks.contains(packBlock(x, z))) continue;
                scanned[0]++;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                if (y <= level.getMinBuildHeight()) continue;
                BlockPos pos = new BlockPos(x, y, z);
                if (!level.getBlockState(pos).is(Blocks.STONE)) {
                    level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
                    changed[0]++;
                }
            }
        }
        return true;
    }

    private static long packBlock(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
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

    private Path resolveCityDir(String cityId) {
        if (mcServer != null) {
            return mcServer.getWorldPath(LevelResource.ROOT)
                    .resolve("terra_script")
                    .resolve("cities")
                    .resolve(cityId);
        }
        return java.nio.file.Paths.get("terra_script", "cities", cityId);
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
