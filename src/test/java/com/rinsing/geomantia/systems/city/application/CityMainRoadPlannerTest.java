package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.infrastructure.preview.CityStructureLandingPreviewRenderer;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityMainRoadPlannerTest {
    private final CityMainRoadPlanner planner = new CityMainRoadPlanner();

    @Test
    void parentArrayRoadUsesWiderOrthogonalTerrainDetourAndStreetEndpoints() {
        CityBlueprint blueprint = blueprint("HIERARCHICAL");
        List<JsonObject> anchors = List.of(anchor("a", new BlockBounds(8, 8, 14, 14),
                        new BlockPoint(14, 15)),
                anchor("b", new BlockBounds(80, 8, 86, 14), new BlockPoint(80, 15)));
        List<JsonObject> internal = List.of(street("a", "LINEAR_STREET_BAND", 5,
                        new BlockPoint(5, 20), new BlockPoint(15, 20)),
                street("b", "LINEAR_STREET_BAND", 5,
                        new BlockPoint(79, 20), new BlockPoint(90, 20)));

        CityMainRoadPlanner.Result result = planner.plan(blueprint, references("HIERARCHICAL"),
                terrain(true), anchors, internal);

        assertTrue(result.ok(), result.plan().toString());
        assertEquals("planned", result.plan().get("status").getAsString());
        assertEquals(7, result.plan().get("mainRoadWidthBlocks").getAsInt());
        assertFalse(result.streetBands().isEmpty());
        assertTrue(result.streetBands().stream().allMatch(band ->
                band.get("start").getAsJsonObject().get("x").getAsInt()
                        == band.get("end").getAsJsonObject().get("x").getAsInt()
                        || band.get("start").getAsJsonObject().get("z").getAsInt()
                        == band.get("end").getAsJsonObject().get("z").getAsInt()));
        assertTrue(result.streetBands().stream().allMatch(band ->
                "STAIR_SLAB_STAIR".equals(band.get("crossSectionProfile").getAsString())));
        JsonObject connection = result.plan().getAsJsonArray("connections").get(0).getAsJsonObject();
        assertTrue(connection.get("sourceConnectorKind").getAsString().startsWith("LINEAR_STREET_BAND"));
        assertTrue(connection.get("targetConnectorKind").getAsString().startsWith("LINEAR_STREET_BAND"));
        assertFalse(connection.getAsJsonArray("streetBandIds").isEmpty());
        assertTrue(connection.getAsJsonArray("terrainCellPath").asList().stream()
                .map(element -> element.getAsJsonObject())
                .noneMatch(cell -> cell.get("cellX").getAsInt() == 1
                        && cell.get("cellZ").getAsInt() == 0));
    }

    @Test
    void narrowInternalEndpointCanEscapeBeforeMainRoadWidens() {
        List<JsonObject> anchors = List.of(
                anchor("a", new BlockBounds(16, 18, 18, 24), new BlockPoint(18, 20)),
                anchor("a", new BlockBounds(22, 18, 24, 24), new BlockPoint(22, 20)),
                anchor("b", new BlockBounds(80, 18, 86, 24), new BlockPoint(80, 20)));
        List<JsonObject> internal = List.of(
                street("a", "CENTER_AXIS_NORTH", 3,
                        new BlockPoint(20, 20), new BlockPoint(20, 30)),
                street("b", "CENTER_AXIS_NORTH", 3,
                        new BlockPoint(83, 10), new BlockPoint(83, 20)));

        CityMainRoadPlanner.Result result = planner.plan(blueprint("HIERARCHICAL"),
                references("HIERARCHICAL"), terrain(false), anchors, internal);

        assertTrue(result.ok(), result.plan().toString());
        assertEquals("planned", result.plan().get("status").getAsString());
        assertFalse(result.streetBands().isEmpty());
    }

    @Test
    void waterBarrierWithoutDetourFailsInsteadOfDrawingThroughWater() {
        List<LandUseTerrainField.Cell> cells = List.of(cell(0, 0, false), cell(1, 0, true),
                cell(2, 0, false));
        LandUseTerrainField terrain = new LandUseTerrainField(LandUseTerrainField.CURRENT_SCHEMA_VERSION,
                "city:test", new BlockBounds(0, 0, 95, 31), 32, cells);

        CityMainRoadPlanner.Result result = planner.plan(blueprint("HIERARCHICAL"),
                references("HIERARCHICAL"), terrain,
                List.of(anchor("a", new BlockBounds(8, 8, 14, 14), new BlockPoint(14, 15)),
                        anchor("b", new BlockBounds(80, 8, 86, 14), new BlockPoint(80, 15))),
                List.of(street("a", "LINEAR_STREET_BAND", 5,
                                new BlockPoint(5, 20), new BlockPoint(15, 20)),
                        street("b", "LINEAR_STREET_BAND", 5,
                                new BlockPoint(79, 20), new BlockPoint(90, 20))));

        assertFalse(result.ok());
        assertEquals("CITY_BLUEPRINT_MAIN_ROAD_NO_LEGAL_PATH", result.reasonCode());
        assertTrue(result.streetBands().isEmpty());
    }

    @Test
    void simpleRoadProfileDoesNotInventMainRoadHierarchy() {
        CityMainRoadPlanner.Result result = planner.plan(blueprint("SIMPLE"), references("SIMPLE"),
                terrain(false), List.of(), List.of());

        assertTrue(result.ok());
        assertTrue(result.streetBands().isEmpty());
        assertEquals("not_required", result.plan().get("status").getAsString());
    }

    @Test
    void d4PreviewRendersCompiledMainRoadSegments() throws Exception {
        List<JsonObject> anchors = List.of(anchor("a", new BlockBounds(8, 8, 14, 14),
                        new BlockPoint(14, 15)),
                anchor("b", new BlockBounds(80, 8, 86, 14), new BlockPoint(80, 15)));
        List<JsonObject> internal = List.of(street("a", "LINEAR_STREET_BAND", 5,
                        new BlockPoint(5, 20), new BlockPoint(15, 20)),
                street("b", "LINEAR_STREET_BAND", 5,
                        new BlockPoint(79, 20), new BlockPoint(90, 20)));
        CityMainRoadPlanner.Result result = planner.plan(blueprint("HIERARCHICAL"),
                references("HIERARCHICAL"), terrain(true), anchors, internal);
        JsonObject anchorMap = new JsonObject();
        JsonObject grid = new JsonObject();
        grid.addProperty("originBlockX", 0);
        grid.addProperty("originBlockZ", 0);
        grid.addProperty("cellStepBlocks", 32);
        grid.addProperty("cellsX", 3);
        grid.addProperty("cellsZ", 3);
        grid.add("blockBounds", CityStructureCandidateEnvelope.boundsJson(new BlockBounds(0, 0, 95, 95)));
        anchorMap.add("grid", grid);
        JsonArray anchorArray = new JsonArray();
        anchors.forEach(anchorArray::add);
        anchorMap.add("anchors", anchorArray);
        JsonArray bands = new JsonArray();
        internal.forEach(bands::add);
        result.streetBands().forEach(bands::add);
        anchorMap.add("streetBands", bands);
        Path output = Path.of("build", "city-main-road-preview");

        Path preview = new CityStructureLandingPreviewRenderer().renderD4(anchorMap, null, null, null, output);

        assertTrue(Files.isRegularFile(preview));
        assertTrue(Files.size(preview) > 0);
    }

    private static CityBlueprint blueprint(String hierarchy) {
        return new CityBlueprint(CityBlueprint.SCHEMA_VERSION, "city:test",
                new CityBlueprint.ArtifactRef("d3.json", "d3", "sha256:d3"),
                new CityBlueprint.ArtifactRef("catalog.json", "catalog", "sha256:catalog"),
                42L, new CityBlueprint.DesignIntent("test", "test", List.of()),
                new CityBlueprint.ProfileRef("style:test"), List.of(),
                List.of(new CityBlueprint.ArrayComposition("parent", "algorithm:grid", "a", List.of("b"))),
                List.of(), new CityBlueprint.ProfileRef("road:test:" + hierarchy.toLowerCase()),
                new CityBlueprint.ProfileRef("surface:test"),
                new CityBlueprint.OutdoorPlan(CityBlueprint.OutdoorMode.PRESERVE,
                        CityBlueprint.EnvelopeProfile.BALANCED, "foundation:test", List.of(), List.of()));
    }

    private static CityBlueprintReferenceCatalog references(String hierarchy) {
        JsonObject json = new JsonObject();
        JsonArray profiles = new JsonArray();
        JsonObject profile = new JsonObject();
        profile.addProperty("profileRef", "road:test:" + hierarchy.toLowerCase());
        profile.addProperty("hierarchy", hierarchy);
        profile.addProperty("density", "BALANCED");
        profiles.add(profile);
        json.add("roadProfiles", profiles);
        return new CityBlueprintReferenceCatalog(json, Set.of(), Set.of(), Set.of(), Map.of(), Map.of(),
                Set.of(), Set.of(), Set.of(profile.get("profileRef").getAsString()), Set.of(), null,
                Map.of(), Map.of(), Map.of(), Map.of());
    }

    private static LandUseTerrainField terrain(boolean waterDetour) {
        List<LandUseTerrainField.Cell> cells = new ArrayList<>();
        for (int z = 0; z < 3; z++) {
            for (int x = 0; x < 3; x++) {
                cells.add(cell(x, z, waterDetour && x == 1 && z == 0));
            }
        }
        return new LandUseTerrainField(LandUseTerrainField.CURRENT_SCHEMA_VERSION, "city:test",
                new BlockBounds(0, 0, 95, 95), 32, cells);
    }

    private static LandUseTerrainField.Cell cell(int x, int z, boolean water) {
        return new LandUseTerrainField.Cell(x, z, x * 32, z * 32, 32,
                64.0, 1.0, 1.0, 1.0, water, water ? 3.0 : 0.0,
                water ? 0.0 : 16.0, "minecraft:plains", water ? "water" : "plain",
                water ? "patch:water" : "patch:plain", true);
    }

    private static JsonObject anchor(String groupId, BlockBounds collision, BlockPoint entrance) {
        JsonObject anchor = new JsonObject();
        anchor.addProperty("placementGroupId", groupId);
        anchor.addProperty("blueprintPlacementPhase", "required");
        anchor.add("anchorBlock", new BlockPoint((collision.minX() + collision.maxX()) / 2,
                (collision.minZ() + collision.maxZ()) / 2).asJson());
        anchor.add("bodyEnvelope", CityStructureCandidateEnvelope.boundsJson(collision));
        anchor.add("collisionEnvelope", CityStructureCandidateEnvelope.boundsJson(collision));
        anchor.add("maskEnvelope", CityStructureCandidateEnvelope.boundsJson(collision));
        JsonObject placement = new JsonObject();
        JsonObject transformed = new JsonObject();
        JsonArray entrances = new JsonArray();
        JsonObject roadEntrance = new JsonObject();
        roadEntrance.add("worldPosition", entrance.asJson());
        entrances.add(roadEntrance);
        transformed.add("roadEntrances", entrances);
        placement.add("transformed", transformed);
        anchor.add("templatePlacementPlan", placement);
        return anchor;
    }

    private static JsonObject street(String groupId, String roadKind, int width,
                                     BlockPoint start, BlockPoint end) {
        JsonObject street = new JsonObject();
        street.addProperty("groupId", groupId);
        street.addProperty("roadKind", roadKind);
        street.addProperty("widthBlocks", width);
        street.add("start", start.asJson());
        street.add("end", end.asJson());
        return street;
    }

}
