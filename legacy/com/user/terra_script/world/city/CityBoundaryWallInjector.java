package com.user.terra_script.world.city;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Mod.EventBusSubscriber(modid = "terra_script")
public class CityBoundaryWallInjector {
    private static final Set<Long> processedChunks = ConcurrentHashMap.newKeySet();
    private static final Set<Long> queuedChunks = ConcurrentHashMap.newKeySet();
    private static final Queue<WallTask> pendingChunks = new ConcurrentLinkedQueue<>();
    private static final int CHUNKS_PER_TICK = 1;
    private static final int WALL_HEIGHT = 10;

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.getServer().isRunning()) return;

        ChunkPos cp = event.getChunk().getPos();
        long chunkKey = cp.toLong();

        String cityId = CityManager.get().getCityIdAt(chunkKey);
        if (cityId == null) return;

        CityInstance city = CityManager.get().getCity(cityId);
        if (city == null || !city.borderChunks.contains(chunkKey)) return;

        if (processedChunks.contains(chunkKey) || queuedChunks.contains(chunkKey)) return;
        queuedChunks.add(chunkKey);
        pendingChunks.add(new WallTask(level, cp, cityId, chunkKey));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        for (int i = 0; i < CHUNKS_PER_TICK; i++) {
            WallTask task = pendingChunks.poll();
            if (task == null) return;

            if (!task.level.getServer().isRunning() || !task.level.hasChunk(task.chunkPos.x, task.chunkPos.z)) {
                queuedChunks.remove(task.chunkKey);
                continue;
            }

            if (task.level.getChunk(task.chunkPos.x, task.chunkPos.z, ChunkStatus.FULL, false) == null) {
                pendingChunks.add(task);
                return;
            }

            generateWallsInChunk(task.level, task.chunkPos, task.cityId);
            queuedChunks.remove(task.chunkKey);
            processedChunks.add(task.chunkKey);
        }
    }

    private static void generateWallsInChunk(ServerLevel level, ChunkPos cp, String cityId) {
        CityInstance city = CityManager.get().getCity(cityId);
        if (city == null || city.claimedChunks == null) return;

        long selfKey = cp.toLong();
        if (!city.borderChunks.contains(selfKey)) return;

        CityConfig.LayerLayout layout = city.getLayerLayout();
        boolean useWallLayers = hasWallLayers(layout);

        boolean north;
        boolean south;
        boolean west;
        boolean east;

        if (useWallLayers) {
            CityInstance.LayerAssignment assignment = city.claimedChunks.get(selfKey);
            if (assignment == null || !isWallLayer(layout, assignment)) return;
            int layerIndex = assignment.layerIndex;
            north = isDifferentLayer(city, layerIndex, ChunkPos.asLong(cp.x, cp.z - 1));
            south = isDifferentLayer(city, layerIndex, ChunkPos.asLong(cp.x, cp.z + 1));
            west = isDifferentLayer(city, layerIndex, ChunkPos.asLong(cp.x - 1, cp.z));
            east = isDifferentLayer(city, layerIndex, ChunkPos.asLong(cp.x + 1, cp.z));
        } else {
            north = !city.claimedChunks.containsKey(ChunkPos.asLong(cp.x, cp.z - 1));
            south = !city.claimedChunks.containsKey(ChunkPos.asLong(cp.x, cp.z + 1));
            west = !city.claimedChunks.containsKey(ChunkPos.asLong(cp.x - 1, cp.z));
            east = !city.claimedChunks.containsKey(ChunkPos.asLong(cp.x + 1, cp.z));
        }

        int minX = cp.getMinBlockX();
        int minZ = cp.getMinBlockZ();
        int maxX = minX + 15;
        int maxZ = minZ + 15;

        if (north) {
            for (int x = minX; x <= maxX; x++) {
                placeWallColumn(level, x, minZ);
            }
        }
        if (south) {
            for (int x = minX; x <= maxX; x++) {
                placeWallColumn(level, x, maxZ);
            }
        }
        if (west) {
            for (int z = minZ; z <= maxZ; z++) {
                placeWallColumn(level, minX, z);
            }
        }
        if (east) {
            for (int z = minZ; z <= maxZ; z++) {
                placeWallColumn(level, maxX, z);
            }
        }
    }

    private static void placeWallColumn(ServerLevel level, int worldX, int worldZ) {
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ);
        int baseY = surfaceY - 1;
        int minY = level.getMinBuildHeight();
        if (baseY < minY) baseY = minY;

        for (int dy = 0; dy < WALL_HEIGHT; dy++) {
            BlockPos pos = new BlockPos(worldX, baseY + dy, worldZ);
            level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
            updateLighting(level, pos);
        }
    }

    private static void updateLighting(ServerLevel level, BlockPos pos) {
        level.getChunkSource().getLightEngine().checkBlock(pos);
    }

    private static boolean hasWallLayers(CityConfig.LayerLayout layout) {
        if (layout == null || layout.layers == null) return false;
        for (CityConfig.LayerConfig layer : layout.layers) {
            if (layer == null) continue;
            if (layer.wallLayer || layer.wall != null) return true;
        }
        return false;
    }

    private static boolean isWallLayer(CityConfig.LayerLayout layout, CityInstance.LayerAssignment assignment) {
        if (layout == null || assignment == null) return false;
        CityConfig.LayerConfig layer = layout.layerAt(assignment.layerIndex);
        return layer.wallLayer || layer.wall != null;
    }

    private static boolean isDifferentLayer(CityInstance city, int layerIndex, long neighborKey) {
        CityInstance.LayerAssignment neighbor = city.claimedChunks.get(neighborKey);
        if (neighbor == null) return true;
        return neighbor.layerIndex != layerIndex;
    }

    private record WallTask(ServerLevel level, ChunkPos chunkPos, String cityId, long chunkKey) {}
}
