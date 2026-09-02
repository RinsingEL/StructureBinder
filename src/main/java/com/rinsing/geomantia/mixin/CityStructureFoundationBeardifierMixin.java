package com.rinsing.geomantia.mixin;

import com.rinsing.geomantia.systems.city.application.terrain.CityTerrainFoundationDensityComputer;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityStructureFoundationBeardifierAccess;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityStructureFoundationPlatformResolver;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(Beardifier.class)
public abstract class CityStructureFoundationBeardifierMixin implements CityStructureFoundationBeardifierAccess {
    @Unique
    private List<CityTerrainFoundationDensityComputer.FoundationPlatformView> geomantia$foundationPlatforms =
            List.of();

    @Inject(method = "forStructuresInChunk", at = @At("RETURN"))
    private static void geomantia$captureCityStructureFoundations(StructureManager structureManager,
                                                                  ChunkPos chunkPos,
                                                                  CallbackInfoReturnable<Beardifier> cir) {
        CityStructureFoundationBeardifierAccess access =
                (CityStructureFoundationBeardifierAccess) cir.getReturnValue();
        access.geomantia$setFoundationPlatforms(CityStructureFoundationPlatformResolver.forChunk(chunkPos));
    }

    @Inject(method = "compute", at = @At("RETURN"), cancellable = true)
    private void geomantia$addCityStructureFoundationDensity(DensityFunction.FunctionContext context,
                                                              CallbackInfoReturnable<Double> cir) {
        double contribution = CityTerrainFoundationDensityComputer.computePlatformViews(
                context.blockX(), context.blockY(), context.blockZ(), geomantia$foundationPlatforms);
        if (contribution > 0.0D) {
            cir.setReturnValue(cir.getReturnValue() + contribution);
        }
    }

    @Override
    public void geomantia$setFoundationPlatforms(
            List<? extends CityTerrainFoundationDensityComputer.FoundationPlatformView> foundationPlatforms) {
        geomantia$foundationPlatforms = new ArrayList<>(foundationPlatforms);
    }
}
