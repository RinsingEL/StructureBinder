package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import java.util.*;

/** Frozen local elevations. Cross-sections, towers and gates are level; neighbouring sections
 * differ by at most one block. Mountains may intersect masonry instead of raising the whole ring. */
public final class CityWallTerrainPlanner {
    public static final String POLICY = "terrain_following_sections";
    private record Visit(int id, int height) {}
    private record Area(int minX,int minZ,int maxX,int maxZ) {
        List<BlockPoint> points() {
            List<BlockPoint> out=new ArrayList<>();
            for(int z=minZ;z<=maxZ;z++) for(int x=minX;x<=maxX;x++) out.add(new BlockPoint(x,z));
            return out;
        }
    }
    public JsonObject plan(JsonObject wall, JsonArray samples) {
        Map<BlockPoint,JsonObject> terrain=new LinkedHashMap<>();
        for(var e:samples) { var c=e.getAsJsonObject(); terrain.put(new BlockPoint(n(c,"x"),n(c,"z")),c); }
        if(terrain.isEmpty()) throw new IllegalArgumentException("WALL_SURFACE_REQUIRED");
        Map<BlockPoint,Integer> ids=new LinkedHashMap<>();
        for(var p:terrain.keySet()) ids.put(p,ids.size());
        int[] parent=new int[ids.size()]; for(int i=0;i<parent.length;i++) parent[i]=i;
        Map<BlockPoint,List<Integer>> desired=new HashMap<>();
        Map<String,List<BlockPoint>> gates=new LinkedHashMap<>();
        for(var e:wall.getAsJsonArray("wallUnits")) {
            var unit=e.getAsJsonObject(); Area area=area(unit); var points=area.points();
            require(points,ids);
            List<Integer> values=points.stream().map(p->n(terrain.get(p),"surfaceY")).sorted().toList();
            int median=values.get(values.size()/2);
            for(var p:points) desired.computeIfAbsent(p,k->new ArrayList<>()).add(median);
            boolean horizontal="X".equals(unit.get("wallAxis").getAsString());
            Map<Integer,List<BlockPoint>> slices=new LinkedHashMap<>();
            for(var p:points) slices.computeIfAbsent(horizontal?p.x():p.z(),k->new ArrayList<>()).add(p);
            for(var slice:slices.values()) join(slice,ids,parent);
            if("gate_gap".equals(unit.get("unitType").getAsString()))
                gates.computeIfAbsent(unit.get("gateSlotId").getAsString(),k->new ArrayList<>()).addAll(points);
        }
        for(var gate:gates.values()) join(gate,ids,parent);
        for(var e:wall.getAsJsonArray("wallNodes")) { var points=area(e.getAsJsonObject()).points();require(points,ids);join(points,ids,parent); }
        Map<Integer,List<BlockPoint>> groups=new LinkedHashMap<>();
        for(var entry:ids.entrySet()) groups.computeIfAbsent(find(parent,entry.getValue()),k->new ArrayList<>()).add(entry.getKey());
        Map<Integer,Set<Integer>> edges=new HashMap<>();
        for(var p:ids.keySet()) for(var q:List.of(new BlockPoint(p.x()+1,p.z()),new BlockPoint(p.x(),p.z()+1))) {
            if(!ids.containsKey(q)) continue;
            int a=find(parent,ids.get(p)),b=find(parent,ids.get(q)); if(a==b)continue;
            edges.computeIfAbsent(a,k->new HashSet<>()).add(b);edges.computeIfAbsent(b,k->new HashSet<>()).add(a);
        }
        Map<Integer,Integer> heights=new HashMap<>();
        PriorityQueue<Visit> queue=new PriorityQueue<>(Comparator.comparingInt(Visit::height).thenComparingInt(Visit::id));
        for(var entry:groups.entrySet()) {
            List<Integer> values=new ArrayList<>();
            for(var p:entry.getValue()) values.addAll(desired.getOrDefault(p,List.of(n(terrain.get(p),"surfaceY"))));
            Collections.sort(values);int h=values.get(values.size()/2);
            heights.put(entry.getKey(),h);queue.add(new Visit(entry.getKey(),h));
        }
        // Lower steep rises into the mountain; never raise a complete city to its highest point.
        while(!queue.isEmpty()) {
            var v=queue.remove();if(heights.get(v.id)!=v.height)continue;
            for(int next:edges.getOrDefault(v.id,Set.of())) if(heights.get(next)>v.height+1) {
                heights.put(next,v.height+1);queue.add(new Visit(next,v.height+1));
            }
        }
        // Gate openings remain tied to their own road crossing ground, with four blocks headroom.
        Map<Integer,Integer> lower=new HashMap<>();
        PriorityQueue<Visit> raised=new PriorityQueue<>(Comparator.comparingInt(Visit::height).reversed().thenComparingInt(Visit::id));
        for(var gate:gates.values()) {
            int id=find(parent,ids.get(gate.get(0)));
            int h=gate.stream().mapToInt(p->n(terrain.get(p),"surfaceY")).max().orElseThrow()-5;
            if(h>lower.getOrDefault(id,Integer.MIN_VALUE)) {lower.put(id,h);raised.add(new Visit(id,h));}
        }
        while(!raised.isEmpty()) {
            var v=raised.remove();if(lower.get(v.id)!=v.height)continue;
            for(int next:edges.getOrDefault(v.id,Set.of())) if(v.height-1>heights.get(next)
                    && v.height-1>lower.getOrDefault(next,Integer.MIN_VALUE)) {
                lower.put(next,v.height-1);raised.add(new Visit(next,v.height-1));
            }
        }
        lower.forEach((id,h)->heights.merge(id,h,Math::max));
        JsonArray columns=new JsonArray();int min=Integer.MAX_VALUE,max=Integer.MIN_VALUE,transitions=0,filled=0,embedded=0;
        for(var p:ids.keySet()) {
            int h=heights.get(find(parent,ids.get(p)));var c=terrain.get(p).deepCopy();
            c.addProperty("baseY",h);c.addProperty("walkwayFloorY",h+9);
            int surface=n(c,"surfaceY");String mode=surface<h?"retaining_foundation":surface>h+3?"mountain_embed":"wall";
            if(surface<h)filled++;if(surface>h+3)embedded++;
            c.addProperty("terrainMode",mode);columns.add(c);min=Math.min(min,h);max=Math.max(max,h);
        }
        for(var e:edges.entrySet()) for(int b:e.getValue()) if(e.getKey()<b) {
            int delta=Math.abs(heights.get(e.getKey())-heights.get(b));
            if(delta>1) throw new IllegalStateException("WALL_HEIGHT_CONNECTION_INVALID");
            if(delta==1)transitions++;
        }
        for(String key:List.of("wallUnits","wallNodes")) for(var e:wall.getAsJsonArray(key)) {
            var module=e.getAsJsonObject();var points=area(module).points();
            int lo=points.stream().mapToInt(p->heights.get(find(parent,ids.get(p)))).min().orElseThrow();
            int hi=points.stream().mapToInt(p->heights.get(find(parent,ids.get(p)))).max().orElseThrow();
            module.addProperty("minBaseY",lo);module.addProperty("maxBaseY",hi);
            module.addProperty("baseY",lo);module.addProperty("heightMode",POLICY);
            module.addProperty("targetY",lo);
            var localSurfaces=points.stream().mapToInt(p->n(terrain.get(p),"surfaceY")).sorted().toArray();
            module.addProperty("surfaceMedianY",localSurfaces[localSurfaces.length/2]);
            module.addProperty("terrainMode",hi>lo?"stair_transition":points.stream().anyMatch(p->n(terrain.get(p),"surfaceY")>lo+3)?"mountain_embed":
                    points.stream().anyMatch(p->n(terrain.get(p),"surfaceY")<lo)?"retaining_foundation":"wall");
        }
        JsonObject profile=new JsonObject();profile.addProperty("policy",POLICY);profile.addProperty("ok",true);
        profile.addProperty("reasonCode","WALL_LOCAL_HEIGHTS_RESOLVED");profile.addProperty("minBaseY",min);profile.addProperty("maxBaseY",max);
        profile.addProperty("minSurfaceY",terrain.values().stream().mapToInt(c->n(c,"surfaceY")).min().orElseThrow());
        profile.addProperty("maxSurfaceY",terrain.values().stream().mapToInt(c->n(c,"surfaceY")).max().orElseThrow());
        profile.addProperty("transitionCount",transitions);profile.addProperty("foundationColumnCount",filled);profile.addProperty("embeddedColumnCount",embedded);
        profile.add("surfaceColumns",columns);return profile;
    }
    private static int find(int[] p,int x){while(p[x]!=x){p[x]=p[p[x]];x=p[x];}return x;}
    private static void join(List<BlockPoint> points,Map<BlockPoint,Integer> ids,int[] parent){int first=find(parent,ids.get(points.get(0)));for(var p:points)parent[find(parent,ids.get(p))]=first;}
    private static void require(List<BlockPoint> points,Map<BlockPoint,Integer> ids){for(var p:points)if(!ids.containsKey(p))throw new IllegalArgumentException("WALL_SURFACE_PROFILE_INCOMPLETE");}
    private static int n(JsonObject o,String k){return o.get(k).getAsInt();}
    private static Area area(JsonObject m){var b=m.getAsJsonObject("blockBounds");return new Area(n(b,"minX"),n(b,"minZ"),n(b,"maxX"),n(b,"maxZ"));}
}
