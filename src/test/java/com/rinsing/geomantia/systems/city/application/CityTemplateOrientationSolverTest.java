package com.rinsing.geomantia.systems.city.application;

import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityTemplateOrientationSolverTest {
    private final CityTemplateOrientationSolver solver = new CityTemplateOrientationSolver();

    @Test
    void ranksAllowedRotationsTowardPointAndCardinalTargets() {
        CityTemplateCatalog.Template template = template(List.of(
                new CityTemplatePlacementGeometry.RoadEntrance(
                        "front", new BlockPoint(2, 0), CityTemplatePlacementGeometry.Direction.NORTH)));

        List<CityTemplateOrientationSolver.RotationScore> east = solver.rank(
                template, CityTemplatePlacementGeometry.Mirror.NONE, "front", new BlockPoint(10, 10),
                CityTemplateOrientationSolver.FacingTarget.point("array_center", new BlockPoint(30, 10)));
        assertEquals(CityTemplatePlacementGeometry.Rotation.CLOCKWISE_90, east.get(0).rotation());
        assertEquals(CityTemplatePlacementGeometry.Direction.EAST, east.get(0).frontageDirection());
        assertEquals(1.0, east.get(0).alignmentScore(), 0.0001);

        List<CityTemplateOrientationSolver.RotationScore> south = solver.rank(
                template, CityTemplatePlacementGeometry.Mirror.NONE, "", BlockPoint.ORIGIN,
                CityTemplateOrientationSolver.FacingTarget.cardinal(
                        "cardinal:south", CityTemplatePlacementGeometry.Direction.SOUTH));
        assertEquals(CityTemplatePlacementGeometry.Rotation.CLOCKWISE_180, south.get(0).rotation());
        assertEquals(CityTemplatePlacementGeometry.Direction.SOUTH, south.get(0).frontageDirection());
    }

    @Test
    void frontageMustBeUnambiguousOrExplicitlySelected() {
        CityTemplateCatalog.Template ambiguous = template(List.of(
                new CityTemplatePlacementGeometry.RoadEntrance(
                        "north_door", new BlockPoint(2, 0), CityTemplatePlacementGeometry.Direction.NORTH),
                new CityTemplatePlacementGeometry.RoadEntrance(
                        "south_door", new BlockPoint(2, 8), CityTemplatePlacementGeometry.Direction.SOUTH)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> solver.rank(
                ambiguous, CityTemplatePlacementGeometry.Mirror.NONE, "", BlockPoint.ORIGIN,
                CityTemplateOrientationSolver.FacingTarget.cardinal(
                        "cardinal:east", CityTemplatePlacementGeometry.Direction.EAST)));
        assertTrue(error.getMessage().contains("D4_ARRAY_LAYOUT_FRONTAGE_ENTRANCE_AMBIGUOUS"));

        List<CityTemplateOrientationSolver.RotationScore> selected = solver.rank(
                ambiguous, CityTemplatePlacementGeometry.Mirror.NONE, "south_door", BlockPoint.ORIGIN,
                CityTemplateOrientationSolver.FacingTarget.cardinal(
                        "cardinal:east", CityTemplatePlacementGeometry.Direction.EAST));
        assertEquals("south_door", selected.get(0).entranceId());
        assertEquals(CityTemplatePlacementGeometry.Direction.EAST, selected.get(0).frontageDirection());
    }

    @Test
    void noFixedFrontChoosesOnlyAuthoredPortsWithoutExpandingRotationSearch() {
        var flexible = flexible(template(List.of(
                new CityTemplatePlacementGeometry.RoadEntrance(
                        "north", new BlockPoint(2, 0), CityTemplatePlacementGeometry.Direction.NORTH),
                new CityTemplatePlacementGeometry.RoadEntrance(
                        "south", new BlockPoint(2, 8), CityTemplatePlacementGeometry.Direction.SOUTH))));
        var target = CityTemplateOrientationSolver.FacingTarget.cardinal(
                "road", CityTemplatePlacementGeometry.Direction.SOUTH);
        var ranked = solver.rank(flexible, CityTemplatePlacementGeometry.Mirror.NONE, "",
                BlockPoint.ORIGIN, target);
        assertEquals(4, ranked.size());
        assertEquals(CityTemplatePlacementGeometry.Rotation.NONE, ranked.get(0).rotation());
        assertEquals("south", ranked.get(0).entranceId());
        assertEquals(1.0, ranked.get(0).alignmentScore());
        assertEquals(ranked, solver.rank(flexible, CityTemplatePlacementGeometry.Mirror.NONE, "",
                BlockPoint.ORIGIN, target));

        var explicit = solver.rank(flexible, CityTemplatePlacementGeometry.Mirror.NONE, "north",
                BlockPoint.ORIGIN, target);
        assertTrue(explicit.stream().allMatch(score -> score.entranceId().equals("north")));
        assertEquals(CityTemplatePlacementGeometry.Rotation.CLOCKWISE_180, explicit.get(0).rotation());
        assertThrows(IllegalArgumentException.class, () -> solver.rank(flexible,
                CityTemplatePlacementGeometry.Mirror.NONE, "invented", BlockPoint.ORIGIN, target));
        assertThrows(IllegalArgumentException.class, () -> solver.rank(flexible,
                CityTemplatePlacementGeometry.Mirror.LEFT_RIGHT, "", BlockPoint.ORIGIN, target));
    }

    @Test
    void declaredFrontIsNotOverriddenByFlexiblePolicy() {
        var flexible = flexible(template(List.of(
                new CityTemplatePlacementGeometry.RoadEntrance(
                        "front", new BlockPoint(2, 0), CityTemplatePlacementGeometry.Direction.NORTH),
                new CityTemplatePlacementGeometry.RoadEntrance(
                        "south", new BlockPoint(2, 8), CityTemplatePlacementGeometry.Direction.SOUTH))));
        var ranked = solver.rank(flexible, CityTemplatePlacementGeometry.Mirror.NONE, "", BlockPoint.ORIGIN,
                CityTemplateOrientationSolver.FacingTarget.cardinal("road", CityTemplatePlacementGeometry.Direction.SOUTH));
        assertTrue(ranked.stream().allMatch(score -> score.entranceId().equals("front")));
    }

    @Test
    void flexibleFrontUsesTransformedDirectionsAndHonorsRestrictedRotations() {
        var base = template(List.of(
                new CityTemplatePlacementGeometry.RoadEntrance(
                        "north", new BlockPoint(2, 0), CityTemplatePlacementGeometry.Direction.NORTH),
                new CityTemplatePlacementGeometry.RoadEntrance(
                        "south", new BlockPoint(2, 8), CityTemplatePlacementGeometry.Direction.SOUTH)));
        var flexible = new CityTemplateCatalog.Template(base.buildingSemantic(), base.style(), base.templateId(),
                base.nbtFile(), base.contentHash(), base.variantId(), base.rawSize(),
                List.of(CityTemplatePlacementGeometry.Rotation.NONE),
                List.of(CityTemplatePlacementGeometry.Mirror.LEFT_RIGHT), base.roadEntrances(),
                base.terrainPosePolicy(), base.supportPolicy(), base.clearanceBlocks(),
                CityTemplateCatalog.FrontagePolicy.ANY_AUTHORED_ENTRANCE);
        var ranked = solver.rank(flexible, CityTemplatePlacementGeometry.Mirror.LEFT_RIGHT, "", BlockPoint.ORIGIN,
                CityTemplateOrientationSolver.FacingTarget.cardinal("road", CityTemplatePlacementGeometry.Direction.SOUTH));
        assertEquals(1, ranked.size());
        assertEquals("north", ranked.get(0).entranceId());
        assertEquals(CityTemplatePlacementGeometry.Direction.SOUTH, ranked.get(0).frontageDirection());
    }

    private static CityTemplateCatalog.Template flexible(CityTemplateCatalog.Template base) {
        return new CityTemplateCatalog.Template(base.buildingSemantic(), base.style(), base.templateId(),
                base.nbtFile(), base.contentHash(), base.variantId(), base.rawSize(), base.allowedRotations(),
                base.allowedMirrors(), base.roadEntrances(), base.terrainPosePolicy(), base.supportPolicy(),
                base.clearanceBlocks(), CityTemplateCatalog.FrontagePolicy.ANY_AUTHORED_ENTRANCE);
    }

    private static CityTemplateCatalog.Template template(
            List<CityTemplatePlacementGeometry.RoadEntrance> entrances) {
        return new CityTemplateCatalog.Template(
                "commerce.market", "oak", "test:market", "test:market/house", "hash", "oak",
                new CityTemplatePlacementGeometry.Size(5, 4, 9),
                List.of(CityTemplatePlacementGeometry.Rotation.NONE,
                        CityTemplatePlacementGeometry.Rotation.CLOCKWISE_90,
                        CityTemplatePlacementGeometry.Rotation.CLOCKWISE_180,
                        CityTemplatePlacementGeometry.Rotation.COUNTERCLOCKWISE_90),
                List.of(CityTemplatePlacementGeometry.Mirror.NONE), entrances,
                "grounded", "none", 1);
    }
}
