package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.rinsing.geomantia.systems.city.application.CityTemplatePlacementGeometry;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Verifies that Minecraft's template transform matches the geometry frozen by City D4-D6. */
final class CityTemplateRuntimeTransform {
    private CityTemplateRuntimeTransform() {
    }

    static Transform derive(CityTemplatePlacementGeometry.Size sourceSize,
                            BlockPoint anchor,
                            int datum,
                            CityTemplatePlacementGeometry.Rotation rotation,
                            CityTemplatePlacementGeometry.Mirror mirror) {
        Objects.requireNonNull(sourceSize, "sourceSize");
        Objects.requireNonNull(anchor, "anchor");
        Mirror minecraftMirror = minecraftMirror(Objects.requireNonNull(mirror, "mirror"));
        Rotation minecraftRotation = minecraftRotation(Objects.requireNonNull(rotation, "rotation"));
        BlockPos anchorOrigin = new BlockPos(anchor.x(), datum, anchor.z());
        BlockPos placementOrigin = StructureTemplate.getZeroPositionWithTransform(
                anchorOrigin, minecraftMirror, minecraftRotation, sourceSize.width(), sourceSize.depth());
        BlockPos pivot = BlockPos.ZERO;

        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int x : new int[]{0, sourceSize.width() - 1}) {
            for (int z : new int[]{0, sourceSize.depth() - 1}) {
                BlockPos transformed = StructureTemplate.transform(
                        new BlockPos(x, 0, z), minecraftMirror, minecraftRotation, pivot)
                        .offset(placementOrigin);
                minX = Math.min(minX, transformed.getX());
                minZ = Math.min(minZ, transformed.getZ());
                maxX = Math.max(maxX, transformed.getX());
                maxZ = Math.max(maxZ, transformed.getZ());
            }
        }
        return new Transform(sourceSize, anchor, datum, rotation, mirror, minecraftMirror,
                minecraftRotation, placementOrigin, pivot, new BlockBounds(minX, minZ, maxX, maxZ));
    }

    private static Mirror minecraftMirror(CityTemplatePlacementGeometry.Mirror mirror) {
        return switch (mirror) {
            case NONE -> Mirror.NONE;
            case LEFT_RIGHT -> Mirror.LEFT_RIGHT;
            case FRONT_BACK -> Mirror.FRONT_BACK;
        };
    }

    private static Rotation minecraftRotation(CityTemplatePlacementGeometry.Rotation rotation) {
        return switch (rotation) {
            case NONE -> Rotation.NONE;
            case CLOCKWISE_90 -> Rotation.CLOCKWISE_90;
            case CLOCKWISE_180 -> Rotation.CLOCKWISE_180;
            case COUNTERCLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
        };
    }

    record Transform(CityTemplatePlacementGeometry.Size sourceSize,
                     BlockPoint anchor,
                     int datum,
                     CityTemplatePlacementGeometry.Rotation rotation,
                     CityTemplatePlacementGeometry.Mirror mirror,
                     Mirror minecraftMirror,
                     Rotation minecraftRotation,
                     BlockPos placementOrigin,
                     BlockPos rotationPivot,
                     BlockBounds transformedFootprint) {
        Transform {
            Objects.requireNonNull(sourceSize, "sourceSize");
            Objects.requireNonNull(anchor, "anchor");
            Objects.requireNonNull(rotation, "rotation");
            Objects.requireNonNull(mirror, "mirror");
            Objects.requireNonNull(minecraftMirror, "minecraftMirror");
            Objects.requireNonNull(minecraftRotation, "minecraftRotation");
            Objects.requireNonNull(placementOrigin, "placementOrigin");
            Objects.requireNonNull(rotationPivot, "rotationPivot");
            Objects.requireNonNull(transformedFootprint, "transformedFootprint");
        }

        BlockPos worldPosition(BlockPoint localPosition) {
            Objects.requireNonNull(localPosition, "localPosition");
            if (localPosition.x() < 0 || localPosition.x() >= sourceSize.width()
                    || localPosition.z() < 0 || localPosition.z() >= sourceSize.depth()) {
                throw new IllegalArgumentException("Local point is outside the source template dimensions.");
            }
            return StructureTemplate.transform(new BlockPos(localPosition.x(), 0, localPosition.z()),
                    minecraftMirror, minecraftRotation, rotationPivot).offset(placementOrigin);
        }

        Validation validateAgainst(CityTemplatePlacementGeometry geometry) {
            Objects.requireNonNull(geometry, "geometry");
            BlockBounds plannedFootprint = geometry.worldBounds(anchor);
            if (!plannedFootprint.equals(transformedFootprint)) {
                return Validation.invalid("TEMPLATE_RUNTIME_TRANSFORM_MISMATCH",
                        "Minecraft runtime footprint differs from the City NBT footprint.");
            }
            Set<Long> occupied = new HashSet<>(sourceSize.width() * sourceSize.depth());
            for (int x = 0; x < sourceSize.width(); x++) {
                for (int z = 0; z < sourceSize.depth(); z++) {
                    BlockPoint local = new BlockPoint(x, z);
                    BlockPoint planned = geometry.worldPosition(anchor, local);
                    BlockPos runtime = worldPosition(local);
                    if (planned.x() != runtime.getX() || planned.z() != runtime.getZ()) {
                        return Validation.invalid("TEMPLATE_RUNTIME_TRANSFORM_MISMATCH",
                                "Minecraft and City map a source coordinate to different world coordinates.");
                    }
                    occupied.add(ChunkPos.asLong(runtime.getX(), runtime.getZ()));
                }
            }
            if (occupied.size() != sourceSize.width() * sourceSize.depth()) {
                return Validation.invalid("TEMPLATE_RUNTIME_TRANSFORM_MISMATCH",
                        "Minecraft maps multiple source coordinates to one world coordinate.");
            }
            return Validation.success();
        }
    }

    record Validation(boolean valid, String reasonCode, String message) {
        private static Validation success() {
            return new Validation(true, "TEMPLATE_RUNTIME_TRANSFORM_VALID", "");
        }

        private static Validation invalid(String reasonCode, String message) {
            return new Validation(false, reasonCode, message);
        }
    }
}
