package com.rinsing.geomantia.systems.city.application;

import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityTemplatePlacementGeometryTest {
    private static final CityTemplatePlacementGeometry.Size SIZE =
            new CityTemplatePlacementGeometry.Size(5, 3, 9);
    private static final BlockPoint ANCHOR = new BlockPoint(-20, 30);

    @Test
    void everyRotationAndMinecraftMirrorTransformsAllSourceCoordinatesIntoClosedFootprint() {
        for (CityTemplatePlacementGeometry.Rotation rotation
                : CityTemplatePlacementGeometry.Rotation.values()) {
            for (CityTemplatePlacementGeometry.Mirror mirror
                    : CityTemplatePlacementGeometry.Mirror.values()) {
                String context = rotation + " / " + mirror;
                CityTemplatePlacementGeometry geometry = CityTemplatePlacementGeometry.of(
                        SIZE, rotation, mirror, List.of());
                int expectedWidth = swapsAxes(rotation) ? SIZE.depth() : SIZE.width();
                int expectedDepth = swapsAxes(rotation) ? SIZE.width() : SIZE.depth();
                assertEquals(new CityTemplatePlacementGeometry.Size(
                                expectedWidth, SIZE.height(), expectedDepth),
                        geometry.transformedSize(), context);
                assertEquals(new BlockBounds(0, 0, expectedWidth - 1, expectedDepth - 1),
                        geometry.relativeBounds(), context);
                assertEquals(new BlockBounds(ANCHOR.x(), ANCHOR.z(),
                                ANCHOR.x() + expectedWidth - 1, ANCHOR.z() + expectedDepth - 1),
                        geometry.worldBounds(ANCHOR), context);

                Set<BlockPoint> transformed = new HashSet<>();
                for (int x = 0; x < SIZE.width(); x++) {
                    for (int z = 0; z < SIZE.depth(); z++) {
                        BlockPoint local = new BlockPoint(x, z);
                        BlockPoint expected = expectedPoint(local, rotation, mirror);
                        assertEquals(expected, geometry.transformLocalPoint(local),
                                context + " local=" + local);
                        transformed.add(geometry.transformLocalPoint(local));
                    }
                }
                assertEquals(SIZE.width() * SIZE.depth(), transformed.size(), context);
                assertTrue(transformed.stream().allMatch(point -> geometry.relativeBounds()
                                .contains(point.x(), point.z())),
                        context);
            }
        }
    }

    @Test
    void roadEntrancesUseTheSamePointAndDirectionTransformForAllCombinations() {
        List<CityTemplatePlacementGeometry.RoadEntrance> entrances = List.of(
                new CityTemplatePlacementGeometry.RoadEntrance(
                        "north", new BlockPoint(1, 0), CityTemplatePlacementGeometry.Direction.NORTH),
                new CityTemplatePlacementGeometry.RoadEntrance(
                        "east", new BlockPoint(4, 2), CityTemplatePlacementGeometry.Direction.EAST),
                new CityTemplatePlacementGeometry.RoadEntrance(
                        "south", new BlockPoint(3, 8), CityTemplatePlacementGeometry.Direction.SOUTH),
                new CityTemplatePlacementGeometry.RoadEntrance(
                        "west", new BlockPoint(0, 6), CityTemplatePlacementGeometry.Direction.WEST));

        for (CityTemplatePlacementGeometry.Rotation rotation
                : CityTemplatePlacementGeometry.Rotation.values()) {
            for (CityTemplatePlacementGeometry.Mirror mirror
                    : CityTemplatePlacementGeometry.Mirror.values()) {
                String context = rotation + " / " + mirror;
                CityTemplatePlacementGeometry geometry = CityTemplatePlacementGeometry.of(
                        SIZE, rotation, mirror, entrances);
                for (int index = 0; index < entrances.size(); index++) {
                    CityTemplatePlacementGeometry.RoadEntrance source = entrances.get(index);
                    CityTemplatePlacementGeometry.TransformedRoadEntrance transformed =
                            geometry.roadEntrances().get(index);
                    assertEquals(expectedPoint(source.localPosition(), rotation, mirror),
                            transformed.relativePosition(), context + " / " + source.entranceId());
                    assertEquals(expectedDirection(source.direction(), rotation, mirror),
                            transformed.direction(), context + " / " + source.entranceId());
                    BlockPoint relative = transformed.relativePosition();
                    assertEquals(new BlockPoint(ANCHOR.x() + relative.x(), ANCHOR.z() + relative.z()),
                            transformed.worldPosition(ANCHOR), context + " / " + source.entranceId());
                }
            }
        }
    }

    @Test
    void mirrorNamesFollowMinecraftNativeAxes() {
        CityTemplatePlacementGeometry leftRight = CityTemplatePlacementGeometry.of(
                SIZE, CityTemplatePlacementGeometry.Rotation.NONE,
                CityTemplatePlacementGeometry.Mirror.LEFT_RIGHT, List.of());
        CityTemplatePlacementGeometry frontBack = CityTemplatePlacementGeometry.of(
                SIZE, CityTemplatePlacementGeometry.Rotation.NONE,
                CityTemplatePlacementGeometry.Mirror.FRONT_BACK, List.of());

        assertEquals(new BlockPoint(1, 7), leftRight.transformLocalPoint(new BlockPoint(1, 1)));
        assertEquals(new BlockPoint(3, 1), frontBack.transformLocalPoint(new BlockPoint(1, 1)));
    }

    private static BlockPoint expectedPoint(BlockPoint local,
                                            CityTemplatePlacementGeometry.Rotation rotation,
                                            CityTemplatePlacementGeometry.Mirror mirror) {
        int x = mirror == CityTemplatePlacementGeometry.Mirror.FRONT_BACK
                ? SIZE.width() - 1 - local.x() : local.x();
        int z = mirror == CityTemplatePlacementGeometry.Mirror.LEFT_RIGHT
                ? SIZE.depth() - 1 - local.z() : local.z();
        return switch (rotation) {
            case NONE -> new BlockPoint(x, z);
            case CLOCKWISE_90 -> new BlockPoint(SIZE.depth() - 1 - z, x);
            case CLOCKWISE_180 -> new BlockPoint(SIZE.width() - 1 - x, SIZE.depth() - 1 - z);
            case COUNTERCLOCKWISE_90 -> new BlockPoint(z, SIZE.width() - 1 - x);
        };
    }

    private static CityTemplatePlacementGeometry.Direction expectedDirection(
            CityTemplatePlacementGeometry.Direction direction,
            CityTemplatePlacementGeometry.Rotation rotation,
            CityTemplatePlacementGeometry.Mirror mirror) {
        int x = switch (direction) {
            case EAST -> 1;
            case WEST -> -1;
            default -> 0;
        };
        int z = switch (direction) {
            case SOUTH -> 1;
            case NORTH -> -1;
            default -> 0;
        };
        if (mirror == CityTemplatePlacementGeometry.Mirror.LEFT_RIGHT) {
            z = -z;
        } else if (mirror == CityTemplatePlacementGeometry.Mirror.FRONT_BACK) {
            x = -x;
        }
        int rotatedX;
        int rotatedZ;
        switch (rotation) {
            case NONE -> {
                rotatedX = x;
                rotatedZ = z;
            }
            case CLOCKWISE_90 -> {
                rotatedX = -z;
                rotatedZ = x;
            }
            case CLOCKWISE_180 -> {
                rotatedX = -x;
                rotatedZ = -z;
            }
            case COUNTERCLOCKWISE_90 -> {
                rotatedX = z;
                rotatedZ = -x;
            }
            default -> throw new IllegalStateException("Unexpected rotation: " + rotation);
        }
        if (rotatedX == 1) {
            return CityTemplatePlacementGeometry.Direction.EAST;
        }
        if (rotatedX == -1) {
            return CityTemplatePlacementGeometry.Direction.WEST;
        }
        return rotatedZ == 1
                ? CityTemplatePlacementGeometry.Direction.SOUTH
                : CityTemplatePlacementGeometry.Direction.NORTH;
    }

    private static boolean swapsAxes(CityTemplatePlacementGeometry.Rotation rotation) {
        return rotation == CityTemplatePlacementGeometry.Rotation.CLOCKWISE_90
                || rotation == CityTemplatePlacementGeometry.Rotation.COUNTERCLOCKWISE_90;
    }
}
