package com.rinsing.geomantia.mixin;

import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationTerrainRunCompiler;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityDecorationBeardifierAccess;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityDecorationFoundationDensityComputer;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityDecorationWorldgenRegistry;
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

import java.util.List;

@Mixin(Beardifier.class)
public abstract class CityDecorationBeardifierMixin implements CityDecorationBeardifierAccess {
    @Unique
    private List<CityDecorationTerrainRunCompiler.FoundationSegment> geomantia$foundationSegments = List.of();

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
        List<CityDecorationTerrainRunCompiler.FoundationSegment> snapshot =
                CityDecorationWorldgenRegistry.foundationSegmentsForChunk(
                        level.dimension().location().toString(), chunkPos);
        ((CityDecorationBeardifierAccess) cir.getReturnValue()).geomantia$setFoundationSegments(snapshot);
    }

    @Inject(method = "compute", at = @At("RETURN"), cancellable = true)
    private void geomantia$addCityDecorationFoundationDensity(DensityFunction.FunctionContext context,
                                                               CallbackInfoReturnable<Double> cir) {
        double contribution = CityDecorationFoundationDensityComputer.compute(
                context.blockX(), context.blockY(), context.blockZ(), geomantia$foundationSegments);
        if (contribution > 0.0D) {
            cir.setReturnValue(cir.getReturnValue() + contribution);
        }
    }

    @Override
    public void geomantia$setFoundationSegments(
            List<CityDecorationTerrainRunCompiler.FoundationSegment> foundationSegments) {
        geomantia$foundationSegments = List.copyOf(foundationSegments);
    }
}
