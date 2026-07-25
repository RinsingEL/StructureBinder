package com.rinsing.geomantia.systems.city.application.landuse;

import com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseRuleCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LandUseRuleCatalogTest {
    @Test
    void semanticOverlapUsesMostSpecificTokenIndependentOfInputOrder() {
        LandUseRuleCatalog catalog = LandUseRuleCatalog.defaults();

        assertEquals("plaza", catalog.resolveSemantic(List.of("function.market", "role.market_center"))
                .orElseThrow().ruleRef());
        assertEquals("plaza", catalog.resolveSemantic(List.of("role.market_center", "function.market"))
                .orElseThrow().ruleRef());
    }

    @Test
    void commonTerraSenseTermsMapToDefaultProfiles() {
        LandUseRuleCatalog catalog = LandUseRuleCatalog.defaults();

        assertEquals("civic", catalog.resolveSemantic(List.of("function.landmark", "usage.public_core"))
                .orElseThrow().ruleRef());
        assertEquals("residential", catalog.resolveSemantic(List.of("function.residence"))
                .orElseThrow().ruleRef());
        assertEquals("general_settlement", catalog.resolveSemantic(List.of("usage.filler"))
                .orElseThrow().ruleRef());
        assertEquals("military", catalog.resolveSemantic(List.of("function.guard_tower"))
                .orElseThrow().ruleRef());
    }
}
