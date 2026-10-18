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
        Set<BlockPoint> protectedStructures=district.structures();int wetCount=0;
        List<AreaPrint> areas=new ArrayList<>();
        Map<BlockPoint,Integer> frozenPlatforms=new HashMap<>();
        for(var area:source.areas()){
            if(!(area.recipe() instanceof UniformRecipe u)||u.platformSpans().isEmpty()){areas.add(area);continue;}
            Map<BlockPoint,Integer> dry=new HashMap<>(),excluded=new HashMap<>();
            for(var s:area.exclusionSpans())for(int x=s.minX();x<=s.maxX();x++)excluded.put(new BlockPoint(x,s.z()),0);
            for(var s:u.platformSpans())for(int x=s.minX();x<=s.maxX();x++){var p=new BlockPoint(x,s.z());
                if(protectedStructures.contains(p)||ground.apply(p).dryLand())dry.put(p,s.targetY());else {wetCount++;excluded.put(p,0);}}
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
        Map<BlockPoint,List<Integer>> tiles=new HashMap<>();
        for(var p:greens.keySet())tiles.computeIfAbsent(new BlockPoint(Math.floorDiv(p.x(),8),Math.floorDiv(p.z(),8)),k->new ArrayList<>()).add(ground.apply(p).surfaceY());
        Map<BlockPoint,Integer> levels=new HashMap<>();tiles.forEach((p,ys)->{ys.sort(Integer::compare);levels.put(p,ys.get(ys.size()/2));});
        Map<BlockPoint,Integer> targets=new HashMap<>();int changed=0;
        for(var entry:greens.entrySet()){
            var p=entry.getKey();var f=entry.getValue();int natural=ground.apply(p).surfaceY();
            int target=f.targetSurfaceY()!=null?f.targetSurfaceY():levels.get(new BlockPoint(Math.floorDiv(p.x(),8),Math.floorDiv(p.z(),8)));
            if(f.targetSurfaceY()==null&&Math.abs(target-natural)>2)target=natural;
            targets.put(p,target);
        }
        JsonArray warnings=new JsonArray();
        for(var space:district.publicSpaces()){
            var b=space.bounds();var points=greens.keySet().stream().filter(p->b.contains(p.x(),p.z())).toList();if(points.isEmpty())continue;
            var ys=points.stream().map(p->greens.get(p).targetSurfaceY()!=null?greens.get(p).targetSurfaceY():ground.apply(p).surfaceY()).sorted().toList();int y=ys.get(ys.size()/2);
            var sharedYs=points.stream().map(frozenPlatforms::get).filter(Objects::nonNull).sorted().toList();
            if(sharedYs.isEmpty())sharedYs=kept.stream().filter(f->f.surfaceOffset()==0&&f.kind()==FeatureKind.TERRACE_GROUND
                    &&f.targetSurfaceY()!=null&&b.contains(f.x(),f.z())).map(FeatureCell::targetSurfaceY).sorted().toList();
            if(!sharedYs.isEmpty())y=sharedYs.get(sharedYs.size()/2);
            final int courtY=y;
            if(points.stream().allMatch(p->Math.abs(ground.apply(p).surfaceY()-courtY)<=6))points.forEach(p->targets.put(p,courtY));
            else warnings.add("CITY_PUBLIC_SPACE_RELIEF_REQUIRES_DESIGN:"+space.owner());
            long area=(long)b.widthBlocks()*b.heightBlocks();if(points.size()<area*.9)warnings.add("CITY_PUBLIC_SPACE_NATURAL_WATER_RETAINED:"+space.owner());
        }
        Map<BlockPoint,Integer> bridges=new HashMap<>();
        for(boolean alongX:List.of(true,false)){
            Map<Integer,List<BlockPoint>> lines=new TreeMap<>();paths.forEach(p->lines.computeIfAbsent(alongX?p.z():p.x(),k->new ArrayList<>()).add(p));
            for(var line:lines.values()){
                line.sort(Comparator.comparingInt(p->alongX?p.x():p.z()));
                for(int i=0;i<line.size();i++){
                    var start=line.get(i);int left=ground.apply(start).surfaceY(),minimum=left;
                    for(int j=i+1;j<line.size()&&j-i<=32;j++){
                        var end=line.get(j);if((alongX?end.x()-start.x():end.z()-start.z())!=j-i)break;
                        int right=ground.apply(end).surfaceY();minimum=Math.min(minimum,right);
                        if(j-i>=2&&Math.abs(left-right)<=1&&Math.min(left,right)-minimum>=6){
                            for(int k=i+1;k<j;k++){var p=line.get(k);int y=(int)Math.round(left+(right-left)*(k-i)/(double)(j-i));targets.put(p,y);if(y-ground.apply(p).surfaceY()>6)bridges.put(p,y);}
                            i=j-1;break;
                        }
                    }
                }
            }
        }
        Set<BlockPoint> occupied=new HashSet<>();kept.stream().filter(f->f.kind()!=FeatureKind.GREEN_PLANT).forEach(f->occupied.add(new BlockPoint(f.x(),f.z())));
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
            kept.add(new FeatureCell((stair?CityEntranceAccessPlanner.BRIDGE_SOURCE:SOURCE)+source.cityId(),p.x(),p.z(),block,0,
                    stair?FeatureKind.ROAD_STAIR:bridge?FeatureKind.BRIDGE_DECK:f.kind(),facing,y+(stair?1:0)));
        }
        // Each railing has its own deck column; a beam below an empty column would leave the post floating.
        railTargets.forEach((p,y)->kept.add(new FeatureCell(SOURCE+source.cityId(),p.x(),p.z(),"minecraft:stone_brick_wall",1,FeatureKind.BRIDGE_RAIL,HorizontalFacing.NONE,y)));
        kept.sort(Comparator.comparingInt(FeatureCell::z).thenComparingInt(FeatureCell::x).thenComparingInt(FeatureCell::surfaceOffset));
        var surface=new CityLandUseSurfacePrintPlanCodec().withComputedHash(new CityLandUseSurfacePrintPlan(source.schema(),source.cityId(),source.sourceLandUsePlanHash(),"",areas,source.sharedBoundarySpans(),kept,source.materialField()));
        var report=new JsonObject();report.addProperty("sampleSource",precise==null?"coarse_d3":"exact_generator_columns");report.addProperty("waterColumnsRetained",wetCount);report.addProperty("gradedGreenColumns",changed);report.addProperty("publicPathBridgeColumns",bridges.size());report.add("warnings",warnings);
        return new Result(surface,report);
    }
}
