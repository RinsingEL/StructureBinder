package com.rinsing.geomantia.systems.city.application.landuse;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import java.util.*;
import static com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan.*;

/** Entrance stairs prefer three columns; an existing single-file alley remains usable and is reported. */
public final class CityEntranceAccessPlanner {
    public static final String SOURCE="entrance_access::", BRIDGE_SOURCE=SOURCE+"bridge::";
    public record Result(CityLandUseSurfacePrintPlan surface,JsonObject report){}
    private record Key(int x,int z,int offset){}
    public Result plan(CityLandUseSurfacePrintPlan source,List<LandUseAreaPlan.GateSlot> gates,Set<BlockPoint> structures){
        Map<BlockPoint,Integer> floors=new HashMap<>();
        for(var a:source.areas())if(a.recipe() instanceof UniformRecipe u)for(var s:u.platformSpans())
            for(int x=s.minX();x<=s.maxX();x++)floors.put(new BlockPoint(x,s.z()),s.targetY());
        Map<Key,FeatureCell> cells=new LinkedHashMap<>();source.featureCells().forEach(f->cells.put(new Key(f.x(),f.z(),f.surfaceOffset()),f));
        int connected=0,changed=0;JsonArray warnings=new JsonArray();Set<String> seen=new HashSet<>();
        for(var gate:gates.stream().sorted(Comparator.comparing(LandUseAreaPlan.GateSlot::gateId)).toList()){
            var door=gate.block();Integer floor=floors.get(door);if(floor==null||!seen.add(door+":"+gate.direction()))continue;
            int dx=0,dz=0;switch(gate.direction()){case NORTH->dz=-1;case SOUTH->dz=1;case WEST->dx=-1;case EAST->dx=1;}
            var first=cells.get(new Key(door.x()+dx,door.z()+dz,0));if(!road(first)||first.targetSurfaceY()==null)continue;
            int street=grade(first),delta=floor-street;if(delta==0){connected++;continue;}
            if(Math.abs(delta)>6){warnings.add("CITY_ENTRANCE_GRADE_TOO_STEEP:"+gate.gateId());continue;}
            List<FeatureCell> pending=new ArrayList<>();boolean safe=false;
            for(int attempt=0;attempt<2;attempt++){
            int halfWidth=attempt==0?1:0;
            for(int length=1;length<=12;length++){
            pending.clear();safe=true;
            // Resolve the untouched far landing first. The first road column is about to be
            // replaced and cannot serve as the destination of the whole staircase.
            Integer landing=null;
            for(int side=-halfWidth;side<=halfWidth;side++){
                var end=cells.get(new Key(door.x()+dx*(length+1)-dz*side,door.z()+dz*(length+1)+dx*side,0));
                if(!road(end)||end.targetSurfaceY()==null||end.kind()==FeatureKind.ROAD_STAIR
                        ||landing!=null&&landing!=grade(end)){safe=false;break;}
                landing=grade(end);
            }
            if(!safe)continue;
            int endDelta=floor-landing;
            if(Math.abs(endDelta)>6||Math.abs(endDelta)+(endDelta<0?1:0)>length){safe=false;continue;}
            for(int n=1;n<=length&&safe;n++)for(int side=-halfWidth;side<=halfWidth;side++){
                int x=door.x()+dx*n-dz*side,z=door.z()+dz*n+dx*side;var key=new Key(x,z,0);var old=cells.get(key);
                if(structures.contains(new BlockPoint(x,z))||!road(old)||old.targetSurfaceY()==null){safe=false;break;}
                var above=cells.get(new Key(x,z,1));
                if(above!=null&&above.kind()!=FeatureKind.GREEN_PLANT){safe=false;break;}
                int base=endDelta>0?Math.max(landing,floor-n):Math.min(landing,floor+n-1);
                boolean stair=endDelta>0?n<=endDelta:endDelta<0&&n<=-endDelta;
                String fallbackStair=old.blockId().endsWith("_slab")?old.blockId().substring(0,old.blockId().length()-5)+"_stairs":old.blockId().endsWith("_stairs")?old.blockId():"minecraft:stone_brick_stairs";
                String block=stair?source.materialField().at("accessStair",x,z,old.sourceId(),fallbackStair):
                        source.materialField().at("accessSurface",x,z,old.sourceId(),"minecraft:stone_brick_slab");
                var facing=HorizontalFacing.NONE;if(stair){int fx=endDelta>0?-dx:dx,fz=endDelta>0?-dz:dz;
                    facing=fx<0?HorizontalFacing.WEST:fx>0?HorizontalFacing.EAST:fz<0?HorizontalFacing.NORTH:HorizontalFacing.SOUTH;}
                boolean bridge=old.kind()==FeatureKind.BRIDGE_DECK||old.sourceId().startsWith(BRIDGE_SOURCE);
                pending.add(new FeatureCell((bridge?BRIDGE_SOURCE:SOURCE)+gate.gateId(),x,z,block,0,
                        stair?FeatureKind.ROAD_STAIR:FeatureKind.ROAD_SLAB,facing,base+(stair?1:0)));
            }
            if(safe)break;
            }
            if(safe){if(halfWidth==0)warnings.add("CITY_ENTRANCE_NARROW_STAIR:"+gate.gateId());break;}
            }
            if(!safe){warnings.add("CITY_ENTRANCE_CONTINUOUS_CORRIDOR_REQUIRED:"+gate.gateId());continue;}
            for(var f:pending){cells.put(new Key(f.x(),f.z(),0),f);var above=cells.get(new Key(f.x(),f.z(),1));
                if(above!=null&&above.kind()==FeatureKind.GREEN_PLANT)cells.remove(new Key(f.x(),f.z(),1));}
            connected++;changed+=pending.size();
        }
        var features=cells.values().stream().sorted(Comparator.comparingInt(FeatureCell::z).thenComparingInt(FeatureCell::x).thenComparingInt(FeatureCell::surfaceOffset)).toList();
        var surface=new CityLandUseSurfacePrintPlanCodec().withComputedHash(new CityLandUseSurfacePrintPlan(source.schema(),source.cityId(),source.sourceLandUsePlanHash(),"",source.areas(),source.sharedBoundarySpans(),features,source.materialField()));
        var report=new JsonObject();report.addProperty("connectedEntrances",connected);report.addProperty("gradedApproachColumns",changed);report.add("warnings",warnings);return new Result(surface,report);
    }
    private static boolean road(FeatureCell f){return f!=null&&Set.of(FeatureKind.ROAD_SLAB,FeatureKind.ROAD_STAIR,FeatureKind.BRIDGE_DECK).contains(f.kind());}
    private static int grade(FeatureCell f){return f.targetSurfaceY()-(f.kind()==FeatureKind.ROAD_STAIR?1:0);}
}
