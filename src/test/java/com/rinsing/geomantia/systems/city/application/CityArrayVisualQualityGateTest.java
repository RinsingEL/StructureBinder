package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityArrayVisualQualityGateTest {
    private final CityArrayVisualQualityGate gate = new CityArrayVisualQualityGate();
    private final CityInternalStreetPlanner streets = new CityInternalStreetPlanner();
    private final CityBlueprintGroupLayoutPlanner layout = new CityBlueprintGroupLayoutPlanner();

    @Test
    void rejectsGridAnchorMoreThanOneBlockFromItsTheoreticalPoint() {
        JsonArray anchors = new JsonArray();
        anchors.add(anchor("grid", "GRID", 2, 0, 0, 0, gridLayout(0, 0, 0, 0)));
        anchors.add(anchor("grid", "GRID", 20, 0, 20, 0, gridLayout(1, 0, 20, 0)));
        anchors.add(anchor("grid", "GRID", 0, 20, 0, 20, gridLayout(0, 1, 0, 20)));
        anchors.add(anchor("grid", "GRID", 20, 20, 20, 20, gridLayout(1, 1, 20, 20)));
        JsonArray roads = roads("grid", "GRID", anchors);

        var result = gate.evaluate(anchors, roads);

        assertFalse(result.passed());
        assertTrue(result.hardBlocks().contains("grid: GRID_ROW_COLUMN_ERROR_EXCEEDS_ONE_BLOCK"));
    }

    @Test
    void rejectsCourtyardWhoseCenterIsOccupied() {
        JsonArray anchors = new JsonArray();
        anchors.add(anchor("court", "COURTYARD", 0, 0, 0, 0, courtyardLayout(-1, 0)));
        anchors.add(anchor("court", "COURTYARD", 20, 0, 20, 0, courtyardLayout(0, 1)));
        anchors.add(anchor("court", "COURTYARD", 20, 20, 20, 20, courtyardLayout(1, 1)));
        anchors.add(anchor("court", "COURTYARD", -20, 0, -20, 0, courtyardLayout(0, -1)));
        anchors.add(anchor("court", "COURTYARD", -20, 20, -20, 20, courtyardLayout(1, -1)));
        JsonArray roads = roads("court", "COURTYARD", anchors);

        var result = gate.evaluate(anchors, roads);

        assertFalse(result.passed());
        assertTrue(result.hardBlocks().contains("court: COURTYARD_CENTER_OCCUPIED"));
    }

    @Test
    void rejectsDisconnectedOrganicGapGraph() {
        JsonArray anchors = new JsonArray();
        anchors.add(anchor("organic", "ORGANIC_COMPACT", 0, 0, 0, 0, new JsonObject()));
        anchors.add(anchor("organic", "ORGANIC_COMPACT", 40, 0, 40, 0, new JsonObject()));

        var result = gate.evaluate(anchors, new JsonArray());

        assertFalse(result.passed());
        assertTrue(result.hardBlocks().contains(
                "organic: ORGANIC_ONE_TO_THREE_BLOCK_GAPS_DISCONNECTED"));
    }

    @Test
    void rejectsAnyFormalRoadOverlappingStructureCollision() {
        JsonArray anchors = new JsonArray();
        JsonObject source = anchor("organic", "ORGANIC_COMPACT", 0, 0, 0, 0, new JsonObject());
        anchors.add(source);
        JsonObject road = new JsonObject();
        road.addProperty("streetBandId", "city::main");
        road.addProperty("groupId", "__city_main_road__");
        road.addProperty("roadKind", "CITY_MAIN_ROAD");
        road.add("bounds", source.getAsJsonObject("collisionEnvelope").deepCopy());
        JsonArray roads = new JsonArray();
        roads.add(road);

        var result = gate.evaluate(anchors, roads);

        assertFalse(result.passed());
        assertTrue(result.hardBlocks().stream().anyMatch(value ->
                value.contains("ROAD_OVERLAPS_STRUCTURE")));
        assertTrue(result.json().get("roadStructureOverlapCount").getAsInt() > 0);
    }

    private JsonArray roads(String groupId, String algorithm, JsonArray anchors) {
        JsonArray result = new JsonArray();
        List<JsonObject> source = anchors.asList().stream().map(value -> value.getAsJsonObject()).toList();
        streets.plan(groupId, algorithm, layout.parameters(algorithm, CityBlueprint.DensityClass.DENSE),
                source, false).forEach(result::add);
        return result;
    }

    private static JsonObject gridLayout(int row, int column, int x, int z) {
        JsonObject value = new JsonObject();
        value.addProperty("gridRow", row);
        value.addProperty("gridColumn", column);
        value.addProperty("gridPitchBlocks", 20);
        value.add("theoreticalAnchor", point(x, z));
        return value;
    }

    private static JsonObject courtyardLayout(int row, int column) {
        JsonObject value = new JsonObject();
        value.addProperty("courtyardRow", row);
        value.addProperty("courtyardColumn", column);
        value.addProperty("spacingBlocks", 20);
        value.add("courtyardCenter", point(0, 0));
        return value;
    }

    private static JsonObject anchor(String groupId, String algorithm, int x, int z,
                                     int theoreticalX, int theoreticalZ, JsonObject layout) {
        layout.addProperty("algorithm", algorithm);
        JsonObject value = new JsonObject();
        value.addProperty("anchorId", groupId + ':' + x + ':' + z);
        value.addProperty("placementGroupId", groupId);
        value.addProperty("blueprintPlacementPhase", "fill");
        value.add("anchorBlock", point(x, z));
        JsonObject collision = new JsonObject();
        collision.addProperty("minX", x - 4);
        collision.addProperty("minZ", z - 4);
        collision.addProperty("maxX", x + 4);
        collision.addProperty("maxZ", z + 4);
        value.add("collisionEnvelope", collision);
        value.add("blueprintLayout", layout);
        return value;
    }

    private static JsonObject point(int x, int z) {
        JsonObject value = new JsonObject();
        value.addProperty("x", x);
        value.addProperty("z", z);
        return value;
    }
}
