package com.rinsing.geomantia.systems.city.infrastructure.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityTemplateTerrainStartPolicyTest {
    @Test
    void onlyTheThreeTerrainExperimentTemplatesUseStructureStarts() {
        assertTrue(CityTemplateTerrainStartPolicy.usesStructureStart(
                "geomantia:city/stubbs/agriculture/windmill_01"));
        assertTrue(CityTemplateTerrainStartPolicy.usesStructureStart(
                "geomantia:city/stubbs/agriculture/barn_windmill_01"));
        assertTrue(CityTemplateTerrainStartPolicy.usesStructureStart(
                "geomantia:city/stubbs/commercial/small_butcher_shop_01"));

        assertFalse(CityTemplateTerrainStartPolicy.usesStructureStart(
                "geomantia:city/stubbs/agriculture/farmhouse_01"));
        assertFalse(CityTemplateTerrainStartPolicy.usesStructureStart(
                "minecraft:village/plains/houses/plains_small_house_1"));
        assertFalse(CityTemplateTerrainStartPolicy.usesStructureStart((String) null));
    }
}
