package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationTerrainRunCompiler;

import java.util.List;

public interface CityDecorationBeardifierAccess {
    void geomantia$setFoundationSegments(
            List<CityDecorationTerrainRunCompiler.FoundationSegment> foundationSegments);
}
