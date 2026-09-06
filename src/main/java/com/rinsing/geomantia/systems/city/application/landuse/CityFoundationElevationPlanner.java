package com.rinsing.geomantia.systems.city.application.landuse;

import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import java.util.*;

/** Freeze one elevation per complete platform before owner chunks are sliced. */
public final class CityFoundationElevationPlanner {
    private CityFoundationElevationPlanner() {}

    public static List<CityLandUseSurfacePrintPlan.PlatformSpan> plan(
            List<LandUseAreaPlan.ScanlineSpan> spans, LandUseTerrainField terrain) {
        Map<BlockPoint, LandUseTerrainField.Cell> samples = new HashMap<>();
        int step = terrain.cellStepBlocks();
        for (var cell : terrain.cells()) samples.put(new BlockPoint(Math.floorDiv(cell.blockMinX(),step),
                Math.floorDiv(cell.blockMinZ(),step)), cell);
        Set<BlockPoint> remaining = new HashSet<>();
        for (var span : spans) for (int x = span.minX(); x <= span.maxX(); x++)
            remaining.add(new BlockPoint(x,span.z()));
        Map<BlockPoint,Integer> heights = new HashMap<>();
        while (!remaining.isEmpty()) {
            BlockPoint seed = remaining.iterator().next();
            remaining.remove(seed);
            List<BlockPoint> component = new ArrayList<>();
            ArrayDeque<BlockPoint> queue = new ArrayDeque<>();
            Map<Integer,Integer> counts = new TreeMap<>();
            queue.add(seed);
            while (!queue.isEmpty()) {
                BlockPoint point = queue.removeFirst(); component.add(point);
                var sample = samples.get(new BlockPoint(Math.floorDiv(point.x(),step),Math.floorDiv(point.z(),step)));
                if (sample != null && sample.sampled() && !sample.water()) {
                    int y = Math.floorDiv((int)Math.round(sample.elevation())+2,4)*4;
                    counts.merge(y,1,Integer::sum);
                }
                for (BlockPoint next : List.of(new BlockPoint(point.x()-1,point.z()),
                        new BlockPoint(point.x()+1,point.z()),new BlockPoint(point.x(),point.z()-1),
                        new BlockPoint(point.x(),point.z()+1))) if (remaining.remove(next)) queue.addLast(next);
            }
            if (counts.isEmpty()) throw new IllegalArgumentException("CITY_FOUNDATION_ELEVATION_UNAVAILABLE");
            int target = counts.entrySet().stream().sorted(Comparator
                    .<Map.Entry<Integer,Integer>>comparingInt(Map.Entry::getValue).reversed()
                    .thenComparingInt(Map.Entry::getKey)).findFirst().orElseThrow().getKey();
            for (BlockPoint point : component) heights.put(point,target);
        }
        List<CityLandUseSurfacePrintPlan.PlatformSpan> result = new ArrayList<>();
        List<BlockPoint> ordered = heights.keySet().stream().sorted(Comparator.comparingInt(BlockPoint::z)
                .thenComparingInt(BlockPoint::x)).toList();
        for (int i=0; i<ordered.size();) {
            BlockPoint first=ordered.get(i++); int last=first.x(), y=heights.get(first);
            while(i<ordered.size() && ordered.get(i).z()==first.z() && ordered.get(i).x()==last+1
                    && heights.get(ordered.get(i))==y) last=ordered.get(i++).x();
            result.add(new CityLandUseSurfacePrintPlan.PlatformSpan(first.z(),first.x(),last,y));
        }
        return List.copyOf(result);
    }
}
