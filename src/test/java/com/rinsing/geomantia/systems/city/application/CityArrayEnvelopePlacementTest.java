package com.rinsing.geomantia.systems.city.application;
import com.rinsing.geomantia.systems.city.domain.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CityArrayEnvelopePlacementTest {
    @Test void nonCentralOriginsRetainRectangularDimensionsAndClearanceOnEverySide() {
        var parent = new BlockBounds(-80, 20, -11, 60);
        var child = new BlockBounds(-9, -40, 20, -26);
        for (String side : new String[]{"east", "west", "north", "south"}) {
            var shift = CityArrayEnvelopePlacement.beside(parent, child, side, 7);
            var placed = CityArrayEnvelopePlacement.move(child, shift);
            assertEquals(30, placed.widthBlocks()); assertEquals(15, placed.heightBlocks()); assertFalse(placed.overlaps(parent));
            int gap = switch(side) {case "east" -> placed.minX()-parent.maxX()-1; case "west" -> parent.minX()-placed.maxX()-1;
                case "north" -> parent.minZ()-placed.maxZ()-1; default -> placed.minZ()-parent.maxZ()-1;};
            assertEquals(7, gap);
        }
    }
}
