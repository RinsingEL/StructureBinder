package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CityReservationMaskPlannerCityDomainTest {
    @Test void wallBoundsBecomeWholeCityNoVegetationMask() {
        JsonObject wall = JsonParser.parseString("""
                {"wallBounds":{"minX":-100,"minZ":-100,"maxX":100,"maxZ":100}}
                """).getAsJsonObject();
        JsonArray vegetation = new JsonArray();
        JsonArray reasons = new JsonArray();
        CityReservationMaskPlanner.appendCityDomainMask(vegetation, reasons, new JsonObject(), wall);
        assertEquals(1, vegetation.size());
        JsonObject mask = vegetation.get(0).getAsJsonObject();
        assertEquals("city_domain_no_vegetation", mask.get("maskId").getAsString());
        assertEquals(-100, mask.getAsJsonObject("blockBounds").get("minX").getAsInt());
        assertEquals(100, mask.getAsJsonObject("blockBounds").get("maxX").getAsInt());
        assertEquals(1, reasons.size());
    }

    @Test void anchorsFallbackExpandsWhenCityHasNoWall() {
        JsonObject anchors = JsonParser.parseString("""
                {"anchors":[
                  {"collisionEnvelope":{"minX":0,"minZ":0,"maxX":10,"maxZ":10}},
                  {"collisionEnvelope":{"minX":20,"minZ":5,"maxX":30,"maxZ":15}}]}
                """).getAsJsonObject();
        JsonArray vegetation = new JsonArray();
        CityReservationMaskPlanner.appendCityDomainMask(vegetation, new JsonArray(), anchors, null);
        assertEquals(1, vegetation.size());
        var bounds = vegetation.get(0).getAsJsonObject().getAsJsonObject("blockBounds");
        assertEquals(-8, bounds.get("minX").getAsInt());
        assertEquals(38, bounds.get("maxX").getAsInt());
        assertEquals(-8, bounds.get("minZ").getAsInt());
        assertEquals(23, bounds.get("maxZ").getAsInt());
    }

    @Test void emptyInputsAddNothing() {
        JsonArray vegetation = new JsonArray();
        CityReservationMaskPlanner.appendCityDomainMask(vegetation, new JsonArray(), new JsonObject(), null);
        assertTrue(vegetation.isEmpty());
    }
}
