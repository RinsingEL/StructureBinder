package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan;
import java.util.*;

/** Connects natural roads to frozen platforms using immutable terrain, within the compiler halo. */
final class CityRoadTransitionGrader {
    static final int RADIUS = 12;
    private static final int[][] DIRECTIONS = {{0,-1},{1,0},{0,1},{-1,0}};
    private record Cell(int x, int z) { Cell offset(int[] d) { return new Cell(x+d[0],z+d[1]); } }
    private CityRoadTransitionGrader() { }

    static List<CityLandUseChunkCompiler.FeatureOperation> grade(
            CityLandUseChunkCompiler.ChunkFragment fragment, CityLandUseMicroGrader.TerrainView terrain) {
        Map<Cell, CityLandUseChunkCompiler.FeatureOperation> roads = new HashMap<>();
        for (var f : fragment.gradingFeatureOperations()) if (road(f)) roads.put(new Cell(f.x(),f.z()),f);
        Map<Cell,Integer> anchors = new HashMap<>();
        for (var c : fragment.gradingMaskCells()) {
            Cell p = new Cell(c.x(),c.z());
            if (c.foundation() && c.targetY()!=null && roads.containsKey(p)) anchors.put(p,c.targetY());
        }
        for (var entry : roads.entrySet()) {
            var f=entry.getValue();
            // This is the actual block Y consumed by the executor, including existing stairs.
            if (f.targetSurfaceY()!=null) anchors.put(entry.getKey(), f.targetSurfaceY());
        }
        if (anchors.isEmpty()) return fragment.featureOperations();
        Map<Cell,Integer> lower = new HashMap<>(), upper = new HashMap<>();
        // Stop at other fixed columns; only transitions adjoining natural road need work.
        for (var anchor : anchors.entrySet()) {
            Map<Cell,Integer> distances = new HashMap<>();
            ArrayDeque<Cell> queue = new ArrayDeque<>();
            distances.put(anchor.getKey(),0); queue.add(anchor.getKey());
            while (!queue.isEmpty()) {
                Cell cell=queue.removeFirst(); int distance=distances.get(cell);
                if (distance==RADIUS) continue;
                for (int[] direction : DIRECTIONS) {
                    Cell next=cell.offset(direction);
                    if (!roads.containsKey(next) || anchors.containsKey(next) || distances.containsKey(next)) continue;
                    int d=distance+1; distances.put(next,d); queue.addLast(next);
                    lower.merge(next,anchor.getValue()-d,Math::max);
                    upper.merge(next,anchor.getValue()+d,Math::min);
                }
            }
        }
        Map<Cell,Integer> targets = new HashMap<>(anchors);
        for (var e : upper.entrySet()) {
            Cell cell=e.getKey(); int lo=lower.get(cell), hi=e.getValue();
            if (lo>hi) continue; // Conflicting pre-existing frozen grades are not silently moved.
            var sample=terrain.sample(cell.x(),cell.z());
            if (!sample.naturalSurface()) continue;
            targets.put(cell,Math.max(lo,Math.min(hi,sample.surfaceY())));
        }
        List<CityLandUseChunkCompiler.FeatureOperation> result=new ArrayList<>();
        for (var f : fragment.featureOperations()) {
            Cell cell=new Cell(f.x(),f.z()); Integer y=targets.get(cell);
            if (!road(f) || f.targetSurfaceY()!=null || y==null) { result.add(f); continue; }
            var kind=CityLandUseSurfacePrintPlan.FeatureKind.ROAD_SLAB;
            var facing=CityLandUseSurfacePrintPlan.HorizontalFacing.NONE;
            String block=f.blockId().endsWith("_stairs")
                    ? f.blockId().substring(0,f.blockId().length()-7)+"_slab" : f.blockId();
            int target=y;
            for(int i=0;i<DIRECTIONS.length;i++) {
                Integer neighbour=targets.get(cell.offset(DIRECTIONS[i]));
                if (neighbour!=null && neighbour==y+1 && block.endsWith("_slab")) {
                    kind=CityLandUseSurfacePrintPlan.FeatureKind.ROAD_STAIR;
                    block=block.substring(0,block.length()-5)+"_stairs";
                    facing= switch(i) {case 0->CityLandUseSurfacePrintPlan.HorizontalFacing.NORTH;
                        case 1->CityLandUseSurfacePrintPlan.HorizontalFacing.EAST;
                        case 2->CityLandUseSurfacePrintPlan.HorizontalFacing.SOUTH;
                        default->CityLandUseSurfacePrintPlan.HorizontalFacing.WEST;};
                    // Replace the road block at its grade; raising it again leaves a lip at the low end.
                    break;
                }
            }
            result.add(new CityLandUseChunkCompiler.FeatureOperation(f.sourceId(),f.x(),f.z(),block,
                    f.surfaceOffset(),kind,facing,target));
        }
        return List.copyOf(result);
    }

    private static boolean road(CityLandUseChunkCompiler.FeatureOperation f) {
        return f.surfaceOffset()==0 && (f.kind()==CityLandUseSurfacePrintPlan.FeatureKind.ROAD_SLAB
                || f.kind()==CityLandUseSurfacePrintPlan.FeatureKind.ROAD_STAIR);
    }
}
