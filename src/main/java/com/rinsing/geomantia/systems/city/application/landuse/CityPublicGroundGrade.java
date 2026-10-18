package com.rinsing.geomantia.systems.city.application.landuse;

import com.rinsing.geomantia.systems.city.application.outdoor.CityPublicRealmPlanner;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import java.util.*;
import java.util.function.Function;

/** Whole-domain grading. Neither tile coordinates nor chunk ownership participate in the solution. */
final class CityPublicGroundGrade {
    static final int MAX_EARTHWORK = 6;
    private static final Comparator<BlockPoint> ORDER = Comparator.comparingInt(BlockPoint::z).thenComparingInt(BlockPoint::x);
    record Result(Map<BlockPoint,Integer> heights, Set<BlockPoint> conflicts) {}
    private record Entry(BlockPoint point, int height) {}

    static Result solve(Map<BlockPoint,Integer> preferred, Map<BlockPoint,Integer> fixed,
                        Function<BlockPoint,CityPublicRealmPlanner.Ground> ground) {
        Map<BlockPoint,Integer> lower = new HashMap<>(), upper = new HashMap<>();
        Map<BlockPoint,List<BlockPoint>> edges = new HashMap<>();
        for (var p : preferred.keySet()) {
            int natural = ground.apply(p).surfaceY();
            Integer pin = fixed.get(p);
            int lo = pin == null ? natural - MAX_EARTHWORK : pin;
            int hi = pin == null ? natural + MAX_EARTHWORK : pin;
            List<BlockPoint> connected = new ArrayList<>();
            for (var q : neighbors(p)) {
                if (preferred.containsKey(q)) {
                    // An existing ravine is not an invitation to terraform it into a lawn.
                    Integer other=fixed.get(q);
                    boolean terraceEdge=pin!=null&&other!=null&&Math.abs(pin-other)>1;
                    if (!terraceEdge&&(Math.abs(natural-ground.apply(q).surfaceY()) <= 2
                            || pin != null && Objects.equals(pin,other))) connected.add(q);
                } else if (pin == null) {
                    Integer adjacent = fixed.get(q);
                    var outside = ground.apply(q);
                    if (adjacent != null) {
                        // The first green column meets the actual paved edge, then slopes inward.
                        lo = Math.max(lo,adjacent); hi = Math.min(hi,adjacent);
                    } else if (outside.dryLand() && Math.abs(natural-outside.surfaceY()) <= 2) {
                        lo = Math.max(lo,outside.surfaceY()-1); hi = Math.min(hi,outside.surfaceY()+1);
                    }
                }
            }
            lower.put(p,lo); upper.put(p,hi); edges.put(p,connected);
        }
        var minimum = envelope(lower,edges,true);
        var maximum = envelope(upper,edges,false);
        Set<BlockPoint> conflicts = new HashSet<>();
        Map<BlockPoint,Integer> wanted = new HashMap<>();
        for (var p : preferred.keySet()) {
            if (minimum.get(p)>maximum.get(p)) conflicts.add(p);
            wanted.put(p,Math.max(minimum.get(p),Math.min(maximum.get(p),preferred.get(p))));
        }
        var result = envelope(wanted,edges,true);
        // Infeasible fixed interfaces are explicitly reported, never averaged into a new datum.
        for (var p : conflicts) result.put(p,fixed.getOrDefault(p,ground.apply(p).surfaceY()));
        return new Result(result,Set.copyOf(conflicts));
    }

    private static Map<BlockPoint,Integer> envelope(Map<BlockPoint,Integer> values,
            Map<BlockPoint,List<BlockPoint>> edges, boolean raise) {
        Map<BlockPoint,Integer> result = new HashMap<>(values);
        Comparator<Entry> order = Comparator.comparingInt(Entry::height);
        if (raise) order = order.reversed();
        PriorityQueue<Entry> queue = new PriorityQueue<>(order.thenComparing(Entry::point,ORDER));
        values.forEach((p,y)->queue.add(new Entry(p,y)));
        while (!queue.isEmpty()) {
            var entry = queue.remove();
            if (result.get(entry.point()) != entry.height()) continue;
            int next = entry.height() + (raise ? -1 : 1);
            for (var q : edges.get(entry.point())) {
                if (raise ? next > result.get(q) : next < result.get(q)) {
                    result.put(q,next); queue.add(new Entry(q,next));
                }
            }
        }
        return result;
    }

    static List<BlockPoint> neighbors(BlockPoint p) {
        return List.of(new BlockPoint(p.x()-1,p.z()),new BlockPoint(p.x()+1,p.z()),
                new BlockPoint(p.x(),p.z()-1),new BlockPoint(p.x(),p.z()+1));
    }
}
