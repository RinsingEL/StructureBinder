package com.rinsing.geomantia.mixin;

import com.rinsing.geomantia.systems.city.infrastructure.world.CityReservationMaskRegistry;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityWorldgenBlockObservationRegistry;
import com.rinsing.geomantia.systems.city.infrastructure.world.MinecraftCityWorldgenStructurePlacer;
import com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityLandUseChunkExecutor;
import com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityLandUseWorldgenRegistry;
import com.rinsing.geomantia.systems.city.testsupport.CityLandUseWorldgenGameTestFixture;
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
    private void geomantia$filterExternalAttempt(StructureSet.StructureSelectionEntry entry, StructureManager manager,
            RegistryAccess registries, RandomState random, StructureTemplateManager templates, long seed,
            ChunkAccess chunk, ChunkPos pos, SectionPos section, CallbackInfoReturnable<Boolean> callback) {
        var world = ((StructureManagerAccessor) manager).geomantia$getLevel();
        if (world instanceof WorldGenLevel generation
                && net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(new com.rinsing.geomantia.api.regions.StructureDistributionEvent.Attempt(
                    generation.getLevel(), entry.structure().value(), pos))) callback.setReturnValue(false);
    }

    @org.spongepowered.asm.mixin.injection.Redirect(method = "tryGenerateStructure", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/StructureManager;setStartForStructure(Lnet/minecraft/core/SectionPos;Lnet/minecraft/world/level/levelgen/structure/Structure;Lnet/minecraft/world/level/levelgen/structure/StructureStart;Lnet/minecraft/world/level/chunk/StructureAccess;)V"))
    private void geomantia$checkCompleteExternalStart(StructureManager manager, SectionPos section,
            net.minecraft.world.level.levelgen.structure.Structure structure,
            net.minecraft.world.level.levelgen.structure.StructureStart start,
            net.minecraft.world.level.chunk.StructureAccess chunk) {
        var world = ((StructureManagerAccessor) manager).geomantia$getLevel();
        if (!(structure instanceof com.rinsing.geomantia.systems.city.infrastructure.world.CityTemplateTerrainStructure)
                && start.isValid() && world instanceof WorldGenLevel generation
                && com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityGenerationProtection
                .intersects(generation.getLevel(), start.getBoundingBox()))
            start = net.minecraft.world.level.levelgen.structure.StructureStart.INVALID_START;
        com.rinsing.geomantia.api.regions.StructureDistributionEvent.Candidate decision = null;
        try {
            if (start.isValid() && world instanceof WorldGenLevel generation
                    && !(structure instanceof com.rinsing.geomantia.systems.city.infrastructure.world.CityTemplateTerrainStructure)) {
                decision = new com.rinsing.geomantia.api.regions.StructureDistributionEvent.Candidate(generation.getLevel(), structure, start);
                if (net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(decision))
                    start = net.minecraft.world.level.levelgen.structure.StructureStart.INVALID_START;
            }
            manager.setStartForStructure(section, structure, start, chunk);
        } catch (RuntimeException | Error failure) {
            if (decision != null) decision.finish(false);
            throw failure;
        }
        if (decision != null) decision.finish(start.isValid());
    }

    @Inject(method = "createStructures", at = @At("TAIL"))
    private void geomantia$injectCityPlannedStructure(RegistryAccess registryAccess,
                                                      ChunkGeneratorStructureState structureState,
                                                      StructureManager structureManager,
                                                      ChunkAccess chunk,
                                                      StructureTemplateManager templateManager,
                                                      CallbackInfo ci) {
        CityReservationMaskRegistry.recordStructureHookCall(chunk.getPos());
        MinecraftCityWorldgenStructurePlacer.injectPlannedTemplateTerrainStarts(
                (ChunkGenerator) (Object) this,
                registryAccess,
                structureState,
                chunk,
                templateManager);
        var world = ((StructureManagerAccessor) structureManager).geomantia$getLevel();
        if (world instanceof WorldGenLevel generation)
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(new com.rinsing.geomantia.api.regions.StructureDistributionEvent.Prepare(
                    generation.getLevel(), (ChunkGenerator) (Object) this, structureState, chunk, templateManager));
    }

    @Inject(method = "applyBiomeDecoration", at = @At("HEAD"))
    private void geomantia$placeCityTemplateFragments(WorldGenLevel level,
                                                       ChunkAccess chunk,
                                                       StructureManager structureManager,
                                                       CallbackInfo ci) {
        CityWorldgenBlockObservationRegistry.begin(level, chunk);
        try {
            CityLandUseWorldgenGameTestFixture.prepare(level, chunk.getPos());
            CityLandUseWorldgenRegistry.applyForChunk(
                    level.getLevel().dimension().location().toString(),
                    chunk.getPos().x,
                    chunk.getPos().z,
                    CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES,
                    new CityLandUseChunkExecutor.WorldGenExecutionWorld(level));
            com.rinsing.geomantia.systems.city.infrastructure.world.CityWallWorldgenRegistry.apply(level, chunk);
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
