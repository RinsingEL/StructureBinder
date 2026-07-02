package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.List;
import java.util.Optional;

public final class MinecraftCityWorldgenStructurePlacer {
    private MinecraftCityWorldgenStructurePlacer() {
    }

    public static void injectPlannedStructures(ChunkGenerator generator,
                                               RegistryAccess registryAccess,
                                               ChunkGeneratorStructureState structureState,
                                               StructureManager structureManager,
                                               ChunkAccess chunk,
                                               StructureTemplateManager templateManager) {
        ChunkPos chunkPos = chunk.getPos();
        List<CityReservationMaskRegistry.PlannedStructure> planned =
                CityReservationMaskRegistry.plannedStructuresForChunk(chunkPos);
        if (planned.isEmpty()) {
            return;
        }
        for (CityReservationMaskRegistry.PlannedStructure item : planned) {
            tryInject(generator, registryAccess, structureState.randomState(), structureState.getLevelSeed(),
                    chunk, templateManager, chunkPos, item);
        }
    }

    public static void injectPlannedStructures(ChunkGenerator generator,
                                               RegistryAccess registryAccess,
                                               RandomState randomState,
                                               long levelSeed,
                                               ChunkAccess chunk,
                                               StructureTemplateManager templateManager) {
        ChunkPos chunkPos = chunk.getPos();
        List<CityReservationMaskRegistry.PlannedStructure> planned =
                CityReservationMaskRegistry.plannedStructuresForChunk(chunkPos);
        if (planned.isEmpty()) {
            return;
        }
        for (CityReservationMaskRegistry.PlannedStructure item : planned) {
            tryInject(generator, registryAccess, randomState, levelSeed, chunk, templateManager, chunkPos, item);
        }
    }

    private static void tryInject(ChunkGenerator generator,
                                  RegistryAccess registryAccess,
                                  RandomState randomState,
                                  long levelSeed,
                                  ChunkAccess chunk,
                                  StructureTemplateManager templateManager,
                                  ChunkPos chunkPos,
                                  CityReservationMaskRegistry.PlannedStructure item) {
        ResourceLocation id = ResourceLocation.tryParse(item.structureId());
        if (id == null) {
            CityReservationMaskRegistry.recordWorldgenFailure(item, chunkPos,
                    "CONFIGURED_STRUCTURE_REGISTRY_MISSING",
                    "Invalid configured structure id: " + item.structureId());
            return;
        }
        Optional<Holder.Reference<Structure>> holder = registryAccess
                .registryOrThrow(Registries.STRUCTURE)
                .getHolder(ResourceKey.create(Registries.STRUCTURE, id));
        if (holder.isEmpty()) {
            CityReservationMaskRegistry.recordWorldgenFailure(item, chunkPos,
                    "CONFIGURED_STRUCTURE_REGISTRY_MISSING",
                    "Configured structure registry does not contain: " + item.structureId());
            return;
        }
        Structure structure = holder.get().value();
        StructureStart existing = chunk.getStartForStructure(structure);
        if (existing != null && existing.isValid()) {
            CityReservationMaskRegistry.recordWorldgenFailure(item, chunkPos,
                    "STRUCTURE_START_ALREADY_EXISTS",
                    "Chunk already has a valid StructureStart for " + item.structureId());
            return;
        }

        StructureStart start = structure.generate(
                registryAccess,
                generator,
                generator.getBiomeSource(),
                randomState,
                templateManager,
                levelSeed,
                new ChunkPos(item.anchorChunkX(), item.anchorChunkZ()),
                0,
                chunk.getHeightAccessorForGeneration(),
                biome -> true);
        if (!start.isValid()) {
            CityReservationMaskRegistry.recordWorldgenFailure(item, chunkPos,
                    "CONFIGURED_STRUCTURE_START_INVALID",
                    "Configured structure generated invalid StructureStart: " + item.structureId());
            return;
        }

        BlockBounds footprint = footprint(start.getBoundingBox());
        if (!contains(item.collisionEnvelope(), footprint)) {
            CityReservationMaskRegistry.recordWorldgenFailure(item, chunkPos,
                    "RESERVED_ENVELOPE_EXCEEDED",
                    "Worldgen StructureStart bbox exceeded D6 locked collisionEnvelope.");
            return;
        }
        if (CityReservationMaskRegistry.overlapsWorldgenLedger(footprint, item.anchorId())) {
            CityReservationMaskRegistry.recordWorldgenFailure(item, chunkPos,
                    "LEDGER_OCCUPIED_OVERLAP",
                    "Worldgen StructureStart bbox overlaps existing City worldgen ledger.");
            return;
        }
        String signature = signature(item, start);
        if (!item.expectedStartSignature().isBlank() && !item.expectedStartSignature().equals(signature)) {
            CityReservationMaskRegistry.recordWorldgenFailure(item, chunkPos,
                    "START_SIGNATURE_MISMATCH",
                    "Worldgen StructureStart differs from expected signature.");
            return;
        }

        chunk.setStartForStructure(structure, start);
        chunk.setUnsaved(true);
        CityReservationMaskRegistry.recordWorldgenPlacement(item, footprint, signature, pieces(start), chunkPos,
                structure.terrainAdaptation().getSerializedName(),
                "WORLDGEN_PLACEMENT_RECORDED",
                "Planned StructureStart injected during ChunkGenerator.createStructures.");
    }

    private static boolean contains(BlockBounds container, BlockBounds child) {
        return container.minX() <= child.minX()
                && container.minZ() <= child.minZ()
                && container.maxX() >= child.maxX()
                && container.maxZ() >= child.maxZ();
    }

    private static String signature(CityReservationMaskRegistry.PlannedStructure item, StructureStart start) {
        return item.structureId() + "@" + item.anchorBlock().x() + "," + item.anchorBlock().z()
                + "#" + start.getPieces().size() + "#" + footprint(start.getBoundingBox());
    }

    private static JsonArray pieces(StructureStart start) {
        JsonArray array = new JsonArray();
        int index = 0;
        for (StructurePiece piece : start.getPieces()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("pieceIndex", index++);
            obj.add("box", boundsJson(footprint(piece.getBoundingBox())));
            obj.addProperty("type", piece.getType().toString());
            array.add(obj);
        }
        return array;
    }

    private static BlockBounds footprint(BoundingBox box) {
        return new BlockBounds(box.minX(), box.minZ(), box.maxX(), box.maxZ());
    }

    private static JsonObject boundsJson(BlockBounds bounds) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minX", bounds.minX());
        obj.addProperty("minZ", bounds.minZ());
        obj.addProperty("maxX", bounds.maxX());
        obj.addProperty("maxZ", bounds.maxZ());
        return obj;
    }
}
