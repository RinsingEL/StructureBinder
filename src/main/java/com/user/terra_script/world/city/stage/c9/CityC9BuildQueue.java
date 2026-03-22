package com.user.terra_script.world.city.stage.c9;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.user.terra_script.event.ServerTickTracker;
import com.user.terra_script.world.city.stage.c8.CityC8Stages;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class CityC9BuildQueue {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final String BUILD_QUEUE_FILE = "C9_BuildQueue.json";
    public static final int MAX_RETRIES = 3;

    private CityC9BuildQueue() {}

    public static class BuildQueue {
        public String step = "C9_QUEUE";
        public boolean ok = true;
        public String city_id;
        public long generated_at_epoch_ms;
        public long updated_at_epoch_ms;
        public List<BuildTask> tasks = new ArrayList<>();
    }

    public static class BuildTask {
        public String task_id;
        public String city_id;
        public String group_id;
        public String build_area_id;
        public int build_area_numeric_id;
        public String node_id;
        public String template_id;
        public int x;
        public int y;
        public int z;
        public int rotation;
        public int chunk_x;
        public int chunk_z;
        public int priority;
        public String status = Status.PLANNED.name();
        public int retry_count;
        public String last_error;
        public long created_at_tick;
        public long updated_at_tick;
        public Integer build_order;
        public String parent_node_id;
        public boolean terminalized;
        public CityC8Stages.PlacementNode placement_node;
    }

    public static class QueueSummary {
        public int planned_count;
        public int ready_count;
        public int inflight_count;
        public int done_count;
        public int blocked_count;
        public int total_count;
    }

    public enum Status {
        PLANNED,
        READY,
        BUILDING,
        DONE,
        BLOCKED;

        public static String normalize(String raw) {
            if (raw == null || raw.isBlank()) return PLANNED.name();
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT)).name();
            } catch (IllegalArgumentException ignored) {
                return PLANNED.name();
            }
        }
    }

    public static Path resolveCityDir(MinecraftServer server, String cityId) {
        return server.getWorldPath(LevelResource.ROOT).resolve("terra_script").resolve("cities").resolve(cityId);
    }

    public static BuildQueue load(Path cityDir) throws Exception {
        if (cityDir == null) return null;
        Path file = cityDir.resolve(BUILD_QUEUE_FILE);
        if (!Files.exists(file)) return null;
        BuildQueue queue = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), BuildQueue.class);
        normalize(queue);
        return queue;
    }

    public static BuildQueue loadOrCreate(Path cityDir, String cityId) throws Exception {
        BuildQueue queue = load(cityDir);
        if (queue != null) return queue;
        queue = new BuildQueue();
        queue.city_id = cityId;
        queue.generated_at_epoch_ms = System.currentTimeMillis();
        queue.updated_at_epoch_ms = queue.generated_at_epoch_ms;
        return queue;
    }

    public static void save(Path cityDir, BuildQueue queue) throws Exception {
        if (cityDir == null || queue == null) return;
        Files.createDirectories(cityDir);
        queue.updated_at_epoch_ms = System.currentTimeMillis();
        normalize(queue);
        Files.writeString(cityDir.resolve(BUILD_QUEUE_FILE), GSON.toJson(queue), StandardCharsets.UTF_8);
    }

    public static QueueSummary summarize(BuildQueue queue, String groupId) {
        QueueSummary summary = new QueueSummary();
        if (queue == null || queue.tasks == null) return summary;
        for (BuildTask task : queue.tasks) {
            if (task == null) continue;
            if (groupId != null && !groupId.isBlank() && !Objects.equals(groupId, task.group_id)) continue;
            summary.total_count++;
            switch (Status.normalize(task.status)) {
                case "READY" -> summary.ready_count++;
                case "BUILDING" -> summary.inflight_count++;
                case "DONE" -> summary.done_count++;
                case "BLOCKED" -> summary.blocked_count++;
                default -> summary.planned_count++;
            }
        }
        return summary;
    }

    public static BuildQueue upsertFromPlan(BuildQueue queue, String cityId, CityC8Stages.C8Plan plan, String groupId) {
        if (queue == null) queue = new BuildQueue();
        if (queue.city_id == null || queue.city_id.isBlank()) queue.city_id = cityId;
        if (queue.generated_at_epoch_ms <= 0L) queue.generated_at_epoch_ms = System.currentTimeMillis();
        normalize(queue);
        Map<String, BuildTask> byId = new LinkedHashMap<>();
        for (BuildTask task : queue.tasks) {
            if (task != null && task.task_id != null && !task.task_id.isBlank()) {
                byId.put(task.task_id, task);
            }
        }
        long nowTick = ServerTickTracker.currentTick();
        if (plan != null && plan.foundations != null) {
            for (CityC8Stages.FoundationItem foundation : plan.foundations) {
                if (foundation == null || foundation.placements == null || foundation.placements.isEmpty()) continue;
                if (groupId != null && !groupId.isBlank() && !groupId.equals(foundation.group_id)) continue;
                for (CityC8Stages.PlacementNode node : foundation.placements) {
                    if (node == null || node.node_id == null || node.node_id.isBlank() || node.template_id == null || node.template_id.isBlank()) {
                        continue;
                    }
                    String taskId = taskId(cityId, foundation.build_area_id, node.node_id);
                    BuildTask existing = byId.get(taskId);
                    if (existing != null && Status.DONE.name().equals(Status.normalize(existing.status))) {
                        continue;
                    }
                    BuildTask task = existing != null ? existing : new BuildTask();
                    task.task_id = taskId;
                    task.city_id = cityId;
                    task.group_id = foundation.group_id;
                    task.build_area_id = foundation.build_area_id;
                    task.build_area_numeric_id = foundation.build_area_numeric_id;
                    task.node_id = node.node_id;
                    task.template_id = node.template_id;
                    task.x = node.x;
                    task.y = node.y > 0 ? node.y : foundation.base_y;
                    task.z = node.z;
                    task.rotation = node.rotation;
                    task.chunk_x = Math.floorDiv(node.x, 16);
                    task.chunk_z = Math.floorDiv(node.z, 16);
                    task.priority = buildPriority(node);
                    task.build_order = node.build_order;
                    task.parent_node_id = node.parent_node_id;
                    task.terminalized = node.terminalized;
                    task.placement_node = node;
                    task.updated_at_tick = nowTick;
                    if (existing == null) {
                        task.created_at_tick = nowTick;
                        task.status = Status.PLANNED.name();
                    } else if (!Status.BLOCKED.name().equals(Status.normalize(task.status))) {
                        task.status = Status.PLANNED.name();
                    }
                    byId.put(taskId, task);
                }
            }
        }
        queue.tasks = new ArrayList<>(byId.values());
        queue.tasks.sort(Comparator
                .comparing((BuildTask t) -> safe(t.group_id))
                .thenComparing(t -> safe(t.build_area_id))
                .thenComparingInt(t -> t.build_order != null ? t.build_order : Integer.MAX_VALUE)
                .thenComparing(t -> safe(t.task_id)));
        queue.updated_at_epoch_ms = System.currentTimeMillis();
        return queue;
    }

    public static BuildQueue filtered(BuildQueue queue, String groupId) {
        if (queue == null) return null;
        BuildQueue copy = new BuildQueue();
        copy.ok = queue.ok;
        copy.city_id = queue.city_id;
        copy.generated_at_epoch_ms = queue.generated_at_epoch_ms;
        copy.updated_at_epoch_ms = queue.updated_at_epoch_ms;
        if (queue.tasks != null) {
            for (BuildTask task : queue.tasks) {
                if (task == null) continue;
                if (groupId != null && !groupId.isBlank() && !Objects.equals(groupId, task.group_id)) continue;
                copy.tasks.add(task);
            }
        }
        return copy;
    }

    public static String taskId(String cityId, String buildAreaId, String nodeId) {
        return safe(cityId) + "|" + safe(buildAreaId) + "|" + safe(nodeId);
    }

    private static int buildPriority(CityC8Stages.PlacementNode node) {
        int base = node != null && node.build_order != null ? Math.max(0, 1000 - node.build_order) : 100;
        if (node != null && node.level == 0) base += 100;
        return base;
    }

    private static void normalize(BuildQueue queue) {
        if (queue == null) return;
        if (queue.tasks == null) queue.tasks = new ArrayList<>();
        for (BuildTask task : queue.tasks) {
            if (task == null) continue;
            task.status = Status.normalize(task.status);
            if (task.task_id == null || task.task_id.isBlank()) {
                task.task_id = taskId(task.city_id, task.build_area_id, task.node_id);
            }
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
