package com.rinsing.geomantia.systems.city.application;

import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

/** Positions complete local envelopes without assuming the layout origin is their center. */
final class CityArrayEnvelopePlacement {
    private CityArrayEnvelopePlacement() { }

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
