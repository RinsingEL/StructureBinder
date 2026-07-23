package com.rinsing.geomantia.systems.city;

import com.rinsing.geomantia.systems.city.application.CitySiteContextBuilder;
import com.rinsing.geomantia.systems.city.domain.config.CityPlanningConfig;
import com.rinsing.geomantia.systems.city.domain.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CitySiteContextBuilderTest {

    private final CityPlanningConfig config = CityPlanningConfig.defaults();
    private final CitySiteContextBuilder builder = new CitySiteContextBuilder(config);

    @Test
    void hamletScale_computesBoundsCorrectly() {
        BlockBounds bounds = builder.computeBounds(0, 0, 160);
        assertEquals(-160, bounds.minX());
        assertEquals(-160, bounds.minZ());
        assertEquals(160, bounds.maxX());
        assertEquals(160, bounds.maxZ());
    }

    @Test
    void villageScale_computesBoundsCorrectly() {
        int radius = 64 * 4; // planningRadiusCells * cellStep
        BlockBounds bounds = builder.computeBounds(0, 0, radius);
        assertEquals(-256, bounds.minX());
        assertEquals(256, bounds.maxX());
    }

    @Test
    void townScale_computesBoundsCorrectly() {
        int radius = 128 * 4;
        BlockBounds bounds = builder.computeBounds(100, 200, radius);
        assertEquals(100 - 512, bounds.minX());
        assertEquals(200 - 512, bounds.minZ());
        assertEquals(100 + 512, bounds.maxX());
        assertEquals(200 + 512, bounds.maxZ());
    }

    @Test
    void gridCellStep_clampedToScaleRange() {
        BlockBounds bounds = new BlockBounds(0, 0, 512, 512);
        PlanningGrid grid = builder.buildGrid(bounds, CityScale.VILLAGE, 4);
        assertEquals(16, grid.cellStepBlocks());
    }

    @Test
    void gridCellStep_hamletUsesMinStep() {
        BlockBounds bounds = new BlockBounds(0, 0, 320, 320);
        PlanningGrid grid = builder.buildGrid(bounds, CityScale.HAMLET, 4);
        assertEquals(8, grid.cellStepBlocks());
    }

    @Test
    void gridCellStep_townUsesGisCompatibleStep() {
        BlockBounds bounds = new BlockBounds(0, 0, 1024, 1024);
        PlanningGrid grid = builder.buildGrid(bounds, CityScale.TOWN, 128);

        assertEquals(16, grid.cellStepBlocks());
        assertEquals(0, 512 % grid.cellStepBlocks());
    }

    @Test
    void gridCellStep_cityUsesGisCompatibleStep() {
        BlockBounds bounds = new BlockBounds(0, 0, 2048, 2048);
        PlanningGrid grid = builder.buildGrid(bounds, CityScale.CITY, 128);

        assertEquals(32, grid.cellStepBlocks());
        assertEquals(0, 512 % grid.cellStepBlocks());
    }

    @Test
    void entryCandidates_containsAnchorAndGates() {
        BlockBounds bounds = new BlockBounds(-200, -200, 200, 200);
        List<EntryCandidate> entries = builder.buildEntryCandidates(
                new BlockPoint(0, 0), bounds, CityScale.VILLAGE);
        assertTrue(entries.stream().anyMatch(e -> e.id().equals("main_gate")));
        assertTrue(entries.stream().anyMatch(e -> e.id().equals("gate_n")));
        assertTrue(entries.stream().anyMatch(e -> e.id().equals("gate_s")));
        assertTrue(entries.stream().anyMatch(e -> e.id().equals("gate_e")));
        assertTrue(entries.stream().anyMatch(e -> e.id().equals("gate_w")));
    }

    @Test
    void entryCandidates_townHasDiagonals() {
        BlockBounds bounds = new BlockBounds(-600, -600, 600, 600);
        List<EntryCandidate> entries = builder.buildEntryCandidates(
                new BlockPoint(0, 0), bounds, CityScale.TOWN);
        assertTrue(entries.stream().anyMatch(e -> e.id().equals("gate_nw")));
        assertTrue(entries.stream().anyMatch(e -> e.id().equals("gate_ne")));
        assertTrue(entries.stream().anyMatch(e -> e.id().equals("gate_sw")));
        assertTrue(entries.stream().anyMatch(e -> e.id().equals("gate_se")));
    }

    @Test
    void entryCandidates_hamletNoDiagonals() {
        BlockBounds bounds = new BlockBounds(-200, -200, 200, 200);
        List<EntryCandidate> entries = builder.buildEntryCandidates(
                new BlockPoint(0, 0), bounds, CityScale.HAMLET);
        assertEquals(5, entries.size()); // main_gate + N/S/E/W only
    }

    @Test
    void territoryCheck_anchorInside_returnsInside() {
        // bounds fully covered by territory: cell (0,0) at cellStep=100 covers (0,0)-(99,99)
        BlockBounds bounds = new BlockBounds(20, 20, 80, 80);
        BlockPoint anchor = new BlockPoint(50, 50);
        List<CitySiteContextBuilder.TerritoryCellRef> cells = List.of(
                new CitySiteContextBuilder.TerritoryCellRef(0, 0));
        TerritoryCheckResult result = builder.checkTerritory(bounds, anchor, cells, 100);
        assertEquals(TerritoryCheckResult.INSIDE, result);
    }

    @Test
    void territoryCheck_anchorOutside_returnsOutside() {
        BlockBounds bounds = new BlockBounds(0, 0, 1000, 1000);
        BlockPoint anchor = new BlockPoint(500, 500);
        List<CitySiteContextBuilder.TerritoryCellRef> cells = List.of(
                new CitySiteContextBuilder.TerritoryCellRef(0, 0));
        TerritoryCheckResult result = builder.checkTerritory(bounds, anchor, cells, 128);
        assertEquals(TerritoryCheckResult.OUTSIDE, result);
    }

    @Test
    void territoryCheck_nullTerritory_returnsUnknown() {
        BlockBounds bounds = new BlockBounds(0, 0, 100, 100);
        BlockPoint anchor = new BlockPoint(50, 50);
        TerritoryCheckResult result = builder.checkTerritory(bounds, anchor, null, 4);
        assertEquals(TerritoryCheckResult.UNKNOWN, result);
    }

    @Test
    void territoryCheck_emptyTerritory_returnsUnknown() {
        BlockBounds bounds = new BlockBounds(0, 0, 100, 100);
        BlockPoint anchor = new BlockPoint(50, 50);
        TerritoryCheckResult result = builder.checkTerritory(bounds, anchor, List.of(), 4);
        assertEquals(TerritoryCheckResult.UNKNOWN, result);
    }

    @Test
    void build_hamlet_createsCompleteContext() {
        CitySiteContext ctx = builder.build(
                "city_h", "realm_h", "overworld",
                "seed_h", "cand_h",
                0, 0, "satellite", "hamlet",
                40, 4, null);

        assertEquals(CityScale.HAMLET, ctx.scaleClass());
        assertEquals(CitySiteContext.CURRENT_SCHEMA_VERSION, ctx.schemaVersion());
        assertEquals(5, ctx.entryCandidates().size());
        assertTrue(ctx.planningRadiusBlocks() >= 160 && ctx.planningRadiusBlocks() <= 240);
        assertEquals(TerritoryCheckResult.UNKNOWN, ctx.territoryCheckResult());
    }

    @Test
    void build_village_withNoTerritory_returnsUnknown() {
        CitySiteContext ctx = builder.build(
                "city_v", "realm_v", "overworld",
                "seed_v", "cand_v",
                8, 8, "capital", "village",
                64, 4, null);

        assertEquals(TerritoryCheckResult.UNKNOWN, ctx.territoryCheckResult());
    }

    @Test
    void build_withTerritoryContainingAnchor_returnsInside() {
        // Single cell covering the full hamlet bounds: cell(0,0) at cellStep=500 covers 0-499
        List<CitySiteContextBuilder.TerritoryCellRef> territory = List.of(
                new CitySiteContextBuilder.TerritoryCellRef(0, 0));

        CitySiteContext ctx = builder.build(
                "city_wt", "realm_wt", "overworld",
                "seed_wt", "cand_wt",
                100, 100, "capital", "hamlet",
                1, 500, territory);

        assertEquals(TerritoryCheckResult.INSIDE, ctx.territoryCheckResult());
    }

    @Test
    void config_injected_customThreshold_used() {
        CityPlanningConfig custom = CityPlanningConfig.defaults()
                .withTerritoryBorderRatio(0.01); // very strict
        CitySiteContextBuilder customBuilder = new CitySiteContextBuilder(custom);

        // Create territory that barely covers the anchor but very little of perimeter
        BlockBounds bounds = new BlockBounds(0, 0, 400, 400);
        BlockPoint anchor = new BlockPoint(200, 200);
        List<CitySiteContextBuilder.TerritoryCellRef> territory = List.of(
                new CitySiteContextBuilder.TerritoryCellRef(1, 1)); // only covers a tiny area
        TerritoryCheckResult result = customBuilder.checkTerritory(bounds, anchor, territory, 128);
        assertEquals(TerritoryCheckResult.BORDER, result);
    }

    @Test
    void unknownScaleLabel_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                builder.build("c", "r", "d", "s", "cand", 0, 0, "role", "megacity", 64, 4, null));
    }

    @Test
    void cityScale_fromContractName_nullReturnsNull() {
        assertNull(CityScale.fromContractName(null));
        assertNull(CityScale.fromContractName("nonexistent"));
        assertEquals(CityScale.HAMLET, CityScale.fromContractName("hamlet"));
        assertEquals(CityScale.VILLAGE, CityScale.fromContractName("village"));
    }

    @Test
    void planningGrid_coordinateConversion() {
        PlanningGrid grid = new PlanningGrid(0, 0, 16, 10, 10);
        assertEquals(32, grid.cellToBlockX(2));
        assertEquals(48, grid.cellToBlockZ(3));
        assertEquals(2, grid.blockToCellX(32));
        assertEquals(3, grid.blockToCellZ(48));
    }

    @Test
    void blockBounds_widthAndHeight() {
        BlockBounds b = new BlockBounds(0, 0, 99, 199);
        assertEquals(100, b.widthBlocks());
        assertEquals(200, b.heightBlocks());
    }
}
