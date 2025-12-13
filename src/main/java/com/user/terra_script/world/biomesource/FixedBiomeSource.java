package com.user.terra_script.world.biomesource;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.user.terra_script.config.WorldProjectData;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@SuppressWarnings("removal")
public class FixedBiomeSource extends BiomeSource {

    public static final Codec<FixedBiomeSource> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    RegistryOps.retrieveGetter(Registries.BIOME)
            ).apply(instance, FixedBiomeSource::new)
    );

    private final HolderGetter<Biome> biomeRegistry;

    // 运行时缓存：String ID -> Biome Holder
    // 避免每次生成都去查 Registry，虽然 Registry 也是一种 Map，但自己缓存更可控
    private final Map<String, Holder<Biome>> biomeCache = new ConcurrentHashMap<>();

    public FixedBiomeSource(HolderGetter<Biome> biomeRegistry) {
        super();
        this.biomeRegistry = biomeRegistry;
        WorldProjectData.serverBiomeRegistry = biomeRegistry;

        // 初始化时加载配置 (如果是服务端重启，这会生效)
        // 注意：如果是单人游戏，这里读取的是本地客户端修改过的 config
        WorldProjectData.load();
    }

    @Override
    protected Codec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        // 这里理论上应该返回配置里用到的所有群系
        // 简单起见，我们返回注册表里的常见群系，或者不实现这个优化（可能会影响 /locate biome 命令）
        return Stream.of(
                getBiomeHolder("minecraft:plains"),
                getBiomeHolder("minecraft:ocean")
        );
    }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler climateSampler) {
        // Quart 坐标 -> 区块坐标
        int chunkX = x >> 2;
        int chunkZ = z >> 2;

        WorldProjectData data = WorldProjectData.get();
        if (data == null) return getBiomeHolder("minecraft:ocean");
//
//        // 1. 遍历所有大陆，检查当前区块是否属于该大陆
//        for (WorldProjectData.Continent continent : data.continents) {
//            if (continent.containsChunk(chunkX, chunkZ)) {
//                return getContinentBiome(continent, chunkX, chunkZ);
//            }
//        }

        // 默认群系 (海洋)
        return getBiomeHolder("minecraft:plains");
    }

    private Holder<Biome> getContinentBiome(WorldProjectData.Continent continent, int cx, int cz) {
        // 2. 检查是否有固定群系配置
        long posKey = WorldProjectData.ChunkPos.asLong(cx, cz);
        if (continent.fixedBiomeChunks.containsKey(posKey)) {
            String fixedId = continent.fixedBiomeChunks.get(posKey);
            return getBiomeHolder(fixedId);
        }

        // 3. 按照权重随机生成
        // 使用伪随机，保证同一个位置每次生成结果一致
        long seed = (long) cx * 341873128712L + (long) cz * 132897987541L;
        Random random = new Random(seed);

        return getWeightedBiome(continent.biomeWeights, random);
    }

    private Holder<Biome> getWeightedBiome(Map<String, Integer> weights, Random random) {
        if (weights.isEmpty()) return getBiomeHolder("minecraft:plains");

        int totalWeight = 0;
        for (int w : weights.values()) totalWeight += w;

        if (totalWeight <= 0) return getBiomeHolder("minecraft:plains");

        int r = random.nextInt(totalWeight);
        int current = 0;

        for (Map.Entry<String, Integer> entry : weights.entrySet()) {
            current += entry.getValue();
            if (r < current) {
                return getBiomeHolder(entry.getKey());
            }
        }

        // Fallback
        return getBiomeHolder("minecraft:plains");
    }

    private Holder<Biome> getBiomeHolder(String id) {
        return biomeCache.computeIfAbsent(id, k -> {
            ResourceLocation rl = new ResourceLocation(k);
            // 尝试获取，如果不存在则返回 Plains 防止崩溃
            return biomeRegistry.get(ResourceKey.create(Registries.BIOME, rl))
                    .orElseGet(() -> biomeRegistry.getOrThrow(Biomes.PLAINS));
        });
    }
}