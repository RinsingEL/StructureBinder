package com.rinsing.geomantia.systems.city;

import com.rinsing.geomantia.systems.city.application.BuildableAreaMapBuilder;
import com.rinsing.geomantia.systems.city.domain.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class BuildableAreaMapBuilderTest {
    @Test
    void buildableAreaSubtractsReservedOperationCells() {
        FunctionZoneMap zoneMap = zoneMap(List.of(
                zone("res", CityFunctionType.RESIDENTIAL, -32, -32, 32, 32)));
        BuildOperationPlan plan = new BuildOperationPlan(
                BuildOperationPlan.CURRENT_SCHEMA_VERSION,
                "city_01",
                "geomantia_templates/d5",
                List.of(new BuildOperationPlan.Operation(
                        "road_res_fill",
                        "surfaceFill",
                        "main_road_01",
                        List.of(new BlockPoint(-32, 0), new BlockPoint(32, 0)),
                        16,
                        "minecraft:gravel",
                        "",
                        "",
                        BlockPoint.ORIGIN,
                        "Reserve road corridor.")));

        BuildableAreaMap map = new BuildableAreaMapBuilder().build(zoneMap, plan);
        BuildableAreaMap.ZoneBuildability zone = map.zones().get(0);

        assertTrue(map.quality().passed());
        assertTrue(zone.reservedCellCount() > 0);
        assertTrue(zone.buildableCellCount() > 0);
        assertEquals(zone.originalCellCount(), zone.reservedCellCount() + zone.buildableCellCount());
        assertTrue(zone.reservedCells().stream()
                .anyMatch(cell -> cell.reservedRefs().contains("road_res_fill")
                        && cell.reservationTypes().contains("surfaceFill")));
    }

    @Test
    void buildableAreaReservesTemplateAnchors() {
        FunctionZoneMap zoneMap = zoneMap(List.of(
                zone("core", CityFunctionType.CIVIC_CORE, -16, -16, 16, 16)));
        BuildOperationPlan plan = new BuildOperationPlan(
                BuildOperationPlan.CURRENT_SCHEMA_VERSION,
                "city_01",
                "geomantia_templates/d5",
                List.of(new BuildOperationPlan.Operation(
                        "template_gate_small",
                        "pasteTemplate",
                        "main_road_01",
                        List.of(),
                        1,
                        "",
                        "",
                        "gate_small",
                        new BlockPoint(0, 0),
                        "Reserve template footprint.")));

        BuildableAreaMap map = new BuildableAreaMapBuilder().build(zoneMap, plan);

        assertTrue(map.zones().get(0).reservedCells().stream()
                .anyMatch(cell -> cell.reservationTypes().contains("pasteTemplate")));
    }

    private FunctionZoneMap zoneMap(List<FunctionZonePatch> zones) {
        return new FunctionZoneMap(
                FunctionZoneMap.CURRENT_SCHEMA_VERSION,
                "city_01",
                new PlanningGrid(-64, -64, 16, 8, 8),
                zones,
                List.of(),
                new CityQualityReport(true, 100, List.of(), List.of(), List.of(), new com.google.gson.JsonObject()));
    }

    private FunctionZonePatch zone(String id, CityFunctionType type, int minX, int minZ, int maxX, int maxZ) {
        return new FunctionZonePatch(
                id,
                "group_" + id,
                id,
                type,
                List.of("patch_" + id),
                new BlockBounds(minX, minZ, maxX, maxZ),
                List.of(),
                (maxX - minX + 1) * (maxZ - minZ + 1),
                "",
                id + "_stats",
                "",
                "");
    }
}
