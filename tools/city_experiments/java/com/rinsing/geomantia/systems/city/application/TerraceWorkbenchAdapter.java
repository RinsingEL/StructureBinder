package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.application.outdoor.CityTerraceFeaturePlanner;
import com.rinsing.geomantia.systems.city.domain.blueprint.CitySurfaceMaterials;
import com.rinsing.geomantia.systems.city.domain.landuse.CityMaterialField;
import com.rinsing.geomantia.systems.city.domain.model.*;
import java.util.*;
import java.util.function.IntBinaryOperator;

/** Offline scenario adapter. All theme choices come from scenario data or asset metadata. */
final class TerraceWorkbenchAdapter {
    private static final Gson JSON=new Gson();
    static List<JsonObject> definitions(JsonObject input,List<JsonObject> districts) {
        if(input.has("terraceGroups"))return objects(input.getAsJsonArray("terraceGroups"));
        List<JsonObject> result=new ArrayList<>();
        for(var d:districts)if(d.get("platform").getAsBoolean()) {
            var g=d.deepCopy();g.addProperty("terraceId",text(d,"districtId"));result.add(g);
        }
        return result;
    }
    static Map<String,Set<BlockPoint>> resolveClaims(Map<String,Set<BlockPoint>> groups,Map<String,List<BlockBounds>> buildings) {
        Map<BlockPoint,List<String>> owners=new HashMap<>();
        groups.forEach((id,mask)->mask.forEach(p->owners.computeIfAbsent(p,k->new ArrayList<>()).add(id)));
        Map<String,Set<BlockPoint>> result=new TreeMap<>();groups.keySet().forEach(id->result.put(id,new HashSet<>()));
        owners.forEach((p,ids)->{
            String winner=ids.stream().min(Comparator.<String>comparingLong(id->buildings.get(id).stream()
                    .mapToLong(b->distance(b,p)).min().orElseThrow()).thenComparing(Comparator.naturalOrder())).orElseThrow();
            result.get(winner).add(p);
        });
        for(var e:result.entrySet())for(var b:buildings.get(e.getKey()))for(int z=b.minZ();z<=b.maxZ();z++)for(int x=b.minX();x<=b.maxX();x++)
            if(!e.getValue().contains(new BlockPoint(x,z)))throw new IllegalArgumentException("Terrace ownership clipped a building: "+e.getKey());
        return result;
    }
    static CityMaterialField materials(JsonObject input,List<JsonObject> definitions,List<JsonObject> terraces) {
        JsonObject material=new JsonObject();
        material.add("defaults",input.has("surfaceMaterials")?input.get("surfaceMaterials").deepCopy():new JsonObject());
        JsonObject groups=new JsonObject();List<CityMaterialField.Owner> owners=new ArrayList<>();
        var styles=styles(input);
        for(var d:definitions)if(d.has("styleRef")) {
            String id=text(d,"terraceId");JsonObject style=requireStyle(styles,text(d,"styleRef"));
            groups.add(id,style.has("materials")?style.get("materials").deepCopy():new JsonObject());
            for(var t:terraces)if(text(t,"terraceId").equals(id))for(var s:objects(t.getAsJsonArray("spans")))
                owners.add(new CityMaterialField.Owner(id,new BlockBounds(s.get("minX").getAsInt(),s.get("z").getAsInt(),s.get("maxX").getAsInt(),s.get("z").getAsInt())));
        }
        material.add("groups",groups);
        return new CityMaterialField(CitySurfaceMaterials.read(material),owners,List.of());
    }
    static Map<String,CityTerraceFeaturePlanner.Plan> features(JsonObject input,List<JsonObject> definitions,
            List<JsonObject> terraces,List<JsonObject> members,List<BlockBounds> roads,
            Map<String,JsonObject> assets,IntBinaryOperator ground) {
        Map<String,CityTerraceFeaturePlanner.Plan> plans=new TreeMap<>();
        if(input.has("terraceFeatures")&&!input.get("terraceFeatures").getAsBoolean())return plans;
        Map<String,Map<BlockPoint,Integer>> masks=new TreeMap<>();Map<BlockPoint,Integer> all=new HashMap<>();
        for(var t:terraces){Map<BlockPoint,Integer> mask=new HashMap<>();for(var s:objects(t.getAsJsonArray("spans")))
            for(int x=s.get("minX").getAsInt();x<=s.get("maxX").getAsInt();x++) {
                var p=new BlockPoint(x,s.get("z").getAsInt());int y=s.get("targetY").getAsInt();mask.put(p,y);all.put(p,y);
            }
            masks.put(text(t,"terraceId"),mask);
        }
        List<BlockBounds> buildings=members.stream().map(TerraceWorkbenchAdapter::footprint).toList();
        Map<BlockPoint,Integer> surroundings=new HashMap<>();
        for(var p:all.keySet())for(int[] dir:List.of(new int[]{0,-1},new int[]{1,0},new int[]{0,1},new int[]{-1,0})) {
            var q=new BlockPoint(p.x()+dir[0],p.z()+dir[1]);if(!all.containsKey(q))surroundings.computeIfAbsent(q,k->ground.applyAsInt(k.x(),k.z()));
        }
        var styles=styles(input);
        for(var d:definitions)if(d.has("styleRef")) {
            String id=text(d,"terraceId");var style=requireStyle(styles,text(d,"styleRef"));
            var settings=new CityTerraceFeaturePlanner.Settings(string(style,"railBlock"),string(style,"postBlock"),string(style,"capBlock"),
                    integer(style,"postSpacing",9),integer(style,"minimumExposure",1),integer(style,"maximumCorners",4),integer(style,"cornerSpacing",24),
                    fixture(style,"cornerAssetId",assets),fixture(style,"courtAssetId",assets));
            plans.put(id,new CityTerraceFeaturePlanner().plan(id,masks.get(id),all,surroundings,buildings,roads,settings));
        }
        return plans;
    }
    private static CityTerraceFeaturePlanner.Fixture fixture(JsonObject style,String key,Map<String,JsonObject> assets) {
        if(!style.has(key))return null;
        var a=assets.get(text(style,key));
        if(a==null || !text(a,"consumer").equals("infrastructure"))throw new IllegalArgumentException("Terrace fixture must be program-owned: "+text(style,key));
        return new CityTerraceFeaturePlanner.Fixture(text(a,"assetId"),a.get("width").getAsInt(),a.get("depth").getAsInt(),a.get("height").getAsInt(),a.get("groundPlane").getAsInt());
    }
    private static Map<String,JsonObject> styles(JsonObject input) {
        Map<String,JsonObject> result=new TreeMap<>();
        for(var s:objects(input.getAsJsonArray("terraceStyles")))if(result.putIfAbsent(text(s,"styleId"),s)!=null)throw new IllegalArgumentException("Duplicate terrace style");
        return result;
    }
    private static JsonObject requireStyle(Map<String,JsonObject> styles,String id) {
        var style=styles.get(id);if(style==null)throw new IllegalArgumentException("Unknown terrace style: "+id);return style;
    }
    static BlockBounds footprint(JsonObject m) {
        var p=m.getAsJsonObject("originBlock");int x=p.get("x").getAsInt(),z=p.get("z").getAsInt();
        return new BlockBounds(x,z,x+m.get("width").getAsInt()-1,z+m.get("depth").getAsInt()-1);
    }
    private static long distance(BlockBounds b,BlockPoint p){long dx=Math.max(0,Math.max(b.minX()-p.x(),p.x()-b.maxX()));long dz=Math.max(0,Math.max(b.minZ()-p.z(),p.z()-b.maxZ()));return dx*dx+dz*dz;}
    static String text(JsonObject o,String key){return o.get(key).getAsString();}
    static int integer(JsonObject o,String key,int fallback){return o.has(key)?o.get(key).getAsInt():fallback;}
    private static String string(JsonObject o,String key){return o.has(key)?text(o,key):"";}
    private static List<JsonObject> objects(JsonArray a){return a==null?List.of():a.asList().stream().map(JsonElement::getAsJsonObject).toList();}
}
