package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import java.util.function.IntFunction;

/** Conservative, single-column vegetation recognition; never loads adjacent chunks. */
final class NaturalTrunkColumn {
    enum Cell { TRUNK, SOIL, NATURAL_LEAVES, REPLACEABLE, OTHER }

    static boolean canClear(int topY, boolean protectedFootprint, IntFunction<Cell> sample) {
        if (protectedFootprint || sample.apply(topY) != Cell.TRUNK) return false;
        boolean rooted = false;
        for (int depth = 1; depth <= 64; depth++) {
            Cell cell = sample.apply(topY - depth);
            if (cell == Cell.TRUNK) continue;
            rooted = cell == Cell.SOIL;
            break;
        }
        if (!rooted) return false;
        for (int rise = 1; rise <= 8; rise++) {
            Cell cell = sample.apply(topY + rise);
            if (cell == Cell.NATURAL_LEAVES) return true;
            if (cell != Cell.REPLACEABLE) return false;
        }
        return false;
    }
}
