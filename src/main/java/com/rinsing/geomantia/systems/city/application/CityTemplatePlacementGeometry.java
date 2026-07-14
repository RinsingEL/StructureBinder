package com.rinsing.geomantia.systems.city.application;

import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.List;
import java.util.Objects;

/** Pure X/Z geometry for a fixed City template placement. */
public final class CityTemplatePlacementGeometry {
    public enum Rotation {
        NONE,
        CLOCKWISE_90,
        CLOCKWISE_180,
        COUNTERCLOCKWISE_90;

        private int quarterTurnsClockwise() {
            return this == CLOCKWISE_90 ? 1 : this == CLOCKWISE_180 ? 2 : 0;
        }

        private boolean swapsAxes() {
            return this == CLOCKWISE_90 || this == COUNTERCLOCKWISE_90;
        }
    }

    public enum Mirror {
        NONE,
        LEFT_RIGHT,
        FRONT_BACK
    }

    public enum Direction {
        NORTH(0, -1),
        EAST(1, 0),
        SOUTH(0, 1),
        WEST(-1, 0);

        private final int x;
        private final int z;

        Direction(int x, int z) {
            this.x = x;
            this.z = z;
        }

        private Direction transform(Mirror mirror, Rotation rotation) {
            int transformedX = x;
            int transformedZ = z;
            if (mirror == Mirror.LEFT_RIGHT) {
                transformedX = -transformedX;
            } else if (mirror == Mirror.FRONT_BACK) {
                transformedZ = -transformedZ;
            }
            for (int i = 0; i < rotation.quarterTurnsClockwise(); i++) {
                int nextX = -transformedZ;
                transformedZ = transformedX;
                transformedX = nextX;
            }
            if (rotation == Rotation.COUNTERCLOCKWISE_90) {
                int nextX = transformedZ;
                transformedZ = -transformedX;
                transformedX = nextX;
            }
            return fromVector(transformedX, transformedZ);
        }

        private static Direction fromVector(int x, int z) {
            for (Direction direction : values()) {
                if (direction.x == x && direction.z == z) {
                    return direction;
                }
            }
            throw new IllegalArgumentException("Direction transform did not produce a cardinal direction.");
        }
    }

    public record Size(int width, int height, int depth) {
        public Size {
            if (width <= 0 || height <= 0 || depth <= 0) {
                throw new IllegalArgumentException("Template dimensions must be positive.");
            }
        }
    }

    public record RoadEntrance(String entranceId, BlockPoint localPosition, Direction direction) {
        public RoadEntrance {
            entranceId = requireText(entranceId, "entranceId");
            Objects.requireNonNull(localPosition, "localPosition");
            Objects.requireNonNull(direction, "direction");
        }
    }

    public record TransformedRoadEntrance(String entranceId, BlockPoint relativePosition, Direction direction) {
        public TransformedRoadEntrance {
            entranceId = requireText(entranceId, "entranceId");
            Objects.requireNonNull(relativePosition, "relativePosition");
            Objects.requireNonNull(direction, "direction");
        }

        public BlockPoint worldPosition(BlockPoint anchor) {
            Objects.requireNonNull(anchor, "anchor");
            return new BlockPoint(anchor.x() + relativePosition.x(), anchor.z() + relativePosition.z());
        }
    }

    private final Size sourceSize;
    private final Rotation rotation;
    private final Mirror mirror;
    private final Size transformedSize;
    private final BlockBounds relativeBounds;
    private final List<TransformedRoadEntrance> roadEntrances;

    private CityTemplatePlacementGeometry(Size sourceSize, Rotation rotation, Mirror mirror,
                                          List<RoadEntrance> roadEntrances) {
        this.sourceSize = Objects.requireNonNull(sourceSize, "sourceSize");
        this.rotation = Objects.requireNonNull(rotation, "rotation");
        this.mirror = Objects.requireNonNull(mirror, "mirror");
        // Mirror local X/Z first, then rotate around the source footprint and normalize to min=(0, 0).
        this.transformedSize = rotation.swapsAxes()
                ? new Size(sourceSize.depth(), sourceSize.height(), sourceSize.width())
                : sourceSize;
        this.relativeBounds = new BlockBounds(0, 0, transformedSize.width() - 1, transformedSize.depth() - 1);
        this.roadEntrances = roadEntrances.stream()
                .map(entrance -> new TransformedRoadEntrance(
                        entrance.entranceId(), transformLocalPoint(entrance.localPosition()),
                        entrance.direction().transform(mirror, rotation)))
                .toList();
    }

    public static CityTemplatePlacementGeometry of(Size sourceSize, Rotation rotation, Mirror mirror,
                                                    List<RoadEntrance> roadEntrances) {
        return new CityTemplatePlacementGeometry(sourceSize, rotation, mirror,
                List.copyOf(Objects.requireNonNull(roadEntrances, "roadEntrances")));
    }

    public Size sourceSize() {
        return sourceSize;
    }

    public Size transformedSize() {
        return transformedSize;
    }

    public Rotation rotation() {
        return rotation;
    }

    public Mirror mirror() {
        return mirror;
    }

    /** Bounds are closed and include both min and max block coordinates. */
    public BlockBounds relativeBounds() {
        return relativeBounds;
    }

    public BlockBounds worldBounds(BlockPoint anchor) {
        Objects.requireNonNull(anchor, "anchor");
        return new BlockBounds(anchor.x(), anchor.z(),
                anchor.x() + transformedSize.width() - 1,
                anchor.z() + transformedSize.depth() - 1);
    }

    public List<TransformedRoadEntrance> roadEntrances() {
        return roadEntrances;
    }

    public BlockPoint worldPosition(BlockPoint anchor, BlockPoint localPosition) {
        Objects.requireNonNull(anchor, "anchor");
        BlockPoint relative = transformLocalPoint(localPosition);
        return new BlockPoint(anchor.x() + relative.x(), anchor.z() + relative.z());
    }

    public BlockPoint transformLocalPoint(BlockPoint localPosition) {
        Objects.requireNonNull(localPosition, "localPosition");
        if (localPosition.x() < 0 || localPosition.x() >= sourceSize.width()
                || localPosition.z() < 0 || localPosition.z() >= sourceSize.depth()) {
            throw new IllegalArgumentException("Local point is outside the source template dimensions.");
        }
        int x = localPosition.x();
        int z = localPosition.z();
        if (mirror == Mirror.LEFT_RIGHT) {
            // LEFT_RIGHT reflects local X; FRONT_BACK reflects local Z.
            x = sourceSize.width() - 1 - x;
        } else if (mirror == Mirror.FRONT_BACK) {
            z = sourceSize.depth() - 1 - z;
        }
        return switch (rotation) {
            case NONE -> new BlockPoint(x, z);
            case CLOCKWISE_90 -> new BlockPoint(sourceSize.depth() - 1 - z, x);
            case CLOCKWISE_180 -> new BlockPoint(sourceSize.width() - 1 - x,
                    sourceSize.depth() - 1 - z);
            case COUNTERCLOCKWISE_90 -> new BlockPoint(z, sourceSize.width() - 1 - x);
        };
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank.");
        }
        return value.trim();
    }
}
