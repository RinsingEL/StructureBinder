package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static com.rinsing.geomantia.systems.city.application.CityWallReservationPlanner.WallPolicy;

class CityWallPolicyTest {
    @Test void cityScalePicksTheDefaultWallForm() {
        assertEquals(WallPolicy.FULL, WallPolicy.infer("capital"));
        assertEquals(WallPolicy.LAND_SIDE_ONLY, WallPolicy.infer("city"));
        assertEquals(WallPolicy.NONE, WallPolicy.infer("town"));
        assertEquals(WallPolicy.NONE, WallPolicy.infer("village"));
        assertEquals(WallPolicy.FULL, WallPolicy.infer(null));
        assertEquals(WallPolicy.FULL, WallPolicy.infer("unknown-scale"));
    }

    @Test void explicitPolicyWinsAndUnknownValuesAreRejected() {
        assertEquals(WallPolicy.NONE, WallPolicy.parse("none"));
        assertEquals(WallPolicy.LOW_WALL, WallPolicy.parse(" LOW_WALL "));
        assertNull(WallPolicy.parse(""));
        assertThrows(IllegalArgumentException.class, () -> WallPolicy.parse("tower_only"));
    }

    @Test void skippedWallKeepsAnEmptyButUsablePlan() {
        JsonObject plan = CityWallReservationPlanner.emptyPlan(null, 24, 4, WallPolicy.NONE,
                "city_positioning_requires_no_wall");
        assertEquals(CityWallReservationPlanner.SCHEMA, plan.get("schema").getAsString());
        assertEquals("none", plan.get("wallPolicy").getAsString());
        assertEquals(0, plan.getAsJsonArray("wallLine").size());
        assertEquals(0, plan.getAsJsonArray("wallCorridorMask").size());
        assertEquals(0, plan.getAsJsonArray("gateSlots").size());
        assertFalse(plan.has("wallBounds"));
        assertEquals(4, plan.get("wallCorridorHalfWidthBlocks").getAsInt());
    }

    @Test void notImplementedWallFormsAreSkippedWithReason() {
        assertFalse(WallPolicy.GATE_ONLY.buildsWall());
        assertFalse(WallPolicy.LOW_WALL.buildsWall());
        assertTrue(WallPolicy.FULL.buildsWall());
        assertTrue(WallPolicy.LAND_SIDE_ONLY.buildsWall());
    }
}
