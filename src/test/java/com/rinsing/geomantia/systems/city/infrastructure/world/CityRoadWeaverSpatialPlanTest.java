package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityRoadWeaverSpatialPlanTest {
    @Test
    void neverInventsConnectionsFromPlacementGroupDistance() {
        List<EndpointFixture> fixtures = List.of(
                new EndpointFixture("farm_01", "farm", 0, 0),
                new EndpointFixture("farm_02", "farm", 0, 10),
                new EndpointFixture("plaza_01", "plaza", 200, 0),
                new EndpointFixture("plaza_02", "plaza", 200, 10),
                new EndpointFixture("housing_01", "housing", 400, 0),
                new EndpointFixture("housing_02", "housing", 400, 10));

        JsonObject plan = CityRoadWeaverBridge.createConnectionPlan(materialization(fixtures));

        assertEquals("city_roadweaver_connection_plan.v0.3", plan.get("schemaVersion").getAsString());
        assertEquals("explicit_external_traffic_intents_only", plan.get("connectionStrategy").getAsString());
        assertEquals(6, plan.get("endpointCount").getAsInt());
        assertEquals(0, plan.get("connectionCount").getAsInt());
        assertEquals(3, plan.get("placementGroupCount").getAsInt());
        assertEquals(0, plan.get("intraGroupConnectionCount").getAsInt());
        assertEquals(0, plan.get("interGroupConnectionCount").getAsInt());

        assertTrue(plan.getAsJsonArray("connections").isEmpty());
    }

    @Test
    void connectionGraphIsStableWhenMaterializationOrderChanges() {
        List<EndpointFixture> fixtures = new ArrayList<>(List.of(
                new EndpointFixture("a_01", "a", 0, 0),
                new EndpointFixture("a_02", "a", 5, 0),
                new EndpointFixture("b_01", "b", 200, 0),
                new EndpointFixture("b_02", "b", 205, 0)));
        JsonObject forward = CityRoadWeaverBridge.createConnectionPlan(materialization(fixtures));
        Collections.reverse(fixtures);
        JsonObject reversed = CityRoadWeaverBridge.createConnectionPlan(materialization(fixtures));

        assertEquals(forward.getAsJsonArray("endpoints"), reversed.getAsJsonArray("endpoints"));
        assertEquals(forward.getAsJsonArray("connections"), reversed.getAsJsonArray("connections"));
    }

    @Test
    void hierarchicalCityMainRoadSuppressesAllRoadWeaverConnections() {
        JsonObject materialization = materialization(List.of(
                new EndpointFixture("a", "a", 0, 0),
                new EndpointFixture("b", "b", 400, 0)));
        JsonObject anchorMap = new JsonObject();
        JsonObject mainRoad = new JsonObject();
        mainRoad.addProperty("hierarchy", "HIERARCHICAL");
        mainRoad.addProperty("status", "planned");
        anchorMap.add("cityMainRoadPlan", mainRoad);
        materialization.add("sourceStructureAnchorMap", anchorMap);

        JsonObject plan = CityRoadWeaverBridge.createConnectionPlan(materialization);

        assertTrue(plan.get("delegatedToCityMainRoad").getAsBoolean());
        assertEquals(0, plan.get("connectionCount").getAsInt());
        assertEquals(2, plan.get("endpointCount").getAsInt());
    }

    @Test
    void cityOwnedBridgeIsNeverDelegatedToRoadWeaver() {
        JsonObject materialization = materialization(List.of(
                new EndpointFixture("farm", "agriculture", 0, 0),
                new EndpointFixture("tower", "defense", 80, 0),
                new EndpointFixture("hall", "administration", 300, 0)));
        JsonObject anchorMap = new JsonObject();
        JsonObject mainRoad = new JsonObject();
        mainRoad.addProperty("hierarchy", "HIERARCHICAL");
        mainRoad.addProperty("status", "planned");
        JsonArray bridges = new JsonArray();
        JsonObject bridge = new JsonObject();
        bridge.addProperty("fromGroupId", "agriculture");
        bridge.addProperty("toGroupId", "defense");
        bridges.add(bridge);
        mainRoad.add("bridgeConnections", bridges);
        anchorMap.add("cityMainRoadPlan", mainRoad);
        materialization.add("sourceStructureAnchorMap", anchorMap);

        JsonObject plan = CityRoadWeaverBridge.createConnectionPlan(materialization);

        assertTrue(plan.get("delegatedToCityMainRoad").getAsBoolean());
        assertEquals(0, plan.get("connectionCount").getAsInt());
        assertEquals(0, plan.get("bridgeConnectionCount").getAsInt());
        assertTrue(plan.getAsJsonArray("connections").isEmpty());
    }

    @Test
    void projectsEveryEntranceDirectionOutsideTheLockedFootprint() {
        JsonObject materialization = materialization(List.of(
                new EndpointFixture("north", "group", 0, 0),
                new EndpointFixture("east", "group", 20, 0),
                new EndpointFixture("south", "group", 40, 0),
                new EndpointFixture("west", "group", 60, 0)));
        String[] directions = {"NORTH", "EAST", "SOUTH", "WEST"};
        JsonArray structures = materialization.getAsJsonArray("plannedWorldgenStructures");
        for (int i = 0; i < directions.length; i++) {
            structures.get(i).getAsJsonObject().getAsJsonObject("templatePlacementPlan")
                    .getAsJsonObject("transformed").getAsJsonArray("roadEntrances")
                    .get(0).getAsJsonObject().addProperty("direction", directions[i]);
        }

        JsonArray endpoints = CityRoadWeaverBridge.createConnectionPlan(materialization)
                .getAsJsonArray("endpoints");

        assertEquals(-1, point(endpoints, "north::front").get("z").getAsInt());
        assertEquals(23, point(endpoints, "east::front").get("x").getAsInt());
        assertEquals(5, point(endpoints, "south::front").get("z").getAsInt());
        assertEquals(57, point(endpoints, "west::front").get("x").getAsInt());
    }

    private static JsonObject point(JsonArray endpoints, String endpointId) {
        return endpoints.asList().stream().map(JsonElement::getAsJsonObject)
                .filter(endpoint -> endpointId.equals(endpoint.get("endpointId").getAsString()))
                .findFirst().orElseThrow().getAsJsonObject("roadPoint");
    }

    private static JsonObject materialization(List<EndpointFixture> fixtures) {
        JsonObject plan = new JsonObject();
        plan.addProperty("cityId", "city_test");
        JsonArray structures = new JsonArray();
        for (EndpointFixture fixture : fixtures) {
            JsonObject item = new JsonObject();
            item.addProperty("status", "planned_worldgen");
            item.addProperty("anchorId", fixture.anchorId());
            item.addProperty("placementGroupId", fixture.groupId());
            item.addProperty("structureId", "template:" + fixture.anchorId());
            item.addProperty("priority", 101);
            JsonObject footprint = new JsonObject();
            footprint.addProperty("minX", fixture.x() - 2);
            footprint.addProperty("minZ", fixture.z());
            footprint.addProperty("maxX", fixture.x() + 2);
            footprint.addProperty("maxZ", fixture.z() + 4);
            item.add("lockedActualFootprint", footprint);
            JsonObject placement = new JsonObject();
            placement.addProperty("templateId", "city:" + fixture.anchorId());
            placement.addProperty("templateHash", "sha256:" + fixture.anchorId());
            JsonObject transformed = new JsonObject();
            JsonArray entrances = new JsonArray();
            JsonObject entrance = new JsonObject();
            entrance.addProperty("entranceId", "front");
            entrance.addProperty("direction", "NORTH");
            JsonObject worldPosition = new JsonObject();
            worldPosition.addProperty("x", fixture.x());
            worldPosition.addProperty("z", fixture.z());
            entrance.add("worldPosition", worldPosition);
            entrances.add(entrance);
            transformed.add("roadEntrances", entrances);
            placement.add("transformed", transformed);
            item.add("templatePlacementPlan", placement);
            structures.add(item);
        }
        plan.add("plannedWorldgenStructures", structures);
        return plan;
    }

    private record EndpointFixture(String anchorId, String groupId, int x, int z) {
    }
}
