package com.user.terra_script.world.city.stage.c8;

import com.user.terra_script.world.city.stage.CityC35CatalogIO;
import com.user.terra_script.world.city.stage.c1.CityStage1BinaryIO;
import com.user.terra_script.world.city.stage.c6.CityC6Stages;
import com.user.terra_script.world.city.stage.c7.CityC7Stages;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityC8StagesTest {
    @Test
    void generateMarksExplicitFallbackWhenRuntimeLevelIsUnavailable() {
        CityC6Stages.C6Summary summary = new CityC6Stages.C6Summary();
        summary.city_id = "city_demo";
        summary.areas.add(buildArea());

        CityC6Stages.C6Layout layout = new CityC6Stages.C6Layout();
        layout.city_id = "city_demo";
        layout.plans.add(buildLayoutPlan());

        CityC7Stages.C7Selection selection = new CityC7Stages.C7Selection();
        selection.city_id = "city_demo";
        selection.arrangements.add(buildArrangement());
        selection.selections.add(buildSelection());
        selection.foreman_plans.add(buildForemanPlan());

        CityStage1BinaryIO.HeightData heightData = new CityStage1BinaryIO.HeightData(0, 0, 8, 8, flatHeight(8, 8, 64));
        Map<Long, Integer> index = squareIndex(0, 0, 6, 6, 1);

        CityC8Stages.C8Plan plan = CityC8Stages.generate(
                "city_demo",
                summary,
                layout,
                selection,
                null,
                heightData,
                null,
                index
        );

        assertTrue(plan.ok);
        assertEquals(1, plan.foundations.size());
        CityC8Stages.FoundationItem foundation = plan.foundations.get(0);
        assertNotNull(foundation.active_node);
        assertEquals("horizontal_runtime", foundation.active_node.start_solver_kind);
        assertEquals(Boolean.TRUE, foundation.active_node.runtime_fallback_used);
        assertNotNull(foundation.active_node.runtime_fallback_reason);
        assertTrue(foundation.active_node.runtime_fallback_reason.contains("显式回退"));
        assertEquals("test:root", foundation.active_node.selected_template_id);
        assertEquals(10, foundation.active_node.x);
        assertEquals(12, foundation.active_node.z);
        assertEquals(90, foundation.active_node.selected_rotation);
        assertEquals(90, foundation.active_node.chosen_start_rotation);
        assertFalse(foundation.active_node.chosen_start_reason_zh == null || foundation.active_node.chosen_start_reason_zh.isBlank());
        assertTrue(foundation.active_node.start_runtime_candidates.isEmpty());
        assertTrue(foundation.arrangement_warnings.stream().anyMatch(text -> text != null && text.contains("显式回退")));
    }

    @Test
    void persistSolvedPlacementReplacesPlaceholderAndBuildsRealNextNode() {
        CityC8Stages.FoundationItem foundation = new CityC8Stages.FoundationItem();
        foundation.base_y = 65;

        CityC8Stages.NodeTask start = new CityC8Stages.NodeTask();
        start.node_id = "start_g_market_03";
        start.phase_key = "phase1";
        start.phase_name = "先把入口与前场立住";
        start.status = "VALIDATED";
        foundation.validated_nodes.add(start);

        CityC8Stages.PlacementNode startPlacement = new CityC8Stages.PlacementNode();
        startPlacement.node_id = "start_g_market_03";
        startPlacement.template_id = "test:start";
        startPlacement.build_order = 0;
        foundation.placements.add(startPlacement);

        CityC8Stages.NodeTask placeholder1 = placeholder("start_g_market_03_child_1", "start_g_market_03", "parent_conn_main");
        placeholder1.template_pool_id = "pool_market";
        placeholder1.phase_key = "phase2";
        placeholder1.phase_name = "再推进主体主线";
        placeholder1.target_role = "进入下一阶段的衔接节点";
        placeholder1.target_structure_kind = "延伸节点";
        placeholder1.candidate_template_ids.add("test:legacy");
        placeholder1.allowed_connector_dirs.add("south");
        CityC8Stages.NodeTask placeholder2 = placeholder("start_g_market_03_child_2", "start_g_market_03", "parent_conn_side");
        placeholder2.phase_key = "phase2";
        placeholder2.phase_name = "再推进主体主线";
        foundation.node_queue.add(placeholder1);
        foundation.node_queue.add(placeholder2);
        foundation.active_node = placeholder1;

        CityC8Stages.FailedAttempt failedAttempt = new CityC8Stages.FailedAttempt();
        failedAttempt.node_id = "start_g_market_03_child_1";
        foundation.failed_attempts.add(failedAttempt);

        CityC8Stages.PlacementNode solvedPlacement = new CityC8Stages.PlacementNode();
        solvedPlacement.node_id = "vjigsaw_start_g_market_03_child";
        solvedPlacement.parent_node_id = "start_g_market_03";
        solvedPlacement.template_id = "test:child";
        solvedPlacement.x = 10;
        solvedPlacement.y = 64;
        solvedPlacement.z = 20;
        solvedPlacement.rotation = 270;
        solvedPlacement.build_order = 1;
        solvedPlacement.incoming_connector_dir = "east";
        solvedPlacement.incoming_pool_refs.add("pool_runtime_parent");
        solvedPlacement.outgoing_connector_ids.add("child_out_main");
        solvedPlacement.outgoing_connector_ids.add("child_out_empty");
        CityC8Stages.PlacementConnectorRef mainRef = new CityC8Stages.PlacementConnectorRef();
        mainRef.connector_id = "child_out_main";
        mainRef.front = "east";
        mainRef.pool_refs.add("pool_lane");
        CityC8Stages.PlacementConnectorRef emptyRef = new CityC8Stages.PlacementConnectorRef();
        emptyRef.connector_id = "child_out_empty";
        emptyRef.front = "west";
        emptyRef.pool_refs.add("pool_missing");
        solvedPlacement.outgoing_connectors.add(mainRef);
        solvedPlacement.outgoing_connectors.add(emptyRef);

        CityC8Stages.CatalogContext catalog = new CityC8Stages.CatalogContext();
        CityC35CatalogIO.CatalogStructure childMeta = structure("test:child", "pool_child");
        childMeta.connectors.add(connector("child_out_main", "east", "pool_lane"));
        childMeta.connectors.add(connector("child_out_empty", "west", "pool_missing"));
        catalog.byId.put("test:child", childMeta);

        CityC35CatalogIO.CatalogStructure laneA = structure("test:lane_a", "pool_lane");
        laneA.connectors.add(connector("lane_a_north", "north", "pool_next"));
        laneA.connectors.add(connector("lane_a_east", "east", "pool_next"));
        CityC35CatalogIO.CatalogStructure laneB = structure("test:lane_b", "pool_lane");
        laneB.connectors.add(connector("lane_b_south", "south", "pool_next"));
        catalog.structuresByPool.put("pool_runtime_parent", List.of(childMeta));
        catalog.byId.put("test:lane_a", laneA);
        catalog.byId.put("test:lane_b", laneB);
        catalog.structuresByPool.put("pool_lane", List.of(laneA, laneB));

        CityC8Stages.NodeSubmitResult result = CityC8Stages.persistSolvedPlacement(
                foundation,
                startPlacement,
                "parent_conn_main",
                "test:child",
                "east",
                solvedPlacement,
                catalog
        );

        assertTrue(result.ok);
        assertEquals(2, foundation.validated_nodes.size());
        assertEquals("vjigsaw_start_g_market_03_child", foundation.validated_nodes.get(1).node_id);
        assertEquals("pool_runtime_parent", foundation.validated_nodes.get(1).template_pool_id);
        assertEquals(0, foundation.failed_attempts.size());
        assertTrue(foundation.node_queue.stream().noneMatch(node -> node.node_id.startsWith("start_g_market_03_child_")));
        assertNotNull(foundation.active_node);
        assertEquals("vjigsaw_start_g_market_03_child", foundation.active_node.parent_node_id);
        assertEquals("child_out_main", foundation.active_node.incoming_connector_id);
        assertIterableEquals(List.of("test:lane_a", "test:lane_b"), foundation.active_node.candidate_template_ids);
        assertEquals("east", foundation.active_node.allowed_connector_dirs.get(0));
        assertTrue(foundation.active_node.allowed_connector_dirs.containsAll(List.of("east", "north", "south")));
        assertEquals(2, foundation.queue_summary.validated_count);
        assertEquals(1, foundation.queue_summary.ready_for_ai_count);
    }

    @Test
    void submitNodeDecisionReusesValidatedNodeWithoutDuplicateEntries() {
        CityC8Stages.FoundationItem foundation = new CityC8Stages.FoundationItem();
        foundation.base_y = 64;
        foundation.delta_height = 4;

        CityC8Stages.NodeTask start = new CityC8Stages.NodeTask();
        start.node_id = "start_g_market_03";
        start.status = "VALIDATED";
        start.selected_template_id = "test:start";
        start.selected_connector_dir = "east";
        start.selected_rotation = 180;
        start.x = 10;
        start.y = 64;
        start.z = 12;
        start.candidate_template_ids.add("test:start");
        start.allowed_connector_dirs.add("east");
        foundation.validated_nodes.add(start);

        CityC8Stages.PlacementNode placement = new CityC8Stages.PlacementNode();
        placement.node_id = "start_g_market_03";
        placement.template_id = "test:start";
        placement.x = 10;
        placement.y = 64;
        placement.z = 12;
        placement.rotation = 180;
        placement.footprint_min_x = 10;
        placement.footprint_min_z = 12;
        placement.footprint_max_x = 10;
        placement.footprint_max_z = 12;
        foundation.placements.add(placement);

        CityC8Stages.NodeDecision decision = new CityC8Stages.NodeDecision();
        decision.node_id = "start_g_market_03";
        decision.selected_template_id = "test:start";
        decision.selected_connector_dir = "east";
        decision.selected_rotation = 180;

        CityStage1BinaryIO.HeightData heightData = new CityStage1BinaryIO.HeightData(0, 0, 32, 32, flatHeight(32, 32, 64));
        CityC8Stages.NodeSubmitResult result = CityC8Stages.submitNodeDecision(
                foundation,
                squareGeometry(0, 0, 31, 31),
                decision,
                heightData,
                null
        );

        assertTrue(result.ok);
        assertEquals(1, foundation.validated_nodes.size());
        assertEquals(1, foundation.placements.size());
        assertEquals("start_g_market_03", foundation.validated_nodes.get(0).node_id);
        assertEquals("start_g_market_03", foundation.placements.get(0).node_id);
    }

    @Test
    void persistSolvedPlacementPrefersRuntimePoolAndLogsMismatch() {
        CityC8Stages.FoundationItem foundation = new CityC8Stages.FoundationItem();
        foundation.base_y = 65;

        CityC8Stages.NodeTask start = new CityC8Stages.NodeTask();
        start.node_id = "start_g_market_03";
        start.phase_key = "phase1";
        start.phase_name = "先把入口与前场立住";
        start.status = "VALIDATED";
        foundation.validated_nodes.add(start);

        CityC8Stages.PlacementNode startPlacement = new CityC8Stages.PlacementNode();
        startPlacement.node_id = "start_g_market_03";
        startPlacement.template_id = "test:start";
        startPlacement.build_order = 0;
        foundation.placements.add(startPlacement);

        CityC8Stages.PlacementNode solvedPlacement = new CityC8Stages.PlacementNode();
        solvedPlacement.node_id = "vjigsaw_start_g_market_03_child";
        solvedPlacement.parent_node_id = "start_g_market_03";
        solvedPlacement.template_id = "test:child";
        solvedPlacement.x = 10;
        solvedPlacement.y = 64;
        solvedPlacement.z = 20;
        solvedPlacement.rotation = 270;
        solvedPlacement.build_order = 1;
        solvedPlacement.incoming_connector_dir = "east";
        solvedPlacement.incoming_pool_refs.add("pool_runtime_parent");
        CityC8Stages.PlacementConnectorRef mainRef = new CityC8Stages.PlacementConnectorRef();
        mainRef.connector_id = "child_out_main";
        mainRef.front = "east";
        mainRef.pool_refs.add("pool_runtime");
        mainRef.catalog_pool_refs.add("pool_catalog");
        mainRef.pool_truth_source = "runtime";
        mainRef.pool_mismatch = true;
        mainRef.mismatch_detail = "连接器 `child_out_main` 的 runtime pool refs [pool_runtime] 与 catalog pool refs [pool_catalog] 不一致，当前继续采用 runtime。";
        solvedPlacement.outgoing_connector_ids.add("child_out_main");
        solvedPlacement.outgoing_connectors.add(mainRef);

        CityC8Stages.CatalogContext catalog = new CityC8Stages.CatalogContext();
        CityC35CatalogIO.CatalogStructure childMeta = structure("test:child", "pool_catalog");
        childMeta.connectors.add(connector("child_out_main", "east", "pool_catalog"));
        catalog.byId.put("test:child", childMeta);
        CityC35CatalogIO.CatalogStructure runtimeLane = structure("test:runtime_lane", "pool_runtime");
        runtimeLane.connectors.add(connector("runtime_lane_north", "north", "pool_next"));
        catalog.byId.put("test:runtime_lane", runtimeLane);
        CityC35CatalogIO.CatalogStructure catalogLane = structure("test:catalog_lane", "pool_catalog");
        catalog.byId.put("test:catalog_lane", catalogLane);
        catalog.structuresByPool.put("pool_runtime_parent", List.of(childMeta));
        catalog.structuresByPool.put("pool_runtime", List.of(runtimeLane));
        catalog.structuresByPool.put("pool_catalog", List.of(catalogLane));

        CityC8Stages.NodeSubmitResult result = CityC8Stages.persistSolvedPlacement(
                foundation,
                startPlacement,
                "parent_conn_main",
                "test:child",
                "east",
                solvedPlacement,
                catalog
        );

        assertTrue(result.ok);
        assertEquals("pool_runtime", foundation.active_node.template_pool_id);
        assertIterableEquals(List.of("test:runtime_lane"), foundation.active_node.candidate_template_ids);
        assertTrue(foundation.node_debug.stream().anyMatch(record -> record != null && "pool_mismatch".equals(record.stage)));
    }

    @Test
    void normalizeRuntimeStartNodeAdjustsStartHeightFromConnectorLocalY() {
        CityC8Stages.FoundationItem foundation = new CityC8Stages.FoundationItem();
        foundation.base_y = 65;

        CityC8Stages.NodeTask start = new CityC8Stages.NodeTask();
        start.node_id = "start_g_market_03";
        start.selected_template_id = "minecraft:village/plains/houses/plains_butcher_shop_1";
        start.selected_rotation = 180;
        start.x = 3119;
        start.y = 65;
        start.z = -3239;
        start.chosen_start_connector_id = "jigsaw_east_0_1_7";
        start.runtime_fallback_used = false;
        start.placement = new CityC8Stages.PlacementNode();
        start.placement.node_id = start.node_id;
        start.placement.y = 65;
        foundation.validated_nodes.add(start);
        foundation.placements.add(start.placement);

        boolean changed = CityC8Stages.normalizeRuntimeStartNode(foundation, start, 1);

        assertTrue(changed);
        assertEquals(64, start.y);
        assertEquals(64, start.placement.y);
        assertEquals(64, foundation.placements.get(0).y);
    }

    @Test
    void normalizeRuntimeStartNodeSkipsRuntimeFallbackStart() {
        CityC8Stages.FoundationItem foundation = new CityC8Stages.FoundationItem();
        foundation.base_y = 65;

        CityC8Stages.NodeTask start = new CityC8Stages.NodeTask();
        start.node_id = "start_g_market_03";
        start.selected_template_id = "minecraft:village/plains/houses/plains_butcher_shop_1";
        start.selected_rotation = 180;
        start.x = 3119;
        start.y = 65;
        start.z = -3239;
        start.chosen_start_connector_id = "jigsaw_east_0_1_7";
        start.runtime_fallback_used = true;

        boolean changed = CityC8Stages.normalizeRuntimeStartNode(foundation, start, 1);

        assertFalse(changed);
        assertEquals(65, start.y);
    }

    private static CityC6Stages.BuildAreaSummary buildArea() {
        CityC6Stages.BuildAreaSummary area = new CityC6Stages.BuildAreaSummary();
        area.build_area_id = "ba_1";
        area.build_area_numeric_id = 1;
        area.group_id = "g_1";
        area.centroid.x = 11;
        area.centroid.z = 11;
        area.avg_height = 64;
        area.bbox.minX = 8;
        area.bbox.minZ = 8;
        area.bbox.maxX = 14;
        area.bbox.maxZ = 14;
        return area;
    }

    private static CityC6Stages.LayoutPlan buildLayoutPlan() {
        CityC6Stages.LayoutPlan plan = new CityC6Stages.LayoutPlan();
        plan.group_id = "g_1";
        plan.build_area_id = "ba_1";
        plan.validated = true;

        CityC6Stages.PrimaryModule module = new CityC6Stages.PrimaryModule();
        module.module_id = "module_1";
        module.anchor.x = 11;
        module.anchor.z = 11;
        plan.primary_modules.add(module);
        return plan;
    }

    private static CityC7Stages.GroupArrangementDecision buildArrangement() {
        CityC7Stages.GroupArrangementDecision arrangement = new CityC7Stages.GroupArrangementDecision();
        arrangement.group_id = "g_1";
        arrangement.build_area_id = "ba_1";
        arrangement.arrangement_type = "COURTYARD";
        arrangement.seed.start_template_id = "test:root";
        arrangement.seed.start_x = 10;
        arrangement.seed.start_z = 12;
        arrangement.seed.start_rotation = 90;
        arrangement.seed.start_connector_dir = "north";
        arrangement.arrangement_params.put("spacing", 10);
        return arrangement;
    }

    private static CityC7Stages.TemplateSelectionItem buildSelection() {
        CityC7Stages.TemplateSelectionItem item = new CityC7Stages.TemplateSelectionItem();
        item.group_id = "g_1";
        item.build_area_id = "ba_1";
        item.selected_template = "test:root";
        item.top_k_templates = List.of("test:root", "test:child");
        item.fallback_chain = List.of("test:fallback");
        return item;
    }

    private static CityC7Stages.ForemanPlan buildForemanPlan() {
        CityC7Stages.ForemanPlan plan = new CityC7Stages.ForemanPlan();
        plan.group_id = "g_1";
        plan.build_area_id = "ba_1";
        plan.start_node.node_id = "start_g_1";
        plan.start_node.allowed_connector_dirs = List.of("north", "up");
        return plan;
    }

    private static int[][] flatHeight(int width, int height, int value) {
        int[][] map = new int[width][height];
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < height; z++) {
                map[x][z] = value;
            }
        }
        return map;
    }

    private static Map<Long, Integer> squareIndex(int minX, int minZ, int maxX, int maxZ, int areaId) {
        Map<Long, Integer> index = new LinkedHashMap<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                index.put(packBlock(x, z), areaId);
            }
        }
        return index;
    }

    private static CityC8Stages.AreaGeometry squareGeometry(int minX, int minZ, int maxX, int maxZ) {
        CityC8Stages.AreaGeometry geometry = new CityC8Stages.AreaGeometry();
        geometry.valid = true;
        geometry.min_x = minX;
        geometry.min_z = minZ;
        geometry.max_x = maxX;
        geometry.max_z = maxZ;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                long key = packBlock(x, z);
                geometry.block_keys.add(key);
                geometry.block_set.add(key);
            }
        }
        return geometry;
    }

    private static long packBlock(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private static CityC8Stages.NodeTask placeholder(String nodeId, String parentNodeId, String incomingConnectorId) {
        CityC8Stages.NodeTask node = new CityC8Stages.NodeTask();
        node.node_id = nodeId;
        node.parent_node_id = parentNodeId;
        node.incoming_connector_id = incomingConnectorId;
        node.status = "READY_FOR_AI";
        return node;
    }

    private static CityC35CatalogIO.CatalogStructure structure(String structureId, String presetPool) {
        CityC35CatalogIO.CatalogStructure structure = new CityC35CatalogIO.CatalogStructure();
        structure.structure_id = structureId;
        structure.preset_pool = presetPool;
        return structure;
    }

    private static CityC35CatalogIO.ConnectorSpec connector(String id, String facing, String pool) {
        CityC35CatalogIO.ConnectorSpec connector = new CityC35CatalogIO.ConnectorSpec();
        connector.id = id;
        connector.facing = facing;
        connector.pool = pool;
        return connector;
    }
}
