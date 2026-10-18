package com.rinsing.geomantia.systems.city.application.landuse;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.algorithm.landuse.CityDistrictPlanner;
import com.rinsing.geomantia.systems.city.application.outdoor.CityPublicRealmPlanner;
import com.rinsing.geomantia.systems.city.domain.landuse.*;
import com.rinsing.geomantia.systems.city.domain.model.*;
import java.util.*;
import static com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan.*;

/** Precise shore clipping and bounded interior grading. Public paths span short deep gaps. */
public final class CityPublicGroundPlanner {
    public static final String SOURCE="public_ground::";
    public static final String SHARED_SOURCE=SOURCE+"shared::";
    public static final int MAX_EARTHWORK = CityPublicGroundGrade.MAX_EARTHWORK;
    public static final int MAX_SHARED_CUT = 12;
    /** An accepted terrace can cut a hillside like its buildings; it still cannot cap a deep void. */
    public static boolean permitsEarthwork(int naturalY,int targetY,boolean shared) {
        return targetY-naturalY<=MAX_EARTHWORK && naturalY-targetY<=(shared?MAX_SHARED_CUT:MAX_EARTHWORK);
    }
    public record Result(CityLandUseSurfacePrintPlan surface,JsonObject report){}
    public Result plan(CityLandUseSurfacePrintPlan source,CityDistrictPlanner.Result district,LandUseTerrainField terrain,
                       CityPublicRealmPlanner.GroundSampler precise){
        if(district==null)return new Result(source,new JsonObject());
        Map<BlockPoint,CityPublicRealmPlanner.Ground> samples=new HashMap<>();
        Map<BlockPoint,LandUseTerrainField.Cell> cells=new HashMap<>();int step=terrain.cellStepBlocks();
        for(var c:terrain.cells())cells.put(new BlockPoint(Math.floorDiv(c.blockMinX(),step),Math.floorDiv(c.blockMinZ(),step)),c);
        java.util.function.Function<BlockPoint,CityPublicRealmPlanner.Ground> ground=p->samples.computeIfAbsent(p,q->{
            if(precise!=null)return precise.sample(q.x(),q.z());var c=cells.get(new BlockPoint(Math.floorDiv(q.x(),step),Math.floorDiv(q.z(),step)));
            return c==null?new CityPublicRealmPlanner.Ground(-2048,false):new CityPublicRealmPlanner.Ground((int)Math.round(c.elevation()),c.sampled()&&!c.water());});
        Set<BlockPoint> protectedStructures=district.structures();int wetCount=0,unsupportedCount=0;
        List<AreaPrint> areas=new ArrayList<>();
        Map<BlockPoint,Integer> frozenPlatforms=new HashMap<>();
        for(var area:source.areas()){
            if(!(area.recipe() instanceof UniformRecipe u)||u.platformSpans().isEmpty()){areas.add(area);continue;}
            Map<BlockPoint,Integer> dry=new HashMap<>(),excluded=new HashMap<>();
            for(var s:area.exclusionSpans())for(int x=s.minX();x<=s.maxX();x++)excluded.put(new BlockPoint(x,s.z()),0);
            for(var s:u.platformSpans())for(int x=s.minX();x<=s.maxX();x++){var p=new BlockPoint(x,s.z());
                var sample=ground.apply(p);
                if(protectedStructures.contains(p)||sample.dryLand()&&permitsEarthwork(sample.surfaceY(),s.targetY(),true))dry.put(p,s.targetY());
                else {if(!sample.dryLand())wetCount++;else unsupportedCount++;excluded.put(p,0);}}
            var spans=CitySharedTerracePlanner.spans(dry);
            frozenPlatforms.putAll(dry);
            areas.add(new AreaPrint(area.printAreaId(),area.landUseAreaId(),area.sourceGroupIds(),area.surfaceSettings(),
                    area.memberSpans(),CitySharedTerracePlanner.spans(excluded).stream().map(s->new LandUseAreaPlan.ScanlineSpan(s.z(),s.minX(),s.maxX())).toList(),area.surfaceAlgorithm(),area.algorithmAnchor(),
                    new UniformRecipe(u.surfaceBlockId(),u.boundaryBlockId(),spans),area.terrainReferenceCells()));
        }
        Map<BlockPoint,FeatureCell> greens=new HashMap<>();Set<BlockPoint> paths=new HashSet<>();
        List<FeatureCell> kept=new ArrayList<>();
        for(var f:source.featureCells()){
            var p=new BlockPoint(f.x(),f.z());boolean managed=f.sourceId().startsWith(CityPublicGreeneryPlanner.SOURCE)||f.sourceId().startsWith(CitySharedTerracePlanner.SOURCE);
            if(managed&&!protectedStructures.contains(p)&&!ground.apply(p).dryLand()){wetCount++;continue;}
            if(f.surfaceOffset()==0&&f.sourceId().startsWith(CityPublicGreeneryPlanner.SOURCE)){
                greens.put(p,f);if(f.kind()==FeatureKind.GREEN_PATH)paths.add(p);
            }else kept.add(f);
        }
        Map<BlockPoint,Integer> fixed=new HashMap<>(frozenPlatforms);
        for(var f:kept)if(f.surfaceOffset()==0&&f.targetSurfaceY()!=null&&f.kind()!=FeatureKind.AUTHORED_BLOCK
                &&(f.kind()!=FeatureKind.BRIDGE_DECK||Math.abs(f.targetSurfaceY()-ground.apply(new BlockPoint(f.x(),f.z())).surfaceY())<=MAX_EARTHWORK))
            fixed.put(new BlockPoint(f.x(),f.z()),f.targetSurfaceY()-(f.kind()==FeatureKind.ROAD_STAIR?1:0));
        Map<BlockPoint,Integer> targets=new HashMap<>();int changed=0;
        for(var entry:greens.entrySet()){
            var p=entry.getKey();var f=entry.getValue();int natural=ground.apply(p).surfaceY();
            List<Integer> neighborhood=new ArrayList<>();
            for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++){
                var sample=ground.apply(new BlockPoint(p.x()+dx,p.z()+dz));
                if(sample.dryLand()&&Math.abs(sample.surfaceY()-natural)<=2)neighborhood.add(sample.surfaceY());
            }
            neighborhood.sort(Integer::compare);
            int target=fixed.getOrDefault(p,neighborhood.get(neighborhood.size()/2));
            targets.put(p,target);
        }
        JsonArray warnings=new JsonArray();
        for(var space:district.publicSpaces()){
            var b=space.bounds();var points=greens.keySet().stream().filter(p->b.contains(p.x(),p.z())).toList();if(points.isEmpty())continue;
            // A public-space rectangle describes composition, not a second platform footprint.
            // Only a standalone, gently sloping court gets a preferred level; fixed pads win per column.
            if(points.stream().noneMatch(fixed::containsKey)){
                var ys=points.stream().map(p->ground.apply(p).surfaceY()).sorted().toList();int courtY=ys.get(ys.size()/2);
                if(points.stream().allMatch(p->Math.abs(ground.apply(p).surfaceY()-courtY)<=2))points.forEach(p->targets.put(p,courtY));
                else warnings.add("CITY_PUBLIC_SPACE_RELIEF_REQUIRES_DESIGN:"+space.owner());
            }
            long area=(long)b.widthBlocks()*b.heightBlocks();if(points.size()<area*.9)warnings.add("CITY_PUBLIC_SPACE_NATURAL_WATER_RETAINED:"+space.owner());
        }
        var grade=CityPublicGroundGrade.solve(targets,fixed,ground);
        targets.clear();targets.putAll(grade.heights());
        if(!grade.conflicts().isEmpty())warnings.add("CITY_PUBLIC_GROUND_TRANSITION_SPACE_REQUIRED:columns="+grade.conflicts().size());
        Map<BlockPoint,Integer> bridges=new HashMap<>();
        for(boolean alongX:List.of(true,false)){
            Map<Integer,List<BlockPoint>> lines=new TreeMap<>();paths.forEach(p->lines.computeIfAbsent(alongX?p.z():p.x(),k->new ArrayList<>()).add(p));
            for(var line:lines.values()){
                line.sort(Comparator.comparingInt(p->alongX?p.x():p.z()));
                for(int i=0;i<line.size();i++){
                    var start=line.get(i);int left=targets.get(start),minimum=ground.apply(start).surfaceY();
                    for(int j=i+1;j<line.size()&&j-i<=32;j++){
                        var end=line.get(j);if((alongX?end.x()-start.x():end.z()-start.z())!=j-i)break;
                        int right=targets.get(end);minimum=Math.min(minimum,ground.apply(end).surfaceY());
                        if(j-i>=2&&Math.abs(left-right)<=1&&Math.min(left,right)-minimum>=6){
                            for(int k=i+1;k<j;k++){var p=line.get(k);int y=(int)Math.round(left+(right-left)*(k-i)/(double)(j-i));if(!fixed.containsKey(p)){targets.put(p,y);if(y-ground.apply(p).surfaceY()>6)bridges.put(p,y);}}
                            i=j-1;break;
                        }
                    }
                }
            }
        }
        Map<BlockPoint,Integer> finalGround=new HashMap<>(fixed);finalGround.putAll(targets);
        // Rebuild generated guards from the final surface, including boundaries between districts.
        // Authored walls and fixtures retain their own semantics.
        kept.removeIf(f->f.kind()==FeatureKind.TERRACE_RAIL&&f.sourceId().startsWith(CitySharedTerracePlanner.SOURCE));
        Set<BlockPoint> edgeBlocked=new HashSet<>(protectedStructures);
        Set<BlockPoint> edgePaths=new HashSet<>(paths);
        Map<BlockPoint,Integer> sharedEdges=new HashMap<>(frozenPlatforms);
        for(var f:kept){
            var p=new BlockPoint(f.x(),f.z());
            if(f.surfaceOffset()==0&&f.kind()==FeatureKind.TERRACE_GROUND&&f.targetSurfaceY()!=null)sharedEdges.put(p,f.targetSurfaceY());
            if(Set.of(FeatureKind.ROAD_SLAB,FeatureKind.ROAD_STAIR,FeatureKind.BRIDGE_DECK).contains(f.kind()))edgePaths.add(p);
            if(f.surfaceOffset()>0&&f.kind()!=FeatureKind.GREEN_PLANT)edgeBlocked.add(p);
        }
        for(var p:edgePaths)for(int dz=-2;dz<=2;dz++)for(int dx=-2;dx<=2;dx++)edgeBlocked.add(new BlockPoint(p.x()+dx,p.z()+dz));
        Set<BlockPoint> guarded=new HashSet<>();
        for(var e:sharedEdges.entrySet()){
            var p=e.getKey();int y=finalGround.getOrDefault(p,e.getValue());
            if(edgeBlocked.contains(p)||CityPublicGroundGrade.neighbors(p).stream()
                    .noneMatch(q->y-finalGround.getOrDefault(q,ground.apply(q).surfaceY())>=2))continue;
            guarded.add(p);
        }
        // Diagonal rim cells only touch at a point. Join them through the supported
        // inner corner; never grow onto the low side or bridge a protected opening.
        Set<BlockPoint> corners=new HashSet<>();
        for(var p:sharedEdges.keySet()) {
            if(guarded.contains(p)||edgeBlocked.contains(p))continue;
            int y=finalGround.getOrDefault(p,sharedEdges.get(p));
            for(int dx:new int[]{-1,1})for(int dz:new int[]{-1,1}) {
                var alongX=new BlockPoint(p.x()+dx,p.z());
                var alongZ=new BlockPoint(p.x(),p.z()+dz);
                var outside=new BlockPoint(p.x()+dx,p.z()+dz);
                if(guarded.contains(alongX)&&guarded.contains(alongZ)
                        &&Objects.equals(finalGround.get(alongX),y)&&Objects.equals(finalGround.get(alongZ),y)
                        &&y-finalGround.getOrDefault(outside,ground.apply(outside).surfaceY())>=2)corners.add(p);
            }
        }
        guarded.addAll(corners);
        for(var p:guarded) {
            int y=finalGround.getOrDefault(p,sharedEdges.get(p));
            kept.add(new FeatureCell(CitySharedTerracePlanner.SOURCE+source.cityId(),p.x(),p.z(),
                    source.materialField().at("railing",p.x(),p.z(),"minecraft:stone_brick_wall"),1,FeatureKind.TERRACE_RAIL,HorizontalFacing.NONE,y));
        }
        kept.removeIf(f->f.kind()==FeatureKind.GREEN_PLANT&&guarded.contains(new BlockPoint(f.x(),f.z())));
        Set<BlockPoint> occupied=new HashSet<>();kept.stream().filter(f->f.kind()!=FeatureKind.GREEN_PLANT&&f.kind()!=FeatureKind.TERRACE_RAIL).forEach(f->occupied.add(new BlockPoint(f.x(),f.z())));
        Map<BlockPoint,Integer> railTargets=new HashMap<>();
        for(var e:bridges.entrySet())for(var n:List.of(new BlockPoint(e.getKey().x()+1,e.getKey().z()),new BlockPoint(e.getKey().x()-1,e.getKey().z()),new BlockPoint(e.getKey().x(),e.getKey().z()+1),new BlockPoint(e.getKey().x(),e.getKey().z()-1)))
            if(!paths.contains(n)&&!occupied.contains(n)&&greens.containsKey(n))railTargets.merge(n,e.getValue(),Math::max);
        kept.removeIf(f->f.kind()==FeatureKind.GREEN_PLANT&&railTargets.containsKey(new BlockPoint(f.x(),f.z())));
        for(var e:greens.entrySet()){
            var p=e.getKey();var f=e.getValue();int y=railTargets.getOrDefault(p,targets.get(p));if(y!=ground.apply(p).surfaceY())changed++;
            boolean bridge=bridges.containsKey(p)||railTargets.containsKey(p);
            var neighbors=List.of(new BlockPoint(p.x()+1,p.z()),new BlockPoint(p.x()-1,p.z()),new BlockPoint(p.x(),p.z()+1),new BlockPoint(p.x(),p.z()-1));
            if(paths.contains(p)&&neighbors.stream().anyMatch(n->bridges.containsKey(n)&&targets.get(n)==y+1))bridge=true;
            var facing=f.facing();boolean stair=false;
            if(bridge)for(var n:neighbors)
                if(paths.contains(n)&&targets.get(n)==y+1){stair=true;facing=n.x()>p.x()?HorizontalFacing.EAST:n.x()<p.x()?HorizontalFacing.WEST:n.z()>p.z()?HorizontalFacing.SOUTH:HorizontalFacing.NORTH;break;}
            String block=bridge?source.materialField().at("bridgeSurface",p.x(),p.z(),"minecraft:stone_brick_slab"):f.blockId();
            if(stair)block=source.materialField().at("accessStair",p.x(),p.z(),block.endsWith("_slab")?block.substring(0,block.length()-5)+"_stairs":"minecraft:stone_brick_stairs");
            String provenance=fixed.containsKey(p)?"shared::":y==ground.apply(p).surfaceY()?"natural::":"transition::";
            kept.add(new FeatureCell((stair?CityEntranceAccessPlanner.BRIDGE_SOURCE:bridge?SOURCE+"bridge::":SOURCE+provenance)+source.cityId(),p.x(),p.z(),block,0,
                    stair?FeatureKind.ROAD_STAIR:bridge?FeatureKind.BRIDGE_DECK:f.kind(),facing,y+(stair?1:0)));
        }
        // Each railing has its own deck column; a beam below an empty column would leave the post floating.
        railTargets.forEach((p,y)->kept.add(new FeatureCell(SOURCE+source.cityId(),p.x(),p.z(),"minecraft:stone_brick_wall",1,FeatureKind.BRIDGE_RAIL,HorizontalFacing.NONE,y)));
        kept.sort(Comparator.comparingInt(FeatureCell::z).thenComparingInt(FeatureCell::x).thenComparingInt(FeatureCell::surfaceOffset));
        var surface=new CityLandUseSurfacePrintPlanCodec().withComputedHash(new CityLandUseSurfacePrintPlan(source.schema(),source.cityId(),source.sourceLandUsePlanHash(),"",areas,source.sharedBoundarySpans(),kept,source.materialField()));
        var report=new JsonObject();report.addProperty("sampleSource",precise==null?"coarse_d3":"exact_generator_columns");report.addProperty("waterColumnsRetained",wetCount);report.addProperty("unsupportedPlatformColumnsRetained",unsupportedCount);report.addProperty("transitionConflictColumns",grade.conflicts().size());report.addProperty("gradedGreenColumns",changed);report.addProperty("publicPathBridgeColumns",bridges.size());report.add("warnings",warnings);
        JsonArray conflicts=new JsonArray();
        for(var p:grade.conflicts().stream().sorted(Comparator.comparingInt(BlockPoint::z).thenComparingInt(BlockPoint::x)).limit(24).toList()){
            JsonObject row=new JsonObject();row.addProperty("x",p.x());row.addProperty("z",p.z());
            row.addProperty("naturalY",ground.apply(p).surfaceY());row.addProperty("fixedY",fixed.get(p));
            JsonArray neighbors=new JsonArray();for(var q:CityPublicGroundGrade.neighbors(p)){
                JsonObject n=new JsonObject();n.addProperty("x",q.x());n.addProperty("z",q.z());n.addProperty("fixedY",fixed.get(q));n.addProperty("green",greens.containsKey(q));n.addProperty("naturalY",ground.apply(q).surfaceY());neighbors.add(n);
            }
            row.add("neighbors",neighbors);conflicts.add(row);
        }
        report.add("transitionConflictSamples",conflicts);
        return new Result(surface,report);
    }
}
