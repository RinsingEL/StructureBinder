package com.rinsing.geomantia.systems.city.testsupport;

import com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityLandUseChunkCompiler;
import com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityLandUseChunkExecutor;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;

import java.util.ArrayList;
import java.util.List;

@GameTestHolder("geomantia")
public final class CityLandUseGameTests {
    private CityLandUseGameTests() {
    }

    @GameTest(template = "empty")
    public static void landUseFenceUpdatesBothConnections(GameTestHelper helper) {
        BlockPos west = helper.absolutePos(BlockPos.ZERO.above());
        BlockPos east = west.east();
        CityLandUseChunkExecutor.WorldGenExecutionWorld world =
                new CityLandUseChunkExecutor.WorldGenExecutionWorld(helper.getLevel());

        if (!world.setBlock(west.getX(), west.getY(), west.getZ(), "minecraft:oak_fence")
                || !world.setBlock(east.getX(), east.getY(), east.getZ(), "minecraft:oak_fence")) {
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
}
