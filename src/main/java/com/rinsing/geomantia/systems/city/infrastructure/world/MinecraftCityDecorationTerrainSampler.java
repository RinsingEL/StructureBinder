package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationTerrainRunCompiler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Objects;

/** Activation-only generator sampler. It never requests or loads a chunk. */
public final class MinecraftCityDecorationTerrainSampler
        implements CityDecorationTerrainRunCompiler.TerrainView {
    private final ServerLevel level;
    private final ChunkGenerator generator;

    public MinecraftCityDecorationTerrainSampler(ServerLevel level) {
        this.level = Objects.requireNonNull(level, "level");
        this.generator = level.getChunkSource().getGenerator();
    }

    @Override
    public CityDecorationTerrainRunCompiler.TerrainSample sample(int worldX, int worldZ) {
        int firstFreeY = generator.getBaseHeight(worldX, worldZ, Heightmap.Types.WORLD_SURFACE_WG,
                level, level.getChunkSource().randomState());
        int surfaceY = Math.max(level.getMinBuildHeight(), firstFreeY - 1);
        NoiseColumn column = generator.getBaseColumn(worldX, worldZ, level,
                level.getChunkSource().randomState());
        BlockState surface = column.getBlock(surfaceY);
        boolean water = surface.is(Blocks.WATER) || surface.getFluidState().is(FluidTags.WATER);
        if (!water && firstFreeY <= level.getSeaLevel() + 1) {
            int maxY = Math.min(level.getMaxBuildHeight() - 1, level.getSeaLevel() + 1);
            for (int y = Math.max(level.getMinBuildHeight(), surfaceY - 2); y <= maxY; y++) {
                BlockState state = column.getBlock(y);
                if (state.is(Blocks.WATER) || state.getFluidState().is(FluidTags.WATER)) {
                    water = true;
                    break;
                }
            }
        }
        return new CityDecorationTerrainRunCompiler.TerrainSample(surfaceY, water, true);
    }
}
