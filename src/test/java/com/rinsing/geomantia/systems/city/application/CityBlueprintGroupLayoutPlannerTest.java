package com.rinsing.geomantia.systems.city.application;

import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityBlueprintGroupLayoutPlannerTest {
    private final CityBlueprintGroupLayoutPlanner planner = new CityBlueprintGroupLayoutPlanner();

    @Test
    void boundaryGuidedCompactTraceDoesNotInventAFrontageTarget() {
        BlockPoint boundaryOrigin = new BlockPoint(32, 64);
        var proposal = new CityBlueprintGroupLayoutPlanner.Proposal(0, "COMPACT",
                planner.parameters("COMPACT", CityBlueprint.DensityClass.BALANCED),
                12, false, null, java.util.List.of(boundaryOrigin), null);

        var trace = proposal.traceJson();
        assertFalse(trace.has("frontageTarget"));
        assertFalse(trace.has("compactLaneTarget"));
        assertEquals(boundaryOrigin.asJson(), trace.getAsJsonObject("theoreticalAnchor"));
        assertEquals(boundaryOrigin.asJson(), trace.getAsJsonArray("compactCandidateGuides").get(0));
    }

    @Test
    void compactDemandFollowsCompleteLayersAndContainsAllGuidesAndTemplateExcursions() {
        BlockPoint origin = new BlockPoint(0, 0);
        var frame = planner.worldFrame(origin);
        int templateSpan = 58;
        var density = CityBlueprint.DensityClass.BALANCED;
        assertEquals(planner.compactFormationSpan(1, templateSpan, density),
                planner.compactFormationSpan(8, templateSpan, density));
        assertTrue(planner.compactFormationSpan(9, templateSpan, density)
                > planner.compactFormationSpan(8, templateSpan, density));
        assertTrue(planner.compactFormationSpan(6, templateSpan, density) < 388);
        for (int count : new int[]{1, 6, 8, 9, 17}) {
            int half = planner.compactFormationSpan(count, templateSpan, density) / 2;
            for (int index = 0; index < count; index++) {
                var proposal = planner.propose("COMPACT", density, 1, "farm", index,
                        frame, origin, null, false, templateSpan);
                for (BlockPoint point : proposal.guides()) {
                    assertTrue(Math.abs(point.x()) + templateSpan <= half);
                    assertTrue(Math.abs(point.z()) + templateSpan <= half);
                }
            }
        }
    }

    @Test
    void densityChangesLayoutSpacingButNeverBuildingArea() {
        var dense = planner.parameters("COMPACT", CityBlueprint.DensityClass.DENSE);
        var sparse = planner.parameters("COMPACT", CityBlueprint.DensityClass.SPARSE);
        BlockBounds footprint = new BlockBounds(0, 0, 14, 14);

        assertTrue(dense.targetEdgeGapBlocks() < sparse.targetEdgeGapBlocks());
        assertTrue(dense.maximumEdgeGapBlocks() < sparse.maximumEdgeGapBlocks());
        assertTrue(dense.landUseHandoffGapBlocks() < sparse.landUseHandoffGapBlocks());
        assertEquals(225, planner.claimedArea(footprint, dense));
        assertEquals(225, planner.claimedArea(footprint, sparse));
    }

    @Test
    void algorithmsResolveToStableSpatialPlacementModes() {
        assertEquals(CityBlueprintGroupLayoutPlanner.PlacementMode.CORE_ANCHORED,
                planner.placementMode("CENTER_SYMMETRIC"));
        assertEquals(CityBlueprintGroupLayoutPlanner.PlacementMode.CORE_ANCHORED,
                planner.placementMode("COURTYARD"));
        assertEquals(CityBlueprintGroupLayoutPlanner.PlacementMode.AXIS_ANCHORED,
                planner.placementMode("LINEAR"));
        assertEquals(CityBlueprintGroupLayoutPlanner.PlacementMode.CLUSTER_BOUNDED,
                planner.placementMode("COMPACT"));
        assertEquals(CityBlueprintGroupLayoutPlanner.PlacementMode.TERRAIN_FOLLOWING,
                planner.placementMode("ORGANIC_COMPACT"));
        assertEquals("AXIS_ANCHORED",
                planner.parameters("LINEAR", CityBlueprint.DensityClass.BALANCED)
                        .asJson().get("placementMode").getAsString());
        assertTrue(planner.worldAxisLocked("GRID"));
        assertTrue(planner.worldAxisLocked("COURTYARD"));
        assertTrue(planner.exactInternalGuides("COMPACT"));
        assertFalse(planner.worldAxisLocked("ORGANIC_COMPACT"));
        assertFalse(planner.exactInternalGuides("ORGANIC_COMPACT"));
    }

    @Test
    void compactLayoutKeepsTwoDimensionalMorphologyAcrossIncrementalSlots() {
        BlockPoint center = new BlockPoint(0, 0);
        var frame = planner.frame(center, new BlockPoint(100, 0), 17L, "civic");
        Set<Integer> xs = new LinkedHashSet<>();
        Set<Integer> zs = new LinkedHashSet<>();
        for (int slot = 1; slot <= 10; slot++) {
            var proposal = planner.propose("COMPACT", CityBlueprint.DensityClass.BALANCED,
                    17L, "civic", slot, frame, center, null, false, 18);
            xs.add(proposal.guides().get(0).x());
            zs.add(proposal.guides().get(0).z());
            assertEquals(slot, proposal.slotIndex());
        }

        assertTrue(xs.size() >= 3 && xs.stream().anyMatch(x -> x < 0) && xs.stream().anyMatch(x -> x > 0),
                "compact layout must have depth on both sides of x");
        assertTrue(zs.size() >= 3 && zs.stream().anyMatch(z -> z < 0) && zs.stream().anyMatch(z -> z > 0),
                "compact layout must have depth on both sides of z");
    }

    @Test
    void outwardGuidanceBiasesTheSameMorphologyTowardItsConnectionTarget() {
        BlockPoint center = new BlockPoint(0, 0);
        BlockPoint target = new BlockPoint(200, 0);
        var frame = planner.frame(center, target, 23L, "market");
        var free = planner.propose("ORGANIC_COMPACT", CityBlueprint.DensityClass.BALANCED,
                23L, "market", 6, frame, center, target, false, 20);
        var outward = planner.propose("ORGANIC_COMPACT", CityBlueprint.DensityClass.BALANCED,
                23L, "market", 6, frame, center, target, true, 20);

        assertTrue(outward.guides().get(0).x() > free.guides().get(0).x(),
                "outward mode should bias the organic slot toward the target without changing algorithms");
        assertEquals("ORGANIC_COMPACT", outward.algorithm());
        assertTrue(outward.outwardGuided());
    }

    @Test
    void gridLinearAndCourtyardRemainDistinctSpatialGrammars() {
        BlockPoint center = new BlockPoint(0, 0);
        var frame = planner.frame(center, new BlockPoint(100, 0), 31L, "district");
        BlockPoint grid = planner.propose("GRID", CityBlueprint.DensityClass.BALANCED,
                31L, "district", 4, frame, center, null, false, 16).guides().get(0);
        BlockPoint linear = planner.propose("LINEAR", CityBlueprint.DensityClass.BALANCED,
                31L, "district", 4, frame, center, null, false, 16).guides().get(0);
        BlockPoint courtyard = planner.propose("COURTYARD", CityBlueprint.DensityClass.BALANCED,
                31L, "district", 4, frame, center, null, false, 16).guides().get(0);

        assertTrue(!grid.equals(linear) && !grid.equals(courtyard) && !linear.equals(courtyard));
    }

    @Test
    void gridUsesOneWorldAxisPitchWithOneBlockInLotAdjustments() {
        BlockPoint center = new BlockPoint(100, 200);
        var frame = planner.worldFrame(center);
        var first = planner.propose("GRID", CityBlueprint.DensityClass.BALANCED,
                41L, "grid", 1, frame, center, null, false, 20);
        var second = planner.propose("GRID", CityBlueprint.DensityClass.BALANCED,
                41L, "grid", 2, frame, center, null, false, 20);

        assertEquals(5, first.guides().size());
        assertTrue(first.guides().stream().allMatch(point ->
                Math.abs(point.x() - first.guides().get(0).x())
                        + Math.abs(point.z() - first.guides().get(0).z()) <= 1));
        assertEquals(first.spacingBlocks(), second.spacingBlocks());
        assertEquals(new BlockPoint(130, 200), first.guides().get(0));
        assertEquals(new BlockPoint(130, 230), second.guides().get(0));
        assertEquals(1, first.traceJson().get("gridRow").getAsInt());
        assertEquals(0, first.traceJson().get("gridColumn").getAsInt());
        assertEquals(1, second.traceJson().get("gridRow").getAsInt());
        assertEquals(1, second.traceJson().get("gridColumn").getAsInt());
    }

    @Test
    void linearFallbackGuidesOnlyStaggerAlongTheStreetAxis() {
        BlockPoint center = new BlockPoint(100, 200);
        var proposal = planner.propose("LINEAR", CityBlueprint.DensityClass.DENSE,
                43L, "market", 1, planner.worldFrame(center), center, null, false, 20);

        assertEquals(5, proposal.guides().size());
        int streetSideZ = proposal.guides().get(0).z();
        assertTrue(proposal.guides().stream().allMatch(point -> point.z() == streetSideZ));
        assertEquals(5, proposal.guides().stream().map(BlockPoint::x).distinct().count());
    }

    @Test
    void courtyardStartsOnFivePerimeterSlotsAndLeavesTheCenterForTheCourt() {
        BlockPoint center = new BlockPoint(0, 0);
        var frame = planner.worldFrame(center);
        Set<BlockPoint> firstFive = new LinkedHashSet<>();
        for (int slot = 0; slot < 5; slot++) {
            var proposal = planner.propose("COURTYARD", CityBlueprint.DensityClass.DENSE,
                    43L, "court", slot, frame, center, null, false, 18);
            assertEquals(1, proposal.guides().size());
            assertFalse(proposal.guides().get(0).equals(center));
            assertEquals(center, proposal.frontageTarget());
            firstFive.add(proposal.guides().get(0));
        }

        assertEquals(Set.of(new BlockPoint(0, -26), new BlockPoint(26, 0),
                new BlockPoint(26, 26), new BlockPoint(-26, 26), new BlockPoint(-26, 0)), firstFive);
    }

    @Test
    void compactTriesEveryDirectionBeforeExpandingBeyondTheFirstRing() {
        BlockPoint center = new BlockPoint(0, 0);
        var frame = planner.worldFrame(center);
        Set<String> directions = new LinkedHashSet<>();
        Set<BlockPoint> guides = new LinkedHashSet<>();
        int spacing = -1;
        for (int slot = 0; slot < 8; slot++) {
            var proposal = planner.propose("COMPACT", CityBlueprint.DensityClass.DENSE,
                    47L, "compact", slot, frame, center, null, false, 18);
            assertTrue(proposal.guides().size() >= 3 && proposal.guides().size() <= 5);
            assertTrue(proposal.frontageTarget() != null);
            assertEquals(proposal.frontageTarget().asJson(),
                    proposal.traceJson().getAsJsonObject("compactLaneTarget"));
            spacing = proposal.spacingBlocks();
            BlockPoint guide = proposal.guides().get(0);
            guides.add(guide);
            directions.add(proposal.traceJson().get("compactLaneSide").getAsString());
            assertEquals(1, proposal.traceJson().get("compactLaneRank").getAsInt());
            assertEquals(spacing, Math.max(Math.abs(guide.x()), Math.abs(guide.z())));
            assertTrue(proposal.traceJson().get("compactMicroAdjustmentEnabled").getAsBoolean());
            assertEquals(proposal.guides().size(), proposal.traceJson().getAsJsonArray("compactCandidateGuides").size());
            assertTrue(Math.hypot(proposal.frontageTarget().x(), proposal.frontageTarget().z())
                    < Math.hypot(guide.x(), guide.z()));
        }
        assertEquals(8, guides.size());
        assertEquals(8, directions.size());

        var outer = planner.propose("COMPACT", CityBlueprint.DensityClass.DENSE,
                47L, "compact", 8, frame, center, null, false, 18);
        assertEquals(2, outer.traceJson().get("compactLaneRank").getAsInt());
        assertTrue(Math.abs(Math.hypot(outer.guides().get(0).x(), outer.guides().get(0).z())
                - spacing * 2.0) <= 1.0);
    }

    @Test
    void compactMicroAdjustmentsStayOnTheSameLayerSide() {
        BlockPoint center = new BlockPoint(100, 200);
        var proposal = planner.propose("COMPACT", CityBlueprint.DensityClass.DENSE,
                49L, "compact", 0, planner.worldFrame(center), center, null, false, 18);

        assertEquals(new BlockPoint(123, 200), proposal.guides().get(0));
        assertEquals(5, proposal.guides().size());
        assertEquals(2, proposal.guides().stream().filter(point -> point.z() < center.z()).count());
        assertEquals(2, proposal.guides().stream().filter(point -> point.z() > center.z()).count());
        assertTrue(proposal.guides().stream().allMatch(point ->
                Math.max(Math.abs(point.x() - center.x()), Math.abs(point.z() - center.z())) == 23));
    }

    @Test
    void compactNeedsOneLocalFollowerBeforeScanningTheOuterRing() {
        assertTrue(CityBlueprintGroupLayoutPlanner.compactOuterRingHasLocalFrontier(7, 1));
        assertFalse(CityBlueprintGroupLayoutPlanner.compactOuterRingHasLocalFrontier(8, 1));
        assertTrue(CityBlueprintGroupLayoutPlanner.compactOuterRingHasLocalFrontier(8, 0));
        assertTrue(CityBlueprintGroupLayoutPlanner.compactOuterRingHasLocalFrontier(8, 2));
    }

    @Test
    void organicCompactUsesOnlyOneToThreeBlockCollisionGaps() {
        var parameters = planner.parameters("ORGANIC_COMPACT", CityBlueprint.DensityClass.SPARSE);
        assertEquals(2, parameters.targetEdgeGapBlocks());
        assertEquals(3, parameters.maximumEdgeGapBlocks());
        assertTrue(parameters.jitterBlocks() > planner.parameters(
                "ORGANIC_COMPACT", CityBlueprint.DensityClass.DENSE).jitterBlocks());
    }

    @Test
    void linearLayoutKeepsThePrimaryAtTheAxisEndpointAndPairsFollowersAcrossAStreetBand() {
        BlockPoint center = new BlockPoint(0, 0);
        var frame = planner.frame(center, new BlockPoint(100, 0), 33L, "market");
        var primary = planner.propose("LINEAR", CityBlueprint.DensityClass.BALANCED,
                33L, "market", 0, frame, center, null, false, 18);
        var left = planner.propose("LINEAR", CityBlueprint.DensityClass.BALANCED,
                33L, "market", 1, frame, center, null, false, 18);
        var right = planner.propose("LINEAR", CityBlueprint.DensityClass.BALANCED,
                33L, "market", 2, frame, center, null, false, 18);
        var nextLeft = planner.propose("LINEAR", CityBlueprint.DensityClass.BALANCED,
                33L, "market", 3, frame, center, null, false, 18);

        assertEquals(center, primary.guides().get(0));
        assertEquals(left.guides().get(0).x(), right.guides().get(0).x());
        assertTrue(left.guides().get(0).z() < 0 && right.guides().get(0).z() > 0);
        assertTrue(nextLeft.guides().get(0).x() > left.guides().get(0).x());
        assertTrue(right.guides().get(0).z() - left.guides().get(0).z()
                >= 18 + left.parameters().streetBandWidthBlocks());
        assertTrue(primary.traceJson().get("primaryAxisEndpoint").getAsBoolean());
        assertTrue(left.traceJson().get("streetBandReserved").getAsBoolean());
        assertEquals("LEFT", left.traceJson().get("streetBandSide").getAsString());
    }

    @Test
    void centerSymmetricOffersAtomicOppositePairsAroundTheCommittedCore() {
        BlockPoint center = new BlockPoint(120, -80);
        var frame = planner.frame(center, new BlockPoint(220, -80), 37L, "administration");
        var options = planner.symmetricPairOptions(CityBlueprint.DensityClass.DENSE,
                frame, 0, 60, 18);

        assertEquals(2, options.size());
        Set<Integer> axes = new LinkedHashSet<>();
        for (var pair : options) {
            axes.add(pair.axisVariant());
            assertEquals(center.x() * 2, pair.first().x() + pair.opposite().x());
            assertEquals(center.z() * 2, pair.first().z() + pair.opposite().z());
            assertTrue(pair.traceJson(1).get("atomicPair").getAsBoolean());
        }
        assertEquals(Set.of(0, 2), axes);
    }

    @Test
    void centerSymmetricFillsCompleteLayersAfterCardinalPairs() {
        BlockPoint center = new BlockPoint(120, -80);
        var frame = planner.frame(center, new BlockPoint(220, -80), 37L, "administration");
        var first = planner.symmetricPairOptions(CityBlueprint.DensityClass.DENSE,
                frame, 0, 60, 39).get(0);
        var second = planner.symmetricPairOptions(CityBlueprint.DensityClass.DENSE,
                frame, 1, 60, 39).get(0);
        var third = planner.symmetricPairOptions(CityBlueprint.DensityClass.DENSE,
                frame, 2, 60, 39).get(0);

        assertEquals(0, first.ringIndex());
        assertEquals(0, second.ringIndex());
        assertEquals(0, third.ringIndex());
        assertEquals(0, first.axisVariant());
        assertEquals(2, second.axisVariant());
        assertEquals(0, third.axisVariant());
        assertEquals(second.radiusBlocks(), third.radiusBlocks());
        Set<BlockPoint> members = new LinkedHashSet<>();
        for (int pairIndex = 0; pairIndex < 12; pairIndex++) {
            var pair = planner.symmetricPairOptions(CityBlueprint.DensityClass.DENSE,
                    frame, pairIndex, 60, 39).get(0);
            assertTrue(members.add(pair.first()));
            assertTrue(members.add(pair.opposite()));
            assertEquals(center.x() * 2, pair.first().x() + pair.opposite().x());
            assertEquals(center.z() * 2, pair.first().z() + pair.opposite().z());
            assertTrue(pair.ringIndex() <= 1, "24 followers fit in two complete layers");
        }
        assertEquals(5, members.stream().map(BlockPoint::x).distinct().count());
        assertEquals(5, members.stream().map(BlockPoint::z).distinct().count());
    }
}
