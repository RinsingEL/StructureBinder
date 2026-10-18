package com.rinsing.geomantia.systems.city.application.landuse;

import com.rinsing.geomantia.systems.city.algorithm.landuse.CityDistrictPlanner;
import com.rinsing.geomantia.systems.city.domain.landuse.*;
import com.rinsing.geomantia.systems.city.domain.model.*;
import java.util.*;

/** Whole-city planning before chunk slicing. Small dry gaps share the actual street datum. */
public final class CitySharedTerracePlanner {
    public static final String SOURCE = "shared_terrace::";
    private static final int[][] DIRS = {{1,0},{-1,0},{0,1},{0,-1}};
    private static final Comparator<BlockPoint> ORDER = Comparator.comparingInt(BlockPoint::z).thenComparingInt(BlockPoint::x);
    public record Settings(int maximumGap, int maximumEarthwork, int minimumArea) {
        public Settings {
            if (maximumGap < 1 || maximumGap > 64 || maximumEarthwork < 0 || maximumEarthwork > 16 || minimumArea < 1)
                throw new IllegalArgumentException("CITY_SHARED_TERRACE_SETTINGS_INVALID");
        }
        public static Settings defaults() { return new Settings(32,6,60); }
    }
    private record Reach(String owner, int distance) { }
    private record RoadDatum(BlockPoint road, int y, int distance) { }

    public CityLandUseSurfacePrintPlan apply(CityLandUseSurfacePrintPlan source, CityDistrictPlanner.Result district,
            LandUseTerrainField terrain, LandUseAreaPlan landUse, List<LandUseAreaPlan.CorridorExclusion> reservations,
            Settings settings) {
        return apply(source,district,terrain,landUse,reservations,settings,List.of());
    }
    public CityLandUseSurfacePrintPlan apply(CityLandUseSurfacePrintPlan source, CityDistrictPlanner.Result district,
            LandUseTerrainField terrain, LandUseAreaPlan landUse, List<LandUseAreaPlan.CorridorExclusion> reservations,
            Settings settings,List<LandUseSourceResolver.RoadBand> bands) {
        if (district == null || district.cores().isEmpty()) return source;
        Terrain samples = new Terrain(terrain);
        Map<String,Integer> datums = new TreeMap<>();
        district.cores().forEach((id,core) -> datums.put(id,CityFoundationElevationPlanner.planForCore(
                List.of(new LandUseAreaPlan.ScanlineSpan(core.center().z(),core.center().x(),core.center().x())),terrain,core).get(0).targetY()));
        Map<BlockPoint,String> owners=platformOwners(source,district,datums.keySet());
        Map<BlockPoint,Integer> platforms = new HashMap<>();
        List<CityLandUseSurfacePrintPlan.AreaPrint> areas = new ArrayList<>();
        for (var area : source.areas()) {
            if (!(area.recipe() instanceof CityLandUseSurfacePrintPlan.UniformRecipe recipe)
                    || recipe.platformSpans().isEmpty()) { areas.add(area); continue; }
            Map<BlockPoint,Integer> local = new HashMap<>();
            for (var span : recipe.platformSpans()) for (int x=span.minX();x<=span.maxX();x++) {
                BlockPoint p = new BlockPoint(x,span.z());
                String owner = owners.get(p);
                int y = owner == null ? span.targetY() : datums.get(owner);
                local.put(p,y); platforms.put(p,y);
            }
            areas.add(new CityLandUseSurfacePrintPlan.AreaPrint(area.printAreaId(),area.landUseAreaId(),area.sourceGroupIds(),
                    area.surfaceSettings(),area.memberSpans(),area.exclusionSpans(),area.surfaceAlgorithm(),area.algorithmAnchor(),
                    new CityLandUseSurfacePrintPlan.UniformRecipe(recipe.surfaceBlockId(),recipe.boundaryBlockId(),spans(local)),area.terrainReferenceCells()));
        }
        Map<BlockPoint,CityLandUseSurfacePrintPlan.FeatureCell> roads = new HashMap<>();
        for (var f : source.featureCells()) if (circulation(f)) roads.put(new BlockPoint(f.x(),f.z()),f);
        Map<BlockPoint,Integer> entrancePins = new HashMap<>();
        for(var a:landUse.areas())for(var gate:a.gateSlots())for(var p:roads.keySet())
            if(Math.abs(p.x()-gate.block().x())+Math.abs(p.z()-gate.block().z())<=2 && platforms.containsKey(p)) entrancePins.put(p,platforms.get(p));
        var graded = new CityTerraceRoadGradePlanner().plan(source.featureCells(),platforms,entrancePins,samples::height,bands);
        // Re-read the frozen grade, including internal streets absent from coarse roadBands.
        roads.clear();
        for (var f : graded) if (circulation(f)) roads.put(new BlockPoint(f.x(),f.z()),f);
        Set<BlockPoint> blocked = new HashSet<>(district.structures());
        for (var area : landUse.areas()) if (!area.sourceGroupIds().stream().anyMatch(id -> id.endsWith("::foundation")))
            for (var s : area.memberSpans()) for (int x=s.minX();x<=s.maxX();x++) blocked.add(new BlockPoint(x,s.z()));
        for (var corridor : reservations) addBounds(blocked,corridor.blockBounds());
        for (var corridor : landUse.corridorExclusions()) addBounds(blocked,corridor.blockBounds());
        // Road reservations protect circulation, but must not prevent the dry adjacent shoulder.
        blocked.removeAll(roads.keySet());
        for (var f : graded) if (f.surfaceOffset()>0 && f.kind()!=CityLandUseSurfacePrintPlan.FeatureKind.GREEN_PLANT)
            for (int dz=-2;dz<=2;dz++) for(int dx=-2;dx<=2;dx++) blocked.add(new BlockPoint(f.x()+dx,f.z()+dz));
        Set<BlockPoint> eligible = new HashSet<>();
        for (var p : district.envelope()) if (!platforms.containsKey(p) && !blocked.contains(p) && !roads.containsKey(p)
                && samples.safe(p)) eligible.add(p);
        Map<BlockPoint,List<Reach>> reaches = new HashMap<>();
        for (var entry : new TreeMap<>(district.groups()).entrySet()) {
            if (!datums.containsKey(entry.getKey())) continue;
            ArrayDeque<BlockPoint> queue = new ArrayDeque<>(); Map<BlockPoint,Integer> distance = new HashMap<>();
            for (var p : entry.getValue().stream().sorted(ORDER).toList()) {
                distance.put(p,0); queue.add(p);
            }
            while(!queue.isEmpty()) {
                var p=queue.removeFirst(); int d=distance.get(p);
                if(d>0) reaches.computeIfAbsent(p,k->new ArrayList<>()).add(new Reach(entry.getKey(),d));
                if(d>=settings.maximumGap()) continue;
                for(var q:neighbors(p)) if ((eligible.contains(q)||roads.containsKey(q)) && !distance.containsKey(q)
                        && samples.safe(q) && Math.abs(samples.height(q.x(),q.z())-datums.get(entry.getKey()))<=settings.maximumEarthwork()) {
                    distance.put(q,d+1);queue.add(q);
                }
            }
        }
        Set<BlockPoint> candidates = new HashSet<>();
        reaches.forEach((p,list)->{ list.sort(Comparator.comparingInt(Reach::distance).thenComparing(Reach::owner));
            if(eligible.contains(p) && list.size()>1 && list.get(0).distance()+list.get(1).distance()<=settings.maximumGap()) candidates.add(p); });
        retainComponents(candidates,settings.minimumArea());
        Set<BlockPoint> traversable = new HashSet<>(candidates); traversable.addAll(platforms.keySet()); traversable.addAll(roads.keySet());
        Map<BlockPoint,RoadDatum> nearest = new HashMap<>(); ArrayDeque<BlockPoint> queue = new ArrayDeque<>();
        for(var p:roads.keySet().stream().sorted(ORDER).toList()) {
            var f=roads.get(p); int y=f.targetSurfaceY()==null?platforms.getOrDefault(p,samples.height(p.x(),p.z())):
                    f.targetSurfaceY()-(f.kind()==CityLandUseSurfacePrintPlan.FeatureKind.ROAD_STAIR?1:0);
            nearest.put(p,new RoadDatum(p,y,0)); queue.add(p);
        }
        while(!queue.isEmpty()) {
            var p=queue.removeFirst();var d=nearest.get(p);if(d.distance()>=settings.maximumGap())continue;
            for(var q:neighbors(p))if(traversable.contains(q)&&!nearest.containsKey(q)) {
                nearest.put(q,new RoadDatum(d.road(),d.y(),d.distance()+1));queue.add(q);
            }
        }
        Map<BlockPoint,Integer> targets = new HashMap<>();
        for(var p:candidates.stream().sorted(ORDER).toList()) {
            var d=nearest.get(p);if(d!=null&&Math.abs(samples.height(p.x(),p.z())-d.y())<=settings.maximumEarthwork()) targets.put(p,d.y());
        }
        // Match the width on the opposite side of the same street cross-section.
        for(var p:new ArrayList<>(targets.keySet()).stream().sorted(ORDER).toList()) {
            var d=nearest.get(p);var r=d.road();
            int runX=run(roads,r,1,0),runZ=run(roads,r,0,1);
            for(boolean horizontal: runX==runZ?List.of(true,false):List.of(runX>runZ)) {
                if(horizontal?p.x()!=r.x():p.z()!=r.z())continue;
                int nx=horizontal?0:1,nz=horizontal?1:0,lo=0,hi=0;
                while(lo>-16&&roads.containsKey(offset(r,nx* (lo-1),nz*(lo-1))))lo--;
                while(hi<16&&roads.containsKey(offset(r,nx*(hi+1),nz*(hi+1))))hi++;
                int position=horizontal?p.z()-r.z():p.x()-r.x();
                int sign=position<lo?1:position>hi?-1:0;
                int width=position<lo?lo-position:position-hi;
                for(int i=1;sign!=0&&i<=Math.min(width,settings.maximumGap());i++) {
                    var q=offset(r,nx*(sign>0?hi+i:lo-i),nz*(sign>0?hi+i:lo-i));
                    if(platforms.containsKey(q)||blocked.contains(q)||roads.containsKey(q)||!district.envelope().contains(q)
                            ||!samples.safe(q)||Math.abs(samples.height(q.x(),q.z())-d.y())>settings.maximumEarthwork())break;
                    targets.putIfAbsent(q,d.y());
                }
            }
        }
        List<CityLandUseSurfacePrintPlan.FeatureCell> features=new ArrayList<>(graded);
        Set<BlockPoint> featureGround = new HashSet<>();
        for(var f:graded) if(f.surfaceOffset()==0)featureGround.add(new BlockPoint(f.x(),f.z()));
        targets.keySet().removeAll(featureGround);
        for(var p:targets.keySet().stream().sorted(ORDER).toList()) features.add(new CityLandUseSurfacePrintPlan.FeatureCell(
                SOURCE+source.cityId(),p.x(),p.z(),source.materialField().at("ground",p.x(),p.z(),"minecraft:smooth_stone"),0,
                CityLandUseSurfacePrintPlan.FeatureKind.TERRACE_GROUND,CityLandUseSurfacePrintPlan.HorizontalFacing.NONE,targets.get(p)));
        Map<BlockPoint,Integer> shared=new HashMap<>(platforms);shared.putAll(targets);
        Map<BlockPoint,Integer> surrounding=new HashMap<>();
        for(var p:shared.keySet())for(var q:neighbors(p))if(!shared.containsKey(q))surrounding.put(q,samples.height(q.x(),q.z()));
        var terraceFeatures=new com.rinsing.geomantia.systems.city.application.outdoor.CityTerraceFeaturePlanner().plan(
                SOURCE+source.cityId(),shared,shared,surrounding,landUse.areas().stream().flatMap(a->a.structureFootprintExclusions().stream()).distinct().toList(),
                spans(roads.keySet().stream().collect(java.util.stream.Collectors.toMap(p->p,p->0))).stream()
                        .map(s->new BlockBounds(s.minX(),s.z(),s.maxX(),s.z())).toList(),
                new com.rinsing.geomantia.systems.city.application.outdoor.CityTerraceFeaturePlanner.Settings(
                        "minecraft:stone_brick_wall","minecraft:stone_bricks","minecraft:stone_brick_slab",9,1,0,24,null,null));
        for(var b:terraceFeatures.blocks()) {
            var p=new BlockPoint(b.x(),b.z());int datum=shared.get(p),dy=b.y()-datum;
            String slot=b.kind().equals("EDGE_RAIL")?"railing":b.kind().equals("EDGE_CAP")?"wallCap":"wallColumn";
            features.add(new CityLandUseSurfacePrintPlan.FeatureCell(SOURCE+source.cityId(),b.x(),b.z(),source.materialField().at(slot,b.x(),b.z(),b.blockId()),dy,
                    CityLandUseSurfacePrintPlan.FeatureKind.TERRACE_RAIL,CityLandUseSurfacePrintPlan.HorizontalFacing.NONE,datum));
        }
        return new CityLandUseSurfacePrintPlanCodec().withComputedHash(new CityLandUseSurfacePrintPlan(source.schema(),source.cityId(),
                source.sourceLandUsePlanHash(),"",areas,source.sharedBoundarySpans(),features,source.materialField()));
    }
    private static boolean circulation(CityLandUseSurfacePrintPlan.FeatureCell feature) {
        return feature.surfaceOffset()==0 && (feature.kind()==CityLandUseSurfacePrintPlan.FeatureKind.ROAD_SLAB
                || feature.kind()==CityLandUseSurfacePrintPlan.FeatureKind.ROAD_STAIR
                || feature.kind()==CityLandUseSurfacePrintPlan.FeatureKind.BRIDGE_DECK);
    }
    private static Map<BlockPoint,String> platformOwners(CityLandUseSurfacePrintPlan source,
            CityDistrictPlanner.Result district,Set<String> cores) {
        Set<BlockPoint> domain=new HashSet<>();
        for(var area:source.areas())if(area.recipe() instanceof CityLandUseSurfacePrintPlan.UniformRecipe u)
            for(var span:u.platformSpans())for(int x=span.minX();x<=span.maxX();x++)domain.add(new BlockPoint(x,span.z()));
        Map<BlockPoint,String> owners=new HashMap<>();ArrayDeque<BlockPoint> queue=new ArrayDeque<>();
        // Road shoulders and the gap-closing pass extend construction beyond group masks. They
        // are still part of that actual pad, not independent building-led platforms. Multi-source
        // breadth-first ownership fills only the existing pad and resolves ties by district id.
        for(var e:new TreeMap<>(district.groups()).entrySet())if(cores.contains(e.getKey()))
            for(var p:e.getValue().stream().filter(domain::contains).sorted(ORDER).toList())
                if(owners.putIfAbsent(p,e.getKey())==null)queue.add(p);
        while(!queue.isEmpty()){
            var p=queue.removeFirst();
            for(var q:neighbors(p))if(domain.contains(q)&&!owners.containsKey(q)){
                owners.put(q,owners.get(p));queue.addLast(q);
            }
        }
        return owners;
    }
    public static List<CityLandUseSurfacePrintPlan.PlatformSpan> spans(Map<BlockPoint,Integer> cells) {
        List<CityLandUseSurfacePrintPlan.PlatformSpan> out=new ArrayList<>();var sorted=cells.keySet().stream().sorted(ORDER).toList();
        for(int i=0;i<sorted.size();) {var p=sorted.get(i++);int last=p.x(),y=cells.get(p);
            while(i<sorted.size()&&sorted.get(i).z()==p.z()&&sorted.get(i).x()==last+1&&cells.get(sorted.get(i))==y)last=sorted.get(i++).x();
            out.add(new CityLandUseSurfacePrintPlan.PlatformSpan(p.z(),p.x(),last,y));}
        return List.copyOf(out);
    }
    private static void retainComponents(Set<BlockPoint> cells,int minimum) {
        Set<BlockPoint> remaining=new HashSet<>(cells);
        while(!remaining.isEmpty()) {var seed=remaining.stream().min(ORDER).orElseThrow();Set<BlockPoint> component=new HashSet<>();
            ArrayDeque<BlockPoint> q=new ArrayDeque<>();q.add(seed);remaining.remove(seed);
            while(!q.isEmpty()){var p=q.removeFirst();component.add(p);for(var n:neighbors(p))if(remaining.remove(n))q.add(n);}
            if(component.size()<minimum)cells.removeAll(component);}
    }
    private static int run(Map<BlockPoint,?> cells,BlockPoint p,int dx,int dz) {int count=1;
        for(int sign:List.of(-1,1))for(int i=1;i<=8&&cells.containsKey(offset(p,dx*i*sign,dz*i*sign));i++)count++;return count;}
    private static void addBounds(Set<BlockPoint> cells,BlockBounds b){for(int z=b.minZ();z<=b.maxZ();z++)for(int x=b.minX();x<=b.maxX();x++)cells.add(new BlockPoint(x,z));}
    private static BlockPoint offset(BlockPoint p,int dx,int dz){return new BlockPoint(p.x()+dx,p.z()+dz);}
    private static List<BlockPoint> neighbors(BlockPoint p){return Arrays.stream(DIRS).map(d->offset(p,d[0],d[1])).toList();}
    private static final class Terrain {
        private final LandUseTerrainField field;private final Map<BlockPoint,LandUseTerrainField.Cell> samples=new HashMap<>();
        Terrain(LandUseTerrainField field){this.field=field;for(var c:field.cells())samples.put(new BlockPoint(Math.floorDiv(c.blockMinX(),field.cellStepBlocks()),Math.floorDiv(c.blockMinZ(),field.cellStepBlocks())),c);}
        private LandUseTerrainField.Cell at(BlockPoint p){return samples.get(new BlockPoint(Math.floorDiv(p.x(),field.cellStepBlocks()),Math.floorDiv(p.z(),field.cellStepBlocks())));}
        int height(int x,int z){var c=at(new BlockPoint(x,z));return c==null?-2048:(int)Math.round(c.elevation());}
        boolean safe(BlockPoint p){var c=at(p);if(c==null||!c.sampled()||c.water()||"cliff".equalsIgnoreCase(c.landformType()))return false;
            for(int dz=-2;dz<=2;dz++)for(int dx=-2;dx<=2;dx++){var s=at(offset(p,dx,dz));if(s==null||!s.sampled()||s.water())return false;}
            return neighbors(p).stream().allMatch(q->Math.abs(height(q.x(),q.z())-height(p.x(),p.z()))<=1);}
    }
}
