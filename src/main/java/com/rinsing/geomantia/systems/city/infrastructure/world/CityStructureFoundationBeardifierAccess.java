package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.rinsing.geomantia.systems.city.application.terrain.CityTerrainFoundationDensityComputer;

import java.util.List;

/** Supplies fixed structure-platform foundations to Minecraft's beardifier. */
public interface CityStructureFoundationBeardifierAccess {
    void geomantia$setFoundationPlatforms(
            List<? extends CityTerrainFoundationDensityComputer.FoundationPlatformView> foundationPlatforms);
}
