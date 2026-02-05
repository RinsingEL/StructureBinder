package com.user.terra_script.domain.world.scan.service;

import com.user.terra_script.domain.world.scan.ScanPixel;
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

    private static final ExecutorService SCAN_EXECUTOR = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r);
        t.setName("TerraScript-Scanner-" + t.getId());
        t.setDaemon(true);
        return t;
    });

    private static final AtomicBoolean isCancelled = new AtomicBoolean(false);

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

    // --- 内部逻辑 (runScan 和 runRegionScan 逻辑高度相似，这里重点展示 runRegionScan 的修改) ---

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
            if (isCancelled.get() || !server.isRunning()) return null;

            for (int j = 0; j < gridH; j++) {
                int x = startX + (i * step);
                int z = startZ + (j * step);
                try {
                    // 1. 获取高度
                    int h = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);

                    // 2. 【新增】获取 Biome 信息
                    // Quart 坐标转换：x >> 2
                    Holder<Biome> biomeHolder = generator.getBiomeSource().getNoiseBiome(x >> 2, h >> 2, z >> 2, randomState.sampler());
                    String biomeId = biomeHolder.unwrapKey().map(k -> k.location().toString()).orElse("minecraft:plains");

                    // 3. 【新增】获取温度
                    // getBaseTemperature() 获取的是基准温度，不受高度带来的寒冷影响，适合宏观气候判断
                    float temp = biomeHolder.value().getBaseTemperature();

                    // 4. 存入 Pixel (包含温度)
                    map[i][j] = new ScanPixel(x, z, h, biomeId, h > 63, temp);

                } catch (Exception e) {
                    map[i][j] = new ScanPixel(x, z, 0, "error", false, 0.0f);
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

    private static ScanPixel[][] runScan(ServerLevel level, int chunkRadius, int targetResolution) {
        long startTime = System.currentTimeMillis();
        // ... 初始化 ...
        int worldRadiusBlocks = chunkRadius * 16;
        int totalWidth = worldRadiusBlocks * 2;
        int step = Math.max(1, totalWidth / targetResolution);
        int gridSize = totalWidth / step;

        System.out.println("[Scanner] Grid Size: " + gridSize + "x" + gridSize);
        ScanPixel[][] map = new ScanPixel[gridSize][gridSize];

        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();
        MinecraftServer server = level.getServer();

        long totalPoints = (long) gridSize * gridSize;
        long processed = 0;
        int lastLogPercent = 0;

        for (int i = 0; i < gridSize; i++) {
            if (isCancelled.get() || !server.isRunning()) return null;
            for (int j = 0; j < gridSize; j++) {
                int x = (i * step) - worldRadiusBlocks;
                int z = (j * step) - worldRadiusBlocks;
                try {
                    int h = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
                    Holder<Biome> biomeHolder = generator.getBiomeSource().getNoiseBiome(x >> 2, h >> 2, z >> 2, randomState.sampler());
                    String biomeId = biomeHolder.unwrapKey().map(k -> k.location().toString()).orElse("minecraft:plains");
                    float temp = biomeHolder.value().getBaseTemperature();

                    map[i][j] = new ScanPixel(x, z, h, biomeId, h > 63, temp);
                } catch (Exception e) {
                    map[i][j] = new ScanPixel(x, z, 0, "error", false, 0.0f);
                }
                // ... 进度 ...
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
        System.out.println("[Scanner] Global Scan finished in " + (System.currentTimeMillis() - startTime) + "ms");
        return map;
    }
}
