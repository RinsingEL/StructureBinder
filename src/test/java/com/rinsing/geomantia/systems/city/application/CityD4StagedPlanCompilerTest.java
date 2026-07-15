package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityD4StagedPlanCompilerTest {
    private final CityD4StagedPlanCompiler compiler = new CityD4StagedPlanCompiler();

    @Test
    void separatesKeyStructuresFromArrayStagesInPlacementOrder() {
        JsonObject source = json("""
                {
                  "cityId":"city_test",
                  "slots":[
                    {"slotId":"array_homes","placementStrategy":"array_group"},
                    {"slotId":"keep","placementStrategy":"single_ai_selected"},
                    {"slotId":"market","placementStrategy":"manual_anchor"}
                  ],
                  "placementOrder":["keep","market","array_homes"]
                }
                """);

        CityD4StagedPlanCompiler.StagePlan result = compiler.compile(source);

        JsonObject keyPlan = result.keyDesignSlotPlan();
        assertEquals("key_structure_stage", keyPlan.get("planningMode").getAsString());
        assertEquals("keep", keyPlan.getAsJsonArray("slots").get(0).getAsJsonObject()
                .get("slotId").getAsString());
        assertEquals("market", keyPlan.getAsJsonArray("slots").get(1).getAsJsonObject()
                .get("slotId").getAsString());
        assertEquals(2, keyPlan.getAsJsonArray("placementOrder").size());
        assertEquals("array_homes", result.arraySlots().get(0).get("slotId").getAsString());
        assertEquals(3, result.sourceDesignSlotPlan().getAsJsonArray("slots").size());
    }

    @Test
    void rejectsKeyStructureAfterArrayStage() {
        JsonObject source = json("""
                {
                  "slots":[
                    {"slotId":"keep"},
                    {"slotId":"homes","placementStrategy":"array_fill"}
                  ],
                  "placementOrder":["homes","keep"]
                }
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> compiler.compile(source));

        assertEquals("D4_KEY_STRUCTURES_MUST_PRECEDE_ARRAYS: key slot keep appears after an array_fill slot.",
                error.getMessage());
    }

    @Test
    void rejectsArrayOnlyStage() {
        JsonObject source = json("""
                {"slots":[{"slotId":"homes","placementStrategy":"array_fill"}]}
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> compiler.compile(source));

        assertTrue(error.getMessage().startsWith("D4_KEY_STRUCTURE_STAGE_REQUIRED"));
    }

    @Test
    void rejectsUnsupportedStrategyAndUnknownPlacementOrderSlot() {
        JsonObject unsupported = json("""
                {"slots":[{"slotId":"keep","placementStrategy":"automatic"}]}
                """);
        JsonObject missingSlot = json("""
                {"slots":[{"slotId":"keep"}],"placementOrder":["missing"]}
                """);

        IllegalArgumentException unsupportedError = assertThrows(IllegalArgumentException.class,
                () -> compiler.compile(unsupported));
        IllegalArgumentException orderError = assertThrows(IllegalArgumentException.class,
                () -> compiler.compile(missingSlot));

        assertEquals("D4_PLACEMENT_STRATEGY_UNSUPPORTED: keep uses automatic.", unsupportedError.getMessage());
        assertEquals("D4_SLOT_ORDER_VIOLATION: placementOrder references missing slot missing.",
                orderError.getMessage());
    }

    @Test
    void buildsArrayCandidatePlanWithDefaultsAndPassThroughOptions() {
        JsonObject source = json("{\"cityId\":\"city_test\"}");
        JsonObject slot = json("""
                {
                  "slotId":"homes",
                  "arrayId":"housing_array",
                  "displayRole":"housing",
                  "candidatePatchRefs":["patch_a"],
                  "structureId":"geomantia:house",
                  "arrayCount":4,
                  "patterns":["grid"],
                  "priority":7
                }
                """);

        JsonObject plan = compiler.arrayCandidatePlanFromSlot(source, slot);

        assertEquals(CityStructureArrayCandidatePlanner.PLAN_SCHEMA,
                plan.get("schemaVersion").getAsString());
        assertEquals("city_test", plan.get("cityId").getAsString());
        assertEquals("housing_array", plan.get("arrayId").getAsString());
        assertEquals("housing", plan.get("displayRole").getAsString());
        assertEquals("geomantia:house", plan.getAsJsonArray("structureIds").get(0).getAsString());
        assertEquals(4, plan.get("arrayCount").getAsInt());
        assertEquals("seeded_random", plan.get("variantSelectionMode").getAsString());
        assertEquals("grid", plan.getAsJsonArray("patterns").get(0).getAsString());
        assertEquals(7, plan.get("priority").getAsInt());
    }

    @Test
    void createsMinimalArrayLayoutPlanForV02AndV03() {
        JsonObject source = json("{\"cityId\":\"city_test\",\"cityScale\":\"large_town\"}");

        JsonObject v02 = compiler.minimalArrayLayoutPlan(
                source, CityStructureArrayLayoutLoopPlanner.PLANNING_MODE_V02);
        JsonObject v03 = compiler.minimalArrayLayoutPlan(
                source, CityStructureArrayLayoutLoopPlanner.PLANNING_MODE_V03);

        assertEquals(CityStructureArrayLayoutLoopPlanner.PLAN_SCHEMA,
                v02.get("schemaVersion").getAsString());
        assertEquals(CityStructureArrayLayoutLoopPlanner.PLAN_SCHEMA_V03,
                v03.get("schemaVersion").getAsString());
        assertEquals("large_town", v03.get("cityScale").getAsString());
        assertTrue(v02.getAsJsonArray("layoutPlans").isEmpty());
        assertTrue(v03.getAsJsonObject("designIntent").get("summary").getAsString().contains("v0_3"));
    }

    @Test
    void mergesAnchorPlansAndRejectsDuplicateAnchorIds() {
        JsonObject base = json("""
                {"cityId":"city_test","anchors":[{"anchorId":"keep"}]}
                """);
        JsonObject appended = json("""
                {"anchors":[{"anchorId":"house_1"},{"anchorId":"house_2"}]}
                """);
        JsonArray trace = json("{\"stages\":[{\"stageType\":\"key_structure\"}]}")
                .getAsJsonArray("stages");

        JsonObject merged = compiler.mergeStructureAnchorPlans(base, appended, "", trace);

        assertEquals(CityStructureAnchorPlanner.PLAN_SCHEMA, merged.get("schemaVersion").getAsString());
        assertEquals("city_test", merged.get("cityId").getAsString());
        assertEquals(3, merged.getAsJsonArray("anchors").size());
        assertEquals(1, merged.getAsJsonObject("stagedD4Trace").get("stageCount").getAsInt());

        JsonObject duplicate = json("{\"anchors\":[{\"anchorId\":\"keep\"}]}");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> compiler.mergeStructureAnchorPlans(base, duplicate, "city_test", new JsonArray()));
        assertEquals("D4_STAGED_DUPLICATE_ANCHOR_ID: keep from array.", error.getMessage());
    }

    private static JsonObject json(String value) {
        return JsonParser.parseString(value).getAsJsonObject();
    }
}
