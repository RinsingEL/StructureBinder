package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityResidentialOverflowPlannerTest {
    @Test
    void outwardFillBuildingsBecomeRoadOwningBoundedSubzone() {
        List<JsonObject> anchors = List.of(anchor("a", 0), anchor("b", 10), anchor("c", 20));
        JsonObject road = new JsonObject();
        road.addProperty("streetBandId", "residential::grid_main");
        road.addProperty("groupId", "residential");
        road.add("bounds", CityStructureCandidateEnvelope.boundsJson(new BlockBounds(-2, 3, 28, 7)));

        CityResidentialOverflowPlanner.Result result = new CityResidentialOverflowPlanner().plan(
                anchors, List.of(road));

        assertEquals(1, result.plan().get("zoneCount").getAsInt());
        JsonObject zone = result.plan().getAsJsonArray("zones").get(0).getAsJsonObject();
        assertEquals("RESIDENTIAL_OVERFLOW", zone.get("zoneKind").getAsString());
        assertEquals(3, zone.get("buildingCount").getAsInt());
        assertEquals(1, zone.getAsJsonArray("streetBandIds").size());
        assertTrue(anchors.stream().allMatch(anchor -> anchor.getAsJsonObject("blueprintLayout")
                .has("residentialOverflowZoneId")));
    }

    @Test
    void fewerThanThreeOutwardFillBuildingsDoNotInventZone() {
        CityResidentialOverflowPlanner.Result result = new CityResidentialOverflowPlanner().plan(
                List.of(anchor("a", 0), anchor("b", 10)), List.of());

        assertEquals(0, result.plan().get("zoneCount").getAsInt());
    }

    private static JsonObject anchor(String id, int x) {
        JsonObject anchor = new JsonObject();
        anchor.addProperty("anchorId", id);
        anchor.addProperty("placementGroupId", "residential");
        anchor.addProperty("blueprintPlacementPhase", "fill");
        anchor.add("collisionEnvelope", CityStructureCandidateEnvelope.boundsJson(
                new BlockBounds(x, 0, x + 6, 6)));
        JsonObject layout = new JsonObject();
        layout.addProperty("outwardGuided", true);
        layout.add("theoreticalAnchor", new BlockPoint(x, 0).asJson());
        anchor.add("blueprintLayout", layout);
        return anchor;
    }
}
