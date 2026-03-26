package com.user.terra_script.world.city.stage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureTemplateQueryServiceTest {
    @Test
    void marketQueryRejectsWeakLandmarkTemplate() {
        CityC35CatalogIO.CatalogStructure structure = structure("trek:overworld/rare/portal_sword/1", "SINGLE", "S");
        structure.preset_pool = "landmark_pool";
        structure.function_definition.source = "mcp_path_heuristic";
        structure.tag_source.preset_rule = "mcp_path_heuristic";
        addFunction(structure, "landmark", 0.4);

        StructureTemplateQueryService.QueryRequest request = new StructureTemplateQueryService.QueryRequest();
        request.function_tag = "market";
        request.strict_tag_source = true;

        StructureTemplateQueryService.QueryResult result = StructureTemplateQueryService.queryTemplates(List.of(structure), request);
        assertFalse(result.ok);
        assertEquals(0, result.candidate_count);
    }

    @Test
    void marketQueryAcceptsTrustedCommercialTemplate() {
        CityC35CatalogIO.CatalogStructure structure = structure("test:market_stall", "START", "S");
        structure.preset_pool = "market_pool";
        structure.function_definition.source = "preset_rule";
        structure.tag_source.preset_rule = "manual";
        addFunction(structure, "commercial", 0.9);

        StructureTemplateQueryService.QueryRequest request = new StructureTemplateQueryService.QueryRequest();
        request.function_tag = "market";
        request.strict_tag_source = true;

        StructureTemplateQueryService.QueryResult result = StructureTemplateQueryService.queryTemplates(List.of(structure), request);
        assertTrue(result.ok);
        assertEquals(1, result.candidate_count);
        assertEquals("test:market_stall", result.candidates.get(0).structure_id);
    }

    @Test
    void connectorRequirementFiltersOutTemplatesWithoutConnectors() {
        CityC35CatalogIO.CatalogStructure structure = structure("test:port_piece", "START", "M");
        structure.function_definition.source = "preset_rule";
        structure.tag_source.preset_rule = "manual";
        addFunction(structure, "port", 0.9);

        StructureTemplateQueryService.QueryRequest request = new StructureTemplateQueryService.QueryRequest();
        request.function_tag = "port";
        request.require_connector = true;
        request.strict_tag_source = true;

        StructureTemplateQueryService.QueryResult result = StructureTemplateQueryService.queryTemplates(List.of(structure), request);
        assertFalse(result.ok);
        assertEquals(0, result.candidate_count);
    }

    @Test
    void connectorRequirementIgnoresConnectorDirsWithoutRealConnectors() {
        CityC35CatalogIO.CatalogStructure structure = structure("test:legacy_port_piece", "START", "M");
        structure.function_definition.source = "preset_rule";
        structure.tag_source.preset_rule = "manual";
        structure.connector_dirs.add("east");
        addFunction(structure, "port", 0.9);

        StructureTemplateQueryService.QueryRequest request = new StructureTemplateQueryService.QueryRequest();
        request.function_tag = "port";
        request.require_connector = true;
        request.strict_tag_source = true;

        StructureTemplateQueryService.QueryResult result = StructureTemplateQueryService.queryTemplates(List.of(structure), request);
        assertFalse(result.ok);
        assertEquals(0, result.candidate_count);
    }

    private static CityC35CatalogIO.CatalogStructure structure(String id, String pieceRole, String sizeTier) {
        CityC35CatalogIO.CatalogStructure structure = new CityC35CatalogIO.CatalogStructure();
        structure.structure_id = id;
        structure.piece_role = pieceRole;
        structure.size_tier = sizeTier;
        structure.path = id;
        return structure;
    }

    private static void addFunction(CityC35CatalogIO.CatalogStructure structure, String function, double score) {
        CityC35CatalogIO.FunctionCandidate candidate = new CityC35CatalogIO.FunctionCandidate();
        candidate.function = function;
        candidate.score = score;
        structure.function_candidates.add(candidate);
    }
}
