package com.user.terra_script.world.city.stage.c8;

import com.user.terra_script.world.city.stage.c1.CityStage1BinaryIO;
import com.user.terra_script.world.city.stage.c6.CityC6Stages;
import com.user.terra_script.world.city.stage.c7.CityC7Stages;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static long packBlock(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }
}
