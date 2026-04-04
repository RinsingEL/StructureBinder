package com.user.terra_script.world.city.execution;

import com.google.gson.JsonObject;
import com.user.terra_script.world.StructureInjector;
import com.user.terra_script.world.city.stage.c9.CityC9BuildQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class BuildExecutionTestSupport {
    private BuildExecutionTestSupport() {
    }

    static CityC9BuildQueue.BuildTask task(String taskId) {
        CityC9BuildQueue.BuildTask task = new CityC9BuildQueue.BuildTask();
        task.task_id = taskId;
        task.city_id = "city_demo";
        task.group_id = "g_market_04";
        task.build_area_id = "ba_1";
        task.node_id = "node_1";
        task.template_id = "test:template";
        task.x = 10;
        task.y = 64;
        task.z = 20;
        task.chunk_x = 0;
        task.chunk_z = 1;
        task.rotation = 0;
        return task;
    }

    static CityC9BuildQueue.BuildQueue queue(CityC9BuildQueue.BuildTask... tasks) {
        CityC9BuildQueue.BuildQueue queue = new CityC9BuildQueue.BuildQueue();
        queue.city_id = "city_demo";
        for (CityC9BuildQueue.BuildTask task : tasks) {
            queue.tasks.add(task);
        }
        return queue;
    }

    static final class FakeWorldAccess implements BuildWorldAccess {
        final Map<BlockPos, BuildBlockSnapshot> blocks = new HashMap<>();
        boolean serverRunning = true;
        boolean chunkLoaded = true;
        boolean chunkFull = true;

        @Override
        public ServerLevel level() {
            return null;
        }

        @Override
        public MinecraftServer server() {
            return null;
        }

        @Override
        public boolean isServerRunning() {
            return serverRunning;
        }

        @Override
        public boolean hasChunk(int chunkX, int chunkZ) {
            return chunkLoaded;
        }

        @Override
        public boolean isChunkFull(int chunkX, int chunkZ) {
            return chunkFull;
        }

        @Override
        public BuildBlockSnapshot blockSnapshot(BlockPos pos) {
            return blocks.getOrDefault(pos, BuildBlockSnapshot.airBlock());
        }

        @Override
        public boolean clearBlock(BlockPos pos) {
            blocks.put(pos.immutable(), BuildBlockSnapshot.airBlock());
            return true;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return null;
        }

        @Override
        public boolean setBlock(BlockPos pos, BlockState state, int flags) {
            return true;
        }
    }

    static final class RecordingLogger implements BuildTaskDebugLogger {
        final List<LogEntry> entries = new ArrayList<>();

        @Override
        public void progress(String message, JsonObject details) {
            entries.add(new LogEntry("progress", message, details == null ? new JsonObject() : details.deepCopy()));
        }

        @Override
        public void completed(String message, JsonObject details) {
            entries.add(new LogEntry("completed", message, details == null ? new JsonObject() : details.deepCopy()));
        }

        @Override
        public void failed(String message, JsonObject details) {
            entries.add(new LogEntry("failed", message, details == null ? new JsonObject() : details.deepCopy()));
        }
    }

    static final class FakePlacementGateway implements StructurePlacementGateway {
        StructureInjector.PlacementBounds bounds = StructureInjector.PlacementBounds.of(10, 64, 20, 12, 67, 22);
        StructureInjector.PlacementOutcome outcome = StructureInjector.PlacementOutcome.of(true, bounds, 0, List.of());

        @Override
        public StructureInjector.PlacementBounds placementBounds(BuildWorldAccess world, String structureId, BlockPos origin, Rotation rotation) {
            return bounds;
        }

        @Override
        public StructureInjector.PlacementOutcome placeStructure(BuildWorldAccess world, String structureId, BlockPos origin, Rotation rotation, boolean clearJigsawBlocks) {
            return outcome;
        }
    }

    record LogEntry(String level, String message, JsonObject details) {
    }
}
