package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.rinsing.geomantia.systems.city.application.CityTemplatePlacementGeometry;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityTemplateRuntimeTransformTest {
    @Test
    void runtimeMatchesCityGeometryForEveryCoordinateAndTransform() {
        CityTemplatePlacementGeometry.Size size = new CityTemplatePlacementGeometry.Size(5, 7, 9);
        BlockPoint anchor = new BlockPoint(21, -11);
        for (CityTemplatePlacementGeometry.Rotation rotation : CityTemplatePlacementGeometry.Rotation.values()) {
            for (CityTemplatePlacementGeometry.Mirror mirror : CityTemplatePlacementGeometry.Mirror.values()) {
                CityTemplateRuntimeTransform.Transform runtime = CityTemplateRuntimeTransform.derive(
                        size, anchor, 64, rotation, mirror);
                CityTemplatePlacementGeometry geometry = CityTemplatePlacementGeometry.of(
                        size, rotation, mirror, List.of());

                assertTrue(runtime.validateAgainst(geometry).valid(), rotation + " / " + mirror);
                assertEquals(geometry.worldBounds(anchor), runtime.transformedFootprint());
                assertEquals(BlockPos.ZERO, runtime.rotationPivot());
                for (int x = 0; x < size.width(); x++) {
                    for (int z = 0; z < size.depth(); z++) {
                        BlockPoint local = new BlockPoint(x, z);
                        BlockPoint expected = geometry.worldPosition(anchor, local);
                        BlockPos actual = runtime.worldPosition(local);
                        assertEquals(expected.x(), actual.getX());
                        assertEquals(expected.z(), actual.getZ());
                    }
                }
            }
        }
    }

    @Test
    void rotationsUseNormalizedPlacementOrigin() {
        CityTemplatePlacementGeometry.Size size = new CityTemplatePlacementGeometry.Size(19, 8, 13);
        BlockPoint anchor = new BlockPoint(660210, 659551);

        CityTemplateRuntimeTransform.Transform clockwise = CityTemplateRuntimeTransform.derive(
                size, anchor, 107, CityTemplatePlacementGeometry.Rotation.CLOCKWISE_90,
                CityTemplatePlacementGeometry.Mirror.NONE);
        CityTemplateRuntimeTransform.Transform counterclockwise = CityTemplateRuntimeTransform.derive(
                size, anchor, 107, CityTemplatePlacementGeometry.Rotation.COUNTERCLOCKWISE_90,
                CityTemplatePlacementGeometry.Mirror.NONE);

        assertEquals(new BlockPos(660222, 107, 659551), clockwise.placementOrigin());
        assertEquals(new BlockPos(660210, 107, 659569), counterclockwise.placementOrigin());
        assertEquals(new BlockBounds(660210, 659551, 660222, 659569),
                clockwise.transformedFootprint());
        assertEquals(clockwise.transformedFootprint(), counterclockwise.transformedFootprint());
    }

    @Test
    void mismatchedRuntimeAndPlanningTransformIsRejected() {
        CityTemplatePlacementGeometry.Size size = new CityTemplatePlacementGeometry.Size(19, 8, 13);
        BlockPoint anchor = new BlockPoint(0, 0);
        CityTemplateRuntimeTransform.Transform runtime = CityTemplateRuntimeTransform.derive(
                size, anchor, 64, CityTemplatePlacementGeometry.Rotation.CLOCKWISE_90,
                CityTemplatePlacementGeometry.Mirror.NONE);
        CityTemplatePlacementGeometry wrong = CityTemplatePlacementGeometry.of(size,
                CityTemplatePlacementGeometry.Rotation.COUNTERCLOCKWISE_90,
                CityTemplatePlacementGeometry.Mirror.NONE, List.of());

        CityTemplateRuntimeTransform.Validation validation = runtime.validateAgainst(wrong);

        assertFalse(validation.valid());
        assertEquals("TEMPLATE_RUNTIME_TRANSFORM_MISMATCH", validation.reasonCode());
    }
}
