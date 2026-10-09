package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CityFoundationSupportSettingsTest {
    @Test void fillReachesAuthoredFloorWithoutReplacingGroundOrFloor() {
        var settings = CityFoundationSupportSettings.defaults();
        var blocks = settings.column(64,69,-3,1,false);
        assertEquals(java.util.List.of(65,66,67,68),blocks.stream().map(CityFoundationSupportSettings.SupportBlock::y).toList());
        assertTrue(blocks.stream().noneMatch(CityFoundationSupportSettings.SupportBlock::blackstonePier));
        assertTrue(settings.column(68,69,0,0,true).isEmpty());
    }

    @Test void deepDeckUsesNegativeCoordinateGridPiersAndAlwaysSupportsCorner() {
        var settings = CityFoundationSupportSettings.defaults();
        assertEquals(1,settings.column(40,69,-3,-3,false).size());
        var pier = settings.column(40,69,-4,-8,false);
        assertEquals(28,pier.size());
        assertTrue(pier.subList(0,27).stream().allMatch(CityFoundationSupportSettings.SupportBlock::blackstonePier));
        assertFalse(pier.get(27).blackstonePier());
        assertEquals(pier,settings.column(40,69,-3,-3,true));
        assertEquals(16,settings.column(52,69,1,1,false).size());
    }
}
