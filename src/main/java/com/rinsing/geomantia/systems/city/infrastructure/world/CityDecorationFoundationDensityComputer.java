package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.rinsing.geomantia.systems.city.application.terrain.CityTerrainFoundationDensityComputer;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationTerrainRunCompiler;

import java.util.List;

/** Positive-only density contribution for frozen Decoration fill-only foundation segments. */
public final class CityDecorationFoundationDensityComputer {
    private CityDecorationFoundationDensityComputer() {
    }

    public static double compute(int x, int y, int z,
                                 List<CityDecorationTerrainRunCompiler.FoundationSegment> segments) {
        return CityTerrainFoundationDensityComputer.computeViews(x, y, z, segments);
    }
}
