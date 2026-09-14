package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import java.util.Arrays;

/** Constant-size surface admission. No terrain generation, search, or whole-parcel rollback. */
final class CityLandscapeSurfaceGate {
    static final int MAX_FILL_DEPTH = 3;
    private CityLandscapeSurfaceGate() { }

    static Integer target(CityLandUseChunkCompiler.GradingMaskCell mask,
                          CityLandUseMicroGrader.TerrainView terrain) {
        var center = terrain.sample(mask.x(),mask.z());
        if (!dryNatural(center) || mask.targetY()==null
                || Math.abs((long)center.surfaceY()-mask.targetY())>mask.maxTerrainDelta()) return null;
        int[] heights = new int[9];
        int count=0;
        for(int dz=-1;dz<=1;dz++) for(int dx=-1;dx<=1;dx++) {
            var sample=terrain.sample(mask.x()+dx,mask.z()+dz);
            // A missing neighbour is not permission to extend a field into unknown terrain.
            if(sample==null) return null;
            if(dryNatural(sample)) heights[count++]=sample.surfaceY();
        }
        Arrays.sort(heights,0,count);
        int median=heights[count/2];
        int target=center.surfaceY();
        boolean enclosed=count==9;
        for(int[] d:new int[][]{{0,-1},{1,0},{0,1},{-1,0}})
            enclosed &= terrain.sample(mask.x()+d[0],mask.z()+d[1]).surfaceY()==median;
        // Repair enclosed single-column pits only; do not grow a terrace from earlier writes.
        if(enclosed && median>target && median-target<=MAX_FILL_DEPTH) target=median;
        if(Math.abs((long)target-mask.targetY())>mask.maxTerrainDelta()) return null;
        for(int[] d:new int[][]{{0,-1},{1,0},{0,1},{-1,0}}) {
            var sample=terrain.sample(mask.x()+d[0],mask.z()+d[1]);
            if(dryNatural(sample) && Math.abs((long)target-sample.surfaceY())>MAX_FILL_DEPTH) return null;
        }
        return target;
    }

    private static boolean dryNatural(CityLandUseChunkExecutor.ColumnSample column) {
        return column!=null && (column.naturalSurface() || "minecraft:farmland".equals(column.surfaceBlockId()))
                && !"minecraft:water".equals(column.surfaceBlockId())
                && !"minecraft:lava".equals(column.surfaceBlockId());
    }
}
