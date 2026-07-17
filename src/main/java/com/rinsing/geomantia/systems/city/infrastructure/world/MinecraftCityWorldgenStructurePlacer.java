package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.application.CityStructureMaterializationPlanner;
import com.rinsing.geomantia.systems.city.application.CityTemplatePlacementGeometry;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.slf4j.Logger;

public final class MinecraftCityWorldgenStructurePlacer {
    private static final Logger LOGGER = LogUtils.getLogger();

    private MinecraftCityWorldgenStructurePlacer() {
    }

    public static void injectPlannedTemplateStructures(WorldGenLevel level, ChunkAccess chunk) {
        if (level == null || chunk == null) {
            return;
        }
        List<CityReservationMaskRegistry.PlannedStructure> planned =
                CityReservationMaskRegistry.plannedStructuresForChunk(chunk.getPos());
        if (planned.isEmpty()) {
            return;
        }
        for (CityReservationMaskRegistry.PlannedStructure item : planned) {
            if (!item.isTemplatePlacement() || CityTemplateTerrainStartPolicy.usesStructureStart(item)) {
                continue;
            }
            placeTemplateOwner(level, chunk.getPos(), item, false);
        }
    }

    /**
     * Controlled delayed path for an owner that was durably observed during first FEATURES.
     * The registry proof is mandatory, so this method cannot turn into general old-chunk late paste.
     */
    public static void retryPendingTemplateStructures(ServerLevel level, LevelChunk chunk) {
        if (level == null || chunk == null) {
            return;
        }
        for (CityReservationMaskRegistry.PlannedStructure item
                : CityReservationMaskRegistry.pendingTemplateStructuresForChunk(chunk.getPos())) {
            if (CityTemplateTerrainStartPolicy.usesStructureStart(item)) {
                continue;
            }
            placeTemplateOwner(level, chunk.getPos(), item, true);
        }
    }

    private static void placeTemplateOwner(WorldGenLevel level,
                                           ChunkPos ownerChunk,
                                           CityReservationMaskRegistry.PlannedStructure item,
                                           boolean delayedFirstGenerationRetry) {
        JsonObject plan = item.templatePlan();
        try {
            String templateRef = text(plan, "templateRef", nestedText(plan, "structureTemplate", "templateRef"));
            String templateHash = text(plan, "templateHash", nestedText(plan, "structureTemplate", "templateHash"));
            BlockPoint anchor = point(plan, "anchorBlock", item.anchorBlock());
            CityTemplatePlacementGeometry.Rotation rotation = CityTemplatePlacementGeometry.Rotation.valueOf(
                    text(plan, "rotation", "NONE"));
            CityTemplatePlacementGeometry.Mirror mirror = CityTemplatePlacementGeometry.Mirror.valueOf(
                    text(plan, "mirror", "NONE"));
            String datumPolicy = text(plan, "templateDatumPolicy", "");
            if (!CityStructureMaterializationPlanner.TEMPLATE_DATUM_POLICY_WORLDGEN_SURFACE.equals(datumPolicy)) {
                CityReservationMaskRegistry.recordWorldgenFailure(item, ownerChunk,
                        "TEMPLATE_DATUM_POLICY_INVALID",
                        "Template placement requires templateDatumPolicy="
                                + CityStructureMaterializationPlanner.TEMPLATE_DATUM_POLICY_WORLDGEN_SURFACE + ".");
                return;
            }

            OptionalInt resolved = CityReservationMaskRegistry.resolvedTemplateDatum(item);
            if (delayedFirstGenerationRetry) {
                if (!CityReservationMaskRegistry.hasTemplatePendingProof(item, ownerChunk)
                        || resolved.isEmpty()) {
                    return;
                }
            } else {
                OptionalInt candidate = OptionalInt.empty();
                if (resolved.isEmpty()
                        && ownerChunk.x == item.anchorChunkX() && ownerChunk.z == item.anchorChunkZ()) {
                    candidate = OptionalInt.of(level.getHeight(
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, anchor.x(), anchor.z()));
                }
                CityReservationMaskRegistry.TemplateDatumPreparation preparation =
                        CityReservationMaskRegistry.prepareTemplateOwner(
                                item, ownerChunk, candidate, level.getMinBuildHeight());
                if (!preparation.ready()) {
                    if (preparation.status()
                            != CityReservationMaskRegistry.TemplateDatumPreparationStatus.WAITING
                            && preparation.status()
                            != CityReservationMaskRegistry.TemplateDatumPreparationStatus.PERSISTENCE_FAILED) {
                        CityReservationMaskRegistry.recordWorldgenFailure(item, ownerChunk,
                                preparation.reasonCode(), preparation.message());
                    }
                    return;
                }
                resolved = preparation.templateDatumY();
            }

            int datum = resolved.orElseThrow();
            StructureTemplateManager manager = level.getLevel().getStructureManager();
            MinecraftCityTemplateWorldgenPlacer placer = new MinecraftCityTemplateWorldgenPlacer(manager);
            MinecraftCityTemplateWorldgenPlacer.PlacementRequest request =
                    new MinecraftCityTemplateWorldgenPlacer.PlacementRequest(templateRef, templateHash,
                            anchor, rotation, mirror, datum, ownerChunk, item.lockedActualFootprint());
            MinecraftCityTemplateWorldgenPlacer.PlacementResult result = placer.place(request,
                    new MinecraftCityTemplateWorldgenPlacer.WorldGenLevelWriter(level, ownerChunk));
            if (!result.success() && !result.waiting()) {
                CityReservationMaskRegistry.recordWorldgenFailure(item, ownerChunk,
                        result.reasonCode(), result.message());
            } else if (result.worldMutationApplied() && result.templateFootprint() != null) {
                CityReservationMaskRegistry.TemplateFragmentRecordResult recordResult =
                        CityReservationMaskRegistry.recordTemplateWorldgenFragment(
                                item, result.templateFootprint(),
                                templateSignature(item, templateHash, rotation, mirror), new JsonArray(), ownerChunk,
                                datum, "", result.reasonCode(), delayedFirstGenerationRetry
                                        ? "Delayed first-generation owner fragment written after durable FEATURES wait."
                                        : result.message());
                if (!recordResult.recorded()) {
                    LOGGER.warn("City template fragment remains pending for {} {} chunk {},{}: {}",
                            item.anchorId(), item.structureId(), ownerChunk.x, ownerChunk.z,
                            recordResult.reasonCode());
                }
            }
        } catch (RuntimeException ex) {
            CityReservationMaskRegistry.recordWorldgenFailure(item, ownerChunk,
                    "TEMPLATE_PLACEMENT_FAILED", ex.getMessage());
        }
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
        if (item.isTemplatePlacement()) {
            if (CityTemplateTerrainStartPolicy.usesStructureStart(item)) {
                tryInjectTemplateTerrainStart(generator, registryAccess, randomState, chunk, templateManager,
                        chunkPos, item);
            }
            return;
        }
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

    private static void tryInjectTemplateTerrainStart(ChunkGenerator generator,
                                                      RegistryAccess registryAccess,
                                                      RandomState randomState,
                                                      ChunkAccess chunk,
                                                      StructureTemplateManager templateManager,
                                                      ChunkPos chunkPos,
                                                      CityReservationMaskRegistry.PlannedStructure item) {
        if (chunkPos.x != item.anchorChunkX() || chunkPos.z != item.anchorChunkZ()) {
            return;
        }
        Optional<Holder.Reference<Structure>> holder = registryAccess.registryOrThrow(Registries.STRUCTURE)
                .getHolder(ResourceKey.create(Registries.STRUCTURE,
                        CityTemplateTerrainStructureRegistries.CITY_TEMPLATE_TERRAIN_STRUCTURE_ID));
        if (holder.isEmpty()) {
            CityReservationMaskRegistry.recordWorldgenFailure(item, chunkPos,
                    "CITY_TEMPLATE_TERRAIN_STRUCTURE_UNAVAILABLE",
                    "The registered City template terrain structure is absent from the world registry.");
            return;
        }
        Structure structure = holder.get().value();
        StructureStart existing = chunk.getStartForStructure(structure);
        if (existing != null && existing.isValid()) {
            return;
        }

        try {
            JsonObject plan = item.templatePlan();
            ResourceLocation templateRef = ResourceLocation.tryParse(text(plan, "templateRef",
                    nestedText(plan, "structureTemplate", "templateRef")));
            if (templateRef == null) {
                CityReservationMaskRegistry.recordWorldgenFailure(item, chunkPos,
                        "TEMPLATE_REF_INVALID", "Terrain StructureStart requires a valid templateRef.");
                return;
            }
            MinecraftCityTemplateReader.ReadResult read = new MinecraftCityTemplateReader(templateManager)
                    .read(templateRef);
            if (!read.success()) {
                CityReservationMaskRegistry.recordWorldgenFailure(item, chunkPos,
                        read.failureCode().name(), read.failureDetail());
                return;
            }
            String templateHash = text(plan, "templateHash", nestedText(plan, "structureTemplate", "templateHash"));
            if (!templateHash.equals(read.contentHash())) {
                CityReservationMaskRegistry.recordWorldgenFailure(item, chunkPos,
                        "TEMPLATE_HASH_MISMATCH", "D6 template hash differs from the current template NBT.");
                return;
            }
            CityTemplatePlacementGeometry.Size size = new CityTemplatePlacementGeometry.Size(
                    read.size().getX(), read.size().getY(), read.size().getZ());
            CityTemplatePlacementGeometry.Rotation rotation = CityTemplatePlacementGeometry.Rotation.valueOf(
                    text(plan, "rotation", "NONE"));
            CityTemplatePlacementGeometry.Mirror mirror = CityTemplatePlacementGeometry.Mirror.valueOf(
                    text(plan, "mirror", "NONE"));
            BlockPoint anchor = point(plan, "anchorBlock", item.anchorBlock());
            CityTemplatePlacementGeometry geometry = CityTemplatePlacementGeometry.of(size, rotation, mirror,
                    List.of());
            BlockBounds footprint = geometry.worldBounds(anchor);
            if (!item.lockedActualFootprint().equals(footprint)) {
                CityReservationMaskRegistry.recordWorldgenFailure(item, chunkPos,
                        "TEMPLATE_LOCKED_FOOTPRINT_MISMATCH",
                        "D6 locked footprint differs from the fixed template StructureStart piece.");
                return;
            }
            int datumY = generator.getBaseHeight(anchor.x(), anchor.z(),
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, chunk.getHeightAccessorForGeneration(), randomState);
            if (datumY <= chunk.getMinBuildHeight()) {
                CityReservationMaskRegistry.recordWorldgenFailure(item, chunkPos,
                        "TEMPLATE_DATUM_SURFACE_UNAVAILABLE",
                        "Generator base height did not provide a usable terrain datum.");
                return;
            }
            CityReservationMaskRegistry.TemplateDatumPreparation preparation =
                    CityReservationMaskRegistry.prepareTemplateTerrainStart(item, datumY);
            if (!preparation.ready()) {
                CityReservationMaskRegistry.recordWorldgenFailure(item, chunkPos,
                        preparation.reasonCode(), preparation.message());
                return;
            }
            CityTemplateTerrainStructurePiece piece = new CityTemplateTerrainStructurePiece(templateRef,
                    templateHash, item.anchorId(), anchor, preparation.templateDatumY().orElseThrow(), rotation,
                    mirror, size);
            StructureStart start = new StructureStart(structure, chunkPos, 0,
                    new PiecesContainer(List.of(piece)));
            chunk.setStartForStructure(structure, start);
            chunk.setUnsaved(true);
            LOGGER.info("Injected City template terrain StructureStart {} {} at {},{} with datum {}",
                    item.anchorId(), templateRef, chunkPos.x, chunkPos.z, datumY);
        } catch (RuntimeException ex) {
            CityReservationMaskRegistry.recordWorldgenFailure(item, chunkPos,
                    "TEMPLATE_TERRAIN_START_INJECTION_FAILED", ex.getMessage());
        }
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

    private static String templateSignature(CityReservationMaskRegistry.PlannedStructure item, String hash,
                                             CityTemplatePlacementGeometry.Rotation rotation,
                                             CityTemplatePlacementGeometry.Mirror mirror) {
        return "template:" + item.anchorId() + "#" + hash + "#" + rotation + "#" + mirror;
    }

    private static String text(JsonObject object, String key, String fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString() : fallback;
    }

    private static String nestedText(JsonObject object, String nestedKey, String key) {
        if (object != null && object.has(nestedKey) && object.get(nestedKey).isJsonObject()) {
            return text(object.getAsJsonObject(nestedKey), key, "");
        }
        return "";
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsInt() : fallback;
    }

    private static BlockPoint point(JsonObject object, String key, BlockPoint fallback) {
        if (object != null && object.has(key) && object.get(key).isJsonObject()) {
            JsonObject value = object.getAsJsonObject(key);
            return new BlockPoint(value.get("x").getAsInt(), value.get("z").getAsInt());
        }
        return fallback;
    }
}
