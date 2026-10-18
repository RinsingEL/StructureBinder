package com.rinsing.geomantia.systems.city.application.landuse;

import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import java.util.*;
import java.util.function.IntBinaryOperator;

/** Freezes a shared road grade around boundaries between different designed platforms.
 * Works on the complete road graph before chunk compilation, without theme or world access. */
public final class CityTerraceRoadGradePlanner {
    private static final int[][] DIRS={{0,-1},{1,0},{0,1},{-1,0}};
    private static final Comparator<BlockPoint> ORDER=Comparator.comparingInt(BlockPoint::z).thenComparingInt(BlockPoint::x);
    private record Station(BlockPoint point,int height) {}

    public List<CityLandUseSurfacePrintPlan.FeatureCell> plan(
            List<CityLandUseSurfacePrintPlan.FeatureCell> features,Map<BlockPoint,Integer> platforms,
            Map<BlockPoint,Integer> entrancePins,IntBinaryOperator ground) {
        return plan(features,platforms,entrancePins,ground,List.of());
    }
    public List<CityLandUseSurfacePrintPlan.FeatureCell> plan(
            List<CityLandUseSurfacePrintPlan.FeatureCell> features,Map<BlockPoint,Integer> platforms,
            Map<BlockPoint,Integer> entrancePins,IntBinaryOperator ground,List<LandUseSourceResolver.RoadBand> bands) {
        Map<String,LandUseSourceResolver.RoadBand> byId=new HashMap<>();
        bands.forEach(b->byId.put(b.streetBandId(),b));
        Map<BlockPoint,CityLandUseSurfacePrintPlan.FeatureCell> roads=new HashMap<>();
        for(var f:features)if(f.surfaceOffset()==0&&(f.kind()==CityLandUseSurfacePrintPlan.FeatureKind.ROAD_SLAB
                ||f.kind()==CityLandUseSurfacePrintPlan.FeatureKind.ROAD_STAIR)
                &&(!byId.containsKey(f.sourceId())||byId.get(f.sourceId()).bounds().contains(f.x(),f.z())))roads.put(new BlockPoint(f.x(),f.z()),f);
        Set<BlockPoint> seams=new HashSet<>();
        // Core-owned platforms may change datum even when all neighbouring cores
        // have the same height. Re-grade their connected streets in that case too.
        for (var entry : roads.entrySet()) {
            var f = entry.getValue();
            Integer platformY = platforms.get(entry.getKey());
            Integer oldY = f.targetSurfaceY() == null ? null : f.targetSurfaceY()
                    - (f.kind() == CityLandUseSurfacePrintPlan.FeatureKind.ROAD_STAIR ? 1 : 0);
            if (platformY != null && oldY != null && !platformY.equals(oldY)) seams.add(entry.getKey());
        }
        for(var p:roads.keySet())for(var q:neighbors(p))if(roads.containsKey(q)&&platforms.containsKey(p)
                &&platforms.containsKey(q)&&!platforms.get(p).equals(platforms.get(q))){seams.add(p);seams.add(q);}
        if(seams.isEmpty())return features;
        // Include each connected road component containing a seam. A band boundary or
        // arbitrary search radius is not a valid elevation pin at a nearby junction.
        Map<BlockPoint,Integer> distance=new HashMap<>();ArrayDeque<BlockPoint> queue=new ArrayDeque<>();
        for(var p:seams){distance.put(p,0);queue.add(p);}
        while(!queue.isEmpty()) {
            var p=queue.removeFirst();int d=distance.get(p);
            for(var q:neighbors(p))if(roads.containsKey(q)&&distance.putIfAbsent(q,d+1)==null)queue.addLast(q);
        }
        Map<BlockPoint,Integer> desired=new HashMap<>(),pins=new HashMap<>();
        for(var p:distance.keySet()) {
            var f=roads.get(p);int y=platforms.getOrDefault(p,f.targetSurfaceY()!=null
                    ?f.targetSurfaceY()-(f.kind()==CityLandUseSurfacePrintPlan.FeatureKind.ROAD_STAIR?1:0):ground.applyAsInt(p.x(),p.z()));
            desired.put(p,y);
            if(entrancePins.containsKey(p))pins.put(p,entrancePins.get(p));
            else if(neighbors(p).stream().filter(roads::containsKey).count()==1)pins.put(p,y);
        }
        // A carriageway cross-section is one elevation variable. At junctions the shared
        // pixels merge both sections, making a level landing rather than transverse stairs.
        Map<BlockPoint,BlockPoint> parents=new HashMap<>();desired.keySet().forEach(p->parents.put(p,p));
        for(var band:bands){boolean alongX=band.start().z()==band.end().z();Map<Integer,BlockPoint> sections=new HashMap<>();
            for(var p:desired.keySet().stream().filter(p->band.bounds().contains(p.x(),p.z())).sorted(ORDER).toList()){
                var first=sections.putIfAbsent(alongX?p.x():p.z(),p);if(first!=null)join(parents,first,p);}
        }
        Map<String,List<BlockPoint>> bySource=new TreeMap<>();
        desired.keySet().forEach(p->bySource.computeIfAbsent(roads.get(p).sourceId(),k->new ArrayList<>()).add(p));
        for(var entry:bySource.entrySet()) {
            var points=entry.getValue();var band=byId.get(entry.getKey());
            boolean alongX=band!=null?band.start().z()==band.end().z():
                    points.stream().mapToInt(BlockPoint::x).max().orElse(0)-points.stream().mapToInt(BlockPoint::x).min().orElse(0)
                    >points.stream().mapToInt(BlockPoint::z).max().orElse(0)-points.stream().mapToInt(BlockPoint::z).min().orElse(0);
            Map<Integer,BlockPoint> sections=new HashMap<>();
            for(var p:points.stream().sorted(ORDER).toList()) {
                var first=sections.putIfAbsent(alongX?p.x():p.z(),p);
                if(first!=null)join(parents,first,p);
            }
        }
        Map<BlockPoint,List<Integer>> sectionHeights=new HashMap<>();
        desired.forEach((p,y)->sectionHeights.computeIfAbsent(root(parents,p),k->new ArrayList<>()).add(y));
        Map<BlockPoint,Integer> sectionDesired=new HashMap<>(),sectionPins=new HashMap<>();
        sectionHeights.forEach((p,ys)->{ys.sort(Integer::compare);sectionDesired.put(p,ys.get(ys.size()/2));});
        pins.forEach((p,y)->{var r=root(parents,p);var previous=sectionPins.putIfAbsent(r,y);
            if(previous!=null&&!previous.equals(y))throw new IllegalArgumentException("CITY_ROAD_CROSS_SECTION_PIN_CONFLICT:"+p);});
        Map<BlockPoint,Set<BlockPoint>> graph=new HashMap<>();
        for(var p:desired.keySet())for(var q:neighbors(p))if(desired.containsKey(q)) {
            var a=root(parents,p);var b=root(parents,q);
            if(!a.equals(b))graph.computeIfAbsent(a,k->new HashSet<>()).add(b);
        }
        Map<BlockPoint,Integer> lower=new HashMap<>(),upper=new HashMap<>();
        sectionDesired.forEach((p,y)->{lower.put(p,y-12);upper.put(p,y+12);});
        sectionPins.forEach((p,y)->{lower.put(p,y);upper.put(p,y);});
        propagate(lower,true,graph);propagate(upper,false,graph);
        Map<BlockPoint,Integer> sectionGrade=new HashMap<>(),grade=new HashMap<>();
        for(var p:sectionDesired.keySet()) {
            int lo=lower.get(p),hi=upper.get(p);
            if(lo>hi)throw new IllegalArgumentException("Terrace road needs more transition space near "+p);
            sectionGrade.put(p,Math.max(lo,Math.min(hi,sectionDesired.get(p))));
        }
        propagate(sectionGrade,false,graph);
        desired.keySet().forEach(p->grade.put(p,sectionGrade.get(root(parents,p))));
        List<CityLandUseSurfacePrintPlan.FeatureCell> result=new ArrayList<>();
        for(var f:features) {
            var p=new BlockPoint(f.x(),f.z());Integer y=grade.get(p);
            if(y==null||f.surfaceOffset()!=0||!roads.containsKey(p)){
                var band=byId.get(f.sourceId());
                if(band!=null&&f.kind()==CityLandUseSurfacePrintPlan.FeatureKind.ROAD_STAIR&&f.surfaceOffset()==0) {
                    var b=band.bounds();Integer adjacent=grade.get(new BlockPoint(Math.max(b.minX(),Math.min(b.maxX(),p.x())),Math.max(b.minZ(),Math.min(b.maxZ(),p.z()))));
                    if(adjacent!=null){result.add(new CityLandUseSurfacePrintPlan.FeatureCell(f.sourceId(),f.x(),f.z(),f.blockId(),0,f.kind(),f.facing(),adjacent));continue;}
                }
                result.add(f);continue;
            }
            String slab=f.blockId().endsWith("_stairs")?f.blockId().substring(0,f.blockId().length()-7)+"_slab":f.blockId();
            String stair=slab.endsWith("_slab")?slab.substring(0,slab.length()-5)+"_stairs":"";
            var facing=CityLandUseSurfacePrintPlan.HorizontalFacing.NONE;int target=y;
            for(int i=0;i<DIRS.length;i++) {
                var q=new BlockPoint(p.x()+DIRS[i][0],p.z()+DIRS[i][1]);
                if(grade.getOrDefault(q,y)==y+1&&!stair.isEmpty()) {
                    facing=switch(i){case 0->CityLandUseSurfacePrintPlan.HorizontalFacing.NORTH;
                        case 1->CityLandUseSurfacePrintPlan.HorizontalFacing.EAST;
                        case 2->CityLandUseSurfacePrintPlan.HorizontalFacing.SOUTH;
                        default->CityLandUseSurfacePrintPlan.HorizontalFacing.WEST;};
                    target++;break;
                }
            }
            boolean stepped=facing!=CityLandUseSurfacePrintPlan.HorizontalFacing.NONE;
            result.add(new CityLandUseSurfacePrintPlan.FeatureCell(f.sourceId(),f.x(),f.z(),stepped?stair:slab,0,
                    stepped?CityLandUseSurfacePrintPlan.FeatureKind.ROAD_STAIR:CityLandUseSurfacePrintPlan.FeatureKind.ROAD_SLAB,facing,target));
        }
        return List.copyOf(result);
    }
    private static BlockPoint root(Map<BlockPoint,BlockPoint> parents,BlockPoint p) {
        var r=p;while(!parents.get(r).equals(r))r=parents.get(r);
        while(!p.equals(r)){var next=parents.put(p,r);p=next;}return r;
    }
    private static void join(Map<BlockPoint,BlockPoint> parents,BlockPoint a,BlockPoint b) {
        a=root(parents,a);b=root(parents,b);if(ORDER.compare(a,b)<0)parents.put(b,a);else parents.put(a,b);
    }
    private static void propagate(Map<BlockPoint,Integer> heights,boolean maximum,Map<BlockPoint,Set<BlockPoint>> graph) {
        Comparator<Station> order=Comparator.comparingInt(Station::height);
        if(maximum)order=order.reversed();
        PriorityQueue<Station> queue=new PriorityQueue<>(order.thenComparing(Station::point,ORDER));
        heights.forEach((p,y)->queue.add(new Station(p,y)));
        while(!queue.isEmpty()) {
            var s=queue.remove();if(heights.get(s.point())!=s.height())continue;
            for(var q:graph.getOrDefault(s.point(),Set.of()))if(heights.containsKey(q)) {
                int next=s.height()+(maximum?-1:1);
                if(maximum?next>heights.get(q):next<heights.get(q)){heights.put(q,next);queue.add(new Station(q,next));}
            }
        }
    }
    private static List<BlockPoint> neighbors(BlockPoint p) {
        return Arrays.stream(DIRS).map(d->new BlockPoint(p.x()+d[0],p.z()+d[1])).toList();
    }
}
