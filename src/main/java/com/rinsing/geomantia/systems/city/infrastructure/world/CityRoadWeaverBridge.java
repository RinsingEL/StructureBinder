package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class CityRoadWeaverBridge {
    public static final String PROVIDER_AUTO = "auto";
    public static final String PROVIDER_ROADWEAVER = "roadweaver";
    public static final String PROVIDER_WORLDEDIT_DEBUG = "worldedit_debug";
    public static final String PROVIDER_NONE = "none";

    private CityRoadWeaverBridge() {
    }

    public static String normalizeProvider(String raw) {
        String value = raw == null || raw.isBlank() ? PROVIDER_AUTO : raw.trim().toLowerCase();
        return switch (value) {
            case PROVIDER_AUTO, PROVIDER_ROADWEAVER, PROVIDER_WORLDEDIT_DEBUG, PROVIDER_NONE -> value;
            default -> throw new IllegalArgumentException("Unsupported roadProvider: " + raw);
        };
    }

    public static JsonObject createConnectionPlan(JsonObject materializationPlan) {
        JsonObject plan = new JsonObject();
        plan.addProperty("schemaVersion", "city_roadweaver_connection_plan.v0.1");
        plan.addProperty("cityId", stringValue(materializationPlan, "cityId", ""));
        plan.addProperty("connectionStrategy", "priority_chain");
        plan.addProperty("generateImmediately", false);

        List<Endpoint> endpoints = endpoints(materializationPlan);
        JsonArray endpointArray = new JsonArray();
        endpoints.forEach(endpoint -> endpointArray.add(endpoint.asJson()));
        plan.add("endpoints", endpointArray);

        JsonArray connections = new JsonArray();
        for (int i = 1; i < endpoints.size(); i++) {
            JsonObject connection = new JsonObject();
            connection.addProperty("connectionId", "roadweaver_" + endpoints.get(i - 1).anchorId()
                    + "_to_" + endpoints.get(i).anchorId());
            connection.addProperty("fromAnchorId", endpoints.get(i - 1).anchorId());
            connection.addProperty("toAnchorId", endpoints.get(i).anchorId());
            connection.add("from", endpoints.get(i - 1).roadPoint().asJson());
            connection.add("to", endpoints.get(i).roadPoint().asJson());
            connections.add(connection);
        }
        plan.add("connections", connections);
        return plan;
    }

    public static JsonObject register(ServerLevel level, JsonObject connectionPlan, String requestedProvider) {
        String provider = normalizeProvider(requestedProvider);
        JsonObject report = new JsonObject();
        report.addProperty("schemaVersion", "city_roadweaver_registration_report.v0.1");
        report.addProperty("requestedProvider", provider);
        report.addProperty("roadweaverAvailable", available());
        report.addProperty("generateImmediately", false);

        if (PROVIDER_NONE.equals(provider)) {
            report.addProperty("status", "skipped");
            report.addProperty("reasonCode", "ROAD_PROVIDER_NONE");
            report.addProperty("message", "Road generation disabled by request.");
            return report;
        }
        if (PROVIDER_WORLDEDIT_DEBUG.equals(provider)) {
            report.addProperty("status", "skipped");
            report.addProperty("reasonCode", "ROAD_PROVIDER_WORLDEDIT_DEBUG");
            report.addProperty("message", "D7 debug road fallback will run after ledger completion.");
            return report;
        }
        if (!available()) {
            if (PROVIDER_ROADWEAVER.equals(provider)) {
                report.addProperty("status", "failed");
                report.addProperty("reasonCode", "ROADWEAVER_UNAVAILABLE");
                report.addProperty("message", "RoadWeaver mod is not loaded.");
            } else {
                report.addProperty("status", "fallback");
                report.addProperty("reasonCode", "ROADWEAVER_UNAVAILABLE");
                report.addProperty("message", "RoadWeaver mod is not loaded; D7 debug road fallback remains available.");
            }
            return report;
        }
        if (level == null) {
            report.addProperty("status", "failed");
            report.addProperty("reasonCode", "ROADWEAVER_LEVEL_UNAVAILABLE");
            report.addProperty("message", "ServerLevel is required for RoadWeaver registration.");
            return report;
        }

        try {
            Class<?> api = Class.forName("net.shiroha233.roadweaver.api.RoadNetworkApi");
            Method registerEndpoint = api.getMethod("registerStructureEndpoint",
                    ServerLevel.class, BlockPos.class, String.class, boolean.class);
            Method ensureConnection = api.getMethod("ensureConnection",
                    ServerLevel.class, BlockPos.class, BlockPos.class, boolean.class);

            JsonArray endpointResults = new JsonArray();
            for (JsonElement elem : array(connectionPlan, "endpoints")) {
                if (!elem.isJsonObject()) {
                    continue;
                }
                JsonObject endpoint = elem.getAsJsonObject();
                BlockPos pos = blockPos(endpoint.getAsJsonObject("roadPoint"));
                registerEndpoint.invoke(null, level, pos, stringValue(endpoint, "structureId", ""), false);
                JsonObject item = new JsonObject();
                item.addProperty("anchorId", stringValue(endpoint, "anchorId", ""));
                item.add("roadPoint", endpoint.getAsJsonObject("roadPoint").deepCopy());
                item.addProperty("status", "registered");
                endpointResults.add(item);
            }

            JsonArray connectionResults = new JsonArray();
            for (JsonElement elem : array(connectionPlan, "connections")) {
                if (!elem.isJsonObject()) {
                    continue;
                }
                JsonObject connection = elem.getAsJsonObject();
                ensureConnection.invoke(null, level,
                        blockPos(connection.getAsJsonObject("from")),
                        blockPos(connection.getAsJsonObject("to")),
                        false);
                JsonObject item = new JsonObject();
                item.addProperty("connectionId", stringValue(connection, "connectionId", ""));
                item.addProperty("fromAnchorId", stringValue(connection, "fromAnchorId", ""));
                item.addProperty("toAnchorId", stringValue(connection, "toAnchorId", ""));
                item.addProperty("status", "planned");
                connectionResults.add(item);
            }

            report.addProperty("status", "registered");
            report.addProperty("reasonCode", "ROADWEAVER_CONNECTIONS_REGISTERED");
            report.addProperty("message", "Registered City endpoints and planned RoadWeaver connections.");
            report.add("endpointResults", endpointResults);
            report.add("connectionResults", connectionResults);
            return report;
        } catch (ReflectiveOperationException ex) {
            report.addProperty("status", "failed");
            report.addProperty("reasonCode", "ROADWEAVER_API_UNAVAILABLE");
            report.addProperty("message", ex.getMessage() == null ? ex.toString() : ex.getMessage());
            return report;
        }
    }

    public static boolean shouldRunWorldEditDebugFallback(String requestedProvider, JsonObject registrationReport) {
        String provider = normalizeProvider(requestedProvider);
        if (PROVIDER_WORLDEDIT_DEBUG.equals(provider)) {
            return true;
        }
        if (!PROVIDER_AUTO.equals(provider)) {
            return false;
        }
        String status = stringValue(registrationReport, "status", "");
        return "fallback".equals(status);
    }

    public static boolean roadWeaverRegistered(JsonObject registrationReport) {
        return "registered".equals(stringValue(registrationReport, "status", ""));
    }

    public static boolean available() {
        try {
            ModList modList = ModList.get();
            return modList != null && modList.isLoaded("roadweaver");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static List<Endpoint> endpoints(JsonObject materializationPlan) {
        List<Endpoint> endpoints = new ArrayList<>();
        int index = 0;
        for (JsonElement elem : array(materializationPlan, "plannedWorldgenStructures")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject item = elem.getAsJsonObject();
            if (!"planned_worldgen".equals(stringValue(item, "status", ""))) {
                continue;
            }
            BlockBounds actual = bounds(item.has("lockedActualFootprint") && item.get("lockedActualFootprint").isJsonObject()
                    ? item.getAsJsonObject("lockedActualFootprint")
                    : item.getAsJsonObject("actualFootprint"));
            BlockPoint point = endpoint(actual);
            endpoints.add(new Endpoint(
                    stringValue(item, "anchorId", "anchor_" + index),
                    stringValue(item, "structureId", "unknown"),
                    intValue(item, "priority", index),
                    actual,
                    point));
            index++;
        }
        endpoints.sort(Comparator.comparingInt(Endpoint::priority).thenComparing(Endpoint::anchorId));
        return List.copyOf(endpoints);
    }

    private static BlockPoint endpoint(BlockBounds bounds) {
        return new BlockPoint(bounds.center().x(), bounds.maxZ() + 3);
    }

    private static JsonArray array(JsonObject obj, String key) {
        return obj != null && obj.has(key) && obj.get(key).isJsonArray()
                ? obj.getAsJsonArray(key)
                : new JsonArray();
    }

    private static BlockPos blockPos(JsonObject obj) {
        return new BlockPos(intValue(obj, "x", 0), 0, intValue(obj, "z", 0));
    }

    private static BlockBounds bounds(JsonObject obj) {
        return new BlockBounds(intValue(obj, "minX", 0), intValue(obj, "minZ", 0),
                intValue(obj, "maxX", 0), intValue(obj, "maxZ", 0));
    }

    private static String stringValue(JsonObject obj, String key, String fallback) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsString()
                : fallback;
    }

    private static int intValue(JsonObject obj, String key, int fallback) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsInt()
                : fallback;
    }

    private record Endpoint(String anchorId, String structureId, int priority, BlockBounds footprint,
                            BlockPoint roadPoint) {
        JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("anchorId", anchorId);
            obj.addProperty("structureId", structureId);
            obj.addProperty("priority", priority);
            obj.add("lockedActualFootprint", boundsJson(footprint));
            obj.add("roadPoint", roadPoint.asJson());
            return obj;
        }
    }

    private static JsonObject boundsJson(BlockBounds bounds) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minX", bounds.minX());
        obj.addProperty("minZ", bounds.minZ());
        obj.addProperty("maxX", bounds.maxX());
        obj.addProperty("maxZ", bounds.maxZ());
        return obj;
    }
}
