package com.user.terra_script.world.city;

import com.user.terra_script.world.city.CityInstance;
import com.user.terra_script.world.city.CityManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
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

    private static final Set<Long> processedChunks = ConcurrentHashMap.newKeySet();
    private static final Set<Long> queuedChunks = ConcurrentHashMap.newKeySet();
    private static final Queue<RoadTask> pendingChunks = new ConcurrentLinkedQueue<>();
    private static final int CHUNKS_PER_TICK = 2; // �?tick 处理多少�?chunk

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.getServer().isRunning()) return;

        ChunkPos cp = event.getChunk().getPos();
        long chunkKey = cp.toLong();

        // 检查该 Chunk 是否属于任何城市
        String cityId = CityManager.get().getCityIdAt(chunkKey);
        if (cityId == null) return;

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

            // 再次检查世界状�?
            if (!task.level.getServer().isRunning() || !task.level.hasChunk(task.chunkPos.x, task.chunkPos.z)) {
                queuedChunks.remove(task.chunkKey);
                continue;
            }

            // 确保 Chunk 已完全加�?
            if (task.level.getChunk(task.chunkPos.x, task.chunkPos.z, ChunkStatus.FULL, false) == null) {
                pendingChunks.add(task); // 还没加载完，放回去下次再�?
                return; // 暂停处理后续任务
            }

            // 执行生成
            CityManager.get().ensureRoadsGenerated(task.cityId);
            generateRoadsInChunk(task.level, task.chunkPos, task.cityId);

            // 标记未保存，确保改动被写入磁�?
            task.level.getChunk(task.chunkPos.x, task.chunkPos.z).setUnsaved(true);

            queuedChunks.remove(task.chunkKey);
            processedChunks.add(task.chunkKey);
        }
    }

    private static void generateRoadsInChunk(ServerLevel level, ChunkPos cp, String cityId) {
        CityInstance city = CityManager.get().getCity(cityId);
        if (city == null || city.roadBlocks == null) return;

        BlockState roadState = Blocks.COBBLESTONE.defaultBlockState();
        BlockState slabState = Blocks.COBBLESTONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        BlockState airState = Blocks.AIR.defaultBlockState();
        BlockState fillState = Blocks.DIRT.defaultBlockState();

        int startX = cp.getMinBlockX();
        int startZ = cp.getMinBlockZ();

        var lightEngine = level.getLightEngine();

        // 定义清理高度
        int clearHeight = 6;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int worldX = startX + x;
                int worldZ = startZ + z;
                long blockKey = packBlock(worldX, worldZ);

                if (city.roadBlocks.contains(blockKey)) {
                    Integer plannedY = city.roadHeights != null ? city.roadHeights.get(blockKey) : null;
                    int groundY = findRoadBaseY(level, worldX, worldZ);
                    if (plannedY != null && (groundY - plannedY) > 10) {
                        continue;
                    }
                    int roadY = plannedY != null ? Math.max(plannedY, groundY) : groundY;
                    BlockPos roadPos = new BlockPos(worldX, roadY, worldZ);

                    // 1. 铺设路面 (Flag 3)
                    BlockState placeState = roadState;
                    if (city.roadSlabBlocks != null && city.roadSlabBlocks.contains(blockKey)) {
                        placeState = slabState;
                    }
                    level.setBlock(roadPos, placeState, 3);

                    // 2. 清理上方障碍�?
                    clearObstacles(level, roadPos, airState, 1, clearHeight);

                    // 3. 向下夯实
                    BlockPos below = roadPos.below();
                    int fillDepth = 0;
                    while (fillDepth < 4 && !level.getBlockState(below).isSolid()) {
                        level.setBlock(below, fillState, 3);
                        below = below.below();
                        fillDepth++;
                    }
                    updateLightingColumn(level, roadPos, 4);

                    // 4. 【核心修复】垂直链路光照刷�?
                    // 我们刚刚制造了一个“空气柱”，需要告诉引擎整条柱子的光照都变�?
                    // 从路面上一格开始，一直到清理高度的上方一�?
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            for (int dy = -4; dy <= clearHeight + 1; dy++) {
                                lightEngine.checkBlock(roadPos.offset(dx, dy, dz));
                            }
                        }
                    }
                }
            }
        }
    }

    private static int findRoadBaseY(ServerLevel level, int worldX, int worldZ) {
        // 使用 MOTION_BLOCKING_NO_LEAVES 忽略树叶，直接找到地面或树干顶端
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ);
        int minY = level.getMinBuildHeight();
        int y = surfaceY - 1;

        // 向下搜索直到找到固体地面 (避开树干)
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
        // 地基必须是固体，且不能是原木或树�?
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
                    // 只清除空气以外的方块，且不破坏基�?
                    if (!state.isAir() && !state.is(Blocks.BEDROCK)) {
                        level.setBlock(target, airState, 3); // Flag 3 触发光照更新
                    }
                }
            }
        }
    }

    
    private static void updateLightingColumn(ServerLevel level, BlockPos basePos, int height) {
        var lightEngine = level.getLightEngine();
        for (int dy = 0; dy <= height; dy++) {
            lightEngine.checkBlock(basePos.above(dy));
        }
    }

    public static void resetProcessing() {
        processedChunks.clear();
        queuedChunks.clear();
        pendingChunks.clear();
    }
    private record RoadTask(ServerLevel level, ChunkPos chunkPos, String cityId, long chunkKey) {}

    private static long packBlock(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }
}














