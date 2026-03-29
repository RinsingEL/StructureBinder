package com.user.terra_script.world.city.stage.c9;

import com.user.terra_script.world.city.stage.c6.CityC6Stages;
import com.user.terra_script.world.city.stage.c8.CityC8Stages;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CityC9StagesTest {
    @Test
    void dryRunDoesNotEnqueueTasks() {
        CityC9Stages.C9Result result = CityC9Stages.generate(
                "city_demo",
                summary(),
                index(),
                validPlan(),
                CityC9Stages.Mode.DRY_RUN,
                100,
                null
        );

        assertNotNull(result.placement);
        assertEquals("dry_run", result.placement.mode);
        assertEquals(0, result.placement.enqueued_tasks_count);
    }

    @Test
    void enqueueBuildsQueueTasks() {
        CityC9Stages.C9Result result = CityC9Stages.generate(
                "city_demo",
                summary(),
                index(),
                validPlan(),
                CityC9Stages.Mode.ENQUEUE,
                100,
                null
        );

        assertNotNull(result.queue);
        assertEquals(1, result.queue.tasks.size());
        assertEquals(1, result.placement.enqueued_tasks_count);
    }

    @Test
    void enqueueSkipsPlacementsOutsideAreaBlocks() {
        CityC9Stages.C9Result result = CityC9Stages.generate(
                "city_demo",
                summary(),
                index(),
                invalidPlan(),
                CityC9Stages.Mode.ENQUEUE,
                100,
                null
        );

        assertNotNull(result.queue);
        assertEquals(0, result.queue.tasks.size());
        assertEquals(0, result.placement.enqueued_tasks_count);
        assertEquals("runtime_out_of_area", result.placement.items.get(0).structures.get(0).reason);
    }

    private static CityC6Stages.C6Summary summary() {
        CityC6Stages.C6Summary summary = new CityC6Stages.C6Summary();
        CityC6Stages.BuildAreaSummary area = new CityC6Stages.BuildAreaSummary();
        area.build_area_id = "ba_1";
        area.build_area_numeric_id = 1;
        area.group_id = "g_market_04";
        summary.areas.add(area);
        return summary;
    }

    private static Map<Long, Integer> index() {
        Map<Long, Integer> index = new HashMap<>();
        index.put(packBlock(32, 48), 1);
        index.put(packBlock(33, 48), 1);
        index.put(packBlock(32, 49), 1);
        index.put(packBlock(33, 49), 1);
        return index;
    }

    private static CityC8Stages.C8Plan validPlan() {
        CityC8Stages.C8Plan plan = new CityC8Stages.C8Plan();
        CityC8Stages.FoundationItem foundation = new CityC8Stages.FoundationItem();
        foundation.group_id = "g_market_04";
        foundation.build_area_id = "ba_1";
        foundation.build_area_numeric_id = 1;
        foundation.foundation_type = "PLATFORM";
        foundation.base_y = 64;

        CityC8Stages.PlacementNode node = new CityC8Stages.PlacementNode();
        node.node_id = "p1";
        node.template_id = "test:template";
        node.x = 32;
        node.y = 64;
        node.z = 48;
        node.footprint_min_x = 32;
        node.footprint_min_z = 48;
        node.footprint_max_x = 33;
        node.footprint_max_z = 49;
        foundation.placements.add(node);

        plan.foundations.add(foundation);
        return plan;
    }

    private static CityC8Stages.C8Plan invalidPlan() {
        CityC8Stages.C8Plan plan = validPlan();
        CityC8Stages.PlacementNode node = plan.foundations.get(0).placements.get(0);
        node.footprint_max_x = 34;
        return plan;
    }

    private static long packBlock(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }
}
