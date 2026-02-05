package com.user.terra_script.event;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.user.terra_script.world.NationGenManager;
import com.user.terra_script.world.city.CityInstance;
import com.user.terra_script.world.city.CityManager;
import com.user.terra_script.world.city.RoadInjector;
import com.user.terra_script.core.artifact.ArtifactStore;
import com.user.terra_script.core.artifact.ArtifactKey;
import com.user.terra_script.core.stage.StageContext;
import com.user.terra_script.core.workflow.FileStageStatusStore;
import com.user.terra_script.core.workflow.StageRegistry;
import com.user.terra_script.core.workflow.WorkflowEngine;
import com.user.terra_script.domain.world.stage.W3Stage;
import com.user.terra_script.domain.world.stage.W4Stage;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Mod.EventBusSubscriber(modid = "terra_script")
public class DevCommandHandler {
    private static final String MCP_BASE_URL = "http://localhost:5000";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final Gson GSON = new Gson();
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DEV_OUTPUT_FILE = "terra_script_mcp_dev_last.json";
    private static final String LORE_FILE = "world_lore.json";

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("dev")
                        .then(Commands.literal("rebuild_road")
                                .requires(src -> src.hasPermission(2))
                                .executes(ctx -> {
                                    CityManager mgr = CityManager.get();
                                    for (CityInstance city : mgr.getAllCities()) {
                                        city.isRoadsGenerated = false;
                                        city.roadBlocks = null;
                                    }
                                    for (CityInstance city : mgr.getAllCities()) {
                                        mgr.ensureRoadsGenerated(city.id);
                                    }
                                    RoadInjector.resetProcessing();
                                    ctx.getSource().sendSuccess(() ->
                                            Component.literal("Roads rebuilt. Reload chunks to apply."), false);
                                    return 1;
                                }))
                        .then(Commands.literal("stage1")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("city_id", StringArgumentType.string())
                                        .executes(ctx -> {
                                            String cityId = StringArgumentType.getString(ctx, "city_id");
                                            ServerLevel level = ctx.getSource().getLevel();
                                            try {
                                                var result = NationGenManager.Stage1Manager.computeAndSave(level, cityId);
                                                if (result == null) {
                                                    ctx.getSource().sendFailure(Component.literal("City not found: " + cityId));
                                                    return 0;
                                                }
                                                ctx.getSource().sendSuccess(() ->
                                                        Component.literal("Stage1 computed for " + cityId), false);
                                                return 1;
                                            } catch (Exception e) {
                                                ctx.getSource().sendFailure(Component.literal("Stage1 failed: " + e.getMessage()));
                                                return 0;
                                            }
                                        })))
                        .then(Commands.literal("stage2")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("city_id", StringArgumentType.string())
                                        .executes(ctx -> {
                                            String cityId = StringArgumentType.getString(ctx, "city_id");
                                            try {
                                                var result = NationGenManager.Stage2Manager.computeAndSave(cityId);
                                                if (result == null) {
                                                    ctx.getSource().sendFailure(Component.literal("Stage1 not found for: " + cityId));
                                                    return 0;
                                                }
                                                ctx.getSource().sendSuccess(() ->
                                                        Component.literal("Stage2 computed for " + cityId), false);
                                                return 1;
                                            } catch (Exception e) {
                                                ctx.getSource().sendFailure(Component.literal("Stage2 failed: " + e.getMessage()));
                                                return 0;
                                            }
                                        })))
                        .then(Commands.literal("stage")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("stage_id", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String stageId = StringArgumentType.getString(ctx, "stage_id");
                                            return runWorkflowStage(ctx, stageId);
                                        })))
                        .then(Commands.literal("mcp")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("api", StringArgumentType.word())
                                        .executes(ctx -> handleMcpCall(ctx, StringArgumentType.getString(ctx, "api"), null))
                                        .then(Commands.argument("json", StringArgumentType.greedyString())
                                                .executes(ctx -> handleMcpCall(ctx,
                                                        StringArgumentType.getString(ctx, "api"),
                                                        StringArgumentType.getString(ctx, "json"))))))
        );
    }

    private static int runWorkflowStage(CommandContext<CommandSourceStack> ctx, String stageIdRaw) {
        String stageId = stageIdRaw == null ? "" : stageIdRaw.trim().toUpperCase(Locale.ROOT);
        if (!"W3".equals(stageId) && !"W4".equals(stageId)) {
            ctx.getSource().sendFailure(Component.literal("Unknown stage: " + stageIdRaw + " (use W3 or W4)"));
            return 0;
        }

        try {
            ArtifactStore artifacts = new ArtifactStore();
            FileStageStatusStore statusStore = new FileStageStatusStore(artifacts);
            StageContext stageCtx = StageContext.forServer(ctx.getSource().getServer(), artifacts, statusStore);

            StageRegistry registry = new StageRegistry();
            registry.register(new W3Stage());
            registry.register(new W4Stage());

            WorkflowEngine engine = new WorkflowEngine(registry);
            engine.runStage(stageId, stageCtx);

            ctx.getSource().sendSuccess(() ->
                    Component.literal("Stage " + stageId + " completed."), false);

            List<ArtifactKey> keys = "W3".equals(stageId)
                    ? List.of(ArtifactKey.W3_CONTINENT_META_JSON, ArtifactKey.W3_OCEAN_META_JSON)
                    : List.of(ArtifactKey.W4_TERRAIN_FACTS_DAT, ArtifactKey.W4_TERRAIN_SUMMARY_JSON);
            for (ArtifactKey key : keys) {
                Path p = artifacts.resolve(ctx.getSource().getServer(), stageCtx.worldId, key);
                ctx.getSource().sendSuccess(() ->
                        Component.literal("Output: " + p), false);
            }
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Stage " + stageId + " failed: " + e.getMessage()));
            return 0;
        }
    }

    private static int handleMcpCall(CommandContext<CommandSourceStack> ctx, String apiRaw, String jsonArg) {
        String api = normalizeApi(apiRaw);
        if ("help".equalsIgnoreCase(api) || "list".equalsIgnoreCase(api)) {
            sendMcpHelp(ctx.getSource());
            return 1;
        }

        JsonObject args = null;
        if (jsonArg != null && !jsonArg.isBlank()) {
            try {
                args = parseJsonObject(jsonArg);
            } catch (Exception e) {
                ctx.getSource().sendFailure(Component.literal("Invalid JSON: " + e.getMessage()));
                return 0;
            }
        }

        try {
            ApiCallResult result = dispatchMcp(api, args);
            Path outPath = writeDevOutput(api, result.endpoint, result.request, result);
            String err = result.status >= 400 ? extractError(result.body) : null;
            if (result.status >= 400) {
                ctx.getSource().sendFailure(Component.literal("MCP " + api + " failed (" + result.status + "): " + err));
                ctx.getSource().sendSuccess(() -> Component.literal("Output: " + outPath), false);
                return 0;
            }
            ctx.getSource().sendSuccess(() ->
                    Component.literal("MCP " + api + " ok (" + result.status + "). Output: " + outPath), false);
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("MCP " + api + " failed: " + e.getMessage()));
            return 0;
        }
    }

    private static ApiCallResult dispatchMcp(String api, JsonObject args) throws Exception {
        switch (api) {
            case "W3_get_continents":
                return httpGet("/continents");
            case "W4_get_world_atlas":
                return httpGet("/world_atlas");
            case "W4_world_summary":
                return httpGet("/world_summary");
            case "W4_terrain_summary":
                return httpGet("/terrain_summary");
            case "T1_submit_blueprint":
                return httpPost("/t1_blueprint", args);
            case "T1_list_blueprints":
                return httpGet("/t1_blueprint");
            case "get_world_atlas":
                return httpGet("/world_atlas");
            case "world_summary":
                return httpGet("/world_summary");
            case "terrain_summary":
                return httpGet("/terrain_summary");
            case "continents":
                return httpGet("/continents");
            case "list_available_structures":
            case "structures": {
                ApiCallResult res = httpGet("/structures");
                if ("list_available_structures".equals(api)) {
                    JsonObject payload = args != null ? args : new JsonObject();
                    return filterStructures(res, payload);
                }
                if (args != null && (args.has("search_query") || args.has("limit"))) {
                    return filterStructures(res, args);
                }
                return res;
            }
            case "W4_scan_local_candidates":
            case "query_region":
                return httpPost("/query_region", args);
            case "establish_territory":
            case "create_territory": {
                JsonObject payload = args != null ? args.deepCopy() : new JsonObject();
                Integer color = parseColor(pickFirst(payload, "color"));
                if (color != null) payload.addProperty("color", color);
                return httpPost("/create_territory", payload);
            }
            case "get_territory_status":
            case "territory_status":
                return httpGet("/territory_status");
            case "freeze_status":
                return httpGet("/freeze_status");
            case "freeze_project":
                return httpPost("/freeze_project", args);
            case "establish_city": {
                JsonObject payload = buildCreateCityPayload(args);
                return httpPost("/create_city", payload);
            }
            case "create_city":
                return httpPost("/create_city", args);
            case "place_structure": {
                JsonObject payload = buildPlacePayload(args);
                return httpPost("/place", payload);
            }
            case "place":
                return httpPost("/place", args);
            case "city_stage1_data":
                return httpPost("/city_stage1_data", args);
            case "city_stage2_data":
                return httpPost("/city_stage2_data", args);
            case "city_heightmap":
                return httpPost("/city_heightmap", args);
            case "city_forbidden":
                return httpPost("/city_forbidden", args);
            case "city_buildable_groups":
                return httpPost("/city_buildable_groups", args);
            case "read_history_lore":
                return readHistoryLore();
            case "draft_nation_concept":
                return draftNationConcept(args);
            default:
                throw new IllegalArgumentException("Unknown MCP api: " + api);
        }
    }

    private static ApiCallResult httpGet(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(MCP_BASE_URL + path))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new ApiCallResult(res.statusCode(), res.body(), path, null);
    }

    private static ApiCallResult httpPost(String path, JsonObject body) throws Exception {
        JsonObject payload = body != null ? body : new JsonObject();
        String json = GSON.toJson(payload);
        HttpRequest req = HttpRequest.newBuilder(URI.create(MCP_BASE_URL + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new ApiCallResult(res.statusCode(), res.body(), path, payload);
    }

    private static ApiCallResult filterStructures(ApiCallResult res, JsonObject args) {
        if (res.status < 200 || res.status >= 300) return res;
        JsonElement parsed = tryParseJson(res.body);
        if (!parsed.isJsonArray()) return res;
        JsonArray arr = parsed.getAsJsonArray();
        String query = args != null && args.has("search_query")
                ? args.get("search_query").getAsString().toLowerCase(Locale.ROOT)
                : "";
        int limit = args != null && args.has("limit") ? args.get("limit").getAsInt() : 50;
        JsonArray filtered = new JsonArray();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject obj = el.getAsJsonObject();
            String id = obj.has("id") ? obj.get("id").getAsString().toLowerCase(Locale.ROOT) : "";
            String type = obj.has("type") ? obj.get("type").getAsString().toLowerCase(Locale.ROOT) : "";
            if (query.isBlank() || id.contains(query) || type.contains(query)) {
                filtered.add(obj);
                if (filtered.size() >= limit) break;
            }
        }
        return new ApiCallResult(res.status, GSON.toJson(filtered), res.endpoint, res.request);
    }

    private static JsonObject buildCreateCityPayload(JsonObject args) {
        if (args == null) throw new IllegalArgumentException("Missing JSON payload for establish_city");
        JsonObject payload = new JsonObject();

        JsonElement territoryId = pickFirst(args, "territory_id", "territoryId");
        JsonElement centerX = pickFirst(args, "center_x", "centerX");
        JsonElement centerZ = pickFirst(args, "center_z", "centerZ");
        JsonElement targetChunkCount = pickFirst(args, "target_chunk_count", "targetChunkCount");
        if (territoryId == null || centerX == null || centerZ == null || targetChunkCount == null) {
            throw new IllegalArgumentException("Required: territory_id, center_x, center_z, target_chunk_count");
        }

        payload.add("territoryId", territoryId);
        JsonElement continentId = pickFirst(args, "continent_id", "continentId");
        if (continentId != null) payload.add("continentId", continentId);
        payload.add("centerX", centerX);
        payload.add("centerZ", centerZ);
        payload.add("targetChunkCount", targetChunkCount);

        String bias = pickFirstString(args, "bias", "扩张倾向");
        payload.addProperty("bias", bias != null ? bias : "balanced");

        String ecology = pickFirstString(args, "ecology", "ecology_policy", "生态策略");
        if (ecology != null) payload.addProperty("ecology", ecology);

        String density = normalizeDensityValue(pickFirstString(args, "density", "功能密度"));
        if (density != null) payload.addProperty("density", density);

        JsonElement layerCount = pickFirst(args, "layer_count", "层级数量");
        if (layerCount != null) payload.add("layer_count", layerCount);

        JsonElement layerThresholds = pickFirst(args, "layer_thresholds", "层级阈值");
        if (layerThresholds != null) payload.add("layer_thresholds", layerThresholds);

        JsonArray layers = normalizeLayerConfigs(pickFirst(args, "layers", "层配置"));
        if (layers != null) payload.add("layers", layers);

        return payload;
    }

    private static JsonObject buildPlacePayload(JsonObject args) {
        if (args == null) throw new IllegalArgumentException("Missing JSON payload for place_structure");
        JsonElement xEl = pickFirst(args, "x");
        JsonElement zEl = pickFirst(args, "z");
        JsonElement idEl = pickFirst(args, "structure_id", "id");
        if (xEl == null || zEl == null || idEl == null) {
            throw new IllegalArgumentException("Required: x, z, structure_id");
        }
        int blockX = xEl.getAsInt();
        int blockZ = zEl.getAsInt();
        String structureId = idEl.getAsString();
        int chunkX = Math.floorDiv(blockX, 16);
        int chunkZ = Math.floorDiv(blockZ, 16);
        JsonObject payload = new JsonObject();
        payload.addProperty("x", chunkX);
        payload.addProperty("z", chunkZ);
        payload.addProperty("id", structureId);
        return payload;
    }

    private static ApiCallResult readHistoryLore() throws Exception {
        Path path = FMLPaths.GAMEDIR.get().resolve(LORE_FILE);
        JsonArray records = new JsonArray();
        if (Files.exists(path)) {
            String content = Files.readString(path);
            if (content != null && !content.isBlank()) {
                JsonElement data = JsonParser.parseString(content);
                if (data.isJsonArray()) records = data.getAsJsonArray();
            }
        }
        JsonObject res = new JsonObject();
        res.addProperty("count", records.size());
        res.add("records", records);
        return new ApiCallResult(200, GSON.toJson(res), "local:lore", null);
    }

    private static ApiCallResult draftNationConcept(JsonObject args) throws Exception {
        if (args == null) throw new IllegalArgumentException("Missing JSON payload for draft_nation_concept");
        Path path = FMLPaths.GAMEDIR.get().resolve(LORE_FILE);
        JsonArray records = new JsonArray();
        if (Files.exists(path)) {
            String content = Files.readString(path);
            if (content != null && !content.isBlank()) {
                JsonElement data = JsonParser.parseString(content);
                if (data.isJsonArray()) records = data.getAsJsonArray();
            }
        }
        JsonObject entry = args.deepCopy();
        entry.addProperty("timestamp", Instant.now().toString());
        records.add(entry);
        Files.writeString(path, PRETTY_GSON.toJson(records), StandardCharsets.UTF_8);

        JsonObject res = new JsonObject();
        res.addProperty("status", "saved");
        res.addProperty("count", records.size());
        boolean done = false;
        if (args.has("planned_index") && args.has("total_planned")) {
            try {
                done = args.get("planned_index").getAsInt() >= args.get("total_planned").getAsInt();
            } catch (Exception ignored) { }
        }
        res.addProperty("done", done);
        return new ApiCallResult(200, GSON.toJson(res), "local:lore", entry);
    }

    private static Integer parseColor(JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        if (el.isJsonPrimitive()) {
            JsonPrimitive prim = el.getAsJsonPrimitive();
            if (prim.isNumber()) return prim.getAsInt();
            if (prim.isString()) {
                String raw = prim.getAsString().trim();
                if (raw.startsWith("#")) raw = raw.substring(1);
                if (raw.startsWith("0x") || raw.startsWith("0X")) raw = raw.substring(2);
                try { return Integer.parseInt(raw, 16); } catch (Exception ignored) { }
            }
        }
        return null;
    }

    private static JsonArray normalizeLayerConfigs(JsonElement raw) {
        if (raw == null || !raw.isJsonArray()) return null;
        JsonArray layers = new JsonArray();
        for (JsonElement el : raw.getAsJsonArray()) {
            if (!el.isJsonObject()) continue;
            JsonObject entry = el.getAsJsonObject();
            JsonObject layer = new JsonObject();
            JsonElement name = pickFirst(entry, "name", "层名");
            if (name != null) layer.add("name", name);
            JsonElement type = pickFirst(entry, "type", "层类型");
            if (type != null) layer.add("type", type);
            JsonElement density = normalizeDensityElement(pickFirst(entry, "density", "功能密度"));
            if (density != null) layer.add("density", density);
            JsonElement ecology = pickFirst(entry, "ecology", "生态策略", "ecology_policy");
            if (ecology != null) layer.add("ecology", ecology);
            JsonElement wallLayer = pickFirst(entry, "wall_layer", "是否墙层");
            if (wallLayer != null) layer.add("wall_layer", wallLayer);
            JsonObject wall = normalizeWallConfig(pickFirst(entry, "wall", "墙体"));
            if (wall != null) layer.add("wall", wall);
            if (!layer.entrySet().isEmpty()) layers.add(layer);
        }
        return layers.size() > 0 ? layers : null;
    }

    private static JsonObject normalizeWallConfig(JsonElement raw) {
        if (raw == null || !raw.isJsonObject()) return null;
        JsonObject entry = raw.getAsJsonObject();
        JsonObject wall = new JsonObject();
        JsonElement type = pickFirst(entry, "type", "类型");
        if (type != null) wall.add("type", type);
        JsonElement thickness = pickFirst(entry, "thickness_blocks", "厚度方块");
        if (thickness != null) wall.add("thickness_blocks", thickness);
        JsonElement gateCount = pickFirst(entry, "gate_count", "城门数量");
        if (gateCount != null) wall.add("gate_count", gateCount);
        return wall.entrySet().isEmpty() ? null : wall;
    }

    private static JsonElement normalizeDensityElement(JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
            String normalized = normalizeDensityValue(el.getAsString());
            return new JsonPrimitive(normalized);
        }
        return el;
    }

    private static String normalizeDensityValue(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "medium".equals(normalized) ? "mid" : normalized;
    }

    private static JsonElement pickFirst(JsonObject obj, String... keys) {
        if (obj == null) return null;
        for (String key : keys) {
            if (obj.has(key) && !obj.get(key).isJsonNull()) return obj.get(key);
        }
        return null;
    }

    private static String pickFirstString(JsonObject obj, String... keys) {
        JsonElement el = pickFirst(obj, keys);
        if (el == null) return null;
        return el.isJsonPrimitive() ? el.getAsString() : null;
    }

    private static JsonObject parseJsonObject(String jsonArg) {
        JsonElement el = JsonParser.parseString(jsonArg);
        if (!el.isJsonObject()) {
            throw new IllegalArgumentException("JSON must be an object");
        }
        return el.getAsJsonObject();
    }

    private static Path writeDevOutput(String api, String endpoint, JsonObject request, ApiCallResult result) throws Exception {
        JsonObject out = new JsonObject();
        out.addProperty("timestamp", System.currentTimeMillis());
        out.addProperty("timestamp_iso", Instant.now().toString());
        out.addProperty("api", api);
        if (endpoint != null) out.addProperty("endpoint", endpoint);
        out.addProperty("status", result.status);
        out.add("request", request != null ? request : JsonNull.INSTANCE);
        out.add("response", tryParseJson(result.body));
        Path path = FMLPaths.GAMEDIR.get().resolve(DEV_OUTPUT_FILE);
        Files.writeString(path, PRETTY_GSON.toJson(out), StandardCharsets.UTF_8);
        return path;
    }

    private static JsonElement tryParseJson(String text) {
        if (text == null || text.isBlank()) return JsonNull.INSTANCE;
        try { return JsonParser.parseString(text); } catch (Exception e) { return new JsonPrimitive(text); }
    }

    private static String extractError(String body) {
        JsonElement parsed = tryParseJson(body);
        if (parsed.isJsonObject()) {
            JsonObject obj = parsed.getAsJsonObject();
            if (obj.has("error")) return obj.get("error").getAsString();
        }
        String msg = body == null ? "" : body;
        return msg.length() > 200 ? msg.substring(0, 200) + "..." : msg;
    }

    private static String normalizeApi(String apiRaw) {
        if (apiRaw == null) return "";
        String trimmed = apiRaw.trim();
        if (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
        return trimmed;
    }

    private static void sendMcpHelp(CommandSourceStack source) {
        String tools = String.join(", ",
                "W3_get_continents",
                "W4_get_world_atlas",
                "W4_world_summary",
                "W4_terrain_summary",
                "W4_scan_local_candidates",
                "T1_submit_blueprint",
                "T1_list_blueprints",
                "list_available_structures",
                "establish_territory",
                "get_territory_status",
                "establish_city",
                "place_structure",
                "city_stage1_data",
                "city_stage2_data",
                "city_heightmap",
                "city_forbidden",
                "city_buildable_groups",
                "freeze_status",
                "freeze_project",
                "read_history_lore",
                "draft_nation_concept"
        );
        String apis = String.join(", ",
                "continents",
                "world_atlas",
                "world_summary",
                "terrain_summary",
                "structures",
                "query_region",
                "create_territory",
                "territory_status",
                "create_city",
                "place",
                "city_stage1_data",
                "city_stage2_data",
                "city_heightmap",
                "city_forbidden",
                "city_buildable_groups",
                "freeze_status",
                "freeze_project"
        );
        source.sendSuccess(() -> Component.literal("MCP tools: " + tools + "\nRaw APIs: " + apis + "\nUsage: /dev mcp <api> [json]"), false);
    }

    private static class ApiCallResult {
        final int status;
        final String body;
        final String endpoint;
        final JsonObject request;

        ApiCallResult(int status, String body, String endpoint, JsonObject request) {
            this.status = status;
            this.body = body;
            this.endpoint = endpoint;
            this.request = request;
        }
    }
}
