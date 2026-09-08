package com.rinsing.geomantia.mixin;

import com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityFeatureWriteGuard;
import com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityGenerationProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class CityLevelGenerationProtectionMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"), cancellable = true)
    private void geomantia$guardServerGeneration(BlockPos pos, BlockState state, int flags, int recursion,
                                                CallbackInfoReturnable<Boolean> cir) {
        if (CityFeatureWriteGuard.active() && (Object)this instanceof ServerLevel server
                && CityGenerationProtection.protects(server, pos)) cir.setReturnValue(false);
    }
}
