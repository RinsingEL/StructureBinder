package com.rinsing.geomantia.systems.gis.adapter.minecraft;

import com.rinsing.geomantia.systems.gis.application.sample.AtlasSampler;
import com.rinsing.geomantia.systems.gis.application.sample.SampledCell;
import com.rinsing.geomantia.systems.gis.domain.cell.AtlasCell;
import com.rinsing.geomantia.systems.gis.domain.cell.SampleSource;
import com.rinsing.geomantia.systems.gis.domain.cell.SurfaceType;
import com.rinsing.geomantia.systems.gis.application.refresh.SampleMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.server.level.ServerLevel;

import java.util.Objects;

public final class MinecraftPriorAtlasSampler implements AtlasSampler {
    private final ServerLevel level;

    public MinecraftPriorAtlasSampler(ServerLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    @Override
    public SampledCell sample(AtlasCell cell, SampleMode sampleMode) {
        int x = cell.blockMinX();
        int z = cell.blockMinZ();
        int height = baseHeight(x, z);
        NoiseColumn column = level.getChunkSource().getGenerator().getBaseColumn(
                x,
                z,
                level,
                level.getChunkSource().randomState()
        );
        int seaLevel = level.getSeaLevel();
        int waterFloor = level.getChunkSource().getGenerator().getBaseHeight(
                x,
                z,
                Heightmap.Types.OCEAN_FLOOR_WG,
                level,
                level.getChunkSource().randomState()
        );
        boolean water = isWaterColumn(column, height, seaLevel);
        double waterDepth = water ? Math.max(0, seaLevel - waterFloor) : 0.0;
        SurfaceType surfaceType = water ? SurfaceType.WATER : surfaceType(column.getBlock(height - 1));
        return new SampledCell(SampleSource.PRIOR, height, surfaceType,
                biomeIdAt(x, z, height), water, waterDepth);
    }

    @Override
    public SampledCell sampleFeature(AtlasCell cell, SampleMode sampleMode) {
        int x = cell.blockMinX();
        int z = cell.blockMinZ();
        int height = baseHeight(x, z);
        boolean water = height <= level.getSeaLevel() + 1;
        return new SampledCell(SampleSource.PRIOR, height, water ? SurfaceType.WATER : SurfaceType.UNKNOWN,
                biomeIdAt(x, z, height), water, 0.0);
    }

    @Override
    public double sampleElevation(AtlasCell cell, SampleMode sampleMode) {
        return baseHeight(cell.blockMinX(), cell.blockMinZ());
    }

    private int baseHeight(int x, int z) {
        return level.getChunkSource().getGenerator().getBaseHeight(
                x,
                z,
                Heightmap.Types.WORLD_SURFACE_WG,
                level,
                level.getChunkSource().randomState()
        );
    }

    private String biomeIdAt(int x, int z, int height) {
        Holder<Biome> biome = level.getUncachedNoiseBiome(
                Math.floorDiv(x, 4),
                Math.floorDiv(Math.max(level.getMinBuildHeight(), height), 4),
                Math.floorDiv(z, 4)
        );
        ResourceLocation biomeId = level.registryAccess()
                .registryOrThrow(net.minecraft.core.registries.Registries.BIOME)
                .getKey(biome.value());
        return biomeId == null ? "unknown" : biomeId.toString();
    }

    private static boolean isWaterColumn(NoiseColumn column, int height, int seaLevel) {
        if (height <= seaLevel) {
            for (int y = Math.max(0, height - 3); y <= seaLevel + 1; y++) {
                BlockState state = column.getBlock(y);
                if (state.getFluidState().isSource() || state.is(Blocks.WATER)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static SurfaceType surfaceType(BlockState state) {
        if (state.is(Blocks.SAND) || state.is(Blocks.RED_SAND) || state.is(Blocks.SANDSTONE)) {
            return SurfaceType.SAND;
        }
        if (state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.SNOW) || state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE)) {
            return SurfaceType.SNOW;
        }
        if (state.is(Blocks.STONE) || state.is(Blocks.GRANITE) || state.is(Blocks.DIORITE)
                || state.is(Blocks.ANDESITE) || state.is(Blocks.DEEPSLATE)) {
            return SurfaceType.ROCK;
        }
        if (state.is(Blocks.GRASS_BLOCK)) {
            return SurfaceType.GRASS;
        }
        if (state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.PODZOL)) {
            return SurfaceType.DIRT;
        }
        return SurfaceType.UNKNOWN;
    }
}
