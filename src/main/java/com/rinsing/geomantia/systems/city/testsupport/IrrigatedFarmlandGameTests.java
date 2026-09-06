package com.rinsing.geomantia.systems.city.testsupport;

import com.mojang.logging.LogUtils;
import com.rinsing.geomantia.systems.city.application.landuse.*;
import com.rinsing.geomantia.systems.city.domain.landuse.*;
import com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseRule;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityLandUseChunkCompiler;
import com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityLandUseChunkExecutor;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;

import java.util.ArrayList;
import java.util.List;

/** Explicit, isolated in-game plots. No activation or alteration of a player's city plan. */
@GameTestHolder("geomantia")
public final class IrrigatedFarmlandGameTests {
    private record Plot(int x, int z, boolean slope, List<BlockPos> water) {}

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void curvedChannelsSurviveFluidTicks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        int x = Math.floorDiv(origin.getX() + 64, 16) * 16;
        int z = Math.floorDiv(origin.getZ(), 16) * 16;
        long started = System.nanoTime();
        List<Plot> plots = List.of(build(level, x, z, false, false),
                build(level, x + 64, z, true, false), build(level, x + 128, z, true, true));
        LogUtils.getLogger().info("IRRIGATED_FARM_PLOTS x={} z={} y=220 size=48 flat/slope/reverse owners elapsedMs={}",
                x, z, (System.nanoTime() - started) / 1_000_000);
        helper.runAfterDelay(160, () -> {
            for (Plot plot : plots) verify(level, plot);
            // Reverse owner order must produce exactly the same geometry and fluid state.
            Plot a = plots.get(1), b = plots.get(2);
            for (int dz = -1; dz <= 48; dz++) for (int dx = -1; dx <= 48; dx++) {
                for (int y = 216; y <= 232; y++) {
                    BlockState first = level.getBlockState(new BlockPos(a.x + dx, y, a.z + dz));
                    BlockState second = level.getBlockState(new BlockPos(b.x + dx, y, b.z + dz));
                    // Random crop age/moisture ticks are independent; compare terrain/block identity.
                    if (first.getBlock() != second.getBlock()) {
                        throw new IllegalStateException("Owner-order mismatch at " + dx + "," + y + "," + dz);
                    }
                }
            }
            LogUtils.getLogger().info("IRRIGATED_FARM_PASS flatWater={} slopeWater={} reverseWater={} ticks=160",
                    plots.get(0).water.size(), a.water.size(), b.water.size());
            helper.succeed();
        });
    }

    private static int height(int x, int z, boolean slope) {
        return 220 + (slope ? Math.floorDiv(x, 16) + Math.floorDiv(z, 24) : 0);
    }

    private static Plot build(ServerLevel level, int minX, int minZ, boolean slope, boolean reverse) {
        String city = "irrigation_plot_" + minX + "_" + minZ;
        BlockBounds bounds = new BlockBounds(minX, minZ, minX + 47, minZ + 47);
        for (int dz = -2; dz < 50; dz++) for (int dx = -2; dx < 50; dx++) {
            int top = height(dx, dz, slope);
            for (int y = 216; y <= 233; y++) level.setBlock(new BlockPos(minX + dx, y, minZ + dz),
                    y < top ? Blocks.DIRT.defaultBlockState() : y == top
                            ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
        }
        List<LandUseAreaPlan.ScanlineSpan> spans = new ArrayList<>();
        for (int dz = 0; dz < 48; dz++) {
            // Irregular ends exercise frozen end caps rather than a rectangular-only happy path.
            int inset = dz < 6 ? 6 - dz : dz > 41 ? dz - 41 : 0;
            spans.add(new LandUseAreaPlan.ScanlineSpan(minZ + dz, minX + inset, minX + 47 - inset));
        }
        BlockPoint anchor = new BlockPoint(minX + 24, minZ + 24);
        LandUseAreaPlan.Area area = new LandUseAreaPlan.Area("farm", "test", "agriculture",
                List.of("farm"), List.of(), List.of(anchor), spans, List.of(), List.of(), List.of(),
                spans.stream().mapToInt(LandUseAreaPlan.ScanlineSpan::blockCount).sum(),
                SurfacePolicy.CULTIVATE, VegetationPolicy.CLEAR, BoundaryPolicy.OPEN);
        LandUseAreaPlan plan = new LandUseAreaPlanCodec().withComputedHash(new LandUseAreaPlan(
                LandUseAreaPlan.SCHEMA, "test", city, "", bounds, List.of(area), List.of(), List.of(), List.of()));
        LandUseSurfaceSettings settings = new LandUseSurfaceSettings(true, true, "minecraft:farmland",
                "minecraft:wheat", "CULTIVATE", LandUseSurfaceSettings.SurfaceAlgorithm.CONTOUR_BANDS,
                anchor, "minecraft:dirt", "minecraft:water", "minecraft:stone_brick_slab", "", 3, 3, 3);
        LandUseRule rule = new LandUseRule("farm", "farm", List.of("farm"), 1, 0, 1, 4096,
                4096, 1, 0, 0, 10, 0, 1, false, SurfacePolicy.CULTIVATE,
                VegetationPolicy.CLEAR, BoundaryPolicy.OPEN);
        LandUseSeedGroup group = new LandUseSeedGroup("farm", rule, settings, List.of(), List.of(),
                List.of(anchor), List.of(), 1, 2048, 4096, 4096, 1);
        List<LandUseTerrainField.Cell> cells = new ArrayList<>();
        for (int dz = -4; dz <= 52; dz += 4) for (int dx = -4; dx <= 52; dx += 4) {
            cells.add(new LandUseTerrainField.Cell(Math.floorDiv(minX + dx, 4), Math.floorDiv(minZ + dz, 4),
                    minX + dx, minZ + dz, 4, height(dx, dz, slope), slope ? .06 : 0,
                    0, 0, false, 0, 0, "minecraft:plains", "plain", "", true));
        }
        var terrain = new LandUseTerrainField(LandUseTerrainField.SCHEMA, city, bounds, 4, cells);
        var surface = new CityLandUseSurfacePrintPlanner().plan(plan, List.of(group), terrain);
        var compiler = new CityLandUseChunkCompiler();
        var prepared = compiler.prepare(plan, surface);
        var executor = new CityLandUseChunkExecutor();
        var world = new CityLandUseChunkExecutor.WorldGenExecutionWorld(level);
        for (int i = 0; i < 9; i++) {
            int owner = reverse ? 8 - i : i;
            var fragment = compiler.compilePrepared(prepared, minX / 16 + owner % 3, minZ / 16 + owner / 3);
            var result = executor.execute(fragment, world,
                    CityLandUseChunkExecutor.GenerationEligibility.CONTROLLED_D7_BACKFILL);
            if (result.status() != CityLandUseChunkExecutor.Status.APPLIED)
                throw new IllegalStateException("Farm owner failed: " + result.reasonCode());
        }
        List<BlockPos> water = new ArrayList<>();
        for (int dz = 0; dz < 48; dz++) for (int dx = 0; dx < 48; dx++) {
            BlockPos ground = new BlockPos(minX + dx, height(dx, dz, slope), minZ + dz);
            if (level.getBlockState(ground).is(Blocks.WATER)) {
                water.add(ground);
                level.scheduleTick(ground, net.minecraft.world.level.material.Fluids.WATER, 5);
            }
            // Mature crops for visual inspection; geometry and farmland moisture remain real.
            if (level.getBlockState(ground.above()).is(Blocks.WHEAT)) level.setBlock(ground.above(),
                    Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7), 2);
        }
        if (water.size() < 40) throw new IllegalStateException("Insufficient usable channels: " + water.size());
        return new Plot(minX, minZ, slope, List.copyOf(water));
    }

    private static void verify(ServerLevel level, Plot plot) {
        for (BlockPos pos : plot.water) {
            if (!level.getBlockState(pos).is(Blocks.WATER) || !level.getFluidState(pos).isSource())
                throw new IllegalStateException("Lost channel source: " + pos);
            if (!level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), net.minecraft.core.Direction.UP))
                throw new IllegalStateException("Unsupported channel: " + pos);
        }
        for (int dz = -1; dz <= 48; dz++) for (int dx = -1; dx <= 48; dx++) {
            int surfaceY = height(dx, dz, plot.slope);
            for (int y = 216; y <= 232; y++) {
                BlockPos pos = new BlockPos(plot.x + dx, y, plot.z + dz);
                if (level.getBlockState(pos).is(Blocks.WATER) && (y != surfaceY || !plot.water.contains(pos)))
                    throw new IllegalStateException("Channel leaked: " + pos);
            }
        }
    }
}
