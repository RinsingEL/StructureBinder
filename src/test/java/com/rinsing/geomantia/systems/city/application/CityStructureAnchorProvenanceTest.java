package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CityStructureAnchorProvenanceTest {
    @Test
    void v02OrdinarySlotGetsStableStructuredProvenance() {
        JsonObject anchor = new JsonObject();
        anchor.addProperty("anchorId", "hall");
        anchor.addProperty("slotId", "civic_slot");

        CityStructureAnchorPlanner.applyPlacementProvenance(anchor, anchor);

        assertEquals("city_structure_anchor_plan", CityStructureAnchorPlanner.PLAN_SCHEMA);
        assertEquals("city_structure_anchor_map", CityStructureAnchorPlanner.MAP_SCHEMA);
        assertEquals("civic_slot", anchor.get("placementGroupId").getAsString());
        assertEquals("civic_slot", anchor.getAsJsonObject("placementProvenance").get("slotId").getAsString());
    }

    @Test
    void compositeTemplatePrefersParentGroupAndPreservesSubZone() {
        JsonObject source = new JsonObject();
        source.addProperty("anchorId", "shop_01");
        source.addProperty("arrayId", "shop_row");
        source.addProperty("parentArrayId", "market_square");
        source.addProperty("targetSubZoneId", "north_edge");
        JsonObject output = new JsonObject();
        output.addProperty("anchorId", "shop_01");

        CityStructureAnchorPlanner.applyPlacementProvenance(source, output);

        assertEquals("market_square", output.get("placementGroupId").getAsString());
        JsonObject provenance = output.getAsJsonObject("placementProvenance");
        assertEquals("shop_row", provenance.get("arrayId").getAsString());
        assertEquals("market_square", provenance.get("parentArrayId").getAsString());
        assertEquals("north_edge", provenance.get("subZoneId").getAsString());
    }
}
