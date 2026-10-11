package com.rinsing.geomantia.systems.city.domain.blueprint;

import com.google.gson.*;
import java.util.*;

/** Optional declarative public realm; copied at its boundary to keep blueprint identity immutable. */
public final class CityPublicRealmSpec {
    private final JsonObject value;
    public CityPublicRealmSpec(JsonObject value) {
        fields(value,"schema","catalog","seed","spaces","greenery","fringe","externalAccess");
        if(!"city_public_realm.v1".equals(text(value,"schema","")))throw new IllegalArgumentException("PUBLIC_REALM_SCHEMA");
        if(!"academy".equals(text(value,"catalog","")))throw new IllegalArgumentException("PUBLIC_REALM_CATALOG");
        if(value.toString().length()>200_000)throw new IllegalArgumentException("PUBLIC_REALM_SPEC_TOO_LARGE");
        this.value=value.deepCopy();
    }
    public JsonObject toJson(){return value.deepCopy();}
    @Override public boolean equals(Object other){return other instanceof CityPublicRealmSpec s&&value.equals(s.value);}
    @Override public int hashCode(){return value.hashCode();}
    public static void fields(JsonObject o,String... names){Set<String> allowed=Set.of(names);for(String k:o.keySet())if(!allowed.contains(k))throw new IllegalArgumentException("PUBLIC_REALM_UNKNOWN_FIELD:"+k);}
    public static int integer(JsonObject o,String k,int fallback,int min,int max){
        if(!o.has(k))return fallback;double v=o.get(k).getAsDouble();if(!Double.isFinite(v)||v!=Math.rint(v)||v<min||v>max)throw new IllegalArgumentException("PUBLIC_REALM_INTEGER:"+k);return (int)v;
    }
    public static double number(JsonObject o,String k,double fallback,double min,double max){double v=o.has(k)?o.get(k).getAsDouble():fallback;if(!Double.isFinite(v)||v<min||v>max)throw new IllegalArgumentException("PUBLIC_REALM_NUMBER:"+k);return v;}
    public static String text(JsonObject o,String k,String fallback){return o.has(k)?o.get(k).getAsString():fallback;}
    public static JsonArray array(JsonObject o,String k){return o.has(k)?o.getAsJsonArray(k):new JsonArray();}
    public static JsonObject object(JsonObject o,String k){return o.has(k)?o.getAsJsonObject(k):new JsonObject();}
}
