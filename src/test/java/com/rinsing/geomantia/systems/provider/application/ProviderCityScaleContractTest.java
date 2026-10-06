package com.rinsing.geomantia.systems.provider.application;

import com.rinsing.geomantia.systems.city.application.CitySiteContextBuilder;
import com.rinsing.geomantia.systems.city.domain.config.CityPlanningConfig;
import com.rinsing.geomantia.systems.city.domain.model.CityScale;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProviderCityScaleContractTest {
    private final CitySiteContextBuilder builder = new CitySiteContextBuilder(CityPlanningConfig.defaults());

    @Test
    void everyAdvertisedT4ScaleCanBuildD3Context() {
        var tool = ProviderPlanningToolCatalog.definitions(List.of("realm_t4_patch_planning_add_city"))
                .get(0).getAsJsonObject();
        var scales = tool.getAsJsonObject("parameters").getAsJsonObject("properties")
                .getAsJsonObject("theoreticalScale").getAsJsonArray("enum");
        assertFalse(scales.isEmpty());
        for (var scale : scales) {
            var context = assertDoesNotThrow(() -> builder.buildWithFixedGridStep(
                    "city_test", "realm_test", "minecraft:overworld", "city_test", "PLAIN-01",
                    784, 4048, "scholarly_oasis_outpost", scale.getAsString(), 1, 128, 16, List.of()),
                    "T4 advertised scale must work in D3: " + scale.getAsString());
            assertEquals(16, context.grid().cellStepBlocks());
        }
    }

    @Test
    void t4AllowsComponentStyleTagsAndExposesNationalReview() {
        for (String name : List.of("realm_t4_patch_planning_select_capital", "realm_t4_patch_planning_add_city")) {
            var properties = ProviderPlanningToolCatalog.definitions(List.of(name)).get(0).getAsJsonObject()
                    .getAsJsonObject("parameters").getAsJsonObject("properties");
            assertEquals("string", properties.getAsJsonObject("serviceHierarchy").get("type").getAsString());
            assertFalse(properties.getAsJsonObject("serviceHierarchy").has("enum"), "Service responsibilities are authored freely");
            assertTrue(properties.getAsJsonObject("theoreticalScale").has("enum"), "Physical scale still drives executable capacity rules");
        }
        var tools = ProviderPlanningToolCatalog.definitions(List.of("realm_t4_patch_planning_select_capital",
                "realm_t4_patch_planning_preview","realm_t4_patch_planning_review"));
        var style = tools.get(0).getAsJsonObject().getAsJsonObject("parameters")
                .getAsJsonObject("properties").getAsJsonObject("styleDirection");
        assertEquals("string", style.getAsJsonObject("additionalProperties").get("type").getAsString());
        assertTrue(PlanningStepPolicy.toolsFor(ProviderPlanningDiscovery.Stage.T4).contains("realm_t4_patch_planning_review"));
        var review = tools.get(2).getAsJsonObject().getAsJsonObject("parameters");
        assertTrue(review.getAsJsonArray("required").contains(new com.google.gson.JsonPrimitive("proposalHash")));
    }

    @Test
    void savedOutpostSeedUsesSmallestScaleWithoutLosingRoleOrAnchor() {
        var context = builder.buildWithFixedGridStep(
                "city_sahra_valdoran_library_qasr_ilm", "realm_sahra_valdoran", "minecraft:overworld",
                "city_sahra_valdoran_library_qasr_ilm", "PLAIN-01", 784, 4048,
                "scholarly_oasis_outpost", "outpost", 1, 128, 16, List.of());
        assertEquals(CityScale.HAMLET, context.scaleClass());
        assertEquals(160, context.planningRadiusBlocks());
        assertEquals("scholarly_oasis_outpost", context.cityRole());
        assertEquals(784, context.anchorBlock().x());
        assertEquals(4048, context.anchorBlock().z());
        assertEquals("hamlet", context.asJson().get("scaleClass").getAsString());
        assertNull(CityScale.fromContractName("unknown_scale"));
    }
}
