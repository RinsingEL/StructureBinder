package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

/** Stable facade rhythm in world coordinates: adjoining chunks never restart pillar spacing. */
final class CityRetainingFacadePlanner {
    private CityRetainingFacadePlanner() { }
    static String slot(String area, int x, int z, int y, int top, int bottom,
                       boolean facesX, boolean facesZ) {
        int height=top-bottom;
        int along=facesX?z:x;
        boolean column=height>=3 && (facesX && facesZ || Math.floorMod(along-area.hashCode(),7)==0);
        if(column)return "wallColumn";
        if(y==top-1)return "wallCap";
        if(height>=8 && Math.floorMod(top-1-y,6)==0)return "wallBand";
        return "retainingWall";
    }
}
