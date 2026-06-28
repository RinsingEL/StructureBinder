package com.rinsing.geomantia.mixin;

import com.rinsing.geomantia.systems.city.infrastructure.world.CityReservationMaskRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ConfiguredFeature.class)
public abstract class ConfiguredFeatureMaskMixin {
    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void geomantia$suppressCityVegetation(WorldGenLevel level,
                                                  ChunkGenerator generator,
                                                  RandomSource random,
                                                  BlockPos origin,
                                                  CallbackInfoReturnable<Boolean> cir) {
        if (CityReservationMaskRegistry.suppressFeature((ConfiguredFeature<?, ?>) (Object) this, origin)) {
            cir.setReturnValue(false);
        }
    }
}
