package com.rinsing.geomantia.adventure;

import com.rinsing.geomantia.api.regions.WorldRegionSnapshot.Centre;
import java.util.*;

/** Exact shared Voronoi-edge test via intervals on pair bisectors. Point contacts are excluded. */
public final class ContinentTiers {
    private ContinentTiers() {}
    public static Map<String,Integer> compute(Map<String,Centre> centres,String initial,int maximum) {
        if(maximum<1 || !centres.containsKey(initial)) throw new IllegalArgumentException("Invalid tier origin/maximum");
        List<String> ids=new ArrayList<>(new TreeSet<>(centres.keySet()));
        Map<String,List<String>> neighbors=new HashMap<>(); ids.forEach(id->neighbors.put(id,new ArrayList<>()));
        for(int i=0;i<ids.size();i++) for(int j=i+1;j<ids.size();j++) {
            var a=centres.get(ids.get(i));var b=centres.get(ids.get(j));
            if(a.equals(b)) throw new IllegalArgumentException("Coincident continent centres: "+ids.get(i)+", "+ids.get(j));
            if(adjacent(a,b,centres.values())) { neighbors.get(ids.get(i)).add(ids.get(j));neighbors.get(ids.get(j)).add(ids.get(i)); }
        }
        Map<String,Integer> distance=new TreeMap<>();distance.put(initial,0);
        ArrayDeque<String> todo=new ArrayDeque<>();todo.add(initial);
        while(!todo.isEmpty()) { String id=todo.remove(); for(String next:neighbors.get(id)) if(!distance.containsKey(next)) {
            distance.put(next,distance.get(id)+1);todo.add(next);
        }}
        if(distance.size()!=centres.size()) throw new IllegalStateException("Disconnected continent adjacency");
        distance.replaceAll((id,d)->Math.min(maximum,d+1));return Map.copyOf(distance);
    }
    static boolean adjacent(Centre a,Centre b,Collection<Centre> all) {
        double dx=b.x()-a.x(),dz=b.z()-a.z(),length=Math.hypot(dx,dz);
        double mx=dx/2,mz=dz/2,ux=-dz/length,uz=dx/length;
        double low=Double.NEGATIVE_INFINITY,high=Double.POSITIVE_INFINITY;
        for(var c:all) {
            if(c==a || c==b || c.equals(a) || c.equals(b)) continue;
            double cx=c.x()-a.x(),cz=c.z()-a.z();
            double coefficient=2*(ux*cx+uz*cz), rhs=cx*cx+cz*cz-2*(mx*cx+mz*cz);
            if(Math.abs(coefficient)<1e-10) { if(rhs < -1e-7) return false; }
            else if(coefficient>0) high=Math.min(high,rhs/coefficient); else low=Math.max(low,rhs/coefficient);
            if(high-low<=1e-7) return false;
        }
        return high-low>1e-7;
    }
}
