package com.rinsing.geomantia.mixin;

import com.rinsing.geomantia.systems.city.infrastructure.world.CityTemplateTerrainStructure;
import com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityFeatureWriteGuard;
import com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityGenerationProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.*;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(StructureStart.class)
public abstract class CityStructureStartProtectionMixin {
    @Shadow public abstract Structure getStructure();
    @Shadow public abstract BoundingBox getBoundingBox();

    @Inject(method = "placeInChunk", at = @At("HEAD"), cancellable = true)
    private void geomantia$rejectIntrudingStart(WorldGenLevel level, StructureManager manager, ChunkGenerator generator,
            RandomSource random, BoundingBox owner, ChunkPos chunk, CallbackInfo ci) {
        if (!(getStructure() instanceof CityTemplateTerrainStructure)
                && CityGenerationProtection.intersects(level.getLevel(), getBoundingBox())) ci.cancel();
    }

    private void geomantia$writeScope(Runnable work) {
        if (getStructure() instanceof CityTemplateTerrainStructure)
            CityFeatureWriteGuard.city(() -> { work.run(); return null; });
        else CityFeatureWriteGuard.run(() -> { work.run(); return true; });
    }

    @Redirect(method = "placeInChunk", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/StructurePiece;postProcess(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/level/levelgen/structure/BoundingBox;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/core/BlockPos;)V"))
    private void geomantia$guardPiece(StructurePiece piece, WorldGenLevel level, StructureManager manager,
            ChunkGenerator generator, RandomSource random, BoundingBox box, ChunkPos chunk, BlockPos origin) {
        geomantia$writeScope(() -> piece.postProcess(level, manager, generator, random, box, chunk, origin));
    }

    @Redirect(method = "placeInChunk", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/Structure;afterPlace(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/level/levelgen/structure/BoundingBox;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/levelgen/structure/pieces/PiecesContainer;)V"))
    private void geomantia$guardAfterPlace(Structure structure, WorldGenLevel level, StructureManager manager,
            ChunkGenerator generator, RandomSource random, BoundingBox box, ChunkPos chunk, PiecesContainer pieces) {
        geomantia$writeScope(() -> structure.afterPlace(level, manager, generator, random, box, chunk, pieces));
    }
}
