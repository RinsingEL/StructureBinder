package com.rinsing.geomantia.systems.city;

import com.rinsing.geomantia.systems.city.application.CityRoadBoundaryPlanner;
import com.rinsing.geomantia.systems.city.domain.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class CityRoadBoundaryPlannerTest {
    @Test
    void civicCoreAndMainGate_generateMainRoadAndOperations() {
        CityRoadBoundaryPlanner.Result result = new CityRoadBoundaryPlanner().plan(
                site(List.of(entry("main_gate", -80, 0, "west"))),
                zoneMap(List.of(
                        zone("civic", CityFunctionType.CIVIC_CORE, 0, 0, 40, true),
                        zone("res", CityFunctionType.RESIDENTIAL, 80, 80, 32, true),
                        zone("def", CityFunctionType.DEFENSE, -60, 0, 24, true))),
                List.of(stats("civic", 0.0), stats("res", 0.0), stats("def", 0.0)));

        assertTrue(result.qualityReport().passed());
        assertEquals(3, result.roadIntent().edges().size());
        assertTrue(result.roadIntent().edges().stream().anyMatch(edge -> edge.edgeType().equals("main")));
        assertTrue(result.buildOperationPlan().operations().stream()
                .anyMatch(op -> op.operationType().equals("clearVegetation")));
        assertTrue(result.buildOperationPlan().operations().stream()
                .anyMatch(op -> op.operationType().equals("pasteTemplate") && op.templateId().equals("gate_small")));
        assertFalse(result.buildableAreaMap().zones().isEmpty());
        assertTrue(result.buildableAreaMap().zones().stream()
                .mapToInt(BuildableAreaMap.ZoneBuildability::reservedCellCount)
                .sum() > 0);
    }

    @Test
    void aStarMainRoadAvoidsNonEndpointZone() {
        CityRoadBoundaryPlanner.Result result = new CityRoadBoundaryPlanner().plan(
                site(List.of(entry("main_gate", -112, 0, "west"))),
                zoneMap(List.of(
                        zone("civic", CityFunctionType.CIVIC_CORE, 96, 0, 24, true),
                        zone("res_blocker", CityFunctionType.RESIDENTIAL, 0, 0, 96, false))),
                List.of(stats("civic", 0.0), stats("res_blocker", 0.0)));

        RoadIntent.Edge mainRoad = result.roadIntent().edges().stream()
                .filter(edge -> edge.edgeType().equals("main"))
                .findFirst()
                .orElseThrow();

        assertTrue(result.qualityReport().passed());
        assertEquals("a_star_grid_v0.1",
                result.qualityReport().metrics().get("pathAlgorithm").getAsString());
        assertEquals(0, result.qualityReport().metrics().get("pathFallbackCount").getAsInt());
        assertTrue(mainRoad.polyline().stream().anyMatch(point -> Math.abs(point.z()) >= 48),
                () -> "Expected A* to route around blocker, got " + mainRoad.polyline());
    }

    @Test
    void noCivicCore_fallsBackToMarket() {
        CityRoadBoundaryPlanner.Result result = new CityRoadBoundaryPlanner().plan(
                site(List.of(entry("north_gate", 0, -100, "north"))),
                zoneMap(List.of(
                        zone("market", CityFunctionType.MARKET, 0, 0, 40, true),
                        zone("res", CityFunctionType.RESIDENTIAL, 80, 40, 32, true))),
                List.of(stats("market", 0.0), stats("res", 0.0)));

        assertTrue(result.qualityReport().passed());
        assertTrue(result.roadIntent().nodes().stream()
                .anyMatch(node -> node.nodeType().equals("core") && node.zonePatchId().equals("market")));
    }

    @Test
    void noEntry_hardBlocks() {
        CityRoadBoundaryPlanner.Result result = new CityRoadBoundaryPlanner().plan(
                site(List.of()),
                zoneMap(List.of(zone("civic", CityFunctionType.CIVIC_CORE, 0, 0, 40, true))),
                List.of(stats("civic", 0.0)));

        assertFalse(result.qualityReport().passed());
        assertTrue(result.qualityReport().hardBlocks().stream()
                .anyMatch(block -> block.contains("No entry candidate")));
    }

    @Test
    void missingMemberCells_warnsBoundaryEnvelopeFallback() {
        CityRoadBoundaryPlanner.Result result = new CityRoadBoundaryPlanner().plan(
                site(List.of(entry("main_gate", -80, 0, "west"))),
                zoneMap(List.of(
                        zone("civic", CityFunctionType.CIVIC_CORE, 0, 0, 40, false),
                        zone("farm", CityFunctionType.FARM_OR_PASTURE, 80, 40, 32, false))),
                List.of(stats("civic", 0.0), stats("farm", 0.0)));

        assertTrue(result.qualityReport().passed());
        assertFalse(result.qualityReport().warnings().isEmpty());
        assertTrue(result.boundaryIntent().edges().stream()
                .anyMatch(edge -> edge.treatmentType().equals("green_buffer")));
    }

    @Test
    void nonHarborWaterContactDoesNotTurnEveryBoundaryIntoWaterfrontRoad() {
        CityRoadBoundaryPlanner.Result result = new CityRoadBoundaryPlanner().plan(
                site(List.of(entry("main_gate", -80, 0, "west"))),
                zoneMap(List.of(
                        zone("civic", CityFunctionType.CIVIC_CORE, 0, 0, 40, true),
                        zone("res", CityFunctionType.RESIDENTIAL, 80, 40, 48, true),
                        zone("def", CityFunctionType.DEFENSE, -64, 64, 48, true),
                        zone("harbor", CityFunctionType.HARBOR_OR_WATERFRONT, 64, -64, 48, true))),
                List.of(stats("civic", 0.5), stats("res", 0.5), stats("def", 0.5), stats("harbor", 0.5)));

        assertTrue(result.boundaryIntent().edges().stream()
                .anyMatch(edge -> edge.fromZoneId().equals("res") && edge.treatmentType().equals("green_buffer")));
        assertTrue(result.boundaryIntent().edges().stream()
                .anyMatch(edge -> edge.fromZoneId().equals("def") && edge.treatmentType().equals("wall_hint")));
        assertTrue(result.boundaryIntent().edges().stream()
                .anyMatch(edge -> edge.fromZoneId().equals("harbor") && edge.treatmentType().equals("waterfront")));
        assertTrue(result.buildOperationPlan().operations().stream()
                .anyMatch(op -> op.operationType().equals("carveBuffer")
                        && op.sourceIntentId().equals("boundary_03")
                        && op.material().equals("minecraft:stone_bricks")));
        assertTrue(result.buildOperationPlan().operations().stream()
                .anyMatch(op -> op.operationType().equals("carveBuffer")
                        && op.sourceIntentId().equals("boundary_02")
                        && op.material().equals("minecraft:grass_block")));
    }

    private CitySiteContext site(List<EntryCandidate> entries) {
        return new CitySiteContext(
                CitySiteContext.CURRENT_SCHEMA_VERSION,
                "city_01",
                "realm_01",
                "minecraft:overworld",
                "city_01",
                "candidate_01",
                new BlockBounds(-128, -128, 128, 128),
                new PlanningGrid(-128, -128, 16, 16, 16),
                new BlockPoint(0, 0),
                "village",
                CityScale.VILLAGE,
                256,
                entries,
                TerritoryCheckResult.INSIDE);
    }

    private EntryCandidate entry(String id, int x, int z, String direction) {
        return new EntryCandidate(id, new BlockPoint(x, z), direction, id);
    }

    private FunctionZoneMap zoneMap(List<FunctionZonePatch> zones) {
        return new FunctionZoneMap(
                FunctionZoneMap.CURRENT_SCHEMA_VERSION,
                "city_01",
                new PlanningGrid(-128, -128, 16, 16, 16),
                zones,
                List.of(),
                new CityQualityReport(true, 100, List.of(), List.of(), List.of(), new com.google.gson.JsonObject()));
    }

    private FunctionZonePatch zone(String id, CityFunctionType type, int x, int z, int size, boolean memberCells) {
        List<PatchMemberCell> cells = memberCells
                ? List.of(
                new PatchMemberCell(x / 16, z / 16, x, z),
                new PatchMemberCell(x / 16 + 1, z / 16, x + 16, z),
                new PatchMemberCell(x / 16, z / 16 + 1, x, z + 16))
                : List.of();
        return new FunctionZonePatch(
                id,
                "group_" + id,
                id,
                type,
                semanticTerms(type),
                List.of("patch_" + id),
                new BlockBounds(x - size / 2, z - size / 2, x + size / 2, z + size / 2),
                cells,
                size * size,
                "",
                id + "_stats",
                "",
                "");
    }

    private List<String> semanticTerms(CityFunctionType type) {
        return switch (type) {
            case CIVIC_CORE -> List.of("function.landmark");
            case RESIDENTIAL -> List.of("function.村庄");
            case PRODUCTION -> List.of("function.utility");
            case MARKET -> List.of("function.trade");
            case FARM_OR_PASTURE -> List.of("function.农场");
            case DEFENSE -> List.of("function.瞭望塔");
            case HARBOR_OR_WATERFRONT -> List.of("function.灯塔", "function.贸易船");
            case SACRED_OR_CULTURAL -> List.of("function.教堂");
        };
    }

    private FunctionZoneTerrainStats stats(String id, double waterContactRatio) {
        return new FunctionZoneTerrainStats(
                id,
                1000,
                "compact",
                60, 70, 65,
                0, 0, 0,
                0.1, 0.2, 0.3,
                0,
                waterContactRatio,
                List.of("plain"),
                List.of(),
                "1-2");
    }
}
