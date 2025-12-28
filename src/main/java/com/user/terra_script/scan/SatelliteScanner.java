package com.user.terra_script.scan;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.chunk.ChunkGenerator;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;


public class SatelliteScanner {
    public static final int INTERVAL =  100;
    /**
     * 异步扫描地形 (以 0,0 为中心)
     * @param level 服务端主世界
     * @param chunkRadius 扫描半径 (区块单位)
     * @param targetResolution 目标分辨率 (网格宽/高)
     */
    public static CompletableFuture<ScanPixel[][]> scanAsync(ServerLevel level, int chunkRadius, int targetResolution) {
        System.out.println("[Scanner] Starting in-game scan... Seed: " + level.getSeed());
        // 使用 supplyAsync 在后台线程执行，防止卡死主线程
        return CompletableFuture.supplyAsync(() -> runScan(level, chunkRadius, targetResolution));
    }

    private static ScanPixel[][] runScan(ServerLevel level, int chunkRadius, int targetResolution) {
        long startTime = System.currentTimeMillis();

        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();

        // 计算扫描参数
        int worldRadiusBlocks = chunkRadius * 16;
        int totalWidth = worldRadiusBlocks * 2;
        // 确保 step 至少为 1
        int step = Math.max(1, totalWidth / targetResolution);
        int gridSize = totalWidth / step;

        System.out.println("[Scanner] Grid Size: " + gridSize + "x" + gridSize + " (Step: " + step + ")");

        ScanPixel[][] map = new ScanPixel[gridSize][gridSize];

        // 用于进度显示
        int totalPoints = gridSize * gridSize;
        int processed = 0;
        int lastLogPercent = 0;

        for (int i = 0; i < gridSize; i++) {
            for (int j = 0; j < gridSize; j++) {
                // 计算当前采样点的世界坐标 (以 0,0 为中心)
                int x = (i * step) - worldRadiusBlocks;
                int z = (j * step) - worldRadiusBlocks;

                try {
                    // 1. 获取物理高度
                    // 使用 level 作为 HeightAccessor 是安全的，因为它只读取世界高度限制配置
                    int height = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);

                    // 2. 获取生物群系 (注意 Quart 坐标转换: x >> 2)
                    Holder<Biome> biomeHolder = generator.getBiomeSource().getNoiseBiome(x >> 2, height >> 2, z >> 2, randomState.sampler());
                    String biomeId = biomeHolder.unwrapKey().map(k -> k.location().toString()).orElse("unknown");

                    // 3. 存储像素点 (这里假设海平面是 63，RTF 可能有变动，但通常通用)
                    map[i][j] = new ScanPixel(x, z, height, biomeId, height > 63);
                } catch (Exception e) {
                    // 容错处理
                    map[i][j] = new ScanPixel(x, z, 0, "error", false);
                    if (processed < 5) e.printStackTrace(); // 只打印前几个错误
                }

                // --- 进度日志 (每 10% 打印一次) ---
                processed++;
                int percent = (int)((processed / (float)totalPoints) * INTERVAL);
                if (percent > lastLogPercent && percent % 10 == 0) {
                    System.out.println("[Scanner] Progress: " + percent + "%");
                    lastLogPercent = percent;
                }
            }
        }

        System.out.println("[Scanner] Scan finished in " + (System.currentTimeMillis() - startTime) + "ms");
        return map;
    }

    /**
     * 局部高精度扫描
     * @param startX 世界坐标 X 起点
     * @param startZ 世界坐标 Z 起点
     * @param width  扫描宽度
     * @param height 扫描高度
     * @param step   采样步长 (建议为 1 或 2)
     */
    public static CompletableFuture<ScanPixel[][]> scanRegionAsync(ServerLevel level, int startX, int startZ, int width, int height, int step) {
        System.out.println("[Scanner] Starting LOCAL scan: [" + startX + "," + startZ + "] Size: " + width + "x" + height + " Step: " + step);
        return CompletableFuture.supplyAsync(() -> runRegionScanParallel(level, startX, startZ, width, height, step));
    }

    private static ScanPixel[][] runRegionScanParallel(ServerLevel level, int startX, int startZ, int width, int height, int step) {
        long startTime = System.currentTimeMillis();

        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();

        int gridW = width / step;
        int gridH = height / step;
        if (gridW <= 0) gridW = 1;
        if (gridH <= 0) gridH = 1;

        System.out.println("[Scanner] Target Grid: " + gridW + "x" + gridH + " (Parallel Execution)");

        ScanPixel[][] map = new ScanPixel[gridW][gridH];

        // 进度统计 (线程安全)
        AtomicInteger processed = new AtomicInteger(0);
        int totalPoints = gridW * gridH;
        AtomicInteger lastLogPercent = new AtomicInteger(0);
        Set<String> threadNames = ConcurrentHashMap.newKeySet();

        // --- 并行计算核心 ---
        int finalGridH = gridH;
        IntStream.range(0, gridW).parallel().forEach(i -> {
            for (int j = 0; j < finalGridH; j++) {
                int x = startX + (i * step);
                int z = startZ + (j * step);
                threadNames.add(Thread.currentThread().getName());
                try {
                    // getBaseHeight 是纯数学计算，通常是线程安全的
                    int h = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
                    // 局部扫描为了速度，biomeId 设为 unknown
                    map[i][j] = new ScanPixel(x, z, h, "unknown", h > 63);
                } catch (Exception e) {
                    map[i][j] = new ScanPixel(x, z, 0, "error", false);
                }

                // 进度日志 (减少锁竞争，每隔一定数量检查一次)
                int current = processed.incrementAndGet();
                if (totalPoints > 5000 && current % (totalPoints / 20) == 0) { // 每 5%
                    int p = (int)((current * 100.0f) / totalPoints);
                    int last = lastLogPercent.get();
                    if (p > last && lastLogPercent.compareAndSet(last, p)) {
                        System.out.println("[Scanner] Parallel Progress: " + p + "%");
                    }
                }
            }
        });

        System.out.println("[Scanner] Local Scan finished in " + (System.currentTimeMillis() - startTime) + "ms");
        System.out.println("[Scanner Debug] Threads used: " + threadNames);
        System.out.println("[Scanner Debug] Thread count: " + threadNames.size());
        return map;
    }
}