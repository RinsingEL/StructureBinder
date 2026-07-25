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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class CityRoadWeaverBridge {
    public static final String PROVIDER_AUTO = "auto";
    public static final String PROVIDER_ROADWEAVER = "roadweaver";
    public static final String PROVIDER_WORLDEDIT_DEBUG = "worldedit_debug";
    public static final String PROVIDER_NONE = "none";

    public static final String REASON_TEMPLATE_ROAD_ENTRANCE_MISSING = "TEMPLATE_ROAD_ENTRANCE_MISSING";
    public static final String REASON_REGISTRATION_FAILED = "ROADWEAVER_REGISTRATION_FAILED";

    private CityRoadWeaverBridge() {
    }

    public static String normalizeProvider(String raw) {
        String value = raw == null || raw.isBlank() ? PROVIDER_AUTO : raw.trim().toLowerCase();
        return switch (value) {
            case PROVIDER_AUTO, PROVIDER_ROADWEAVER, PROVIDER_WORLDEDIT_DEBUG, PROVIDER_NONE -> value;
            default -> throw new IllegalArgumentException("Unsupported roadProvider: " + raw);
        };
    }

    /** Extracts only placement-plan transformed entrances; it never derives an entrance from a bbox. */
    public static EndpointExtraction extractRoadEndpoints(JsonObject materializationPlan) {
        List<RoadEndpoint> endpoints = new ArrayList<>();
        JsonArray errors = new JsonArray();
        int index = 0;
        for (JsonElement elem : array(materializationPlan, "plannedWorldgenStructures")) {
            if (!elem.isJsonObject()) {
                addError(errors, "", REASON_TEMPLATE_ROAD_ENTRANCE_MISSING,
                        "Planned structure item is not an object.");
                continue;
            }
            JsonObject item = elem.getAsJsonObject();
            if (!"planned_worldgen".equals(stringValue(item, "status", ""))) {
                continue;
            }
            String anchorId = stringValue(item, "anchorId", "anchor_" + index++);
            String placementGroupId = stringValue(item, "placementGroupId", anchorId);
            JsonObject placement = placementPlan(item);
            if (placement == null) {
                addError(errors, anchorId, REASON_TEMPLATE_ROAD_ENTRANCE_MISSING,
                        "Template placement plan with transformed roadEntrances[] is required.");
                continue;
            }
            String templateId = firstText(placement, item, "templateId", "templateRef");
            String templateHash = firstText(placement, item, "templateHash", "contentHash");
            JsonObject transformed = jsonObject(placement, "transformed");
            JsonArray entrances = transformed == null ? null : jsonArray(transformed, "roadEntrances");
            if (entrances == null) {
                entrances = jsonArray(placement, "transformedRoadEntrances");
            }
            if (entrances == null) {
                addError(errors, anchorId, REASON_TEMPLATE_ROAD_ENTRANCE_MISSING,
                        "Template placement plan is missing transformed.roadEntrances[].");
                continue;
            }
            if (templateId.isBlank() || templateHash.isBlank() || entrances.isEmpty()) {
                addError(errors, anchorId, REASON_TEMPLATE_ROAD_ENTRANCE_MISSING,
                        "templateId, templateHash and at least one transformed road entrance are required.");
                continue;
            }
            BlockPoint anchor = placementAnchor(placement, item);
            for (int entranceIndex = 0; entranceIndex < entrances.size(); entranceIndex++) {
                JsonElement entranceElement = entrances.get(entranceIndex);
                if (!entranceElement.isJsonObject()) {
                    addError(errors, anchorId, REASON_TEMPLATE_ROAD_ENTRANCE_MISSING,
                            "transformed.roadEntrances[" + entranceIndex + "] is not an object.");
                    continue;
                }
                JsonObject entrance = entranceElement.getAsJsonObject();
                String entranceId = stringValue(entrance, "entranceId", "entrance_" + entranceIndex);
                String direction = stringValue(entrance, "direction", "");
                BlockPoint roadPoint = entranceWorldPoint(entrance, anchor);
                if (direction.isBlank() || roadPoint == null) {
                    addError(errors, anchorId, REASON_TEMPLATE_ROAD_ENTRANCE_MISSING,
                            "Each transformed road entrance needs direction and relative/world coordinates.");
                    continue;
                }
                endpoints.add(new RoadEndpoint(
                        anchorId + "::" + entranceId,
                        anchorId,
                        placementGroupId,
                        entranceId,
                        templateId,
                        templateHash,
                        intValue(item, "priority", index),
                        optionalBounds(item, "lockedActualFootprint", "actualFootprint"),
                        roadPoint,
                        direction));
            }
        }
        endpoints.sort(Comparator.comparingInt(RoadEndpoint::priority).thenComparing(RoadEndpoint::endpointId));
        return new EndpointExtraction(List.copyOf(endpoints), errors);
    }

    public static JsonObject createConnectionPlan(JsonObject materializationPlan) {
        EndpointExtraction extraction = extractRoadEndpoints(materializationPlan);
        JsonObject plan = new JsonObject();
        plan.addProperty("schemaVersion", "city_roadweaver_connection_plan.v0.2");
        plan.addProperty("cityId", stringValue(materializationPlan, "cityId", ""));
        plan.addProperty("connectionStrategy", "group_spatial_mst");
        plan.addProperty("generateImmediately", false);
        plan.addProperty("transactional", true);
        plan.addProperty("validationStatus", extraction.valid() ? "valid" : "failed");
        if (!extraction.valid()) {
            plan.addProperty("reasonCode", REASON_TEMPLATE_ROAD_ENTRANCE_MISSING);
            plan.add("validationErrors", extraction.errors().deepCopy());
        }

        JsonArray endpointArray = new JsonArray();
        extraction.endpoints().forEach(endpoint -> endpointArray.add(endpoint.asJson()));
        plan.add("endpoints", endpointArray);

        JsonArray connections = new JsonArray();
        int intraGroupConnectionCount = 0;
        int interGroupConnectionCount = 0;
        if (extraction.valid()) {
            for (RoadConnection connection : groupSpatialMst(extraction.endpoints())) {
                connections.add(connection.asJson());
                if (connection.scope() == ConnectionScope.INTRA_GROUP) {
                    intraGroupConnectionCount++;
                } else {
                    interGroupConnectionCount++;
                }
            }
        }
        plan.add("connections", connections);
        plan.addProperty("endpointCount", endpointArray.size());
        plan.addProperty("connectionCount", connections.size());
        plan.addProperty("placementGroupCount", extraction.endpoints().stream()
                .map(RoadEndpoint::placementGroupId).distinct().count());
        plan.addProperty("intraGroupConnectionCount", intraGroupConnectionCount);
        plan.addProperty("interGroupConnectionCount", interGroupConnectionCount);
        return plan;
    }

    private static List<RoadConnection> groupSpatialMst(List<RoadEndpoint> endpoints) {
        Map<String, List<RoadEndpoint>> groups = new TreeMap<>();
        for (RoadEndpoint endpoint : endpoints) {
            groups.computeIfAbsent(endpoint.placementGroupId(), ignored -> new ArrayList<>()).add(endpoint);
        }
        groups.values().forEach(values -> values.sort(Comparator.comparing(RoadEndpoint::endpointId)));

        List<RoadConnection> result = new ArrayList<>();
        for (Map.Entry<String, List<RoadEndpoint>> entry : groups.entrySet()) {
            result.addAll(endpointMst(entry.getValue(), ConnectionScope.INTRA_GROUP));
        }

        List<String> groupIds = List.copyOf(groups.keySet());
        List<RoadConnection> groupCandidates = new ArrayList<>();
        for (int i = 0; i < groupIds.size(); i++) {
            for (int j = i + 1; j < groupIds.size(); j++) {
                groupCandidates.add(closestConnection(groups.get(groupIds.get(i)), groups.get(groupIds.get(j)),
                        ConnectionScope.INTER_GROUP));
            }
        }
        result.addAll(kruskal(groupIds, groupCandidates, connection -> connection.from().placementGroupId(),
                connection -> connection.to().placementGroupId()));
        return List.copyOf(result);
    }

    private static List<RoadConnection> endpointMst(List<RoadEndpoint> endpoints, ConnectionScope scope) {
        if (endpoints.size() < 2) {
            return List.of();
        }
        List<RoadConnection> candidates = new ArrayList<>();
        for (int i = 0; i < endpoints.size(); i++) {
            for (int j = i + 1; j < endpoints.size(); j++) {
                candidates.add(connection(endpoints.get(i), endpoints.get(j), scope));
            }
        }
        return kruskal(endpoints.stream().map(RoadEndpoint::endpointId).toList(), candidates,
                connection -> connection.from().endpointId(), connection -> connection.to().endpointId());
    }

    private static RoadConnection closestConnection(List<RoadEndpoint> fromGroup,
                                                    List<RoadEndpoint> toGroup,
                                                    ConnectionScope scope) {
        RoadConnection best = null;
        for (RoadEndpoint from : fromGroup) {
            for (RoadEndpoint to : toGroup) {
                RoadConnection candidate = connection(from, to, scope);
                if (best == null || ROAD_CONNECTION_ORDER.compare(candidate, best) < 0) {
                    best = candidate;
                }
            }
        }
        return Objects.requireNonNull(best, "placement groups must contain at least one endpoint");
    }

    private static <T> List<RoadConnection> kruskal(List<T> nodes,
                                                     List<RoadConnection> candidates,
                                                     java.util.function.Function<RoadConnection, T> fromNode,
                                                     java.util.function.Function<RoadConnection, T> toNode) {
        UnionFind<T> unionFind = new UnionFind<>(nodes);
        List<RoadConnection> selected = new ArrayList<>();
        for (RoadConnection candidate : candidates.stream().sorted(ROAD_CONNECTION_ORDER).toList()) {
            if (unionFind.union(fromNode.apply(candidate), toNode.apply(candidate))) {
                selected.add(candidate);
                if (selected.size() == Math.max(0, nodes.size() - 1)) {
                    break;
                }
            }
        }
        return List.copyOf(selected);
    }

    private static RoadConnection connection(RoadEndpoint first, RoadEndpoint second, ConnectionScope scope) {
        RoadEndpoint from = first.endpointId().compareTo(second.endpointId()) <= 0 ? first : second;
        RoadEndpoint to = from == first ? second : first;
        long distance = Math.abs((long) from.roadPoint().x() - to.roadPoint().x())
                + Math.abs((long) from.roadPoint().z() - to.roadPoint().z());
        return new RoadConnection(from, to, scope, distance);
    }

    private static final Comparator<RoadConnection> ROAD_CONNECTION_ORDER = Comparator
            .comparingLong(RoadConnection::distanceBlocks)
            .thenComparing(connection -> connection.from().endpointId())
            .thenComparing(connection -> connection.to().endpointId());

    public static JsonObject register(ServerLevel level, JsonObject connectionPlan, String requestedProvider) {
        String provider = normalizeProvider(requestedProvider);
        JsonObject report = baseReport(provider);
        boolean roadWeaverAvailable = available();
        report.addProperty("roadweaverAvailable", roadWeaverAvailable);

        if (PROVIDER_NONE.equals(provider)) {
            return skipped(report, "ROAD_PROVIDER_NONE", "Road generation disabled by request.");
        }
        if (PROVIDER_WORLDEDIT_DEBUG.equals(provider)) {
            return skipped(report, "ROAD_PROVIDER_WORLDEDIT_DEBUG",
                    "D7 debug road fallback will run after ledger completion.");
        }
        if (!roadWeaverAvailable) {
            if (PROVIDER_AUTO.equals(provider)) {
                return skipped(report, "ROADWEAVER_UNAVAILABLE",
                        "RoadWeaver mod is not loaded; automatic WorldEdit debug road fallback is disabled. "
                                + "Use roadProvider=worldedit_debug for legacy debug roads.");
            }
            throwRegistrationFailure(failure(report, "ROADWEAVER_UNAVAILABLE",
                    "RoadWeaver mod is not loaded.", false));
        }
        if (level == null) {
            throwRegistrationFailure(failure(report, "ROADWEAVER_LEVEL_UNAVAILABLE",
                    "ServerLevel is required for RoadWeaver registration.", false));
        }

        JsonArray validationErrors = validateRegistrationPlan(connectionPlan);
        if (!validationErrors.isEmpty()) {
            JsonObject failed = failure(report, firstReason(validationErrors, REASON_REGISTRATION_FAILED),
                    "RoadWeaver registration plan failed validation.", false);
            failed.add("validationErrors", validationErrors.deepCopy());
            throwRegistrationFailure(failed);
        }

        JsonArray endpointResults = new JsonArray();
        JsonArray connectionResults = new JsonArray();
        boolean registrationStarted = false;
        report.add("endpointResults", endpointResults);
        report.add("connectionResults", connectionResults);
        try {
            Class<?> api;
            Method registerEndpoint;
            Method ensureConnection;
            try {
                api = Class.forName("net.shiroha233.roadweaver.api.RoadNetworkApi");
                registerEndpoint = api.getMethod("registerStructureEndpoint",
                        ServerLevel.class, BlockPos.class, String.class, boolean.class);
                ensureConnection = api.getMethod("ensureConnection",
                        ServerLevel.class, BlockPos.class, BlockPos.class, boolean.class);
            } catch (ReflectiveOperationException ex) {
                JsonObject failed = failure(report, "ROADWEAVER_API_UNAVAILABLE", message(ex), false);
                throwRegistrationFailure(failed);
                return failed;
            }

            for (JsonElement elem : array(connectionPlan, "endpoints")) {
                JsonObject endpoint = elem.getAsJsonObject();
                registrationStarted = true;
                registerEndpoint.invoke(null, level, blockPos(endpoint.getAsJsonObject("roadPoint")),
                        stringValue(endpoint, "templateId", ""), false);
                JsonObject item = new JsonObject();
                item.addProperty("endpointId", stringValue(endpoint, "endpointId", ""));
                item.addProperty("anchorId", stringValue(endpoint, "anchorId", ""));
                item.addProperty("entranceId", stringValue(endpoint, "entranceId", ""));
                item.add("roadPoint", endpoint.getAsJsonObject("roadPoint").deepCopy());
                item.addProperty("status", "registered");
                endpointResults.add(item);
            }

            for (JsonElement elem : array(connectionPlan, "connections")) {
                JsonObject connection = elem.getAsJsonObject();
                registrationStarted = true;
                ensureConnection.invoke(null, level,
                        blockPos(connection.getAsJsonObject("from")),
                        blockPos(connection.getAsJsonObject("to")), false);
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
            report.addProperty("roadWeaverRegistered", true);
            report.addProperty("partialRegistration", false);
            report.addProperty("registrationStarted", true);
            return report;
        } catch (RegistrationException ex) {
            throw ex;
        } catch (Throwable ex) {
            JsonObject failed = failure(report, "ROADWEAVER_REGISTRATION_FAILED", message(ex),
                    registrationStarted || endpointResults.size() > 0 || connectionResults.size() > 0);
            failed.add("endpointResults", endpointResults.deepCopy());
            failed.add("connectionResults", connectionResults.deepCopy());
            throwRegistrationFailure(failed);
            return failed;
        }
    }

    public static boolean shouldRunWorldEditDebugFallback(String requestedProvider, JsonObject registrationReport) {
        String provider = normalizeProvider(requestedProvider);
        return PROVIDER_WORLDEDIT_DEBUG.equals(provider);
    }

    public static boolean roadWeaverRegistered(JsonObject registrationReport) {
        return "registered".equals(stringValue(registrationReport, "status", ""))
                && !booleanValue(registrationReport, "partialRegistration", false);
    }

    public static boolean available() {
        try {
            ModList modList = ModList.get();
            return modList != null && modList.isLoaded("roadweaver");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static JsonObject baseReport(String provider) {
        JsonObject report = new JsonObject();
        report.addProperty("schemaVersion", "city_roadweaver_registration_report.v0.1");
        report.addProperty("requestedProvider", provider);
        report.addProperty("generateImmediately", false);
        report.addProperty("transactional", true);
        report.addProperty("roadWeaverRegistered", false);
        report.addProperty("partialRegistration", false);
        report.addProperty("registrationStarted", false);
        return report;
    }

    private static JsonObject skipped(JsonObject report, String reasonCode, String message) {
        report.addProperty("status", "skipped");
        report.addProperty("reasonCode", reasonCode);
        report.addProperty("message", message);
        return report;
    }

    private static JsonObject failure(JsonObject report, String reasonCode, String message,
                                      boolean partialRegistration) {
        report.addProperty("status", "failed");
        report.addProperty("reasonCode", reasonCode);
        report.addProperty("message", message);
        report.addProperty("roadWeaverRegistered", false);
        report.addProperty("partialRegistration", partialRegistration);
        report.addProperty("registrationStarted", partialRegistration);
        return report;
    }

    private static void throwRegistrationFailure(JsonObject report) {
        throw new RegistrationException(report);
    }

    private static JsonArray validateRegistrationPlan(JsonObject connectionPlan) {
        JsonArray errors = new JsonArray();
        if (connectionPlan == null) {
            addError(errors, "", REASON_REGISTRATION_FAILED,
                    "Connection plan must be created from a fully validated transformed entrance plan.");
            return errors;
        }
        if ("failed".equals(stringValue(connectionPlan, "validationStatus", ""))) {
            if (connectionPlan.has("validationErrors")
                    && connectionPlan.get("validationErrors").isJsonArray()) {
                return connectionPlan.getAsJsonArray("validationErrors").deepCopy();
            }
            addError(errors, "", REASON_REGISTRATION_FAILED,
                    "Connection plan must be created from a fully validated transformed entrance plan.");
            return errors;
        }
        JsonArray endpoints = array(connectionPlan, "endpoints");
        if (endpoints.isEmpty()) {
            addError(errors, "", REASON_REGISTRATION_FAILED,
                    "At least one transformed road entrance is required for RoadWeaver registration.");
            return errors;
        }
        for (JsonElement elem : endpoints) {
            if (!elem.isJsonObject()) {
                addError(errors, "", REASON_REGISTRATION_FAILED, "Endpoint is not an object.");
                continue;
            }
            JsonObject endpoint = elem.getAsJsonObject();
            JsonObject point = jsonObject(endpoint, "roadPoint");
            if (point == null || !point.has("x") || !point.has("z")
                    || stringValue(endpoint, "direction", "").isBlank()
                    || stringValue(endpoint, "templateId", "").isBlank()
                    || stringValue(endpoint, "templateHash", "").isBlank()) {
                addError(errors, stringValue(endpoint, "anchorId", ""), REASON_REGISTRATION_FAILED,
                        "Endpoint coordinates, direction, templateId and templateHash are required.");
            }
        }
        for (JsonElement elem : array(connectionPlan, "connections")) {
            if (!elem.isJsonObject()) {
                addError(errors, "", REASON_REGISTRATION_FAILED, "Connection is not an object.");
                continue;
            }
            JsonObject connection = elem.getAsJsonObject();
            JsonObject from = jsonObject(connection, "from");
            JsonObject to = jsonObject(connection, "to");
            if (from == null || to == null || !from.has("x") || !from.has("z")
                    || !to.has("x") || !to.has("z")
                    || stringValue(connection, "connectionId", "").isBlank()) {
                addError(errors, "", REASON_REGISTRATION_FAILED,
                        "Connection endpoints and connectionId are required.");
            }
        }
        return errors;
    }

    private static String firstReason(JsonArray errors, String fallback) {
        if (!errors.isEmpty() && errors.get(0).isJsonObject()) {
            return stringValue(errors.get(0).getAsJsonObject(), "reasonCode", fallback);
        }
        return fallback;
    }

    private static JsonObject placementPlan(JsonObject item) {
        for (String key : List.of("templatePlacementPlan", "placementPlan", "templatePlacement")) {
            JsonObject value = jsonObject(item, key);
            if (value != null) {
                return value;
            }
        }
        return item.has("transformed") || item.has("transformedRoadEntrances") ? item : null;
    }

    private static BlockPoint placementAnchor(JsonObject placement, JsonObject item) {
        BlockPoint point = point(jsonObject(placement, "anchorBlock"));
        if (point != null) {
            return point;
        }
        point = point(jsonObject(placement, "worldAnchor"));
        if (point != null) {
            return point;
        }
        point = point(jsonObject(item, "anchorBlock"));
        if (point != null) {
            return point;
        }
        point = point(jsonObject(item, "worldAnchor"));
        return point != null ? point : point(jsonObject(item, "commandAnchorBlock"));
    }

    private static BlockPoint entranceWorldPoint(JsonObject entrance, BlockPoint anchor) {
        BlockPoint world = point(jsonObject(entrance, "worldPosition"));
        if (world == null) {
            world = point(jsonObject(entrance, "worldPoint"));
        }
        if (world == null) {
            world = point(jsonObject(entrance, "roadPoint"));
        }
        if (world != null) {
            return world;
        }
        JsonObject relative = jsonObject(entrance, "relativePosition");
        if (relative == null) {
            relative = jsonObject(entrance, "position");
        }
        BlockPoint relativePoint = point(relative);
        return relativePoint == null || anchor == null
                ? null : new BlockPoint(anchor.x() + relativePoint.x(), anchor.z() + relativePoint.z());
    }

    private static JsonObject jsonObject(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonObject()
                ? object.getAsJsonObject(key) : null;
    }

    private static JsonArray jsonArray(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonArray()
                ? object.getAsJsonArray(key) : null;
    }

    private static BlockPoint point(JsonObject object) {
        return object != null && object.has("x") && object.has("z")
                ? new BlockPoint(object.get("x").getAsInt(), object.get("z").getAsInt()) : null;
    }

    private static BlockPos blockPos(JsonObject obj) {
        return new BlockPos(intValue(obj, "x", 0), 0, intValue(obj, "z", 0));
    }

    private static BlockBounds optionalBounds(JsonObject object, String... keys) {
        for (String key : keys) {
            JsonObject value = jsonObject(object, key);
            if (value != null && value.has("minX") && value.has("minZ")
                    && value.has("maxX") && value.has("maxZ")) {
                return new BlockBounds(intValue(value, "minX", 0), intValue(value, "minZ", 0),
                        intValue(value, "maxX", 0), intValue(value, "maxZ", 0));
            }
        }
        return null;
    }

    private static String firstText(JsonObject primary, JsonObject secondary, String... keys) {
        for (String key : keys) {
            String value = stringValue(primary, key, "");
            if (!value.isBlank()) {
                return value;
            }
            value = stringValue(secondary, key, "");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static JsonArray array(JsonObject obj, String key) {
        return jsonArray(obj, key) == null ? new JsonArray() : jsonArray(obj, key);
    }

    private static void addError(JsonArray errors, String anchorId, String reasonCode, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("anchorId", anchorId == null ? "" : anchorId);
        error.addProperty("reasonCode", reasonCode);
        error.addProperty("message", message);
        errors.add(error);
    }

    private static String message(Throwable ex) {
        Throwable cause = ex;
        if (ex.getCause() != null) {
            cause = ex.getCause();
        }
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }

    private static String stringValue(JsonObject obj, String key, String fallback) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsString() : fallback;
    }

    private static int intValue(JsonObject obj, String key, int fallback) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsInt() : fallback;
    }

    private static boolean booleanValue(JsonObject obj, String key, boolean fallback) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsBoolean() : fallback;
    }

    public record EndpointExtraction(List<RoadEndpoint> endpoints, JsonArray errors) {
        public EndpointExtraction {
            endpoints = List.copyOf(Objects.requireNonNull(endpoints, "endpoints"));
            errors = Objects.requireNonNull(errors, "errors").deepCopy();
        }

        public boolean valid() {
            return errors.isEmpty();
        }
    }

    public record RoadEndpoint(String endpointId, String anchorId, String placementGroupId,
                               String entranceId,
                               String templateId, String templateHash, int priority, BlockBounds footprint,
                               BlockPoint roadPoint, String direction) {
        public RoadEndpoint {
            endpointId = requireText(endpointId, "endpointId");
            anchorId = requireText(anchorId, "anchorId");
            placementGroupId = requireText(placementGroupId, "placementGroupId");
            entranceId = requireText(entranceId, "entranceId");
            templateId = requireText(templateId, "templateId");
            templateHash = requireText(templateHash, "templateHash");
            Objects.requireNonNull(roadPoint, "roadPoint");
            direction = requireText(direction, "direction");
        }

        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("endpointId", endpointId);
            obj.addProperty("anchorId", anchorId);
            obj.addProperty("placementGroupId", placementGroupId);
            obj.addProperty("entranceId", entranceId);
            obj.addProperty("templateId", templateId);
            obj.addProperty("templateHash", templateHash);
            obj.addProperty("priority", priority);
            obj.addProperty("direction", direction);
            obj.addProperty("coordinateSource", "transformed_road_entrance");
            obj.add("roadPoint", roadPoint.asJson());
            if (footprint != null) {
                obj.add("lockedActualFootprint", boundsJson(footprint));
            }
            return obj;
        }
    }

    private enum ConnectionScope {
        INTRA_GROUP("intra_group"),
        INTER_GROUP("inter_group");

        private final String wireName;

        ConnectionScope(String wireName) {
            this.wireName = wireName;
        }
    }

    private record RoadConnection(RoadEndpoint from, RoadEndpoint to,
                                  ConnectionScope scope, long distanceBlocks) {
        private RoadConnection {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(scope, "scope");
            if (distanceBlocks < 0) {
                throw new IllegalArgumentException("distanceBlocks must not be negative");
            }
        }

        private JsonObject asJson() {
            JsonObject connection = new JsonObject();
            connection.addProperty("connectionId", "roadweaver_" + from.endpointId() + "_to_"
                    + to.endpointId());
            connection.addProperty("connectionScope", scope.wireName);
            connection.addProperty("fromAnchorId", from.anchorId());
            connection.addProperty("toAnchorId", to.anchorId());
            connection.addProperty("fromPlacementGroupId", from.placementGroupId());
            connection.addProperty("toPlacementGroupId", to.placementGroupId());
            connection.addProperty("fromEndpointId", from.endpointId());
            connection.addProperty("toEndpointId", to.endpointId());
            connection.addProperty("distanceBlocks", distanceBlocks);
            connection.add("from", from.roadPoint().asJson());
            connection.add("to", to.roadPoint().asJson());
            return connection;
        }
    }

    private static final class UnionFind<T> {
        private final Map<T, T> parents = new HashMap<>();

        private UnionFind(List<T> nodes) {
            nodes.forEach(node -> parents.put(node, node));
        }

        private T find(T node) {
            T parent = parents.get(node);
            if (parent == null) {
                throw new IllegalArgumentException("Unknown union-find node: " + node);
            }
            if (!parent.equals(node)) {
                parent = find(parent);
                parents.put(node, parent);
            }
            return parent;
        }

        private boolean union(T first, T second) {
            T firstRoot = find(first);
            T secondRoot = find(second);
            if (firstRoot.equals(secondRoot)) {
                return false;
            }
            if (firstRoot.toString().compareTo(secondRoot.toString()) <= 0) {
                parents.put(secondRoot, firstRoot);
            } else {
                parents.put(firstRoot, secondRoot);
            }
            return true;
        }
    }

    public static final class RegistrationException extends IllegalArgumentException {
        private final JsonObject report;

        public RegistrationException(JsonObject report) {
            super(stringValue(report, "reasonCode", REASON_REGISTRATION_FAILED) + ": "
                    + stringValue(report, "message", "RoadWeaver registration failed."));
            this.report = report.deepCopy();
        }

        public JsonObject report() {
            return report.deepCopy();
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank.");
        }
        return value.trim();
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
