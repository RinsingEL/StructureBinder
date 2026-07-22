package com.rinsing.geomantia.mixin;

import com.rinsing.geomantia.systems.city.application.terrain.CityTerrainFoundationDensityComputer;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityDecorationBeardifierAccess;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityDecorationWorldgenRegistry;
import com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityLandUseWorldgenRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
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
public abstract class CityDecorationBeardifierMixin implements CityDecorationBeardifierAccess {
    @Unique
    private List<CityTerrainFoundationDensityComputer.FoundationSegmentView> geomantia$foundationSegments =
            List.of();

    @Inject(method = "forStructuresInChunk", at = @At("RETURN"))
    private static void geomantia$captureCityDecorationFoundations(StructureManager structureManager,
                                                                   ChunkPos chunkPos,
                                                                   CallbackInfoReturnable<Beardifier> cir) {
        LevelAccessor accessor = ((StructureManagerAccessor) structureManager).geomantia$getLevel();
        ServerLevel level = accessor instanceof WorldGenRegion region ? region.getLevel()
                : accessor instanceof ServerLevel serverLevel ? serverLevel : null;
        if (level == null) {
            return;
        }
        String dimensionId = level.dimension().location().toString();
        List<CityTerrainFoundationDensityComputer.FoundationSegmentView> snapshot = new ArrayList<>();
        snapshot.addAll(CityDecorationWorldgenRegistry.foundationSegmentsForChunk(dimensionId, chunkPos));
        snapshot.addAll(CityLandUseWorldgenRegistry.foundationSegmentsForChunk(dimensionId, chunkPos));
        ((CityDecorationBeardifierAccess) cir.getReturnValue()).geomantia$setFoundationSegments(snapshot);
    }

    @Inject(method = "compute", at = @At("RETURN"), cancellable = true)
    private void geomantia$addCityDecorationFoundationDensity(DensityFunction.FunctionContext context,
                                                               CallbackInfoReturnable<Double> cir) {
        double contribution = CityTerrainFoundationDensityComputer.computeViews(
                context.blockX(), context.blockY(), context.blockZ(), geomantia$foundationSegments);
        if (contribution > 0.0D) {
            cir.setReturnValue(cir.getReturnValue() + contribution);
        }
    }

    @Override
    public void geomantia$setFoundationSegments(
            List<? extends CityTerrainFoundationDensityComputer.FoundationSegmentView> foundationSegments) {
        geomantia$foundationSegments = new ArrayList<>(foundationSegments);
    }
}
