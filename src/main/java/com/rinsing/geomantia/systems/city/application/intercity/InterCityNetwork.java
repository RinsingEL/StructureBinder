package com.rinsing.geomantia.systems.city.application.intercity;

import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Program-owned minimum spanning forest. No cross-realm edges or discovered structures. */
public final class InterCityNetwork {
    public record City(String id, String realm, BlockPoint center) {}
    public record Link(String id, City a, City b) {
        public City other(String cityId) { return a.id().equals(cityId) ? b : a; }
        public boolean touches(String cityId) { return a.id().equals(cityId) || b.id().equals(cityId); }
    }
    public List<Link> plan(List<City> input) {
        List<City> cities = input.stream().sorted(Comparator.comparing(City::id)).toList();
        if (cities.stream().map(City::id).distinct().count() != cities.size())
            throw new IllegalArgumentException("INTERCITY_DUPLICATE_CITY");
        List<Link> candidates = new ArrayList<>();
        for (int i=0;i<cities.size();i++) for (int j=i+1;j<cities.size();j++) {
            City a=cities.get(i), b=cities.get(j);
            if (a.realm().isBlank() || !a.realm().equals(b.realm())) continue;
            String id="intercity_"+UUID.nameUUIDFromBytes((a.realm()+"\n"+a.id()+"\n"+b.id()).getBytes(StandardCharsets.UTF_8));
            candidates.add(new Link(id,a,b));
        }
        candidates.sort(Comparator.comparingDouble((Link e)->distance(e.a().center(),e.b().center())).thenComparing(Link::id));
        Map<String,String> roots=new HashMap<>(); cities.forEach(c->roots.put(c.id(),c.id()));
        List<Link> result=new ArrayList<>();
        for (Link link:candidates) {
            String a=root(roots,link.a().id()),b=root(roots,link.b().id());
            if (!a.equals(b)) { roots.put(a,b); result.add(link); }
        }
        return List.copyOf(result);
    }
    private static String root(Map<String,String> roots,String id) {
        while(!id.equals(roots.get(id))) id=roots.get(id); return id;
    }
    private static double distance(BlockPoint a,BlockPoint b) { return Math.hypot((double)a.x()-b.x(),(double)a.z()-b.z()); }
}
