package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import com.rinsing.geomantia.systems.city.infrastructure.world.CityReservationMaskRegistry;
import com.rinsing.geomantia.systems.city.infrastructure.world.MinecraftCityWorldgenStructurePlacer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import java.util.function.IntBinaryOperator;

/** Geometry policy shared by feature writes and complete external structure starts; never reads generated chunks. */
public final class CityGenerationProtection {
    private CityGenerationProtection() {}
    private static IntBinaryOperator terrain(ServerLevel level) {
        return (x, z) -> {
            var source = level.getChunkSource();
            return MinecraftCityWorldgenStructurePlacer.sampleDesignTerrain(source.getGenerator(),
                    level.registryAccess(), source.randomState(), level, x, z).surfaceY();
        };
    }
    public static boolean protects(ServerLevel level, BlockPos pos) {
        String dimension = level.dimension().location().toString();
        var sample = terrain(level);
        return CityLandUseWorldgenRegistry.generationMask().protects(dimension, pos.getX(), pos.getY(), pos.getZ(), sample)
                || CityReservationMaskRegistry.generationMask().protects(dimension, pos.getX(), pos.getY(), pos.getZ(), sample);
    }
    public static boolean intersects(ServerLevel level, BoundingBox box) {
        String dimension = level.dimension().location().toString();
        var sample = terrain(level);
        return CityLandUseWorldgenRegistry.generationMask().intersects(dimension, box.minX(), box.minY(), box.minZ(),
                box.maxX(), box.maxY(), box.maxZ(), sample)
                || CityReservationMaskRegistry.generationMask().intersects(dimension, box.minX(), box.minY(), box.minZ(),
                box.maxX(), box.maxY(), box.maxZ(), sample);
    }
}
