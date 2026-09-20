package com.rinsing.geomantia.systems.city.domain.blueprint;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Authoring groups and complete material recipes. Geometry is deliberately not configurable here. */
public final class CitySurfaceAppearanceCatalog {
    private static final JsonObject DATA = load();
    private CitySurfaceAppearanceCatalog() { }

    private static JsonObject load() {
        try (var in=CitySurfaceAppearanceCatalog.class.getResourceAsStream("/geomantia/city_surface_appearances.json")) {
            if(in==null)throw new IllegalStateException("Missing city surface appearances");
            return JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
        } catch(java.io.IOException ex) {throw new IllegalStateException("Cannot load city surface appearances",ex);}
    }
    public static JsonObject guide() {return DATA.deepCopy();}
    public static boolean isGroup(String name) {return DATA.has(name);}
    public static Set<String> slots(String group) {
        Set<String> slots=new TreeSet<>();
        DATA.getAsJsonObject(group).getAsJsonArray("slots").forEach(e->slots.add(e.getAsString()));
        return Collections.unmodifiableSet(slots);
    }
    public static Set<String> allSlots() {
        Set<String> slots=new TreeSet<>();DATA.keySet().forEach(g->slots.addAll(slots(g)));
        return Collections.unmodifiableSet(slots);
    }
    public static Map<String,String> preset(String group,String name,String path) {
        var presets=DATA.getAsJsonObject(group).getAsJsonObject("presets");
        if(!presets.has(name))throw new IllegalArgumentException(path+": unknown preset; choose "+presets.keySet());
        Map<String,String> result=new TreeMap<>();
        presets.getAsJsonObject(name).entrySet().forEach(e->result.put(e.getKey(),e.getValue().getAsString()));
        return result;
    }
    public static JsonObject grouped(Map<String,String> slots) {
        JsonObject result=new JsonObject();
        for(String group:DATA.keySet()) {
            JsonObject materials=new JsonObject();
            for(String slot:slots(group))if(slots.containsKey(slot))materials.addProperty(slot,slots.get(slot));
            if(materials.size()>0) {JsonObject value=new JsonObject();value.add("materials",materials);result.add(group,value);}
        }
        return result;
    }
}
