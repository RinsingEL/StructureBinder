package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.landuse.LandscapeTerrainContinuity;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** Plans terrain-aware city main roads from the parent Blueprint array graph. */
final class CityMainRoadPlanner {
    static final String PLAN_SCHEMA = "city_main_road_plan";
    static final String BAND_SCHEMA = "city_main_road_band";
    private static final String MAIN_ROAD_GROUP_ID = "__city_main_road__";
    private static final int MINIMUM_MAIN_ROAD_WIDTH_BLOCKS = 7;
    private static final int MAXIMUM_SLOPE = 18;
    private static final int MAXIMUM_LOCAL_RELIEF = 18;
    private static final int MAXIMUM_BRIDGE_LENGTH_BLOCKS = 100;
    private static final int CONNECTOR_RANK_WEIGHT = 1_000_000;
    private static final int[][] CARDINAL_DIRECTIONS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    Result plan(CityBlueprint blueprint,
                CityBlueprintReferenceCatalog references,
                LandUseTerrainField terrain,
                List<JsonObject> anchors,
                List<JsonObject> internalStreetBands) {
        return plan(blueprint, references, terrain, anchors, internalStreetBands, Set.of());
    }

    Result plan(CityBlueprint blueprint,
                CityBlueprintReferenceCatalog references,
                LandUseTerrainField terrain,
                List<JsonObject> anchors,
                List<JsonObject> internalStreetBands,
                Set<String> excludedRelationPairKeys) {
        JsonObject roadProfile = roadProfile(blueprint, references);
        String hierarchy = string(roadProfile, "hierarchy");
        int internalWidth = internalStreetBands.stream()
                .mapToInt(road -> intValue(road, "widthBlocks", 1)).max().orElse(1);
        int mainWidth = nextOdd(Math.max(MINIMUM_MAIN_ROAD_WIDTH_BLOCKS, internalWidth + 2));
        JsonObject plan = basePlan(blueprint, roadProfile, internalWidth, mainWidth);
        plan.addProperty("skippedParentLinkCount", excludedRelationPairKeys.size());
        if (!"HIERARCHICAL".equals(hierarchy)) {
            plan.addProperty("status", "not_required");
            plan.addProperty("reasonCode", "CITY_MAIN_ROAD_PROFILE_NOT_HIERARCHICAL");
            return Result.ok(List.of(), plan);
        }

        Map<String, GroupGeometry> groups = groupGeometry(anchors, internalStreetBands);
        List<BlockBounds> structureObstacles = anchors.stream()
                .map(anchor -> object(anchor, "collisionEnvelope"))
                .filter(bounds -> bounds.size() > 0)
                .map(CityStructureCandidateEnvelope::bounds).toList();
        JsonArray bridgeConnections = new JsonArray();
        plan.add("bridgeConnections", bridgeConnections);
        plan.addProperty("bridgeConnectionCount", bridgeConnections.size());
        List<Link> links;
        try {
            links = desiredLinks(blueprint, groups, mainWidth + 2, structureObstacles,
                    excludedRelationPairKeys);
        } catch (IllegalArgumentException exception) {
            return Result.failed("CITY_BLUEPRINT_MAIN_ROAD_PARENT_GRAPH_DISCONNECTED",
                    exception.getMessage(), failedPlan(plan,
                            "CITY_BLUEPRINT_MAIN_ROAD_PARENT_GRAPH_DISCONNECTED", exception.getMessage()));
        }
        if (links.isEmpty()) {
            plan.addProperty("status", "not_required");
            plan.addProperty("reasonCode", "CITY_MAIN_ROAD_EXPLICIT_TRAFFIC_CONNECTIONS_EMPTY");
            return Result.ok(List.of(), plan);
        }

        List<JsonObject> bands = new ArrayList<>();
        JsonArray connections = new JsonArray();
        JsonArray skippedConnections = new JsonArray();
        Set<BlockPoint> sharedRoadPoints = new LinkedHashSet<>();
        int sharedReuseBlocks = 0;
        int connectionIndex = 0;
        for (Link link : links) {
            GroupGeometry from = groups.get(link.fromGroupId());
            GroupGeometry to = groups.get(link.toGroupId());
            if (from == null || to == null) continue;
            List<ConnectorPair> pairs = connectorPairs(from, to, mainWidth + 2, structureObstacles);
            ConnectorPair connectors = pairs.isEmpty() ? null : pairs.get(0);
            List<LandUseTerrainField.Cell> cellPath = List.of();
            List<BlockPoint> selectedPolyline = List.of();
            BridgeRoute selectedBridge = null;
            int attemptedPairs = 0;
            for (ConnectorPair candidate : pairs) {
                attemptedPairs++;
                var candidateCells = route(terrain, candidate.from().point(), candidate.to().point());
                if (!candidateCells.isEmpty()) {
                    var candidatePolyline = blockPolyline(candidate.from().point(), candidate.to().point(),
                            candidateCells, terrain, structureObstacles, mainWidth + 2, sharedRoadPoints);
                    if (candidatePolyline.size() >= 2) {
                        connectors = candidate; cellPath = candidateCells; selectedPolyline = candidatePolyline; break;
                    }
                } else {
                    var bridge = bridgeRoute(terrain, candidate, mainWidth + 2, structureObstacles);
                    if (bridge != null) { connectors = candidate; selectedBridge = bridge; break; }
                }
            }
            plan.addProperty("lastConnectionAttemptedInterfacePairs", attemptedPairs);
            if (connectors == null) {
                String message = "No internal street or structure road entrance for "
                        + link.fromGroupId() + " -> " + link.toGroupId();
                return Result.failed("CITY_BLUEPRINT_MAIN_ROAD_CONNECTOR_MISSING", message,
                        failedPlan(plan, "CITY_BLUEPRINT_MAIN_ROAD_CONNECTOR_MISSING", message));
            }
            if (cellPath.isEmpty()) {
                BridgeRoute bridge = selectedBridge;
                if (bridge != null) {
                    connectionIndex++;
                    String connectionId = "city_main_road_" + String.format("%03d", connectionIndex);
                    JsonArray segmentIds = new JsonArray();
                    JsonObject sourceTransition = transitionBand(connectionId, "source", link.fromGroupId(),
                            connectors.from());
                    if (sourceTransition != null) {
                        segmentIds.add(sourceTransition.get("streetBandId").getAsString());
                        bands.add(sourceTransition);
                    }
                    JsonObject targetTransition = transitionBand(connectionId, "target", link.toGroupId(),
                            connectors.to());
                    if (targetTransition != null) {
                        segmentIds.add(targetTransition.get("streetBandId").getAsString());
                        bands.add(targetTransition);
                    }
                    List<JsonObject> bridgeBands = classifiedBridgeBands(connectionId, link, mainWidth,
                            bridge.polyline(), terrain);
                    bridgeBands.forEach(band -> {
                        segmentIds.add(band.get("streetBandId").getAsString());
                        bands.add(band);
                    });
                    JsonObject bridgeJson = new JsonObject();
                    bridgeJson.addProperty("connectionId", connectionId);
                    bridgeJson.addProperty("fromGroupId", link.fromGroupId());
                    bridgeJson.addProperty("toGroupId", link.toGroupId());
                    bridgeJson.addProperty("status", "PLANNED_BY_CITY");
                    bridgeJson.addProperty("provider", "city_surface_print");
                    bridgeJson.addProperty("bridgePolicy", "INDEPENDENT_BRIDGE_DECK_AND_RAIL");
                    bridgeJson.addProperty("waterSpanBlocks", bridge.waterSpanBlocks());
                    bridgeJson.addProperty("maximumBridgeLengthBlocks", MAXIMUM_BRIDGE_LENGTH_BLOCKS);
                    bridgeJson.add("streetBandIds", segmentIds);
                    bridgeConnections.add(bridgeJson);
                    continue;
                }
                skippedConnections.add(skippedConnection(link,
                        "CITY_BLUEPRINT_MAIN_ROAD_NO_LEGAL_PATH", connectors, attemptedPairs));
                continue;
            }
            List<BlockPoint> polyline = selectedPolyline;
            if (polyline.size() < 2) {
                skippedConnections.add(skippedConnection(link,
                        "CITY_BLUEPRINT_MAIN_ROAD_FULL_WIDTH_ROUTE_UNAVAILABLE", connectors, attemptedPairs));
                continue;
            }
            connectionIndex++;
            String connectionId = "city_main_road_" + String.format("%03d", connectionIndex);
            JsonObject connection = connectionJson(connectionId, link, connectors, cellPath, polyline);
            List<BlockPoint> unitRoad = unitPolyline(polyline);
            int reusedByConnection = (int) unitRoad.stream().filter(sharedRoadPoints::contains).count();
            sharedReuseBlocks += reusedByConnection;
            connection.addProperty("sharedNetworkReuseBlocks", reusedByConnection);
            connection.addProperty("routingPolicy", "SHARED_NETWORK_REUSE_BEFORE_NEW_CORRIDOR");
            JsonArray segmentIds = new JsonArray();
            JsonObject sourceTransition = transitionBand(connectionId, "source", link.fromGroupId(),
                    connectors.from());
            if (sourceTransition != null) {
                segmentIds.add(sourceTransition.get("streetBandId").getAsString());
                bands.add(sourceTransition);
            }
            JsonObject targetTransition = transitionBand(connectionId, "target", link.toGroupId(),
                    connectors.to());
            if (targetTransition != null) {
                segmentIds.add(targetTransition.get("streetBandId").getAsString());
                bands.add(targetTransition);
            }
            for (int segmentIndex = 0; segmentIndex + 1 < polyline.size(); segmentIndex++) {
                JsonObject band = band(connectionId, link, segmentIndex, mainWidth,
                        polyline.get(segmentIndex), polyline.get(segmentIndex + 1));
                segmentIds.add(band.get("streetBandId").getAsString());
                bands.add(band);
            }
            connection.add("streetBandIds", segmentIds);
            connections.add(connection);
            sharedRoadPoints.addAll(unitRoad);
        }
        plan.addProperty("status", "planned");
        plan.addProperty("reasonCode", "");
        plan.addProperty("connectionCount", connections.size() + bridgeConnections.size());
        plan.addProperty("bridgeConnectionCount", bridgeConnections.size());
        plan.addProperty("skippedConnectionCount", skippedConnections.size());
        plan.addProperty("segmentCount", bands.size());
        plan.addProperty("sharedNetworkReuseBlocks", sharedReuseBlocks);
        plan.addProperty("sharedNetworkPolicy", "ONE_NETWORK_SERVES_MULTIPLE_TRAFFIC_DEMANDS");
        plan.add("connections", connections);
        plan.add("skippedConnections", skippedConnections);
        return Result.ok(List.copyOf(bands), plan);
    }

    /** Reserve array exits before outward fill, including interfaces not selected by the current backbone. */
    List<JsonObject> reserveInterfaces(List<JsonObject> anchors, List<JsonObject> internalBands) {
        int width = nextOdd(Math.max(MINIMUM_MAIN_ROAD_WIDTH_BLOCKS,
                internalBands.stream().mapToInt(b -> intValue(b, "widthBlocks", 1)).max().orElse(1) + 2)) + 2;
        List<BlockBounds> obstacles = anchors.stream().map(a -> object(a, "collisionEnvelope"))
                .filter(b -> b.size() > 0).map(CityStructureCandidateEnvelope::bounds).toList();
        List<JsonObject> result = new ArrayList<>();
        groupGeometry(anchors, internalBands).entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            GroupGeometry group = entry.getValue();
            int index = 0;
            // Authored street exits take precedence over individual doors when an array has streets.
            int bestRank = group.connectors().stream().mapToInt(Connector::rank).min().orElse(3);
            for (Connector raw : group.connectors()) {
                if (raw.rank() > bestRank) continue;
                Connector connector = roadConnector(raw, group.center(), width, obstacles);
                if (connector == null) continue;
                String id = entry.getKey() + "::interface_" + (++index);
                JsonObject apron = new JsonObject();
                apron.addProperty("streetBandId", id);
                apron.addProperty("groupId", entry.getKey());
                apron.addProperty("reservationOnly", true);
                apron.addProperty("interfaceKind", raw.kind());
                apron.add("connectionPoint", connector.point().asJson());
                apron.add("bounds", CityStructureCandidateEnvelope.boundsJson(roadBounds(width, connector.point(), connector.point())));
                result.add(apron);
                JsonObject transition = transitionBand(id, "reserved", entry.getKey(), connector);
                if (transition != null) { transition.addProperty("reservationOnly", true); result.add(transition); }
            }
        });
        return List.copyOf(result);
    }

    private static JsonObject skippedConnection(Link link, String reasonCode, ConnectorPair connectors, int attemptedPairs) {
        JsonObject value = new JsonObject();
        value.addProperty("fromGroupId", link.fromGroupId());
        value.addProperty("toGroupId", link.toGroupId());
        value.add("fromInterface", connectors.from().point().asJson());
        value.add("toInterface", connectors.to().point().asJson());
        value.addProperty("attemptedInterfacePairCount", attemptedPairs);
        value.addProperty("status", "SKIPPED_WITH_WARNING");
        value.addProperty("reasonCode", reasonCode);
        return value;
    }

    private static BridgeRoute bridgeRoute(LandUseTerrainField terrain,
                                           ConnectorPair connectors,
                                           int width,
                                           List<BlockBounds> obstacles) {
        BlockPoint from = connectors.from().point();
        BlockPoint to = connectors.to().point();
        List<List<BlockPoint>> candidates = new ArrayList<>();
        if (from.x() == to.x() || from.z() == to.z()) {
            candidates.add(List.of(from, to));
        } else {
            candidates.add(List.of(from, new BlockPoint(to.x(), from.z()), to));
            candidates.add(List.of(from, new BlockPoint(from.x(), to.z()), to));
        }
        return candidates.stream()
                .map(CityMainRoadPlanner::withoutDuplicatePoints)
                .filter(points -> bridgePolylineLegal(points, terrain, width, obstacles))
                .map(points -> new BridgeRoute(points, maximumConsecutiveWaterBlocks(points, terrain)))
                .filter(route -> route.waterSpanBlocks() > 0
                        && route.waterSpanBlocks() <= MAXIMUM_BRIDGE_LENGTH_BLOCKS)
                .min(Comparator.comparingInt((BridgeRoute route) -> route.polyline().size())
                        .thenComparingInt(route -> polylineLength(route.polyline()))
                        .thenComparing(route -> route.polyline().toString()))
                .orElse(null);
    }

    private static List<BlockPoint> withoutDuplicatePoints(List<BlockPoint> points) {
        List<BlockPoint> result = new ArrayList<>();
        for (BlockPoint point : points) {
            if (result.isEmpty() || !result.get(result.size() - 1).equals(point)) result.add(point);
        }
        return List.copyOf(result);
    }

    private static boolean bridgePolylineLegal(List<BlockPoint> points,
                                               LandUseTerrainField terrain,
                                               int width,
                                               List<BlockBounds> obstacles) {
        for (BlockPoint point : unitPolyline(points)) {
            LandUseTerrainField.Cell cell = terrain.cellAt(point.x(), point.z()).orElse(null);
            if (cell == null || !passable(cell, true) || blocked(point, width, obstacles)) return false;
        }
        return true;
    }

    private static int maximumConsecutiveWaterBlocks(List<BlockPoint> points,
                                                     LandUseTerrainField terrain) {
        int current = 0;
        int maximum = 0;
        for (BlockPoint point : unitPolyline(points)) {
            boolean water = terrain.cellAt(point.x(), point.z()).map(LandUseTerrainField.Cell::water)
                    .orElse(false);
            current = water ? current + 1 : 0;
            maximum = Math.max(maximum, current);
        }
        return maximum;
    }

    private static int polylineLength(List<BlockPoint> points) {
        int length = 0;
        for (int i = 0; i + 1 < points.size(); i++) length += manhattan(points.get(i), points.get(i + 1));
        return length;
    }

    private static List<BlockPoint> unitPolyline(List<BlockPoint> points) {
        List<BlockPoint> result = new ArrayList<>();
        for (int segment = 0; segment + 1 < points.size(); segment++) {
            BlockPoint from = points.get(segment);
            BlockPoint to = points.get(segment + 1);
            int dx = Integer.compare(to.x(), from.x());
            int dz = Integer.compare(to.z(), from.z());
            int distance = manhattan(from, to);
            for (int step = segment == 0 ? 0 : 1; step <= distance; step++) {
                result.add(new BlockPoint(from.x() + dx * step, from.z() + dz * step));
            }
        }
        return List.copyOf(result);
    }

    private static List<JsonObject> classifiedBridgeBands(String connectionId,
                                                           Link link,
                                                           int width,
                                                           List<BlockPoint> polyline,
                                                           LandUseTerrainField terrain) {
        List<BlockPoint> unit = unitPolyline(polyline);
        List<JsonObject> result = new ArrayList<>();
        int segmentIndex = 0;
        int runStart = 0;
        for (int index = 1; index < unit.size(); index++) {
            BlockPoint previous = unit.get(index - 1);
            BlockPoint current = unit.get(index);
            boolean previousWater = terrain.cellAt(previous.x(), previous.z())
                    .map(LandUseTerrainField.Cell::water).orElse(false);
            boolean currentWater = terrain.cellAt(current.x(), current.z())
                    .map(LandUseTerrainField.Cell::water).orElse(false);
            int previousDx = index - 1 > runStart
                    ? Integer.compare(previous.x(), unit.get(index - 2).x()) : Integer.compare(current.x(), previous.x());
            int previousDz = index - 1 > runStart
                    ? Integer.compare(previous.z(), unit.get(index - 2).z()) : Integer.compare(current.z(), previous.z());
            int dx = Integer.compare(current.x(), previous.x());
            int dz = Integer.compare(current.z(), previous.z());
            if (previousWater != currentWater || dx != previousDx || dz != previousDz) {
                BlockPoint start = unit.get(runStart);
                BlockPoint end = previous;
                if (!start.equals(end)) result.add(band(connectionId, link, segmentIndex++, width,
                        start, end, previousWater ? "CITY_BRIDGE" : "CITY_MAIN_ROAD"));
                runStart = index - 1;
            }
        }
        BlockPoint start = unit.get(runStart);
        BlockPoint end = unit.get(unit.size() - 1);
        boolean water = terrain.cellAt(end.x(), end.z()).map(LandUseTerrainField.Cell::water).orElse(false);
        if (!start.equals(end)) result.add(band(connectionId, link, segmentIndex, width, start, end,
                water ? "CITY_BRIDGE" : "CITY_MAIN_ROAD"));
        return List.copyOf(result);
    }

    private static JsonObject roadProfile(CityBlueprint blueprint, CityBlueprintReferenceCatalog references) {
        JsonArray profiles = array(references.json(), "roadProfiles");
        for (JsonElement element : profiles) {
            if (!element.isJsonObject()) continue;
            JsonObject profile = element.getAsJsonObject();
            if (blueprint.roadProfile().profileRef().equals(string(profile, "profileRef"))) {
                return profile;
            }
        }
        throw new IllegalArgumentException("CITY_BLUEPRINT_ROAD_PROFILE_UNKNOWN:"
                + blueprint.roadProfile().profileRef());
    }

    private static JsonObject basePlan(CityBlueprint blueprint, JsonObject roadProfile,
                                       int internalWidth, int mainWidth) {
        JsonObject plan = new JsonObject();
        plan.addProperty("schema", PLAN_SCHEMA);
        plan.addProperty("cityId", blueprint.cityId());
        plan.addProperty("roadProfileRef", blueprint.roadProfile().profileRef());
        plan.addProperty("hierarchy", string(roadProfile, "hierarchy"));
        plan.addProperty("density", string(roadProfile, "density"));
        plan.addProperty("planningOwner", "BLUEPRINT_EXPLICIT_TRAFFIC_CONNECTIONS");
        plan.addProperty("geometryMode", "TERRAIN_AWARE_AXIS_ALIGNED_90_DEGREE");
        plan.addProperty("surfacePolicy", "FOLLOW_TERRAIN_STEP_GRADED");
        plan.addProperty("crossSectionProfile", "STAIR_SLAB_STAIR");
        plan.addProperty("internalStreetMaxWidthBlocks", internalWidth);
        plan.addProperty("mainRoadWidthBlocks", mainWidth);
        plan.add("connections", new JsonArray());
        return plan;
    }

    private static JsonObject failedPlan(JsonObject base, String reasonCode, String message) {
        JsonObject failed = base.deepCopy();
        failed.addProperty("status", "failed");
        failed.addProperty("reasonCode", reasonCode);
        failed.addProperty("message", message);
        return failed;
    }

    private static Map<String, GroupGeometry> groupGeometry(List<JsonObject> anchors,
                                                            List<JsonObject> internalStreetBands) {
        Map<String, MutableGroup> mutable = new LinkedHashMap<>();
        for (JsonObject anchor : anchors) {
            String groupId = string(anchor, "placementGroupId");
            if (groupId.isBlank()) continue;
            MutableGroup group = mutable.computeIfAbsent(groupId, MutableGroup::new);
            JsonObject collision = object(anchor, "collisionEnvelope");
            if (collision.size() > 0) group.include(CityStructureCandidateEnvelope.bounds(collision));
            JsonObject placement = object(anchor, "templatePlacementPlan");
            JsonObject transformed = object(placement, "transformed");
            for (JsonElement entranceElement : array(transformed, "roadEntrances")) {
                if (!entranceElement.isJsonObject()) continue;
                JsonObject position = object(entranceElement.getAsJsonObject(), "worldPosition");
                if (position.size() > 0) {
                    BlockPoint entrance = point(position);
                    group.addConnector(new Connector(entrance, "STRUCTURE_ROAD_ENTRANCE", 3,
                            string(entranceElement.getAsJsonObject(), "direction"), 1, entrance,
                            collision.size() > 0 ? CityStructureCandidateEnvelope.bounds(collision) : null,
                            structureBody(anchor)));
                }
            }
        }
        for (JsonObject band : internalStreetBands) {
            String groupId = string(band, "groupId");
            MutableGroup group = mutable.get(groupId);
            if (group == null) continue;
            int rank = connectorRank(string(band, "roadKind"));
            int localWidth = intValue(band, "widthBlocks", 1);
            int axisX = intValue(band, "axisX", 0);
            int axisZ = intValue(band, "axisZ", 0);
            JsonObject start = object(band, "start");
            JsonObject end = object(band, "end");
                if (start.size() > 0) group.addConnector(new Connector(point(start),
                    string(band, "roadKind") + "_ENDPOINT", rank,
                    cardinal(-axisX, -axisZ), localWidth, point(start), null, null));
            if (end.size() > 0) group.addConnector(new Connector(point(end),
                    string(band, "roadKind") + "_ENDPOINT", rank,
                    cardinal(axisX, axisZ), localWidth, point(end), null, null));
        }
        Map<String, GroupGeometry> result = new LinkedHashMap<>();
        mutable.forEach((groupId, group) -> {
            if (group.extent != null) result.put(groupId, group.freeze());
        });
        return Map.copyOf(result);
    }

    private static int connectorRank(String roadKind) {
        if ("COURTYARD_GATE".equals(roadKind) || "LINEAR_STREET_BAND".equals(roadKind)
                || "GRID_MAIN_STREET".equals(roadKind) || roadKind.startsWith("CENTER_AXIS_")) return 0;
        if ("COMPACT_ALLEY".equals(roadKind)) return 1;
        return 2;
    }

    private static String cardinal(int axisX, int axisZ) {
        if (axisX > 0) return "EAST";
        if (axisX < 0) return "WEST";
        if (axisZ > 0) return "SOUTH";
        if (axisZ < 0) return "NORTH";
        return "";
    }

    private static Connector roadConnector(Connector connector, BlockPoint groupCenter, int width,
                                           List<BlockBounds> obstacles) {
        int[] preferred = preferredDirection(connector, groupCenter);
        List<int[]> directions = new ArrayList<>();
        directions.add(preferred);
        if (!"STRUCTURE_ROAD_ENTRANCE".equals(connector.kind())) {
            directions.add(new int[]{-preferred[1], preferred[0]});
            directions.add(new int[]{preferred[1], -preferred[0]});
            directions.add(new int[]{-preferred[0], -preferred[1]});
        }
        for (int[] direction : directions) {
            int dx = direction[0];
            int dz = direction[1];
            BlockPoint base = connector.point();
            int localDistance = (connector.localWidth() + 2) / 2 + 1;
            if ("STRUCTURE_ROAD_ENTRANCE".equals(connector.kind())) {
                base = new BlockPoint(base.x() + dx * localDistance, base.z() + dz * localDistance);
                // The template owns everything inside its footprint, including recessed doors/courtyards.
                // Project the authored entrance outward; never pave through the template to reach it.
                BlockBounds body = connector.originBody();
                if (body != null) {
                    if (dx > 0) base = new BlockPoint(Math.max(base.x(), body.maxX() + 1), base.z());
                    if (dx < 0) base = new BlockPoint(Math.min(base.x(), body.minX() - 1), base.z());
                    if (dz > 0) base = new BlockPoint(base.x(), Math.max(base.z(), body.maxZ() + 1));
                    if (dz < 0) base = new BlockPoint(base.x(), Math.min(base.z(), body.minZ() - 1));
                }
            }
            int firstDistance = "STRUCTURE_ROAD_ENTRANCE".equals(connector.kind()) ? 0 : 1;
            List<BlockBounds> transitionObstacles = new ArrayList<>(obstacles);
            // Only the own clearance apron may be crossed. The body remains protected.
            if (connector.originObstacle() != null) transitionObstacles.remove(connector.originObstacle());
            if (connector.originBody() != null) transitionObstacles.add(connector.originBody());
            for (int distance = firstDistance; distance <= 128; distance++) {
                BlockPoint candidate = new BlockPoint(base.x() + dx * distance, base.z() + dz * distance);
                if (!blocked(candidate, width, obstacles)
                        && transitionClear(base, candidate, connector.localWidth(), dx, dz, transitionObstacles)) {
                    return new Connector(candidate, connector.kind(), connector.rank(),
                            cardinal(dx, dz), connector.localWidth(), base, connector.originObstacle(),
                            connector.originBody());
                }
            }
        }
        return null;
    }

    private static int[] preferredDirection(Connector connector, BlockPoint groupCenter) {
        return switch (connector.direction()) {
            case "NORTH" -> new int[]{0, -1};
            case "EAST" -> new int[]{1, 0};
            case "SOUTH" -> new int[]{0, 1};
            case "WEST" -> new int[]{-1, 0};
            default -> {
                int deltaX = connector.point().x() - groupCenter.x();
                int deltaZ = connector.point().z() - groupCenter.z();
                yield Math.abs(deltaX) >= Math.abs(deltaZ)
                        ? new int[]{deltaX < 0 ? -1 : 1, 0}
                        : new int[]{0, deltaZ < 0 ? -1 : 1};
            }
        };
    }

    private static List<Link> desiredLinks(CityBlueprint blueprint, Map<String, GroupGeometry> groups,
                                           int width, List<BlockBounds> obstacles,
                                           Set<String> excludedRelationPairKeys) {
        Map<String, Link> result = new LinkedHashMap<>();
        blueprint.relations().stream()
                .filter(relation -> relation.relationKind() == CityBlueprint.RelationKind.CONNECTION)
                .sorted(Comparator.comparing(CityBlueprint.Relation::fromGroupId)
                        .thenComparing(CityBlueprint.Relation::toGroupId))
                .forEach(relation -> addLink(result, new Link(relation.fromGroupId(), relation.toGroupId(),
                        "BLUEPRINT_" + relation.relationKind().name(), "relation")));
        return List.copyOf(result.values());
    }

    private static void addLink(Map<String, Link> links, Link link) {
        if (link.fromGroupId().equals(link.toGroupId())) return;
        String key = canonicalPair(link.fromGroupId(), link.toGroupId());
        links.putIfAbsent(key, link);
    }

    private static List<ConnectorPair> connectorPairs(GroupGeometry from, GroupGeometry to, int width,
                                                       List<BlockBounds> obstacles) {
        List<Connector> sources = from.connectors().stream()
                .map(c -> roadConnector(c, from.center(), width, obstacles)).filter(java.util.Objects::nonNull).distinct().toList();
        List<Connector> targets = to.connectors().stream()
                .map(c -> roadConnector(c, to.center(), width, obstacles)).filter(java.util.Objects::nonNull).distinct().toList();
        List<ConnectorPair> pairs = new ArrayList<>();
        for (Connector a : sources) for (Connector b : targets) pairs.add(new ConnectorPair(a,b));
        pairs.sort(Comparator.<ConnectorPair>comparingLong(pair ->
                (long)(pair.from().rank() + pair.to().rank()) * CONNECTOR_RANK_WEIGHT
                        + manhattan(pair.from().point(), pair.to().point()))
                .thenComparing(pair -> connectorPairKey(pair.from(),pair.to())));
        return List.copyOf(pairs);
    }

    private static boolean transitionClear(BlockPoint start, BlockPoint end, int width,
                                           int dx, int dz, List<BlockBounds> obstacles) {
        int steps = Math.max(Math.abs(end.x() - start.x()), Math.abs(end.z() - start.z()));
        for (int step = 0; step <= steps; step++) {
            BlockPoint point = new BlockPoint(start.x() + dx * step, start.z() + dz * step);
            int lower = (width - 1) / 2;
            int upper = width / 2;
            BlockBounds surface = dx != 0
                    ? new BlockBounds(point.x(), point.z() - lower, point.x(), point.z() + upper)
                    : new BlockBounds(point.x() - lower, point.z(), point.x() + upper, point.z());
            BlockBounds crossSection = dx != 0
                    ? new BlockBounds(surface.minX(), surface.minZ() - 1, surface.maxX(), surface.maxZ() + 1)
                    : new BlockBounds(surface.minX() - 1, surface.minZ(), surface.maxX() + 1, surface.maxZ());
            if (obstacles.stream().anyMatch(crossSection::overlaps)) {
                return false;
            }
        }
        return true;
    }

    private static boolean blocked(BlockPoint point, int width, List<BlockBounds> obstacles) {
        int lower = (width - 1) / 2;
        int upper = width / 2;
        BlockBounds road = new BlockBounds(point.x() - lower, point.z() - lower,
                point.x() + upper, point.z() + upper);
        return obstacles.stream().anyMatch(road::overlaps);
    }

    private static String connectorPairKey(Connector from, Connector to) {
        if (from == null || to == null) return "~";
        return from.point().x() + ":" + from.point().z() + ":" + from.kind() + "->"
                + to.point().x() + ":" + to.point().z() + ":" + to.kind();
    }

    private static List<LandUseTerrainField.Cell> route(LandUseTerrainField terrain,
                                                         BlockPoint from, BlockPoint to) {
        return route(terrain, from, to, false);
    }

    private static List<LandUseTerrainField.Cell> route(LandUseTerrainField terrain,
                                                         BlockPoint from, BlockPoint to,
                                                         boolean allowWater) {
        LandUseTerrainField.Cell start = terrain.cellAt(from.x(), from.z()).orElse(null);
        LandUseTerrainField.Cell target = terrain.cellAt(to.x(), to.z()).orElse(null);
        if (!passable(start, allowWater) || !passable(target, allowWater)) return List.of();
        CellKey startKey = new CellKey(start.cellX(), start.cellZ());
        CellKey targetKey = new CellKey(target.cellX(), target.cellZ());
        Map<CellKey, LandUseTerrainField.Cell> cells = new HashMap<>();
        terrain.cells().forEach(cell -> cells.put(new CellKey(cell.cellX(), cell.cellZ()), cell));
        Map<CellKey, Double> distance = new HashMap<>();
        Map<CellKey, CellKey> previous = new HashMap<>();
        PriorityQueue<RouteNode> queue = new PriorityQueue<>(Comparator
                .comparingDouble(RouteNode::estimatedTotal)
                .thenComparingDouble(RouteNode::cost)
                .thenComparingInt(node -> node.key().cellZ())
                .thenComparingInt(node -> node.key().cellX()));
        distance.put(startKey, 0.0);
        queue.add(new RouteNode(startKey, 0.0, heuristic(startKey, targetKey)));
        Set<CellKey> closed = new LinkedHashSet<>();
        while (!queue.isEmpty()) {
            RouteNode current = queue.poll();
            if (!closed.add(current.key())) continue;
            if (current.key().equals(targetKey)) return restorePath(previous, cells, startKey, targetKey);
            LandUseTerrainField.Cell currentCell = cells.get(current.key());
            for (int[] direction : CARDINAL_DIRECTIONS) {
                CellKey nextKey = new CellKey(current.key().cellX() + direction[0],
                        current.key().cellZ() + direction[1]);
                LandUseTerrainField.Cell next = cells.get(nextKey);
                if (!passable(next, allowWater)
                        || !bridgeContinuityAllows(currentCell, next, allowWater)) {
                    continue;
                }
                double nextCost = current.cost() + terrainCost(currentCell, next);
                if (nextCost + 1.0e-9 >= distance.getOrDefault(nextKey, Double.POSITIVE_INFINITY)) continue;
                distance.put(nextKey, nextCost);
                previous.put(nextKey, current.key());
                queue.add(new RouteNode(nextKey, nextCost, nextCost + heuristic(nextKey, targetKey)));
            }
        }
        return List.of();
    }

    private static boolean passable(LandUseTerrainField.Cell cell) {
        return passable(cell, false);
    }

    private static boolean passable(LandUseTerrainField.Cell cell, boolean allowWater) {
        if (cell == null || !cell.sampled() || "cliff".equals(cell.landformType())) return false;
        if (cell.water()) return allowWater;
        return cell.slope() <= MAXIMUM_SLOPE && cell.localRelief() <= MAXIMUM_LOCAL_RELIEF;
    }

    private static boolean bridgeContinuityAllows(LandUseTerrainField.Cell from,
                                                   LandUseTerrainField.Cell to,
                                                   boolean allowWater) {
        return allowWater && (from.water() || to.water())
                || LandscapeTerrainContinuity.allows("BALANCED", from, to);
    }

    private static double terrainCost(LandUseTerrainField.Cell from, LandUseTerrainField.Cell to) {
        return 1.0 + Math.max(0.0, to.slope()) * 0.05
                + Math.max(0.0, to.localRelief()) * 0.02
                + Math.abs(from.elevation() - to.elevation()) * 0.10;
    }

    private static int heuristic(CellKey from, CellKey to) {
        return Math.abs(from.cellX() - to.cellX()) + Math.abs(from.cellZ() - to.cellZ());
    }

    private static List<LandUseTerrainField.Cell> restorePath(Map<CellKey, CellKey> previous,
                                                               Map<CellKey, LandUseTerrainField.Cell> cells,
                                                               CellKey start, CellKey target) {
        List<LandUseTerrainField.Cell> reverse = new ArrayList<>();
        CellKey current = target;
        while (current != null) {
            reverse.add(cells.get(current));
            if (current.equals(start)) break;
            current = previous.get(current);
        }
        if (reverse.isEmpty() || current == null) return List.of();
        java.util.Collections.reverse(reverse);
        return List.copyOf(reverse);
    }

    private static List<BlockPoint> blockPolyline(BlockPoint from, BlockPoint to,
                                                   List<LandUseTerrainField.Cell> cellPath,
                                                   LandUseTerrainField terrain,
                                                   List<BlockBounds> obstacles,
                                                   int width,
                                                   Set<BlockPoint> sharedRoadPoints) {
        int margin = terrain.cellStepBlocks();
        int minX = Math.min(from.x(), cellPath.stream().mapToInt(LandUseTerrainField.Cell::blockMinX)
                .min().orElse(from.x())) - margin;
        int minZ = Math.min(from.z(), cellPath.stream().mapToInt(LandUseTerrainField.Cell::blockMinZ)
                .min().orElse(from.z())) - margin;
        int maxX = Math.max(to.x(), cellPath.stream().mapToInt(cell ->
                cell.blockMinX() + cell.cellStepBlocks() - 1).max().orElse(to.x())) + margin;
        int maxZ = Math.max(to.z(), cellPath.stream().mapToInt(cell ->
                cell.blockMinZ() + cell.cellStepBlocks() - 1).max().orElse(to.z())) + margin;
        if (!sharedRoadPoints.isEmpty()) {
            minX = Math.min(minX, sharedRoadPoints.stream().mapToInt(BlockPoint::x).min().orElse(minX) - margin);
            minZ = Math.min(minZ, sharedRoadPoints.stream().mapToInt(BlockPoint::z).min().orElse(minZ) - margin);
            maxX = Math.max(maxX, sharedRoadPoints.stream().mapToInt(BlockPoint::x).max().orElse(maxX) + margin);
            maxZ = Math.max(maxZ, sharedRoadPoints.stream().mapToInt(BlockPoint::z).max().orElse(maxZ) + margin);
        }
        BlockBounds planning = terrain.planningBounds();
        BlockBounds search = new BlockBounds(Math.max(planning.minX(), minX), Math.max(planning.minZ(), minZ),
                Math.min(planning.maxX(), maxX), Math.min(planning.maxZ(), maxZ));
        TerrainLookup lookup = TerrainLookup.of(terrain);
        if (!blockPassable(from, width, obstacles, lookup)
                || !blockPassable(to, width, obstacles, lookup)) return List.of();
        Map<BlockPoint, Integer> distance = new HashMap<>();
        Map<BlockPoint, BlockPoint> previous = new HashMap<>();
        PriorityQueue<BlockRouteNode> queue = new PriorityQueue<>(Comparator
                .comparingInt(BlockRouteNode::estimatedTotal)
                .thenComparingInt(BlockRouteNode::cost)
                .thenComparingInt(node -> node.point().z())
                .thenComparingInt(node -> node.point().x()));
        Set<BlockPoint> closed = new LinkedHashSet<>();
        distance.put(from, 0);
        queue.add(new BlockRouteNode(from, 0, manhattan(from, to)));
        while (!queue.isEmpty()) {
            BlockRouteNode current = queue.poll();
            if (!closed.add(current.point())) continue;
            if (current.point().equals(to)) break;
            LandUseTerrainField.Cell currentCell = lookup.at(current.point());
            for (int[] direction : CARDINAL_DIRECTIONS) {
                BlockPoint next = new BlockPoint(current.point().x() + direction[0],
                        current.point().z() + direction[1]);
                if (!search.contains(next.x(), next.z()) || !blockPassable(next, width, obstacles, lookup)) {
                    continue;
                }
                LandUseTerrainField.Cell nextCell = lookup.at(next);
                if (!LandscapeTerrainContinuity.allows("BALANCED", currentCell, nextCell)) continue;
                int cost = current.cost() + (sharedRoadPoints.contains(next) ? 1 : 4);
                if (cost >= distance.getOrDefault(next, Integer.MAX_VALUE)) continue;
                distance.put(next, cost);
                previous.put(next, current.point());
                queue.add(new BlockRouteNode(next, cost, cost + manhattan(next, to)));
            }
        }
        if (!closed.contains(to)) return List.of();
        List<BlockPoint> reverse = new ArrayList<>();
        BlockPoint current = to;
        while (current != null) {
            reverse.add(current);
            if (current.equals(from)) break;
            current = previous.get(current);
        }
        java.util.Collections.reverse(reverse);
        List<BlockPoint> compressed = new ArrayList<>();
        for (BlockPoint point : reverse) {
            if (compressed.size() >= 2) {
                BlockPoint first = compressed.get(compressed.size() - 2);
                BlockPoint second = compressed.get(compressed.size() - 1);
                boolean sameX = first.x() == second.x() && second.x() == point.x();
                boolean sameZ = first.z() == second.z() && second.z() == point.z();
                if (sameX || sameZ) {
                    compressed.set(compressed.size() - 1, point);
                    continue;
                }
            }
            compressed.add(point);
        }
        return List.copyOf(compressed);
    }

    private static boolean blockPassable(BlockPoint point, int width, List<BlockBounds> obstacles,
                                         TerrainLookup lookup) {
        LandUseTerrainField.Cell cell = lookup.at(point);
        if (!passable(cell)) return false;
        int lower = (width - 1) / 2;
        int upper = width / 2;
        BlockBounds road = new BlockBounds(point.x() - lower, point.z() - lower,
                point.x() + upper, point.z() + upper);
        if (!lookup.planningBounds().contains(road.minX(), road.minZ())
                || !lookup.planningBounds().contains(road.maxX(), road.maxZ())) return false;
        return obstacles.stream().noneMatch(road::overlaps);
    }

    private static BlockPoint cellCenter(LandUseTerrainField.Cell cell) {
        int offset = (cell.cellStepBlocks() - 1) / 2;
        return new BlockPoint(cell.blockMinX() + offset, cell.blockMinZ() + offset);
    }

    private static JsonObject connectionJson(String connectionId, Link link, ConnectorPair connectors,
                                             List<LandUseTerrainField.Cell> path,
                                             List<BlockPoint> polyline) {
        JsonObject value = new JsonObject();
        value.addProperty("connectionId", connectionId);
        value.addProperty("sourceGroupId", link.fromGroupId());
        value.addProperty("targetGroupId", link.toGroupId());
        value.addProperty("graphRole", link.graphRole());
        value.addProperty("graphRef", link.graphRef());
        value.addProperty("sourceConnectorKind", connectors.from().kind());
        value.addProperty("targetConnectorKind", connectors.to().kind());
        value.add("sourceConnector", connectors.from().point().asJson());
        value.add("targetConnector", connectors.to().point().asJson());
        JsonArray cellPath = new JsonArray();
        for (LandUseTerrainField.Cell cell : path) {
            JsonObject item = new JsonObject();
            item.addProperty("cellX", cell.cellX());
            item.addProperty("cellZ", cell.cellZ());
            item.addProperty("elevation", cell.elevation());
            item.addProperty("slope", cell.slope());
            item.addProperty("localRelief", cell.localRelief());
            cellPath.add(item);
        }
        value.add("terrainCellPath", cellPath);
        JsonArray points = new JsonArray();
        polyline.forEach(point -> points.add(point.asJson()));
        value.add("polyline", points);
        return value;
    }

    private static JsonObject transitionBand(String connectionId, String side, String groupId,
                                             Connector connector) {
        BlockPoint start = connector.transitionStart();
        BlockPoint end = connector.point();
        if (start.equals(end)) return null;
        BlockBounds bounds = roadBounds(connector.localWidth(), start, end);
        JsonObject value = new JsonObject();
        value.addProperty("schema", "city_main_road_transition_band");
        value.addProperty("streetBandId", connectionId + "::" + side + "_transition");
        value.addProperty("roadNetworkId", "city::main_road_network");
        value.addProperty("connectionId", connectionId);
        value.addProperty("roadKind", "CITY_MAIN_ROAD_TRANSITION");
        value.addProperty("roadHierarchy", "SECONDARY");
        value.addProperty("segmentIndex", -1);
        value.addProperty("groupId", "__city_road_transition__");
        value.addProperty("sourceGroupId", groupId);
        value.addProperty("targetGroupId", groupId);
        value.addProperty("geometryMode", "AXIS_ALIGNED_WIDTH_TRANSITION");
        value.addProperty("widthBlocks", connector.localWidth());
        value.addProperty("surfacePolicy", "FOLLOW_TERRAIN_STEP_GRADED");
        value.addProperty("crossSectionProfile", "STAIR_SLAB_STAIR");
        value.addProperty("hardSkeleton", true);
        value.addProperty("axisX", Integer.compare(end.x(), start.x()));
        value.addProperty("axisZ", Integer.compare(end.z(), start.z()));
        value.add("start", start.asJson());
        value.add("end", end.asJson());
        value.add("bounds", CityStructureCandidateEnvelope.boundsJson(bounds));
        value.add("platformBounds", CityStructureCandidateEnvelope.boundsJson(bounds));
        value.addProperty("platformPolicy", "CITY_MAIN_ROAD_TRANSITION");
        return value;
    }

    private static JsonObject band(String connectionId, Link link, int segmentIndex, int width,
                                   BlockPoint start, BlockPoint end) {
        return band(connectionId, link, segmentIndex, width, start, end, "CITY_MAIN_ROAD");
    }

    private static JsonObject band(String connectionId, Link link, int segmentIndex, int width,
                                   BlockPoint start, BlockPoint end, String roadKind) {
        if (start.x() != end.x() && start.z() != end.z()) {
            throw new IllegalArgumentException("CITY_MAIN_ROAD_SEGMENT_NOT_AXIS_ALIGNED");
        }
        BlockBounds bounds = roadBounds(width, start, end);
        JsonObject value = new JsonObject();
        value.addProperty("schema", BAND_SCHEMA);
        value.addProperty("streetBandId", connectionId + "::segment_"
                + String.format("%03d", segmentIndex + 1));
        value.addProperty("roadNetworkId", "city::main_road_network");
        value.addProperty("connectionId", connectionId);
        boolean bridge = "CITY_BRIDGE".equals(roadKind);
        value.addProperty("roadKind", roadKind);
        value.addProperty("roadHierarchy", "MAIN");
        value.addProperty("segmentIndex", segmentIndex);
        value.addProperty("groupId", MAIN_ROAD_GROUP_ID);
        value.addProperty("sourceGroupId", link.fromGroupId());
        value.addProperty("targetGroupId", link.toGroupId());
        value.addProperty("geometryMode", "TERRAIN_AWARE_AXIS_ALIGNED_90_DEGREE");
        value.addProperty("widthBlocks", width);
        value.addProperty("surfacePolicy", bridge ? "FIXED_WATER_DECK" : "FOLLOW_PLATFORM_GRADE");
        value.addProperty("crossSectionProfile", bridge ? "BRIDGE_DECK_RAIL" : "STAIR_SLAB_STAIR");
        value.addProperty("hardSkeleton", true);
        value.addProperty("axisX", Integer.compare(end.x(), start.x()));
        value.addProperty("axisZ", Integer.compare(end.z(), start.z()));
        value.add("start", start.asJson());
        value.add("end", end.asJson());
        value.add("bounds", CityStructureCandidateEnvelope.boundsJson(bounds));
        if (!bridge) {
            value.add("platformBounds", CityStructureCandidateEnvelope.boundsJson(bounds));
            value.addProperty("platformPolicy", "CITY_MAIN_ROAD_HARD_SKELETON");
        }
        return value;
    }

    private static BlockBounds roadBounds(int width, BlockPoint start, BlockPoint end) {
        int lowerHalf = (width - 1) / 2;
        int upperHalf = width / 2;
        return start.x() == end.x()
                ? new BlockBounds(start.x() - lowerHalf, Math.min(start.z(), end.z()),
                start.x() + upperHalf, Math.max(start.z(), end.z()))
                : new BlockBounds(Math.min(start.x(), end.x()), start.z() - lowerHalf,
                Math.max(start.x(), end.x()), start.z() + upperHalf);
    }

    private static int nextOdd(int value) {
        return (value & 1) == 0 ? value + 1 : value;
    }

    private static int manhattan(BlockPoint first, BlockPoint second) {
        return Math.abs(first.x() - second.x()) + Math.abs(first.z() - second.z());
    }

    private static String canonicalPair(String first, String second) {
        return first.compareTo(second) <= 0 ? first + "\u0000" + second : second + "\u0000" + first;
    }

    private static JsonArray array(JsonObject value, String key) {
        return value != null && value.has(key) && value.get(key).isJsonArray()
                ? value.getAsJsonArray(key) : new JsonArray();
    }

    private static JsonObject object(JsonObject value, String key) {
        return value != null && value.has(key) && value.get(key).isJsonObject()
                ? value.getAsJsonObject(key) : new JsonObject();
    }

    private static String string(JsonObject value, String key) {
        return value != null && value.has(key) && !value.get(key).isJsonNull()
                ? value.get(key).getAsString() : "";
    }

    private static int intValue(JsonObject value, String key, int fallback) {
        return value != null && value.has(key) && !value.get(key).isJsonNull()
                ? value.get(key).getAsInt() : fallback;
    }

    private static BlockPoint point(JsonObject value) {
        return new BlockPoint(value.get("x").getAsInt(), value.get("z").getAsInt());
    }

    record Result(boolean ok, List<JsonObject> streetBands, JsonObject plan,
                  String reasonCode, String message) {
        static Result ok(List<JsonObject> streetBands, JsonObject plan) {
            return new Result(true, streetBands, plan, "", "");
        }

        static Result failed(String reasonCode, String message, JsonObject plan) {
            return new Result(false, List.of(), plan, reasonCode, message);
        }
    }

    private record GroupGeometry(String groupId, BlockBounds extent, BlockPoint center,
                                 List<Connector> connectors) {
    }

    private static final class MutableGroup {
        private final String groupId;
        private BlockBounds extent;
        private final Map<String, Connector> connectors = new LinkedHashMap<>();

        private MutableGroup(String groupId) {
            this.groupId = groupId;
        }

        private void include(BlockBounds bounds) {
            extent = extent == null ? bounds : new BlockBounds(Math.min(extent.minX(), bounds.minX()),
                    Math.min(extent.minZ(), bounds.minZ()), Math.max(extent.maxX(), bounds.maxX()),
                    Math.max(extent.maxZ(), bounds.maxZ()));
        }

        private void addConnector(Connector connector) {
            String key = connector.point().x() + ":" + connector.point().z() + ":" + connector.kind();
            connectors.putIfAbsent(key, connector);
        }

        private GroupGeometry freeze() {
            BlockPoint center = new BlockPoint((extent.minX() + extent.maxX()) / 2,
                    (extent.minZ() + extent.maxZ()) / 2);
            List<Connector> ordered = connectors.values().stream()
                    .sorted(Comparator.comparingInt(Connector::rank)
                            .thenComparingInt(connector -> connector.point().x())
                            .thenComparingInt(connector -> connector.point().z())
                            .thenComparing(Connector::kind))
                    .toList();
            return new GroupGeometry(groupId, extent, center, ordered);
        }
    }

    private static BlockBounds structureBody(JsonObject anchor) {
        for (String key : List.of("actualFootprint", "plannedFootprint", "bodyEnvelope", "collisionEnvelope")) {
            JsonObject bounds = object(anchor, key);
            if (bounds.size() > 0) return CityStructureCandidateEnvelope.bounds(bounds);
        }
        return null;
    }

    private record Connector(BlockPoint point, String kind, int rank, String direction,
                             int localWidth, BlockPoint transitionStart, BlockBounds originObstacle,
                             BlockBounds originBody) {
    }

    private record ConnectorPair(Connector from, Connector to) {
    }

    private record BridgeRoute(List<BlockPoint> polyline, int waterSpanBlocks) {
        private BridgeRoute {
            polyline = List.copyOf(polyline);
        }
    }

    private record Link(String fromGroupId, String toGroupId, String graphRole, String graphRef) {
    }

    private record CellKey(int cellX, int cellZ) {
    }

    private record RouteNode(CellKey key, double cost, double estimatedTotal) {
    }

    private record BlockRouteNode(BlockPoint point, int cost, int estimatedTotal) {
    }

    private record TerrainLookup(int step, BlockBounds planningBounds,
                                 Map<Long, LandUseTerrainField.Cell> cells) {
        static TerrainLookup of(LandUseTerrainField terrain) {
            Map<Long, LandUseTerrainField.Cell> cells = new HashMap<>();
            terrain.cells().forEach(cell -> cells.put(key(Math.floorDiv(cell.blockMinX(), terrain.cellStepBlocks()),
                    Math.floorDiv(cell.blockMinZ(), terrain.cellStepBlocks())), cell));
            return new TerrainLookup(terrain.cellStepBlocks(), terrain.planningBounds(), Map.copyOf(cells));
        }

        LandUseTerrainField.Cell at(BlockPoint point) {
            return cells.get(key(Math.floorDiv(point.x(), step), Math.floorDiv(point.z(), step)));
        }

        private static long key(int x, int z) {
            return ((long) x << 32) ^ (z & 0xffffffffL);
        }
    }

}
