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
    void adjacentShallowPondCellsAreAdmittedBelowAreaBudget() {
        var result = evaluateGrid((x, z) -> x >= 5 && x < 10 && z >= 5 && z < 13 ? "pond" : "plain",
                new BlockBounds(0, 0, 319, 319));
        assertTrue(result.passed(), result.trace().toString());
        assertEquals(0.10, result.trace().get("anomalyAreaRatio").getAsDouble(), 1e-9);
        assertTrue(result.trace().get("terrainAdaptationRequired").getAsBoolean());
        assertEquals(0, result.trace().get("rejectedCellCount").getAsInt());
    }

    @Test
    void pondAboveAreaBudgetIsRejected() {
        var result = evaluateGrid((x, z) -> x >= 5 && x < 11 && z >= 5 && z < 13 ? "pond" : "plain",
                new BlockBounds(0, 0, 319, 319));
        assertFalse(result.passed());
        assertEquals(0.12, result.trace().get("anomalyAreaRatio").getAsDouble(), 1e-9);
    }

    @Test
    void localizedGullyDoesNotLetElevationExtremesVetoLargeBuilding() {
        var result = evaluateGrid((x, z) -> x == 8 && z == 8 ? "gully" : "plain",
                new BlockBounds(0, 0, 319, 319));
        assertTrue(result.passed(), result.trace().toString());
        assertEquals(30, result.trace().get("elevationRange").getAsDouble());
        assertEquals(0.0025, result.trace().get("anomalyAreaRatio").getAsDouble(), 1e-9);
    }

    @Test
    void broadElevationSpreadCannotHideOnBothSidesOfMedian() {
        var cells = new java.util.ArrayList<LandUseTerrainField.Cell>();
        for (int z = 0; z < 20; z++) for (int x = 0; x < 20; x++) {
            cells.add(new LandUseTerrainField.Cell(x, z, x * 16, z * 16, 16,
                    x < 5 ? 50 : x >= 15 ? 90 : 70, 1, 1, 1, false, 0, 10,
                    "minecraft:plains", "plain", "patch", true));
        }
        var bounds = new BlockBounds(0, 0, 319, 319);
        var field = new LandUseTerrainField(LandUseTerrainField.SCHEMA, "city_test", bounds, 16, cells);
        var result = new CityStructureTerrainGate(field, catalog("SURFACE"))
                .evaluate("test:house", bounds, CityBlueprint.TerrainPolicy.BALANCED, true);
        assertFalse(result.passed());
        assertEquals(0.5, result.trace().get("anomalyAreaRatio").getAsDouble(), 1e-9);
    }

    @Test
    void deepOrUnknownTerrainCannotBeDilutedByBuildingSize() {
        for (String kind : List.of("deep", "unsampled", "missing", "cliff")) {
            var result = evaluateGrid((x, z) -> x == 8 && z == 8 ? kind : "plain",
                    new BlockBounds(0, 0, 319, 319));
            assertFalse(result.passed(), kind);
            assertTrue(result.trace().get("hardTerrainFailure").getAsBoolean(), kind);
        }
    }

    @Test
    void narrowContinuousGullyCrossingBuildingIsRejectedDespiteLowRatio() {
        var result = evaluateGrid((x, z) -> x == 8 ? "gully" : "plain",
                new BlockBounds(0, 0, 319, 319));
        assertFalse(result.passed());
        assertEquals(0.05, result.trace().get("anomalyAreaRatio").getAsDouble(), 1e-9);
        assertTrue(result.trace().get("crossingAnomaly").getAsBoolean());
    }

    @Test
    void edgeCellsUseActualOverlapArea() {
        var result = evaluateGrid((x, z) -> x == 0 && z == 8 ? "pond" : "plain",
                new BlockBounds(15, 0, 319, 319));
        assertTrue(result.passed());
        assertEquals(16, result.trace().get("anomalyAreaBlocks").getAsLong());
        assertEquals(16.0 / (305 * 320), result.trace().get("anomalyAreaRatio").getAsDouble(), 1e-9);
    }

    private static CityStructureTerrainGate.Evaluation evaluateGrid(
            java.util.function.BiFunction<Integer, Integer, String> terrain, BlockBounds footprint) {
        var cells = new java.util.ArrayList<LandUseTerrainField.Cell>();
        for (int z = 0; z < 20; z++) for (int x = 0; x < 20; x++) {
            String kind = terrain.apply(x, z);
            if (kind.equals("missing")) continue;
            boolean water = kind.equals("pond") || kind.equals("deep");
            cells.add(new LandUseTerrainField.Cell(x, z, x * 16, z * 16, 16,
                    kind.equals("gully") ? 40 : 70, kind.equals("cliff") ? 60 : 1,
                    kind.equals("gully") ? 30 : 1, 1, water,
                    kind.equals("deep") ? 30 : water ? 2 : 0, 10,
                    "minecraft:plains", water ? "lake" : "plain", "patch", !kind.equals("unsampled")));
        }
        var field = new LandUseTerrainField(LandUseTerrainField.SCHEMA, "city_test",
                new BlockBounds(0, 0, 319, 319), 16, cells);
        return new CityStructureTerrainGate(field, catalog("SURFACE")).evaluate(
                "test:house", footprint, CityBlueprint.TerrainPolicy.BALANCED, true);
    }

    @Test
    void onlyShallowEnclosedWaterPitsReceiveFoundationAdmission() {
        for (String kind : List.of("pit", "lake", "river", "wide_pit", "deep_pit")) {
            var cells = new java.util.ArrayList<LandUseTerrainField.Cell>();
            for (int z=-1; z<=1; z++) for(int x=-1; x<=1; x++) {
                boolean water = x==0 && z==0 || kind.equals("wide_pit") && x==1 && z==0;
                cells.add(new LandUseTerrainField.Cell(x,z,x*16,z*16,16,70,1,1,1,water,
                        water ? kind.equals("deep_pit") ? 30 : 2 : 0,water ? 0 : 10,
                        "minecraft:plains",water ? kind : "plain","patch",true));
            }
            var field = new LandUseTerrainField(LandUseTerrainField.SCHEMA,"city_test",new BlockBounds(-16,-16,31,31),16,cells);
            var result = new CityStructureTerrainGate(field,catalog("SURFACE")).evaluate("test:house",new BlockBounds(1,1,8,8),CityBlueprint.TerrainPolicy.BALANCED,true);
            assertEquals(kind.equals("pit"),result.passed(),kind+result.trace());
            if (result.passed()) assertTrue(result.trace().get("terrainAdaptationRequired").getAsBoolean());
        }
    }

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
    void conformPolicyMarksSlopeForFoundationInsteadOfRejectingCity() {
        CityStructureTerrainGate gate = new CityStructureTerrainGate(
                field(List.of(cell(0, 70, 2, 3), cell(1, 70, 7, 3))), catalog("SURFACE"));

        CityStructureTerrainGate.Evaluation evaluation = gate.evaluate(
                "test:house", new BlockBounds(0, 0, 31, 15), CityBlueprint.TerrainPolicy.CONFORM, true);

        assertTrue(evaluation.passed());
        assertTrue(evaluation.trace().get("terrainAdaptationRequired").getAsBoolean());
        assertEquals("realize_designed_platform",
                evaluation.trace().getAsJsonArray("terrainAdaptations").get(0)
                        .getAsJsonObject().get("action").getAsString());
        assertEquals("CONFORM", evaluation.trace().get("terrainPolicy").getAsString());
    }

    @Test
    void balancedPolicyMarksLargeElevationRangeForFoundationInsteadOfRejectingCity() {
        CityStructureTerrainGate gate = new CityStructureTerrainGate(
                field(List.of(cell(0, 64, 2, 3), cell(1, 80, 2, 3))), catalog("SURFACE"));

        CityStructureTerrainGate.Evaluation evaluation = gate.evaluate(
                "test:house", new BlockBounds(0, 0, 31, 15), CityBlueprint.TerrainPolicy.BALANCED, true);

        assertTrue(evaluation.passed());
        assertTrue(evaluation.trace().get("terrainAdaptationRequired").getAsBoolean());
        assertEquals("CITY_STRUCTURE_SURFACE_ELEVATION_RANGE_EXCEEDED",
                evaluation.trace().getAsJsonArray("terrainAdaptations").get(0)
                        .getAsJsonObject().get("reasonCode").getAsString());
        assertEquals(16.0, evaluation.trace().get("elevationRange").getAsDouble());
    }

    @Test
    void extremeReliefSkipsMemberEvenWithAnEngineeredPlatform() {
        var gate = new CityStructureTerrainGate(field(List.of(cell(0, 68, 80, 120), cell(1, -40, 2, 3))), catalog("SURFACE"));
        var result = gate.evaluate("test:house", new BlockBounds(0,0,31,15), CityBlueprint.TerrainPolicy.CONFORM, true);
        assertFalse(result.passed());
        assertEquals("CITY_STRUCTURE_TERRAIN_UNFIT_SKIP_MEMBER", result.reasonCode());
        assertEquals("DESIGN_FIRST_PLATFORM_REALIZATION", result.trace().get("terrainAdaptationPolicy").getAsString());
        assertTrue(result.trace().get("terrainAdaptationRequired").getAsBoolean());
        assertTrue(result.trace().get("rejectedCellCount").getAsInt() > 0);
        assertFalse(gate.evaluate("test:house", new BlockBounds(0,0,31,15), CityBlueprint.TerrainPolicy.CONFORM, false).passed(),
                "A natural unpaved group must not pretend to own an engineered platform.");
    }

    private static LandUseTerrainField field(List<LandUseTerrainField.Cell> cells) {
        return new LandUseTerrainField(LandUseTerrainField.SCHEMA, "city_test",
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
