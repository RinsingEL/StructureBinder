package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CityStructureAnchorPlannerPublicSpacesTest {
    @Test void designedPublicSpacesAreHandedOffToAnchorMap() {
        JsonObject plan = new JsonObject();
        JsonArray spaces = new JsonArray();
        spaces.add(courtyard("courtyard_a", "residence-court", 10, 20, 30, 40));
        spaces.add(courtyard("courtyard_b", "teaching-court", 50, 60, 70, 80));
        plan.add("publicSpaces", spaces);
        JsonObject map = new JsonObject();

        CityStructureAnchorPlanner.copyPublicSpaces(plan, map);

        assertEquals(spaces, map.getAsJsonArray("publicSpaces"));
        assertEquals("courtyard_a", map.getAsJsonArray("publicSpaces").get(0).getAsJsonObject()
                .get("ownerGroupId").getAsString());
        assertNotSame(spaces.get(0), map.getAsJsonArray("publicSpaces").get(0));
    }

    @Test void missingOrMalformedPublicSpacesLeaveAnchorMapUnchanged() {
        JsonObject map = new JsonObject();
        CityStructureAnchorPlanner.copyPublicSpaces(new JsonObject(), map);
        assertFalse(map.has("publicSpaces"));

        JsonObject malformed = new JsonObject();
        malformed.addProperty("publicSpaces", "not-an-array");
        CityStructureAnchorPlanner.copyPublicSpaces(malformed, map);
        assertFalse(map.has("publicSpaces"));
    }

    private static JsonObject courtyard(String owner, String purpose, int minX, int minZ, int maxX, int maxZ) {
        JsonObject space = new JsonObject();
        space.addProperty("minX", minX);
        space.addProperty("minZ", minZ);
        space.addProperty("maxX", maxX);
        space.addProperty("maxZ", maxZ);
        space.addProperty("ownerGroupId", owner);
        space.addProperty("purpose", purpose);
        return space;
    }
}
