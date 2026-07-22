package com.rinsing.geomantia.systems.city.application.landuse;

import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSurfaceSettings;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LandUseIntentPlanCodecTest {
    @Test
    void parsesLockedPublicWireShape() {
        LandUseIntentPlan plan = new LandUseIntentPlanCodec().parse(JsonParser.parseString("""
                {
                  "schemaVersion":"city_land_use_intent_plan.v0.2",
                  "cityId":"city_test",
                  "seedSalt":"reviewed",
                  "groupOverrides":[{"groupId":"market","memberAnchorIds":["a","b"],"ruleRef":"plaza"}],
                  "subjectOverrides":[{"targetType":"anchor","targetId":"c","mode":"exclude"}],
                  "surfaceOverrides":[{
                    "targetGroupId":"market",
                    "surfacePrintEnabled":true,
                    "autoConnect":false,
                    "surfaceBlockId":"minecraft:polished_andesite",
                    "cropBlockId":"minecraft:carrots",
                    "directionMode":"radial",
                    "directionCenter":{"x":48,"z":-12}
                  }]
                }
                """).getAsJsonObject(), "ignored");

        assertEquals("reviewed", plan.seedSalt());
        assertEquals("plaza", plan.groupOverrides().get(0).ruleRef());
        assertEquals(LandUseIntentPlan.Mode.EXCLUDE, plan.subjectOverrides().get(0).mode());
        assertEquals("market", plan.surfaceOverrides().get(0).targetGroupId());
        assertEquals(false, plan.surfaceOverrides().get(0).autoConnect());
        assertEquals("minecraft:polished_andesite", plan.surfaceOverrides().get(0).surfaceBlockId());
        assertEquals(LandUseSurfaceSettings.DirectionMode.RADIAL,
                plan.surfaceOverrides().get(0).directionMode());
        assertEquals(new BlockPoint(48, -12), plan.surfaceOverrides().get(0).directionCenter());
    }

    @Test
    void rejectsUnknownFieldsAndInvalidRuleModes() {
        assertThrows(IllegalArgumentException.class, () -> new LandUseIntentPlanCodec().parse(
                JsonParser.parseString("""
                        {"schemaVersion":"city_land_use_intent_plan.v0.2","cityId":"c","cost":1}
                        """).getAsJsonObject(), "c"));
        assertThrows(IllegalArgumentException.class, () -> new LandUseIntentPlanCodec().parse(
                JsonParser.parseString("""
                        {"schemaVersion":"city_land_use_intent_plan.v0.2","cityId":"c",
                         "subjectOverrides":[{"targetType":"group","targetId":"g","mode":"exclude","ruleRef":"plaza"}]}
                        """).getAsJsonObject(), "c"));
        assertThrows(IllegalArgumentException.class, () -> new LandUseIntentPlanCodec().parse(
                JsonParser.parseString("""
                        {"schemaVersion":"city_land_use_intent_plan.v0.2","cityId":"c",
                         "continuityOverrides":[]}
                        """).getAsJsonObject(), "c"));
        assertThrows(IllegalArgumentException.class, () -> new LandUseIntentPlanCodec().parse(
                JsonParser.parseString("""
                        {"schemaVersion":"city_land_use_intent_plan.v0.1","cityId":"c",
                         "groupOverrides":[]}
                        """).getAsJsonObject(), "c"));
        assertThrows(IllegalArgumentException.class, () -> new LandUseIntentPlanCodec().parse(
                JsonParser.parseString("""
                        {"schemaVersion":"city_land_use_intent_plan.v0.2","cityId":"c",
                         "surfaceOverrides":[
                           {"targetGroupId":"g","autoConnect":true},
                           {"targetGroupId":"g","surfacePrintEnabled":false}
                         ]}
                        """).getAsJsonObject(), "c"));
        assertThrows(IllegalArgumentException.class, () -> new LandUseIntentPlanCodec().parse(
                JsonParser.parseString("""
                        {"schemaVersion":"city_land_use_intent_plan.v0.2","cityId":"c",
                         "surfaceOverrides":[{"targetGroupId":"g","surfaceBlockId":"Minecraft:Stone"}]}
                        """).getAsJsonObject(), "c"));
        assertThrows(IllegalArgumentException.class, () -> new LandUseIntentPlanCodec().parse(
                JsonParser.parseString("""
                        {"schemaVersion":"city_land_use_intent_plan.v0.2","cityId":"c",
                         "surfaceOverrides":[{"targetGroupId":"g","autoConnect":"yes"}]}
                        """).getAsJsonObject(), "c"));
        assertThrows(IllegalArgumentException.class, () -> new LandUseIntentPlanCodec().parse(
                JsonParser.parseString("""
                        {"schemaVersion":"city_land_use_intent_plan.v0.2","cityId":"c",
                         "surfaceOverrides":[{"targetGroupId":"g","autoConnectDistanceBlocks":64}]}
                        """).getAsJsonObject(), "c"));
        assertThrows(IllegalArgumentException.class, () -> new LandUseIntentPlanCodec().parse(
                JsonParser.parseString("""
                        {"schemaVersion":"city_land_use_intent_plan.v0.2","cityId":"c",
                         "surfaceOverrides":[{"targetGroupId":"g","directionCenter":{"x":1,"z":2}}]}
                        """).getAsJsonObject(), "c"));
        assertThrows(IllegalArgumentException.class, () -> new LandUseIntentPlanCodec().parse(
                JsonParser.parseString("""
                        {"schemaVersion":"city_land_use_intent_plan.v0.2","cityId":"c",
                         "surfaceOverrides":[{"targetGroupId":"g","directionMode":"radial",
                         "directionCenter":{"x":1.5,"z":2}}]}
                        """).getAsJsonObject(), "c"));
    }
}
