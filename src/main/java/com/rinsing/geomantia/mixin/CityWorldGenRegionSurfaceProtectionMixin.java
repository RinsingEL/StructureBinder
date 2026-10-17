package com.rinsing.geomantia.mixin;

import com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityFeatureWriteGuard;
import com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityLandUseWorldgenRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldGenRegion.class)
public abstract class CityWorldGenRegionSurfaceProtectionMixin {
    @Inject(method = "setBlock", at = @At("HEAD"), cancellable = true)
    private void geomantia$protectDesignedSurface(BlockPos position, BlockState state, int flags, int recursion,
                                                  CallbackInfoReturnable<Boolean> cir) {
        if (!CityFeatureWriteGuard.active()) return;
        WorldGenRegion level = (WorldGenRegion)(Object)this;
        if (com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityGenerationProtection.protects(level.getLevel(), position)){
            CityFeatureWriteGuard.rejectFeature();
            cir.setReturnValue(false);
        }else{var key=position.immutable();CityFeatureWriteGuard.record(key,()->{var previous=level.getBlockState(key);return ()->level.setBlock(key,previous,2);});}
    }
}
