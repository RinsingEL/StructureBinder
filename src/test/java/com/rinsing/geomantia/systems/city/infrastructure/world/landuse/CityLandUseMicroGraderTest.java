package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.domain.landuse.CardinalDirection;
import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityLandUseMicroGraderTest {
    @Test
    void frozenPlatformDoesNotFollowAnOwnerWhoseEntireTerrainIsPitBottom() {
        var mask = gradingMask().stream().map(cell -> new CityLandUseChunkCompiler.GradingMaskCell(
                cell.areaId(),cell.x(),cell.z(),true,68)).toList();
        var decisions = CityLandUseMicroGrader.planFoundation(fragment(mask),new FakeTerrain(44));
        assertEquals(List.of(new CityLandUseMicroGrader.FoundationDecision("area",8,8,44,68,
                CityLandUseMicroGrader.FoundationMode.FILL)),decisions);
        var flat = CityLandUseMicroGrader.planFoundation(fragment(mask),new FakeTerrain(68));
        assertEquals(68,flat.get(0).targetY());
    }

    @Test
    void raisesSmallEnclosedDepressionToLocalMedian() {
        FakeTerrain terrain = new FakeTerrain(64);
        terrain.height(8, 8, 62);

        List<CityLandUseMicroGrader.FillDecision> decisions =
                CityLandUseMicroGrader.plan(fragment(), terrain);

        assertEquals(List.of(new CityLandUseMicroGrader.FillDecision("area", 8, 8, 62, 64)), decisions);
    }

    @Test
    void preservesOpenTrenchDeepPitAndWaterEdge() {
        FakeTerrain trench = new FakeTerrain(64);
        for (int x = 4; x <= 20; x++) trench.height(x, 8, 62);
        assertTrue(CityLandUseMicroGrader.plan(fragment(), trench).isEmpty());

        FakeTerrain deep = new FakeTerrain(64);
        deep.height(8, 8, 60);
        assertTrue(CityLandUseMicroGrader.plan(fragment(), deep).isEmpty());

        FakeTerrain shore = new FakeTerrain(64);
        shore.height(8, 8, 62);
        shore.water(10, 8, 63);
        assertTrue(CityLandUseMicroGrader.plan(fragment(), shore).isEmpty());
    }

    @Test
    void preservesDepressionThatEscapesAreaMask() {
        FakeTerrain terrain = new FakeTerrain(64);
        terrain.height(8, 8, 62);
        terrain.height(9, 8, 62);
        List<CityLandUseChunkCompiler.GradingMaskCell> mask = gradingMask();
        mask.removeIf(cell -> cell.x() == 9 && cell.z() == 8);

        assertTrue(CityLandUseMicroGrader.plan(fragment(mask), terrain).isEmpty());
    }

    @Test
    void foundationCutsBoundedPeakInsteadOfSkippingTheSurfaceOnHighRelief() {
        FakeTerrain terrain = new FakeTerrain(64);
        terrain.height(8, 8, 76);

        List<CityLandUseMicroGrader.FoundationDecision> decisions =
                CityLandUseMicroGrader.planFoundation(foundationFragment(), terrain);

        assertEquals(List.of(new CityLandUseMicroGrader.FoundationDecision(
                "area", 8, 8, 76, 64, CityLandUseMicroGrader.FoundationMode.CUT)), decisions);
    }

    @Test
    void foundationSnapsAdjacentNoiseToOneDominantPlatformHeight() {
        FakeTerrain terrain = new FakeTerrain(64);
        terrain.height(8, 8, 66);
        terrain.height(9, 8, 62);
        CityLandUseChunkCompiler.ChunkFragment fragment = foundationFragment(List.of(
                new CityLandUseChunkCompiler.SurfaceOperation("area", "plaza", 8, 8,
                        "minecraft:stone_bricks"),
                new CityLandUseChunkCompiler.SurfaceOperation("area", "plaza", 9, 8,
                        "minecraft:stone_bricks")));

        CityLandUseMicroGrader.FoundationPlan plan =
                CityLandUseMicroGrader.planFoundationPlatform(fragment, terrain);

        assertEquals(List.of(64, 64), plan.decisions().stream()
                .map(CityLandUseMicroGrader.FoundationDecision::targetY).toList());
    }

    @Test
    void subthresholdHighPatchMergesIntoItsSurroundingMainPlatform() {
        FakeTerrain terrain = new FakeTerrain(64);
        for (int z = 6; z <= 12; z++) {
            for (int x = 6; x <= 12; x++) terrain.height(x, z, 68);
        }

        CityLandUseMicroGrader.FoundationPlan plan = CityLandUseMicroGrader.planFoundationPlatform(
                foundationFragment(List.of(new CityLandUseChunkCompiler.SurfaceOperation(
                        "area", "plaza", 9, 9, "minecraft:stone_bricks"))), terrain);

        assertEquals(new CityLandUseMicroGrader.FoundationDecision(
                "area", 9, 9, 68, 64, CityLandUseMicroGrader.FoundationMode.CUT),
                plan.decisions().get(0));
    }

    @Test
    void buildingSizedHighPatchRemainsAnIndependentPlatformLevel() {
        FakeTerrain terrain = new FakeTerrain(64);
        for (int z = 4; z <= 16; z++) {
            for (int x = 4; x <= 16; x++) terrain.height(x, z, 68);
        }
        terrain.height(10, 10, 76);

        CityLandUseMicroGrader.FoundationPlan plan = CityLandUseMicroGrader.planFoundationPlatform(
                foundationFragment(List.of(new CityLandUseChunkCompiler.SurfaceOperation(
                        "area", "plaza", 10, 10, "minecraft:stone_bricks"))), terrain);

        assertEquals(new CityLandUseMicroGrader.FoundationDecision(
                "area", 10, 10, 76, 68, CityLandUseMicroGrader.FoundationMode.CUT),
                plan.decisions().get(0));
    }

    @Test
    void largeEmptyHighPatchMergesBecauseAreaAloneIsNotAPlatformPurpose() {
        FakeTerrain terrain = new FakeTerrain(64);
        for (int z = 4; z <= 16; z++) {
            for (int x = 4; x <= 16; x++) terrain.height(x, z, 68);
        }
        CityLandUseChunkCompiler.ChunkFragment fragment = foundationFragmentWithPlatformFacts(
                List.of(new CityLandUseChunkCompiler.SurfaceOperation(
                        "area", "plaza", 10, 10, "minecraft:stone_bricks")),
                List.of(new CityLandUseChunkCompiler.PlatformPurposeAnchor("area", "low-house",
                        CityLandUseChunkCompiler.PlatformPurpose.BUILDING,
                        new BlockBounds(0, 0, 1, 1))), List.of());

        CityLandUseMicroGrader.FoundationPlan plan =
                CityLandUseMicroGrader.planFoundationPlatform(fragment, terrain);

        assertEquals(new CityLandUseMicroGrader.FoundationDecision(
                "area", 10, 10, 68, 64, CityLandUseMicroGrader.FoundationMode.CUT),
                plan.decisions().get(0));
        assertTrue(plan.platformAdjustments().stream().anyMatch(adjustment ->
                adjustment.status() == CityLandUseMicroGrader.PlatformAdjustmentStatus.MERGED
                        && adjustment.reasonCode().equals("CITY_LAND_USE_PLATFORM_PURPOSE_MISSING")));
    }

    @Test
    void isolatedPurposeLessFoundationWithdrawsInsteadOfPavingRawTerrain() {
        FakeTerrain terrain = new FakeTerrain(64);
        CityLandUseChunkCompiler.ChunkFragment fragment = foundationFragmentWithPlatformFacts(
                List.of(new CityLandUseChunkCompiler.SurfaceOperation(
                        "area", "plaza", 8, 8, "minecraft:stone_bricks")),
                List.of(new CityLandUseChunkCompiler.PlatformPurposeAnchor("area", "remote-house",
                        CityLandUseChunkCompiler.PlatformPurpose.BUILDING,
                        new BlockBounds(100, 100, 101, 101))), List.of());

        CityLandUseMicroGrader.FoundationPlan plan =
                CityLandUseMicroGrader.planFoundationPlatform(fragment, terrain);

        assertEquals(CityLandUseMicroGrader.FoundationMode.PRESERVE,
                plan.decisions().get(0).mode());
        assertTrue(plan.platformAdjustments().stream().anyMatch(adjustment ->
                adjustment.status() == CityLandUseMicroGrader.PlatformAdjustmentStatus.WITHDRAWN
                        && adjustment.targetY() == null));
    }

    @Test
    void buildingPurposeAllowsLargeHighPatchToRemainIndependent() {
        FakeTerrain terrain = new FakeTerrain(64);
        for (int z = 4; z <= 16; z++) {
            for (int x = 4; x <= 16; x++) terrain.height(x, z, 68);
        }
        terrain.height(10, 10, 76);
        CityLandUseChunkCompiler.ChunkFragment fragment = foundationFragmentWithPlatformFacts(
                List.of(new CityLandUseChunkCompiler.SurfaceOperation(
                        "area", "plaza", 10, 10, "minecraft:stone_bricks")),
                List.of(new CityLandUseChunkCompiler.PlatformPurposeAnchor("area", "high-house",
                        CityLandUseChunkCompiler.PlatformPurpose.BUILDING,
                        new BlockBounds(9, 9, 11, 11))), List.of());

        CityLandUseMicroGrader.FoundationPlan plan =
                CityLandUseMicroGrader.planFoundationPlatform(fragment, terrain);

        assertEquals(new CityLandUseMicroGrader.FoundationDecision(
                "area", 10, 10, 76, 68, CityLandUseMicroGrader.FoundationMode.CUT),
                plan.decisions().get(0));
        assertTrue(plan.platformAdjustments().stream().anyMatch(adjustment ->
                adjustment.status() == CityLandUseMicroGrader.PlatformAdjustmentStatus.RETAINED
                        && adjustment.purposeIds().contains("high-house")));
    }

    @Test
    void platformEdgeWithTwoBlockDropProducesStoneRetainingWall() {
        FakeTerrain terrain = new FakeTerrain(64);
        terrain.height(25, 8, 62);
        CityLandUseChunkCompiler.ChunkFragment fragment = foundationFragment(List.of(
                new CityLandUseChunkCompiler.SurfaceOperation("area", "plaza", 24, 8,
                        "minecraft:stone_bricks")));

        CityLandUseMicroGrader.FoundationPlan plan =
                CityLandUseMicroGrader.planFoundationPlatform(fragment, terrain);

        assertTrue(plan.retainingWalls().stream().anyMatch(wall -> wall.x() == 24 && wall.z() == 8
                && wall.y() == 63 && "minecraft:stone_bricks".equals(wall.blockId())));
    }

    @Test
    void foundationClosesSmallWaterPocketInsidePavedPlatform() {
        FakeTerrain terrain = new FakeTerrain(64);
        terrain.water(8, 8, 62);

        List<CityLandUseMicroGrader.FoundationDecision> decisions =
                CityLandUseMicroGrader.planFoundation(foundationFragment(), terrain);

        assertEquals(new CityLandUseMicroGrader.FoundationDecision(
                "area", 8, 8, 62, 64, CityLandUseMicroGrader.FoundationMode.FILL), decisions.get(0));
    }

    @Test
    void foundationPreservesWaterConnectedOutsidePavedPlatform() {
        FakeTerrain terrain = new FakeTerrain(64);
        terrain.water(8, 8, 62);
        List<CityLandUseChunkCompiler.GradingMaskCell> openMask = gradingMask().stream()
                .filter(cell -> cell.x() != 9 || cell.z() != 8)
                .map(cell -> new CityLandUseChunkCompiler.GradingMaskCell(
                        cell.areaId(), cell.x(), cell.z(), true))
                .toList();
        CityLandUseChunkCompiler.ChunkFragment fragment = fragment(openMask, List.of(
                new CityLandUseChunkCompiler.SurfaceOperation(
                        "area", "plaza", 8, 8, "minecraft:stone_bricks")));

        List<CityLandUseMicroGrader.FoundationDecision> decisions =
                CityLandUseMicroGrader.planFoundation(fragment, terrain);

        assertEquals(CityLandUseMicroGrader.FoundationMode.PRESERVE, decisions.get(0).mode());
    }

    @Test
    void structureDatumUsesSurroundingFoundationPlatformSurfacePlusOne() {
        FakeTerrain terrain = new FakeTerrain(64);
        List<CityLandUseChunkCompiler.GradingMaskCell> ring = gradingMask().stream()
                .filter(cell -> cell.x() < 8 || cell.x() > 9 || cell.z() < 8 || cell.z() > 9)
                .map(cell -> new CityLandUseChunkCompiler.GradingMaskCell(
                        cell.areaId(), cell.x(), cell.z(), true))
                .toList();
        CityLandUseChunkCompiler.ChunkFragment fragment = fragment(ring, List.of());

        assertEquals(65, CityLandUseMicroGrader.resolveStructureDatum(
                fragment, terrain, new BlockBounds(8, 8, 9, 9)).orElseThrow());
    }

    @Test
    void structureDatumUsesMergedPlatformLevelInsteadOfRawLocalSlopeHeight() {
        FakeTerrain terrain = new FakeTerrain(66);
        List<CityLandUseChunkCompiler.GradingMaskCell> ring = gradingMask().stream()
                .filter(cell -> cell.x() < 8 || cell.x() > 9 || cell.z() < 8 || cell.z() > 9)
                .map(cell -> new CityLandUseChunkCompiler.GradingMaskCell(
                        cell.areaId(), cell.x(), cell.z(), true))
                .toList();

        assertEquals(69, CityLandUseMicroGrader.resolveStructureDatum(
                fragment(ring, List.of()), terrain, new BlockBounds(8, 8, 9, 9)).orElseThrow());
    }

    @Test
    void roadUsesStraightStairRunWhenLowSideHasEnoughDepth() {
        FakeTerrain terrain = splitTerrain();
        List<CityLandUseChunkCompiler.SurfaceOperation> surfaces = platformSurfaces();
        List<CityLandUseChunkCompiler.FeatureOperation> roads = horizontalRoad(0, 15, 8);

        CityLandUseMicroGrader.FoundationPlan plan = CityLandUseMicroGrader.planFoundationPlatform(
                foundationFragment(surfaces, roads), terrain);

        List<CityLandUseMicroGrader.StairDecision> direct = plan.stairs().stream()
                .filter(stair -> stair.mode() == CityLandUseMicroGrader.StairMode.DIRECT
                        && stair.z() == 8 && stair.x() >= 4 && stair.x() <= 7)
                .toList();
        assertEquals(List.of(64, 65, 66, 67), direct.stream()
                .map(CityLandUseMicroGrader.StairDecision::targetY).toList());
        assertTrue(direct.stream().allMatch(stair ->
                stair.facing() == CityLandUseSurfacePrintPlan.HorizontalFacing.EAST));
    }

    @Test
    void nonStairPlatformEdgeGetsStableRailingsAndGreeneryWithClearStairMouth() {
        FakeTerrain terrain = splitTerrain();
        List<CityLandUseChunkCompiler.FeatureOperation> features = new ArrayList<>(
                horizontalRoad(0, 15, 8));
        features.add(new CityLandUseChunkCompiler.FeatureOperation("existing-greenery", 8, 0,
                "minecraft:poppy", 1, CityLandUseSurfacePrintPlan.FeatureKind.GREEN_PLANT,
                CityLandUseSurfacePrintPlan.HorizontalFacing.NONE));
        CityLandUseChunkCompiler.ChunkFragment fragment = foundationFragment(
                platformSurfaces(), features);

        CityLandUseMicroGrader.FoundationPlan first =
                CityLandUseMicroGrader.planFoundationPlatform(fragment, terrain);
        CityLandUseMicroGrader.FoundationPlan second =
                CityLandUseMicroGrader.planFoundationPlatform(fragment, terrain);

        assertEquals(first.terraceEdges(), second.terraceEdges());
        assertTrue(first.terraceEdges().stream().anyMatch(edge ->
                edge.kind() == CityLandUseMicroGrader.TerraceEdgeKind.RAILING
                        && edge.x() == 8 && edge.y() == 69));
        assertTrue(first.terraceEdges().stream().anyMatch(edge ->
                edge.kind() == CityLandUseMicroGrader.TerraceEdgeKind.GREENERY
                        && edge.x() == 8 && edge.y() == 69));
        assertTrue(first.terraceEdges().stream().noneMatch(edge ->
                edge.x() == 8 && edge.z() >= 7 && edge.z() <= 9));
        assertTrue(first.terraceEdges().stream().noneMatch(edge -> edge.x() == 8 && edge.z() == 0));
    }

    @Test
    void shortFrontRunCreatesTwoLowSideFlightsMeetingCentralHighPlatform() {
        FakeTerrain terrain = splitTerrain();
        List<CityLandUseChunkCompiler.SurfaceOperation> surfaces = platformSurfaces();
        List<CityLandUseChunkCompiler.FeatureOperation> roads = horizontalRoad(7, 12, 8);

        CityLandUseMicroGrader.FoundationPlan plan = CityLandUseMicroGrader.planFoundationPlatform(
                foundationFragment(surfaces, roads), terrain);

        List<CityLandUseMicroGrader.StairDecision> split = plan.stairs().stream()
                .filter(stair -> stair.mode() == CityLandUseMicroGrader.StairMode.SPLIT)
                .toList();
        assertEquals(7, split.size());
        assertTrue(split.stream().anyMatch(stair -> stair.x() == 7 && stair.z() == 8
                && stair.targetY() == 67
                && stair.facing() == CityLandUseSurfacePrintPlan.HorizontalFacing.EAST));
        assertTrue(split.stream().anyMatch(stair -> stair.x() == 7 && stair.z() == 5
                && stair.targetY() == 64
                && stair.facing() == CityLandUseSurfacePrintPlan.HorizontalFacing.SOUTH));
        assertTrue(split.stream().anyMatch(stair -> stair.x() == 7 && stair.z() == 11
                && stair.targetY() == 64
                && stair.facing() == CityLandUseSurfacePrintPlan.HorizontalFacing.NORTH));
    }

    @Test
    void shortFrontRunKeepsAvailableSideWhenOtherFlightIsOutsideFoundation() {
        var base = foundationFragment(platformSurfaces(), horizontalRoad(7, 12, 8));
        var mask = base.gradingMaskCells().stream()
                .filter(cell -> !(cell.x() == 7 && cell.z() >= 5 && cell.z() < 8)).toList();
        var fragment = new CityLandUseChunkCompiler.ChunkFragment(base.schema(), base.cityId(),
                base.planHash(), base.paletteHash(), base.chunkX(), base.chunkZ(), base.relevantCellCount(),
                base.footprintExcludedCount(), base.corridorExcludedCount(), base.gateExcludedCount(),
                base.microFillBlockId(), mask, base.surfaceOperations(), base.boundaryOperations(),
                base.featureOperations(), base.gradingFeatureOperations(), base.platformPurposeAnchors(),
                base.platformAccessDemands());
        var plan = CityLandUseMicroGrader.planFoundationPlatform(fragment, splitTerrain());
        var stairs = plan.stairs().stream().filter(stair ->
                stair.mode() == CityLandUseMicroGrader.StairMode.SPLIT).toList();
        assertEquals(4, stairs.size());
        assertTrue(stairs.stream().allMatch(stair -> stair.z() >= 8));
        assertTrue(stairs.stream().anyMatch(stair -> stair.z() == 11 && stair.targetY() == 64));
    }

    @Test
    void activeStairScanFindsTransitionOnSquareLShapedRoadSource() {
        FakeTerrain terrain = splitTerrain();

        CityLandUseMicroGrader.FoundationPlan plan = CityLandUseMicroGrader.planFoundationPlatform(
                foundationFragment(platformSurfaces(), squareCrossRoad()), terrain);

        assertTrue(plan.stairs().stream().anyMatch(stair ->
                stair.mode() == CityLandUseMicroGrader.StairMode.DIRECT
                        && stair.facing() == CityLandUseSurfacePrintPlan.HorizontalFacing.EAST));
    }

    @Test
    void occupiedPlatformWithoutRoadGetsEntrancePathAndActiveStair() {
        FakeTerrain terrain = splitTerrain();
        List<CityLandUseChunkCompiler.PlatformPurposeAnchor> purposes = List.of(
                new CityLandUseChunkCompiler.PlatformPurposeAnchor("area", "low-house",
                        CityLandUseChunkCompiler.PlatformPurpose.BUILDING,
                        new BlockBounds(2, 7, 3, 9)),
                new CityLandUseChunkCompiler.PlatformPurposeAnchor("area", "high-house",
                        CityLandUseChunkCompiler.PlatformPurpose.BUILDING,
                        new BlockBounds(12, 7, 13, 9)));
        List<CityLandUseChunkCompiler.PlatformAccessDemand> demands = List.of(
                new CityLandUseChunkCompiler.PlatformAccessDemand("area", "high-house::front",
                        new BlockPoint(12, 8), CardinalDirection.WEST),
                new CityLandUseChunkCompiler.PlatformAccessDemand("area", "high-house::side",
                        new BlockPoint(12, 9), CardinalDirection.WEST));
        CityLandUseChunkCompiler.ChunkFragment fragment = foundationFragmentWithPlatformFacts(
                platformSurfaces(), purposes, demands);

        CityLandUseMicroGrader.FoundationPlan plan =
                CityLandUseMicroGrader.planFoundationPlatform(fragment, terrain);

        assertTrue(plan.stairs().stream().anyMatch(stair ->
                stair.mode() == CityLandUseMicroGrader.StairMode.ACCESS_DIRECT));
        assertTrue(plan.accessPaths().stream().anyMatch(path ->
                path.demandId().equals("high-house::front")));
        assertEquals(1, plan.accessOutcomes().stream().filter(outcome ->
                outcome.status() == CityLandUseMicroGrader.AccessStatus.ACTIVE_STAIR).count());
        assertEquals(1, plan.accessOutcomes().stream().filter(outcome ->
                outcome.status() == CityLandUseMicroGrader.AccessStatus.SHARED_PLATFORM_ACCESS).count());
    }

    private static CityLandUseChunkCompiler.ChunkFragment fragment() {
        return fragment(gradingMask());
    }

    private static CityLandUseChunkCompiler.ChunkFragment foundationFragment() {
        return foundationFragment(List.of(new CityLandUseChunkCompiler.SurfaceOperation(
                "area", "plaza", 8, 8, "minecraft:stone_bricks")));
    }

    private static CityLandUseChunkCompiler.ChunkFragment foundationFragment(
            List<CityLandUseChunkCompiler.SurfaceOperation> operations) {
        List<CityLandUseChunkCompiler.GradingMaskCell> mask = gradingMask().stream()
                .map(cell -> new CityLandUseChunkCompiler.GradingMaskCell(
                        cell.areaId(), cell.x(), cell.z(), true))
                .toList();
        return fragment(mask, operations);
    }

    private static CityLandUseChunkCompiler.ChunkFragment foundationFragmentWithPlatformFacts(
            List<CityLandUseChunkCompiler.SurfaceOperation> operations,
            List<CityLandUseChunkCompiler.PlatformPurposeAnchor> purposes,
            List<CityLandUseChunkCompiler.PlatformAccessDemand> demands) {
        List<CityLandUseChunkCompiler.GradingMaskCell> mask = gradingMask().stream()
                .map(cell -> new CityLandUseChunkCompiler.GradingMaskCell(
                        cell.areaId(), cell.x(), cell.z(), true))
                .toList();
        return new CityLandUseChunkCompiler.ChunkFragment(CityLandUseChunkCompiler.RESULT_SCHEMA,
                "city", "hash", "palette", 0, 0, operations.size(), 0, 0, 0,
                "minecraft:dirt", mask, operations, List.of(), List.of(), List.of(),
                purposes, demands);
    }

    private static CityLandUseChunkCompiler.ChunkFragment foundationFragment(
            List<CityLandUseChunkCompiler.SurfaceOperation> operations,
            List<CityLandUseChunkCompiler.FeatureOperation> features) {
        List<CityLandUseChunkCompiler.GradingMaskCell> mask = gradingMask().stream()
                .map(cell -> new CityLandUseChunkCompiler.GradingMaskCell(
                        cell.areaId(), cell.x(), cell.z(), true))
                .toList();
        return new CityLandUseChunkCompiler.ChunkFragment(CityLandUseChunkCompiler.RESULT_SCHEMA,
                "city", "hash", "palette", 0, 0, operations.size(), 0, 0, 0,
                "minecraft:dirt", mask, operations, List.of(), features);
    }

    private static FakeTerrain splitTerrain() {
        FakeTerrain terrain = new FakeTerrain(64);
        for (int z = -8; z <= 24; z++) {
            for (int x = 8; x <= 24; x++) terrain.height(x, z, 68);
        }
        return terrain;
    }

    private static List<CityLandUseChunkCompiler.SurfaceOperation> platformSurfaces() {
        List<CityLandUseChunkCompiler.SurfaceOperation> operations = new ArrayList<>();
        for (int z = 0; z <= 15; z++) {
            for (int x = 0; x <= 15; x++) {
                operations.add(new CityLandUseChunkCompiler.SurfaceOperation(
                        "area", "plaza", x, z, "minecraft:stone_bricks"));
            }
        }
        return operations;
    }

    private static List<CityLandUseChunkCompiler.FeatureOperation> horizontalRoad(
            int minX, int maxX, int z) {
        List<CityLandUseChunkCompiler.FeatureOperation> operations = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            operations.add(new CityLandUseChunkCompiler.FeatureOperation("road", x, z,
                    "minecraft:stone_brick_slab", 0,
                    CityLandUseSurfacePrintPlan.FeatureKind.ROAD_SLAB,
                    CityLandUseSurfacePrintPlan.HorizontalFacing.NONE));
        }
        operations.add(new CityLandUseChunkCompiler.FeatureOperation("road", minX, z - 1,
                "minecraft:stone_brick_stairs", 0,
                CityLandUseSurfacePrintPlan.FeatureKind.ROAD_STAIR,
                CityLandUseSurfacePrintPlan.HorizontalFacing.NORTH));
        return operations;
    }

    private static List<CityLandUseChunkCompiler.FeatureOperation> squareCrossRoad() {
        List<CityLandUseChunkCompiler.FeatureOperation> operations = new ArrayList<>();
        for (int coordinate = 0; coordinate <= 15; coordinate++) {
            operations.add(new CityLandUseChunkCompiler.FeatureOperation("road", coordinate, 8,
                    "minecraft:stone_brick_slab", 0,
                    CityLandUseSurfacePrintPlan.FeatureKind.ROAD_SLAB,
                    CityLandUseSurfacePrintPlan.HorizontalFacing.NONE));
            operations.add(new CityLandUseChunkCompiler.FeatureOperation("road", 8, coordinate,
                    "minecraft:stone_brick_slab", 0,
                    CityLandUseSurfacePrintPlan.FeatureKind.ROAD_SLAB,
                    CityLandUseSurfacePrintPlan.HorizontalFacing.NONE));
        }
        operations.add(new CityLandUseChunkCompiler.FeatureOperation("road", 0, 7,
                "minecraft:stone_brick_stairs", 0,
                CityLandUseSurfacePrintPlan.FeatureKind.ROAD_STAIR,
                CityLandUseSurfacePrintPlan.HorizontalFacing.NORTH));
        return operations;
    }

    private static CityLandUseChunkCompiler.ChunkFragment fragment(
            List<CityLandUseChunkCompiler.GradingMaskCell> mask) {
        return fragment(mask, List.of(new CityLandUseChunkCompiler.SurfaceOperation(
                "area", "plaza", 8, 8, "minecraft:stone_bricks")));
    }

    private static CityLandUseChunkCompiler.ChunkFragment fragment(
            List<CityLandUseChunkCompiler.GradingMaskCell> mask,
            List<CityLandUseChunkCompiler.SurfaceOperation> operations) {
        return new CityLandUseChunkCompiler.ChunkFragment(CityLandUseChunkCompiler.RESULT_SCHEMA,
                "city", "hash", "palette", 0, 0, 1, 0, 0, 0,
                "minecraft:dirt", mask, operations, List.of());
    }

    private static List<CityLandUseChunkCompiler.GradingMaskCell> gradingMask() {
        List<CityLandUseChunkCompiler.GradingMaskCell> mask = new ArrayList<>();
        for (int z = -8; z <= 24; z++) {
            for (int x = -8; x <= 24; x++) {
                mask.add(new CityLandUseChunkCompiler.GradingMaskCell("area", x, z));
            }
        }
        return mask;
    }

    private static final class FakeTerrain implements CityLandUseMicroGrader.TerrainView {
        private final int defaultHeight;
        private final Map<String, CityLandUseChunkExecutor.ColumnSample> samples = new HashMap<>();

        private FakeTerrain(int defaultHeight) {
            this.defaultHeight = defaultHeight;
        }

        private void height(int x, int z, int y) {
            samples.put(x + "," + z,
                    new CityLandUseChunkExecutor.ColumnSample(y, "minecraft:dirt", true));
        }

        private void water(int x, int z, int y) {
            samples.put(x + "," + z,
                    new CityLandUseChunkExecutor.ColumnSample(y, "minecraft:water", false));
        }

        @Override
        public CityLandUseChunkExecutor.ColumnSample sample(int worldX, int worldZ) {
            return samples.getOrDefault(worldX + "," + worldZ,
                    new CityLandUseChunkExecutor.ColumnSample(defaultHeight, "minecraft:grass_block", true));
        }
    }
}
