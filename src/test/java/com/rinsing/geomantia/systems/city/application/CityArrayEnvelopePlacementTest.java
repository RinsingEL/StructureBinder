package com.rinsing.geomantia.systems.city.application;
import com.rinsing.geomantia.systems.city.domain.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CityArrayEnvelopePlacementTest {
    @Test void longNorthSouthEnvelopesDoNotInflateEastWestSymmetricSpacing() {
        var parent = new BlockBounds(-21, -125, 22, 125);
        var east = new BlockBounds(500, -800, 692, 200);
        var west = new BlockBounds(-40, 900, 118, 1082);
        var shifts = CityArrayEnvelopePlacement.symmetricPair(parent.center(), east, west,
                new BlockPoint(1, 0), java.util.List.of(parent), 17);
        var a = CityArrayEnvelopePlacement.move(east, shifts.get(0));
        var b = CityArrayEnvelopePlacement.move(west, shifts.get(1));
        assertEquals(136, a.center().x());
        assertEquals(-136, b.center().x());
        assertEquals(17, a.minX() - parent.maxX() - 1);
        assertTrue(parent.minX() - b.maxX() - 1 >= 17);
        assertEquals(1001, a.heightBlocks());
        assertEquals(0, a.center().z());
        assertEquals(0, b.center().z());
    }

    @Test void laterPairsClearEarlierSiblingsAndRetainSymmetry() {
        var parent = new BlockBounds(-10, -10, 10, 10);
        var child = new BlockBounds(-20, -50, 20, 50);
        var occupied = new java.util.ArrayList<BlockBounds>(); occupied.add(parent);
        for (var direction : java.util.List.of(new BlockPoint(1, 0), new BlockPoint(0, 1),
                new BlockPoint(1, 1), new BlockPoint(1, 0))) {
            var shifts = CityArrayEnvelopePlacement.symmetricPair(parent.center(), child, child, direction, occupied, 8);
            var a = CityArrayEnvelopePlacement.move(child, shifts.get(0));
            var b = CityArrayEnvelopePlacement.move(child, shifts.get(1));
            assertEquals(-a.center().x(), b.center().x());
            assertEquals(-a.center().z(), b.center().z());
            for (var placed : java.util.List.of(a, b)) for (var other : occupied) {
                assertTrue(placed.maxX() + 8 < other.minX() || other.maxX() + 8 < placed.minX()
                        || placed.maxZ() + 8 < other.minZ() || other.maxZ() + 8 < placed.minZ());
            }
            assertFalse(a.overlaps(b)); occupied.add(a); occupied.add(b);
        }
    }
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
