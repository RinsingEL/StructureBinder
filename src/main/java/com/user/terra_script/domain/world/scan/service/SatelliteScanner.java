package com.user.terra_script.domain.world.scan.service;

import com.user.terra_script.domain.world.scan.ScanPixel;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.chunk.ChunkGenerator;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class SatelliteScanner {
    private static final int MAX_SCAN_WORKERS = 12;
    private static final long PARALLEL_THRESHOLD_POINTS = 80_000L;

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
        int workers = pickWorkerCount(gridW, totalPoints);
        if (workers <= 1) {
            long processed = 0;
            int lastLogPercent = 0;
            for (int i = 0; i < gridW; i++) {
                if (isCancelled.get() || !server.isRunning()) return null;
                for (int j = 0; j < gridH; j++) {
                    int x = startX + (i * step);
                    int z = startZ + (j * step);
                    map[i][j] = samplePixel(level, generator, randomState, x, z);
                    processed++;
                    if (totalPoints > 5000) {
                        int percent = (int) ((processed * 100) / totalPoints);
                        if (percent > lastLogPercent && percent % 10 == 0) {
                            System.out.println("[Scanner] Local Progress: " + percent + "%");
                            lastLogPercent = percent;
                        }
                    }
                }
            }
        } else {
            parallelFill(
                    map, gridH, workers, totalPoints, server, "Local",
                    i -> startX + (i * step),
                    j -> startZ + (j * step),
                    level, generator, randomState
            );
            if (isCancelled.get() || !server.isRunning()) return null;
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
        int workers = pickWorkerCount(gridSize, totalPoints);
        if (workers <= 1) {
            long processed = 0;
            int lastLogPercent = 0;
            for (int i = 0; i < gridSize; i++) {
                if (isCancelled.get() || !server.isRunning()) return null;
                for (int j = 0; j < gridSize; j++) {
                    int x = (i * step) - worldRadiusBlocks;
                    int z = (j * step) - worldRadiusBlocks;
                    map[i][j] = samplePixel(level, generator, randomState, x, z);
                    processed++;
                    if (totalPoints > 5000) {
                        int percent = (int) ((processed * 100) / totalPoints);
                        if (percent > lastLogPercent && percent % 10 == 0) {
                            System.out.println("[Scanner] Global Progress: " + percent + "%");
                            lastLogPercent = percent;
                        }
                    }
                }
            }
        } else {
            parallelFill(
                    map, gridSize, workers, totalPoints, server, "Global",
                    i -> (i * step) - worldRadiusBlocks,
                    j -> (j * step) - worldRadiusBlocks,
                    level, generator, randomState
            );
            if (isCancelled.get() || !server.isRunning()) return null;
        }
        System.out.println("[Scanner] Global Scan finished in " + (System.currentTimeMillis() - startTime) + "ms");
        return map;
    }

    private interface CoordMapper {
        int toWorld(int gridIndex);
    }

    private static void parallelFill(
            ScanPixel[][] map,
            int gridH,
            int workers,
            long totalPoints,
            MinecraftServer server,
            String progressLabel,
            CoordMapper xMapper,
            CoordMapper zMapper,
            ServerLevel level,
            ChunkGenerator generator,
            RandomState randomState
    ) {
        int gridW = map.length;
        int stripe = Math.max(1, (int) Math.ceil(gridW / (double) workers));
        AtomicLong processed = new AtomicLong(0L);
        AtomicInteger lastLoggedBucket = new AtomicInteger(0);
        List<CompletableFuture<Void>> jobs = new ArrayList<>();

        for (int from = 0; from < gridW; from += stripe) {
            int start = from;
            int end = Math.min(gridW, from + stripe);
            jobs.add(CompletableFuture.runAsync(() -> {
                for (int i = start; i < end; i++) {
                    if (isCancelled.get() || !server.isRunning()) return;
                    for (int j = 0; j < gridH; j++) {
                        int x = xMapper.toWorld(i);
                        int z = zMapper.toWorld(j);
                        map[i][j] = samplePixel(level, generator, randomState, x, z);
                        long done = processed.incrementAndGet();
                        logProgressEvery10(progressLabel, done, totalPoints, lastLoggedBucket);
                    }
                }
            }, SCAN_EXECUTOR));
        }

        CompletableFuture.allOf(jobs.toArray(new CompletableFuture[0])).join();
    }

    private static int pickWorkerCount(int gridW, long totalPoints) {
        if (totalPoints < PARALLEL_THRESHOLD_POINTS) return 1;
        int cpu = Runtime.getRuntime().availableProcessors();
        int maxByCpu = Math.max(1, Math.min(MAX_SCAN_WORKERS, cpu - 1));
        return Math.max(1, Math.min(gridW, maxByCpu));
    }

    private static void logProgressEvery10(String label, long done, long total, AtomicInteger lastLoggedBucket) {
        if (total <= 5000L) return;
        int percent = (int) ((done * 100L) / total);
        int bucket = (percent / 10) * 10;
        if (bucket <= 0) return;

        int prev = lastLoggedBucket.get();
        while (bucket > prev) {
            if (lastLoggedBucket.compareAndSet(prev, bucket)) {
                System.out.println("[Scanner] " + label + " Progress: " + bucket + "%");
                return;
            }
            prev = lastLoggedBucket.get();
        }
    }

    private static ScanPixel samplePixel(
            ServerLevel level,
            ChunkGenerator generator,
            RandomState randomState,
            int x,
            int z
    ) {
        try {
            int h = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
            Holder<Biome> biomeHolder = generator.getBiomeSource()
                    .getNoiseBiome(x >> 2, h >> 2, z >> 2, randomState.sampler());
            String biomeId = biomeHolder.unwrapKey().map(k -> k.location().toString()).orElse("minecraft:plains");
            float temp = biomeHolder.value().getBaseTemperature();
            return new ScanPixel(x, z, h, biomeId, h > 63, temp);
        } catch (Exception e) {
            return new ScanPixel(x, z, 0, "error", false, 0.0f);
        }
    }
}
