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
                List.of("patch_" + id),
                new BlockBounds(x - size / 2, z - size / 2, x + size / 2, z + size / 2),
                cells,
                size * size,
                "",
                id + "_stats",
                "",
                "");
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
