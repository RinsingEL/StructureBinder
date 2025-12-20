package com.user.terra_script.scan;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import java.util.concurrent.CompletableFuture;
public class SatelliteScanner {
    public static CompletableFuture<ScanPixel[][]> scanAsync(WorldCreationContext context, long seed, int chunkRadius, int pixelSize) {
        System.out.println("[Scanner] Starting async scan... Seed: " + seed);
        return CompletableFuture.supplyAsync(() -> {
            try {
                return runScan(context, seed, chunkRadius, pixelSize);
            } catch (Exception e) {
                e.printStackTrace(); // 打印错误堆栈
                throw new RuntimeException(e);
            }
        });
    }

    private static ScanPixel[][] runScan(WorldCreationContext context, long seed, int chunkRadius, int targetResolution) {
        long startTime = System.currentTimeMillis();
        System.out.println("[Scanner] Fetching generator...");

        ChunkGenerator generator = context.selectedDimensions().dimensions().get(LevelStem.OVERWORLD).generator();

        if (!(generator instanceof NoiseBasedChunkGenerator noiseGen)) {
            System.err.println("[Scanner] Error: Not a NoiseBasedChunkGenerator!");
            return new ScanPixel[0][0];
        }

        System.out.println("[Scanner] Building RandomState...");
        Holder<NoiseGeneratorSettings> settings = noiseGen.generatorSettings();

        RandomState randomState = RandomState.create(
                settings.value(),
                context.worldgenLoadContext().lookupOrThrow(Registries.NOISE),
                seed
        );

        LevelHeightAccessor heightAccessor = new LevelHeightAccessor() {
            @Override public int getHeight() { return settings.value().noiseSettings().height(); }
            @Override public int getMinBuildHeight() { return settings.value().noiseSettings().minY(); }
        };

        int worldRadiusBlocks = chunkRadius * 16;
        int totalWidth = worldRadiusBlocks * 2;
        int step = Math.max(1, totalWidth / targetResolution);
        int gridSize = totalWidth / step;

        System.out.println("[Scanner] Grid Size: " + gridSize + "x" + gridSize + " (Step: " + step + ")");
        System.out.println("[Scanner] Total points to calculate: " + (gridSize * gridSize));

        ScanPixel[][] map = new ScanPixel[gridSize][gridSize];

        // 进度计数器
        int totalPoints = gridSize * gridSize;
        int processed = 0;
        int lastLogPercent = 0;

        for (int i = 0; i < gridSize; i++) {
            for (int j = 0; j < gridSize; j++) {
                int x = (i * step) - worldRadiusBlocks;
                int z = (j * step) - worldRadiusBlocks;

                try {
                    // 核心计算
                    int height = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, heightAccessor, randomState);

                    // 获取群系 (使用 x>>2 Quart 坐标)
                    Holder<Biome> biomeHolder = generator.getBiomeSource().getNoiseBiome(x >> 2, height >> 2, z >> 2, randomState.sampler());
                    String biomeId = biomeHolder.unwrapKey().map(k -> k.location().toString()).orElse("unknown");

                    map[i][j] = new ScanPixel(x, z, height, biomeId, height > 63);
                } catch (Exception e) {
                    // 如果某个点报错，打印并填入默认值，防止整个扫描中断
                    if (processed < 5) e.printStackTrace(); // 只打印前几个错误，防止刷屏
                    map[i][j] = new ScanPixel(x, z, 0, "error", false);
                }

                // --- 进度日志 ---
                processed++;
                int percent = (int)((processed / (float)totalPoints) * 100);
                if (percent > lastLogPercent && percent % 10 == 0) { // 每 10% 打印一次
                    System.out.println("[Scanner] Progress: " + percent + "% (" + processed + "/" + totalPoints + ")");
                    lastLogPercent = percent;
                }
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        System.out.println("[Scanner] Scan finished successfully in " + duration + "ms.");
        return map;
    }
}