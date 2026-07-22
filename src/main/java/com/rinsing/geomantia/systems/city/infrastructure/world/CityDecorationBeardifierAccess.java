package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.rinsing.geomantia.systems.city.application.terrain.CityTerrainFoundationDensityComputer;

import java.util.List;

public interface CityDecorationBeardifierAccess {
    void geomantia$setFoundationSegments(
            List<? extends CityTerrainFoundationDensityComputer.FoundationSegmentView> foundationSegments);
}
