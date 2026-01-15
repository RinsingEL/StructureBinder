package com.user.terra_script.world.city;

import com.user.terra_script.world.city.CityInstance;
import com.user.terra_script.world.city.CityManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.tags.BlockTags;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Mod.EventBusSubscriber(modid = "terra_script")
public class RoadInjector {

    // 记录已处理过�?Chunk，防止重复铺�?(简单内存缓�?
    // 更好的做法是检�?Chunk NBT 标记，但这里简化处�?
    private static final Set<Long> processedChunks = ConcurrentHashMap.newKeySet();
    private static final Set<Long> queuedChunks = ConcurrentHashMap.newKeySet();
    private static final Queue<RoadTask> pendingChunks = new ConcurrentLinkedQueue<>();
    private static final int CHUNKS_PER_TICK = 2;

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.getServer().isRunning()) return;

        ChunkPos cp = event.getChunk().getPos();
        long chunkKey = cp.toLong();

        // 1. 快速检查：这个 Chunk 是否属于任何城市�?
        // 我们可以�?CityManager
        String cityId = CityManager.get().getCityIdAt(chunkKey);
        if (cityId == null) return;

        // 2. 防重入检�?
        if (processedChunks.contains(chunkKey) || queuedChunks.contains(chunkKey)) return;
        queuedChunks.add(chunkKey);
        pendingChunks.add(new RoadTask(level, cp, cityId, chunkKey));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        for (int i = 0; i < CHUNKS_PER_TICK; i++) {
            RoadTask task = pendingChunks.poll();
            if (task == null) return;

            if (!task.level.getServer().isRunning() || !task.level.hasChunk(task.chunkPos.x, task.chunkPos.z)) {
                queuedChunks.remove(task.chunkKey);
                continue;
            }

            if (task.level.getChunk(task.chunkPos.x, task.chunkPos.z, ChunkStatus.FULL, false) == null) {
                pendingChunks.add(task);
                return;
            }

            CityManager.get().ensureRoadsGenerated(task.cityId);
            generateRoadsInChunk(task.level, task.chunkPos, task.cityId);
            queuedChunks.remove(task.chunkKey);
            processedChunks.add(task.chunkKey);
        }
    }

    private static void generateRoadsInChunk(ServerLevel level, ChunkPos cp, String cityId) {
        CityInstance city = CityManager.get().getCity(cityId);
        if (city == null || city.roadBlocks == null) return;

        BlockState roadState = Blocks.COBBLESTONE.defaultBlockState();
        BlockState airState = Blocks.AIR.defaultBlockState();
        BlockState fillState = Blocks.DIRT.defaultBlockState(); // 地基

        int startX = cp.getMinBlockX();
        int startZ = cp.getMinBlockZ();

        // 预加�?Tag (虽然 Forge 会缓存，但提出来好一�?
        // TagKey<Block> LOGS = BlockTags.LOGS;
        // TagKey<Block> LEAVES = BlockTags.LEAVES;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int worldX = startX + x;
                int worldZ = startZ + z;
                long blockKey = packBlock(worldX, worldZ);

                if (city.roadBlocks.contains(blockKey)) {
                    // 1. 找地平线 (忽略树木)
                    int roadY = findRoadBaseY(level, worldX, worldZ);
                    BlockPos roadPos = new BlockPos(worldX, roadY, worldZ);
                    // 或�?surfaceY (铺在草地�? -> 看您喜好，嵌入更自然

                    // 2. 铺设路面
                    level.setBlock(roadPos, roadState, 2); // 2 = Send to client (无方块更新通知)
                    // 清理 6 格高，保证骑马能过，且砍掉大部分树干
                    clearObstacles(level, roadPos, airState, 1, 6);

                    // 4. 向下夯实 (防止悬空)
                    // 如果下面是水或者空气，填土
                    BlockPos below = roadPos.below();
                    int fillDepth = 0;
                    while (fillDepth < 4 && !level.getBlockState(below).isSolid()) {
                        level.setBlock(below, fillState, 2);
                        below = below.below();
                        fillDepth++;
                    }
                }
            }
        }
    }

    private static int findRoadBaseY(ServerLevel level, int worldX, int worldZ) {
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ);
        int minY = level.getMinBuildHeight();
        int y = surfaceY - 1;

        while (y > minY) {
            BlockState state = level.getBlockState(new BlockPos(worldX, y, worldZ));
            if (isValidGround(state)) {
                return y;
            }
            y--;
        }
        return minY;
    }

    private static boolean isValidGround(BlockState state) {
        if (!state.isSolid()) return false;
        return !state.is(BlockTags.LOGS) && !state.is(BlockTags.LEAVES);
    }

    private static void clearObstacles(ServerLevel level, BlockPos roadPos, BlockState airState, int radius, int height) {
        int baseX = roadPos.getX();
        int baseY = roadPos.getY();
        int baseZ = roadPos.getZ();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int i = 1; i <= height; i++) {
                    BlockPos target = new BlockPos(baseX + dx, baseY + i, baseZ + dz);
                    BlockState state = level.getBlockState(target);
                    if (!state.isAir() && !state.is(Blocks.BEDROCK)) {
                        level.setBlock(target, airState, 2);
                    }
                }
            }
        }
    }

    private record RoadTask(ServerLevel level, ChunkPos chunkPos, String cityId, long chunkKey) {}
    // Reuse VoronoiComputer packed key logic
    private static long packBlock(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }
}





