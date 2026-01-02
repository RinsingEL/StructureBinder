package com.user.terra_script.scan;

import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.chunk.ChunkGenerator;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class SatelliteScanner {

    // 1. 自定义线程池：限制最大并发数为 4，防止占满 CPU 导致主线程卡死
    private static final ExecutorService SCAN_EXECUTOR = Executors.newFixedThreadPool(4);

    // 2. 取消标记：用于紧急停止任务
    private static final AtomicBoolean isCancelled = new AtomicBoolean(false);

    /**
     * 紧急停止所有扫描任务 (在世界卸载时调用)
     */
    public static void stopScanning() {
        isCancelled.set(true);
        System.out.println("[Scanner] Stop signal received. Aborting tasks...");
    }

    /**
     * 异步扫描地形 (全局)
     */
    public static CompletableFuture<ScanPixel[][]> scanAsync(ServerLevel level, int chunkRadius, int targetResolution) {
        // 重置取消标记
        isCancelled.set(false);
        System.out.println("[Scanner] Starting global scan... Seed: " + level.getSeed());

        // 使用自定义线程池提交任务
        return CompletableFuture.supplyAsync(() -> runScan(level, chunkRadius, targetResolution), SCAN_EXECUTOR);
    }

    private static ScanPixel[][] runScan(ServerLevel level, int chunkRadius, int targetResolution) {
        long startTime = System.currentTimeMillis();
        MinecraftServer server = level.getServer();

        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();

        int worldRadiusBlocks = chunkRadius * 16;
        int totalWidth = worldRadiusBlocks * 2;
        int step = Math.max(1, totalWidth / targetResolution);
        int gridSize = totalWidth / step;

        System.out.println("[Scanner] Grid Size: " + gridSize + "x" + gridSize + " (Step: " + step + ")");

        ScanPixel[][] map = new ScanPixel[gridSize][gridSize];

        long totalPoints = (long) gridSize * gridSize;
        long processed = 0;
        int lastLogPercent = 0;

        for (int i = 0; i < gridSize; i++) {

            // --- 安全检查点 1 ---
            // 检查是否取消，或者服务器是否已关闭
            if (isCancelled.get() || !server.isRunning()) {
                return null;
            }

            for (int j = 0; j < gridSize; j++) {
                int x = (i * step) - worldRadiusBlocks;
                int z = (j * step) - worldRadiusBlocks;

                try {
                    int height = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);

                    // 获取群系 (为了速度，全图扫描也可以选择跳过)
                    Holder<Biome> biomeHolder = generator.getBiomeSource().getNoiseBiome(x >> 2, height >> 2, z >> 2, randomState.sampler());
                    String biomeId = biomeHolder.unwrapKey().map(k -> k.location().toString()).orElse("unknown");

                    map[i][j] = new ScanPixel(x, z, height, biomeId, height > 63);
                } catch (Exception e) {
                    map[i][j] = new ScanPixel(x, z, 0, "error", false);
                }

                processed++;
                // 简单的进度计算
                if (totalPoints > 5000) {
                    int percent = (int)((processed * 100) / totalPoints);
                    if (percent > lastLogPercent && percent % 10 == 0) {
                        System.out.println("[Scanner] Global Progress: " + percent + "%");
                        lastLogPercent = percent;
                    }
                }
            }
        }

        System.out.println("[Scanner] Scan finished in " + (System.currentTimeMillis() - startTime) + "ms");
        return map;
    }

    /**
     * 局部高精度扫描
     */
    public static CompletableFuture<ScanPixel[][]> scanRegionAsync(ServerLevel level, int startX, int startZ, int width, int height, int step) {
        isCancelled.set(false);
        System.out.println("[Scanner] Starting LOCAL scan: [" + startX + "," + startZ + "] Size: " + width + "x" + height + " Step: " + step);
        // 使用自定义线程池
        return CompletableFuture.supplyAsync(() -> runRegionScan(level, startX, startZ, width, height, step), SCAN_EXECUTOR);
    }

    private static ScanPixel[][] runRegionScan(ServerLevel level, int startX, int startZ, int width, int height, int step) {
        long startTime = System.currentTimeMillis();
        MinecraftServer server = level.getServer();

        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();

        int gridW = width / step;
        int gridH = height / step;
        if (gridW <= 0) gridW = 1;
        if (gridH <= 0) gridH = 1;

        System.out.println("[Scanner] Target Grid: " + gridW + "x" + gridH);

        ScanPixel[][] map = new ScanPixel[gridW][gridH];

        long totalPoints = (long) gridW * gridH;
        long processed = 0;
        int lastLogPercent = 0;

        // 这里改回普通循环，不再使用 IntStream.parallel()
        // 因为 parallel() 会使用公共线程池，容易导致死锁且无法响应 isCancelled
        // 既然我们在外部已经是异步线程了，内部直接跑循环即可，或者手动拆分任务提交给 SCAN_EXECUTOR
        // 为了稳定性，单线程跑这个异步任务是最安全的

        for (int i = 0; i < gridW; i++) {

            // --- 安全检查点 2 ---
            if (isCancelled.get() || !server.isRunning()) {
                System.out.println("[Scanner] Local scan aborted.");
                return null;
            }

            for (int j = 0; j < gridH; j++) {
                int x = startX + (i * step);
                int z = startZ + (j * step);

                try {
                    int h = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
                    // 局部扫描暂不计算 Biome
                    map[i][j] = new ScanPixel(x, z, h, "unknown", h > 63);
                } catch (Exception e) {
                    map[i][j] = new ScanPixel(x, z, 0, "error", false);
                }

                processed++;
                if (totalPoints > 5000) {
                    int percent = (int)((processed * 100) / totalPoints);
                    if (percent > lastLogPercent && percent % 10 == 0) {
                        System.out.println("[Scanner] Local Progress: " + percent + "%");
                        lastLogPercent = percent;
                    }
                }
            }
        }

        System.out.println("[Scanner] Local Scan finished in " + (System.currentTimeMillis() - startTime) + "ms");
        return map;
    }
}