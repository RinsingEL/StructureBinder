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

    private CityBlueprintGroupLayoutPlanner.Parameters parameters(String algorithm) {
        return layout.parameters(algorithm, CityBlueprint.DensityClass.DENSE);
    }

    private static JsonObject gridAnchor(String id, int row, int column, int x, int z) {
        JsonObject layout = new JsonObject();
        layout.addProperty("gridRow", row);
        layout.addProperty("gridColumn", column);
        layout.add("theoreticalAnchor", point(x, z));
        return anchor(id, "fill", x, z, x + 8, z + 8, layout);
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
        if (id.equals("a") || id.equals("b") || id.equals("c") || id.equals("d")) return "grid";
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
