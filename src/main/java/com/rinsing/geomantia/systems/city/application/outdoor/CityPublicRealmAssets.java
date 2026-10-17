package com.rinsing.geomantia.systems.city.application.outdoor;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Frozen, lossless voxel exports of authored NBT. Asset IDs carry no theme behaviour. */
public final class CityPublicRealmAssets {
    public record Voxel(int x, int y, int z, String state) {}
    public record Asset(String id, int width, int height, int depth, int groundPlane, String kind,
                        String nbtHash, String authorHash, List<Voxel> voxels) {}
    public record Choice(String assetId, double weight) {}
    private final Map<String, Asset> assets;
    private final Map<String, List<Choice>> pools;
    public CityPublicRealmAssets(JsonObject json) {
        if (!"city_public_realm_assets.v1".equals(json.get("schema").getAsString())) throw new IllegalArgumentException("PUBLIC_REALM_ASSET_SCHEMA");
        Map<String, Asset> found = new TreeMap<>();
        for (JsonElement e : json.getAsJsonArray("assets")) {
            JsonObject a=e.getAsJsonObject(); JsonArray s=a.getAsJsonArray("size");
            int w=s.get(0).getAsInt(),h=s.get(1).getAsInt(),d=s.get(2).getAsInt(),g=a.get("groundPlane").getAsInt();
            if(w<1||w>128||h<1||h>128||d<1||d>128||g<0||g>=h) throw new IllegalArgumentException("PUBLIC_REALM_ASSET_SIZE");
            List<Voxel> voxels=new ArrayList<>(); Set<String> positions=new HashSet<>();
            for(JsonElement b:a.getAsJsonArray("blocks")) {
                JsonArray v=b.getAsJsonArray();int x=v.get(0).getAsInt(),y=v.get(1).getAsInt(),z=v.get(2).getAsInt();String state=v.get(3).getAsString();
                if(x<0||x>=w||y<0||y>=h||z<0||z>=d||!validState(state)||!positions.add(x+":"+y+":"+z)) throw new IllegalArgumentException("PUBLIC_REALM_ASSET_VOXEL");
                if(state.matches("minecraft:(air|cave_air|void_air|lava)(\\[.*)?")) throw new IllegalArgumentException("PUBLIC_REALM_DESTRUCTIVE_ASSET");
                voxels.add(new Voxel(x,y,z,state));
            }
            String id=a.get("id").getAsString(), hash=a.get("nbtSha256").getAsString(),author=a.get("authorSha256").getAsString();
            if(!hash.matches("[a-f0-9]{64}")||!author.matches("[a-f0-9]{64}")||voxels.isEmpty()) throw new IllegalArgumentException("PUBLIC_REALM_ASSET_HASH");
            if(found.put(id,new Asset(id,w,h,d,g,a.get("kind").getAsString(),hash,author,List.copyOf(voxels)))!=null) throw new IllegalArgumentException("PUBLIC_REALM_ASSET_DUPLICATE");
        }
        assets=Map.copyOf(found); Map<String,List<Choice>> p=new TreeMap<>();
        json.getAsJsonObject("pools").entrySet().forEach(e->p.put(e.getKey(),choices(e.getValue().getAsJsonArray()))); pools=Map.copyOf(p);
    }
    public static CityPublicRealmAssets load(String catalog) {
        if(!"academy".equals(catalog)) throw new IllegalArgumentException("PUBLIC_REALM_UNKNOWN_CATALOG:"+catalog);
        try(InputStream in=CityPublicRealmAssets.class.getResourceAsStream("/data/geomantia/city/public_realm/"+catalog+".json")) {
            if(in==null) throw new IllegalArgumentException("PUBLIC_REALM_MISSING_CATALOG");
            return new CityPublicRealmAssets(JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject());
        } catch(IOException e) {throw new IllegalStateException(e);}
    }
    public static JsonObject describe() {
        try(InputStream in=CityPublicRealmAssets.class.getResourceAsStream("/data/geomantia/city/public_realm/academy.json");
            InputStream examples=CityPublicRealmAssets.class.getResourceAsStream("/data/geomantia/city/public_realm/examples.json")) {
            JsonObject catalog=JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(in),StandardCharsets.UTF_8)).getAsJsonObject();
            for(var e:catalog.getAsJsonArray("assets"))e.getAsJsonObject().remove("blocks");
            catalog.add("examples",JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(examples),StandardCharsets.UTF_8)));
            catalog.addProperty("contract","publicRealm={schema:'city_public_realm.v1',catalog:'academy',seed:integer,spaces:[{id,owner,origin?:{x,z},plan:<example.plan>}],greenery?:{pools:{trees:'courtyard_trees',shrubs:'low_shrubs',flowers:'flower_patches',hedges:'hedge_segments',planters:'planters'},spacing:{trees:13,shrubs:5,flowers:4},slotLimits:{trees:80,planters:40,hedges:64,shrubs:96,flowers:220},maxPlacements:500,layeredPlanting:true},fringe?:{width:20,pools:{trees:'outer_trees',flowers:'flower_patches'},maxPlacements:500,layeredPlanting:true},externalAccess?:{id,width:5,waypoints:[{x,z,surfaceY}],landing:{minX,minZ,maxX,maxZ},harborReservation:{minX,minZ,maxX,maxZ}}}. Space coordinates are relative to origin. Exterior access coordinates are world coordinates, surfaceY is walkable top height (0.5 steps); no docks or ocean filling. Reserve room in the original courtyard layout. Full asset size and groundPlane determine placement; do not resize templates.");
            return catalog;
        }catch(IOException e){throw new IllegalStateException(e);}
    }
    public Asset asset(String id) {Asset a=assets.get(id);if(a==null)throw new IllegalArgumentException("PUBLIC_REALM_UNKNOWN_ASSET:"+id);return a;}
    public List<Choice> pool(String id) {var p=pools.get(id);if(p==null)throw new IllegalArgumentException("PUBLIC_REALM_UNKNOWN_POOL:"+id);return p;}
    public List<Choice> choices(JsonArray rows) {
        if(rows.isEmpty()||rows.size()>128)throw new IllegalArgumentException("PUBLIC_REALM_POOL_SIZE");
        List<Choice> result=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(JsonElement e:rows){var o=e.getAsJsonObject();String id=o.get("assetId").getAsString();asset(id);double w=o.get("weight").getAsDouble();
            if(!Double.isFinite(w)||w<=0||!ids.add(id))throw new IllegalArgumentException("PUBLIC_REALM_POOL_WEIGHT");result.add(new Choice(id,w));}
        return List.copyOf(result);
    }
    public static boolean validState(String state) {return state!=null&&state.matches("[a-z0-9_.-]+:[a-z0-9_./-]+(\\[[a-z0-9_]+=[a-z0-9_]+(,[a-z0-9_]+=[a-z0-9_]+)*])?");}
    public static String rotateState(String state,int rotation) {
        int i=state.indexOf('[');if(i<0)return state;
        Map<String,String> props=new TreeMap<>();List<String> dirs=List.of("north","east","south","west");
        for(String pair:state.substring(i+1,state.length()-1).split(",")) {String[] kv=pair.split("=");String k=kv[0],v=kv[1];
            if(dirs.contains(k))k=dirs.get((dirs.indexOf(k)+rotation/90)%4);
            if(k.equals("facing")&&dirs.contains(v))v=dirs.get((dirs.indexOf(v)+rotation/90)%4);
            if(k.equals("axis")&&rotation%180!=0&&(v.equals("x")||v.equals("z")))v=v.equals("x")?"z":"x";
            if(k.equals("rotation"))v=Integer.toString((Integer.parseInt(v)+rotation/90*4)%16);
            props.put(k,v);
        }
        return state.substring(0,i)+"["+String.join(",",props.entrySet().stream().map(e->e.getKey()+"="+e.getValue()).toList())+"]";
    }
}
