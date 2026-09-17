package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import java.util.*;

/** Host-owned edit scope. Geometry comes only from the preceding compiled draft. */
final class CityD4LayoutPolicy {
    final JsonObject data;
    final Set<String> editedGroups = new LinkedHashSet<>();
    final Set<String> frozenGroups = new LinkedHashSet<>();
    final Set<String> protectedGroups = new LinkedHashSet<>();
    final Map<String,String> districtByGroup = new LinkedHashMap<>();
    final JsonArray evicted = new JsonArray();

    CityD4LayoutPolicy(JsonObject data) {
        this.data=data.deepCopy();
        array(data,"editedGroups").forEach(e->editedGroups.add(e.getAsString()));
        object(data,"districtByGroup").entrySet().forEach(e->districtByGroup.put(e.getKey(),e.getValue().getAsString()));
        frozenGroups.addAll(districtByGroup.keySet());frozenGroups.removeAll(editedGroups);
        array(data,"protectedGroups").forEach(e->protectedGroups.add(e.getAsString()));
    }
    static JsonObject request(JsonObject before,JsonObject after,JsonObject draft,String owner,boolean expansion) {
        JsonObject result=new JsonObject(),owners=new JsonObject();JsonArray edited=new JsonArray(),protectedIds=new JsonArray();
        Set<String> protectedDistricts=new HashSet<>();array(after,"protectedDistrictIds").forEach(e->protectedDistricts.add(e.getAsString()));
        Set<String> oldOwnerGroups=new HashSet<>();array(object(object(before,"bodies"),owner),"groups").forEach(g->oldOwnerGroups.add(text(g.getAsJsonObject(),"groupId")));
        boolean outward=expansion&&"OUTWARD_ARRAY".equals(text(after,"expansionMode"));
        object(after,"bodies").entrySet().forEach(e->{for(var g:array(e.getValue().getAsJsonObject(),"groups")) {
            String id=text(g.getAsJsonObject(),"groupId");owners.addProperty(id,e.getKey());
            if(e.getKey().equals(owner)&&!(outward&&oldOwnerGroups.contains(id)))edited.add(id);
            else if(e.getKey().equals(owner)||!expansion||protectedDistricts.contains(e.getKey()))protectedIds.add(id);
        }});
        for(var g:array(object(after,"integrationDesign"),"groups")){String id=text(g.getAsJsonObject(),"groupId");owners.addProperty(id,"legacy_integration");protectedIds.add(id);}
        result.add("editedGroups",edited);result.add("protectedGroups",protectedIds);result.add("districtByGroup",owners);
        result.addProperty("expansion",expansion);result.addProperty("owner",owner);
        result.addProperty("expansionMode",text(after,"expansionMode"));
        result.add("previousAnchors",array(object(draft,"compiledLayout"),"anchors").deepCopy());
        result.add("previousSkippedMembers",array(object(draft,"compiledLayout"),"skippedMembers").deepCopy());
        result.add("previousLandscapes",object(draft,"landscapeLayout").deepCopy());
        JsonArray frozenLandscapes=new JsonArray();
        object(before,"bodies").entrySet().forEach(e->{if(expansion||!e.getKey().equals(owner))for(var l:array(e.getValue().getAsJsonObject(),"landscapes"))frozenLandscapes.add(text(l.getAsJsonObject(),"landscapeId"));});
        result.add("frozenLandscapeIds",frozenLandscapes);
        return result;
    }
    JsonArray previousAnchors(){return array(data,"previousAnchors");}
    boolean frozen(String group){return frozenGroups.contains(group);}
    boolean displaceable(String group){return frozen(group)&&!protectedGroups.contains(group)&&data.get("expansion").getAsBoolean();}
    JsonArray obstacles(JsonArray occupied){
        JsonArray result=new JsonArray();for(var e:occupied)if(!displaceable(text(e.getAsJsonObject(),"ownerGroupId")))result.add(e);return result;
    }
    /** Called only after terrain and hard collisions passed. Never removes a building on failed placement. */
    boolean displace(BlockBounds incoming,JsonArray anchors,JsonArray occupied){
        List<JsonElement> victims=new ArrayList<>();Map<String,Integer> remaining=new HashMap<>();
        for(var e:anchors){JsonObject a=e.getAsJsonObject();String group=text(a,"placementGroupId");String district=districtByGroup.get(group);
            if(district!=null)remaining.merge(district,1,Integer::sum);
            if(displaceable(group)&&incoming.overlaps(bounds(object(a,"collisionEnvelope"))))victims.add(e);
        }
        for(var e:victims)remaining.merge(districtByGroup.get(text(e.getAsJsonObject(),"placementGroupId")),-1,Integer::sum);
        for(var e:victims)if(remaining.get(districtByGroup.get(text(e.getAsJsonObject(),"placementGroupId")))<=0)return false;
        Set<String> ids=new HashSet<>();for(var e:victims){ids.add(text(e.getAsJsonObject(),"anchorId"));evicted.add(e.deepCopy());anchors.remove(e);}
        occupied.asList().removeIf(e->ids.contains(text(e.getAsJsonObject(),"anchorId")));
        return true;
    }
    /** Existing landscape footprints remain fixed; expanding arrays may not erase their function. */
    void addLandscapeObstacles(JsonArray occupied){
        for(var e:array(object(data,"previousLandscapes"),"instances")) {
            JsonObject instance=e.getAsJsonObject();
            if(!isFrozenLandscape(text(instance,"landscapeId")))continue;
            for(var row:array(instance,"reservationSpans")) {
                JsonObject span=row.getAsJsonObject(),box=new JsonObject(),obstacle=new JsonObject();
                box.add("minX",span.get("minX"));box.add("maxX",span.get("maxX"));box.add("minZ",span.get("z"));box.add("maxZ",span.get("z"));
                obstacle.add("blockBounds",box);obstacle.add("bodyBounds",box.deepCopy());obstacle.addProperty("ownerGroupId","frozen_landscape");
                obstacle.addProperty("anchorId","landscape:"+text(instance,"landscapeInstanceId")+":"+text(span,"z"));occupied.add(obstacle);
            }
        }
    }
    CityLandscapeCapacityReservationPlanner.Result preserveLandscapes(CityLandscapeCapacityReservationPlanner.Result proposed){
        JsonObject plan=proposed.plan().deepCopy();JsonArray instances=new JsonArray();
        for(var e:array(plan,"instances"))if(!isFrozenLandscape(text(e.getAsJsonObject(),"landscapeId")))instances.add(e.deepCopy());
        for(var e:array(object(data,"previousLandscapes"),"instances"))if(isFrozenLandscape(text(e.getAsJsonObject(),"landscapeId")))instances.add(e.deepCopy());
        JsonArray warnings=new JsonArray();
        for(var e:array(plan,"warnings"))if(!isFrozenLandscape(text(e.getAsJsonObject(),"landscapeId")))warnings.add(e.deepCopy());
        for(var e:array(object(data,"previousLandscapes"),"warnings"))if(isFrozenLandscape(text(e.getAsJsonObject(),"landscapeId")))warnings.add(e.deepCopy());
        plan.add("instances",instances);plan.add("warnings",warnings);CityLandscapeCapacityReservationPlanner.refreshPlanHash(plan);
        return new CityLandscapeCapacityReservationPlanner.Result(proposed.ok(),proposed.reasonCode(),plan);
    }
    boolean isFrozenLandscape(String id){return array(data,"frozenLandscapeIds").asList().stream().anyMatch(e->e.getAsString().equals(id));}
    static boolean hasContent(JsonObject draft,JsonObject body){
        Set<String> groups=new HashSet<>(),landscapes=new HashSet<>();
        array(body,"groups").forEach(g->groups.add(text(g.getAsJsonObject(),"groupId")));
        array(body,"landscapes").forEach(g->landscapes.add(text(g.getAsJsonObject(),"landscapeId")));
        return array(object(draft,"compiledLayout"),"anchors").asList().stream().anyMatch(a->groups.contains(text(a.getAsJsonObject(),"placementGroupId")))
            ||array(object(draft,"landscapeLayout"),"instances").asList().stream().anyMatch(l->landscapes.contains(text(l.getAsJsonObject(),"landscapeId"))&&!array(l.getAsJsonObject(),"reservationSpans").isEmpty());
    }
    private static BlockBounds bounds(JsonObject b){return new BlockBounds(b.get("minX").getAsInt(),b.get("minZ").getAsInt(),b.get("maxX").getAsInt(),b.get("maxZ").getAsInt());}
    private static JsonObject object(JsonObject o,String k){return o!=null&&o.has(k)&&o.get(k).isJsonObject()?o.getAsJsonObject(k):new JsonObject();}
    private static JsonArray array(JsonObject o,String k){return o!=null&&o.has(k)&&o.get(k).isJsonArray()?o.getAsJsonArray(k):new JsonArray();}
    private static String text(JsonObject o,String k){return o!=null&&o.has(k)&&o.get(k).isJsonPrimitive()?o.get(k).getAsString():"";}
}
