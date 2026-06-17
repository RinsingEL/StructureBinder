package com.rinsing.geomantia.systems.city.domain.model;

public record BlockPoint(int x, int z) {
    public static final BlockPoint ORIGIN = new BlockPoint(0, 0);
}
