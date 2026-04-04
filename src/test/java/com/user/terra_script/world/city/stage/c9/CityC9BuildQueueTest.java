package com.user.terra_script.world.city.stage.c9;

import com.user.terra_script.world.city.stage.c8.CityC8Stages;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CityC9BuildQueueTest {
    @Test
    void upsertFromPlanDoesNotDuplicateTasks() {
        CityC9BuildQueue.BuildQueue queue = new CityC9BuildQueue.BuildQueue();
        CityC8Stages.C8Plan plan = new CityC8Stages.C8Plan();
        plan.foundations.add(foundation("g_market_04", "ba_1", 1, "p1"));

        queue = CityC9BuildQueue.upsertFromPlan(queue, "city_demo", plan, null);
        queue = CityC9BuildQueue.upsertFromPlan(queue, "city_demo", plan, null);

        assertEquals(1, queue.tasks.size());
        assertEquals("city_demo|ba_1|p1", queue.tasks.get(0).task_id);
    }

    @Test
    void summarizeCountsStatuses() {
        CityC9BuildQueue.BuildQueue queue = new CityC9BuildQueue.BuildQueue();
        queue.tasks.add(task("t1", "g1", CityC9BuildQueue.Status.PLANNED.name()));
        queue.tasks.add(task("t2", "g1", CityC9BuildQueue.Status.READY.name()));
        queue.tasks.add(task("t3", "g1", CityC9BuildQueue.Status.BUILDING.name()));
        queue.tasks.add(task("t4", "g1", CityC9BuildQueue.Status.DONE.name()));
        queue.tasks.add(task("t5", "g2", CityC9BuildQueue.Status.BLOCKED.name()));

        CityC9BuildQueue.QueueSummary summary = CityC9BuildQueue.summarize(queue, "g1");

        assertEquals(1, summary.planned_count);
        assertEquals(1, summary.ready_count);
        assertEquals(1, summary.inflight_count);
        assertEquals(1, summary.done_count);
        assertEquals(0, summary.blocked_count);
        assertEquals(4, summary.total_count);
    }

    @Test
    void upsertFromPlanReplacesCompletedTaskWhenPlacementChanges() {
        CityC9BuildQueue.BuildQueue queue = new CityC9BuildQueue.BuildQueue();
        CityC9BuildQueue.BuildTask existing = task("city_demo|ba_1|p1", "g_market_04", CityC9BuildQueue.Status.DONE.name());
        existing.city_id = "city_demo";
        existing.build_area_id = "ba_1";
        existing.build_area_numeric_id = 1;
        existing.node_id = "p1";
        existing.template_id = "test:old_market";
        existing.x = 32;
        existing.y = 70;
        existing.z = 48;
        existing.rotation = 90;
        existing.retry_count = 2;
        existing.last_error = "old_error";
        existing.build_order = 0;
        existing.placement_node = placement("p1", "test:old_market", 32, 70, 48, 90);
        existing.placement_signature = "old_signature";
        queue.tasks.add(existing);

        CityC8Stages.C8Plan plan = new CityC8Stages.C8Plan();
        CityC8Stages.FoundationItem foundation = foundation("g_market_04", "ba_1", 1, "p1");
        foundation.placements.clear();
        foundation.placements.add(placement("p1", "test:new_market", 64, 72, 80, 180));
        plan.foundations.add(foundation);

        queue = CityC9BuildQueue.upsertFromPlan(queue, "city_demo", plan, null);

        assertEquals(1, queue.tasks.size());
        CityC9BuildQueue.BuildTask updated = queue.tasks.get(0);
        assertEquals(CityC9BuildQueue.Status.PLANNED.name(), updated.status);
        assertEquals("test:new_market", updated.template_id);
        assertEquals(64, updated.x);
        assertEquals(72, updated.y);
        assertEquals(80, updated.z);
        assertEquals(180, updated.rotation);
        assertEquals(0, updated.retry_count);
        assertNull(updated.last_error);
    }

    private static CityC8Stages.FoundationItem foundation(String groupId, String areaId, int areaNumericId, String nodeId) {
        CityC8Stages.FoundationItem foundation = new CityC8Stages.FoundationItem();
        foundation.group_id = groupId;
        foundation.build_area_id = areaId;
        foundation.build_area_numeric_id = areaNumericId;

        CityC8Stages.PlacementNode node = new CityC8Stages.PlacementNode();
        node.node_id = nodeId;
        node.template_id = "test:template";
        node.x = 32;
        node.y = 70;
        node.z = 48;
        node.rotation = 90;
        node.build_order = 0;
        foundation.placements.add(node);
        return foundation;
    }

    private static CityC8Stages.PlacementNode placement(String nodeId, String templateId, int x, int y, int z, int rotation) {
        CityC8Stages.PlacementNode node = new CityC8Stages.PlacementNode();
        node.node_id = nodeId;
        node.component_id = "component_" + nodeId;
        node.template_id = templateId;
        node.role = "market_stall";
        node.x = x;
        node.y = y;
        node.z = z;
        node.rotation = rotation;
        node.build_order = 0;
        node.footprint_min_x = x;
        node.footprint_min_z = z;
        node.footprint_max_x = x + 4;
        node.footprint_max_z = z + 4;
        return node;
    }

    private static CityC9BuildQueue.BuildTask task(String taskId, String groupId, String status) {
        CityC9BuildQueue.BuildTask task = new CityC9BuildQueue.BuildTask();
        task.task_id = taskId;
        task.group_id = groupId;
        task.status = status;
        return task;
    }
}
