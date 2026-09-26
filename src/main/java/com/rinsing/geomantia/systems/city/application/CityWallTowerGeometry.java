package com.rinsing.geomantia.systems.city.application;

import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import java.util.Locale;

/** Authored 7x10 tower: front +Z, wall connector at local (3,6). */
public final class CityWallTowerGeometry {
    private CityWallTowerGeometry() {}

    public static String facing(String side) {
        String value = side.toUpperCase(Locale.ROOT);
        if (!java.util.Set.of("NORTH", "EAST", "SOUTH", "WEST").contains(value))
            throw new IllegalArgumentException("WALL_TOWER_FACING_REQUIRED:" + side);
        return value;
    }

    public static BlockPoint offset(int x, int z, String facing) {
        int dx = x - 3, dz = z - 6;
        return switch (facing(facing)) {
            case "NORTH" -> new BlockPoint(-dx, -dz);
            case "EAST" -> new BlockPoint(dz, -dx);
            case "WEST" -> new BlockPoint(-dz, dx);
            default -> new BlockPoint(dx, dz);
        };
    }

    public static BlockBounds bounds(BlockPoint anchor, String facing) {
        BlockPoint first = offset(0, 0, facing), last = offset(6, 9, facing);
        return new BlockBounds(anchor.x() + Math.min(first.x(), last.x()),
                anchor.z() + Math.min(first.z(), last.z()),
                anchor.x() + Math.max(first.x(), last.x()),
                anchor.z() + Math.max(first.z(), last.z()));
    }
}
