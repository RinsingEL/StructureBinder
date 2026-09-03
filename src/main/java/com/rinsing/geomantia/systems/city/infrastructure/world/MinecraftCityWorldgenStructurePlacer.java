package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.application.CityStructureMaterializationPlanner;
import com.rinsing.geomantia.systems.city.application.CityTemplatePlacementGeometry;
import com.rinsing.geomantia.systems.city.application.CityTemplateTerrainPosePolicy;
import com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityLandUseChunkExecutor;
import com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityLandUseWorldgenRegistry;
import com.rinsing.geomantia.systems.realm_planning.adapter.minecraft.RtfNativeTerrainSampler;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.state.BlockState;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.IntBinaryOperator;
import org.slf4j.Logger;

public final class MinecraftCityWorldgenStructurePlacer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int RTF_CALIBRATION_REGION_BLOCKS = 512;
    private static final int RTF_MAX_CALIBRATION_RESIDUAL_BLOCKS = 2;
    private static final Map<ChunkGenerator, GeneratorFastPath> RTF_FAST_PATHS =
            Collections.synchronizedMap(new WeakHashMap<>());

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
            LevelHeightAccessor heightAccessor = chunk.getHeightAccessorForGeneration();
            TerrainSamplingChoice terrainSampling = terrainSamplingChoice(generator, registryAccess,
                    randomState, heightAccessor, footprint);
            int datumY = CityLandUseWorldgenRegistry.resolveStructureFoundationDatum(
                            item.cityId(), footprint, terrainSampling.cacheIdentity(),
                            terrainSampling.sampler())
                    .orElseGet(() -> medianFoundationDatum(footprint,
                            (x, z) -> generator.getBaseHeight(x, z,
                                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                                    heightAccessor, randomState)));
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

    static int medianFoundationDatum(BlockBounds footprint, IntBinaryOperator heightAt) {
        List<Integer> heights = new java.util.ArrayList<>();
        for (int z : sampleAxis(footprint.minZ(), footprint.maxZ())) {
            for (int x : sampleAxis(footprint.minX(), footprint.maxX())) {
                heights.add(heightAt.applyAsInt(x, z));
            }
        }
        heights.sort(Integer::compareTo);
        int middle = heights.size() / 2;
        return heights.size() % 2 == 1 ? heights.get(middle)
                : Math.floorDiv(heights.get(middle - 1) + heights.get(middle), 2);
    }

    private static CityLandUseChunkExecutor.ColumnSample generatorTerrainSample(
            ChunkGenerator generator,
            LevelHeightAccessor heightAccessor,
            RandomState randomState,
            int worldX,
            int worldZ) {
        int firstFreeY = generator.getBaseHeight(worldX, worldZ,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, heightAccessor, randomState);
        int surfaceY = Math.max(heightAccessor.getMinBuildHeight(), firstFreeY - 1);
        NoiseColumn column = generator.getBaseColumn(worldX, worldZ, heightAccessor, randomState);
        BlockState surface = column.getBlock(surfaceY);
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(surface.getBlock());
        return new CityLandUseChunkExecutor.ColumnSample(surfaceY, blockId.toString(), true);
    }

    private static TerrainSamplingChoice terrainSamplingChoice(
            ChunkGenerator generator,
            RegistryAccess registryAccess,
            RandomState randomState,
            LevelHeightAccessor heightAccessor,
            BlockBounds footprint) {
        GeneratorFastPath fastPath;
        synchronized (RTF_FAST_PATHS) {
            fastPath = RTF_FAST_PATHS.computeIfAbsent(generator, ignored ->
                    new GeneratorFastPath(RtfNativeTerrainSampler.probe(randomState, registryAccess)));
        }
        if (!fastPath.probe().available()) {
            return exactTerrainSampling(generator, heightAccessor, randomState);
        }
        int centerX = Math.floorDiv(footprint.minX() + footprint.maxX(), 2);
        int centerZ = Math.floorDiv(footprint.minZ() + footprint.maxZ(), 2);
        long regionKey = (((long) Math.floorDiv(centerX, RTF_CALIBRATION_REGION_BLOCKS)) << 32)
                ^ (Math.floorDiv(centerZ, RTF_CALIBRATION_REGION_BLOCKS) & 0xffffffffL);
        FastCalibration calibration;
        synchronized (RTF_FAST_PATHS) {
            calibration = fastPath.calibrations().get(regionKey);
            if (calibration == null) {
                calibration = calibrateFastPath(fastPath.probe().sampler(), generator,
                        heightAccessor, randomState, footprint);
                fastPath.calibrations().put(regionKey, calibration);
                if (calibration.usable()) {
                    LOGGER.info("Enabled RTF native City terrain sampling for region {},{}: api={}, offset={}, maxResidual={}",
                            Math.floorDiv(centerX, RTF_CALIBRATION_REGION_BLOCKS),
                            Math.floorDiv(centerZ, RTF_CALIBRATION_REGION_BLOCKS),
                            fastPath.probe().sampler().apiVariant(), calibration.offsetBlocks(),
                            calibration.maxResidualBlocks());
                } else {
                    LOGGER.warn("RTF native City terrain sampling calibration rejected for region {},{}: {}; using exact generator sampling.",
                            Math.floorDiv(centerX, RTF_CALIBRATION_REGION_BLOCKS),
                            Math.floorDiv(centerZ, RTF_CALIBRATION_REGION_BLOCKS), calibration.reason());
                }
            }
        }
        if (!calibration.usable()) {
            return exactTerrainSampling(generator, heightAccessor, randomState);
        }
        RtfNativeTerrainSampler.Sampler sampler = fastPath.probe().sampler();
        int offset = calibration.offsetBlocks();
        CityLandUseWorldgenRegistry.ExactTerrainSampler terrain = (x, z) -> {
            RtfNativeTerrainSampler.Sample sample = sampler.sample(x, z);
            int rawSurfaceY = sample.water() ? sample.waterSurfaceElevation() : sample.elevation();
            return new CityLandUseChunkExecutor.ColumnSample(rawSurfaceY + offset,
                    sample.water() ? "minecraft:water" : "minecraft:grass_block", true);
        };
        return new TerrainSamplingChoice(calibration, terrain);
    }

    private static FastCalibration calibrateFastPath(
            RtfNativeTerrainSampler.Sampler sampler,
            ChunkGenerator generator,
            LevelHeightAccessor heightAccessor,
            RandomState randomState,
            BlockBounds footprint) {
        Set<BlockPoint> controls = new LinkedHashSet<>();
        controls.add(new BlockPoint(Math.floorDiv(footprint.minX() + footprint.maxX(), 2),
                Math.floorDiv(footprint.minZ() + footprint.maxZ(), 2)));
        controls.add(new BlockPoint(footprint.minX(), footprint.minZ()));
        controls.add(new BlockPoint(footprint.minX(), footprint.maxZ()));
        controls.add(new BlockPoint(footprint.maxX(), footprint.minZ()));
        controls.add(new BlockPoint(footprint.maxX(), footprint.maxZ()));
        List<Integer> offsets = new ArrayList<>();
        try {
            for (BlockPoint control : controls) {
                RtfNativeTerrainSampler.Sample fast = sampler.sample(control.x(), control.z());
                int fastSurfaceY = fast.water() ? fast.waterSurfaceElevation() : fast.elevation();
                int exactSurfaceY = generator.getBaseHeight(control.x(), control.z(),
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, heightAccessor, randomState) - 1;
                offsets.add(exactSurfaceY - fastSurfaceY);
            }
        } catch (RuntimeException ex) {
            return FastCalibration.rejected("sampling_failed:" + ex.getClass().getSimpleName());
        }
        offsets.sort(Comparator.naturalOrder());
        int medianOffset = offsets.get(offsets.size() / 2);
        int maxResidual = offsets.stream().mapToInt(value -> Math.abs(value - medianOffset)).max().orElse(0);
        if (maxResidual > RTF_MAX_CALIBRATION_RESIDUAL_BLOCKS) {
            return FastCalibration.rejected("calibration_residual=" + maxResidual);
        }
        return FastCalibration.usable(medianOffset, maxResidual);
    }

    private static TerrainSamplingChoice exactTerrainSampling(
            ChunkGenerator generator,
            LevelHeightAccessor heightAccessor,
            RandomState randomState) {
        return new TerrainSamplingChoice(generator,
                (x, z) -> generatorTerrainSample(generator, heightAccessor, randomState, x, z));
    }

    private static List<Integer> sampleAxis(int minimum, int maximum) {
        List<Integer> result = new java.util.ArrayList<>();
        for (int value = minimum; value <= maximum; value += 4) result.add(value);
        if (result.get(result.size() - 1) != maximum) result.add(maximum);
        return result;
    }

    private record TerrainSamplingChoice(Object cacheIdentity,
                                         CityLandUseWorldgenRegistry.ExactTerrainSampler sampler) {
    }

    private record GeneratorFastPath(RtfNativeTerrainSampler.Probe probe,
                                     Map<Long, FastCalibration> calibrations) {
        private GeneratorFastPath(RtfNativeTerrainSampler.Probe probe) {
            this(probe, new java.util.HashMap<>());
        }
    }

    private record FastCalibration(boolean usable,
                                   int offsetBlocks,
                                   int maxResidualBlocks,
                                   String reason) {
        private static FastCalibration usable(int offsetBlocks, int maxResidualBlocks) {
            return new FastCalibration(true, offsetBlocks, maxResidualBlocks, "");
        }

        private static FastCalibration rejected(String reason) {
            return new FastCalibration(false, 0, Integer.MAX_VALUE, reason);
        }
    }
}
