package com.rinsing.geomantia.systems.city.application.outdoor;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.algorithm.landuse.CityDistrictPlanner;
import com.rinsing.geomantia.systems.city.application.landuse.*;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityPublicRealmSpec;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.model.*;
import java.util.*;
import static com.rinsing.geomantia.systems.city.domain.blueprint.CityPublicRealmSpec.*;
import static com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan.*;

/** Deterministic composition on accepted district ground, followed by terrain-following fringe planting. */
public final class CityPublicRealmPlanner {
    public static final String SOURCE="public_realm::";
    private static final int[][] DIR={{1,0},{0,1},{-1,0},{0,-1}};
    private static final Comparator<BlockPoint> ORDER=Comparator.comparingInt(BlockPoint::z).thenComparingInt(BlockPoint::x);
    public record Result(CityLandUseSurfacePrintPlan surface,JsonObject report) {}
    public record Ground(int surfaceY, boolean dryLand) {}
    @FunctionalInterface public interface GroundSampler { Ground sample(int x, int z); }
    private record XYZ(int x,int y,int z) {}
    private record Volume(BlockBounds bounds,int minY,int maxY,boolean tree) {}
    private CityPublicRealmAssets assets;
    private LandUseTerrainField terrain;
    private GroundSampler preciseGround;
    private final Map<BlockPoint,Ground> groundSamples=new HashMap<>();
    private final Map<BlockPoint,LandUseTerrainField.Cell> terrainIndex=new HashMap<>();
    private final Map<BlockPoint,Integer> heights=new HashMap<>();
    private final Map<XYZ,FeatureCell> writes=new LinkedHashMap<>();
    private final Set<BlockPoint> blocked=new HashSet<>(),paths=new HashSet<>(),clearings=new HashSet<>();
    private final Set<BlockPoint> hardReservations=new HashSet<>();
    private final List<Volume> volumes=new ArrayList<>();
    private final JsonArray placements=new JsonArray(),warnings=new JsonArray();
    private final JsonObject plantingBudgets=new JsonObject();
    private final Map<String,Integer> counters=new TreeMap<>();
    private long seed;
    private int nodes,instances;
    private final Set<String> nodeIds=new HashSet<>();

    public Result plan(CityLandUseSurfacePrintPlan source,CityDistrictPlanner.Result district,
                       LandUseTerrainField terrain,List<com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan.CorridorExclusion> reservations,CityPublicRealmSpec spec) {
        return plan(source,district,terrain,reservations,spec,null);
    }
    public Result plan(CityLandUseSurfacePrintPlan source,CityDistrictPlanner.Result district,
                       LandUseTerrainField terrain,List<com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan.CorridorExclusion> reservations,CityPublicRealmSpec spec,GroundSampler preciseGround) {
        if(spec==null)return new Result(source,new JsonObject());
        if(district==null)throw new IllegalArgumentException("PUBLIC_REALM_REQUIRES_DISTRICT");
        // Planner instances are deliberately single-use: no state can leak between cities.
        if(this.terrain!=null)throw new IllegalStateException("PUBLIC_REALM_PLANNER_ALREADY_USED");
        this.terrain=terrain;this.preciseGround=preciseGround;JsonObject config=spec.toJson();assets=CityPublicRealmAssets.load(text(config,"catalog","academy"));
        seed=integer(config,"seed",0,Integer.MIN_VALUE,Integer.MAX_VALUE);
        for(var c:terrain.cells())terrainIndex.put(new BlockPoint(Math.floorDiv(c.blockMinX(),terrain.cellStepBlocks()),Math.floorDiv(c.blockMinZ(),terrain.cellStepBlocks())),c);
        for(var a:source.areas())if(a.recipe() instanceof UniformRecipe u)for(var s:u.platformSpans())for(int x=s.minX();x<=s.maxX();x++)heights.put(new BlockPoint(x,s.z()),s.targetY());
        for(var f:source.featureCells())if(f.surfaceOffset()==0&&f.targetSurfaceY()!=null)heights.put(p(f.x(),f.z()),f.targetSurfaceY());
        for(var p:district.structures())expand(blocked,p,1);
        for(var b:reservations)hardReservations.addAll(rect(b.blockBounds()));
        blocked.addAll(hardReservations);
        Set<BlockPoint> green=new HashSet<>();
        List<FeatureCell> retained=new ArrayList<>();
        for(var f:source.featureCells()) {
            boolean legacy=f.sourceId().startsWith(CityPublicGreeneryPlanner.SOURCE);
            if(f.surfaceOffset()==0&&(f.kind()==FeatureKind.GREEN_GROUND||f.blockId().equals("minecraft:grass_block")))green.add(p(f.x(),f.z()));
            if(legacy&&f.surfaceOffset()>0)continue;
            retained.add(f);
            if(circulation(f)||f.kind()==FeatureKind.TERRACE_RAIL)expand(blocked,p(f.x(),f.z()),2);
            if(circulation(f))paths.add(p(f.x(),f.z()));
            if(f.surfaceOffset()>0)expand(blocked,p(f.x(),f.z()),1);
        }
        JsonObject access=config.has("externalAccess")?access(object(config,"externalAccess"),retained,district):new JsonObject();
        JsonArray spaces=array(config,"spaces");if(spaces.size()>24)throw new IllegalArgumentException("PUBLIC_REALM_SPACE_LIMIT");
        Set<String> spaceIds=new HashSet<>();
        for(JsonElement e:spaces) {
            JsonObject space=e.getAsJsonObject();fields(space,"id","owner","origin","plan");
            String id=text(space,"id",""),owner=text(space,"owner","");
            if(id.isBlank()||!spaceIds.add(id))throw new IllegalArgumentException("PUBLIC_REALM_SPACE_ID");
            JsonObject requestedOrigin=object(space,"origin"),plan=object(space,"plan");
            int w=integer(plan,"width",96,8,128),d=integer(plan,"depth",80,8,128);
            var candidates=district.publicSpaces().stream().filter(s->s.owner().equals(owner)).filter(s->{
                if(!space.has("origin"))return true;
                int x=integer(requestedOrigin,"x",0,-30_000_000,30_000_000),z=integer(requestedOrigin,"z",0,-30_000_000,30_000_000);
                return s.bounds().contains(x,z)&&s.bounds().contains(x+w-1,z+d-1);
            }).toList();
            if(candidates.size()!=1)throw new IllegalArgumentException("PUBLIC_REALM_OWNER_AMBIGUOUS_OR_MISSING:"+owner);
            var bounds=candidates.get(0).bounds();
            JsonObject origin=object(space,"origin");int x=integer(origin,"x",bounds.minX()+(bounds.widthBlocks()-w)/2,-30_000_000,30_000_000);
            int z=integer(origin,"z",bounds.minZ()+(bounds.heightBlocks()-d)/2,-30_000_000,30_000_000);
            Set<BlockPoint> domain=rect(new BlockBounds(x,z,x+w-1,z+d-1));
            if(domain.stream().anyMatch(q->!bounds.contains(q.x(),q.z())||!knownLand(q)||!heights.containsKey(q)))throw new IllegalArgumentException("PUBLIC_REALM_SPACE_OUTSIDE_FROZEN_GROUND:"+id);
            compose(id,plan,p(x,z),domain);
            green.removeAll(domain);
        }
        if(config.has("greenery"))plant("interior",object(config,"greenery"),green,Map.of());
        Map<BlockPoint,Integer> fringe= config.has("fringe")?fringe(district,object(config,"fringe")):Map.of();
        if(!fringe.isEmpty())plant("fringe",object(config,"fringe"),fringe.keySet(),fringe);
        Set<XYZ> emitted=writes.keySet();
        retained.removeIf(f->{var key=new XYZ(f.x(),(f.targetSurfaceY()!=null?f.targetSurfaceY():height(p(f.x(),f.z())))+f.surfaceOffset(),f.z());
            if(!emitted.contains(key))return false;
            // Preserve the structural platform writer underneath a decorative surface overlay.
            return f.kind()!=FeatureKind.TERRACE_GROUND||writes.get(key).sourceId().startsWith(SOURCE+"access/");});
        retained.addAll(writes.values());retained.sort(Comparator.comparingInt(FeatureCell::z).thenComparingInt(FeatureCell::x).thenComparingInt(f->f.targetSurfaceY()==null?f.surfaceOffset():f.targetSurfaceY()+f.surfaceOffset()));
        CityLandUseSurfacePrintPlan finalPlan=new CityLandUseSurfacePrintPlanCodec().withComputedHash(new CityLandUseSurfacePrintPlan(source.schema(),source.cityId(),source.sourceLandUsePlanHash(),"",source.areas(),source.sharedBoundarySpans(),retained,source.materialField()));
        JsonObject report=new JsonObject();report.addProperty("schema","city_public_realm_report.v1");report.add("spec",config);report.add("placements",placements);report.add("warnings",warnings);report.add("externalAccess",access);
        report.addProperty("fringeColumns",fringe.size());report.addProperty("authoredBlocks",writes.size());report.addProperty("surfacePlanHash",finalPlan.planHash());
        report.addProperty("naturalGroundSource",preciseGround==null?"d3_coarse_field":"exact_generator_columns");
        report.addProperty("naturalGroundSamples",groundSamples.size());
        report.add("plantingBudgets",plantingBudgets);
        JsonObject counts=new JsonObject();counters.forEach(counts::addProperty);report.add("counts",counts);
        return new Result(finalPlan,report);
    }

    private void compose(String id,JsonObject plan,BlockPoint origin,Set<BlockPoint> domain) {
        fields(plan,"name","seed","width","depth","ground","pathWidth","entries","pools","nodes","clearings","plantingDensity","plantingZones");
        Map<String,List<CityPublicRealmAssets.Choice>> pools=new TreeMap<>();object(plan,"pools").entrySet().forEach(e->pools.put(e.getKey(),assets.choices(e.getValue().getAsJsonArray())));
        int width=integer(plan,"pathWidth",5,3,7);if(width%2==0)throw new IllegalArgumentException("PUBLIC_REALM_PATH_WIDTH");
        String ground=text(plan,"ground","GRASS");if(!Set.of("GRASS","STONE").contains(ground))throw new IllegalArgumentException("PUBLIC_REALM_GROUND");
        // A composition consumes its already accepted flat public-space surface, never creates a new terrace.
        Set<Integer> grades=new HashSet<>();domain.forEach(q->grades.add(height(q)));if(grades.size()!=1)throw new IllegalArgumentException("PUBLIC_REALM_SPACE_NOT_FLAT:"+id);
        for(var q:domain)if(!blocked.contains(q))emit(id+"/ground",q,height(q),0,ground.equals("GRASS")?"minecraft:grass_block":"minecraft:smooth_stone");
        for(JsonElement e:array(plan,"clearings")) {
            JsonObject c=e.getAsJsonObject();Set<BlockPoint> mask=localRect(c,origin);
            if(!domain.containsAll(mask)||!Collections.disjoint(mask,blocked))throw new IllegalArgumentException("PUBLIC_REALM_CLEARING_COLLISION");
            clearings.addAll(mask);for(var q:mask)emit(id+"/clearing",q,height(q),0,"minecraft:polished_andesite");
        }
        long previous=seed;seed=integer(plan,"seed",(int)seed,Integer.MIN_VALUE,Integer.MAX_VALUE);
        walkNodes(id,array(plan,"nodes"),origin,0,domain,pools,0);
        JsonArray entries=array(plan,"entries");if(entries.size()<2||entries.size()>8)throw new IllegalArgumentException("PUBLIC_REALM_ENTRIES");
        List<BlockPoint> points=new ArrayList<>();for(JsonElement e:entries){var c=e.getAsJsonObject();points.add(p(origin.x()+integer(c,"x",0,0,127),origin.z()+integer(c,"z",0,0,127)));}
        Set<BlockPoint> obstacles=new HashSet<>(blocked);for(var v:volumes)for(var q:rect(v.bounds()))expand(obstacles,q,1);
        for(int i=1;i<points.size();i++) {
            List<BlockPoint> route=route(points.get(0),points.get(i),domain,obstacles,width/2,paths);
            if(route.isEmpty())throw new IllegalArgumentException("PUBLIC_REALM_ENTRY_DISCONNECTED:"+id+":"+i);
            for(var q:route)for(int dz=-width/2;dz<=width/2;dz++)for(int dx=-width/2;dx<=width/2;dx++) {
                BlockPoint r=p(q.x()+dx,q.z()+dz);if(domain.contains(r)){paths.add(r);emit(id+"/path",r,height(r),0,"minecraft:polished_andesite");}
            }
        }
        JsonArray zones=array(plan,"plantingZones");if(zones.size()>24)throw new IllegalArgumentException("PUBLIC_REALM_ZONE_LIMIT");
        double density=number(plan,"plantingDensity",1,0,2);int total=0;
        for(JsonElement e:zones) {
            JsonObject z=e.getAsJsonObject();fields(z,"id","x","z","width","depth","pool","count","spacing","shape");
            String pool=text(z,"pool","");var choices=pools.get(pool);if(choices==null)throw new IllegalArgumentException("PUBLIC_REALM_ZONE_POOL");
            String shape=text(z,"shape","RECTANGLE");if(!Set.of("RECTANGLE","ELLIPSE","PATH_EDGE").contains(shape))throw new IllegalArgumentException("PUBLIC_REALM_ZONE_SHAPE");
            Set<BlockPoint> mask=localRect(z,origin);if(!domain.containsAll(mask))throw new IllegalArgumentException("PUBLIC_REALM_ZONE_OUTSIDE_SPACE");
            mask.removeIf(q->{var f=writes.get(new XYZ(q.x(),height(q),q.z()));return f==null||!f.blockId().equals("minecraft:grass_block");});
            int zx=origin.x()+integer(z,"x",0,-128,128),zz=origin.z()+integer(z,"z",0,-128,128),zw=integer(z,"width",1,1,128),zd=integer(z,"depth",1,1,128);
            if(shape.equals("ELLIPSE"))mask.removeIf(q->Math.pow((q.x()-zx-(zw-1)/2.0)/(zw/2.0),2)+Math.pow((q.z()-zz-(zd-1)/2.0)/(zd/2.0),2)>1);
            if(shape.equals("PATH_EDGE"))mask.removeIf(q->!near(paths,q,5));
            int count=(int)Math.round(integer(z,"count",1,1,150)*density);count=Math.min(count,Math.min(150,400-total));total+=count;
            scatter(id+"/"+text(z,"id",pool),choices,mask,integer(z,"spacing",4,3,24),count,true,Map.of());
        }
        seed=previous;
    }

    private void walkNodes(String prefix,JsonArray nodes,BlockPoint origin,int rotation,Set<BlockPoint> domain,Map<String,List<CityPublicRealmAssets.Choice>> pools,int depth) {
        if(depth>5||nodes.size()>32)throw new IllegalArgumentException("PUBLIC_REALM_NESTING_LIMIT");
        for(JsonElement e:nodes) {
            JsonObject n=e.getAsJsonObject();fields(n,"id","x","z","rotation","layout","count","spacing","columns","radius","faceCenter","assetId","pool","children");
            String local=text(n,"id","");if(local.isBlank()||++this.nodes>128)throw new IllegalArgumentException("PUBLIC_REALM_NODE_LIMIT");
            String id=prefix+"/"+local;if(!nodeIds.add(id))throw new IllegalArgumentException("PUBLIC_REALM_NODE_DUPLICATE");
            int selections=(n.has("assetId")?1:0)+(n.has("pool")?1:0)+(n.has("children")?1:0);if(selections!=1)throw new IllegalArgumentException("PUBLIC_REALM_NODE_SOURCE");
            int r=integer(n,"rotation",0,0,270);if(r%90!=0)throw new IllegalArgumentException("PUBLIC_REALM_ROTATION");
            BlockPoint localCenter=turn(integer(n,"x",0,-128,128),integer(n,"z",0,-128,128),rotation);
            BlockPoint center=p(origin.x()+localCenter.x(),origin.z()+localCenter.z());r=(rotation+r)%360;
            String layout=text(n,"layout","POINT");if(!Set.of("POINT","LINE","GRID","RING").contains(layout))throw new IllegalArgumentException("PUBLIC_REALM_LAYOUT");
            int count=integer(n,"count",1,1,48),spacing=integer(n,"spacing",12,1,64),columns=integer(n,"columns",1,1,12),radius=integer(n,"radius",20,1,60);
            if(layout.equals("POINT")&&count!=1)throw new IllegalArgumentException("PUBLIC_REALM_POINT_COUNT");
            for(int i=0;i<count;i++) {
                int x=0,z=0,rr=r;
                if(layout.equals("LINE"))x=i*spacing;
                if(layout.equals("GRID")){x=i%columns*spacing;z=i/columns*spacing;}
                if(layout.equals("RING")){double a=2*Math.PI*i/count;x=(int)Math.rint(Math.sin(a)*radius);z=(int)Math.rint(-Math.cos(a)*radius);if(n.has("faceCenter")&&n.get("faceCenter").getAsBoolean())rr=(r+(int)Math.rint((Math.toDegrees(a)+180)/90)*90)%360;}
                BlockPoint v=turn(x,z,r),at=p(center.x()+v.x(),center.z()+v.z());String instance=id+"/"+i;
                if(n.has("children")){walkNodes(instance,n.getAsJsonArray("children"),at,rr,domain,pools,depth+1);continue;}
                if(++instances>200)throw new IllegalArgumentException("PUBLIC_REALM_INSTANCE_LIMIT");
                List<CityPublicRealmAssets.Choice> choices=n.has("assetId")?List.of(new CityPublicRealmAssets.Choice(text(n,"assetId",""),1)):pools.get(text(n,"pool",""));
                if(choices==null)throw new IllegalArgumentException("PUBLIC_REALM_UNKNOWN_POOL");
                boolean placed=false;for(var c:weighted(choices,instance,at))if(place(instance,assets.asset(c.assetId()),at,rr,domain,false)){placed=true;break;}
                if(!placed)throw new IllegalArgumentException("PUBLIC_REALM_NODE_NO_ROOM:"+instance);
            }
        }
    }

    private void plant(String id,JsonObject config,Set<BlockPoint> domain,Map<BlockPoint,Integer> distances) {
        fields(config,"pools","spacing","slotLimits","maxPlacements","layeredPlanting","width");
        int maximum=integer(config,"maxPlacements",500,0,1000);JsonObject pools=object(config,"pools"),spacing=object(config,"spacing"),limits=object(config,"slotLimits");
        for(var o:List.of(pools,spacing,limits))fields(o,"trees","planters","hedges","shrubs","flowers");
        List<String> slots=List.of("trees","planters","hedges","shrubs","flowers").stream().filter(pools::has).toList();
        Map<String,Integer> requested=new LinkedHashMap<>(),allocated=new LinkedHashMap<>();
        for(String slot:slots) {
            int fallback=(maximum+slots.size()-1)/slots.size();
            int limit=integer(limits,slot,fallback,0,1000);requested.put(slot,limit);allocated.put(slot,0);
            if(!limits.has(slot))warnings.add("PUBLIC_REALM_SLOT_LIMIT_DEFAULTED:"+id+"/"+slot+":"+limit);
        }
        // Reserve every category's budget before placing anything. Earlier categories cannot consume
        // a later category's share, including when its explicit limit exceeds the shared maximum.
        for(int remaining=maximum;remaining>0;) {
            boolean advanced=false;
            for(String slot:slots)if(remaining>0&&allocated.get(slot)<requested.get(slot)) {
                allocated.merge(slot,1,Integer::sum);remaining--;advanced=true;
            }
            if(!advanced)break;
        }
        JsonObject budget=new JsonObject();plantingBudgets.add(id,budget);
        for(String slot:slots) {
            var choices=assets.pool(text(pools,slot,""));String expected=switch(slot){case "trees"->"tree";case "planters"->"planter";case "hedges"->"hedge";case "shrubs"->"shrub";default->"flowers";};
            if(choices.stream().anyMatch(c->!assets.asset(c.assetId()).kind().equals(expected)))throw new IllegalArgumentException("PUBLIC_REALM_POOL_KIND:"+slot);
            int limit=allocated.get(slot);
            if(limit<requested.get(slot))warnings.add("PUBLIC_REALM_BUDGET_LIMIT:"+id+"/"+slot+":requested="+requested.get(slot)+":allocated="+limit);
            int placed=scatter(id+"/"+slot,choices,domain,integer(spacing,slot,slot.equals("trees")?13:5,2,64),limit,
                    config.has("layeredPlanting")&&config.get("layeredPlanting").getAsBoolean(),distances);
            JsonObject result=new JsonObject();result.addProperty("requested",requested.get(slot));
            result.addProperty("allocated",limit);result.addProperty("placed",placed);budget.add(slot,result);
        }
    }
    private int scatter(String id,List<CityPublicRealmAssets.Choice> choices,Set<BlockPoint> domain,int spacing,int limit,boolean layered,Map<BlockPoint,Integer> distances) {
        List<BlockPoint> candidates=new ArrayList<>(domain);candidates.sort(Comparator.comparingDouble((BlockPoint q)->random(id,q)).thenComparing(ORDER));
        List<BlockPoint> chosen=new ArrayList<>();int placed=0,maxDistance=distances.values().stream().mapToInt(Integer::intValue).max().orElse(1);
        for(var q:candidates) {
            if(placed>=limit)break;
            if(blocked.contains(q)||near(paths,q,1)||clearings.contains(q)||chosen.stream().anyMatch(p->Math.hypot(p.x()-q.x(),p.z()-q.z())<spacing))continue;
            if(!distances.isEmpty()&&random(id+"/fade",p(Math.floorDiv(q.x(),7),Math.floorDiv(q.z(),7)))>1-.7*distances.get(q)/(double)maxDistance)continue;
            boolean ok=false;for(var c:weighted(choices,id,q)){int start=(int)(random(id+"/rotation",q)*4);for(int r=0;r<4;r++)if(place(id+"/"+placed,assets.asset(c.assetId()),q,(start+r)%4*90,domain,layered)){ok=true;break;}if(ok)break;}
            if(ok){chosen.add(q);placed++;}
        }
        if(placed<limit)warnings.add("PUBLIC_REALM_CAPACITY:"+id+":"+placed+"/"+limit);return placed;
    }
    private boolean place(String id,CityPublicRealmAssets.Asset a,BlockPoint center,int rotation,Set<BlockPoint> domain,boolean layered) {
        int rw=rotation%180==0?a.width():a.depth(),rd=rotation%180==0?a.depth():a.width();
        int ox=center.x()-rw/2,oz=center.z()-rd/2,ground=height(center),base=ground+1-a.groundPlane();
        BlockBounds bounds=new BlockBounds(ox,oz,ox+rw-1,oz+rd-1);Set<BlockPoint> footprint=rect(bounds);
        if(footprint.stream().anyMatch(q->!domain.contains(q)||blocked.contains(q)||near(paths,q,1)||clearings.contains(q)||!knownLand(q)||height(q)!=ground))return false;
        boolean low=layered&&Set.of("shrub","flowers").contains(a.kind());
        for(var v:volumes)if(overlap(bounds,v.bounds())&&!(low&&v.tree()&&ground+a.height()<v.maxY()))return false;
        List<FeatureCell> pending=new ArrayList<>();
        for(var v:a.voxels()) {
            BlockPoint q=rotateVoxel(v.x(),v.z(),a.width(),a.depth(),rotation);q=p(ox+q.x(),oz+q.z());int y=base+v.y();
            FeatureCell existing=writes.get(new XYZ(q.x(),y,q.z()));
            if(existing!=null&&existing.surfaceOffset()>0)return false;
            // Embedded foundation cells may consume only the accepted ground, never below an unknown cliff.
            if(y<ground-8)return false;
            pending.add(new FeatureCell(SOURCE+id+"@"+a.id()+"#"+a.nbtHash(),q.x(),q.z(),CityPublicRealmAssets.rotateState(v.state(),rotation),y-ground,FeatureKind.AUTHORED_BLOCK,HorizontalFacing.NONE,ground));
        }
        // Full bounding volume prevents interpenetration, including unpainted air in a template.
        for(var q:footprint)for(int y=ground+1;y<base+a.height();y++)if(writes.containsKey(new XYZ(q.x(),y,q.z())))return false;
        for(var f:pending)writes.put(new XYZ(f.x(),f.targetSurfaceY()+f.surfaceOffset(),f.z()),f);
        volumes.add(new Volume(bounds,base,base+a.height()-1,a.kind().equals("tree")));
        counters.merge(a.kind(),1,Integer::sum);JsonObject item=new JsonObject();item.addProperty("id",id);item.addProperty("assetId",a.id());item.addProperty("nbtSha256",a.nbtHash());item.addProperty("authorSha256",a.authorHash());item.addProperty("x",ox);item.addProperty("y",base);item.addProperty("z",oz);item.addProperty("rotation",rotation);placements.add(item);return true;
    }

    private Map<BlockPoint,Integer> fringe(CityDistrictPlanner.Result district,JsonObject config) {
        int width=integer(config,"width",20,1,48);Set<BlockPoint> urban=new HashSet<>(district.construction());urban.addAll(district.structures());
        // Flood only from the sampled world's boundary; enclosed courtyards are never exterior greenery.
        BlockBounds b=terrain.planningBounds();if((long)b.widthBlocks()*b.heightBlocks()>2_000_000)throw new IllegalArgumentException("PUBLIC_REALM_FRINGE_DOMAIN_LIMIT");Set<BlockPoint> exterior=new HashSet<>();ArrayDeque<BlockPoint> queue=new ArrayDeque<>();
        for(int x=b.minX();x<=b.maxX();x++){enqueue(p(x,b.minZ()),urban,exterior,queue);enqueue(p(x,b.maxZ()),urban,exterior,queue);}
        for(int z=b.minZ();z<=b.maxZ();z++){enqueue(p(b.minX(),z),urban,exterior,queue);enqueue(p(b.maxX(),z),urban,exterior,queue);}
        while(!queue.isEmpty()){var q=queue.remove();for(var n:neighbors(q))if(b.contains(n.x(),n.z()))enqueue(n,urban,exterior,queue);}
        Map<BlockPoint,Integer> distance=new HashMap<>();queue.clear();for(var q:urban)for(var n:neighbors(q))if(exterior.contains(n)&&distance.putIfAbsent(n,1)==null)queue.add(n);
        while(!queue.isEmpty()){var q=queue.remove();int d=distance.get(q);if(d>=width)continue;for(var n:neighbors(q))if(exterior.contains(n)&&distance.putIfAbsent(n,d+1)==null)queue.add(n);}
        distance.entrySet().removeIf(e->e.getValue()<3||blocked.contains(e.getKey())||!knownLand(e.getKey())||neighbors(e.getKey()).stream().anyMatch(n->!knownLand(n)||Math.abs(height(n)-height(e.getKey()))>1));
        return distance;
    }

    private JsonObject access(JsonObject config,List<FeatureCell> existing,CityDistrictPlanner.Result district) {
        fields(config,"id","width","waypoints","landing","harborReservation");String id=text(config,"id","external");
        int width=integer(config,"width",5,3,7);if(width%2==0)throw new IllegalArgumentException("PUBLIC_REALM_ACCESS_WIDTH");
        JsonArray points=array(config,"waypoints");if(points.size()<2||points.size()>16)throw new IllegalArgumentException("PUBLIC_REALM_ACCESS_WAYPOINTS");
        Map<BlockPoint,Double> grades=new LinkedHashMap<>();BlockPoint previous=null;double previousY=0;
        for(JsonElement e:points){JsonObject q=e.getAsJsonObject();fields(q,"x","z","surfaceY");BlockPoint at=p(integer(q,"x",0,-30_000_000,30_000_000),integer(q,"z",0,-30_000_000,30_000_000));double y=number(q,"surfaceY",0,-60,315);if(y*2!=Math.rint(y*2))throw new IllegalArgumentException("PUBLIC_REALM_HALF_GRADE");
            if(previous==null){boolean connected=existing.stream().anyMatch(f->circulation(f)&&f.x()==at.x()&&f.z()==at.z()&&Math.abs(height(at)+1-y)<=.5);if(!connected)throw new IllegalArgumentException("PUBLIC_REALM_ACCESS_NOT_CONNECTED");}
            else {int dx=at.x()-previous.x(),dz=at.z()-previous.z(),length=Math.abs(dx)+Math.abs(dz);if((dx!=0&&dz!=0)||length<1||length>512||Math.abs(y-previousY)>length*.5)throw new IllegalArgumentException("PUBLIC_REALM_ACCESS_GRADE");
                for(int i=0;i<=length;i++){double level=Math.round((previousY+(y-previousY)*i/length)*2)/2.0;BlockPoint c=p(previous.x()+Integer.signum(dx)*i,previous.z()+Integer.signum(dz)*i);for(int d=-width/2;d<=width/2;d++){BlockPoint p=dx==0?p(c.x()+d,c.z()):p(c.x(),c.z()+d);Double old=grades.putIfAbsent(p,level);if(old!=null&&Math.abs(old-level)>.5)throw new IllegalArgumentException("PUBLIC_REALM_ACCESS_SELF_INTERSECTION");}}
            }previous=at;previousY=y;
        }
        if(config.has("landing")){Set<BlockPoint> landing=rect(jsonBounds(object(config,"landing"),65));if(!landing.contains(previous))throw new IllegalArgumentException("PUBLIC_REALM_LANDING_ENDPOINT");for(var q:landing)grades.put(q,previousY);}
        Set<BlockPoint> structures=new HashSet<>();for(var q:district.structures())expand(structures,q,1);
        for(var e:grades.entrySet()){var q=e.getKey();if(!knownLand(q)||structures.contains(q)||hardReservations.contains(q)||Math.abs(e.getValue()-height(q)-1)>8)throw new IllegalArgumentException("PUBLIC_REALM_UNSAFE_ACCESS:"+q);}
        for(var e:grades.entrySet()) {
            var q=e.getKey();if(paths.contains(q))continue;double top=e.getValue();int surface=(int)Math.ceil(top)-1,natural=height(q);
            // A frozen cut/fill corridor is explicit, bounded, and checked against natural ground at execution.
            for(int y=Math.min(natural,surface);y<surface;y++)emit("access/"+id,q,surface,y-surface,"minecraft:stone_bricks");
            for(int y=surface+1;y<=Math.max(natural,surface)+3;y++)emit("access/"+id,q,surface,y-surface,"minecraft:air");
            emit("access/"+id,q,surface,0,top==Math.rint(top)?"minecraft:polished_andesite_slab[type=double,waterlogged=false]":"minecraft:polished_andesite_slab[type=bottom,waterlogged=false]");
            heights.put(q,surface);paths.add(q);
        }
        for(var q:grades.keySet())expand(blocked,q,3);
        if(config.has("harborReservation"))blocked.addAll(rect(jsonBounds(object(config,"harborReservation"),128)));
        JsonObject report=config.deepCopy();report.addProperty("columns",grades.size());return report;
    }

    private void emit(String id,BlockPoint p,int ground,int offset,String state){if(writes.size()>500_000)throw new IllegalArgumentException("PUBLIC_REALM_BLOCK_LIMIT");writes.put(new XYZ(p.x(),ground+offset,p.z()),new FeatureCell(SOURCE+id,p.x(),p.z(),state,offset,FeatureKind.AUTHORED_BLOCK,HorizontalFacing.NONE,ground));}
    private int height(BlockPoint q){Integer h=heights.get(q);if(h!=null)return h;if(preciseGround!=null)return ground(q).surfaceY();var c=cell(q);if(c==null||!c.sampled())throw new IllegalArgumentException("PUBLIC_REALM_UNSAMPLED_TERRAIN:"+q);return (int)Math.floor(c.elevation());}
    private Ground ground(BlockPoint q){return groundSamples.computeIfAbsent(q,p->Objects.requireNonNull(preciseGround.sample(p.x(),p.z())));}
    private LandUseTerrainField.Cell cell(BlockPoint q){return terrainIndex.get(p(Math.floorDiv(q.x(),terrain.cellStepBlocks()),Math.floorDiv(q.z(),terrain.cellStepBlocks())));}
    private boolean knownLand(BlockPoint q){var c=cell(q);return terrain.planningBounds().contains(q.x(),q.z())&&c!=null&&c.sampled()&&!c.water()&&Double.isFinite(c.elevation())
            &&(preciseGround==null||heights.containsKey(q)||ground(q).dryLand());}
    private double random(String salt,BlockPoint q){long h=seed^salt.hashCode()*0x9E3779B97F4A7C15L^q.x()*0xBF58476D1CE4E5B9L^q.z()*0x94D049BB133111EBL;h=(h^(h>>>30))*0xBF58476D1CE4E5B9L;h=(h^(h>>>27))*0x94D049BB133111EBL;h^=h>>>31;return ((h>>>11)+.5)*0x1.0p-53;}
    private List<CityPublicRealmAssets.Choice> weighted(List<CityPublicRealmAssets.Choice> choices,String id,BlockPoint q){return choices.stream().sorted(Comparator.comparingDouble(c->-Math.log(random(id+"/"+c.assetId(),q))/c.weight())).toList();}
    private static boolean circulation(FeatureCell f){return f.kind()==FeatureKind.ROAD_SLAB||f.kind()==FeatureKind.ROAD_STAIR||f.kind()==FeatureKind.BRIDGE_DECK||f.kind()==FeatureKind.GREEN_PATH;}
    private static BlockPoint p(int x,int z){return new BlockPoint(x,z);}
    private static List<BlockPoint> neighbors(BlockPoint p){return Arrays.stream(DIR).map(d->p(p.x()+d[0],p.z()+d[1])).toList();}
    private static Set<BlockPoint> rect(BlockBounds b){if((long)b.widthBlocks()*b.heightBlocks()>2_000_000)throw new IllegalArgumentException("PUBLIC_REALM_DOMAIN_LIMIT");Set<BlockPoint> s=new HashSet<>();for(int z=b.minZ();z<=b.maxZ();z++)for(int x=b.minX();x<=b.maxX();x++)s.add(p(x,z));return s;}
    private static BlockBounds jsonBounds(JsonObject o,int max){int x=integer(o,"minX",0,-30_000_000,30_000_000),z=integer(o,"minZ",0,-30_000_000,30_000_000),xx=integer(o,"maxX",x,x,x+max-1),zz=integer(o,"maxZ",z,z,z+max-1);return new BlockBounds(x,z,xx,zz);}
    private static Set<BlockPoint> localRect(JsonObject o,BlockPoint origin){int x=origin.x()+integer(o,"x",0,-128,128),z=origin.z()+integer(o,"z",0,-128,128),w=integer(o,"width",1,1,128),d=integer(o,"depth",1,1,128);return rect(new BlockBounds(x,z,x+w-1,z+d-1));}
    private static void expand(Set<BlockPoint> set,BlockPoint q,int radius){for(int z=-radius;z<=radius;z++)for(int x=-radius;x<=radius;x++)set.add(p(q.x()+x,q.z()+z));}
    private static boolean near(Set<BlockPoint> set,BlockPoint q,int radius){for(int z=-radius;z<=radius;z++)for(int x=-radius;x<=radius;x++)if(set.contains(p(q.x()+x,q.z()+z)))return true;return false;}
    private static boolean overlap(BlockBounds a,BlockBounds b){return a.minX()<=b.maxX()+1&&a.maxX()+1>=b.minX()&&a.minZ()<=b.maxZ()+1&&a.maxZ()+1>=b.minZ();}
    private static void enqueue(BlockPoint p,Set<BlockPoint> urban,Set<BlockPoint> seen,Deque<BlockPoint> queue){if(!urban.contains(p)&&seen.add(p))queue.add(p);}
    private static BlockPoint turn(int x,int z,int r){return switch(r){case 0->p(x,z);case 90->p(-z,x);case 180->p(-x,-z);case 270->p(z,-x);default->throw new IllegalArgumentException("PUBLIC_REALM_ROTATION");};}
    private static BlockPoint rotateVoxel(int x,int z,int w,int d,int r){return switch(r){case 0->p(x,z);case 90->p(d-1-z,x);case 180->p(w-1-x,d-1-z);case 270->p(z,w-1-x);default->throw new IllegalArgumentException("PUBLIC_REALM_ROTATION");};}
    private record RouteEntry(BlockPoint point,int cost,int estimate) {}
    private static List<BlockPoint> route(BlockPoint start,BlockPoint end,Set<BlockPoint> domain,Set<BlockPoint> obstacles,int half,Set<BlockPoint> network) {
        if(!domain.contains(start)||!domain.contains(end)||obstacles.contains(start)||obstacles.contains(end))return List.of();
        PriorityQueue<RouteEntry> queue=new PriorityQueue<>(Comparator.comparingInt(RouteEntry::estimate).thenComparingInt(RouteEntry::cost).thenComparing(RouteEntry::point,ORDER));
        Map<BlockPoint,BlockPoint> parents=new HashMap<>();Map<BlockPoint,Integer> costs=new HashMap<>();queue.add(new RouteEntry(start,0,0));parents.put(start,start);costs.put(start,0);
        while(!queue.isEmpty()){var current=queue.remove();var p=current.point();if(current.cost()!=costs.get(p))continue;
            if(p.equals(end)){List<BlockPoint> path=new ArrayList<>();while(!p.equals(start)){path.add(p);p=parents.get(p);}path.add(start);Collections.reverse(path);return path;}
            for(var q:neighbors(p))if(domain.contains(q)&&!obstacles.contains(q)){boolean free=true;for(int z=-half;z<=half;z++)for(int x=-half;x<=half;x++)if(!domain.contains(p(q.x()+x,q.z()+z))||obstacles.contains(p(q.x()+x,q.z()+z)))free=false;
                int cost=current.cost()+(network.contains(q)?1:2);if(free&&cost<costs.getOrDefault(q,Integer.MAX_VALUE)){parents.put(q,p);costs.put(q,cost);queue.add(new RouteEntry(q,cost,cost+Math.abs(q.x()-end.x())+Math.abs(q.z()-end.z())));}}}
        return List.of();
    }
}
