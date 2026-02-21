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
    private static final int SCAN_POOL_SIZE = 8;
    private static final long PARALLEL_THRESHOLD_POINTS = 80_000L;
    private static final long HEARTBEAT_INTERVAL_MS = 60_000L;

    private static final ExecutorService SCAN_EXECUTOR = Executors.newFixedThreadPool(SCAN_POOL_SIZE, r -> {
        Thread t = new Thread(r);
        t.setName("TerraScript-Scanner-" + t.getId());
        t.setDaemon(true);
        return t;
    });

    private static final AtomicBoolean isCancelled = new AtomicBoolean(false);
    private static final AtomicBoolean scanInProgress = new AtomicBoolean(false);
    private static final AtomicLong heartbeatDone = new AtomicLong(0L);
    private static final AtomicLong heartbeatTotal = new AtomicLong(0L);
    private static final AtomicLong heartbeatStartMs = new AtomicLong(0L);
    private static final AtomicLong lastHeartbeatMs = new AtomicLong(0L);
    private static volatile String heartbeatLabel = "";

    public record ScanProgressSnapshot(
            boolean inProgress,
            String label,
            long done,
            long total,
            int percent,
            long elapsedMs
    ) {}

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

    public static int getScanExecutorSize() {
        return SCAN_POOL_SIZE;
    }

    public static int estimateRegionScanWorkers(int width, int height, int step) {
        int safeStep = Math.max(1, step);
        int gridW = Math.max(1, width / safeStep);
        int gridH = Math.max(1, height / safeStep);
        long totalPoints = (long) gridW * gridH;
        return pickWorkerCount(gridW, totalPoints);
    }

    public static ScanProgressSnapshot getProgressSnapshot() {
        long done = heartbeatDone.get();
        long total = heartbeatTotal.get();
        int percent = total <= 0L ? 0 : (int) Math.min(100L, (done * 100L) / total);
        long start = heartbeatStartMs.get();
        long elapsed = start <= 0L ? 0L : Math.max(0L, System.currentTimeMillis() - start);
        return new ScanProgressSnapshot(scanInProgress.get(), heartbeatLabel, done, total, percent, elapsed);
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
        beginProgress("Local", totalPoints);
        try {
            int workers = pickWorkerCount(gridW, totalPoints);
            AtomicInteger lastLogPercent = new AtomicInteger(0);
            if (workers <= 1) {
                long processed = 0;
                for (int i = 0; i < gridW; i++) {
                    if (isCancelled.get() || !server.isRunning()) return null;
                    for (int j = 0; j < gridH; j++) {
                        int x = startX + (i * step);
                        int z = startZ + (j * step);
                        map[i][j] = samplePixel(level, generator, randomState, x, z);
                        processed++;
                        reportProgress("Local", processed, totalPoints, lastLogPercent);
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
        } finally {
            finishProgress();
        }
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
        beginProgress("Global", totalPoints);
        try {
            int workers = pickWorkerCount(gridSize, totalPoints);
            AtomicInteger lastLogPercent = new AtomicInteger(0);
            if (workers <= 1) {
                long processed = 0;
                for (int i = 0; i < gridSize; i++) {
                    if (isCancelled.get() || !server.isRunning()) return null;
                    for (int j = 0; j < gridSize; j++) {
                        int x = (i * step) - worldRadiusBlocks;
                        int z = (j * step) - worldRadiusBlocks;
                        map[i][j] = samplePixel(level, generator, randomState, x, z);
                        processed++;
                        reportProgress("Global", processed, totalPoints, lastLogPercent);
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
        } finally {
            finishProgress();
        }
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
                        reportProgress(progressLabel, done, totalPoints, lastLoggedBucket);
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
        int maxByExecutor = Math.max(1, Math.min(SCAN_POOL_SIZE, maxByCpu));
        return Math.max(1, Math.min(gridW, maxByExecutor));
    }

    private static void beginProgress(String label, long total) {
        long now = System.currentTimeMillis();
        scanInProgress.set(true);
        heartbeatLabel = label;
        heartbeatDone.set(0L);
        heartbeatTotal.set(Math.max(1L, total));
        heartbeatStartMs.set(now);
        lastHeartbeatMs.set(now);
    }

    private static void finishProgress() {
        long total = heartbeatTotal.get();
        if (total > 0L) heartbeatDone.set(total);
        scanInProgress.set(false);
    }

    private static void reportProgress(String label, long done, long total, AtomicInteger lastLoggedBucket) {
        heartbeatDone.set(done);
        heartbeatTotal.set(Math.max(1L, total));

        if (total <= 5000L) return;
        int percent = (int) ((done * 100L) / total);
        int bucket = (percent / 10) * 10;
        if (bucket > 0) {
            int prev = lastLoggedBucket.get();
            while (bucket > prev) {
                if (lastLoggedBucket.compareAndSet(prev, bucket)) {
                    System.out.println("[Scanner] " + label + " Progress: " + bucket + "%");
                    break;
                }
                prev = lastLoggedBucket.get();
            }
        }

        long now = System.currentTimeMillis();
        long last = lastHeartbeatMs.get();
        if (now - last >= HEARTBEAT_INTERVAL_MS && lastHeartbeatMs.compareAndSet(last, now)) {
            System.out.println("[Scanner] " + label + " Heartbeat: " + percent + "% (" + done + "/" + total + ")");
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
