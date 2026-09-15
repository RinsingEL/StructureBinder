package com.rinsing.geomantia.systems.city.domain.blueprint;

import com.google.gson.*;
import java.util.*;

/** Optional material overrides only. Keys describe existing placement slots, never geometry. */
public record CitySurfaceMaterials(Map<String,String> defaults,
        Map<String,Map<String,String>> groups, Map<String,Map<String,String>> roads,
        Map<String,Map<String,String>> landscapes) {
    public static final Set<String> CITY_SLOTS = Set.of("ground", "roadSurface", "roadStair", "roadCurb",
            "roadBase", "retainingWall", "deck", "fill", "pier", "railing", "lowWall", "hedge",
            "accessSurface", "accessStair", "bridgeSurface", "bridgeRail");
    public static final Set<String> ROAD_KINDS = Set.of("CITY_MAIN_ROAD","COMPACT_ALLEY","CITY_BRIDGE",
            "LINEAR_STREET_BAND","GRID_MAIN_STREET","GRID_ROW_LANE","GRID_COLUMN_LANE",
            "CENTER_AXIS_PRIMARY","CENTER_AXIS_NORTH","CENTER_AXIS_SOUTH","CENTER_AXIS_EAST","CENTER_AXIS_WEST",
            "COURTYARD_RING_NORTH","COURTYARD_RING_EAST","COURTYARD_RING_WEST","COURTYARD_RING_SOUTH_WEST",
            "COURTYARD_RING_SOUTH_EAST","COURTYARD_GATE","EXPANSION_UNIT_STREET","ENTRANCE_SHORT_ALLEY");
    public static final Set<String> ROAD_SLOTS = Set.of("roadSurface", "roadStair", "roadCurb", "roadBase",
            "bridgeSurface", "bridgeRail", "pier");
    public static final Set<String> LANDSCAPE_SLOTS = Set.of("surfaceBlockId", "cropBlockId", "channelBankBlockId",
            "channelWaterBlockId", "channelBankOverlayBlockId", "boundaryBlockId");
    public CitySurfaceMaterials {
        defaults = sorted(defaults); groups = nested(groups); roads = nested(roads); landscapes = nested(landscapes);
    }
    public static CitySurfaceMaterials empty() { return new CitySurfaceMaterials(Map.of(),Map.of(),Map.of(),Map.of()); }
    public boolean isEmpty() { return defaults.isEmpty() && groups.isEmpty() && roads.isEmpty() && landscapes.isEmpty(); }
    public String resolve(String slot, String group, String roadKind, String fallback) {
        return roads.getOrDefault(roadKind, Map.of()).getOrDefault(slot,
                groups.getOrDefault(group,Map.of()).getOrDefault(slot, defaults.getOrDefault(slot,fallback)));
    }
    private static Map<String,String> sorted(Map<String,String> map) {
        return Collections.unmodifiableMap(new TreeMap<>(map == null ? Map.of() : map));
    }
    private static Map<String,Map<String,String>> nested(Map<String,Map<String,String>> map) {
        TreeMap<String,Map<String,String>> result = new TreeMap<>();
        if(map != null) map.forEach((k,v)->result.put(k,sorted(v)));
        return Collections.unmodifiableMap(result);
    }
    public static CitySurfaceMaterials read(JsonObject json) {
        if(json == null) return empty();
        for(String key:json.keySet()) if(!Set.of("defaults","groups","roads","landscapes").contains(key))
            throw new IllegalArgumentException("surfaceMaterials."+key+": unknown section; use defaults, groups, roads or landscapes");
        return new CitySurfaceMaterials(readSlots(json.get("defaults"),CITY_SLOTS,"defaults"),
                readNested(json.get("groups"),CITY_SLOTS,"groups"),readNested(json.get("roads"),ROAD_SLOTS,"roads"),
                readNested(json.get("landscapes"),LANDSCAPE_SLOTS,"landscapes"));
    }
    private static Map<String,Map<String,String>> readNested(JsonElement value,Set<String> allowed,String path) {
        Map<String,Map<String,String>> result=new TreeMap<>();
        if(value==null) return result;
        if(!value.isJsonObject()) throw new IllegalArgumentException("surfaceMaterials."+path+": expected object keyed by existing id");
        value.getAsJsonObject().entrySet().forEach(e->result.put(e.getKey(),readSlots(e.getValue(),allowed,path+"."+e.getKey())));
        return result;
    }
    private static Map<String,String> readSlots(JsonElement value,Set<String> allowed,String path) {
        Map<String,String> result=new TreeMap<>();
        if(value==null) return result;
        if(!value.isJsonObject()) throw new IllegalArgumentException("surfaceMaterials."+path+": expected slot-to-block-id object");
        value.getAsJsonObject().entrySet().forEach(e->{
            if(!allowed.contains(e.getKey())) throw new IllegalArgumentException("surfaceMaterials."+path+"."+e.getKey()+": unknown slot; allowed="+new TreeSet<>(allowed));
            if(!e.getValue().isJsonPrimitive() || !e.getValue().getAsJsonPrimitive().isString()
                    || !e.getValue().getAsString().matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))
                throw new IllegalArgumentException("surfaceMaterials."+path+"."+e.getKey()+": use a registered namespace:block id, without block states");
            result.put(e.getKey(),e.getValue().getAsString());
        }); return result;
    }
    public JsonObject toJson() { return new Gson().toJsonTree(this).getAsJsonObject(); }
}
