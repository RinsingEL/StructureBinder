package com.user.terra_script.world.city.stage.c7;

import com.user.terra_script.world.city.stage.CityC35CatalogIO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityC7StagesTest {
    @Test
    void rootScoringPrefersPoolAndConnectorCompatibleSeed() throws Exception {
        Object matching = newCatalogStructure();
        setField(matching, "structure_id", "test:port_start");
        setField(matching, "piece_role", "START");
        setField(matching, "size_tier", "M");
        setField(matching, "preset_pool", "port/main");
        setField(matching, "path", "port/dock/start");
        addFunctionCandidate(matching, "port", 0.85);
        addConnector(matching, "c0", 0, 0, "east");

        Object mismatched = newCatalogStructure();
        setField(mismatched, "structure_id", "test:generic_house");
        setField(mismatched, "piece_role", "START");
        setField(mismatched, "size_tier", "M");
        setField(mismatched, "preset_pool", "residential/base");
        setField(mismatched, "path", "village/house");
        addFunctionCandidate(mismatched, "port", 0.95);
        addConnector(mismatched, "c0", 0, 0, "north");

        Method score = CityC7Stages.class.getDeclaredMethod(
                "scoreStructure",
                matching.getClass(),
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                boolean.class
        );
        score.setAccessible(true);

        double matchingScore = (double) score.invoke(null, matching, "M", "port", "port_group", "port", "east", true);
        double mismatchedScore = (double) score.invoke(null, mismatched, "M", "port", "port_group", "port", "east", true);

        assertTrue(matchingScore > mismatchedScore, "seed scoring should prefer pool+connector compatible root");
    }

    @Test
    void strictSelectionCapturesFailureReasonWhenNoTrustedCandidate() throws Exception {
        Object heuristicOnly = newCatalogStructure();
        setField(heuristicOnly, "structure_id", "test:landmark");
        setField(heuristicOnly, "piece_role", "START");
        setField(heuristicOnly, "size_tier", "S");
        setField(heuristicOnly, "preset_pool", "landmark_pool");
        setField(heuristicOnly, "path", "landmark/test");
        addFunctionCandidate(heuristicOnly, "landmark", 0.8);
        Object functionDefinition = getField(heuristicOnly, "function_definition");
        setField(functionDefinition, "source", "mcp_path_heuristic");
        Object tagSource = getField(heuristicOnly, "tag_source");
        setField(tagSource, "preset_rule", "mcp_path_heuristic");

        Object catalog = newCatalog();
        @SuppressWarnings("unchecked")
        List<Object> structures = (List<Object>) getField(catalog, "structures");
        structures.add(heuristicOnly);

        Method chooseCandidates = CityC7Stages.class.getDeclaredMethod(
                "chooseCandidatesFromCatalog",
                Class.forName("com.user.terra_script.world.city.stage.StructureTemplateQueryService$QueryRequest"),
                String.class,
                String.class,
                boolean.class,
                catalog.getClass()
        );
        chooseCandidates.setAccessible(true);

        Class<?> queryType = Class.forName("com.user.terra_script.world.city.stage.StructureTemplateQueryService$QueryRequest");
        Object query = queryType.getDeclaredConstructor().newInstance();
        queryType.getField("function_tag").set(query, "market");
        queryType.getField("size_tier").set(query, "S");
        queryType.getField("arrangement_type").set(query, "COURTYARD");
        queryType.getField("strict_tag_source").set(query, true);

        Object result = chooseCandidates.invoke(null, query, "g_market_04", "south", true, catalog);
        boolean ok = (boolean) result.getClass().getField("ok").get(result);
        int candidateCount = result.getClass().getField("candidate_count").getInt(result);
        String reason = (String) result.getClass().getField("failure_reason").get(result);

        assertFalse(ok);
        assertEquals(0, candidateCount);
        assertEquals("no_candidates_after_strict_function_filter", reason);
    }

    @Test
    void nonStrictSelectionAllowsHeuristicCandidate() throws Exception {
        Object heuristicOnly = newCatalogStructure();
        setField(heuristicOnly, "structure_id", "test:market_guess");
        setField(heuristicOnly, "piece_role", "START");
        setField(heuristicOnly, "size_tier", "S");
        setField(heuristicOnly, "preset_pool", "village_market_small");
        setField(heuristicOnly, "path", "market/test");
        addFunctionCandidate(heuristicOnly, "market", 0.8);
        addConnector(heuristicOnly, "c0", 0, 0, "south");
        Object functionDefinition = getField(heuristicOnly, "function_definition");
        setField(functionDefinition, "source", "mcp_path_heuristic");
        Object tagSource = getField(heuristicOnly, "tag_source");
        setField(tagSource, "preset_rule", "mcp_path_heuristic");

        Object catalog = newCatalog();
        @SuppressWarnings("unchecked")
        List<Object> structures = (List<Object>) getField(catalog, "structures");
        structures.add(heuristicOnly);

        Method chooseCandidates = CityC7Stages.class.getDeclaredMethod(
                "chooseCandidatesFromCatalog",
                Class.forName("com.user.terra_script.world.city.stage.StructureTemplateQueryService$QueryRequest"),
                String.class,
                String.class,
                boolean.class,
                catalog.getClass()
        );
        chooseCandidates.setAccessible(true);

        Class<?> queryType = Class.forName("com.user.terra_script.world.city.stage.StructureTemplateQueryService$QueryRequest");
        Object query = queryType.getDeclaredConstructor().newInstance();
        queryType.getField("function_tag").set(query, "market");
        queryType.getField("size_tier").set(query, "S");
        queryType.getField("arrangement_type").set(query, "COURTYARD");
        queryType.getField("strict_tag_source").set(query, false);

        Object result = chooseCandidates.invoke(null, query, "g_market_04", "south", true, catalog);
        boolean ok = (boolean) result.getClass().getField("ok").get(result);
        int candidateCount = result.getClass().getField("candidate_count").getInt(result);

        assertTrue(ok);
        assertEquals(1, candidateCount);
    }

    @Test
    void seedConnectorResolutionDoesNotFallbackToJigsawFacing() throws Exception {
        Object legacyOnly = newCatalogStructure();
        setField(legacyOnly, "structure_id", "test:legacy_only");
        setField(legacyOnly, "piece_role", "START");
        setField(legacyOnly, "size_tier", "M");
        setField(legacyOnly, "path", "port/legacy_only");
        Object orientation = getField(legacyOnly, "orientation");
        @SuppressWarnings("unchecked")
        List<String> jigsawFacing = (List<String>) getField(orientation, "jigsaw_facing");
        jigsawFacing.add("east");

        Method resolver = CityC7Stages.class.getDeclaredMethod("resolveSeedConnector", legacyOnly.getClass(), String.class);
        resolver.setAccessible(true);
        Object result = resolver.invoke(null, legacyOnly, "east");

        assertNull(result);
    }

    @Test
    void rootScoringAllowsConnectorlessSingleAsTerminalSeed() throws Exception {
        Object single = newCatalogStructure();
        setField(single, "structure_id", "test:single_market");
        setField(single, "piece_role", "SINGLE");
        setField(single, "size_tier", "M");
        setField(single, "preset_pool", "market_pool");
        setField(single, "path", "market/single");
        addFunctionCandidate(single, "market", 0.9);

        Method score = CityC7Stages.class.getDeclaredMethod(
                "scoreStructure",
                single.getClass(),
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                boolean.class
        );
        score.setAccessible(true);

        double value = (double) score.invoke(null, single, "M", "market", "g_market_04", "market_pool", "south", true);
        assertTrue(value > 0.0);
    }

    private static Object newCatalog() throws Exception {
        Class<?> type = Class.forName("com.user.terra_script.world.city.stage.c7.CityC7Stages$Catalog");
        Constructor<?> ctor = type.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object catalog = ctor.newInstance();
        setField(catalog, "ok", true);
        return catalog;
    }

    private static Object newCatalogStructure() throws Exception {
        Class<?> type = Class.forName("com.user.terra_script.world.city.stage.c7.CityC7Stages$CatalogStructure");
        Constructor<?> ctor = type.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    @SuppressWarnings("unchecked")
    private static void addFunctionCandidate(Object structure, String function, double score) throws Exception {
        CityC35CatalogIO.FunctionCandidate candidate = new CityC35CatalogIO.FunctionCandidate();
        candidate.function = function;
        candidate.score = score;
        ((List<CityC35CatalogIO.FunctionCandidate>) getField(structure, "function_candidates")).add(candidate);
    }

    @SuppressWarnings("unchecked")
    private static void addConnector(Object structure, String id, int x, int z, String facing) throws Exception {
        CityC35CatalogIO.ConnectorSpec connector = new CityC35CatalogIO.ConnectorSpec();
        connector.id = id;
        connector.facing = facing;
        connector.local_pos.x = x;
        connector.local_pos.z = z;
        ((List<CityC35CatalogIO.ConnectorSpec>) getField(structure, "connectors")).add(connector);
    }

    private static Object getField(Object target, String fieldName) throws Exception {
        return target.getClass().getField(fieldName).get(target);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        target.getClass().getField(fieldName).set(target, value);
    }
}
