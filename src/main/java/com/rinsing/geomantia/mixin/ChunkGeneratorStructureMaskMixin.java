package com.rinsing.geomantia.mixin;

import com.rinsing.geomantia.systems.city.infrastructure.world.CityReservationMaskRegistry;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityDecorationWorldgenRegistry;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityWorldgenBlockObservationRegistry;
import com.rinsing.geomantia.systems.city.infrastructure.world.MinecraftCityWorldgenStructurePlacer;
import com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityLandUseChunkExecutor;
import com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityLandUseWorldgenRegistry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorStructureMaskMixin {
    @Inject(method = "tryGenerateStructure", at = @At("HEAD"), cancellable = true)
    private void geomantia$suppressCityVanillaStructure(StructureSet.StructureSelectionEntry entry,
                                                        StructureManager structureManager,
                                                        RegistryAccess registryAccess,
                                                        RandomState randomState,
                                                        StructureTemplateManager templateManager,
                                                        long seed,
                                                        ChunkAccess chunk,
                                                        ChunkPos chunkPos,
                                                        SectionPos sectionPos,
                                                        CallbackInfoReturnable<Boolean> cir) {
        if (CityReservationMaskRegistry.suppressVanillaStructure(entry.structure().value(), chunkPos)) {
            cir.setReturnValue(false);
        }
        MinecraftCityWorldgenStructurePlacer.injectPlannedStructures(
                (ChunkGenerator) (Object) this,
                registryAccess,
                randomState,
                seed,
                chunk,
                templateManager);
    }

    @Inject(method = "createStructures", at = @At("TAIL"))
    private void geomantia$injectCityPlannedStructure(RegistryAccess registryAccess,
                                                      ChunkGeneratorStructureState structureState,
                                                      StructureManager structureManager,
                                                      ChunkAccess chunk,
                                                      StructureTemplateManager templateManager,
                                                      CallbackInfo ci) {
        CityReservationMaskRegistry.recordStructureHookCall(chunk.getPos());
        MinecraftCityWorldgenStructurePlacer.injectPlannedStructures(
                (ChunkGenerator) (Object) this,
                registryAccess,
                structureState,
                structureManager,
                chunk,
                templateManager);
    }

    @Inject(method = "applyBiomeDecoration", at = @At("HEAD"))
    private void geomantia$placeCityTemplateFragments(WorldGenLevel level,
                                                       ChunkAccess chunk,
                                                       StructureManager structureManager,
                                                       CallbackInfo ci) {
        CityWorldgenBlockObservationRegistry.begin(level, chunk);
        try {
            MinecraftCityWorldgenStructurePlacer.injectPlannedTemplateStructures(level, chunk);
            CityLandUseWorldgenRegistry.applyForChunk(
                    level.getLevel().dimension().location().toString(),
                    chunk.getPos().x,
                    chunk.getPos().z,
                    CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES,
                    new CityLandUseChunkExecutor.WorldGenExecutionWorld(level));
            CityDecorationWorldgenRegistry.applyForChunk(level, chunk);
        } catch (RuntimeException | Error ex) {
            CityWorldgenBlockObservationRegistry.abort();
            throw ex;
        }
    }

    @Inject(method = "applyBiomeDecoration", at = @At("TAIL"))
    private void geomantia$observeCityBlocksAfterFeatures(WorldGenLevel level,
                                                           ChunkAccess chunk,
                                                           StructureManager structureManager,
                                                           CallbackInfo ci) {
        CityWorldgenBlockObservationRegistry.finishAfterFeatures(level, chunk);
    }
}
