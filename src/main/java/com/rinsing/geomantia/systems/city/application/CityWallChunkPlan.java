package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import java.util.*;

/** Keeps authored module origins and shared seam elevations while restricting terrain reads to one owner. */
public final class CityWallChunkPlan {
    public record Owner(int x,int z) {
        public BlockBounds bounds() { return new BlockBounds(x*16,z*16,x*16+15,z*16+15); }
    }
    public static Set<Owner> owners(JsonObject plan) {
        Set<Owner> owners=new LinkedHashSet<>();
        for(String key:List.of("wallUnits","wallNodes")) for(var element:plan.getAsJsonArray(key)) {
            var b=bounds(element.getAsJsonObject());
            for(int z=Math.floorDiv(b.minZ(),16);z<=Math.floorDiv(b.maxZ(),16);z++)
                for(int x=Math.floorDiv(b.minX(),16);x<=Math.floorDiv(b.maxX(),16);x++) owners.add(new Owner(x,z));
        }
        return Set.copyOf(owners);
    }
    public static JsonObject fragment(JsonObject plan,Owner owner) {
        var bounds=owner.bounds();
        JsonObject result=new JsonObject();
        plan.entrySet().forEach(e->{if(!Set.of("wallUnits","wallNodes","wallPlacementProfile").contains(e.getKey())) result.add(e.getKey(),e.getValue());});
        for(String key:List.of("wallUnits","wallNodes")) {
            JsonArray modules=new JsonArray();
            for(var element:plan.getAsJsonArray(key)) if(overlaps(bounds(element.getAsJsonObject()),bounds)) modules.add(element);
            result.add(key,modules);
        }
        JsonObject source=plan.getAsJsonObject("wallPlacementProfile"),profile=new JsonObject();
        source.entrySet().forEach(e->{if(!"surfaceColumns".equals(e.getKey()))profile.add(e.getKey(),e.getValue());});
        JsonArray columns=new JsonArray();
        for(var element:source.getAsJsonArray("surfaceColumns")) {
            var c=element.getAsJsonObject();int x=c.get("x").getAsInt(),z=c.get("z").getAsInt();
            if(x>=bounds.minX()-1 && x<=bounds.maxX()+1 && z>=bounds.minZ()-1 && z<=bounds.maxZ()+1) columns.add(c);
        }
        profile.add("surfaceColumns",columns);result.add("wallPlacementProfile",profile);return result;
    }
    private static boolean overlaps(BlockBounds a,BlockBounds b) { return a.minX()<=b.maxX()&&a.maxX()>=b.minX()&&a.minZ()<=b.maxZ()&&a.maxZ()>=b.minZ(); }
    private static BlockBounds bounds(JsonObject module) {var b=module.getAsJsonObject("blockBounds");return new BlockBounds(b.get("minX").getAsInt(),b.get("minZ").getAsInt(),b.get("maxX").getAsInt(),b.get("maxZ").getAsInt());}
}
