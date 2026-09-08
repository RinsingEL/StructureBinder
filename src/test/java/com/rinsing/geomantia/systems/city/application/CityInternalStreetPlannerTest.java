package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityInternalStreetPlannerTest {
    private final CityBlueprintGroupLayoutPlanner layout = new CityBlueprintGroupLayoutPlanner();
    private final CityInternalStreetPlanner planner = new CityInternalStreetPlanner();

    @Test
    void gridProducesMainStreetAndColumnLaneFromFixedRowsAndColumns() {
        var anchors = List.of(
                gridAnchor("a", 0, 0, 0, 0),
                gridAnchor("b", 1, 0, 20, 0),
                gridAnchor("c", 0, 1, 0, 20),
                gridAnchor("d", 1, 1, 20, 20));

        var roads = planner.plan("grid", "GRID", parameters("GRID"), anchors, false);

        assertEquals(2, roads.size());
        assertTrue(roads.stream().anyMatch(road -> "GRID_MAIN_STREET".equals(
                road.get("roadKind").getAsString())));
        assertTrue(roads.stream().anyMatch(road -> "GRID_COLUMN_LANE".equals(
                road.get("roadKind").getAsString())));
    }

    @Test
    void gridSkipsLegacyInvalidCorridorInsteadOfFailingTheWholeCity() {
        var anchors = List.of(
                gridAnchor("a", 0, 0, 0, 0, 30, 8),
                gridAnchor("b", 1, 0, 29, 0, 37, 8));

        var roads = planner.plan("grid", "GRID", parameters("GRID"), anchors, false);

        assertTrue(roads.isEmpty());
    }

    @Test
    void gridRoadsCrossTheActualOccupiedExtentSoTheNetworkStaysConnected() {
        var anchors = List.of(
                gridAnchor("a", 0, 0, 0, 0),
                gridAnchor("b", 1, 0, 20, 0),
                gridAnchor("grid_far", 2, 0, 40, 100, 48, 108));

        var roads = planner.plan("grid", "GRID", parameters("GRID"), anchors, false);

        JsonObject first = roads.stream().filter(road -> "GRID_MAIN_STREET".equals(
                road.get("roadKind").getAsString())).findFirst().orElseThrow();
        assertEquals(108, first.getAsJsonObject("bounds").get("maxZ").getAsInt());
    }

    @Test
    void courtyardProducesOpenSouthRingAndGateForFiveBuildings() {
        var anchors = List.of(
                courtyardAnchor("north", 0, -20),
                courtyardAnchor("east", 20, 0),
                courtyardAnchor("south_east", 20, 20),
                courtyardAnchor("south_west", -20, 20),
                courtyardAnchor("west", -20, 0));

        var roads = planner.plan("court", "COURTYARD", parameters("COURTYARD"), anchors, false);

        assertEquals(6, roads.size());
        assertEquals(1, roads.stream().filter(road -> "COURTYARD_GATE".equals(
                road.get("roadKind").getAsString())).count());
        assertFalse(roads.stream().anyMatch(road -> "COURTYARD_RING_SOUTH".equals(
                road.get("roadKind").getAsString())));
    }

    @Test
    void compactConnectsOrderedLaneTargetsWithAxisAlignedSegments() {
        var anchors = List.of(
                compactAnchor("compact_west", -20, 2),
                compactAnchor("compact_center", 0, 0),
                compactAnchor("compact_east", 20, -2));

        var roads = planner.plan("compact", "COMPACT", parameters("COMPACT"), anchors, false);

        assertEquals(4, roads.size());
        assertTrue(roads.stream().allMatch(road -> "COMPACT_ALLEY".equals(
                road.get("roadKind").getAsString())));
    }

    @Test
    void centerAxisStreetIsAbsentByDefaultAndPresentWhenEnabled() {
        var anchors = List.of(
                anchor("sym_north", "fill", 10, -30, 14, -26, new JsonObject()),
                anchor("sym_core", "required", -4, -4, 4, 4, new JsonObject()),
                anchor("sym_south", "fill", 10, 26, 14, 30, new JsonObject()));

        assertTrue(planner.plan("symmetric", "CENTER_SYMMETRIC",
                parameters("CENTER_SYMMETRIC"), anchors, false).isEmpty());
        var enabled = planner.plan("symmetric", "CENTER_SYMMETRIC",
                parameters("CENTER_SYMMETRIC"), anchors, true);
        assertEquals(2, enabled.size());
        assertTrue(enabled.stream().allMatch(road -> road.get("roadKind").getAsString()
                .startsWith("CENTER_AXIS_")));
    }

    @Test
    void gridStreetSkeletonCutsBlocksFromTheCoreBeforeFillBuildingsExist() {
        JsonObject core = gridAnchor("grid_core", 0, 0, 0, 0);
        core.addProperty("blueprintPlacementPhase", "required");
        core.getAsJsonObject("blueprintLayout").addProperty("gridPitchBlocks", 20);
        core.getAsJsonObject("blueprintLayout").addProperty("spacingBlocks", 20);

        var roads = planner.planSkeleton("grid", "GRID", parameters("GRID"), List.of(core),
                false, 6, 60, layout.worldFrame(new com.rinsing.geomantia.systems.city.domain.model.BlockPoint(0, 0)));

        assertFalse(roads.isEmpty());
        assertTrue(roads.stream().allMatch(road -> road.get("reservedBeforeFill").getAsBoolean()));
        assertTrue(roads.stream().allMatch(road -> "STREET_SKELETON_BEFORE_FILL".equals(
                road.get("planningPhase").getAsString())));
        var coreBounds = CityStructureCandidateEnvelope.bounds(core.getAsJsonObject("collisionEnvelope"));
        assertTrue(roads.stream().noneMatch(road -> CityStructureCandidateEnvelope.bounds(
                road.getAsJsonObject("bounds")).overlaps(coreBounds)));
    }

    @Test
    void compactStreetSkeletonScalesWithThePlannedFormationInsteadOfStayingLocal() {
        JsonObject core = compactAnchor("compact_core", 0, 0);
        core.addProperty("blueprintPlacementPhase", "required");

        var roads = planner.planSkeleton("compact", "COMPACT", parameters("COMPACT"), List.of(core),
                false, 8, 232,
                layout.worldFrame(new com.rinsing.geomantia.systems.city.domain.model.BlockPoint(0, 0)));

        assertEquals(3, roads.size());
        int span = roads.stream().map(road -> CityStructureCandidateEnvelope.bounds(
                        road.getAsJsonObject("bounds")))
                .mapToInt(bounds -> Math.max(bounds.widthBlocks(), bounds.heightBlocks()))
                .max().orElseThrow();
        assertTrue(span >= 100);
    }

    @Test
    void connectsActualDoorThroughNarrowGapToNearbyCityMainRoad() {
        JsonObject house = anchor("compact_house", "fill", 0, 0, 4, 4, new JsonObject());
        JsonObject transformed = new JsonObject();
        JsonObject entrance = new JsonObject();
        entrance.addProperty("entranceId", "door");
        entrance.addProperty("direction", "EAST");
        entrance.add("worldPosition", point(4, 2));
        com.google.gson.JsonArray entrances = new com.google.gson.JsonArray();
        entrances.add(entrance);
        transformed.add("roadEntrances", entrances);
        JsonObject placement = new JsonObject();
        placement.add("transformed", transformed);
        house.add("templatePlacementPlan", placement);
        JsonObject oppositeHouse = anchor("compact_other", "fill", 6, -5, 10, 4, new JsonObject());
        JsonObject main = new JsonObject();
        main.addProperty("groupId", "city-network");
        main.addProperty("roadHierarchy", "MAIN");
        main.addProperty("widthBlocks", 7);
        main.add("start", point(5, 10));
        main.add("end", point(20, 10));
        JsonObject bounds = new JsonObject();
        bounds.addProperty("minX", 5); bounds.addProperty("maxX", 20);
        bounds.addProperty("minZ", 7); bounds.addProperty("maxZ", 13);
        main.add("bounds", bounds);
        String original = house.toString();

        var result = planner.finalizeSkeleton(List.of(), List.of(main), List.of(house, oppositeHouse));

        assertEquals("CONNECTED_BY_SHARED_EXTENSION", result.trace().getAsJsonArray("accessOutcomes")
                .get(0).getAsJsonObject().get("status").getAsString());
        assertFalse(result.streetBands().isEmpty());
        assertTrue(result.streetBands().stream().allMatch(band ->
                "SURFACE_ONLY".equals(band.get("crossSectionProfile").getAsString())));
        assertTrue(result.streetBands().stream().anyMatch(band ->
                CityStructureCandidateEnvelope.bounds(band.getAsJsonObject("bounds")).contains(5, 2)));
        assertTrue(result.streetBands().stream().noneMatch(band ->
                CityStreetObstacleRouter.crossSection(band).overlaps(
                        CityStructureCandidateEnvelope.bounds(oppositeHouse.getAsJsonObject("collisionEnvelope")))));
        assertEquals(original, house.toString());
    }

    private CityBlueprintGroupLayoutPlanner.Parameters parameters(String algorithm) {
        return layout.parameters(algorithm, CityBlueprint.DensityClass.DENSE);
    }

    private static JsonObject gridAnchor(String id, int row, int column, int x, int z) {
        return gridAnchor(id, row, column, x, z, x + 8, z + 8);
    }

    private static JsonObject gridAnchor(String id, int row, int column,
                                         int minX, int minZ, int maxX, int maxZ) {
        JsonObject layout = new JsonObject();
        layout.addProperty("gridRow", row);
        layout.addProperty("gridColumn", column);
        layout.add("theoreticalAnchor", point(minX, minZ));
        return anchor(id, "fill", minX, minZ, maxX, maxZ, layout);
    }

    private static JsonObject courtyardAnchor(String id, int x, int z) {
        JsonObject layout = new JsonObject();
        layout.add("courtyardCenter", point(0, 0));
        layout.addProperty("spacingBlocks", 20);
        return anchor(id, "fill", x, z, x + 8, z + 8, layout);
    }

    private static JsonObject compactAnchor(String id, int x, int z) {
        JsonObject layout = new JsonObject();
        layout.add("compactLaneTarget", point(x, z));
        return anchor(id, "fill", x - 4, z + 4, x + 4, z + 12, layout);
    }

    private static JsonObject anchor(String id, String phase, int minX, int minZ, int maxX, int maxZ,
                                     JsonObject layout) {
        JsonObject value = new JsonObject();
        value.addProperty("anchorId", id);
        value.addProperty("placementGroupId", group(id));
        value.addProperty("blueprintPlacementPhase", phase);
        JsonObject collision = new JsonObject();
        collision.addProperty("minX", minX);
        collision.addProperty("minZ", minZ);
        collision.addProperty("maxX", maxX);
        collision.addProperty("maxZ", maxZ);
        value.add("collisionEnvelope", collision);
        value.add("blueprintLayout", layout);
        return value;
    }

    private static String group(String id) {
        if (id.equals("a") || id.equals("b") || id.equals("c") || id.equals("d")
                || id.startsWith("grid_")) return "grid";
        if (id.equals("north") || id.equals("east") || id.startsWith("south_") || id.equals("west")) {
            return "court";
        }
        if (id.startsWith("sym_")) return "symmetric";
        if (id.startsWith("compact_")) return "compact";
        return "compact";
    }

    private static JsonObject point(int x, int z) {
        JsonObject value = new JsonObject();
        value.addProperty("x", x);
        value.addProperty("z", z);
        return value;
    }
}
