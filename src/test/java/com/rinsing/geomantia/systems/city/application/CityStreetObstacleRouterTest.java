package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CityStreetObstacleRouterTest {
    @Test void repairsRecordedNorthernBoundaryWithoutChangingBuildings() {
        JsonObject road = json("""
                {"streetBandId":"watch_north_post","widthBlocks":7,"axisX":0,"axisZ":-1,
                 "start":{"x":5044,"z":-6764},"end":{"x":5044,"z":-6842},
                 "bounds":{"minX":5041,"minZ":-6842,"maxX":5047,"maxZ":-6764}}
                """);
        JsonObject building = json("""
                {"actualFootprint":{"minX":5040,"minZ":-6795,"maxX":5048,"maxZ":-6785}}
                """);
        var bounds = new com.rinsing.geomantia.systems.city.domain.model.BlockBounds(4176,-6832,5711,-5297);
        JsonObject original = building.deepCopy();
        var repaired = CityStreetObstacleRouter.repair(List.of(road), List.of(building), bounds);
        assertTrue(repaired.size() > 1);
        for (int i = 0; i < repaired.size(); i++) {
            var footprint = CityStreetObstacleRouter.crossSection(repaired.get(i));
            assertTrue(bounds.contains(footprint.minX(), footprint.minZ()));
            assertTrue(bounds.contains(footprint.maxX(), footprint.maxZ()));
            assertFalse(footprint.overlaps(CityStructureCandidateEnvelope.bounds(building.getAsJsonObject("actualFootprint"))));
            if (i > 0) assertEquals(repaired.get(i-1).get("end"), repaired.get(i).get("start"));
        }
        assertEquals(original, building);
        // Boundary repair must also run when there are no obstacles.
        var clear = CityStreetObstacleRouter.repair(List.of(road), List.of(), bounds);
        assertTrue(CityStreetObstacleRouter.crossSection(clear.get(0)).minZ() >= bounds.minZ());
        assertThrows(IllegalStateException.class, () -> CityStreetObstacleRouter.repair(List.of(road), List.of(),
                new com.rinsing.geomantia.systems.city.domain.model.BlockBounds(5043,-6832,5045,-6700)));
    }

    @Test void reroutesRecordedMarketStreetAroundArmorerIncludingShoulders() {
        JsonObject road = json("""
                {"streetBandId":"market_segment_001","widthBlocks":3,"axisX":0,"axisZ":-1,
                 "start":{"x":5320,"z":-114},"end":{"x":5320,"z":-186},
                 "bounds":{"minX":5319,"minZ":-186,"maxX":5321,"maxZ":-114}}
                """);
        JsonObject building = json("""
                {"actualFootprint":{"minX":5315,"minZ":-125,"maxX":5322,"maxZ":-119}}
                """);
        JsonObject original = building.deepCopy();
        var result = CityStreetObstacleRouter.repair(List.of(road), List.of(building));
        assertTrue(result.size() > 1);
        assertEquals(road.get("start"), result.get(0).get("start"));
        assertEquals(road.get("end"), result.get(result.size()-1).get("end"));
        for (int i = 0; i < result.size(); i++) {
            assertFalse(CityStreetObstacleRouter.crossSection(result.get(i)).overlaps(
                    CityStructureCandidateEnvelope.bounds(building.getAsJsonObject("actualFootprint"))));
            if (i > 0) assertEquals(result.get(i-1).get("end"), result.get(i).get("start"));
        }
        assertEquals(original, building);
        assertEquals(result, CityStreetObstacleRouter.repair(List.of(road), List.of(building)));
    }

    @Test void repairsShoulderOnlyIntersectionAndPreservesUnchangedStreet() {
        JsonObject road = json("""
                {"streetBandId":"lane","widthBlocks":3,"axisX":1,"axisZ":0,
                 "start":{"x":0,"z":0},"end":{"x":20,"z":0},
                 "bounds":{"minX":0,"minZ":-1,"maxX":20,"maxZ":1}}
                """);
        JsonObject building = json("""
                {"actualFootprint":{"minX":8,"minZ":2,"maxX":12,"maxZ":6}}
                """);
        assertSame(road, CityStreetObstacleRouter.repair(List.of(road), List.of()).get(0));
        assertTrue(CityStreetObstacleRouter.repair(List.of(road), List.of(building)).size() > 1);
    }
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
}
