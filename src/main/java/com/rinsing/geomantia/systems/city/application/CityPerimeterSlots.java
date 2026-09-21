package com.rinsing.geomantia.systems.city.application;

/** Complete square layers: add frontage slots as the perimeter grows, not just longer spokes. */
final class CityPerimeterSlots {
    record Slot(int x, int z, int ring) {}

    static Slot compact(int index) {
        int ring = 1;
        while (index >= 8 * ring) {
            index -= 8 * ring;
            ring++;
        }
        // Clockwise, starting at the midpoint of the east side.
        if (index <= ring) return new Slot(ring, index, ring);
        index -= ring;
        if (index <= 2 * ring) return new Slot(ring - index, ring, ring);
        index -= 2 * ring;
        if (index <= 2 * ring) return new Slot(-ring, ring - index, ring);
        index -= 2 * ring;
        if (index <= 2 * ring) return new Slot(-ring + index, -ring, ring);
        index -= 2 * ring;
        return new Slot(ring, -ring + index, ring);
    }

    static Slot courtyard(int index) {
        int ring = 1;
        while (index >= 8 * ring - 1) {
            index -= 8 * ring - 1;
            ring++;
        }
        // Keep the south-center approach open through every layer.
        int base = 4 * (ring - 1) * ring;
        for (int offset = 0; offset < 8 * ring; offset++) {
            Slot slot = compact(base + offset);
            if (slot.x() == 0 && slot.z() == ring) continue;
            if (index-- == 0) return slot;
        }
        throw new IllegalArgumentException("Invalid courtyard slot");
    }

    static Slot symmetricPair(int index) {
        int ring = 1;
        while (index >= 4 * ring) {
            index -= 4 * ring;
            ring++;
        }
        // Opposite pairs: cardinal midpoints first, then fill the sides before the next layer.
        if (index == 0) return new Slot(ring, 0, ring);
        if (index == 1) return new Slot(0, ring, ring);
        index -= 2;
        for (int offset = 1; offset < ring; offset++) {
            Slot[] sides = {new Slot(ring, offset, ring), new Slot(ring, -offset, ring),
                    new Slot(offset, ring, ring), new Slot(-offset, ring, ring)};
            if (index < sides.length) return sides[index];
            index -= sides.length;
        }
        return new Slot(ring, index == 0 ? ring : -ring, ring);
    }

    private CityPerimeterSlots() {}
}
