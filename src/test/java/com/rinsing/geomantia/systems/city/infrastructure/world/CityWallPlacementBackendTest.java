package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityWallPlacementBackendTest {
    @Test
    void v5HeightPlanSplitsCreepingSlopeByWholeSegmentDelta() {
        JsonArray units = new JsonArray();
        units.add(unit("u0", 0, 0, 7, 4));
        units.add(unit("u1", 8, 0, 15, 4));
        units.add(unit("u2", 16, 0, 23, 4));

        JsonObject medians = new JsonObject();
        medians.addProperty("u0", 64);
        medians.addProperty("u1", 70);
        medians.addProperty("u2", 76);

        JsonObject plan = CityWallPlacementBackend.debugWallHeightPlan(units, medians, 7, 16, 17);

        assertEquals(2, plan.get("heightSegmentCount").getAsInt());
        assertTrue(plan.getAsJsonArray("heightSegments").toString().contains("\"u0\",\"u1\""),
                plan.toString());
        assertTrue(plan.getAsJsonArray("heightSegments").toString().contains("\"u2\""),
                plan.toString());
    }

    @Test
    void v5HeightPlanSegmentsUniformTopAndSkipsNaturalCliffBoundary() {
        JsonArray units = new JsonArray();
        units.add(unit("u0", 0, 0, 7, 4));
        units.add(unit("u1", 8, 0, 15, 4));
        units.add(unit("u2", 16, 0, 23, 4));
        units.add(unit("u3", 24, 0, 31, 4));

        JsonObject medians = new JsonObject();
        medians.addProperty("u0", 64);
        medians.addProperty("u1", 66);
        medians.addProperty("u2", 76);
        medians.addProperty("u3", 92);

        JsonObject plan = CityWallPlacementBackend.debugWallHeightPlan(units, medians, 7, 16, 17);

        assertEquals(66, plan.get("baselineSurfaceY").getAsInt());
        assertEquals(3, plan.get("heightSegmentCount").getAsInt());
        assertEquals(1, plan.get("naturalBoundarySegmentCount").getAsInt());
        assertTrue(plan.getAsJsonArray("heightSegments").toString().contains("\"u0\",\"u1\""),
                plan.toString());
        assertTrue(plan.getAsJsonArray("heightSegments").toString().contains("stepped_transition_up"),
                plan.toString());
        assertTrue(plan.getAsJsonArray("heightSegments").toString().contains("NATURAL_CLIFF_BOUNDARY_NO_WALL")
                        || plan.getAsJsonArray("heightSegments").toString().contains("high_segment_above_baseline"),
                plan.toString());
    }

    private static JsonObject unit(String id, int minX, int minZ, int maxX, int maxZ) {
        JsonObject unit = new JsonObject();
        unit.addProperty("unitId", id);
        unit.addProperty("sourceLineId", "north");
        unit.addProperty("unitType", "wall_unit_v5");
        unit.addProperty("placementAllowed", true);
        JsonObject bounds = new JsonObject();
        bounds.addProperty("minX", minX);
        bounds.addProperty("minZ", minZ);
        bounds.addProperty("maxX", maxX);
        bounds.addProperty("maxZ", maxZ);
        unit.add("blockBounds", bounds);
        return unit;
    }
}
