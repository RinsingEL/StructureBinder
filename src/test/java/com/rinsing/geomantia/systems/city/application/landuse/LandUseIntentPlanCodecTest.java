package com.rinsing.geomantia.systems.city.application.landuse;

import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSurfaceSettings;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LandUseIntentPlanCodecTest {
    @Test
    void parsesLockedPublicWireShape() {
        LandUseIntentPlan plan = new LandUseIntentPlanCodec().parse(JsonParser.parseString("""
                {
                  "schemaVersion":"city_land_use_intent_plan.v0.3",
                  "cityId":"city_test",
                  "seedSalt":"reviewed",
                  "groupOverrides":[{"groupId":"market","memberAnchorIds":["a","b"],"ruleRef":"plaza"}],
                  "subjectOverrides":[{"targetType":"anchor","targetId":"c","mode":"exclude"}],
                  "surfaceAlgorithmDefaults":[{
                    "surfaceAlgorithm":"uniform",
                    "surfaceBlockId":"minecraft:sandstone"
                  },{
                    "surfaceAlgorithm":"contour_bands",
                    "surfaceBlockId":"minecraft:farmland",
                    "cropBlockId":"minecraft:wheat",
                    "channelBankBlockId":"minecraft:dirt",
                    "channelWaterBlockId":"minecraft:water",
                    "channelBankOverlayBlockId":"minecraft:oak_slab"
                  }],
                  "surfaceOverrides":[{
                    "targetGroupId":"market",
                    "surfacePrintEnabled":true,
                    "autoConnect":false,
                    "surfaceAlgorithm":"contour_bands",
                    "surfaceBlockId":"minecraft:polished_andesite",
                    "cropBlockId":"minecraft:carrots",
                    "algorithmAnchor":{"x":48,"z":-12}
                  }]
                }
                """).getAsJsonObject(), "ignored");

        assertEquals("reviewed", plan.seedSalt());
        assertEquals("plaza", plan.groupOverrides().get(0).ruleRef());
        assertEquals(LandUseIntentPlan.Mode.EXCLUDE, plan.subjectOverrides().get(0).mode());
        assertEquals("market", plan.surfaceOverrides().get(0).targetGroupId());
        assertEquals(false, plan.surfaceOverrides().get(0).autoConnect());
        assertEquals("minecraft:polished_andesite", plan.surfaceOverrides().get(0).surfaceBlockId());
        assertEquals(LandUseSurfaceSettings.SurfaceAlgorithm.CONTOUR_BANDS,
                plan.surfaceOverrides().get(0).surfaceAlgorithm());
        assertEquals(new BlockPoint(48, -12), plan.surfaceOverrides().get(0).algorithmAnchor());
        assertEquals("minecraft:sandstone", plan.surfaceAlgorithmDefaults().get(0).surfaceBlockId());
    }

    @Test
    void rejectsUnknownFieldsAndInvalidRuleModes() {
        assertThrows(IllegalArgumentException.class, () -> new LandUseIntentPlanCodec().parse(
                JsonParser.parseString("""
                        {"schemaVersion":"city_land_use_intent_plan.v0.3","cityId":"c","cost":1}
                        """).getAsJsonObject(), "c"));
        assertThrows(IllegalArgumentException.class, () -> new LandUseIntentPlanCodec().parse(
                JsonParser.parseString("""
                        {"schemaVersion":"city_land_use_intent_plan.v0.3","cityId":"c",
                         "subjectOverrides":[{"targetType":"group","targetId":"g","mode":"exclude","ruleRef":"plaza"}]}
                        """).getAsJsonObject(), "c"));
        assertThrows(IllegalArgumentException.class, () -> new LandUseIntentPlanCodec().parse(
                JsonParser.parseString("""
                        {"schemaVersion":"city_land_use_intent_plan.v0.3","cityId":"c",
                         "continuityOverrides":[]}
                        """).getAsJsonObject(), "c"));
        assertThrows(IllegalArgumentException.class, () -> new LandUseIntentPlanCodec().parse(
                JsonParser.parseString("""
                        {"schemaVersion":"city_land_use_intent_plan.v0.1","cityId":"c",
                         "groupOverrides":[]}
                        """).getAsJsonObject(), "c"));
        assertThrows(IllegalArgumentException.class, () -> new LandUseIntentPlanCodec().parse(
                JsonParser.parseString("""
                        {"schemaVersion":"city_land_use_intent_plan.v0.3","cityId":"c",
                         "surfaceOverrides":[
                           {"targetGroupId":"g","autoConnect":true},
                           {"targetGroupId":"g","surfacePrintEnabled":false}
                         ]}
                        """).getAsJsonObject(), "c"));
        assertThrows(IllegalArgumentException.class, () -> new LandUseIntentPlanCodec().parse(
                JsonParser.parseString("""
                        {"schemaVersion":"city_land_use_intent_plan.v0.3","cityId":"c",
                         "surfaceOverrides":[{"targetGroupId":"g","surfaceBlockId":"Minecraft:Stone"}]}
                        """).getAsJsonObject(), "c"));
        assertThrows(IllegalArgumentException.class, () -> new LandUseIntentPlanCodec().parse(
                JsonParser.parseString("""
                        {"schemaVersion":"city_land_use_intent_plan.v0.3","cityId":"c",
                         "surfaceOverrides":[{"targetGroupId":"g","autoConnect":"yes"}]}
                        """).getAsJsonObject(), "c"));
        assertThrows(IllegalArgumentException.class, () -> new LandUseIntentPlanCodec().parse(
                JsonParser.parseString("""
                        {"schemaVersion":"city_land_use_intent_plan.v0.3","cityId":"c",
                         "surfaceOverrides":[{"targetGroupId":"g","autoConnectDistanceBlocks":64}]}
                        """).getAsJsonObject(), "c"));
        LandUseIntentPlan anchorOnly = new LandUseIntentPlanCodec().parse(
                JsonParser.parseString("""
                        {"schemaVersion":"city_land_use_intent_plan.v0.3","cityId":"c",
                         "surfaceOverrides":[{"targetGroupId":"g","algorithmAnchor":{"x":1,"z":2}}]}
                        """).getAsJsonObject(), "c");
        assertEquals(new BlockPoint(1, 2), anchorOnly.surfaceOverrides().get(0).algorithmAnchor());
        assertThrows(IllegalArgumentException.class, () -> new LandUseIntentPlanCodec().parse(
                JsonParser.parseString("""
                        {"schemaVersion":"city_land_use_intent_plan.v0.3","cityId":"c",
                         "surfaceOverrides":[{"targetGroupId":"g","directionMode":"radial",
                         "directionCenter":{"x":1,"z":2}}]}
                        """).getAsJsonObject(), "c"));
        assertThrows(IllegalArgumentException.class, () -> new LandUseIntentPlanCodec().parse(
                JsonParser.parseString("""
                        {"schemaVersion":"city_land_use_intent_plan.v0.2","cityId":"c"}
                        """).getAsJsonObject(), "c"));
    }

    @Test
    void explicitNullAlgorithmAnchorMeansNoGroupOverride() {
        LandUseIntentPlan plan = new LandUseIntentPlanCodec().parse(
                JsonParser.parseString("""
                        {"schemaVersion":"city_land_use_intent_plan.v0.3","cityId":"c",
                         "surfaceOverrides":[{
                           "targetGroupId":"g","surfaceAlgorithm":"contour_bands",
                           "algorithmAnchor":null
                         }]}
                        """).getAsJsonObject(), "c");

        assertNull(plan.surfaceOverrides().get(0).algorithmAnchor());
    }
}
