package com.rinsing.geomantia.systems.city.testsupport;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan;
import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlanCodec;
import com.rinsing.geomantia.systems.city.application.landuse.LandUseAreaPlanCodec;
import com.rinsing.geomantia.systems.city.domain.landuse.BoundaryPolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSurfaceSettings;
import com.rinsing.geomantia.systems.city.domain.landuse.SurfacePolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.VegetationPolicy;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityLandUseChunkCompiler;
import com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityLandUseChunkExecutor;
import com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityLandUseWorldgenRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.gametest.GameTestHolder;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@GameTestHolder("geomantia")
public final class CityLandUseGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int TEST_CHUNK_OFFSET = 65_536;
    private static final int TEST_CHUNK_RANGE = 1_000_000;

    private CityLandUseGameTests() {
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void generationMaskGuardsForeignWritesButAllowsCityAndPlayer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos protectedPos = helper.absolutePos(new BlockPos(2, 0, 2)).atY(level.getMaxBuildHeight() - 16);
        BlockPos outside = protectedPos.east(3);
        String cityId = "gametest_generation_mask_" + UUID.randomUUID();
        String dimension = level.dimension().location().toString();
        Path root = level.getServer().getWorldPath(LevelResource.ROOT);
        var areas = worldgenSmokeAreaPlan(cityId, protectedPos.getX(), protectedPos.getX(),
                protectedPos.getZ(), protectedPos.getZ() + 1);
        CityLandUseWorldgenRegistry.activate(dimension, areas,
                worldgenSmokeSurfacePlan(areas, protectedPos.getX(), protectedPos.getX(), protectedPos.getZ()), root);
        try {
            // Deliberately unnamed feature originating OUTSIDE the protected column.
            var feature = new net.minecraft.world.level.levelgen.feature.Feature<net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration>(
                    net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration.CODEC) {
                @Override public boolean place(net.minecraft.world.level.levelgen.feature.FeaturePlaceContext<net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration> context) {
                    context.level().setBlock(protectedPos, Blocks.OAK_LEAVES.defaultBlockState(), 2);
                    context.level().setBlock(outside, Blocks.OAK_LOG.defaultBlockState(), 2);
                    return true;
                }
            };
            var configured = new net.minecraft.world.level.levelgen.feature.ConfiguredFeature<>(feature,
                    net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration.INSTANCE);
            var chunk = new ChunkPos(protectedPos);
            List<net.minecraft.world.level.chunk.ChunkAccess> chunks = new ArrayList<>();
            for (int z = chunk.z - 1; z <= chunk.z + 1; z++)
                for (int x = chunk.x - 1; x <= chunk.x + 1; x++) chunks.add(level.getChunk(x, z));
            var region = new net.minecraft.server.level.WorldGenRegion(level, chunks, ChunkStatus.FEATURES, 1);
            for (net.minecraft.world.level.WorldGenLevel target : List.of(level, region)) {
                level.setBlock(protectedPos, Blocks.AIR.defaultBlockState(), 2);
                level.setBlock(outside, Blocks.AIR.defaultBlockState(), 2);
                configured.place(target, level.getChunkSource().getGenerator(), level.random, outside);
                if (!level.getBlockState(protectedPos).isAir() || !level.getBlockState(outside).is(Blocks.OAK_LOG))
                    throw new IllegalStateException("Foreign canopy mask failed for " + target.getClass()
                            + ", protected=" + level.getBlockState(protectedPos) + ", outside=" + level.getBlockState(outside)
                            + ", pos=" + protectedPos + ", mask="
                            + com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityGenerationProtection.protects(level, protectedPos));
            }
            boolean ownWrite = com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityFeatureWriteGuard.run(() ->
                    com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityFeatureWriteGuard.city(() ->
                            level.setBlock(protectedPos, Blocks.STONE_BRICKS.defaultBlockState(), 2)));
            if (!ownWrite || !level.getBlockState(protectedPos).is(Blocks.STONE_BRICKS))
                throw new IllegalStateException("City write was blocked");
            if (!level.setBlock(protectedPos, Blocks.GOLD_BLOCK.defaultBlockState(), 2))
                throw new IllegalStateException("Ordinary player/server write was blocked");
            var box = new net.minecraft.world.level.levelgen.structure.BoundingBox(
                    outside.getX(), protectedPos.getY(), protectedPos.getZ(),
                    outside.getX() + 2, protectedPos.getY() + 5, protectedPos.getZ() + 2);
            if (com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityGenerationProtection.intersects(level, box))
                throw new IllegalStateException("Outside structure was blocked");
            box = new net.minecraft.world.level.levelgen.structure.BoundingBox(
                    protectedPos.getX(), protectedPos.getY(), protectedPos.getZ(),
                    outside.getX(), protectedPos.getY() + 5, protectedPos.getZ());
            if (!com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityGenerationProtection.intersects(level, box))
                throw new IllegalStateException("Intruding structure was allowed");
            var foreign = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE)
                    .getOrThrow(net.minecraft.world.level.levelgen.structure.BuiltinStructures.IGLOO);
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            var owner = new net.minecraft.world.level.levelgen.structure.BoundingBox(
                    chunk.getMinBlockX() - 16, level.getMinBuildHeight(), chunk.getMinBlockZ() - 16,
                    chunk.getMaxBlockX() + 16, level.getMaxBuildHeight() - 1, chunk.getMaxBlockZ() + 16);
            exerciseStructureStart(foreign, box, protectedPos, calls, level, owner, chunk);
            if (calls.get() != 0) throw new IllegalStateException("Intruding structure piece was executed");
            var own = new com.rinsing.geomantia.systems.city.infrastructure.world.CityTemplateTerrainStructure(
                    new net.minecraft.world.level.levelgen.structure.Structure.StructureSettings(
                            net.minecraft.core.HolderSet.direct(), java.util.Map.of(),
                            net.minecraft.world.level.levelgen.GenerationStep.Decoration.SURFACE_STRUCTURES,
                            net.minecraft.world.level.levelgen.structure.TerrainAdjustment.NONE));
            exerciseStructureStart(own, box, protectedPos, calls, level, owner, chunk);
            if (calls.get() != 1 || !level.getBlockState(protectedPos).is(Blocks.EMERALD_BLOCK))
                throw new IllegalStateException("City structure was blocked");
            helper.succeed();
        } finally {
            CityLandUseWorldgenRegistry.deactivate(dimension, cityId, root);
            level.setBlock(protectedPos, Blocks.AIR.defaultBlockState(), 2);
            level.setBlock(outside, Blocks.AIR.defaultBlockState(), 2);
        }
    }

    private static void exerciseStructureStart(net.minecraft.world.level.levelgen.structure.Structure structure,
            net.minecraft.world.level.levelgen.structure.BoundingBox box, BlockPos write,
            java.util.concurrent.atomic.AtomicInteger calls, ServerLevel level,
            net.minecraft.world.level.levelgen.structure.BoundingBox owner, ChunkPos chunk) {
        var piece = new net.minecraft.world.level.levelgen.structure.StructurePiece(
                net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType.IGLOO, 0, box) {
            @Override protected void addAdditionalSaveData(
                    net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext context,
                    net.minecraft.nbt.CompoundTag tag) {}
            @Override public void postProcess(net.minecraft.world.level.WorldGenLevel world,
                    net.minecraft.world.level.StructureManager manager,
                    net.minecraft.world.level.chunk.ChunkGenerator generator, net.minecraft.util.RandomSource random,
                    net.minecraft.world.level.levelgen.structure.BoundingBox bounds, ChunkPos position, BlockPos origin) {
                calls.incrementAndGet(); world.setBlock(write, Blocks.EMERALD_BLOCK.defaultBlockState(), 2);
            }
        };
        new net.minecraft.world.level.levelgen.structure.StructureStart(structure, chunk, 0,
                new net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer(List.of(piece)))
                .placeInChunk(level, level.structureManager(), level.getChunkSource().getGenerator(),
                        level.random, owner, chunk);
    }

    @GameTest(template = "empty")
    public static void landUseFenceUpdatesBothConnections(GameTestHelper helper) {
        BlockPos west = helper.absolutePos(BlockPos.ZERO.above());
        BlockPos east = west.east();
        CityLandUseChunkExecutor.WorldGenExecutionWorld world =
                new CityLandUseChunkExecutor.WorldGenExecutionWorld(helper.getLevel());

        if (!world.setBoundaryBlockRaw(west.getX(), west.getY(), west.getZ(), "minecraft:oak_fence")
                || !world.setBoundaryBlockRaw(east.getX(), east.getY(), east.getZ(), "minecraft:oak_fence")
                || !world.finalizeBoundaryConnections(List.of(
                new CityLandUseChunkExecutor.BlockPosition(west.getX(), west.getY(), west.getZ()),
                new CityLandUseChunkExecutor.BlockPosition(east.getX(), east.getY(), east.getZ()))).success()) {
            helper.fail("LandUse fence placement returned false");
            return;
        }
        BlockState westState = helper.getLevel().getBlockState(west);
        BlockState eastState = helper.getLevel().getBlockState(east);
        if (!westState.getValue(CrossCollisionBlock.EAST)
                || !eastState.getValue(CrossCollisionBlock.WEST)) {
            helper.fail("Adjacent LandUse fences did not update both connection states");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void landUseWorldgenCrossChunkCropAndFence(GameTestHelper helper) {
        UUID nonce = UUID.randomUUID();
        WorldgenSmokeTarget target = worldgenSmokeTarget(nonce);
        String cityId = "gametest_land_use_worldgen_" + nonce.toString().replace("-", "");
        int minX = target.minX();
        int maxX = target.maxX();
        int cropZ = target.cropZ();
        int fenceZ = target.fenceZ();
        ServerLevel level = helper.getLevel();
        Path serverRoot = level.getServer().getWorldPath(LevelResource.ROOT);
        LOGGER.info("LandUse cross-chunk GameTest nonce={} cityId={} x={}..{} cropZ={} fenceZ={} seamX={}",
                nonce, cityId, minX, maxX, cropZ, fenceZ, target.seamX());
        String failure = null;
        boolean activated = false;
        try {
            LandUseAreaPlan areaPlan = worldgenSmokeAreaPlan(cityId, minX, maxX, cropZ, fenceZ);
            CityLandUseSurfacePrintPlan surfacePlan = worldgenSmokeSurfacePlan(
                    areaPlan, minX, maxX, cropZ);
            CityLandUseWorldgenRegistry.activate(
                    level.dimension().location().toString(), areaPlan, surfacePlan, serverRoot);
            activated = true;
            CityLandUseWorldgenGameTestFixture.enable(minX, maxX, cropZ, fenceZ);

            int minChunkX = Math.floorDiv(minX, 16);
            int maxChunkX = Math.floorDiv(maxX, 16);
            int chunkZ = Math.floorDiv(cropZ, 16);
            if (maxX - minX + 1 != 20
                    || minChunkX + 1 != maxChunkX
                    || target.seamX() != maxChunkX * 16
                    || minX >= target.seamX()
                    || maxX < target.seamX()) {
                throw new IllegalStateException("Dynamic test line is not exactly 20 blocks across one seam: "
                        + target);
            }
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, true);
            }

            for (int x = minX; x <= maxX; x++) {
                int farmlandY = findBlockY(level, x, cropZ, Blocks.FARMLAND);
                if (farmlandY == Integer.MIN_VALUE
                        || !level.getBlockState(new BlockPos(x, farmlandY + 1, cropZ)).is(Blocks.WHEAT)) {
                    throw new IllegalStateException("Missing farmland/wheat at x=" + x
                            + ", farmlandY=" + farmlandY);
                }
            }
            List<BlockPos> fenceLine = new ArrayList<>();
            for (int x = minX; x <= maxX; x++) {
                int fenceY = findBlockY(level, x, fenceZ, Blocks.OAK_FENCE);
                if (fenceY == Integer.MIN_VALUE) {
                    throw new IllegalStateException("Missing oak fence at x=" + x);
                }
                fenceLine.add(new BlockPos(x, fenceY, fenceZ));
            }
            for (int index = 0; index < fenceLine.size() - 1; index++) {
                BlockPos west = fenceLine.get(index);
                BlockPos east = fenceLine.get(index + 1);
                if (west.getY() != east.getY()
                        || !level.getBlockState(west).getValue(CrossCollisionBlock.EAST)
                        || !level.getBlockState(east).getValue(CrossCollisionBlock.WEST)) {
                    throw new IllegalStateException("Fence connection mismatch between " + west + " and " + east);
                }
            }
            BlockPos seamWest = fenceLine.get(target.seamX() - 1 - minX);
            BlockPos seamEast = fenceLine.get(target.seamX() - minX);
            if (!level.getBlockState(seamWest).getValue(CrossCollisionBlock.EAST)
                    || !level.getBlockState(seamEast).getValue(CrossCollisionBlock.WEST)) {
                throw new IllegalStateException("Cross-chunk fence seam is not bidirectional");
            }
            assertLedgerCounts(cityId, 20, 20);
        } catch (RuntimeException | Error ex) {
            failure = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        } finally {
            CityLandUseWorldgenGameTestFixture.disable();
            if (activated) {
                try {
                    CityLandUseWorldgenRegistry.deactivate(
                            level.dimension().location().toString(), cityId, serverRoot);
                } catch (RuntimeException ex) {
                    failure = failure == null
                            ? "LandUse test cleanup failed: " + ex.getMessage()
                            : failure + "; cleanup failed: " + ex.getMessage();
                }
            }
        }
        if (failure != null) {
            helper.fail(failure);
            return;
        }
        helper.succeed();
    }

    static WorldgenSmokeTarget worldgenSmokeTarget(UUID nonce) {
        int seamChunkX = TEST_CHUNK_OFFSET
                + Math.floorMod(nonce.getMostSignificantBits(), TEST_CHUNK_RANGE);
        int chunkZ = TEST_CHUNK_OFFSET
                + Math.floorMod(nonce.getLeastSignificantBits(), TEST_CHUNK_RANGE);
        int seamX = seamChunkX * 16;
        int minX = seamX - 8;
        int cropZ = chunkZ * 16 + 6;
        return new WorldgenSmokeTarget(minX, minX + 19, cropZ, cropZ + 2, seamX);
    }

    record WorldgenSmokeTarget(int minX, int maxX, int cropZ, int fenceZ, int seamX) {
    }

    @GameTest(template = "micro_fill")
    public static void landUsePaveFillsSmallDepression(GameTestHelper helper) {
        BlockPos centerLocal = new BlockPos(4, 10, 4);
        BlockPos center = helper.absolutePos(centerLocal);
        helper.setBlock(centerLocal.below(2), Blocks.DIRT);
        helper.setBlock(centerLocal.below(), Blocks.AIR);
        helper.setBlock(centerLocal, Blocks.AIR);

        List<CityLandUseChunkCompiler.GradingMaskCell> mask = new ArrayList<>();
        for (int z = center.getZ() - 3; z <= center.getZ() + 3; z++) {
            for (int x = center.getX() - 3; x <= center.getX() + 3; x++) {
                mask.add(new CityLandUseChunkCompiler.GradingMaskCell("area", x, z));
            }
        }
        CityLandUseChunkCompiler.ChunkFragment fragment = new CityLandUseChunkCompiler.ChunkFragment(
                CityLandUseChunkCompiler.RESULT_SCHEMA, "city", "hash", "palette",
                center.getX() >> 4, center.getZ() >> 4, 1, 0, 0, 0,
                "minecraft:dirt", mask,
                List.of(new CityLandUseChunkCompiler.SurfaceOperation("area", "plaza",
                        center.getX(), center.getZ(), "minecraft:stone_bricks")), List.of());
        CityLandUseChunkExecutor.WorldGenExecutionWorld delegate =
                new CityLandUseChunkExecutor.WorldGenExecutionWorld(helper.getLevel());
        CityLandUseChunkExecutor.ExecutionWorld executionWorld = new CityLandUseChunkExecutor.ExecutionWorld() {
            @Override
            public CityLandUseChunkExecutor.ColumnSample sampleColumn(int worldX, int worldZ) {
                int surfaceY = worldX == center.getX() && worldZ == center.getZ()
                        ? center.getY() - 2
                        : center.getY();
                return new CityLandUseChunkExecutor.ColumnSample(surfaceY, "minecraft:dirt", true);
            }

            @Override
            public boolean isKnownBlock(String blockId) {
                return delegate.isKnownBlock(blockId);
            }

            @Override
            public boolean ensureCanWrite(int worldX, int y, int worldZ) {
                return delegate.ensureCanWrite(worldX, y, worldZ);
            }

            @Override
            public CityLandUseChunkExecutor.TargetState inspect(int worldX, int y, int worldZ) {
                return delegate.inspect(worldX, y, worldZ);
            }

            @Override
            public boolean setBlock(int worldX, int y, int worldZ, String blockId) {
                return delegate.setBlock(worldX, y, worldZ, blockId);
            }

            @Override
            public boolean restoreBlock(int worldX, int y, int worldZ, Object snapshot) {
                return delegate.restoreBlock(worldX, y, worldZ, snapshot);
            }
        };
        CityLandUseChunkExecutor.ColumnSample centerBefore = executionWorld.sampleColumn(
                center.getX(), center.getZ());
        CityLandUseChunkExecutor.ColumnSample northBefore = executionWorld.sampleColumn(
                center.getX(), center.getZ() - 1);
        CityLandUseChunkExecutor.ExecutionResult result = new CityLandUseChunkExecutor().execute(fragment,
                executionWorld,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES);

        if (result.status() != CityLandUseChunkExecutor.Status.APPLIED
                || !helper.getLevel().getBlockState(center).is(Blocks.STONE_BRICKS)
                || !helper.getLevel().getBlockState(center.below()).is(Blocks.DIRT)) {
            helper.fail("LandUse PAVE micro-fill mismatch: centerBefore=" + centerBefore
                    + ", northBefore=" + northBefore + ", result=" + result
                    + ", top=" + helper.getLevel().getBlockState(center)
                    + ", subgrade=" + helper.getLevel().getBlockState(center.below())
                    + ", oldSurface=" + helper.getLevel().getBlockState(center.below(2)));
            return;
        }
        helper.succeed();
    }

    private static LandUseAreaPlan worldgenSmokeAreaPlan(String cityId,
                                                          int minX,
                                                          int maxX,
                                                          int cropZ,
                                                          int fenceZ) {
        LandUseAreaPlan.ScanlineSpan cropSpan = new LandUseAreaPlan.ScanlineSpan(cropZ, minX, maxX);
        LandUseAreaPlan.ScanlineSpan fenceSpan = new LandUseAreaPlan.ScanlineSpan(fenceZ, minX, maxX);
        LandUseAreaPlan.Area cropArea = new LandUseAreaPlan.Area(
                "crop_line", "gametest", "agriculture", List.of("crop_group"), List.of("crop_anchor"),
                List.of(new BlockPoint(minX, cropZ)), List.of(cropSpan), List.of(), List.of(), List.of(),
                20, SurfacePolicy.CULTIVATE, VegetationPolicy.CLEAR, BoundaryPolicy.OPEN);
        List<BlockPoint> fencePoints = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) fencePoints.add(new BlockPoint(x, fenceZ));
        LandUseAreaPlan.Area fenceArea = new LandUseAreaPlan.Area(
                "fence_line", "gametest", "agriculture", List.of("fence_group"), List.of("fence_anchor"),
                List.of(new BlockPoint(minX, fenceZ)), List.of(fenceSpan), List.of(),
                List.of(new LandUseAreaPlan.BoundaryLoop(fencePoints, false)), List.of(),
                20, SurfacePolicy.PRESERVE, VegetationPolicy.PRESERVE, BoundaryPolicy.FENCE);
        return new LandUseAreaPlanCodec().withComputedHash(new LandUseAreaPlan(
                LandUseAreaPlan.SCHEMA, "land_use_rules", cityId, "",
                new BlockBounds(minX, cropZ, maxX, fenceZ),
                List.of(cropArea, fenceArea), List.of(), List.of(), List.of()));
    }

    private static CityLandUseSurfacePrintPlan worldgenSmokeSurfacePlan(LandUseAreaPlan areaPlan,
                                                                         int minX,
                                                                         int maxX,
                                                                         int cropZ) {
        LandUseAreaPlan.Area cropArea = areaPlan.areas().stream()
                .filter(area -> "crop_line".equals(area.areaId()))
                .findFirst().orElseThrow();
        BlockPoint anchor = new BlockPoint(minX, cropZ);
        LandUseSurfaceSettings settings = new LandUseSurfaceSettings(
                true, true, "minecraft:farmland", "minecraft:wheat", SurfacePolicy.CULTIVATE.name(),
                LandUseSurfaceSettings.SurfaceAlgorithm.CONTOUR_BANDS, anchor,
                "minecraft:dirt", "minecraft:water", "minecraft:oak_slab");
        CityLandUseSurfacePrintPlan.ContourBandsRecipe recipe =
                new CityLandUseSurfacePrintPlan.ContourBandsRecipe(
                        settings.surfaceBlockId(), settings.cropBlockId(), settings.channelBankBlockId(),
                        settings.channelWaterBlockId(), settings.channelBankOverlayBlockId(),
                        settings.fieldBeforeBlocks() + settings.channelWidthBlocks() + settings.fieldAfterBlocks(),
                        settings.fieldBeforeBlocks(), settings.channelWidthBlocks(), settings.fieldAfterBlocks(),
                        CityLandUseSurfacePrintPlan.ClassificationMode.CONTOUR_NORMAL,
                        anchor, List.of(new CityLandUseSurfacePrintPlan.BandSpan(
                        cropZ, minX, maxX, CityLandUseSurfacePrintPlan.BandRole.FIELD)));
        CityLandUseSurfacePrintPlan.AreaPrint print = new CityLandUseSurfacePrintPlan.AreaPrint(
                "crop_line/surface", cropArea.areaId(), cropArea.sourceGroupIds(), settings,
                cropArea.memberSpans(), List.of(), LandUseSurfaceSettings.SurfaceAlgorithm.CONTOUR_BANDS,
                anchor, recipe);
        return new CityLandUseSurfacePrintPlanCodec().withComputedHash(new CityLandUseSurfacePrintPlan(
                CityLandUseSurfacePrintPlan.SCHEMA, areaPlan.cityId(),
                areaPlan.planHash(), "", List.of(print)));
    }

    private static int findBlockY(ServerLevel level, int x, int z, net.minecraft.world.level.block.Block block) {
        for (int y = level.getMaxBuildHeight() - 1; y >= level.getMinBuildHeight(); y--) {
            if (level.getBlockState(new BlockPos(x, y, z)).is(block)) return y;
        }
        return Integer.MIN_VALUE;
    }

    private static void assertLedgerCounts(String cityId, int expectedCrop, int expectedBoundary) {
        int crop = 0;
        int boundary = 0;
        int owners = 0;
        for (JsonElement element : CityLandUseWorldgenRegistry.ledgerSnapshot()
                .getAsJsonArray("appliedOwners")) {
            JsonObject owner = element.getAsJsonObject();
            if (!cityId.equals(owner.get("cityId").getAsString())) continue;
            owners++;
            crop += owner.get("appliedCropOperationCount").getAsInt();
            boundary += owner.get("appliedBoundaryOperationCount").getAsInt();
        }
        if (owners != 2 || crop != expectedCrop || boundary != expectedBoundary) {
            throw new IllegalStateException("Ledger mismatch: owners=" + owners
                    + ", crop=" + crop + ", boundary=" + boundary);
        }
    }
}
