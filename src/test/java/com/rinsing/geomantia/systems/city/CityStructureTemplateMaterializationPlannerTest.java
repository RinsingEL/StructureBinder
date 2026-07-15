package com.rinsing.geomantia.systems.city;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.CityStructureMaterializationPlanner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityStructureTemplateMaterializationPlannerTest {
    @Test
    void templatePlanDerivesAndLocksNbtFootprintWithoutStructureStartFields() {
        JsonObject result = new CityStructureMaterializationPlanner()
                .planWorldgen(anchorMap(), CityStructureMaterializationPlanner.ChunkStatusInspector.plannedOnly(),
                        emptyLedger())
                .structureMaterializationPlan();

        assertEquals("structure_template_nbt", result.get("materializationSource").getAsString());
        assertEquals("structure_template_nbt_no_registry", result.get("preflightMode").getAsString());
        JsonObject item = result.getAsJsonArray("plannedWorldgenStructures").get(0).getAsJsonObject();
        assertEquals("city:house", item.get("templateId").getAsString());
        assertEquals("sha256:house", item.get("templateHash").getAsString());
        assertEquals("structure_template_nbt", item.get("materializationSource").getAsString());
        assertEquals(CityStructureMaterializationPlanner.TEMPLATE_DATUM_POLICY_WORLDGEN_SURFACE,
                item.get("templateDatumPolicy").getAsString());
        assertEquals(CityStructureMaterializationPlanner.TEMPLATE_DATUM_POLICY_WORLDGEN_SURFACE,
                item.getAsJsonObject("structureTemplate").get("templateDatumPolicy").getAsString());
        assertEquals(8, item.getAsJsonObject("templateSize").get("width").getAsInt());
        assertEquals(bounds(10, 10, 17, 15), item.getAsJsonObject("actualFootprint"));
        assertEquals(item.getAsJsonObject("actualFootprint"), item.getAsJsonObject("lockedActualFootprint"));
        assertFalse(item.has("templateFootprint"));
        assertFalse(item.has("pieceBoxes") && item.getAsJsonArray("pieceBoxes").size() > 0);
        assertEquals("residential_row", item.get("placementGroupId").getAsString());
        assertEquals("row_north", item.getAsJsonObject("placementProvenance")
                .get("subZoneId").getAsString());
        assertTrue(result.get("locked").getAsBoolean());
    }

    @Test
    void templateLedgerDriftIsRejected() {
        JsonObject plan = new CityStructureMaterializationPlanner()
                .planWorldgen(anchorMap(), CityStructureMaterializationPlanner.ChunkStatusInspector.plannedOnly(),
                        emptyLedger())
                .structureMaterializationPlan();
        JsonObject ledger = emptyLedger();
        JsonObject placed = templateFields();
        placed.addProperty("templateHash", "sha256:changed");
        placed.add("actualFootprint", bounds(10, 10, 20, 20));
        ledger.getAsJsonArray("placedStructures").add(placed);

        JsonObject result = new CityStructureMaterializationPlanner()
                .executeWorldgen(plan, ledger, CityStructureMaterializationPlanner.ChunkStatusInspector.plannedOnly(),
                        false)
                .structureMaterializationTrace();
        assertTrue(result.toString().contains("STRUCTURE_TEMPLATE_HASH_DRIFT"));
    }

    private static JsonObject anchorMap() {
        JsonObject root = JsonParser.parseString("""
                {
                  "schemaVersion": "city_structure_anchor_map.v0.2",
                  "cityId": "city_template_test",
                  "anchors": [{
                    "anchorId": "house_1",
                    "placementGroupId": "residential_row",
                    "placementProvenance": {
                      "slotId": "house_slot",
                      "arrayId": "residential_row",
                      "parentArrayId": "residential_block",
                      "subZoneId": "row_north"
                    },
                    "structureId": "legacy:ignored",
                    "commandAnchorBlock": {"x": 10, "z": 10},
                    "plannedFootprint": {"minX": 10, "minZ": 10, "maxX": 17, "maxZ": 15},
                    "reservedEnvelope": {"minX": 10, "minZ": 10, "maxX": 17, "maxZ": 15},
                    "collisionEnvelope": {"minX": 10, "minZ": 10, "maxX": 17, "maxZ": 15},
                    "templateId": "city:house",
                    "templateRef": "city:house",
                    "templateHash": "sha256:house",
                    "variantId": "oak",
                    "rotation": "NONE",
                    "mirror": "NONE",
                    "templateSize": {"width": 8, "height": 5, "depth": 6},
                    "materializationSource": "structure_template_nbt"
                  }]
                }
                """).getAsJsonObject();
        return root;
    }

    private static JsonObject emptyLedger() {
        JsonObject ledger = new JsonObject();
        ledger.add("placedStructures", new com.google.gson.JsonArray());
        return ledger;
    }

    private static JsonObject templateFields() {
        JsonObject item = new JsonObject();
        item.addProperty("anchorId", "house_1");
        item.addProperty("templateId", "city:house");
        item.addProperty("templateRef", "city:house");
        item.addProperty("templateHash", "sha256:house");
        item.addProperty("variantId", "oak");
        item.addProperty("rotation", "NONE");
        item.addProperty("mirror", "NONE");
        item.add("lockedActualFootprint", bounds(10, 10, 17, 15));
        item.addProperty("materializationSource", "structure_template_nbt");
        return item;
    }

    private static JsonObject bounds(int minX, int minZ, int maxX, int maxZ) {
        JsonObject bounds = new JsonObject();
        bounds.addProperty("minX", minX);
        bounds.addProperty("minZ", minZ);
        bounds.addProperty("maxX", maxX);
        bounds.addProperty("maxZ", maxZ);
        return bounds;
    }
}
