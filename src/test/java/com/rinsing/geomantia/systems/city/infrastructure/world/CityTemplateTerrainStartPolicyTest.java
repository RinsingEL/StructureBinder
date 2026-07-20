package com.rinsing.geomantia.systems.city.infrastructure.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityTemplateTerrainStartPolicyTest {
    @Test
    void executionUsesOnlyTheFrozenPolicyValue() {
        assertTrue(CityTemplateTerrainStartPolicy.usesStructureStart(
                "structure_start_beard_thin"));

        assertFalse(CityTemplateTerrainStartPolicy.usesStructureStart(
                "flat_or_small_step"));
        assertFalse(CityTemplateTerrainStartPolicy.usesStructureStart(
                "geomantia:city/stubbs/agriculture/windmill_01"));
        assertFalse(CityTemplateTerrainStartPolicy.usesStructureStart((String) null));
    }
}
