package com.rinsing.geomantia.systems.city.application.intercity;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.domain.model.*;
import com.rinsing.geomantia.thirdparty.roadweaver.BoundedRoadPathfinder;
import java.util.*;

/** Derived D5 roads; accepted D4 input is never rewritten. */
public final class CityExitRoadPlanner {
    private record Candidate(BlockPoint inside, BlockPoint outside, BlockPoint gate, double score) {}
    public JsonObject plan(String cityId, JsonObject anchors, JsonObject wall, BlockBounds domain,
                           List<InterCityNetwork.Link> network) {
        JsonObject result=new JsonObject(); result.addProperty("schema","city_intercity_exit_plan");
        result.addProperty("cityId",cityId);
        JsonArray roads=new JsonArray(),portals=new JsonArray(),failures=new JsonArray();
        result.add("streetBands",roads); result.add("portals",portals); result.add("unresolvedLinks",failures);
        List<BlockBounds> buildings=new ArrayList<>(),corridors=new ArrayList<>();
        for (JsonElement e:array(anchors,"anchors")) for(String key:List.of("reservedEnvelope","collisionEnvelope","maskEnvelope","plannedFootprint"))
            if(e.getAsJsonObject().has(key)) buildings.add(bounds(e.getAsJsonObject().getAsJsonObject(key)));
        for(JsonElement e:array(wall,"wallCorridorMask")) corridors.add(bounds(e.getAsJsonObject().getAsJsonObject("blockBounds")));
        List<BlockPoint> starts=new ArrayList<>();
        for(JsonElement e:array(anchors,"streetBands")) {
            JsonObject r=e.getAsJsonObject();
            if(r.has("start") && r.has("end")) {
                BlockPoint a=point(r.getAsJsonObject("start")),b=point(r.getAsJsonObject("end"));
                starts.add(a);starts.add(b); starts.add(new BlockPoint((a.x()+b.x())/2,(a.z()+b.z())/2));
            }
        }
        starts.removeIf(p -> !insideWall(p,wall));
        int half=wall.has("wallCorridorHalfWidthBlocks")?wall.get("wallCorridorHalfWidthBlocks").getAsInt():8;
        // Include the full corridor, even when its catalog width exceeds the requested minimum.
        for(JsonElement e:array(wall,"wallLine")) {
            JsonObject line=e.getAsJsonObject(); BlockBounds b=bounds(line.getAsJsonObject("blockBounds"));
            half=Math.max(half,(Math.min(b.widthBlocks(),b.heightBlocks())-1)/2);
        }
        final int distance=half+5;
        for(InterCityNetwork.Link link:network) {
            if(!link.touches(cityId)) continue;
            BlockPoint target=link.other(cityId).center(); List<Candidate> candidates=new ArrayList<>();
            for(JsonElement e:array(wall,"wallLine")) {
                JsonObject line=e.getAsJsonObject(); BlockPoint a=point(line.getAsJsonObject("from")),b=point(line.getAsJsonObject("to"));
                String side=line.get("sideHint").getAsString().toLowerCase(Locale.ROOT);
                int nx=side.contains("east")?1:side.contains("west")?-1:0;
                int nz=side.contains("south")?1:side.contains("north")?-1:0;
                if(nx==0 && nz==0) continue;
                int length=Math.abs(a.x()-b.x())+Math.abs(a.z()-b.z());
                for(int offset=20;offset<=length-20;offset+=8) {
                    BlockPoint g=new BlockPoint(a.x()+Integer.signum(b.x()-a.x())*offset,a.z()+Integer.signum(b.z()-a.z())*offset);
                    BlockPoint in=new BlockPoint(g.x()-nx*distance,g.z()-nz*distance),out=new BlockPoint(g.x()+nx*distance,g.z()+nz*distance);
                    if(!insideWall(in,wall)||insideWall(out,wall))continue;
                    if(!domain.contains(out.x()+nx*2,out.z()+nz*2)) continue;
                    double score=Math.hypot(target.x()-out.x(),target.z()-out.z());
                    if((target.x()-g.x())*nx+(target.z()-g.z())*nz<=0) score+=10000;
                    candidates.add(new Candidate(in,out,g,score));
                }
            }
            candidates.sort(Comparator.comparingDouble(Candidate::score).thenComparingInt(c->c.gate().x()).thenComparingInt(c->c.gate().z()));
            boolean found=false;
            for(Candidate c:candidates.stream().limit(16).toList()) {
                // Several destinations in the same direction may share one city gate.
                JsonObject shared=null;
                for(JsonElement e:portals)if(point(e.getAsJsonObject().getAsJsonObject("point")).equals(c.outside())) {shared=e.getAsJsonObject();break;}
                if(shared!=null) {
                    JsonObject portal=shared.deepCopy();portal.addProperty("linkId",link.id());portal.addProperty("otherCityId",link.other(cityId).id());
                    portals.add(portal);found=true;break;
                }
                BlockBounds opening=bandBounds(c.inside(),c.outside(),3);
                BoundedRoadPathfinder.Terrain terrain=new BoundedRoadPathfinder.Terrain() {
                    public double height(int x,int z){return 0;}
                    public boolean blocked(int x,int z){return buildings.stream().anyMatch(b->b.contains(x,z))
                            || corridors.stream().anyMatch(b->b.contains(x,z))&&!opening.contains(x,z);}
                };
                // A straight crossing prevents turns or secondary gates within the wall belt.
                if(!new BoundedRoadPathfinder().find(c.inside(),c.outside(),domain,1,3,128,terrain).success()) continue;
                List<BlockPoint> ordered=starts.stream().distinct().sorted(Comparator.comparingDouble(p->Math.hypot(p.x()-c.inside().x(),p.z()-c.inside().z()))).limit(4).toList();
                for(BlockPoint start:ordered) {
                    var route=new BoundedRoadPathfinder().find(start,c.inside(),domain,1,3,20000,terrain);
                    if(!route.success()) continue;
                    List<BlockPoint> path=new ArrayList<>(route.path()); path.add(c.outside());
                    List<BlockPoint> simplified=simplify(path);
                    for(int i=1;i<simplified.size();i++) roads.add(road(link.id()+"/"+cityId+"/"+i,link.id(),simplified.get(i-1),simplified.get(i)));
                    JsonObject portal=new JsonObject();portal.addProperty("linkId",link.id());portal.addProperty("otherCityId",link.other(cityId).id());
                    portal.add("point",c.outside().asJson());portal.add("gate",c.gate().asJson()); portals.add(portal);
                    found=true;break;
                }
                if(found)break;
            }
            if(!found) {JsonObject failure=new JsonObject();failure.addProperty("linkId",link.id());failure.addProperty("reason","NO_SAFE_CITY_EXIT");failures.add(failure);}
        }
        return result;
    }
    public static JsonObject append(JsonObject original,JsonObject exits) {
        JsonObject copy=original.deepCopy(); JsonArray bands=array(copy,"streetBands").deepCopy();
        for(JsonElement e:array(exits,"streetBands")) bands.add(e.deepCopy());
        copy.add("streetBands",bands); copy.add("interCityExitPlan",exits.deepCopy());return copy;
    }
    private static boolean insideWall(BlockPoint p,JsonObject wall) {
        int crossings=0;
        for(JsonElement e:array(wall,"wallLine")) {
            JsonObject line=e.getAsJsonObject();BlockPoint a=point(line.getAsJsonObject("from")),b=point(line.getAsJsonObject("to"));
            if(a.x()==b.x() && a.x()>p.x() && p.z()>=Math.min(a.z(),b.z()) && p.z()<Math.max(a.z(),b.z()))crossings++;
        }
        return crossings%2==1;
    }
    public static JsonObject withoutDerived(JsonObject source) {
        JsonObject copy=source.deepCopy();
        if(!copy.has("interCityExitPlan")) return copy;
        Set<String> ids=new HashSet<>();for(JsonElement e:array(copy.getAsJsonObject("interCityExitPlan"),"streetBands"))ids.add(e.getAsJsonObject().get("streetBandId").getAsString());
        JsonArray bands=new JsonArray();for(JsonElement e:array(copy,"streetBands"))if(!ids.contains(e.getAsJsonObject().get("streetBandId").getAsString()))bands.add(e);
        copy.add("streetBands",bands);copy.remove("interCityExitPlan");return copy;
    }
    public static List<BlockPoint> simplify(List<BlockPoint> path) {
        if(path.size()<3)return List.copyOf(path);List<BlockPoint> out=new ArrayList<>();out.add(path.get(0));
        for(int i=1;i<path.size()-1;i++) {BlockPoint a=out.get(out.size()-1),b=path.get(i),c=path.get(i+1);
            if((a.x()==b.x()&&b.x()==c.x()) || (a.z()==b.z()&&b.z()==c.z()))continue;out.add(b);}
        out.add(path.get(path.size()-1));return List.copyOf(out);
    }
    public static JsonObject road(String id,String network,BlockPoint a,BlockPoint b) {
        JsonObject r=new JsonObject();r.addProperty("streetBandId",id);r.addProperty("roadNetworkId",network);r.addProperty("roadKind","CITY_MAIN_ROAD");
        r.addProperty("widthBlocks",5);r.addProperty("crossSectionProfile","STAIR_SLAB_STAIR");
        r.add("start",a.asJson());r.add("end",b.asJson());r.add("bounds",json(bandBounds(a,b,2)));return r;
    }
    public static BlockBounds bandBounds(BlockPoint a,BlockPoint b,int half){return new BlockBounds(Math.min(a.x(),b.x())-half,Math.min(a.z(),b.z())-half,Math.max(a.x(),b.x())+half,Math.max(a.z(),b.z())+half);}
    public static JsonArray array(JsonObject o,String key){return o.has(key)?o.getAsJsonArray(key):new JsonArray();}
    public static BlockPoint point(JsonObject o){return new BlockPoint(o.get("x").getAsInt(),o.get("z").getAsInt());}
    public static BlockBounds bounds(JsonObject o){return new BlockBounds(o.get("minX").getAsInt(),o.get("minZ").getAsInt(),o.get("maxX").getAsInt(),o.get("maxZ").getAsInt());}
    public static JsonObject json(BlockBounds b){JsonObject o=new JsonObject();o.addProperty("minX",b.minX());o.addProperty("minZ",b.minZ());o.addProperty("maxX",b.maxX());o.addProperty("maxZ",b.maxZ());return o;}
}
