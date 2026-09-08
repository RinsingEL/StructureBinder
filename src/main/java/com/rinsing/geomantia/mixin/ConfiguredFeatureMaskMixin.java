package com.rinsing.geomantia.mixin;

import com.rinsing.geomantia.systems.city.infrastructure.world.CityReservationMaskRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ConfiguredFeature.class)
public abstract class ConfiguredFeatureMaskMixin {
    @org.spongepowered.asm.mixin.injection.Redirect(method = "place", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/feature/Feature;place(Lnet/minecraft/world/level/levelgen/feature/configurations/FeatureConfiguration;Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z"))
    private boolean geomantia$guardFeatureWrites(net.minecraft.world.level.levelgen.feature.Feature feature,
            net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration config,
            WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
        CityReservationMaskRegistry.recordFeatureHookCall();
        return com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityFeatureWriteGuard.run(
                () -> feature.place(config, level, generator, random, origin));
    }

}
