package com.rinsing.geomantia.mixin;

import com.rinsing.geomantia.systems.city.infrastructure.world.CityReservationMaskRegistry;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityDecorationWorldgenRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ConfiguredFeature.class)
public abstract class ConfiguredFeatureMaskMixin {
    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void geomantia$suppressCityVegetation(WorldGenLevel level,
                                                  ChunkGenerator generator,
                                                  RandomSource random,
                                                  BlockPos origin,
                                                  CallbackInfoReturnable<Boolean> cir) {
        ConfiguredFeature<?, ?> feature = (ConfiguredFeature<?, ?>) (Object) this;
        CityDecorationWorldgenRegistry.enterFeatureOrigin(level, origin);
        try {
            if (CityDecorationWorldgenRegistry.suppressFeature(level, feature, origin)
                    || CityReservationMaskRegistry.suppressFeature(feature, origin)) {
                CityDecorationWorldgenRegistry.exitFeatureOrigin();
                cir.setReturnValue(false);
            }
        } catch (RuntimeException | Error failure) {
            CityDecorationWorldgenRegistry.exitFeatureOrigin();
            throw failure;
        }
    }

    @Redirect(method = "place", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/feature/Feature;place(Lnet/minecraft/world/level/levelgen/feature/configurations/FeatureConfiguration;Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z"))
    private boolean geomantia$finishFeatureInvocation(Feature<?> feature,
                                                       FeatureConfiguration configuration,
                                                       WorldGenLevel level,
                                                       ChunkGenerator generator,
                                                       RandomSource random,
                                                       BlockPos origin) {
        try {
            return placeFeature(feature, configuration, level, generator, random, origin);
        } finally {
            CityDecorationWorldgenRegistry.exitFeatureOrigin();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean placeFeature(Feature<?> feature,
                                        FeatureConfiguration configuration,
                                        WorldGenLevel level,
                                        ChunkGenerator generator,
                                        RandomSource random,
                                        BlockPos origin) {
        return ((Feature) feature).place(configuration, level, generator, random, origin);
    }
}
