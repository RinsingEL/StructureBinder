package com.user.terra_script.world.biomesource;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;

import java.util.stream.Stream;

public class FixedBiomeSource extends BiomeSource {

    public static final Codec<FixedBiomeSource> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    RegistryOps.retrieveGetter(Registries.BIOME)
            ).apply(instance, FixedBiomeSource::new)
    );

    private final HolderGetter<Biome> biomeRegistry;

    public FixedBiomeSource(HolderGetter<Biome> biomeRegistry) {
        super();
        this.biomeRegistry = biomeRegistry;
    }

    @Override
    protected Codec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        // 注册所有可能生成的群系，防止结构生成错误
        return Stream.of(
                biomeRegistry.getOrThrow(Biomes.OCEAN),
                biomeRegistry.getOrThrow(Biomes.SNOWY_PLAINS),
                biomeRegistry.getOrThrow(Biomes.ICE_SPIKES),
                biomeRegistry.getOrThrow(Biomes.FROZEN_RIVER),
                biomeRegistry.getOrThrow(Biomes.JUNGLE),
                biomeRegistry.getOrThrow(Biomes.SPARSE_JUNGLE),
                biomeRegistry.getOrThrow(Biomes.PLAINS),
                biomeRegistry.getOrThrow(Biomes.FOREST)
        );
    }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler climateSampler) {
        // 坐标转换：Quart (4 blocks) -> Chunk (16 blocks)
        int chunkX = x >> 2;
        int chunkZ = z >> 2;

        // --- 1. 北方艾尔大陆 (North Aier) ---
        // Center: 0, -5000 | Radius: 2500
        if (isInsideContinent(chunkX, chunkZ, 0, -5000, 2500)) {
            return getAierContinentBiome(chunkX, chunkZ);
        }

        // --- 2. 东方荒野 (East Wilds) ---
        // Center: 6000, 2000 | Radius: 1500
        if (isInsideContinent(chunkX, chunkZ, 6000, 2000, 1500)) {
            return getJungleBiome(chunkX, chunkZ);
        }

        // --- 3. 出生点平原 (Spawn) ---
        // Center: 0, 0 | Radius: 1000
        if (isInsideContinent(chunkX, chunkZ, 0, 0, 1000)) {
            return biomeRegistry.getOrThrow(Biomes.PLAINS);
        }

        // 默认：无尽之海
        return biomeRegistry.getOrThrow(Biomes.OCEAN);
    }

    private boolean isInsideContinent(int x, int z, int centerX, int centerZ, int radius) {
        // 使用 long 防止坐标溢出
        long dx = x - centerX;
        long dz = z - centerZ;
        return (dx * dx + dz * dz) < ((long) radius * radius);
    }

    // --- 微观细节层：艾尔大陆 ---
    private Holder<Biome> getAierContinentBiome(int chunkX, int chunkZ) {
        // 简单的伪随机噪声模拟
        double noise = Math.sin(chunkX * 0.05) + Math.cos(chunkZ * 0.05);

        if (noise > 1.0) {
            return biomeRegistry.getOrThrow(Biomes.ICE_SPIKES);
        } else if (noise < -0.5) {
            return biomeRegistry.getOrThrow(Biomes.FROZEN_RIVER);
        } else {
            return biomeRegistry.getOrThrow(Biomes.SNOWY_PLAINS);
        }
    }

    // --- 微观细节层：丛林 ---
    private Holder<Biome> getJungleBiome(int chunkX, int chunkZ) {
        double noise = Math.sin(chunkX * 0.1) * Math.cos(chunkZ * 0.1);
        return noise > 0 ? biomeRegistry.getOrThrow(Biomes.JUNGLE) : biomeRegistry.getOrThrow(Biomes.SPARSE_JUNGLE);
    }
}