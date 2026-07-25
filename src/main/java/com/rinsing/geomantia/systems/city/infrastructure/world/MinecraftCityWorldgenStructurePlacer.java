package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.application.CityStructureMaterializationPlanner;
import com.rinsing.geomantia.systems.city.application.CityTemplatePlacementGeometry;
import com.rinsing.geomantia.systems.city.application.CityTemplateTerrainPosePolicy;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;

public final class MinecraftCityWorldgenStructurePlacer {
    private static final Logger LOGGER = LogUtils.getLogger();

    private MinecraftCityWorldgenStructurePlacer() {
    }

    public static void injectPlannedTemplateTerrainStarts(ChunkGenerator generator,
                                               RegistryAccess registryAccess,
                                               ChunkGeneratorStructureState structureState,
                                               ChunkAccess chunk,
                                               StructureTemplateManager templateManager) {
        ChunkPos chunkPos = chunk.getPos();
        List<CityReservationMaskRegistry.PlannedStructure> planned =
                CityReservationMaskRegistry.plannedStructuresForChunk(chunkPos);
        if (planned.isEmpty()) {
            return;
        }
        for (CityReservationMaskRegistry.PlannedStructure item : planned) {
            tryInject(generator, registryAccess, structureState.randomState(),
                    chunk, templateManager, chunkPos, item);
        }
    }

    private static void tryInject(ChunkGenerator generator,
                                  RegistryAccess registryAccess,
                                   RandomState randomState,
                                   ChunkAccess chunk,
                                   StructureTemplateManager templateManager,
                                  ChunkPos chunkPos,
                                  CityReservationMaskRegistry.PlannedStructure item) {
        if (!item.isTemplatePlacement()) {
            CityReservationMaskRegistry.recordWorldgenFailure(item, chunkPos,
                    "CITY_CONFIGURED_STRUCTURE_FLOW_REMOVED",
                    "City worldgen accepts fixed NBT template placements only.");
            return;
        }
        if (CityTemplateTerrainStartPolicy.usesStructureStart(item)) {
            tryInjectTemplateTerrainStart(generator, registryAccess, randomState, chunk, templateManager,
                    chunkPos, item);
        }
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
            String terrainPosePolicy = text(plan, "terrainPosePolicy",
                    nestedText(plan, "structureTemplate", "terrainPosePolicy"));
            String datumPolicy = text(plan, "templateDatumPolicy", "");
            if (!CityTemplateTerrainPosePolicy.usesStructureStart(terrainPosePolicy)
                    || !CityStructureMaterializationPlanner.TEMPLATE_DATUM_POLICY_GENERATOR_BASE_HEIGHT
                    .equals(datumPolicy)) {
                CityReservationMaskRegistry.recordWorldgenFailure(item, chunkPos,
                        "TEMPLATE_TERRAIN_START_POLICY_INVALID",
                        "Terrain StructureStart requires terrainPosePolicy="
                                + CityTemplateTerrainPosePolicy.STRUCTURE_START_BEARD_THIN
                                + " and templateDatumPolicy="
                                + CityStructureMaterializationPlanner.TEMPLATE_DATUM_POLICY_GENERATOR_BASE_HEIGHT + ".");
                return;
            }
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

    private static BlockPoint point(JsonObject object, String key, BlockPoint fallback) {
        if (object != null && object.has(key) && object.get(key).isJsonObject()) {
            JsonObject value = object.getAsJsonObject(key);
            return new BlockPoint(value.get("x").getAsInt(), value.get("z").getAsInt());
        }
        return fallback;
    }
}
