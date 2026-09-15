package com.rinsing.geomantia.systems.city.domain.landuse;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.domain.blueprint.CitySurfaceMaterials;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import java.util.*;

/** Frozen ownership geometry for material lookup. Does not reserve or move any land. */
public record CityMaterialField(CitySurfaceMaterials selections, List<Owner> owners, List<Road> roads) {
    public record Owner(String groupId, BlockBounds bounds) { }
    public record Road(String sourceId, String kind, BlockBounds bounds) { }
    public CityMaterialField {
        selections=selections==null?CitySurfaceMaterials.empty():selections;
        owners=List.copyOf(owners==null?List.of():owners); roads=List.copyOf(roads==null?List.of():roads);
    }
    public static CityMaterialField empty(){return new CityMaterialField(null,null,null);}
    public boolean isEmpty(){return selections.isEmpty();}
    public String at(String slot,int x,int z,String fallback){return at(slot,x,z,"",fallback);}
    public String at(String slot,int x,int z,String sourceId,String fallback){
        if(isEmpty()) return fallback;
        String group=""; long best=Long.MAX_VALUE;
        for(Owner owner:selections.groups().isEmpty()?List.<Owner>of():owners){
            long d=distance(owner.bounds(),x,z);
            if(d<best || d==best && owner.groupId().compareTo(group)<0){best=d;group=owner.groupId();}
        }
        String road="";
        for(Road candidate:selections.roads().isEmpty()?List.<Road>of():roads) if(candidate.sourceId().equals(sourceId)) {road=candidate.kind();break;}
        return selections.resolve(slot,group,road,fallback);
    }
    public boolean isCurb(String sourceId,int x,int z) {
        return roads.stream().anyMatch(road->road.sourceId().equals(sourceId) && distance(road.bounds(),x,z)>0);
    }
    private static long distance(BlockBounds b,int x,int z){
        long dx=Math.max(0,Math.max((long)b.minX()-x,(long)x-b.maxX()));
        long dz=Math.max(0,Math.max((long)b.minZ()-z,(long)z-b.maxZ()));return dx*dx+dz*dz;
    }
    public JsonObject toJson(){return new Gson().toJsonTree(this).getAsJsonObject();}
    public static CityMaterialField read(JsonObject value){
        return value==null?empty():new Gson().fromJson(value,CityMaterialField.class);
    }
}
