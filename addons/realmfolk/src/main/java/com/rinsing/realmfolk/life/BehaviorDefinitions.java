package com.rinsing.realmfolk.life;

import com.google.gson.*;
import java.util.*;
import java.util.function.BiConsumer;

/** Declarative sequences. Completed steps retain their effects until the whole behavior stops. */
public final class BehaviorDefinitions {
    public record Step(String action,JsonObject parameters) {}
    public record Behavior(String id,Set<String> kinds,List<Step> steps) {}
    private BehaviorDefinitions() {}
    public static Map<String,Behavior> parse(JsonObject input,BiConsumer<String,JsonObject> validate) {
        fields(input,Set.of("schema","behaviors"),"behavior catalogue");
        if(!input.has("schema")||input.get("schema").getAsInt()!=1)throw bad("schema must be 1");
        var definitions=input.getAsJsonObject("behaviors");
        if(definitions==null||definitions.size()>256)throw bad("behaviors must contain at most 256 definitions");
        var result=new LinkedHashMap<String,Behavior>();
        for(var entry:definitions.entrySet()) {
            String id=id(entry.getKey());JsonObject value=entry.getValue().getAsJsonObject();
            fields(value,Set.of("kinds","steps"),id);
            var kinds=new LinkedHashSet<String>();
            for(var kind:value.getAsJsonArray("kinds")) {
                String k=kind.getAsString();if(!Set.of("bed","seat","work","meeting").contains(k))throw bad(id+": unknown kind "+k);kinds.add(k);
            }
            if(kinds.isEmpty())throw bad(id+": kinds must not be empty");
            var steps=new ArrayList<Step>();var array=value.getAsJsonArray("steps");
            if(array==null||array.isEmpty()||array.size()>16)throw bad(id+": requires 1..16 steps");
            for(var element:array) {
                JsonObject step=element.getAsJsonObject();fields(step,Set.of("action","parameters"),id+" step");
                String action=id(step.get("action").getAsString());
                JsonObject params=step.has("parameters")?step.getAsJsonObject("parameters").deepCopy():new JsonObject();
                validate.accept(action,params);steps.add(new Step(action,params));
            }
            result.put(id,new Behavior(id,Set.copyOf(kinds),List.copyOf(steps)));
        }
        return Collections.unmodifiableMap(result);
    }
    public static String id(String text) {
        if(text==null||!text.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw bad("Expected namespaced id: "+text);return text;
    }
    public static void fields(JsonObject object,Set<String> allowed,String at) {
        for(String key:object.keySet())if(!allowed.contains(key))throw bad(at+": unknown field "+key);
    }
    public static double number(JsonObject object,String key,double fallback,double min,double max) {
        if(!object.has(key))return fallback;
        if(!object.get(key).isJsonPrimitive()||!object.getAsJsonPrimitive(key).isNumber())throw bad(key+": expected number");
        double n=object.get(key).getAsDouble();if(!Double.isFinite(n)||n<min||n>max)throw bad(key+": outside "+min+".."+max);return n;
    }
    public static IllegalArgumentException bad(String message) { return new IllegalArgumentException("Living behaviors: "+message); }
}
