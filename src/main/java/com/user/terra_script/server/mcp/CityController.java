package com.user.terra_script.server.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.user.terra_script.config.CityGenerationConfig;
import com.user.terra_script.world.city.stage.CityGroupPathUtil;
import com.user.terra_script.world.city.stage.GroupStepStateUtil;
import com.user.terra_script.world.city.stage.c6.CityC6Validation;
import com.user.terra_script.world.city.stage.c7.CityC7Validation;
import com.user.terra_script.server.http.HttpUtil;
import com.user.terra_script.world.NationGenManager;
import com.user.terra_script.world.city.CityConfig;
import com.user.terra_script.world.city.CityInstance;
import com.user.terra_script.world.city.CityManager;
import com.user.terra_script.world.city.stage.c4.CitySemanticStages;
import com.user.terra_script.world.city.stage.c4.CityC5ModulePreviewExporter;
import com.user.terra_script.world.city.stage.c4.CityC5GroupTerrainPreviewExporter;
import com.user.terra_script.world.city.stage.c1.CityStage1BinaryIO;
import com.user.terra_script.world.city.stage.c1.CityStage1Processor;
import com.user.terra_script.world.city.stage.c2.CityC2ScanBinaryIO;
import com.user.terra_script.world.city.stage.c2.CityC3PolygonPreviewExporter;
import com.user.terra_script.world.city.stage.c2.CityC2SatellitePreviewExporter;
import com.user.terra_script.world.city.stage.c2.CityC3OwnershipIO;
import com.user.terra_script.world.city.stage.c6.C6FillStyle;
import com.user.terra_script.world.city.stage.c6.CityC6Stages;
import com.user.terra_script.world.city.stage.c6.CityC6BuildAreaPreviewExporter;
import com.user.terra_script.world.city.stage.c6.CityC6RectPlacementPreviewExporter;
import com.user.terra_script.world.city.stage.c6.CityC6GroupPreviewExporter;
import com.user.terra_script.world.city.stage.c7.CityC7Stages;
import com.user.terra_script.world.city.stage.c8.CityC8Stages;
import com.user.terra_script.world.city.stage.c8.CityC8ArrangementPreviewExporter;
import com.user.terra_script.world.city.stage.c9.CityC9Stages;
import com.user.terra_script.world.city.stage.c9.CityC9BuildQueue;
import com.user.terra_script.world.city.stage.c9.CityC9PlacementPreviewExporter;
import com.user.terra_script.domain.world.scan.ScanPixel;
import com.user.terra_script.domain.world.scan.service.SatelliteScanner;
import com.user.terra_script.runtime.context.RuntimeLogContext;
import com.user.terra_script.runtime.log.RuntimeLogEvent;
import com.user.terra_script.runtime.log.RuntimeLogger;
import com.user.terra_script.world.city.CityBuildQueueExecutor;
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
import java.util.ArrayList;
import java.nio.file.Path;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

public class CityController {
    private static final int CENTER_WATER_PROBE_RADIUS = 8;
    private static final int CENTER_WATER_PROBE_STEP = 2;
    private static final double CENTER_WATER_RATIO_THRESHOLD = 0.60;
    private static final int C2_SCAN_DEFAULT_STEP = 1;
    private static final int C2_SCAN_DEFAULT_PADDING_BLOCKS = 64;
    // Serialize heavy C2 scans across all cities to avoid concurrent scanner pressure.
    private static final ReentrantLock C2_SCAN_LOCK = new ReentrantLock(true);
    private static final AtomicReference<String> C2_ACTIVE_CITY = new AtomicReference<>(null);
    private static final long C2_MEMORY_CACHE_TTL_MS = 10 * 60 * 1000L;
    private static final Map<String, C2CacheEntry> C2_SCAN_CACHE = new ConcurrentHashMap<>();

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

    public void handleCityC2Generate(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }
            String taskId = "city_c2_" + cityId;
            RuntimeLogger logger = RuntimeLogger.forServer(
                    mcServer,
                    RuntimeLogContext.builder()
                            .domain("city")
                            .scope("task")
                            .taskId(taskId)
                            .stageId("C2")
                            .cityId(cityId)
                            .build()
            );
            JsonObject requestDetails = new JsonObject();
            requestDetails.addProperty("city_id", cityId);
            if (json.has("scan_step")) requestDetails.addProperty("scan_step", json.get("scan_step").getAsInt());
            if (json.has("scan_padding_blocks")) requestDetails.addProperty("scan_padding_blocks", json.get("scan_padding_blocks").getAsInt());
            logger.info(RuntimeLogEvent.TASK_STARTED, "Starting city C2 generation.", requestDetails);
            if (mcServer == null || mcServer.overworld() == null) {
                JsonObject errorDetails = new JsonObject();
                errorDetails.addProperty("city_id", cityId);
                logger.error(RuntimeLogEvent.TASK_FAILED, "Minecraft server/overworld unavailable.", errorDetails);
                HttpUtil.sendResponse(exchange, 500, "{\"error\": \"Minecraft server/overworld unavailable\"}");
                return;
            }

            long waitStart = System.currentTimeMillis();
            C2_SCAN_LOCK.lock();
            long waitedMs = Math.max(0L, System.currentTimeMillis() - waitStart);
            C2_ACTIVE_CITY.set(cityId);
            try {
                var result = NationGenManager.Stage1Manager.computeAndSave(mcServer.overworld(), cityId);
                if (result == null) {
                    HttpUtil.sendResponse(exchange, 404, "{\"error\": \"City not found: " + cityId + "\"}");
                    return;
                }

                CityInstance city = CityManager.get().getCity(cityId);
                if (city == null) {
                    HttpUtil.sendResponse(exchange, 404, "{\"error\": \"City not found after C2 compute: " + cityId + "\"}");
                    return;
                }

                int scanStep = json.has("scan_step") ? Math.max(1, json.get("scan_step").getAsInt()) : C2_SCAN_DEFAULT_STEP;
                int scanPadding = json.has("scan_padding_blocks")
                        ? Math.max(0, json.get("scan_padding_blocks").getAsInt())
                        : C2_SCAN_DEFAULT_PADDING_BLOCKS;
                CityScanBounds scanBounds = computeCityScanBounds(city, scanPadding);
                C2ScanResolved resolved = resolveC2ScanData(
                        cityId,
                        scanBounds,
                        scanStep,
                        mcServer.overworld()
                );
                ScanPixel[][] scanned = resolved.map;
                File c2ScanFile = resolved.file;

                JsonObject preview = CityC2SatellitePreviewExporter.export(
                        mcServer,
                        cityId,
                        scanned,
                        scanBounds.minX,
                        scanBounds.minZ,
                        scanStep,
                        scanBounds.width,
                        scanBounds.height,
                        city.config.centerX,
                        city.config.centerZ,
                        city.claimedChunks
                );

                JsonObject res = new JsonObject();
                res.addProperty("status", "ok");
                res.addProperty("step", "C2");
                res.addProperty("city_id", cityId);
                res.addProperty("district_count", result.buildableStats != null ? result.buildableStats.size() : 0);
                res.addProperty("buildable_group_count", result.buildableGroups != null ? result.buildableGroups.size() : 0);
                res.addProperty("scan_step", scanStep);
                res.addProperty("scan_padding_blocks", scanPadding);
                res.addProperty("scan_data_file", c2ScanFile.getName());
                res.addProperty("scan_serialized", true);
                res.addProperty("scan_queue_wait_ms", waitedMs);
                res.addProperty("scan_cache_hit", resolved.cacheHit);
                res.addProperty("scan_cache_source", resolved.cacheSource);
                res.addProperty("task_id", taskId);
                res.addProperty("state", "SUCCEEDED");
                res.add("satellite_preview", preview);
                res.addProperty("ai_should_pause", true);
                res.addProperty("next_action", "STOP_CURRENT_STEP_AND_REVIEW_C2_SATELLITE_PREVIEW");
                res.addProperty("message", "C2 completed and city-local satellite preview is generated. Pause current step, save, and review preview before continuing.");
                logger.info(RuntimeLogEvent.TASK_COMPLETED, "City C2 generation completed.", res);
                HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
            } finally {
                C2_ACTIVE_CITY.set(null);
                C2_SCAN_LOCK.unlock();
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            JsonObject errorDetails = new JsonObject();
            errorDetails.addProperty("error", e.getMessage() != null ? e.getMessage() : "Invalid city request");
            RuntimeLogger.forServer(
                    mcServer,
                    RuntimeLogContext.builder()
                            .domain("city")
                            .scope("task")
                            .stageId("C2")
                            .build()
            ).error(RuntimeLogEvent.TASK_FAILED, "City C2 generation failed.", errorDetails);
            HttpUtil.sendResponse(exchange, 400, "{\"error\": \"" + escapeJson(e.getMessage() != null ? e.getMessage() : "Invalid city request") + "\"}");
        } catch (Exception e) {
            JsonObject errorDetails = new JsonObject();
            errorDetails.addProperty("error", e.getMessage() != null ? e.getMessage() : "unknown");
            RuntimeLogger.forServer(
                    mcServer,
                    RuntimeLogContext.builder()
                            .domain("city")
                            .scope("task")
                            .stageId("C2")
                            .build()
            ).error(RuntimeLogEvent.TASK_FAILED, "City C2 generation crashed.", errorDetails);
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

    public void handleCityC3Generate(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }

            var result = NationGenManager.Stage2Manager.computeAndSave(cityId);
            if (result == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"Stage1 not found for: " + cityId + "\"}");
                return;
            }

            CityInstance city = CityManager.get().getCity(cityId);
            CityC2ScanBinaryIO.C2ScanData scanData = CityC2ScanBinaryIO.load(cityId);
            Path cityDir = resolveCityDir(cityId);
            CityC3OwnershipIO.OwnershipData ownership = CityC3OwnershipIO.compute(city);
            Path ownershipFile = CityC3OwnershipIO.save(cityDir, ownership);
            JsonObject polygonPreview = CityC3PolygonPreviewExporter.export(mcServer, cityId, city, scanData, ownership);

            JsonObject res = new JsonObject();
            res.addProperty("status", "ok");
            res.addProperty("step", "C3");
            res.addProperty("city_id", cityId);
            res.addProperty("intent_count", result.intents != null ? result.intents.size() : 0);
            if (ownershipFile != null) {
                res.addProperty("ownership_file", ownershipFile.toString());
                res.addProperty("ownership_step", ownership != null ? ownership.step : -1);
            }
            res.add("polygon_preview", polygonPreview);
            res.addProperty("ai_should_pause", true);
            res.addProperty("next_action", "STOP_CURRENT_STEP_AND_REVIEW_C3_POLYGON_PREVIEW");
            res.addProperty("message", "C3 completed and polygon preview is generated from C2 step scan data.");
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
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

            Path cityDir = resolveCityDir(cityId);
            CitySemanticStages.FunctionWhitelist whitelist = CitySemanticStages.loadFunctionWhitelist(cityDir);
            if (whitelist == null || !whitelist.ok) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing/invalid C4 whitelist. Run city_c4_whitelist_generate before C4.\"}");
                return;
            }

            CitySemanticStages.C4Plan plan = CitySemanticStages.generateC4(city, whitelist);
            CitySemanticStages.saveC4(cityDir, plan);

            JsonObject res = new JsonObject();
            res.addProperty("status", "ok");
            res.addProperty("step", "C4");
            res.addProperty("city_id", cityId);
            res.addProperty("district_count", plan.district_functions != null ? plan.district_functions.size() : 0);
            res.addProperty("function_whitelist_version", plan.function_whitelist_version);
            res.addProperty("whitelist_file", cityDir.resolve(CitySemanticStages.C4_WHITELIST_FILE).toString());
            res.addProperty("file", cityDir.resolve(CitySemanticStages.C4_FILE).toString());
            res.addProperty("validated_file", cityDir.resolve(CitySemanticStages.C4_VALIDATED_FILE).toString());
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC4WhitelistGenerate(HttpExchange exchange) throws IOException {
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

            List<String> primary = readStringList(json, "primary_functions");
            List<String> secondary = readStringList(json, "secondary_functions");
            if (primary.isEmpty() || secondary.isEmpty()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"primary_functions and secondary_functions are required and cannot be empty.\"}");
                return;
            }

            CitySemanticStages.FunctionWhitelist whitelist = new CitySemanticStages.FunctionWhitelist();
            whitelist.city_id = cityId;
            whitelist.generated_at_epoch_ms = System.currentTimeMillis();
            whitelist.version = json.has("version") ? json.get("version").getAsString() : "ai_dynamic_v1";
            whitelist.source = json.has("source") ? json.get("source").getAsString() : "mcp_ai";
            whitelist.rationale = json.has("rationale") ? json.get("rationale").getAsString() : "";
            whitelist.primary_functions = primary;
            whitelist.secondary_functions = secondary;

            Path cityDir = resolveCityDir(cityId);
            CitySemanticStages.saveFunctionWhitelist(cityDir, whitelist);
            CitySemanticStages.FunctionWhitelist saved = CitySemanticStages.loadFunctionWhitelist(cityDir);

            JsonObject res = new JsonObject();
            res.addProperty("status", "ok");
            res.addProperty("step", "C4_WHITELIST");
            res.addProperty("city_id", cityId);
            res.addProperty("function_whitelist_version", saved != null ? saved.version : whitelist.version);
            res.addProperty("primary_count", saved != null && saved.primary_functions != null ? saved.primary_functions.size() : primary.size());
            res.addProperty("secondary_count", saved != null && saved.secondary_functions != null ? saved.secondary_functions.size() : secondary.size());
            res.addProperty("file", cityDir.resolve(CitySemanticStages.C4_WHITELIST_FILE).toString());
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC4WhitelistData(HttpExchange exchange) throws IOException {
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
            CitySemanticStages.FunctionWhitelist whitelist = CitySemanticStages.loadFunctionWhitelist(cityDir);
            if (whitelist == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"C4 whitelist not found for: " + cityId + "\"}");
                return;
            }
            HttpUtil.sendResponse(exchange, 200, gson.toJson(whitelist));
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
            CitySemanticStages.C4Plan c4Plan = CitySemanticStages.loadC4Validated(cityDir);
            if (c4Plan == null) {
                c4Plan = CitySemanticStages.loadC4(cityDir);
            }
            if (c4Plan == null) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"C4 data missing. Run city_c4_whitelist_generate then city_c4_generate first.\"}");
                return;
            }

            CitySemanticStages.MergePolicy policy = parseC5MergePolicy(json);
            CitySemanticStages.C5Groups groups = CitySemanticStages.generateC5(city, c4Plan, policy);
            CitySemanticStages.saveC5(cityDir, groups);
            CityC2ScanBinaryIO.C2ScanData scanData = CityC2ScanBinaryIO.load(cityId);
            CityC3OwnershipIO.OwnershipData ownership = CityC3OwnershipIO.load(cityDir);
            JsonObject modulePreview = CityC5ModulePreviewExporter.export(mcServer, cityId, city, groups, scanData, ownership);

            JsonObject res = new JsonObject();
            res.addProperty("status", "ok");
            res.addProperty("step", "C5");
            res.addProperty("city_id", cityId);
            res.addProperty("review_required", true);
            res.addProperty("next_action", "REVIEW_C5_PREVIEW_AND_MERGE_RESULT");
            res.addProperty("group_count", groups.groups != null ? groups.groups.size() : 0);
            res.addProperty("cross_layer_merge", groups.policy != null && groups.policy.cross_layer_merge);
            res.add("policy", gson.toJsonTree(groups.policy));
            res.addProperty("merge_log_count", groups.merge_log != null ? groups.merge_log.size() : 0);
            res.addProperty("file", cityDir.resolve(CitySemanticStages.C5_FILE).toString());
            res.addProperty("merge_log_file", cityDir.resolve(CitySemanticStages.C5_MERGE_LOG_FILE).toString());
            res.addProperty("c5_groups_file", cityDir.resolve(CitySemanticStages.C5_FILE).toString());
            res.addProperty("c5_merge_log_file", cityDir.resolve(CitySemanticStages.C5_MERGE_LOG_FILE).toString());
            res.add("module_preview", modulePreview);
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
            CityC2ScanBinaryIO.C2ScanData scanData = CityC2ScanBinaryIO.load(cityId);
            CityC3OwnershipIO.OwnershipData ownership = CityC3OwnershipIO.load(cityDir);
            CityInstance city = CityManager.get().getCity(cityId);
            JsonObject modulePreview = null;
            if (mcServer != null && city != null && scanData != null && ownership != null) {
                modulePreview = CityC5ModulePreviewExporter.export(mcServer, cityId, city, groups, scanData, ownership);
            }

            JsonObject res = new JsonObject();
            res.addProperty("status", "ok");
            res.addProperty("step", "C5");
            res.addProperty("city_id", cityId);
            res.addProperty("review_required", true);
            res.addProperty("next_action", "REVIEW_C5_PREVIEW_AND_MERGE_RESULT");
            res.addProperty("group_count", groups.groups != null ? groups.groups.size() : 0);
            res.addProperty("merge_log_count", groups.merge_log != null ? groups.merge_log.size() : 0);
            res.add("policy", gson.toJsonTree(groups.policy));
            res.add("groups", gson.toJsonTree(groups.groups));
            res.add("merge_stats", gson.toJsonTree(groups.merge_stats));
            res.add("quality", gson.toJsonTree(groups.quality));
            res.add("merge_log", gson.toJsonTree(groups.merge_log));
            res.addProperty("c5_groups_file", cityDir.resolve(CitySemanticStages.C5_FILE).toString());
            res.addProperty("c5_merge_log_file", cityDir.resolve(CitySemanticStages.C5_MERGE_LOG_FILE).toString());
            JsonObject mergeSummary = new JsonObject();
            mergeSummary.addProperty("group_count", groups.groups != null ? groups.groups.size() : 0);
            mergeSummary.addProperty("merge_log_count", groups.merge_log != null ? groups.merge_log.size() : 0);
            mergeSummary.addProperty("fragment_reduction_ratio", groups.merge_stats != null ? groups.merge_stats.fragment_reduction_ratio : 0.0);
            mergeSummary.addProperty("disconnected_groups", groups.quality != null ? groups.quality.disconnected_groups : 0);
            mergeSummary.addProperty("low_compactness_groups", groups.quality != null ? groups.quality.low_compactness_groups : 0);
            res.add("merge_log_summary", mergeSummary);
            if (modulePreview != null) {
                res.add("module_preview", modulePreview);
                res.add("preview_references", modulePreview.deepCopy());
            }
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC6Generate(HttpExchange exchange) throws IOException {
        handleCityC6RectPrepare(exchange);
    }

    public void handleCityC6RectPrepare(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            String groupId = readOptionalString(json, "group_id");
            System.out.println("[C6] city_c6_rect_prepare request city_id=" + cityId + " group_id=" + groupId);
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
                CitySemanticStages.C4Plan c4 = CitySemanticStages.loadC4Validated(cityDir);
                if (c4 == null) c4 = CitySemanticStages.loadC4(cityDir);
                if (c4 == null) {
                    HttpUtil.sendResponse(exchange, 400, "{\"error\": \"C4 data missing. Run city_c4_whitelist_generate then city_c4_generate first.\"}");
                    return;
                }
                c5 = CitySemanticStages.generateC5(city, c4, CitySemanticStages.MergePolicy.defaults());
                CitySemanticStages.saveC5(cityDir, c5);
            }

            CityStage1BinaryIO.HeightData heightData = CityStage1BinaryIO.loadHeightData(cityId);
            CityC2ScanBinaryIO.C2ScanData c2ScanData = CityC2ScanBinaryIO.load(cityId);
            List<List<CityStage1Processor.BlockCoord>> buildableGroups = CityStage1BinaryIO.loadBuildableGroups(cityId);
            if (heightData == null || buildableGroups == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"Stage1 binary data not found for: " + cityId + "\"}");
                return;
            }
            List<CityStage1Processor.ForbiddenBlock> forbiddenBlocks = CityStage1BinaryIO.loadForbidden(cityId);

            CityC6Stages.C6Bundle bundle = CityC6Stages.generate(city, c5, heightData, c2ScanData, buildableGroups, fillStyle);
            System.out.println("[C6] city_c6_rect_prepare generated city_id=" + cityId
                    + " group_id=" + (groupId != null ? groupId : "all")
                    + " areas=" + (bundle.summary != null && bundle.summary.areas != null ? bundle.summary.areas.size() : 0)
                    + " plans=" + (bundle.layout != null && bundle.layout.plans != null ? bundle.layout.plans.size() : 0));
            CityC6Stages.C6Layout existingLayout = CityC6Stages.loadLayout(cityDir);
            CityC6Stages.C6RectCandidates existingCandidates = CityC6Stages.loadCandidates(cityDir);
            CityC6Stages.C6RectValidation existingValidation = CityC6Stages.loadValidation(cityDir);
            if (existingLayout != null && existingLayout.plans != null && !existingLayout.plans.isEmpty()) {
                for (CityC6Stages.LayoutPlan existingPlan : existingLayout.plans) {
                    CityC6Stages.LayoutPlan freshPlan = CityC6Stages.findPlanByGroup(bundle.layout, existingPlan != null ? existingPlan.group_id : null);
                    if (freshPlan != null && existingPlan != null && existingPlan.validated) {
                        freshPlan.primary_modules = existingPlan.primary_modules != null ? existingPlan.primary_modules : new ArrayList<>();
                        freshPlan.validated = true;
                        freshPlan.decision_mode = existingPlan.decision_mode;
                        freshPlan.accepted_attempt_index = existingPlan.accepted_attempt_index;
                        freshPlan.notes = existingPlan.notes;
                    }
                }
            }
            if (existingCandidates != null) bundle.candidates = existingCandidates;
            if (existingValidation != null) bundle.validation = existingValidation;
            if (bundle.decision_input != null && bundle.decision_input.groups != null) {
                for (CityC6Stages.GroupDecisionInput item : bundle.decision_input.groups) {
                    item.current_attempt_count = CityC6Stages.currentAttemptCount(bundle.candidates, item.group_id);
                    if (groupId == null || groupId.equals(item.group_id)) {
                        System.out.println("[C6] prepare guidance group=" + item.group_id
                                + " candidate_count=" + (item.rect_guidance != null ? item.rect_guidance.candidate_count : 0)
                                + " edge_buffer=" + (item.rect_guidance != null ? item.rect_guidance.edge_buffer_blocks : 0)
                                + " growth_buffer=" + (item.rect_guidance != null ? item.rect_guidance.growth_buffer_blocks : 0)
                                + " expansion_side=" + (item.rect_guidance != null ? item.rect_guidance.requires_expansion_side : "")
                                + " connector_reserve=" + (item.rect_guidance != null ? item.rect_guidance.connector_reserve_by_side : null));
                    }
                }
            }
            CityC6Stages.save(cityDir, bundle);
            CityC3OwnershipIO.OwnershipData ownership = CityC3OwnershipIO.load(cityDir);
            if (c2ScanData != null && ownership != null) {
                CityC5GroupTerrainPreviewExporter.export(mcServer, cityId, c5, c2ScanData, ownership);
            }
            JsonObject c6Preview = CityC6BuildAreaPreviewExporter.export(
                    mcServer,
                    cityId,
                    city,
                    heightData,
                    bundle.index_by_block,
                    forbiddenBlocks,
                    bundle.summary
            );
            JsonObject rectPreview = CityC6RectPlacementPreviewExporter.export(
                    mcServer,
                    cityId,
                    heightData,
                    bundle.summary,
                    bundle.layout,
                    bundle.index_by_block
            );
            JsonObject groupPreview = CityC6GroupPreviewExporter.export(
                    mcServer,
                    cityId,
                    heightData,
                    c2ScanData,
                    bundle.summary,
                    bundle.decision_input,
                    bundle.candidates,
                    bundle.validation,
                    bundle.index_by_block
            );
            CityC6Stages.saveDecisionInput(cityDir, bundle.decision_input);

            JsonObject res = new JsonObject();
            res.addProperty("status", "ok");
            res.addProperty("step", "C6_prepare");
            res.addProperty("city_id", cityId);
            if (groupId != null) res.addProperty("group_id", groupId);
            res.addProperty("fill_style", fillStyle.name());
            res.addProperty("area_count", bundle.summary != null && bundle.summary.areas != null ? bundle.summary.areas.size() : 0);
            res.addProperty("plan_count", bundle.layout != null && bundle.layout.plans != null ? bundle.layout.plans.size() : 0);
            res.addProperty("indexed_block_count", bundle.indexed_block_count);
            res.addProperty("summary_file", cityDir.resolve(CityC6Stages.C6_SUMMARY_FILE).toString());
            res.addProperty("layout_file", cityDir.resolve(CityC6Stages.C6_LAYOUT_FILE).toString());
            res.addProperty("decision_input_file", cityDir.resolve(CityC6Stages.C6_RECT_DECISION_INPUT_FILE).toString());
            res.addProperty("candidates_file", cityDir.resolve(CityC6Stages.C6_RECT_CANDIDATES_FILE).toString());
            res.addProperty("validation_file", cityDir.resolve(CityC6Stages.C6_RECT_VALIDATION_FILE).toString());
            res.addProperty("index_file", cityDir.resolve(CityC6Stages.C6_INDEX_FILE).toString());
            res.add("build_area_preview", c6Preview);
            res.add("rect_placement_preview", rectPreview);
            res.add("group_previews", groupPreview);
            res.add("decision_input", gson.toJsonTree(filterDecisionInputByGroup(bundle.decision_input, groupId)));
            System.out.println("[C6] city_c6_rect_prepare response city_id=" + cityId
                    + " group_id=" + (groupId != null ? groupId : "all")
                    + " indexed_block_count=" + bundle.indexed_block_count);
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC6RectSubmit(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = readOptionalString(json, "city_id");
            String groupId = readOptionalString(json, "group_id");
            String decisionMode = readOptionalString(json, "decision_mode");
            int attemptIndex = json.has("attempt_index") ? json.get("attempt_index").getAsInt() : 0;
            System.out.println("[C6] city_c6_rect_submit request city_id=" + cityId
                    + " group_id=" + groupId
                    + " decision_mode=" + decisionMode
                    + " attempt_index=" + attemptIndex);
            if (cityId == null || groupId == null || decisionMode == null) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id/group_id/decision_mode\"}");
                return;
            }

            Path cityDir = resolveCityDir(cityId);
            CityC6Stages.C6Summary summary = CityC6Stages.loadSummary(cityDir);
            CityC6Stages.C6Layout layout = CityC6Stages.loadLayout(cityDir);
            CityC6Stages.C6RectDecisionInput input = CityC6Stages.loadDecisionInput(cityDir);
            CityC6Stages.C6RectCandidates candidates = CityC6Stages.loadCandidates(cityDir);
            CityC6Stages.C6RectValidation validation = CityC6Stages.loadValidation(cityDir);
            Map<Long, Integer> indexByBlock = CityC6Stages.loadIndex(cityDir);
            CityStage1BinaryIO.HeightData heightData = CityStage1BinaryIO.loadHeightData(cityId);
            CityC2ScanBinaryIO.C2ScanData c2ScanData = CityC2ScanBinaryIO.load(cityId);
            if (summary == null || layout == null || input == null || indexByBlock == null || heightData == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"Required C6 prepare data not found. Run city_c6_rect_prepare first.\"}");
                return;
            }

            CityC6Stages.BuildAreaSummary area = findBestAreaByGroup(summary, groupId);
            if (area == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"C6 group not found: " + groupId + "\"}");
                return;
            }

            int currentAttemptCount = CityC6Stages.currentAttemptCount(candidates, groupId);
            if (attemptIndex < 1) attemptIndex = currentAttemptCount + 1;
            if (attemptIndex != currentAttemptCount + 1 && attemptIndex <= CityC6Stages.RECT_ATTEMPT_LIMIT) {
                HttpUtil.sendResponse(exchange, 409, "{\"error\": \"attempt_index must be sequential\"}");
                return;
            }

            CityC6Stages.GroupRectCandidate candidate = new CityC6Stages.GroupRectCandidate();
            candidate.group_id = groupId;
            candidate.build_area_id = area.build_area_id;
            candidate.attempt_index = attemptIndex;
            candidate.decision_mode = decisionMode;
            candidate.rects = parseSubmittedRects(json);

            CityC6Stages.GroupRectValidation validationItem = CityC6Validation.validateSubmission(
                    area,
                    CityC6Stages.findDecisionGroup(input, groupId),
                    candidate,
                    indexByBlock,
                    CityC6Stages.RECT_ATTEMPT_LIMIT
            );
            System.out.println("[C6] submit validation group=" + groupId
                    + " attempt=" + attemptIndex
                    + " accepted=" + validationItem.accepted
                    + " reason=" + validationItem.reason
                    + " structure_compatible_rects=" + validationItem.structure_compatible_rects
                    + " matching_main_template_count=" + validationItem.matching_main_template_count);

            boolean finalized = false;
            if ("keep_current".equals(decisionMode)) {
                CityC6Stages.LayoutPlan existingPlan = CityC6Stages.findPlanByGroup(layout, groupId);
                finalized = existingPlan != null && existingPlan.validated;
                if (!finalized) {
                    validationItem.accepted = false;
                    validationItem.continue_allowed = attemptIndex < CityC6Stages.RECT_ATTEMPT_LIMIT;
                    validationItem.decision_terminal = !validationItem.continue_allowed;
                    validationItem.reason = "keep_current_without_validated_layout";
                }
            } else if ("no_primary_module".equals(decisionMode)) {
                CityC6Stages.applyAcceptedDecision(layout, area, candidate);
                finalized = true;
            } else if (validationItem.accepted) {
                CityC6Stages.applyAcceptedDecision(layout, area, candidate);
                finalized = true;
            }
            validationItem.finalized_into_layout = finalized;

            CityC6Stages.upsertCandidate(candidates, candidate);
            CityC6Stages.upsertValidation(validation, validationItem);
            CityC6Stages.updateAttemptCount(input, groupId, Math.max(currentAttemptCount, attemptIndex));
            CityC6Stages.saveCandidates(cityDir, candidates);
            CityC6Stages.saveValidation(cityDir, validation);
            CityC6Stages.saveDecisionInput(cityDir, input);
            if (finalized) {
                CityC6Stages.saveLayout(cityDir, layout);
            }

            JsonObject groupPreview = CityC6GroupPreviewExporter.export(
                    mcServer,
                    cityId,
                    heightData,
                    c2ScanData,
                    summary,
                    input,
                    candidates,
                    validation,
                    indexByBlock
            );
            Path validationFile = CityC6Validation.save(cityDir, groupId, validationItem);

            JsonObject res = new JsonObject();
            res.addProperty("status", validationItem.accepted || finalized ? "ok" : "invalid");
            res.addProperty("step", "C6_decide_validate");
            res.addProperty("city_id", cityId);
            res.addProperty("group_id", groupId);
            res.addProperty("attempt_index", attemptIndex);
            res.addProperty("decision_mode", decisionMode);
            res.addProperty("continue_allowed", validationItem.continue_allowed);
            res.addProperty("decision_terminal", validationItem.decision_terminal);
            res.addProperty("finalized_into_layout", finalized);
            res.addProperty("validation_file", validationFile.toString());
            res.add("validation", gson.toJsonTree(validationItem));
            res.add("group_previews", groupPreview);
            int statusCode = (validationItem.accepted || finalized) ? 200 : (validationItem.decision_terminal ? 409 : 422);
            System.out.println("[C6] city_c6_rect_submit response group=" + groupId
                    + " status=" + statusCode
                    + " finalized=" + finalized
                    + " continue_allowed=" + validationItem.continue_allowed
                    + " reason=" + validationItem.reason);
            HttpUtil.sendResponse(exchange, statusCode, gson.toJson(res));
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
            String groupId = readOptionalString(json, "group_id");
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }
            Path cityDir = resolveCityDir(cityId);
            CityC6Stages.C6Summary summary = CityC6Stages.loadSummary(cityDir);
            CityC6Stages.C6Layout layout = CityC6Stages.loadLayout(cityDir);
            CityC6Stages.C6RectDecisionInput input = CityC6Stages.loadDecisionInput(cityDir);
            CityC6Stages.C6RectCandidates candidates = CityC6Stages.loadCandidates(cityDir);
            CityC6Stages.C6RectValidation validation = CityC6Stages.loadValidation(cityDir);
            if (summary == null && layout == null && input == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"C6 data not found for: " + cityId + "\"}");
                return;
            }
            JsonObject res = new JsonObject();
            res.addProperty("step", "C6");
            res.addProperty("ok", true);
            res.add("summary", gson.toJsonTree(filterC6SummaryByGroup(summary, groupId)));
            res.add("layout", gson.toJsonTree(filterC6LayoutByGroup(layout, groupId)));
            res.add("decision_input", gson.toJsonTree(filterDecisionInputByGroup(input, groupId)));
            res.add("candidates", gson.toJsonTree(filterCandidatesByGroup(candidates, groupId)));
            res.add("validation", gson.toJsonTree(filterValidationByGroup(validation, groupId)));
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC7Generate(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            String groupId = readOptionalString(json, "group_id");
            Boolean strictTagOverride = json.has("strict_tag_source") ? json.get("strict_tag_source").getAsBoolean() : null;
            boolean strictTagSource = CityGenerationConfig.resolveStrictTagSource(strictTagOverride);
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }

            Path cityDir = resolveCityDir(cityId);
            CityC6Stages.C6Layout c6Layout = CityC6Stages.loadLayout(cityDir);
            if (c6Layout == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"C6 layout not found for: " + cityId + "\"}");
                return;
            }

            CityC7Stages.C7Selection selection = json.has("arrangements")
                    ? CityC7Stages.fromDecisionRequest(cityId, c6Layout, json, strictTagSource)
                    : CityC7Stages.generate(cityId, c6Layout, strictTagSource);
            CityC7Stages.C7Selection responseSelection = filterC7SelectionByGroup(selection, groupId);
            Path outputFile = cityDir.resolve(CityC7Stages.C7_FILE);
            if (groupId != null && !groupId.isBlank()) {
                Path groupDir = resolveGroupDir(cityDir, groupId);
                outputFile = groupDir.resolve("c7_selection.json");
                java.nio.file.Files.writeString(outputFile, gson.toJson(responseSelection));
            } else {
                CityC7Stages.save(cityDir, selection);
            }

            JsonObject res = new JsonObject();
            res.addProperty("status", "ok");
            res.addProperty("step", "C7");
            res.addProperty("city_id", cityId);
            if (groupId != null) res.addProperty("group_id", groupId);
            res.addProperty("selection_count", responseSelection.selections != null ? responseSelection.selections.size() : 0);
            res.addProperty("arrangement_count", responseSelection.arrangements != null ? responseSelection.arrangements.size() : 0);
            res.addProperty("catalog_source", responseSelection.catalog_source);
            res.addProperty("decision_source", responseSelection.decision_source);
            res.addProperty("selection_mode", responseSelection.selection_mode);
            res.addProperty("strict_tag_source", responseSelection.strict_tag_source);
            res.addProperty("filtered_candidate_count", responseSelection.filtered_candidate_count);
            if (responseSelection.strict_filter_failure_reason != null) {
                res.addProperty("strict_filter_failure_reason", responseSelection.strict_filter_failure_reason);
            }
            res.addProperty("file", outputFile.toString());
            if (groupId != null && !groupId.isBlank()) {
                CityC7Validation.Report validation = CityC7Validation.generate(cityId, groupId, responseSelection);
                Path groupDir = resolveGroupDir(cityDir, groupId);
                Path validationFile = CityC7Validation.save(cityDir, groupId, validation);
                GroupStepStateUtil.State state = GroupStepStateUtil.update(groupDir, "C7", !validation.ok, "c7_validation_failed_three_times");
                res.addProperty("artifact_dir", groupDir.toString());
                res.addProperty("validation_file", validationFile.toString());
                res.addProperty("retry_count", state.failure_count);
                res.addProperty("blocked_after_failures", state.blocked);
                res.add("validation", gson.toJsonTree(validation));
                if (state.blocked || !validation.ok) {
                    res.addProperty("status", "invalid");
                    HttpUtil.sendResponse(exchange, state.blocked ? 409 : 422, gson.toJson(res));
                    return;
                }
            }
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC7Data(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            String groupId = readOptionalString(json, "group_id");
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }
            Path cityDir = resolveCityDir(cityId);
            if (groupId != null && !groupId.isBlank()) {
                Path file = resolveGroupDir(cityDir, groupId).resolve("c7_selection.json");
                if (!java.nio.file.Files.exists(file)) {
                    HttpUtil.sendResponse(exchange, 404, "{\"error\": \"C7 group data not found for: " + cityId + " / " + groupId + "\"}");
                    return;
                }
                HttpUtil.sendResponse(exchange, 200, java.nio.file.Files.readString(file));
                return;
            }
            CityC7Stages.C7Selection selection = CityC7Stages.load(cityDir);
            if (selection == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"C7 data not found for: " + cityId + "\"}");
                return;
            }
            HttpUtil.sendResponse(exchange, 200, gson.toJson(selection));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }
    public void handleCityC8Generate(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            String groupId = readOptionalString(json, "group_id");
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }

            Path cityDir = resolveCityDir(cityId);
            CityC6Stages.C6Summary c6Summary = CityC6Stages.loadSummary(cityDir);
            CityC6Stages.C6Layout c6Layout = CityC6Stages.loadLayout(cityDir);
            Map<Long, Integer> c6Index = CityC6Stages.loadIndex(cityDir);
            CityStage1BinaryIO.HeightData heightData = CityStage1BinaryIO.loadHeightData(cityId);
            if (c6Summary == null || c6Layout == null || c6Index == null || c6Index.isEmpty() || heightData == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"Required C6/heightmap data not found for: " + cityId + "\"}");
                return;
            }

            CityC2ScanBinaryIO.C2ScanData c2ScanData = CityC2ScanBinaryIO.load(cityId);
            CityC7Stages.C7Selection c7Selection = loadC8GenerationSelection(cityDir, groupId);
            System.out.println("[C8] handleCityC8Generate city=" + cityId
                    + " group=" + safe(groupId)
                    + " c7_selection_generated_at=" + (c7Selection != null ? c7Selection.generated_at_epoch_ms : -1)
                    + " c7_selection_count=" + (c7Selection != null && c7Selection.selections != null ? c7Selection.selections.size() : -1));
            CityC8Stages.C8Plan plan = CityC8Stages.generate(cityId, c6Summary, c6Layout, c7Selection, heightData, c2ScanData, c6Index);
            if (groupId == null || groupId.isBlank()) {
                CityC8Stages.save(cityDir, plan);
            }

            Set<Integer> targetAreaIds = collectAreaIdsForGroup(c6Summary, groupId);
            CityC8Stages.C8Plan responsePlan = filterC8PlanByGroup(plan, groupId, targetAreaIds);
            Path outputFile = cityDir.resolve(CityC8Stages.C8_PLAN_FILE);
            if (groupId != null && !groupId.isBlank()) {
                Path groupDir = resolveGroupDir(cityDir, groupId);
                outputFile = groupDir.resolve("c8_foundation.json");
                java.nio.file.Files.writeString(outputFile, gson.toJson(responsePlan));
                String written = java.nio.file.Files.readString(outputFile);
                System.out.println("[C8] wrote_group_file=" + outputFile
                        + " bytes=" + written.length()
                        + " contains_plains_butcher_shop_1=" + written.contains("minecraft:village/plains/houses/plains_butcher_shop_1")
                        + " contains_missing_catalog_meta=" + written.contains("missing_catalog_meta"));
                CityC8Stages.saveDebug(cityDir, groupId, responsePlan);
            }

            JsonObject preview = groupId != null && !groupId.isBlank()
                    ? CityC8ArrangementPreviewExporter.export(mcServer, cityId, groupId, heightData, c2ScanData, c6Summary, c6Layout, responsePlan)
                    : new JsonObject();

            JsonObject res = new JsonObject();
            res.addProperty("status", "ok");
            res.addProperty("step", "C8");
            res.addProperty("city_id", cityId);
            if (groupId != null) res.addProperty("group_id", groupId);
            res.addProperty("foundation_count", responsePlan.foundations != null ? responsePlan.foundations.size() : 0);
            res.addProperty("file", outputFile.toString());
            if (groupId != null && !groupId.isBlank()) res.add("arrangement_preview", preview);
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC8Data(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            String groupId = readOptionalString(json, "group_id");
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }

            Path cityDir = resolveCityDir(cityId);
            if (groupId != null && !groupId.isBlank()) {
                Path file = resolveGroupDir(cityDir, groupId).resolve("c8_foundation.json");
                if (!java.nio.file.Files.exists(file)) {
                    HttpUtil.sendResponse(exchange, 404, "{\"error\": \"C8 group data not found for: " + cityId + " / " + groupId + "\"}");
                    return;
                }
                HttpUtil.sendResponse(exchange, 200, java.nio.file.Files.readString(file));
                return;
            }
            CityC8Stages.C8Plan plan = CityC8Stages.load(cityDir);
            if (plan == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"C8 data not found for: " + cityId + "\"}");
                return;
            }
            HttpUtil.sendResponse(exchange, 200, gson.toJson(plan));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC9Generate(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            String groupId = readOptionalString(json, "group_id");
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }
            Boolean deprecatedApplyBlocks = json.has("apply_blocks") ? json.get("apply_blocks").getAsBoolean() : null;
            CityC9Stages.Mode mode = CityC9Stages.Mode.parse(readOptionalString(json, "mode"), deprecatedApplyBlocks);
            boolean applyBlocks = mode == CityC9Stages.Mode.APPLY_NOW;
            int maxBlocks = json.has("max_blocks") ? Math.max(1, Math.min(200000, json.get("max_blocks").getAsInt())) : 25000;

            Path cityDir = resolveCityDir(cityId);
            CityC6Stages.C6Summary c6Summary = CityC6Stages.loadSummary(cityDir);
            CityC6Stages.C6Layout c6Layout = CityC6Stages.loadLayout(cityDir);
            Map<Long, Integer> c6Index = CityC6Stages.loadIndex(cityDir);
            CityStage1BinaryIO.HeightData heightData = CityStage1BinaryIO.loadHeightData(cityId);
            CityC2ScanBinaryIO.C2ScanData c2ScanData = CityC2ScanBinaryIO.load(cityId);
            if (c6Summary == null || c6Index == null || c6Index.isEmpty()) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"C6 summary/index not found for: " + cityId + "\"}");
                return;
            }

            CityC8Stages.C8Plan c8Plan = CityC8Stages.load(cityDir);
            if (c8Plan == null) {
                if (c6Layout == null || heightData == null) {
                    HttpUtil.sendResponse(exchange, 404, "{\"error\": \"C8 missing and required data to regenerate C8 not found for: " + cityId + "\"}");
                    return;
                }
                CityC7Stages.C7Selection c7Selection = loadC8GenerationSelection(cityDir, groupId);
                c8Plan = CityC8Stages.generate(cityId, c6Summary, c6Layout, c7Selection, heightData, c2ScanData, c6Index);
                if (groupId == null || groupId.isBlank()) {
                    CityC8Stages.save(cityDir, c8Plan);
                }
            }

            Set<Integer> targetAreaIds = collectAreaIdsForGroup(c6Summary, groupId);
            CityC6Stages.C6Summary responseSummary = filterC6SummaryByGroup(c6Summary, groupId);
            Map<Long, Integer> responseIndex = filterIndexByAreaIds(c6Index, targetAreaIds);
            CityC8Stages.C8Plan responsePlan = filterC8PlanByGroup(c8Plan, groupId, targetAreaIds);
            CityC9BuildQueue.BuildQueue existingQueue = CityC9BuildQueue.loadOrCreate(cityDir, cityId);
            final CityC9Stages.C9Result[] holder = new CityC9Stages.C9Result[1];
            holder[0] = CityC9Stages.generate(cityId, responseSummary, responseIndex, responsePlan, mode, maxBlocks, existingQueue);
            System.out.println("[C9] generate city=" + cityId
                    + " group=" + safe(groupId)
                    + " mode=" + mode.name()
                    + " max_blocks=" + maxBlocks
                    + " foundation_count=" + (responsePlan != null && responsePlan.foundations != null ? responsePlan.foundations.size() : -1)
                    + " queue_total_before_save=" + (holder[0] != null && holder[0].queue != null && holder[0].queue.tasks != null ? holder[0].queue.tasks.size() : -1)
                    + " enqueued_tasks_count=" + (holder[0] != null && holder[0].placement != null ? holder[0].placement.enqueued_tasks_count : -1));
            if (mode != CityC9Stages.Mode.DRY_RUN && holder[0] != null && holder[0].queue != null) {
                CityC9BuildQueue.save(cityDir, holder[0].queue);
                if (mcServer != null) {
                    CityBuildQueueExecutor.refreshCityQueue(mcServer, cityId);
                }
            }
            if (mode == CityC9Stages.Mode.APPLY_NOW) {
                if (mcServer == null || mcServer.overworld() == null) {
                    HttpUtil.sendResponse(exchange, 500, "{\"error\": \"Minecraft server/overworld unavailable\"}");
                    return;
                }
                CountDownLatch latch = new CountDownLatch(1);
                mcServer.execute(() -> {
                    try {
                        CityBuildQueueExecutor.ExecutionReport execution = CityBuildQueueExecutor.executeLoadedTasksNow(mcServer, cityId, groupId, maxBlocks);
                        System.out.println("[C9] apply_now city=" + cityId
                                + " group=" + safe(groupId)
                                + " max_blocks=" + maxBlocks
                                + " attempted=" + execution.attempted
                                + " completed=" + execution.completed
                                + " blocked=" + execution.blocked
                                + " retried=" + execution.retried);
                        if (holder[0] != null && holder[0].placement != null) {
                            holder[0].placement.applied_tasks_count = execution.completed;
                            holder[0].placement.changed_blocks_total = execution.completed;
                        }
                        try {
                            CityC9BuildQueue.BuildQueue latestQueue = CityC9BuildQueue.loadOrCreate(cityDir, cityId);
                            if (holder[0] != null) {
                                holder[0].queue = groupId != null && !groupId.isBlank()
                                        ? CityC9BuildQueue.filtered(latestQueue, groupId)
                                        : latestQueue;
                                holder[0].queue_summary = CityC9BuildQueue.summarize(holder[0].queue, null);
                                System.out.println("[C9] queue_after_apply city=" + cityId
                                        + " group=" + safe(groupId)
                                        + " planned=" + (holder[0].queue_summary != null ? holder[0].queue_summary.planned_count : -1)
                                        + " ready=" + (holder[0].queue_summary != null ? holder[0].queue_summary.ready_count : -1)
                                        + " inflight=" + (holder[0].queue_summary != null ? holder[0].queue_summary.inflight_count : -1)
                                        + " done=" + (holder[0].queue_summary != null ? holder[0].queue_summary.done_count : -1)
                                        + " blocked=" + (holder[0].queue_summary != null ? holder[0].queue_summary.blocked_count : -1)
                                        + " total=" + (holder[0].queue_summary != null ? holder[0].queue_summary.total_count : -1));
                            }
                        } catch (Exception ignored) {
                        }
                    } catch (Exception ignored) {
                    } finally {
                        latch.countDown();
                    }
                });
                latch.await();
            }
            CityC9Stages.C9Result result = holder[0];
            CityC9Stages.C9Result responseResult = filterC9ResultByAreas(result, targetAreaIds);
            if (responseResult != null && result != null && result.queue != null) {
                responseResult.queue = groupId != null && !groupId.isBlank()
                        ? CityC9BuildQueue.filtered(result.queue, groupId)
                        : result.queue;
                responseResult.queue_summary = CityC9BuildQueue.summarize(responseResult.queue, null);
            }
            Path placementFile = cityDir.resolve(CityC9Stages.C9_PLACEMENT_FILE);
            Path decorationFile = cityDir.resolve(CityC9Stages.C9_DECORATION_FILE);
            JsonObject placementPreview = new JsonObject();
            if (groupId != null && !groupId.isBlank()) {
                Path groupDir = resolveGroupDir(cityDir, groupId);
                placementFile = groupDir.resolve("c9_placement.json");
                decorationFile = groupDir.resolve("c9_decoration.json");
                if (responseResult.placement != null) java.nio.file.Files.writeString(placementFile, gson.toJson(responseResult.placement));
                if (responseResult.decoration != null) java.nio.file.Files.writeString(decorationFile, gson.toJson(responseResult.decoration));
                placementPreview = CityC9PlacementPreviewExporter.export(
                        mcServer,
                        cityId,
                        groupId,
                        mode.name().toLowerCase(java.util.Locale.ROOT),
                        heightData,
                        c2ScanData,
                        c6Summary,
                        c6Layout,
                        responsePlan,
                        responseResult != null ? responseResult.placement : null,
                        responseResult != null ? responseResult.queue : null
                );
            } else {
                CityC9Stages.save(cityDir, result);
            }

            JsonObject res = new JsonObject();
            res.addProperty("status", "ok");
            res.addProperty("step", "C9");
            res.addProperty("city_id", cityId);
            if (groupId != null) res.addProperty("group_id", groupId);
            res.addProperty("mode", mode.name().toLowerCase(java.util.Locale.ROOT));
            res.addProperty("apply_blocks", applyBlocks);
            if (deprecatedApplyBlocks != null) {
                res.addProperty("deprecated_apply_blocks_mapped", true);
            }
            res.addProperty("processed_areas", responseResult != null && responseResult.placement != null ? responseResult.placement.processed_areas : 0);
            res.addProperty("changed_blocks_total", responseResult != null && responseResult.placement != null ? responseResult.placement.changed_blocks_total : 0);
            res.addProperty("enqueued_tasks_count", responseResult != null && responseResult.placement != null ? responseResult.placement.enqueued_tasks_count : 0);
            res.addProperty("applied_tasks_count", responseResult != null && responseResult.placement != null ? responseResult.placement.applied_tasks_count : 0);
            if (responseResult != null && responseResult.queue_summary != null) {
                res.add("queue_summary", gson.toJsonTree(responseResult.queue_summary));
            }
            res.addProperty("placement_file", placementFile.toString());
            res.addProperty("decoration_file", decorationFile.toString());
            if (groupId != null && !groupId.isBlank()) {
                res.add("placement_preview", placementPreview);
            }
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            HttpUtil.sendResponse(exchange, 500, "{\"error\": \"Interrupted while generating C9\"}");
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC9Data(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            String groupId = readOptionalString(json, "group_id");
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }
            Path cityDir = resolveCityDir(cityId);
            if (groupId != null && !groupId.isBlank()) {
                Path groupDir = resolveGroupDir(cityDir, groupId);
                Path placementFile = groupDir.resolve("c9_placement.json");
                Path decorationFile = groupDir.resolve("c9_decoration.json");
                CityC9BuildQueue.BuildQueue queue = CityC9BuildQueue.filtered(CityC9BuildQueue.load(cityDir), groupId);
                if (!java.nio.file.Files.exists(placementFile) && !java.nio.file.Files.exists(decorationFile) && queue == null) {
                    HttpUtil.sendResponse(exchange, 404, "{\"error\": \"C9 group data not found for: " + cityId + " / " + groupId + "\"}");
                    return;
                }
                JsonObject res = new JsonObject();
                res.addProperty("step", "C9");
                res.addProperty("ok", true);
                if (java.nio.file.Files.exists(placementFile)) res.add("placement", JsonParser.parseString(java.nio.file.Files.readString(placementFile)));
                if (java.nio.file.Files.exists(decorationFile)) res.add("decoration", JsonParser.parseString(java.nio.file.Files.readString(decorationFile)));
                res.add("queue", gson.toJsonTree(queue));
                res.add("queue_summary", gson.toJsonTree(CityC9BuildQueue.summarize(queue, null)));
                HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
                return;
            }
            Path cityRoot = resolveCityDir(cityId);
            CityC9Stages.C9Placement placement = CityC9Stages.loadPlacement(cityRoot);
            CityC9Stages.C9Decoration decoration = CityC9Stages.loadDecoration(cityRoot);
            CityC9BuildQueue.BuildQueue queue = CityC9BuildQueue.load(cityRoot);
            if (placement == null && decoration == null && queue == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"C9 data not found for: " + cityId + "\"}");
                return;
            }
            JsonObject res = new JsonObject();
            res.addProperty("step", "C9");
            res.addProperty("ok", true);
            res.add("placement", gson.toJsonTree(placement));
            res.add("decoration", gson.toJsonTree(decoration));
            CityC9BuildQueue.BuildQueue responseQueue = groupId != null && !groupId.isBlank()
                    ? CityC9BuildQueue.filtered(queue, groupId)
                    : queue;
            res.add("queue", gson.toJsonTree(responseQueue));
            res.add("queue_summary", gson.toJsonTree(CityC9BuildQueue.summarize(responseQueue, null)));
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC9QueueData(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            String groupId = readOptionalString(json, "group_id");
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }
            Path cityDir = resolveCityDir(cityId);
            CityC9BuildQueue.BuildQueue queue = CityC9BuildQueue.load(cityDir);
            if (queue == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"C9 queue not found for: " + cityId + "\"}");
                return;
            }
            CityC9BuildQueue.BuildQueue responseQueue = groupId != null && !groupId.isBlank()
                    ? CityC9BuildQueue.filtered(queue, groupId)
                    : queue;
            JsonObject res = new JsonObject();
            res.addProperty("step", "C9_QUEUE");
            res.addProperty("ok", true);
            res.add("queue", gson.toJsonTree(responseQueue));
            res.add("queue_summary", gson.toJsonTree(CityC9BuildQueue.summarize(responseQueue, null)));
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

    private static List<String> readStringList(JsonObject json, String key) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (json == null || key == null || key.isBlank() || !json.has(key) || !json.get(key).isJsonArray()) {
            return new java.util.ArrayList<>();
        }
        JsonArray arr = json.getAsJsonArray(key);
        for (int i = 0; i < arr.size(); i++) {
            if (arr.get(i) == null || arr.get(i).isJsonNull()) continue;
            String value = arr.get(i).getAsString();
            if (value == null) continue;
            String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
            if (normalized.isBlank()) continue;
            out.add(normalized);
        }
        return new java.util.ArrayList<>(out);
    }

    private static CitySemanticStages.MergePolicy parseC5MergePolicy(JsonObject json) {
        CitySemanticStages.MergePolicy policy = CitySemanticStages.MergePolicy.defaults();
        if (json == null) return policy;
        if (json.has("cross_layer_merge")) {
            policy.cross_layer_merge = json.get("cross_layer_merge").getAsBoolean();
        }
        if (json.has("adjacency_mode")) {
            policy.adjacency_mode = json.get("adjacency_mode").getAsString();
        }
        if (json.has("min_district_area_chunks")) {
            policy.min_district_area_chunks = Math.max(0, json.get("min_district_area_chunks").getAsInt());
        }
        if (json.has("allow_cross_function_absorb_for_tiny")) {
            policy.allow_cross_function_absorb_for_tiny = json.get("allow_cross_function_absorb_for_tiny").getAsBoolean();
        }
        if (json.has("split_disconnected_group")) {
            policy.split_disconnected_group = json.get("split_disconnected_group").getAsBoolean();
        }
        if (json.has("min_compactness")) {
            double value = json.get("min_compactness").getAsDouble();
            if (!Double.isFinite(value)) value = policy.min_compactness;
            policy.min_compactness = Math.max(0.0, Math.min(1.0, value));
        }
        return policy;
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

    private static CityScanBounds computeCityScanBounds(CityInstance city, int paddingBlocks) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        if (city != null && city.claimedChunks != null && !city.claimedChunks.isEmpty()) {
            for (Long key : city.claimedChunks.keySet()) {
                if (key == null) continue;
                int cx = net.minecraft.world.level.ChunkPos.getX(key);
                int cz = net.minecraft.world.level.ChunkPos.getZ(key);
                int cMinX = cx << 4;
                int cMinZ = cz << 4;
                int cMaxX = cMinX + 15;
                int cMaxZ = cMinZ + 15;
                if (cMinX < minX) minX = cMinX;
                if (cMinZ < minZ) minZ = cMinZ;
                if (cMaxX > maxX) maxX = cMaxX;
                if (cMaxZ > maxZ) maxZ = cMaxZ;
            }
        } else {
            int centerX = city != null && city.config != null ? city.config.centerX : 0;
            int centerZ = city != null && city.config != null ? city.config.centerZ : 0;
            minX = centerX - 128;
            minZ = centerZ - 128;
            maxX = centerX + 128;
            maxZ = centerZ + 128;
        }

        minX -= paddingBlocks;
        minZ -= paddingBlocks;
        maxX += paddingBlocks;
        maxZ += paddingBlocks;
        int width = Math.max(16, maxX - minX + 1);
        int height = Math.max(16, maxZ - minZ + 1);
        return new CityScanBounds(minX, minZ, width, height);
    }

    private static final class CityScanBounds {
        final int minX;
        final int minZ;
        final int width;
        final int height;

        private CityScanBounds(int minX, int minZ, int width, int height) {
            this.minX = minX;
            this.minZ = minZ;
            this.width = width;
            this.height = height;
        }
    }

    private static C2ScanResolved resolveC2ScanData(
            String cityId,
            CityScanBounds bounds,
            int scanStep,
            ServerLevel level
    ) throws Exception {
        long now = System.currentTimeMillis();
        C2CacheEntry mem = C2_SCAN_CACHE.get(cityId);
        if (mem != null
                && (now - mem.cachedAtMs) <= C2_MEMORY_CACHE_TTL_MS
                && isMatchingScan(mem.data, bounds, scanStep)
                && mem.data.map != null
                && mem.data.map.length > 0
                && mem.data.map[0] != null) {
            return new C2ScanResolved(mem.data.map, CityC2ScanBinaryIO.dataFile(cityId), true, "memory");
        }

        CityC2ScanBinaryIO.C2ScanData disk = CityC2ScanBinaryIO.load(cityId);
        if (isMatchingScan(disk, bounds, scanStep) && disk.map != null && disk.map.length > 0 && disk.map[0] != null) {
            C2_SCAN_CACHE.put(cityId, new C2CacheEntry(disk, now));
            return new C2ScanResolved(disk.map, CityC2ScanBinaryIO.dataFile(cityId), true, "disk");
        }

        ScanPixel[][] scanned = SatelliteScanner.scanRegionAsync(
                level,
                bounds.minX,
                bounds.minZ,
                bounds.width,
                bounds.height,
                scanStep
        ).get(10, TimeUnit.MINUTES);
        File file = CityC2ScanBinaryIO.save(
                cityId,
                bounds.minX,
                bounds.minZ,
                bounds.width,
                bounds.height,
                scanStep,
                scanned
        );
        C2_SCAN_CACHE.put(cityId, new C2CacheEntry(
                new CityC2ScanBinaryIO.C2ScanData(bounds.minX, bounds.minZ, bounds.width, bounds.height, scanStep, scanned),
                now
        ));
        return new C2ScanResolved(scanned, file, false, "scan");
    }

    private static boolean isMatchingScan(CityC2ScanBinaryIO.C2ScanData data, CityScanBounds bounds, int step) {
        if (data == null || bounds == null) return false;
        return data.step == step
                && data.originX == bounds.minX
                && data.originZ == bounds.minZ
                && data.widthBlocks == bounds.width
                && data.heightBlocks == bounds.height;
    }

    private static final class C2CacheEntry {
        final CityC2ScanBinaryIO.C2ScanData data;
        final long cachedAtMs;

        C2CacheEntry(CityC2ScanBinaryIO.C2ScanData data, long cachedAtMs) {
            this.data = data;
            this.cachedAtMs = cachedAtMs;
        }
    }

    private static final class C2ScanResolved {
        final ScanPixel[][] map;
        final File file;
        final boolean cacheHit;
        final String cacheSource;

        C2ScanResolved(ScanPixel[][] map, File file, boolean cacheHit, String cacheSource) {
            this.map = map;
            this.file = file;
            this.cacheHit = cacheHit;
            this.cacheSource = cacheSource;
        }
    }

    private static String readOptionalString(JsonObject json, String key) {
        if (json == null || key == null || key.isBlank() || !json.has(key) || json.get(key).isJsonNull()) return null;
        String value = json.get(key).getAsString();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Path resolveGroupDir(Path cityDir, String groupId) throws Exception {
        return CityGroupPathUtil.resolveGroupDir(cityDir, groupId);
    }

    private static CityC6Stages.C6Summary filterC6SummaryByGroup(CityC6Stages.C6Summary summary, String groupId) {
        if (summary == null || groupId == null || groupId.isBlank()) return summary;
        CityC6Stages.C6Summary copy = new CityC6Stages.C6Summary();
        copy.ok = summary.ok;
        copy.city_id = summary.city_id;
        copy.rules_version = summary.rules_version;
        copy.fill_style = summary.fill_style;
        copy.generated_at_epoch_ms = summary.generated_at_epoch_ms;
        for (CityC6Stages.BuildAreaSummary area : summary.areas) {
            if (area != null && groupId.equals(area.group_id)) copy.areas.add(area);
        }
        return copy;
    }

    private static CityC6Stages.C6Layout filterC6LayoutByGroup(CityC6Stages.C6Layout layout, String groupId) {
        if (layout == null || groupId == null || groupId.isBlank()) return layout;
        CityC6Stages.C6Layout copy = new CityC6Stages.C6Layout();
        copy.ok = layout.ok;
        copy.city_id = layout.city_id;
        copy.fill_style = layout.fill_style;
        copy.version = layout.version;
        copy.generated_at_epoch_ms = layout.generated_at_epoch_ms;
        for (CityC6Stages.LayoutPlan plan : layout.plans) {
            if (plan != null && groupId.equals(plan.group_id)) copy.plans.add(plan);
        }
        return copy;
    }


    private static CityC6Stages.C6RectDecisionInput filterDecisionInputByGroup(CityC6Stages.C6RectDecisionInput input, String groupId) {
        if (input == null || groupId == null || groupId.isBlank()) return input;
        CityC6Stages.C6RectDecisionInput copy = new CityC6Stages.C6RectDecisionInput();
        copy.ok = input.ok;
        copy.city_id = input.city_id;
        copy.rect_limit = input.rect_limit;
        copy.min_total_primary_area_ratio = input.min_total_primary_area_ratio;
        copy.generated_at_epoch_ms = input.generated_at_epoch_ms;
        for (CityC6Stages.GroupDecisionInput item : input.groups) {
            if (item != null && groupId.equals(item.group_id)) copy.groups.add(item);
        }
        return copy;
    }

    private static CityC6Stages.C6RectCandidates filterCandidatesByGroup(CityC6Stages.C6RectCandidates candidates, String groupId) {
        if (candidates == null || groupId == null || groupId.isBlank()) return candidates;
        CityC6Stages.C6RectCandidates copy = new CityC6Stages.C6RectCandidates();
        copy.ok = candidates.ok;
        copy.city_id = candidates.city_id;
        copy.updated_at_epoch_ms = candidates.updated_at_epoch_ms;
        for (CityC6Stages.GroupRectCandidate item : candidates.items) {
            if (item != null && groupId.equals(item.group_id)) copy.items.add(item);
        }
        return copy;
    }

    private static CityC6Stages.C6RectValidation filterValidationByGroup(CityC6Stages.C6RectValidation validation, String groupId) {
        if (validation == null || groupId == null || groupId.isBlank()) return validation;
        CityC6Stages.C6RectValidation copy = new CityC6Stages.C6RectValidation();
        copy.ok = validation.ok;
        copy.city_id = validation.city_id;
        copy.updated_at_epoch_ms = validation.updated_at_epoch_ms;
        for (CityC6Stages.GroupRectValidation item : validation.items) {
            if (item != null && groupId.equals(item.group_id)) copy.items.add(item);
        }
        return copy;
    }

    private static CityC6Stages.BuildAreaSummary findBestAreaByGroup(CityC6Stages.C6Summary summary, String groupId) {
        if (summary == null || summary.areas == null || groupId == null) return null;
        CityC6Stages.BuildAreaSummary best = null;
        for (CityC6Stages.BuildAreaSummary area : summary.areas) {
            if (area == null || !groupId.equals(area.group_id)) continue;
            if (best == null || area.area_blocks > best.area_blocks) best = area;
        }
        return best;
    }

    private static List<CityC6Stages.RectDecision> parseSubmittedRects(JsonObject json) {
        List<CityC6Stages.RectDecision> rects = new ArrayList<>();
        if (json == null || !json.has("rects") || !json.get("rects").isJsonArray()) return rects;
        for (var element : json.getAsJsonArray("rects")) {
            if (!element.isJsonObject()) continue;
            JsonObject obj = element.getAsJsonObject();
            CityC6Stages.RectDecision rect = new CityC6Stages.RectDecision();
            rect.rect_id = readOptionalString(obj, "rect_id");
            rect.role = readOptionalString(obj, "role");
            if (rect.role == null) rect.role = "primary";
            rect.cx = obj.has("cx") ? obj.get("cx").getAsInt() : 0;
            rect.cz = obj.has("cz") ? obj.get("cz").getAsInt() : 0;
            rect.w = obj.has("w") ? obj.get("w").getAsInt() : 0;
            rect.h = obj.has("h") ? obj.get("h").getAsInt() : 0;
            rect.minX = obj.has("minX") ? obj.get("minX").getAsInt() : 0;
            rect.minZ = obj.has("minZ") ? obj.get("minZ").getAsInt() : 0;
            rect.maxX = obj.has("maxX") ? obj.get("maxX").getAsInt() : 0;
            rect.maxZ = obj.has("maxZ") ? obj.get("maxZ").getAsInt() : 0;
            CityC6Validation.normalizeRect(rect);
            rects.add(rect);
        }
        return rects;
    }

    private static Set<Integer> collectAreaIdsForGroup(CityC6Stages.C6Summary summary, String groupId) {
        Set<Integer> ids = new java.util.LinkedHashSet<>();
        if (summary == null || summary.areas == null) return ids;
        for (CityC6Stages.BuildAreaSummary area : summary.areas) {
            if (area != null && (groupId == null || groupId.isBlank() || groupId.equals(area.group_id))) ids.add(area.build_area_numeric_id);
        }
        return ids;
    }

    private static Map<Long, Integer> filterIndexByAreaIds(Map<Long, Integer> index, Set<Integer> areaIds) {
        if (index == null || areaIds == null || areaIds.isEmpty()) return index;
        Map<Long, Integer> filtered = new java.util.LinkedHashMap<>();
        for (Map.Entry<Long, Integer> entry : index.entrySet()) {
            if (entry.getValue() != null && areaIds.contains(entry.getValue())) filtered.put(entry.getKey(), entry.getValue());
        }
        return filtered;
    }

    private static CityC7Stages.C7Selection filterC7SelectionByGroup(CityC7Stages.C7Selection selection, String groupId) {
        if (selection == null || groupId == null || groupId.isBlank()) return selection;
        CityC7Stages.C7Selection copy = new CityC7Stages.C7Selection();
        copy.ok = selection.ok;
        copy.city_id = selection.city_id;
        copy.generated_at_epoch_ms = selection.generated_at_epoch_ms;
        copy.catalog_source = selection.catalog_source;
        copy.decision_source = selection.decision_source;
        copy.selection_mode = selection.selection_mode;
        copy.strict_tag_source = selection.strict_tag_source;
        copy.filtered_candidate_count = selection.filtered_candidate_count;
        copy.strict_filter_failure_reason = selection.strict_filter_failure_reason;
        copy.puzzle_depth = selection.puzzle_depth;
        if (selection.selections != null) {
            for (CityC7Stages.TemplateSelectionItem item : selection.selections) {
                if (item != null && groupId.equals(item.group_id)) copy.selections.add(item);
            }
        }
        if (selection.arrangements != null) {
            for (CityC7Stages.GroupArrangementDecision item : selection.arrangements) {
                if (item != null && groupId.equals(item.group_id)) copy.arrangements.add(item);
            }
        }
        return copy;
    }

    private static CityC7Stages.C7Selection loadC7Selection(Path cityDir, String groupId) throws Exception {
        if (groupId != null && !groupId.isBlank()) {
            Path groupFile = resolveGroupDir(cityDir, groupId).resolve("c7_selection.json");
            if (java.nio.file.Files.exists(groupFile)) {
                return new Gson().fromJson(java.nio.file.Files.readString(groupFile), CityC7Stages.C7Selection.class);
            }
        }
        return CityC7Stages.load(cityDir);
    }

    private static CityC7Stages.C7Selection loadC8GenerationSelection(Path cityDir, String groupId) throws Exception {
        CityC7Stages.C7Selection citySelection = CityC7Stages.load(cityDir);
        if (citySelection != null) return citySelection;
        return loadC7Selection(cityDir, groupId);
    }

    private static CityC8Stages.C8Plan filterC8PlanByGroup(CityC8Stages.C8Plan plan, String groupId, Set<Integer> areaIds) {
        if (plan == null || groupId == null || groupId.isBlank()) return plan;
        CityC8Stages.C8Plan copy = new CityC8Stages.C8Plan();
        copy.ok = plan.ok;
        copy.city_id = plan.city_id;
        copy.version = plan.version;
        copy.generated_at_epoch_ms = plan.generated_at_epoch_ms;
        for (CityC8Stages.FoundationItem item : plan.foundations) {
            if (item != null && (groupId.equals(item.group_id) || areaIds.contains(item.build_area_numeric_id))) copy.foundations.add(item);
        }
        return copy;
    }

    private static CityC9Stages.C9Result filterC9ResultByAreas(CityC9Stages.C9Result result, Set<Integer> areaIds) {
        if (result == null || areaIds == null || areaIds.isEmpty()) return result;
        CityC9Stages.C9Result copy = new CityC9Stages.C9Result();
        copy.placement = new CityC9Stages.C9Placement();
        copy.decoration = new CityC9Stages.C9Decoration();
        if (result.placement != null) {
            copy.placement.city_id = result.placement.city_id;
            copy.placement.generated_at_epoch_ms = result.placement.generated_at_epoch_ms;
            copy.placement.mode = result.placement.mode;
            copy.placement.apply_blocks = result.placement.apply_blocks;
            copy.placement.max_blocks = result.placement.max_blocks;
            copy.placement.enqueued_tasks_count = result.placement.enqueued_tasks_count;
            copy.placement.applied_tasks_count = result.placement.applied_tasks_count;
            for (CityC9Stages.PlacementItem item : result.placement.items) {
                if (item != null && areaIds.contains(item.build_area_numeric_id)) {
                    copy.placement.items.add(item);
                    copy.placement.changed_blocks_total += item.changed_blocks;
                }
            }
            copy.placement.processed_areas = copy.placement.items.size();
        }
        if (result.decoration != null) {
            copy.decoration.city_id = result.decoration.city_id;
            copy.decoration.generated_at_epoch_ms = result.decoration.generated_at_epoch_ms;
            for (CityC9Stages.DecorationItem item : result.decoration.items) {
                if (item != null) copy.decoration.items.add(item);
            }
        }
        copy.queue = result.queue;
        copy.queue_summary = result.queue_summary;
        return copy;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
