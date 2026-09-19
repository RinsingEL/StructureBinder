package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CityRoadsideTreePlannerTest {
    private final CityRoadsideTreePlanner planner = new CityRoadsideTreePlanner();

    @Test void sizesAndRotationsControlFullCanopySpacingAndRootTransform() {
        JsonObject original = source();
        JsonObject result = plan(original, new JsonObject(), terrain(false));
        assertEquals(0, original.getAsJsonArray("anchors").size());
        assertTrue(result.getAsJsonArray("anchors").size() > 12);
        Set<String> variants = new HashSet<>(), rotations = new HashSet<>();
        List<BlockBounds> occupied = new ArrayList<>();
        for (var element : result.getAsJsonArray("anchors")) {
            JsonObject item = element.getAsJsonObject();
            var tree = trees().stream().filter(t -> t.ref().equals(item.get("templateRef").getAsString())).findFirst().orElseThrow();
            var rotation = CityTemplatePlacementGeometry.Rotation.valueOf(item.get("rotation").getAsString());
            var geometry = CityTemplatePlacementGeometry.of(tree.size(), rotation, CityTemplatePlacementGeometry.Mirror.NONE, List.of());
            var anchor = item.getAsJsonObject("anchorBlock");
            var point = new BlockPoint(anchor.get("x").getAsInt(), anchor.get("z").getAsInt());
            BlockBounds footprint = CityRoadsideTreePlanner.bounds(item.getAsJsonObject("plannedFootprint"));
            assertEquals(geometry.worldBounds(point), footprint);
            assertEquals(geometry.worldPosition(point, tree.root()).asJson(), item.get("treeRootBlock"));
            assertFalse(footprint.overlaps(new BlockBounds(-100, -2, 100, 2)));
            assertTrue(occupied.stream().noneMatch(footprint::overlaps));
            occupied.add(CityStructureMaterializationPlanner.expand(footprint, 2));
            variants.add(tree.ref()); rotations.add(rotation.name());
        }
        assertEquals(3, variants.size()); assertEquals(4, rotations.size());
        assertEquals(result, plan(source(), new JsonObject(), terrain(false)));
    }

    @Test void buildingsEntrancesWallsGatesTowersAndLandscapesHavePriority() {
        JsonObject input = source();
        input.getAsJsonArray("anchors").add(json("""
            {"collisionEnvelope":{"minX":-90,"minZ":-20,"maxX":-60,"maxZ":20},
             "templatePlacementPlan":{"transformed":{"roadEntrances":[
              {"worldPosition":{"x":0,"z":-16},"direction":"SOUTH"}]}}}
            """));
        JsonObject walls = json("""
            {"wallCorridorMask":[{"blockBounds":{"minX":35,"minZ":-50,"maxX":40,"maxZ":50}}],
             "gateCorridorMask":[{"blockBounds":{"minX":60,"minZ":-50,"maxX":65,"maxZ":50}}],
             "wallNodeSlots":[{"blockBounds":{"minX":80,"minZ":-50,"maxX":85,"maxZ":50}}]}
            """);
        JsonObject landscape = json("""
            {"instances":[{"parcelReservations":[{"reservationSpans":[{"z":-8,"minX":-55,"maxX":-25}]}]}]}
            """);
        var result = planner.append(input, walls, landscape, terrain(false), trees());
        var excluded = List.of(new BlockBounds(-92,-22,-58,22), new BlockBounds(-3,-19,3,3),
                new BlockBounds(33,-52,42,52), new BlockBounds(58,-52,67,52),
                new BlockBounds(78,-52,87,52), new BlockBounds(-55,-8,-25,-8));
        int count = 0;
        for (var element : result.getAsJsonArray("anchors")) {
            var item = element.getAsJsonObject();
            if (!CityRoadsideTreePlanner.isTree(item)) continue;
            count++;
            var footprint = CityRoadsideTreePlanner.bounds(item.getAsJsonObject("plannedFootprint"));
            assertTrue(excluded.stream().noneMatch(footprint::overlaps));
        }
        assertTrue(count > 0);
        assertTrue(count < plan(source(), new JsonObject(), terrain(false)).getAsJsonArray("anchors").size());
    }

    @Test void intersectionClearanceAppliesToBothRoadsRegardlessOfInputOrder() {
        JsonObject input = source();
        var roads = input.getAsJsonArray("streetBands");
        roads.add(json("""
            {"streetBandId":"vertical","roadKind":"CITY_MAIN_ROAD", "bounds":{"minX":-2,"minZ":-50,"maxX":2,"maxZ":50}}
            """));
        JsonObject result = plan(input, new JsonObject(), terrain(false));
        for (var element : result.getAsJsonArray("anchors")) {
            var footprint = CityRoadsideTreePlanner.bounds(element.getAsJsonObject().getAsJsonObject("plannedFootprint"));
            assertFalse(footprint.overlaps(new BlockBounds(-4,-52,4,52)));
            assertFalse(footprint.overlaps(new BlockBounds(-102,-4,102,4)));
        }
        var reversed = new JsonArray(); reversed.add(roads.get(1)); reversed.add(roads.get(0));
        input.add("streetBands", reversed);
        assertEquals(result.get("anchors"), plan(input, new JsonObject(), terrain(false)).get("anchors"));
    }

    @Test void waterMissingTerrainBridgeAndOutOfCoverageAreSkipped() {
        assertTrue(plan(source(), new JsonObject(), terrain(true)).getAsJsonArray("anchors").isEmpty());
        var terrain = terrain(false);
        var missing = new LandUseTerrainField(terrain.schema(), terrain.cityId(), terrain.planningBounds(), 16, List.of());
        assertTrue(plan(source(), new JsonObject(), missing).getAsJsonArray("anchors").isEmpty());
        var bridge = source(); bridge.getAsJsonArray("streetBands").get(0).getAsJsonObject().addProperty("roadKind", "CITY_BRIDGE");
        assertTrue(plan(bridge, new JsonObject(), terrain).getAsJsonArray("anchors").isEmpty());
        var walls = json("{\"wallCoverageBounds\":{\"minX\":-100,\"minZ\":-2,\"maxX\":100,\"maxZ\":2}}");
        assertTrue(plan(source(), walls, terrain).getAsJsonArray("anchors").isEmpty());
    }

    @Test void optionalTreesLockWithDecorationPolicyAndDoNotBecomeBuildingAreas() {
        var input = plan(source(), new JsonObject(), terrain(false));
        var result = new CityStructureMaterializationPlanner().planWorldgen(input, null, null, ref -> {
            var tree = trees().stream().filter(t -> t.ref().equals(ref)).findFirst().orElseThrow();
            return CityStructureMaterializationPlanner.TemplateMetadata.readable(tree.hash(), tree.size());
        });
        assertTrue(result.structureMaterializationPlan().get("locked").getAsBoolean());
        assertTrue(result.inferredFunctionAreaMap().getAsJsonArray("areas").isEmpty());
        for (var element : result.structureMaterializationPlan().getAsJsonArray("plannedWorldgenStructures")) {
            var item = element.getAsJsonObject();
            assertEquals(CityTemplateTerrainPosePolicy.STRUCTURE_START_DECORATION, item.get("terrainPosePolicy").getAsString());
            assertTrue(item.has("treeRootBlock"));
            assertFalse(item.getAsJsonArray("ownerChunks").isEmpty());
        }
    }

    private JsonObject plan(JsonObject source, JsonObject walls, LandUseTerrainField terrain) {
        return planner.append(source, walls, new JsonObject(), terrain, trees());
    }
    private static List<CityRoadsideTreePlanner.Tree> trees() {
        return List.of(new CityRoadsideTreePlanner.Tree("geomantia:roadside/a", new CityTemplatePlacementGeometry.Size(10, 11, 8), new BlockPoint(5,4), "hash-a"),
                new CityRoadsideTreePlanner.Tree("geomantia:roadside/b", new CityTemplatePlacementGeometry.Size(12, 10, 11), new BlockPoint(5,7), "hash-b"),
                new CityRoadsideTreePlanner.Tree("geomantia:roadside/c", new CityTemplatePlacementGeometry.Size(10, 16, 9), new BlockPoint(6,2), "hash-c"));
    }
    private static LandUseTerrainField terrain(boolean water) {
        List<LandUseTerrainField.Cell> cells = new ArrayList<>();
        for (int z = -4; z <= 3; z++) for (int x = -8; x <= 7; x++)
            cells.add(new LandUseTerrainField.Cell(x,z,x*16,z*16,16,64,0,0,0,water,0,100,"plains","plain","",true));
        return new LandUseTerrainField(LandUseTerrainField.SCHEMA,"city",new BlockBounds(-128,-64,127,63),16,cells);
    }
    private static JsonObject source() { return json("""
        {"cityId":"city", "anchors":[], "streetBands":[{"streetBandId":"main", "roadKind":"CITY_MAIN_ROAD",
         "bounds":{"minX":-100,"minZ":-2,"maxX":100,"maxZ":2}}]}
        """); }
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
}
