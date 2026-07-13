package com.rinsing.geomantia.systems.city.application.dressing;

import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.domain.model.CityLandformReviewPackage;
import com.rinsing.geomantia.systems.city.domain.model.LandformPatchSummary;
import com.rinsing.geomantia.systems.city.domain.model.PatchMemberCell;

import java.util.ArrayList;
import java.util.List;

/** Resolves the v0.2 patch-only intent slice against D3 member-cell truth. */
public final class D3PatchDecorationProgramContextResolver implements ResolvedDecorationProgramContext.Resolver {
    private final CityLandformReviewPackage reviewPackage;
    private final List<CompiledDecorationProgramPlan.HardObstacle> hardObstacles;

    public D3PatchDecorationProgramContextResolver(CityLandformReviewPackage reviewPackage) {
        this(reviewPackage, List.of());
    }

    public D3PatchDecorationProgramContextResolver(
            CityLandformReviewPackage reviewPackage,
            List<CompiledDecorationProgramPlan.HardObstacle> hardObstacles) {
        this.reviewPackage = reviewPackage;
        this.hardObstacles = List.copyOf(hardObstacles);
    }

    @Override
    public ResolvedDecorationProgramContext resolve(DecorationProgramIntent intent) {
        DecorationProgramIntent.TargetArea target = intent.targetArea();
        if (!"patch".equals(target.sourceType())) {
            throw new IllegalArgumentException("CITY_DECORATION_TARGET_SOURCE_UNSUPPORTED: " + target.sourceType());
        }
        LandformPatchSummary patch = reviewPackage.landformPatches().stream()
                .filter(candidate -> candidate.landformPatchId().equals(target.ref()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "CITY_DECORATION_TARGET_PATCH_UNKNOWN: " + target.ref()));
        if (patch.memberCells().isEmpty()) {
            throw new IllegalArgumentException("CITY_DECORATION_PATCH_MEMBER_CELLS_REQUIRED: " + target.ref());
        }
        List<BlockBounds> members = patchMemberBounds(patch.memberCells(), reviewPackage.grid().cellStepBlocks());
        if (target.insetBlocks() > 0 || !hardObstacles.isEmpty()) {
            members = erodeSubtractAndMerge(members, patch.blockBounds(), target.insetBlocks());
        }
        if (members.isEmpty()) {
            throw new IllegalArgumentException("CITY_DECORATION_TARGET_AREA_EMPTY: " + target.ref());
        }
        CompiledDecorationProgram.TargetMask mask =
                new CompiledDecorationProgram.TargetMask("patch:" + target.ref(), members);
        CompiledDecorationProgram.CoordinateFrame frame = resolveFrame(intent.coordinateFrame(), mask);
        return new ResolvedDecorationProgramContext(mask, frame);
    }

    private List<BlockBounds> patchMemberBounds(List<PatchMemberCell> cells, int step) {
        List<BlockBounds> result = new ArrayList<>(cells.size());
        for (PatchMemberCell cell : cells) {
            result.add(new BlockBounds(cell.blockMinX(), cell.blockMinZ(),
                    cell.blockMinX() + step - 1, cell.blockMinZ() + step - 1));
        }
        return List.copyOf(result);
    }

    private List<BlockBounds> erodeSubtractAndMerge(List<BlockBounds> source, BlockBounds scanBounds, int inset) {
        List<BlockBounds> runs = new ArrayList<>();
        for (int z = scanBounds.minZ(); z <= scanBounds.maxZ(); z++) {
            int runStart = Integer.MIN_VALUE;
            for (int x = scanBounds.minX(); x <= scanBounds.maxX(); x++) {
                boolean valid = hasInsetSupport(source, x, z, inset) && !isHardBlocked(x, z);
                if (valid && runStart == Integer.MIN_VALUE) {
                    runStart = x;
                }
                if (!valid && runStart != Integer.MIN_VALUE) {
                    runs.add(new BlockBounds(runStart, z, x - 1, z));
                    runStart = Integer.MIN_VALUE;
                }
            }
            if (runStart != Integer.MIN_VALUE) {
                runs.add(new BlockBounds(runStart, z, scanBounds.maxX(), z));
            }
        }
        return List.copyOf(runs);
    }

    private boolean hasInsetSupport(List<BlockBounds> source, int x, int z, int inset) {
        for (int dz = -inset; dz <= inset; dz++) {
            for (int dx = -inset; dx <= inset; dx++) {
                if (!contains(source, x + dx, z + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean contains(List<BlockBounds> bounds, int x, int z) {
        for (BlockBounds member : bounds) {
            if (member.contains(x, z)) {
                return true;
            }
        }
        return false;
    }

    private boolean isHardBlocked(int x, int z) {
        for (CompiledDecorationProgramPlan.HardObstacle obstacle : hardObstacles) {
            if (obstacle.blockBounds().contains(x, z)) {
                return true;
            }
        }
        return false;
    }

    private CompiledDecorationProgram.CoordinateFrame resolveFrame(
            DecorationProgramIntent.CoordinateFrameIntent intent,
            CompiledDecorationProgram.TargetMask mask) {
        if (!"target_centroid".equals(intent.originMode())) {
            throw new IllegalArgumentException("CITY_DECORATION_ORIGIN_MODE_UNSUPPORTED: " + intent.originMode());
        }
        CompiledDecorationProgram.Vector2 axisU;
        CompiledDecorationProgram.Vector2 axisV;
        switch (intent.orientationMode()) {
            case "world_x" -> {
                axisU = new CompiledDecorationProgram.Vector2(1, 0);
                axisV = new CompiledDecorationProgram.Vector2(0, 1);
            }
            case "world_z" -> {
                axisU = new CompiledDecorationProgram.Vector2(0, 1);
                axisV = new CompiledDecorationProgram.Vector2(-1, 0);
            }
            case "patch_long_axis" -> {
                if (mask.bounds().widthBlocks() >= mask.bounds().heightBlocks()) {
                    axisU = new CompiledDecorationProgram.Vector2(1, 0);
                    axisV = new CompiledDecorationProgram.Vector2(0, 1);
                } else {
                    axisU = new CompiledDecorationProgram.Vector2(0, 1);
                    axisV = new CompiledDecorationProgram.Vector2(-1, 0);
                }
            }
            default -> throw new IllegalArgumentException(
                    "CITY_DECORATION_ORIENTATION_MODE_UNSUPPORTED: " + intent.orientationMode());
        }
        for (int turn = 0; turn < intent.quarterTurns(); turn++) {
            CompiledDecorationProgram.Vector2 oldU = axisU;
            axisU = axisV;
            axisV = new CompiledDecorationProgram.Vector2(-oldU.x(), -oldU.z());
        }
        BlockPoint centroid = centroid(mask.memberBounds());
        BlockPoint origin = new BlockPoint(
                centroid.x() + axisU.x() * intent.offsetUBlocks() + axisV.x() * intent.offsetVBlocks(),
                centroid.z() + axisU.z() * intent.offsetUBlocks() + axisV.z() * intent.offsetVBlocks());
        return new CompiledDecorationProgram.CoordinateFrame(origin, axisU, axisV);
    }

    private BlockPoint centroid(List<BlockBounds> members) {
        long count = 0L;
        long sumXTimesTwo = 0L;
        long sumZTimesTwo = 0L;
        for (BlockBounds member : members) {
            long width = member.widthBlocks();
            long height = member.heightBlocks();
            long cells = width * height;
            count += cells;
            sumXTimesTwo += height * width * ((long) member.minX() + member.maxX());
            sumZTimesTwo += width * height * ((long) member.minZ() + member.maxZ());
        }
        return new BlockPoint((int) Math.round(sumXTimesTwo / (2.0 * count)),
                (int) Math.round(sumZTimesTwo / (2.0 * count)));
    }
}
