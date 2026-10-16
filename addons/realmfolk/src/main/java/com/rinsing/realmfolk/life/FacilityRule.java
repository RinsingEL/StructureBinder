package com.rinsing.realmfolk.life;

import com.google.gson.*;
import java.util.*;
import static com.rinsing.realmfolk.life.BehaviorDefinitions.*;

/** Shared declarative shape used by runtime discovery and the Studio exporter. */
public record FacilityRule(String id,List<String> blocks,Map<String,String> state,String kind,String behavior,
                           String use,String facing,double height,List<Slot> slots,List<Part> parts) {
    public record Slot(String id,int[] approach,double[] offset) {}
    public record Part(int[] offset,List<String> blocks,Map<String,String> state,boolean sameBlock) {}
    public static List<FacilityRule> parse(JsonObject input,Map<String,BehaviorDefinitions.Behavior> behaviors,
                                          java.util.function.Predicate<String> modLoaded) {
        fields(input,Set.of("schema","facilities","tags"),"facility rules");
        if(input.has("schema")&&input.get("schema").getAsInt()!=1)throw bad("facility schema must be 1");
        var array=input.getAsJsonArray("facilities");if(array==null||array.size()>512)throw bad("facilities: expected at most 512 rules");
        var result=new ArrayList<FacilityRule>();var ids=new HashSet<String>();int index=0;
        for(var element:array) {
            JsonObject o=element.getAsJsonObject();String id=text(o,"id","legacy_"+index++);
            if(!id.matches("[a-zA-Z0-9_.:/-]{1,128}")||!ids.add(id))throw bad("Invalid/duplicate facility rule id "+id);
            fields(o,Set.of("id","requiresMod","blocks","state","kind","behavior","adapter","use","facing","height","seatHeight","slots","requires"),id);
            if(o.has("requiresMod")&&!modLoaded.test(o.get("requiresMod").getAsString()))continue;
            String kind=text(o,"kind","");if(!Set.of("bed","seat","work","meeting").contains(kind))throw bad(id+": unknown facility kind");
            String behavior=text(o,"behavior",legacy(text(o,"adapter",kind.equals("bed")?"bed":"stand")));
            var definition=behaviors.get(behavior);
            if(definition==null||!definition.kinds().contains(kind))throw bad(id+": behavior does not support "+kind+": "+behavior);
            String use=text(o,"use","building");if(!Set.of("building","resident","guest","care","public","staff").contains(use))throw bad(id+": unknown use");
            String facing=text(o,"facing","");if(!facing.isEmpty()&&!Set.of("north","south","east","west").contains(facing))throw bad(id+": invalid facing");
            double height=number(o,"height",number(o,"seatHeight",kind.equals("bed")?.15:.5,0,2),0,2);
            var slots=new ArrayList<Slot>();var slotIds=new HashSet<String>();
            if(o.has("slots"))for(var entry:o.getAsJsonArray("slots")) {
                var s=entry.getAsJsonObject();fields(s,Set.of("id","approach","offset"),id+" slot");
                String slot=text(s,"id","");if(!slot.matches("[a-zA-Z0-9_-]{1,32}")||!slotIds.add(slot))throw bad(id+": invalid/duplicate slot");
                slots.add(new Slot(slot,s.has("approach")?integers(s.getAsJsonArray("approach"),8):null,s.has("offset")?vector(s.getAsJsonArray("offset"),4):new double[3]));
            }
            else slots.add(new Slot("default",null,new double[3]));
            if(slots.isEmpty()||slots.size()>8)throw bad(id+": requires 1..8 slots");
            var parts=new ArrayList<Part>();
            if(o.has("requires"))for(var entry:o.getAsJsonArray("requires")) {
                var p=entry.getAsJsonObject();fields(p,Set.of("offset","blocks","state","sameBlock"),id+" required part");
                var selectors=p.has("blocks")?selectors(p.getAsJsonArray("blocks")):List.<String>of();
                boolean same=p.has("sameBlock")?p.get("sameBlock").getAsBoolean():selectors.isEmpty();
                if(!same&&selectors.isEmpty())throw bad(id+": required part needs blocks or sameBlock");
                parts.add(new Part(integers(p.getAsJsonArray("offset"),8),selectors,states(p),same));
            }
            if(parts.size()>16)throw bad(id+": too many required parts");
            result.add(new FacilityRule(id,selectors(o.getAsJsonArray("blocks")),states(o),kind,behavior,use,facing,height,List.copyOf(slots),List.copyOf(parts)));
        }
        return List.copyOf(result);
    }
    private static String legacy(String adapter) { return switch(adapter){case "bed"->"realmfolk:bed";case "marker_seat"->"realmfolk:seat";case "stand"->"realmfolk:work";default->adapter;}; }
    private static String text(JsonObject o,String key,String fallback){return o.has(key)?o.get(key).getAsString():fallback;}
    private static List<String> selectors(JsonArray a) {
        if(a==null||a.isEmpty()||a.size()>128)throw bad("blocks: requires 1..128 selectors");var values=new ArrayList<String>();
        for(var e:a){String s=e.getAsString();BehaviorDefinitions.id(s.startsWith("#")?s.substring(1):s);values.add(s);}return List.copyOf(values);
    }
    private static Map<String,String> states(JsonObject o) {
        if(!o.has("state"))return Map.of();var result=new LinkedHashMap<String,String>();
        for(var e:o.getAsJsonObject("state").entrySet()){
            if(!e.getValue().isJsonPrimitive())throw bad("state values must be scalar");result.put(e.getKey(),e.getValue().getAsString());
        }
        return Map.copyOf(result);
    }
    private static double[] vector(JsonArray a,double bound) {
        if(a==null||a.size()!=3)throw bad("offset/approach requires three coordinates");double[] n=new double[3];
        for(int i=0;i<3;i++){
            if(!a.get(i).isJsonPrimitive()||!a.get(i).getAsJsonPrimitive().isNumber())throw bad("vector requires numbers");
            n[i]=a.get(i).getAsDouble();if(!Double.isFinite(n[i])||Math.abs(n[i])>bound)throw bad("vector outside bounds");
        }return n;
    }
    private static int[] integers(JsonArray a,int bound){double[] v=vector(a,bound);int[] result=new int[3];for(int i=0;i<3;i++){if(v[i]!=Math.rint(v[i]))throw bad("block offsets require integers");result[i]=(int)v[i];}return result;}
}
