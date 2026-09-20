package com.rinsing.geomantia.systems.city.application.intercity;

import com.rinsing.geomantia.systems.city.domain.model.*;
import com.rinsing.geomantia.thirdparty.roadweaver.BoundedRoadPathfinder;
import java.util.*;

/** One explicitly selected link, bounded search, no chunk access or placement side effects. */
public final class InterCityRoutePlanner {
    public record Sample(double height, boolean water, boolean ocean) {}
    public interface Terrain { Sample sample(int x,int z); boolean blocked(int x,int z); }
    public record Route(String status,List<List<BlockPoint>> sections,int expanded) {}
    public static final int MAX_LENGTH=32768, SEARCH_BUDGET=30000, MAX_BRIDGE_LENGTH=64;
    public Route plan(BlockPoint start,BlockPoint end,Terrain terrain) {
        if(Math.abs((long)start.x()-end.x())+Math.abs((long)start.z()-end.z())>MAX_LENGTH)
            return new Route("DISTANCE_LIMIT",List.of(),0);
        BlockBounds domain=CityExitRoadPlanner.bandBounds(start,end,512);
        var land=find(start,end,domain,terrain,false);
        var search=land.success()?land:find(start,end,domain,terrain,true);
        if(!search.success())return new Route(search.reason(),List.of(),search.expanded());
        List<BlockPoint> path=densify(search.path());
        if(path.size()>MAX_LENGTH)return new Route("DISTANCE_LIMIT",List.of(),search.expanded());
        int first=-1,last=-1;
        boolean[] waters=new boolean[path.size()];
        for(int i=0;i<path.size();i++) {
            BlockPoint p=path.get(i);
            boolean ocean=false,wet=false;
            for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++) {
                Sample across=terrain.sample(p.x()+dx,p.z()+dz);ocean|=across.ocean();wet|=across.water();
            }
            if(ocean) {if(first<0)first=i;last=i;}
            waters[i]=wet;
        }
        for(int i=0;i<path.size();i++) {
            if(waters[i]) {
                int from=i;
                while(i+1<path.size() && waters[i+1])i++;
                // Wide water without an ocean biome is also a shore, never an unbounded bridge.
                if(i-from+1>MAX_BRIDGE_LENGTH) {first=first<0?from:Math.min(first,from);last=Math.max(last,i);}
            }
        }
        if(first<0)return new Route("LAND_CONNECTED",List.of(CityExitRoadPlanner.simplify(path)),search.expanded());
        // Only retain the two city-facing shores; no roads on intermediate islands and no ferry claim.
        List<List<BlockPoint>> sections=new ArrayList<>();
        int prefix=Math.max(0,first-4),suffix=Math.min(path.size(),last+5);
        while(prefix>0 && !dry(path.get(prefix-1),terrain))prefix--;
        while(suffix<path.size() && !dry(path.get(suffix),terrain))suffix++;
        if(prefix>1)sections.add(CityExitRoadPlanner.simplify(path.subList(0,prefix)));
        if(path.size()-suffix>1)sections.add(CityExitRoadPlanner.simplify(path.subList(suffix,path.size())));
        return new Route(sections.isEmpty()?"NO_DRY_SHORE":"SHORE_TERMINATED",List.copyOf(sections),search.expanded());
    }
    private static boolean dry(BlockPoint p,Terrain terrain) {
        for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++)if(terrain.sample(p.x()+dx,p.z()+dz).water())return false;return true;
    }
    private static BoundedRoadPathfinder.Result find(BlockPoint a,BlockPoint b,BlockBounds domain,Terrain input,boolean oceans) {
        // Reserve sampling capacity for the coastal fallback when an ocean divides the search domain.
        return new BoundedRoadPathfinder().find(a,b,domain,16,3,oceans?SEARCH_BUDGET:3000,new BoundedRoadPathfinder.Terrain(){
            public double height(int x,int z){return input.sample(x,z).height();}
            public boolean blocked(int x,int z){return input.blocked(x,z)||!oceans&&input.sample(x,z).ocean();}
            public double penalty(int x,int z){Sample s=input.sample(x,z);return s.ocean()?64:s.water()?16:0;}
        });
    }
    public static List<BlockPoint> densify(List<BlockPoint> corners) {
        List<BlockPoint> result=new ArrayList<>();if(corners.isEmpty())return result;result.add(corners.get(0));
        for(int i=1;i<corners.size();i++) {
            BlockPoint a=corners.get(i-1),b=corners.get(i);
            if(a.x()!=b.x()&&a.z()!=b.z())throw new IllegalArgumentException("INTERCITY_NON_CARDINAL_ROUTE");
            int dx=Integer.compare(b.x(),a.x()),dz=Integer.compare(b.z(),a.z()),length=Math.abs(b.x()-a.x())+Math.abs(b.z()-a.z());
            for(int d=1;d<=length;d++)result.add(new BlockPoint(a.x()+d*dx,a.z()+d*dz));
        }
        return result;
    }
}
