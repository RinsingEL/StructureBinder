package com.rinsing.geomantia.systems.city;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.CityStructureMaterializationPlanner;
import com.rinsing.geomantia.systems.city.application.CityTemplatePlacementGeometry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityStructureTemplateMaterializationPlannerTest {
    @Test
    void templatePlanDerivesAndLocksNbtFootprintWithoutStructureStartFields() {
        JsonObject result = new CityStructureMaterializationPlanner()
                .planWorldgen(anchorMap(), CityStructureMaterializationPlanner.ChunkStatusInspector.plannedOnly(),
                        emptyLedger(), metadata())
                .structureMaterializationPlan();

        assertEquals("structure_template_nbt", result.get("materializationSource").getAsString());
        assertEquals("current_world_template_nbt", result.get("preflightMode").getAsString());
        JsonObject item = result.getAsJsonArray("plannedWorldgenStructures").get(0).getAsJsonObject();
        assertEquals("city:house", item.get("templateId").getAsString());
        assertEquals("sha256:house", item.get("templateHash").getAsString());
        assertEquals("structure_template_nbt", item.get("materializationSource").getAsString());
        assertEquals(CityStructureMaterializationPlanner.TEMPLATE_DATUM_POLICY_GENERATOR_BASE_HEIGHT,
                item.get("templateDatumPolicy").getAsString());
        assertEquals(CityStructureMaterializationPlanner.TEMPLATE_DATUM_POLICY_GENERATOR_BASE_HEIGHT,
                item.getAsJsonObject("structureTemplate").get("templateDatumPolicy").getAsString());
        assertEquals("structure_start_beard_thin", item.get("terrainPosePolicy").getAsString());
        assertEquals(8, item.getAsJsonObject("rawSize").get("width").getAsInt());
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
    void structureStartTerrainPolicyIsFrozenWithItsGeneratorDatumPolicy() {
        JsonObject anchorMap = anchorMap();
        anchorMap.getAsJsonArray("anchors").get(0).getAsJsonObject().addProperty(
                "terrainPosePolicy", "structure_start_beard_thin");

        JsonObject item = new CityStructureMaterializationPlanner()
                .planWorldgen(anchorMap, CityStructureMaterializationPlanner.ChunkStatusInspector.plannedOnly(),
                        emptyLedger(), metadata())
                .structureMaterializationPlan()
                .getAsJsonArray("plannedWorldgenStructures").get(0).getAsJsonObject();

        assertEquals("structure_start_beard_thin", item.get("terrainPosePolicy").getAsString());
        assertEquals(CityStructureMaterializationPlanner.TEMPLATE_DATUM_POLICY_GENERATOR_BASE_HEIGHT,
                item.get("templateDatumPolicy").getAsString());
        assertEquals("structure_start_beard_thin", item.getAsJsonObject("structureTemplate")
                .get("terrainPosePolicy").getAsString());
    }

    @Test
    void geomantiaTemplateIsCanonicalizedToStructureStartDuringD6Planning() {
        JsonObject anchorMap = anchorMap();
        JsonObject anchor = anchorMap.getAsJsonArray("anchors").get(0).getAsJsonObject();
        anchor.addProperty("templateId", "geomantia:city/house");
        anchor.addProperty("templateRef", "geomantia:city/house");
        anchor.addProperty("terrainPosePolicy", "flat_or_small_step");

        JsonObject item = new CityStructureMaterializationPlanner()
                .planWorldgen(anchorMap, CityStructureMaterializationPlanner.ChunkStatusInspector.plannedOnly(),
                        emptyLedger(), metadata())
                .structureMaterializationPlan()
                .getAsJsonArray("plannedWorldgenStructures").get(0).getAsJsonObject();

        assertEquals("structure_start_beard_thin", item.get("terrainPosePolicy").getAsString());
        assertEquals(CityStructureMaterializationPlanner.TEMPLATE_DATUM_POLICY_GENERATOR_BASE_HEIGHT,
                item.get("templateDatumPolicy").getAsString());
        assertEquals("structure_start_beard_thin", item.getAsJsonObject("structureTemplate")
                .get("terrainPosePolicy").getAsString());
    }

    @Test
    void templateLedgerDriftIsRejected() {
        JsonObject plan = new CityStructureMaterializationPlanner()
                .planWorldgen(anchorMap(), CityStructureMaterializationPlanner.ChunkStatusInspector.plannedOnly(),
                        emptyLedger(), metadata())
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

    @Test
    void d6RejectsSuppliedOwnerChunkDrift() {
        JsonObject map = anchorMap();
        map.getAsJsonArray("anchors").get(0).getAsJsonObject().add("ownerChunks",
                JsonParser.parseString("[{\"x\":99,\"z\":99}]").getAsJsonArray());

        CityStructureMaterializationPlanner.Result result = new CityStructureMaterializationPlanner()
                .planWorldgen(map, CityStructureMaterializationPlanner.ChunkStatusInspector.plannedOnly(),
                        emptyLedger(), metadata());

        assertFalse(result.structureMaterializationPlan().get("locked").getAsBoolean());
        assertTrue(result.structureMaterializationTrace().toString()
                .contains("STRUCTURE_TEMPLATE_OWNER_CHUNKS_DRIFT"));
    }

    @Test
    void d7AcceptsOnlyCompleteLockedRuntimeGeometry() {
        JsonObject plan = new CityStructureMaterializationPlanner()
                .planWorldgen(anchorMap(), CityStructureMaterializationPlanner.ChunkStatusInspector.plannedOnly(),
                        emptyLedger(), metadata())
                .structureMaterializationPlan();
        JsonObject runtime = plan.getAsJsonArray("plannedWorldgenStructures").get(0).getAsJsonObject().deepCopy();
        JsonObject ledger = emptyLedger();
        ledger.getAsJsonArray("placedStructures").add(runtime);

        CityStructureMaterializationPlanner.Result accepted = new CityStructureMaterializationPlanner()
                .executeWorldgen(plan, ledger, CityStructureMaterializationPlanner.ChunkStatusInspector.plannedOnly(),
                        true);
        assertEquals(1, accepted.placedStructureLedger().getAsJsonArray("placedStructures").size());

        runtime.add("collisionEnvelope", bounds(0, 0, 1, 1));
        CityStructureMaterializationPlanner.Result rejected = new CityStructureMaterializationPlanner()
                .executeWorldgen(plan, ledger, CityStructureMaterializationPlanner.ChunkStatusInspector.plannedOnly(),
                        true);
        assertTrue(rejected.structureMaterializationTrace().toString()
                .contains("STRUCTURE_TEMPLATE_COLLISION_DRIFT"));
    }

    @Test
    void d6PreservesFiveBlockClearanceAndTenBlockBodyGap() {
        JsonObject map = anchorMap();
        JsonObject first = map.getAsJsonArray("anchors").get(0).getAsJsonObject();
        first.addProperty("clearanceBlocks", 5);
        first.add("collisionEnvelope", bounds(5, 5, 22, 20));
        first.add("reservedEnvelope", bounds(5, 5, 22, 20));
        first.add("maskEnvelope", bounds(-3, -3, 30, 28));

        JsonObject second = first.deepCopy();
        second.addProperty("anchorId", "house_2");
        second.getAsJsonObject("commandAnchorBlock").addProperty("x", 28);
        second.add("plannedFootprint", bounds(28, 10, 35, 15));
        second.add("collisionEnvelope", bounds(23, 5, 40, 20));
        second.add("reservedEnvelope", bounds(23, 5, 40, 20));
        second.add("maskEnvelope", bounds(15, -3, 48, 28));
        map.getAsJsonArray("anchors").add(second);

        JsonObject plan = new CityStructureMaterializationPlanner()
                .planWorldgen(map, CityStructureMaterializationPlanner.ChunkStatusInspector.plannedOnly(),
                        emptyLedger(), metadata())
                .structureMaterializationPlan();

        JsonObject firstPlan = plan.getAsJsonArray("plannedWorldgenStructures").get(0).getAsJsonObject();
        JsonObject secondPlan = plan.getAsJsonArray("plannedWorldgenStructures").get(1).getAsJsonObject();
        assertEquals(bounds(5, 5, 22, 20), firstPlan.getAsJsonObject("collisionEnvelope"));
        assertEquals(bounds(23, 5, 40, 20), secondPlan.getAsJsonObject("collisionEnvelope"));
        assertEquals(10, secondPlan.getAsJsonObject("actualFootprint").get("minX").getAsInt()
                - firstPlan.getAsJsonObject("actualFootprint").get("maxX").getAsInt() - 1);
    }

    @Test
    void configuredIdentityIsRejectedBeforePlanOutput() {
        JsonObject map = anchorMap();
        map.getAsJsonArray("anchors").get(0).getAsJsonObject()
                .addProperty("structureId", "trek:legacy_configured");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new CityStructureMaterializationPlanner().planWorldgen(map,
                        CityStructureMaterializationPlanner.ChunkStatusInspector.plannedOnly(), emptyLedger(),
                        metadata()));
        assertEquals("CITY_CONFIGURED_STRUCTURE_FLOW_REMOVED", error.getMessage());
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
                    "rawSize": {"width": 8, "height": 5, "depth": 6},
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

    private static CityStructureMaterializationPlanner.TemplateMetadataInspector metadata() {
        return templateRef -> CityStructureMaterializationPlanner.TemplateMetadata.readable(
                "sha256:house", new CityTemplatePlacementGeometry.Size(8, 5, 6));
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
