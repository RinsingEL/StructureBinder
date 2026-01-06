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

    // 【优化1】使用固定大小的线程池 (4线程)，防止抢占系统资源
    // 【优化2】使用 ThreadFactory 设置为守护线程 (Daemon)，保证游戏关闭时线程自动结束
    private static final ExecutorService SCAN_EXECUTOR = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r);
        t.setName("TerraScript-Scanner-" + t.getId());
        t.setDaemon(true);
        return t;
    });

    // 取消标记
    private static final AtomicBoolean isCancelled = new AtomicBoolean(false);

    /**
     * 紧急停止扫描 (在世界卸载时调用)
     */
    public static void stopScanning() {
        isCancelled.set(true);
        System.out.println("[Scanner] Stop signal received. Aborting tasks...");
    }

    public static CompletableFuture<ScanPixel[][]> scanAsync(ServerLevel level, int chunkRadius, int targetResolution) {
        isCancelled.set(false);
        System.out.println("[Scanner] Starting global scan... Seed: " + level.getSeed());
        return CompletableFuture.supplyAsync(() -> runScan(level, chunkRadius, targetResolution), SCAN_EXECUTOR);
    }

    public static CompletableFuture<ScanPixel[][]> scanRegionAsync(ServerLevel level, int startX, int startZ, int width, int height, int step) {
        isCancelled.set(false);
        System.out.println("[Scanner] Starting LOCAL scan...");
        return CompletableFuture.supplyAsync(() -> runRegionScan(level, startX, startZ, width, height, step), SCAN_EXECUTOR);
    }

    // --- 内部逻辑 ---

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
            // 【优化3】每行检查一次：如果取消或服务器停止，立即退出
            if (isCancelled.get() || !server.isRunning()) return null;

            for (int j = 0; j < gridSize; j++) {
                int x = (i * step) - worldRadiusBlocks;
                int z = (j * step) - worldRadiusBlocks;
                try {
                    int h = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
                    // 全图扫描可以尝试获取 Biome (如果太慢可注释掉)
                    Holder<Biome> biomeHolder = generator.getBiomeSource().getNoiseBiome(x >> 2, h >> 2, z >> 2, randomState.sampler());
                    String biomeId = biomeHolder.unwrapKey().map(k -> k.location().toString()).orElse("unknown");
                    map[i][j] = new ScanPixel(x, z, h, biomeId, h > 63);
                } catch (Exception e) {
                    map[i][j] = new ScanPixel(x, z, 0, "error", false);
                }

                processed++;
                if (totalPoints > 5000) {
                    int percent = (int)((processed * 100) / totalPoints);
                    if (percent > lastLogPercent && percent % 10 == 0) {
                        System.out.println("[Scanner] Global Progress: " + percent + "%");
                        lastLogPercent = percent;
                    }
                }
            }
        }
        System.out.println("[Scanner] Finished in " + (System.currentTimeMillis() - startTime) + "ms");
        return map;
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

        for (int i = 0; i < gridW; i++) {
            // 【优化3】安全退出检查
            if (isCancelled.get() || !server.isRunning()) return null;

            for (int j = 0; j < gridH; j++) {
                int x = startX + (i * step);
                int z = startZ + (j * step);
                try {
                    int h = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
                    // 局部扫描为了速度暂不取 Biome
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
        System.out.println("[Scanner] Local Finished in " + (System.currentTimeMillis() - startTime) + "ms");
        return map;
    }
}