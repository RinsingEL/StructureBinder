package com.user.terra_script.world.city;

import com.user.terra_script.event.ServerTickTracker;
import com.user.terra_script.world.StructureInjector;
import com.user.terra_script.world.city.stage.c9.CityC9BuildQueue;
import com.user.terra_script.world.city.stage.c9.CityC9BuildQueue.BuildQueue;
import com.user.terra_script.world.city.stage.c9.CityC9BuildQueue.BuildTask;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = "terra_script")
public final class CityBuildQueueExecutor {
    private static final Map<String, BuildQueue> QUEUES_BY_CITY = new ConcurrentHashMap<>();
    private static final Map<Long, Set<String>> TASK_IDS_BY_CHUNK = new ConcurrentHashMap<>();
    private static final Deque<String> READY_TASK_IDS = new ArrayDeque<>();
    private static final Set<String> ENQUEUED_READY_TASK_IDS = ConcurrentHashMap.newKeySet();
    private static final Set<String> ACTIVE_TASK_IDS = ConcurrentHashMap.newKeySet();

    private static volatile Path loadedWorldRoot;

    private static final int TASKS_PER_TICK = 2;

    private CityBuildQueueExecutor() {}

    public static final class ExecutionReport {
        public int attempted;
        public int completed;
        public int blocked;
        public int retried;
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        MinecraftServer server = level.getServer();
        if (server == null || !server.isRunning()) return;
        ensureLoaded(server);

        long chunkKey = event.getChunk().getPos().toLong();
        Set<String> taskIds = TASK_IDS_BY_CHUNK.get(chunkKey);
        if (taskIds == null || taskIds.isEmpty()) return;
        for (String taskId : taskIds) {
            BuildTask task = findTask(taskId);
            if (task == null) continue;
            String status = CityC9BuildQueue.Status.normalize(task.status);
            if (CityC9BuildQueue.Status.DONE.name().equals(status) || CityC9BuildQueue.Status.BLOCKED.name().equals(status)) {
                continue;
            }
            task.status = CityC9BuildQueue.Status.READY.name();
            task.updated_at_tick = ServerTickTracker.currentTick();
            queueReady(task.task_id);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleAccess.currentServer();
        if (server == null || !server.isRunning()) return;
        ensureLoaded(server);
        executeReady(server, null, null, TASKS_PER_TICK);
    }

    public static void refreshCityQueue(MinecraftServer server, String cityId) throws Exception {
        if (server == null || cityId == null || cityId.isBlank()) return;
        ensureLoaded(server);
        Path cityDir = CityC9BuildQueue.resolveCityDir(server, cityId);
        BuildQueue queue = CityC9BuildQueue.load(cityDir);
        reindexCity(cityId, queue);
    }

    public static ExecutionReport executeLoadedTasksNow(MinecraftServer server, String cityId, String groupId, int maxTasks) throws Exception {
        ensureLoaded(server);
        if (cityId != null && !cityId.isBlank()) refreshCityQueue(server, cityId);
        return executeReady(server, cityId, groupId, Math.max(0, maxTasks));
    }

    private static synchronized void ensureLoaded(MinecraftServer server) {
        if (server == null) return;
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        if (Objects.equals(worldRoot, loadedWorldRoot)) return;
        loadedWorldRoot = worldRoot;
        QUEUES_BY_CITY.clear();
        TASK_IDS_BY_CHUNK.clear();
        READY_TASK_IDS.clear();
        ENQUEUED_READY_TASK_IDS.clear();
        ACTIVE_TASK_IDS.clear();

        Path citiesDir = worldRoot.resolve("terra_script").resolve("cities");
        if (!Files.isDirectory(citiesDir)) return;
        try (var stream = Files.list(citiesDir)) {
            stream.filter(Files::isDirectory).forEach(cityDir -> {
                try {
                    BuildQueue queue = CityC9BuildQueue.load(cityDir);
                    String cityId = cityDir.getFileName().toString();
                    reindexCity(cityId, queue);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private static synchronized void reindexCity(String cityId, BuildQueue queue) {
        if (cityId == null || cityId.isBlank()) return;
        BuildQueue previous = QUEUES_BY_CITY.remove(cityId);
        if (previous != null && previous.tasks != null) {
            for (BuildTask task : previous.tasks) {
                if (task == null) continue;
                removeIndexedTask(task);
            }
        }
        if (queue == null) return;
        QUEUES_BY_CITY.put(cityId, queue);
        if (queue.tasks == null) return;
        for (BuildTask task : queue.tasks) {
            if (task == null || task.task_id == null || task.task_id.isBlank()) continue;
            TASK_IDS_BY_CHUNK.computeIfAbsent(chunkKey(task.chunk_x, task.chunk_z), ignored -> ConcurrentHashMap.newKeySet()).add(task.task_id);
            String status = CityC9BuildQueue.Status.normalize(task.status);
            if (CityC9BuildQueue.Status.READY.name().equals(status)) {
                queueReady(task.task_id);
            }
        }
    }

    private static void removeIndexedTask(BuildTask task) {
        if (task == null || task.task_id == null) return;
        Set<String> ids = TASK_IDS_BY_CHUNK.get(chunkKey(task.chunk_x, task.chunk_z));
        if (ids != null) {
            ids.remove(task.task_id);
            if (ids.isEmpty()) TASK_IDS_BY_CHUNK.remove(chunkKey(task.chunk_x, task.chunk_z));
        }
        ENQUEUED_READY_TASK_IDS.remove(task.task_id);
        ACTIVE_TASK_IDS.remove(task.task_id);
    }

    private static void queueReady(String taskId) {
        if (taskId == null || taskId.isBlank()) return;
        if (!ENQUEUED_READY_TASK_IDS.add(taskId)) return;
        READY_TASK_IDS.addLast(taskId);
    }

    private static ExecutionReport executeReady(MinecraftServer server, String cityId, String groupId, int maxTasks) {
        ExecutionReport report = new ExecutionReport();
        if (server == null || maxTasks <= 0) return report;
        ServerLevel level = server.overworld();
        if (level == null) return report;

        List<BuildTask> candidates = collectCandidates(cityId, groupId);
        candidates.sort(Comparator
                .comparingInt((BuildTask task) -> -task.priority)
                .thenComparing(task -> task.build_order != null ? task.build_order : Integer.MAX_VALUE)
                .thenComparing(task -> task.task_id));
        int processed = 0;
        for (BuildTask task : candidates) {
            if (processed >= maxTasks) break;
            if (!ACTIVE_TASK_IDS.add(task.task_id)) continue;
            try {
                report.attempted++;
                TaskOutcome outcome = executeTask(level, task);
                processed++;
                switch (outcome) {
                    case COMPLETED -> report.completed++;
                    case BLOCKED -> report.blocked++;
                    case RETRIED -> report.retried++;
                    case SKIPPED -> { }
                }
            } finally {
                ACTIVE_TASK_IDS.remove(task.task_id);
                ENQUEUED_READY_TASK_IDS.remove(task.task_id);
            }
        }
        return report;
    }

    private static List<BuildTask> collectCandidates(String cityId, String groupId) {
        List<BuildTask> out = new ArrayList<>();
        for (BuildQueue queue : QUEUES_BY_CITY.values()) {
            if (queue == null || queue.tasks == null) continue;
            if (cityId != null && !cityId.isBlank() && !cityId.equals(queue.city_id)) continue;
            for (BuildTask task : queue.tasks) {
                if (task == null) continue;
                if (groupId != null && !groupId.isBlank() && !groupId.equals(task.group_id)) continue;
                String status = CityC9BuildQueue.Status.normalize(task.status);
                if (!CityC9BuildQueue.Status.READY.name().equals(status) && !CityC9BuildQueue.Status.PLANNED.name().equals(status)) {
                    continue;
                }
                out.add(task);
            }
        }
        return out;
    }

    private enum TaskOutcome {
        COMPLETED,
        BLOCKED,
        RETRIED,
        SKIPPED
    }

    private static TaskOutcome executeTask(ServerLevel level, BuildTask task) {
        if (task == null) return TaskOutcome.SKIPPED;
        if (!level.getServer().isRunning()) return TaskOutcome.SKIPPED;
        if (!level.hasChunk(task.chunk_x, task.chunk_z)) {
            task.status = CityC9BuildQueue.Status.PLANNED.name();
            persist(task.city_id);
            return TaskOutcome.SKIPPED;
        }
        if (level.getChunk(task.chunk_x, task.chunk_z, ChunkStatus.FULL, false) == null) {
            task.status = CityC9BuildQueue.Status.READY.name();
            persist(task.city_id);
            return TaskOutcome.SKIPPED;
        }

        task.status = CityC9BuildQueue.Status.BUILDING.name();
        task.updated_at_tick = ServerTickTracker.currentTick();
        persist(task.city_id);

        String error = validateTask(task);
        if (error != null) {
            return failTask(task, error);
        }

        boolean ok = StructureInjector.spawnStructureAtBlock(
                level,
                task.template_id,
                new BlockPos(task.x, task.y, task.z),
                toRotation(task.rotation),
                true
        );
        if (!ok) {
            return failTask(task, "structure_place_failed");
        }

        task.status = CityC9BuildQueue.Status.DONE.name();
        task.last_error = null;
        task.updated_at_tick = ServerTickTracker.currentTick();
        persist(task.city_id);
        return TaskOutcome.COMPLETED;
    }

    private static String validateTask(BuildTask task) {
        if (task.parent_node_id != null && !task.parent_node_id.isBlank()) {
            BuildTask parent = findTask(CityC9BuildQueue.taskId(task.city_id, task.build_area_id, task.parent_node_id));
            if (parent == null) return "missing_parent_task";
            if (!CityC9BuildQueue.Status.DONE.name().equals(CityC9BuildQueue.Status.normalize(parent.status))) {
                return "waiting_for_parent";
            }
        }
        ServerLevel level = ServerLifecycleAccess.currentServer() != null ? ServerLifecycleAccess.currentServer().overworld() : null;
        StructureInjector.PlacementBounds candidateBounds = level != null
                ? StructureInjector.placementBounds(level, task.template_id, new BlockPos(task.x, task.y, task.z), toRotation(task.rotation))
                : null;
        BuildQueue queue = QUEUES_BY_CITY.get(task.city_id);
        if (queue == null || queue.tasks == null) return null;
        for (BuildTask other : queue.tasks) {
            if (other == null || other == task) continue;
            if (!Objects.equals(task.build_area_id, other.build_area_id)) continue;
            if (!CityC9BuildQueue.Status.DONE.name().equals(CityC9BuildQueue.Status.normalize(other.status))) continue;
            if (intersects(level, candidateBounds, task, other)) return "runtime_footprint_collision";
        }
        return null;
    }

    private static TaskOutcome failTask(BuildTask task, String error) {
        task.retry_count++;
        task.last_error = error;
        task.updated_at_tick = ServerTickTracker.currentTick();
        if (task.retry_count >= CityC9BuildQueue.MAX_RETRIES || isTerminalError(error)) {
            task.status = CityC9BuildQueue.Status.BLOCKED.name();
            persist(task.city_id);
            return TaskOutcome.BLOCKED;
        }
        task.status = CityC9BuildQueue.Status.READY.name();
        persist(task.city_id);
        queueReady(task.task_id);
        return TaskOutcome.RETRIED;
    }

    private static boolean isTerminalError(String error) {
        return "missing_parent_task".equals(error);
    }

    private static boolean intersects(ServerLevel level, StructureInjector.PlacementBounds candidateBounds, BuildTask candidate, BuildTask existing) {
        if (level == null || candidate == null || existing == null) return false;
        StructureInjector.PlacementBounds left = candidateBounds != null
                ? candidateBounds
                : StructureInjector.placementBounds(level, candidate.template_id, new BlockPos(candidate.x, candidate.y, candidate.z), toRotation(candidate.rotation));
        StructureInjector.PlacementBounds right = StructureInjector.placementBounds(
                level,
                existing.template_id,
                new BlockPos(existing.x, existing.y, existing.z),
                toRotation(existing.rotation)
        );
        return left != null && left.intersects(right);
    }

    private static BuildTask findTask(String taskId) {
        if (taskId == null || taskId.isBlank()) return null;
        for (BuildQueue queue : QUEUES_BY_CITY.values()) {
            if (queue == null || queue.tasks == null) continue;
            for (BuildTask task : queue.tasks) {
                if (task != null && taskId.equals(task.task_id)) return task;
            }
        }
        return null;
    }

    private static void persist(String cityId) {
        if (cityId == null || cityId.isBlank() || loadedWorldRoot == null) return;
        BuildQueue queue = QUEUES_BY_CITY.get(cityId);
        if (queue == null) return;
        try {
            CityC9BuildQueue.save(loadedWorldRoot.resolve("terra_script").resolve("cities").resolve(cityId), queue);
        } catch (Exception ignored) {
        }
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ChunkPos.asLong(chunkX, chunkZ);
    }

    private static Rotation toRotation(int degrees) {
        int normalized = ((degrees % 360) + 360) % 360;
        return switch (normalized) {
            case 90 -> Rotation.CLOCKWISE_90;
            case 180 -> Rotation.CLOCKWISE_180;
            case 270 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    private static final class ServerLifecycleAccess {
        private static volatile MinecraftServer currentServer;

        private ServerLifecycleAccess() {}

        private static MinecraftServer currentServer() {
            return currentServer;
        }
    }

    public static void bindServer(MinecraftServer server) {
        ServerLifecycleAccess.currentServer = server;
    }

    public static void unbindServer() {
        ServerLifecycleAccess.currentServer = null;
        loadedWorldRoot = null;
        QUEUES_BY_CITY.clear();
        TASK_IDS_BY_CHUNK.clear();
        READY_TASK_IDS.clear();
        ENQUEUED_READY_TASK_IDS.clear();
        ACTIVE_TASK_IDS.clear();
    }
}
