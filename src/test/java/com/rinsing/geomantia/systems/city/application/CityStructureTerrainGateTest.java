package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityStructureTerrainGateTest {
    @Test
    void surfaceRequiresEveryIntersectingCellToBeSampledAndNonWater() {
        LandUseTerrainField field = field(List.of(
                cell(0, false, true),
                cell(1, true, true)));
        CityStructureTerrainGate gate = new CityStructureTerrainGate(field, catalog("SURFACE"));

        CityStructureTerrainGate.Evaluation evaluation = gate.evaluate(
                "test:house", new BlockBounds(8, 2, 23, 13));

        assertFalse(evaluation.passed());
        assertEquals("CITY_STRUCTURE_SURFACE_CELL_WATER", evaluation.reasonCode());
        assertEquals(2, evaluation.trace().get("intersectingCellCount").getAsInt());
        assertEquals("SURFACE", evaluation.trace().get("resolvedTerrainMode").getAsString());
    }

    @Test
    void multipleModesUseOrAndResolveSurfaceWhenAvailable() {
        CityStructureTerrainGate gate = new CityStructureTerrainGate(
                field(List.of(cell(0, false, true), cell(1, false, true))),
                catalog("FLOATING", "SURFACE"));

        CityStructureTerrainGate.Evaluation evaluation = gate.evaluate(
                "test:house", new BlockBounds(0, 0, 31, 15));

        assertTrue(evaluation.passed());
        assertEquals("SURFACE", evaluation.resolvedTerrainMode());
    }

    @Test
    void unsupportedOnlyModesFailExplicitly() {
        CityStructureTerrainGate gate = new CityStructureTerrainGate(
                field(List.of(cell(0, false, true), cell(1, false, true))),
                catalog("EMBEDDED", "FLOATING"));

        CityStructureTerrainGate.Evaluation evaluation = gate.evaluate(
                "test:house", new BlockBounds(0, 0, 15, 15));

        assertFalse(evaluation.passed());
        assertEquals("CITY_STRUCTURE_TERRAIN_MODE_UNSUPPORTED", evaluation.reasonCode());
        assertFalse(evaluation.trace().has("resolvedTerrainMode"));
    }

    @Test
    void conformPolicyRejectsSlopeAndReliefAcrossFullFootprint() {
        CityStructureTerrainGate gate = new CityStructureTerrainGate(
                field(List.of(cell(0, 70, 2, 3), cell(1, 70, 7, 3))), catalog("SURFACE"));

        CityStructureTerrainGate.Evaluation evaluation = gate.evaluate(
                "test:house", new BlockBounds(0, 0, 31, 15), CityBlueprint.TerrainPolicy.CONFORM);

        assertFalse(evaluation.passed());
        assertEquals("CITY_STRUCTURE_SURFACE_CELL_SLOPE_EXCEEDED", evaluation.reasonCode());
        assertEquals("CONFORM", evaluation.trace().get("terrainPolicy").getAsString());
    }

    @Test
    void balancedPolicyRejectsLargeElevationRangeAcrossFullFootprint() {
        CityStructureTerrainGate gate = new CityStructureTerrainGate(
                field(List.of(cell(0, 64, 2, 3), cell(1, 80, 2, 3))), catalog("SURFACE"));

        CityStructureTerrainGate.Evaluation evaluation = gate.evaluate(
                "test:house", new BlockBounds(0, 0, 31, 15), CityBlueprint.TerrainPolicy.BALANCED);

        assertFalse(evaluation.passed());
        assertEquals("CITY_STRUCTURE_SURFACE_ELEVATION_RANGE_EXCEEDED", evaluation.reasonCode());
        assertEquals(16.0, evaluation.trace().get("elevationRange").getAsDouble());
    }

    private static LandUseTerrainField field(List<LandUseTerrainField.Cell> cells) {
        return new LandUseTerrainField(LandUseTerrainField.CURRENT_SCHEMA_VERSION, "city_test",
                new BlockBounds(0, 0, 31, 15), 16, cells);
    }

    private static LandUseTerrainField.Cell cell(int cellX, boolean water, boolean sampled) {
        return new LandUseTerrainField.Cell(cellX, 0, cellX * 16, 0, 16,
                70, 99, 99, 99, water, water ? 3 : 0, water ? 0 : 20,
                "minecraft:plains", "plain", "patch", sampled);
    }

    private static LandUseTerrainField.Cell cell(int cellX, double elevation,
                                                 double slope, double localRelief) {
        return new LandUseTerrainField.Cell(cellX, 0, cellX * 16, 0, 16,
                elevation, slope, localRelief, localRelief, false, 0, 20,
                "minecraft:plains", "plain", "patch", true);
    }

    private static JsonObject catalog(String... modes) {
        StringBuilder values = new StringBuilder();
        for (String mode : modes) {
            if (!values.isEmpty()) values.append(',');
            values.append('"').append(mode).append('"');
        }
        return JsonParser.parseString("""
                {"semanticProfiles":[{"semanticProfileId":"test:house","terrainModes":[%s]}]}
                """.formatted(values)).getAsJsonObject();
    }
}
