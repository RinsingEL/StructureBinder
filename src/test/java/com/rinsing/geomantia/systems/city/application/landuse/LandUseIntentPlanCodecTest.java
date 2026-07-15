package com.rinsing.geomantia.systems.city.application.landuse;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LandUseIntentPlanCodecTest {
    @Test
    void parsesLockedPublicWireShape() {
        LandUseIntentPlan plan = new LandUseIntentPlanCodec().parse(JsonParser.parseString("""
                {
                  "schemaVersion":"city_land_use_intent_plan.v0.1",
                  "cityId":"city_test",
                  "seedSalt":"reviewed",
                  "groupOverrides":[{"groupId":"market","memberAnchorIds":["a","b"],"ruleRef":"plaza"}],
                  "subjectOverrides":[{"targetType":"anchor","targetId":"c","mode":"exclude"}]
                }
                """).getAsJsonObject(), "ignored");

        assertEquals("reviewed", plan.seedSalt());
        assertEquals("plaza", plan.groupOverrides().get(0).ruleRef());
        assertEquals(LandUseIntentPlan.Mode.EXCLUDE, plan.subjectOverrides().get(0).mode());
    }

    @Test
    void rejectsUnknownFieldsAndInvalidRuleModes() {
        assertThrows(IllegalArgumentException.class, () -> new LandUseIntentPlanCodec().parse(
                JsonParser.parseString("""
                        {"schemaVersion":"city_land_use_intent_plan.v0.1","cityId":"c","cost":1}
                        """).getAsJsonObject(), "c"));
        assertThrows(IllegalArgumentException.class, () -> new LandUseIntentPlanCodec().parse(
                JsonParser.parseString("""
                        {"schemaVersion":"city_land_use_intent_plan.v0.1","cityId":"c",
                         "subjectOverrides":[{"targetType":"group","targetId":"g","mode":"exclude","ruleRef":"plaza"}]}
                        """).getAsJsonObject(), "c"));
    }
}
