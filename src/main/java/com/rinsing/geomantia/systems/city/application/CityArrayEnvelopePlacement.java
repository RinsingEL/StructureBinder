package com.rinsing.geomantia.systems.city.application;

import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import java.util.List;

/** Positions complete local envelopes without assuming the layout origin is their center. */
final class CityArrayEnvelopePlacement {
    private CityArrayEnvelopePlacement() { }

    /** Move a complete pair together; perpendicular length must not inflate its separation. */
    static List<BlockPoint> symmetricPair(BlockPoint center, BlockBounds first, BlockBounds second,
                                          BlockPoint direction, List<BlockBounds> occupied, int gap) {
        double scale = Math.max(Math.abs(direction.x()), Math.abs(direction.z()));
        if (scale == 0 || gap < 0) throw new IllegalArgumentException("Invalid symmetric pair direction/gap");
        double dx = direction.x() / scale, dz = direction.z() / scale;
        int reach = 0;
        for (BlockBounds box : occupied) {
            reach = Math.max(reach, Math.max(Math.max(Math.abs(box.minX() - center.x()), Math.abs(box.maxX() - center.x())),
                    Math.max(Math.abs(box.minZ() - center.z()), Math.abs(box.maxZ() - center.z()))));
        }
        int limit = reach + Math.max(Math.max(first.widthBlocks(), first.heightBlocks()),
                Math.max(second.widthBlocks(), second.heightBlocks())) + gap + 2;
        for (int radius = 1; radius <= limit; radius++) {
            int x = (int) Math.round(dx * radius), z = (int) Math.round(dz * radius);
            BlockPoint a = new BlockPoint(center.x() + x - first.center().x(), center.z() + z - first.center().z());
            BlockPoint b = new BlockPoint(center.x() - x - second.center().x(), center.z() - z - second.center().z());
            BlockBounds placedA = move(first, a), placedB = move(second, b);
            if (separated(placedA, placedB, gap)
                    && occupied.stream().allMatch(box -> separated(placedA, box, gap) && separated(placedB, box, gap))) {
                return List.of(a, b);
            }
        }
        throw new IllegalStateException("Could not separate symmetric envelopes within geometric bound");
    }

    private static boolean separated(BlockBounds a, BlockBounds b, int gap) {
        return (long) a.maxX() + gap < b.minX() || (long) b.maxX() + gap < a.minX()
                || (long) a.maxZ() + gap < b.minZ() || (long) b.maxZ() + gap < a.minZ();
    }

    static BlockPoint beside(BlockBounds parent, BlockBounds childLocal, String direction, int gap) {
        int x = parent.center().x() - childLocal.center().x();
        int z = parent.center().z() - childLocal.center().z();
        String side = direction.toLowerCase(java.util.Locale.ROOT);
        if (side.contains("east")) x = parent.maxX() + gap + 1 - childLocal.minX();
        else if (side.contains("west")) x = parent.minX() - gap - 1 - childLocal.maxX();
        if (side.contains("south")) z = parent.maxZ() + gap + 1 - childLocal.minZ();
        else if (side.contains("north")) z = parent.minZ() - gap - 1 - childLocal.maxZ();
        return new BlockPoint(x, z);
    }

    static BlockBounds move(BlockBounds bounds, BlockPoint origin) {
        return new BlockBounds(bounds.minX() + origin.x(), bounds.minZ() + origin.z(),
                bounds.maxX() + origin.x(), bounds.maxZ() + origin.z());
    }
}
