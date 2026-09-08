package com.rinsing.geomantia.systems.city.application.landuse;

import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import java.util.*;

/** Freeze local building-led terraces before owner chunks are sliced. Connectivity does not imply equal height. */
public final class CityFoundationElevationPlanner {
    private CityFoundationElevationPlanner() {}

    public static List<CityLandUseSurfacePrintPlan.PlatformSpan> plan(
            List<LandUseAreaPlan.ScanlineSpan> spans, LandUseTerrainField terrain) {
        return plan(spans, terrain, List.of());
    }

    public static List<CityLandUseSurfacePrintPlan.PlatformSpan> plan(
            List<LandUseAreaPlan.ScanlineSpan> spans, LandUseTerrainField terrain,
            List<com.rinsing.geomantia.systems.city.domain.model.BlockBounds> buildings) {
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
            Set<BlockPoint> sampledCells = new HashSet<>();
            for (BlockPoint point : component) sampledCells.add(new BlockPoint(Math.floorDiv(point.x(),step),Math.floorDiv(point.z(),step)));
            Map<BlockPoint,Integer> localTargets = new HashMap<>();
            for (BlockPoint point : component) {
                var nearest = buildings.stream().min(Comparator.comparingLong(b -> {
                    long dx = Math.max(0, Math.max(b.minX()-point.x(), point.x()-b.maxX()));
                    long dz = Math.max(0, Math.max(b.minZ()-point.z(), point.z()-b.maxZ()));
                    return dx*dx+dz*dz;
                })).orElse(null);
                BlockPoint local = nearest == null
                        ? new BlockPoint(Math.floorDiv(point.x(),step),Math.floorDiv(point.z(),step))
                        : new BlockPoint(Math.floorDiv(Math.floorDiv(nearest.minX()+nearest.maxX(),2),step),
                            Math.floorDiv(Math.floorDiv(nearest.minZ()+nearest.maxZ(),2),step));
                heights.put(point, localTargets.computeIfAbsent(local, key -> {
                    Map<Integer,Integer> localCounts = new TreeMap<>();
                    int radius = Math.max(1, 24 / step);
                    for (int dz=-radius;dz<=radius;dz++) for(int dx=-radius;dx<=radius;dx++) {
                        BlockPoint cellKey = new BlockPoint(key.x()+dx,key.z()+dz);
                        if (nearest == null && !sampledCells.contains(cellKey)) continue;
                        var sample = samples.get(cellKey);
                        if(sample != null && sample.sampled() && !sample.water()) {
                            int y = Math.floorDiv((int)Math.round(sample.elevation())+2,4)*4;
                            localCounts.merge(y,1,Integer::sum);
                        }
                    }
                    return localCounts.entrySet().stream().sorted(Comparator
                            .<Map.Entry<Integer,Integer>>comparingInt(Map.Entry::getValue).reversed()
                            .thenComparingInt(Map.Entry::getKey)).map(Map.Entry::getKey).findFirst().orElse(target);
                }));
            }
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
