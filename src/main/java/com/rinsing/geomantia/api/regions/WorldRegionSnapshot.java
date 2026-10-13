package com.rinsing.geomantia.api.regions;

import java.util.*;

/** Immutable geography for addons. No chunk reads; coordinates and centres are in blocks. */
public record WorldRegionSnapshot(String identity, String dimension, int step, String initialContinent,
                                  Map<Long, Cell> cells, Map<String, Centre> continents) {
    public record Cell(String region, boolean land, boolean ocean) {}
    public record Centre(double x, double z) {}
    public WorldRegionSnapshot { cells=Map.copyOf(cells); continents=Map.copyOf(continents); }
    public static long key(int x,int z) { return ((long)x<<32) ^ (z&0xffffffffL); }
    public Cell at(int x,int z) { return cells.get(key(Math.floorDiv(x,step),Math.floorDiv(z,step))); }
}
