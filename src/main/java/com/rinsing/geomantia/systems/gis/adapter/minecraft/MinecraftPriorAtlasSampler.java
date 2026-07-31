package com.rinsing.geomantia.systems.gis.adapter.minecraft;

import com.rinsing.geomantia.systems.gis.application.refresh.SampleMode;
import com.rinsing.geomantia.systems.gis.application.sample.AtlasSampler;
import com.rinsing.geomantia.systems.gis.application.sample.SampledCell;
import com.rinsing.geomantia.systems.gis.domain.cell.AtlasCell;
import com.rinsing.geomantia.systems.gis.domain.cell.SampleSource;
import com.rinsing.geomantia.systems.gis.domain.cell.SurfaceType;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Objects;
import java.util.function.IntFunction;

public final class MinecraftPriorAtlasSampler implements AtlasSampler {
    private static final ColumnStateAdapter<BlockState> BLOCK_STATE_ADAPTER = new ColumnStateAdapter<>() {
        @Override
        public boolean worldSurface(BlockState state) {
            return Heightmap.Types.WORLD_SURFACE_WG.isOpaque().test(state);
        }

        @Override
        public boolean oceanFloor(BlockState state) {
            return Heightmap.Types.OCEAN_FLOOR_WG.isOpaque().test(state);
        }

        @Override
        public boolean water(BlockState state) {
            return state.getFluidState().isSource() || state.is(Blocks.WATER);
        }

        @Override
        public SurfaceType surfaceType(BlockState state) {
            return MinecraftPriorAtlasSampler.surfaceType(state);
        }
    };

    private final ServerLevel level;

    public MinecraftPriorAtlasSampler(ServerLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    @Override
    public SampledCell sample(AtlasCell cell, SampleMode sampleMode) {
        int x = cell.blockMinX();
        int z = cell.blockMinZ();
        NoiseColumn column = level.getChunkSource().getGenerator().getBaseColumn(
                x,
                z,
                level,
                level.getChunkSource().randomState()
        );
        ColumnSample terrain = sampleColumn(column, level.getMinBuildHeight(), level.getMaxBuildHeight(),
                level.getSeaLevel());
        return new SampledCell(SampleSource.PRIOR, terrain.surfaceHeight(), terrain.surfaceType(),
                biomeIdAt(x, z, terrain.surfaceHeight()), terrain.water(), terrain.waterDepth());
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

    static ColumnSample sampleColumn(NoiseColumn column, int minBuildHeight, int maxBuildHeight, int seaLevel) {
        Objects.requireNonNull(column, "column");
        return sampleColumn(column::getBlock, BLOCK_STATE_ADAPTER, minBuildHeight, maxBuildHeight, seaLevel);
    }

    static <T> ColumnSample sampleColumn(IntFunction<T> column, ColumnStateAdapter<T> adapter,
            int minBuildHeight, int maxBuildHeight, int seaLevel) {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(adapter, "adapter");
        if (maxBuildHeight <= minBuildHeight) {
            throw new IllegalArgumentException("maxBuildHeight must be greater than minBuildHeight");
        }

        int surfaceHeight = minBuildHeight;
        int oceanFloorHeight = minBuildHeight;
        boolean surfaceFound = false;
        boolean oceanFloorFound = false;
        for (int y = maxBuildHeight - 1; y >= minBuildHeight; y--) {
            T state = column.apply(y);
            if (!surfaceFound && adapter.worldSurface(state)) {
                surfaceHeight = y + 1;
                surfaceFound = true;
            }
            if (!oceanFloorFound && adapter.oceanFloor(state)) {
                oceanFloorHeight = y + 1;
                oceanFloorFound = true;
            }
            if (surfaceFound && oceanFloorFound) {
                break;
            }
        }

        boolean water = isWaterColumn(column, adapter, surfaceHeight, seaLevel);
        double waterDepth = water ? Math.max(0, seaLevel - oceanFloorHeight) : 0.0;
        SurfaceType sampledSurfaceType = water
                ? SurfaceType.WATER
                : adapter.surfaceType(column.apply(surfaceHeight - 1));
        return new ColumnSample(surfaceHeight, sampledSurfaceType, water, waterDepth);
    }

    private static <T> boolean isWaterColumn(IntFunction<T> column, ColumnStateAdapter<T> adapter,
            int height, int seaLevel) {
        if (height <= seaLevel) {
            for (int y = Math.max(0, height - 3); y <= seaLevel + 1; y++) {
                if (adapter.water(column.apply(y))) {
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

    record ColumnSample(int surfaceHeight, SurfaceType surfaceType, boolean water, double waterDepth) {
    }

    interface ColumnStateAdapter<T> {
        boolean worldSurface(T state);

        boolean oceanFloor(T state);

        boolean water(T state);

        SurfaceType surfaceType(T state);
    }
}
