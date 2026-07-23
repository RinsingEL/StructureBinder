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
    void connectsWithinPlacementGroupsBeforeBuildingNearestGroupBackbone() {
        List<EndpointFixture> fixtures = List.of(
                new EndpointFixture("farm_01", "farm", 0, 0),
                new EndpointFixture("farm_02", "farm", 0, 10),
                new EndpointFixture("plaza_01", "plaza", 50, 0),
                new EndpointFixture("plaza_02", "plaza", 50, 10),
                new EndpointFixture("housing_01", "housing", 100, 0),
                new EndpointFixture("housing_02", "housing", 100, 10));

        JsonObject plan = CityRoadWeaverBridge.createConnectionPlan(materialization(fixtures));

        assertEquals("city_roadweaver_connection_plan.v0.2", plan.get("schemaVersion").getAsString());
        assertEquals("group_spatial_mst", plan.get("connectionStrategy").getAsString());
        assertEquals(6, plan.get("endpointCount").getAsInt());
        assertEquals(5, plan.get("connectionCount").getAsInt());
        assertEquals(3, plan.get("placementGroupCount").getAsInt());
        assertEquals(3, plan.get("intraGroupConnectionCount").getAsInt());
        assertEquals(2, plan.get("interGroupConnectionCount").getAsInt());

        for (JsonElement element : plan.getAsJsonArray("connections")) {
            JsonObject connection = element.getAsJsonObject();
            boolean sameGroup = connection.get("fromPlacementGroupId").getAsString()
                    .equals(connection.get("toPlacementGroupId").getAsString());
            assertEquals(sameGroup ? "intra_group" : "inter_group",
                    connection.get("connectionScope").getAsString());
            assertTrue(connection.get("distanceBlocks").getAsLong() <= 50,
                    "Spatial MST must not reproduce the 100+ block priority-chain jump");
        }
    }

    @Test
    void connectionGraphIsStableWhenMaterializationOrderChanges() {
        List<EndpointFixture> fixtures = new ArrayList<>(List.of(
                new EndpointFixture("a_01", "a", 0, 0),
                new EndpointFixture("a_02", "a", 5, 0),
                new EndpointFixture("b_01", "b", 20, 0),
                new EndpointFixture("b_02", "b", 25, 0)));
        JsonObject forward = CityRoadWeaverBridge.createConnectionPlan(materialization(fixtures));
        Collections.reverse(fixtures);
        JsonObject reversed = CityRoadWeaverBridge.createConnectionPlan(materialization(fixtures));

        assertEquals(forward.getAsJsonArray("endpoints"), reversed.getAsJsonArray("endpoints"));
        assertEquals(forward.getAsJsonArray("connections"), reversed.getAsJsonArray("connections"));
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
