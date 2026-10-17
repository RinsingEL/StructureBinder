package com.rinsing.realmfolk.design;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DesignActivityMigrationTest {
    @Test void legacyActivityRadiusIsRaisedToCoverAFullCity() {
        var design = DesignCodec.defaults();
        var activity = new Design.PublicActivity();
        activity.id = "lunch";
        activity.maxDistance = 128;
        design.realms.get("default").population.activities = java.util.List.of(activity);
        assertEquals(384, design.resolve("default", "city").settings().activities.get(0).maxDistance);
    }

    @Test void explicitlyLargerRadiusIsKept() {
        var design = DesignCodec.defaults();
        var activity = new Design.PublicActivity();
        activity.id = "market";
        activity.maxDistance = 256;
        design.realms.get("default").population.activities = java.util.List.of(activity);
        assertEquals(256, design.resolve("default", "city").settings().activities.get(0).maxDistance);
    }
}
