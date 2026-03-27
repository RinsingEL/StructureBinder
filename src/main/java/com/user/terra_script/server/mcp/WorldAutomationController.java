package com.user.terra_script.server.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.user.terra_script.domain.world.scan.service.SatelliteScanner;
import com.user.terra_script.server.http.HttpUtil;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;

public class WorldAutomationController {
    private static final Gson GSON = new Gson();
    private final MinecraftServer server;

    public WorldAutomationController(MinecraftServer server) {
        this.server = server;
    }

    public void handleWorldScanStart(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            JsonObject req = parseBody(exchange);
            int chunkRadius = req.has("chunk_radius") ? req.get("chunk_radius").getAsInt() : 500;
            int targetResolution = req.has("target_resolution") ? req.get("target_resolution").getAsInt() : 1024;
            JsonObject res = WorldAutomationService.startWorldScan(server, chunkRadius, targetResolution);
            HttpUtil.sendResponse(exchange, 200, GSON.toJson(res));
        } catch (IllegalArgumentException | IllegalStateException e) {
            HttpUtil.sendResponse(exchange, 400, "{\"error\": \"" + escape(e.getMessage()) + "\"}");
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleWorldScanStatus(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "GET")) return;
        try {
            SatelliteScanner.ScanProgressSnapshot snapshot = SatelliteScanner.getProgressSnapshot();
            JsonObject res = new JsonObject();
            res.addProperty("in_progress", snapshot.inProgress());
            res.addProperty("label", snapshot.label());
            res.addProperty("done", snapshot.done());
            res.addProperty("total", snapshot.total());
            res.addProperty("percent", snapshot.percent());
            res.addProperty("elapsed_ms", snapshot.elapsedMs());
            HttpUtil.sendResponse(exchange, 200, GSON.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleWorldScanCancel(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        SatelliteScanner.stopScanning();
        HttpUtil.sendResponse(exchange, 200, "{\"ok\":true,\"message\":\"cancel_requested\"}");
    }

    public void handleW3Cluster(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            JsonObject req = parseBody(exchange);
            int continentMinSize = req.has("continent_min_size") ? req.get("continent_min_size").getAsInt() : 5;
            int oceanMinMultiplier = req.has("ocean_min_size_multiplier") ? req.get("ocean_min_size_multiplier").getAsInt() : 10;
            int mergeDistance = req.has("merge_distance") ? req.get("merge_distance").getAsInt() : 0;
            JsonObject res = WorldAutomationService.clusterWorld(server, continentMinSize, oceanMinMultiplier, mergeDistance);
            HttpUtil.sendResponse(exchange, 200, GSON.toJson(res));
        } catch (IllegalArgumentException | IllegalStateException e) {
            HttpUtil.sendResponse(exchange, 400, "{\"error\": \"" + escape(e.getMessage()) + "\"}");
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleW4RegionScan(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            JsonObject req = parseBody(exchange);
            int regionId = req.has("region_id") ? req.get("region_id").getAsInt() : -1;
            if (regionId <= 0) throw new IllegalArgumentException("region_id is required");
            int padding = req.has("padding_blocks") ? req.get("padding_blocks").getAsInt() : 128;
            int scanStep = req.has("scan_step") ? req.get("scan_step").getAsInt() : 16;
            JsonObject res = WorldAutomationService.scanRegion(server, regionId, padding, scanStep);
            HttpUtil.sendResponse(exchange, 200, GSON.toJson(res));
        } catch (IllegalArgumentException | IllegalStateException e) {
            HttpUtil.sendResponse(exchange, 400, "{\"error\": \"" + escape(e.getMessage()) + "\"}");
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleW4RegionStatus(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "GET")) return;
        try {
            String regionIdRaw = getQueryParam(exchange, "region_id");
            int regionId = regionIdRaw == null || regionIdRaw.isBlank() ? -1 : Integer.parseInt(regionIdRaw);
            if (regionId <= 0) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"region_id query parameter is required\"}");
                return;
            }
            JsonObject res = WorldAutomationService.getRegionStatus(regionId);
            HttpUtil.sendResponse(exchange, 200, GSON.toJson(res));
        } catch (IllegalArgumentException e) {
            HttpUtil.sendResponse(exchange, 400, "{\"error\": \"" + escape(e.getMessage()) + "\"}");
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleW4Export(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            JsonObject req = parseBody(exchange);
            boolean allCached = req.has("all_cached_regions") && req.get("all_cached_regions").getAsBoolean();
            Integer regionId = req.has("region_id") ? req.get("region_id").getAsInt() : null;
            JsonObject res = WorldAutomationService.exportW4(server, regionId, allCached);
            HttpUtil.sendResponse(exchange, 200, GSON.toJson(res));
        } catch (IllegalArgumentException | IllegalStateException e) {
            HttpUtil.sendResponse(exchange, 400, "{\"error\": \"" + escape(e.getMessage()) + "\"}");
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    private static JsonObject parseBody(HttpExchange exchange) throws IOException {
        String body = HttpUtil.readBody(exchange);
        return body == null || body.isBlank() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
    }

    private static String getQueryParam(HttpExchange exchange, String key) {
        String raw = exchange.getRequestURI() != null ? exchange.getRequestURI().getQuery() : null;
        if (raw == null || raw.isBlank()) return null;
        String[] pairs = raw.split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 0) continue;
            if (key.equals(kv[0])) return kv.length > 1 ? kv[1] : "";
        }
        return null;
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
