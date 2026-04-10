package com.user.terra_script.world.city.stage.c6;

import com.user.terra_script.world.city.stage.CityC35CatalogIO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityC6StagesTest {
    @Test
    void defaultTemplateHintUsesRectAreaWhenAvailable() {
        CityC6Stages.BuildAreaSummary area = new CityC6Stages.BuildAreaSummary();
        area.area_blocks = 4993;

        CityC6Stages.RectDecision rect = new CityC6Stages.RectDecision();
        rect.w = 49;
        rect.h = 25;

        CityC6Stages.TemplateHint hint = CityC6Stages.defaultTemplateHint(area, rect);
        assertEquals("M", hint.size_tier);
    }

    @Test
    void footprintGuidanceUsesCatalogFunctionPool() {
        CityC6Stages.BuildAreaSummary area = new CityC6Stages.BuildAreaSummary();
        area.group_id = "g_market_01";
        area.function = "market";
        area.area_blocks = 1200;

        CityC35CatalogIO.CatalogStructure largeMarket = structure("test:market_large", "market", "L", 20, 14);
        CityC35CatalogIO.CatalogStructure compactMarket = structure("test:market_compact", "market", "M", 12, 10);
        CityC35CatalogIO.CatalogStructure house = structure("test:house", "residential", "S", 6, 6);

        CityC6Stages.RectGuidance guidance = CityC6Stages.deriveRectGuidance(area, java.util.List.of(largeMarket, compactMarket, house));

        assertEquals("market", guidance.function_tag);
        assertFalse(guidance.fallback_path);
        assertEquals(2, guidance.candidate_count);
        assertEquals("L", guidance.target_size_tiers.get(guidance.target_size_tiers.size() - 1));
        assertEquals("south", guidance.requires_expansion_side);
        assertTrue(guidance.connector_reserve_by_side.get("south") > 0);
        assertTrue(guidance.growth_buffer_blocks > 0);
    }

    @Test
    void growthGuidanceDiffersByFunctionAndAreaTier() {
        CityC6Stages.BuildAreaSummary market = new CityC6Stages.BuildAreaSummary();
        market.group_id = "g_market_01";
        market.function = "market";
        market.area_blocks = 3600;

        CityC6Stages.BuildAreaSummary residential = new CityC6Stages.BuildAreaSummary();
        residential.group_id = "g_residential_01";
        residential.function = "residential_core";
        residential.area_blocks = 1200;

        CityC35CatalogIO.CatalogStructure marketRoot = structure("test:market_large", "market", "M", 18, 14);
        addConnectorPool(marketRoot, "south", "market_lane");
        CityC35CatalogIO.CatalogStructure marketChild = structureWithPool("test:market_lane_piece", "market", "S", 6, 10, "market_lane");
        CityC35CatalogIO.CatalogStructure residence = structure("test:house", "residential", "S", 10, 11);

        CityC6Stages.RectGuidance marketGuidance = CityC6Stages.deriveRectGuidance(market, List.of(marketRoot, marketChild, residence));
        CityC6Stages.RectGuidance residentialGuidance = CityC6Stages.deriveRectGuidance(residential, List.of(marketRoot, marketChild, residence));

        assertEquals("south", marketGuidance.requires_expansion_side);
        assertEquals("south", residentialGuidance.requires_expansion_side);
        assertTrue(marketGuidance.growth_buffer_blocks > residentialGuidance.growth_buffer_blocks);
        assertTrue(marketGuidance.connector_reserve_by_side.get("south") > 0);
        assertTrue(!marketGuidance.design_notes.isEmpty());
    }

    @Test
    void growthGuidanceFallsBackToConnectorPoolWhenConnectToPoolsMissing() {
        CityC6Stages.BuildAreaSummary market = new CityC6Stages.BuildAreaSummary();
        market.group_id = "g_market_02";
        market.function = "market";
        market.area_blocks = 3600;

        CityC35CatalogIO.CatalogStructure marketRoot = structure("test:market_root", "market", "M", 18, 14);
        CityC35CatalogIO.ConnectorSpec connector = new CityC35CatalogIO.ConnectorSpec();
        connector.facing = "south";
        connector.pool = "market_lane";
        marketRoot.connectors.add(connector);

        CityC35CatalogIO.CatalogStructure marketChild = structureWithPool("test:market_lane_piece", "market", "S", 6, 10, "market_lane");

        CityC6Stages.RectGuidance guidance = CityC6Stages.deriveRectGuidance(market, List.of(marketRoot, marketChild));

        assertEquals(6, guidance.connector_reserve_by_side.get("south"));
    }

    private static CityC35CatalogIO.CatalogStructure structure(String id, String function, String sizeTier, int width, int height) {
        CityC35CatalogIO.CatalogStructure structure = new CityC35CatalogIO.CatalogStructure();
        structure.structure_id = id;
        structure.size_tier = sizeTier;
        structure.function_candidates.add(functionCandidate(function));
        structure.tag_source.manual_override = true;
        structure.placement.footprint.min_x = 0;
        structure.placement.footprint.min_z = 0;
        structure.placement.footprint.max_x = width - 1;
        structure.placement.footprint.max_z = height - 1;
        return structure;
    }

    private static CityC35CatalogIO.CatalogStructure structureWithPool(String id, String function, String sizeTier, int width, int height, String pool) {
        CityC35CatalogIO.CatalogStructure structure = structure(id, function, sizeTier, width, height);
        structure.preset_pool = pool;
        return structure;
    }

    private static void addConnectorPool(CityC35CatalogIO.CatalogStructure structure, String facing, String pool) {
        CityC35CatalogIO.ConnectorSpec connector = new CityC35CatalogIO.ConnectorSpec();
        connector.facing = facing;
        connector.connect_to_pools.add(pool);
        structure.connectors.add(connector);
    }

    private static CityC35CatalogIO.FunctionCandidate functionCandidate(String function) {
        CityC35CatalogIO.FunctionCandidate candidate = new CityC35CatalogIO.FunctionCandidate();
        candidate.function = function;
        candidate.score = 1.0;
        return candidate;
    }
}
