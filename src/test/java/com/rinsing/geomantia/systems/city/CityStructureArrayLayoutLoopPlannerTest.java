package com.rinsing.geomantia.systems.city;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.CityLandformReviewBuilder;
import com.rinsing.geomantia.systems.city.application.CitySiteContextBuilder;
import com.rinsing.geomantia.systems.city.application.CityStructureArrayLayoutLoopPlanner;
import com.rinsing.geomantia.systems.city.domain.config.CityPlanningConfig;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.CityLandformReviewPackage;
import com.rinsing.geomantia.systems.city.domain.model.CitySiteContext;
import com.rinsing.geomantia.systems.gis.domain.cell.LandformType;
import com.rinsing.geomantia.systems.gis.domain.landform.LandformPatch;
import com.rinsing.geomantia.systems.gis.domain.landform.PatchFlag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityStructureArrayLayoutLoopPlannerTest {

    @Test
    void fixedTemplateArrayUsesNbtBodyCollisionMaskAndTransformedEntrance() throws Exception {
        Fixture fixture = fixture(2);
        CityStructureArrayLayoutLoopPlanner planner = new CityStructureArrayLayoutLoopPlanner();
        CityStructureArrayLayoutLoopPlanner.CreateResult created = planner.create(
                fixture.baseDir(), fixture.review(), fixture.semanticSource(), fixture.plan(),
                new JsonObject(), new JsonObject());

        CityStructureArrayLayoutLoopPlanner.ExecuteResult result = planner.execute(
                fixture.baseDir(), fixture.review(), fixture.semanticSource(), created.loopState(),
                templateItem(2, "CLOCKWISE_90"));

        assertTrue(result.asJson().get("ok").getAsBoolean(), result.asJson().toString());
        JsonArray anchors = result.loopState().getAsJsonArray("arrayAnchors");
        assertEquals(2, anchors.size());
        for (JsonElement element : anchors) {
            JsonObject anchor = element.getAsJsonObject();
            assertFalse(anchor.has("structureId"));
            assertEquals("geomantia:test_house", anchor.get("templateId").getAsString());
            assertEquals("CLOCKWISE_90", anchor.get("rotation").getAsString());
            assertEquals(9, anchor.getAsJsonObject("rawSize").get("width").getAsInt());
            assertEquals(5, bounds(anchor, "actualFootprint").widthBlocks());
            assertEquals(9, bounds(anchor, "actualFootprint").heightBlocks());
            assertTrue(contains(bounds(anchor, "collisionEnvelope"), bounds(anchor, "actualFootprint")));
            JsonObject placement = anchor.getAsJsonObject("templatePlacementPlan");
            assertEquals(9, placement.getAsJsonObject("rawSize").get("width").getAsInt());
            JsonObject entrance = placement.getAsJsonObject("transformed")
                    .getAsJsonArray("roadEntrances").get(0).getAsJsonObject();
            assertEquals("EAST", entrance.get("direction").getAsString());
        }
    }

    @Test
    void twoClearanceFiveBodiesKeepAtLeastTenBlocksOfNetGap() throws Exception {
        Fixture fixture = fixture(5);
        CityStructureArrayLayoutLoopPlanner planner = new CityStructureArrayLayoutLoopPlanner();
        CityStructureArrayLayoutLoopPlanner.CreateResult created = planner.create(
                fixture.baseDir(), fixture.review(), fixture.semanticSource(), fixture.plan(),
                new JsonObject(), new JsonObject());
        CityStructureArrayLayoutLoopPlanner.ExecuteResult result = planner.execute(
                fixture.baseDir(), fixture.review(), fixture.semanticSource(), created.loopState(),
                templateItem(2, "NONE"));

        assertTrue(result.asJson().get("ok").getAsBoolean(), result.asJson().toString());
        JsonArray anchors = result.loopState().getAsJsonArray("arrayAnchors");
        BlockBounds first = bounds(anchors.get(0).getAsJsonObject(), "actualFootprint");
        BlockBounds second = bounds(anchors.get(1).getAsJsonObject(), "actualFootprint");
        assertTrue(edgeGap(first, second) >= 10, () -> first + " / " + second);
    }

    @Test
    void callerSuppliedGeometryIsRejectedBeforeLoopStateIsProduced() throws Exception {
        Fixture fixture = fixture(2);
        CityStructureArrayLayoutLoopPlanner planner = new CityStructureArrayLayoutLoopPlanner();
        CityStructureArrayLayoutLoopPlanner.CreateResult created = planner.create(
                fixture.baseDir(), fixture.review(), fixture.semanticSource(), fixture.plan(),
                new JsonObject(), new JsonObject());
        JsonObject item = templateItem(1, "NONE");
        item.getAsJsonArray("fillPool").get(0).getAsJsonObject()
                .add("rawSize", JsonParser.parseString("{\"width\":99,\"height\":1,\"depth\":99}"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> planner.execute(
                fixture.baseDir(), fixture.review(), fixture.semanticSource(), created.loopState(), item));
        assertTrue(error.getMessage().contains("TEMPLATE_GEOMETRY_INPUT_FORBIDDEN"));
    }

    @Test
    void weightedTemplateSelectionIsRejected() throws Exception {
        Fixture fixture = fixture(2);
        CityStructureArrayLayoutLoopPlanner planner = new CityStructureArrayLayoutLoopPlanner();
        CityStructureArrayLayoutLoopPlanner.CreateResult created = planner.create(
                fixture.baseDir(), fixture.review(), fixture.semanticSource(), fixture.plan(),
                new JsonObject(), new JsonObject());
        JsonObject item = templateItem(1, "NONE");
        item.getAsJsonArray("fillPool").get(0).getAsJsonObject().addProperty("weight", 1);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> planner.execute(
                fixture.baseDir(), fixture.review(), fixture.semanticSource(), created.loopState(), item));
        assertTrue(error.getMessage().startsWith("D4_RANDOM_TEMPLATE_SELECTION_REMOVED:"));
    }

    @Test
    void configuredIdentityIsRejectedWithoutFallback() throws Exception {
        Fixture fixture = fixture(2);
        JsonObject configuredPlan = fixture.plan().deepCopy();
        configuredPlan.addProperty("structureId", "minecraft:village_plains");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new CityStructureArrayLayoutLoopPlanner().create(
                        fixture.baseDir(), fixture.review(), fixture.semanticSource(), configuredPlan,
                        new JsonObject(), new JsonObject()));
        assertTrue(error.getMessage().contains("CITY_CONFIGURED_STRUCTURE_FLOW_REMOVED"));
    }

    @Test
    void finalizeReturnsOnlyTemplateSelectionInputsForD4Refreeze() throws Exception {
        Fixture fixture = fixture(2);
        CityStructureArrayLayoutLoopPlanner planner = new CityStructureArrayLayoutLoopPlanner();
        CityStructureArrayLayoutLoopPlanner.CreateResult created = planner.create(
                fixture.baseDir(), fixture.review(), fixture.semanticSource(), fixture.plan(),
                new JsonObject(), new JsonObject());
        JsonObject state = planner.execute(fixture.baseDir(), fixture.review(), fixture.semanticSource(),
                created.loopState(), templateItem(1, "NONE")).loopState();

        JsonObject anchor = planner.finalizeLoop(state).structureAnchorPlan()
                .getAsJsonArray("anchors").get(0).getAsJsonObject();
        assertEquals("geomantia:test_house", anchor.get("templateId").getAsString());
        assertEquals("fixed_v1", anchor.get("variantId").getAsString());
        assertFalse(anchor.has("templateHash"));
        assertFalse(anchor.has("rawSize"));
        assertFalse(anchor.has("actualFootprint"));
        assertFalse(anchor.has("structureId"));
    }

    private static JsonObject plan() {
        JsonObject plan = JsonParser.parseString("""
                {"schemaVersion":"city_d4_array_layout_plan.v0.2","cityId":"city_test",
                 "cityScale":"town","maxArrayPlans":4,"layoutPlans":[]}
                """).getAsJsonObject();
        plan.add("templateCatalog", templateCatalog(2));
        return plan;
    }

    private static JsonObject templateItem(int count, String rotation) {
        return JsonParser.parseString("""
                {"arrayId":"houses","plannerType":"compound_cluster","role":"residential",
                 "candidatePatchRefs":["plain_big"],"startSector":"center",
                 "fillPool":[{"templateId":"geomantia:test_house","variantId":"fixed_v1","rotation":"%s"}],
                 "countPolicy":{"minCount":%d,"targetCount":%d,"maxCount":%d},
                 "variantSelectionMode":"round_robin"}
                """.formatted(rotation, count, count, count)).getAsJsonObject();
    }

    private static JsonObject templateCatalog(int clearance) {
        return JsonParser.parseString("""
                {"schemaVersion":"city_template_catalog.v0.1","templates":[{
                 "buildingSemantic":"house","style":"test","templateId":"geomantia:test_house",
                 "templateRef":"geomantia:city/test_house","contentHash":"sha256:test-house",
                 "variantId":"fixed_v1","rawSize":{"width":9,"height":6,"depth":5},
                 "allowedRotations":["NONE","CLOCKWISE_90"],"allowedMirrors":["NONE"],
                 "roadEntrances":[{"entranceId":"front","x":4,"z":0,"direction":"NORTH"}],
                 "terrainPosePolicy":"structure_start_beard_thin","supportPolicy":"none",
                 "clearanceBlocks":%d}]}
                """.formatted(clearance)).getAsJsonObject();
    }

    private static Fixture fixture(int clearance) throws Exception {
        Path baseDir = Files.createTempDirectory("city-fixed-array-loop");
        CityPlanningConfig config = CityPlanningConfig.defaults();
        CitySiteContext context = new CitySiteContextBuilder(config).build(
                "city_test", "realm_test", "minecraft:overworld", "city_test", "candidate_test",
                0, 0, "town", "town", 180, 4, null);
        CityLandformReviewPackage review = new CityLandformReviewBuilder(config).build(context,
                List.of(patch("plain_big", -220, -220, 220, 220)));
        Path catalogPath = baseDir.resolve("semantic_catalog.json");
        Files.writeString(catalogPath, """
                {"schemaVersion":"city_structure_profile_catalog.v0.1","catalogMode":"debug",
                 "source":{"basis":"semantic-only fixture"},"structures":[],
                 "quality":{"passed":true,"score":100,"hardBlocks":[],"warnings":[],"needsReview":[],"metrics":{}}}
                """);
        JsonObject source = new JsonObject();
        source.addProperty("schemaVersion", "terrasense_structure_profile_source.v0.1");
        source.addProperty("sourceType", "debug_catalog");
        source.addProperty("catalogMode", "debug");
        source.addProperty("debugCatalogPath", catalogPath.toString());
        JsonObject plan = plan();
        plan.add("templateCatalog", templateCatalog(clearance));
        return new Fixture(baseDir, review, source, plan);
    }

    private static LandformPatch patch(String id, int minX, int minZ, int maxX, int maxZ) {
        return new LandformPatch(id, "region_0", LandformType.PLAIN,
                Math.max(1, (maxX - minX) * (maxZ - minZ) / 256), minX, minZ, maxX, maxZ,
                70.0, 65.0, 75.0, 0.08, 50.0,
                false, false, 0.9, EnumSet.noneOf(PatchFlag.class));
    }

    private static BlockBounds bounds(JsonObject object, String key) {
        JsonObject value = object.getAsJsonObject(key);
        return new BlockBounds(value.get("minX").getAsInt(), value.get("minZ").getAsInt(),
                value.get("maxX").getAsInt(), value.get("maxZ").getAsInt());
    }

    private static boolean contains(BlockBounds outer, BlockBounds inner) {
        return outer.minX() <= inner.minX() && outer.minZ() <= inner.minZ()
                && outer.maxX() >= inner.maxX() && outer.maxZ() >= inner.maxZ();
    }

    private static int edgeGap(BlockBounds a, BlockBounds b) {
        int x = Math.max(0, Math.max(b.minX() - a.maxX() - 1, a.minX() - b.maxX() - 1));
        int z = Math.max(0, Math.max(b.minZ() - a.maxZ() - 1, a.minZ() - b.maxZ() - 1));
        return Math.max(x, z);
    }

    private record Fixture(Path baseDir, CityLandformReviewPackage review, JsonObject semanticSource,
                           JsonObject plan) {
    }
}
