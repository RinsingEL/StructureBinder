package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.domain.blueprint.*;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSurfaceSettings;
import java.util.*;

public final class CityMaterialSupport {
    private CityMaterialSupport() { }
    public static Map<String,String> landscapeSlots(CityBlueprintReferenceCatalog.SurfaceRecipe recipe) {
        Map<String,String> result=new TreeMap<>();
        if(!recipe.surfacePrintEnabled())return result;
        put(result,"surfaceBlockId",recipe.surfaceBlockId());put(result,"cropBlockId",recipe.cropBlockId());
        put(result,"channelBankBlockId",recipe.channelBankBlockId());put(result,"channelWaterBlockId",recipe.channelWaterBlockId());
        put(result,"channelBankOverlayBlockId",recipe.channelBankOverlayBlockId());put(result,"boundaryBlockId",recipe.boundaryBlockId());
        return result;
    }
    private static void put(Map<String,String> values,String key,String value){if(value!=null&&!value.isBlank())values.put(key,value);}
    public static JsonObject guide(CityBlueprintReferenceCatalog catalog) {
        JsonObject result=new JsonObject();
        result.addProperty("instruction","Optional cityBlueprint.surfaceMaterials: choose appearance groups groundAndRoad, terrace, bridge. Each group accepts {preset:name, materials:{slot:blockId}}; choose a preset first and only override exceptions. Put these groups under defaults, groups[groupId], or roads[roadKind]. Road scope only exposes road/bridge slots. Group overrides inherit defaults; road overrides win. Pillar spacing, caps, bands and bridge supports are automatic, not author parameters. Legacy flat slot maps remain readable; output is grouped. landscapes stays landscapeId -> supported slot overrides. Use blockMaterials for individual block search/preview. Structure materialSelections is separate.");
        result.add("appearanceGroups",CitySurfaceAppearanceCatalog.guide());
        JsonArray slots=new JsonArray();new TreeSet<>(CitySurfaceMaterials.CITY_SLOTS).forEach(slots::add);result.add("citySlots",slots);
        result.add("roadKinds",new Gson().toJsonTree(new TreeSet<>(CitySurfaceMaterials.ROAD_KINDS)));
        JsonObject landscapes=new JsonObject();catalog.landscapeProfiles().forEach((id,p)->{
            var recipe=catalog.surfaceRecipes().get(p.surfaceRecipeRef());
            if(recipe!=null)landscapes.add(id,new Gson().toJsonTree(landscapeSlots(recipe)));
        });result.add("landscapeSupportedSlotsAndDefaults",landscapes);
        result.add("defaultCandidates",CityBlockMaterials.defaults());
        return result;
    }
    public static void validate(CityBlueprint blueprint,CityBlueprintReferenceCatalog catalog) {
        var materials=blueprint.surfaceMaterials();
        Set<String> groupIds=new HashSet<>();blueprint.groups().forEach(g->groupIds.add(g.groupId()));
        for(String group:materials.groups().keySet())if(!groupIds.contains(group))throw new IllegalArgumentException("surfaceMaterials.groups."+group+": group does not exist; use an existing groupId");
        for(String road:materials.roads().keySet())if(!CitySurfaceMaterials.ROAD_KINDS.contains(road))
            throw new IllegalArgumentException("surfaceMaterials.roads."+road+": use an existing road kind from the guide: "+new TreeSet<>(CitySurfaceMaterials.ROAD_KINDS));
        Map<String,CityBlueprint.Landscape> landscapes=new HashMap<>();blueprint.outdoorPlan().landscapes().forEach(l->landscapes.put(l.landscapeId(),l));
        materials.landscapes().forEach((id,slots)->{
            var landscape=landscapes.get(id);
            if(landscape==null)throw new IllegalArgumentException("surfaceMaterials.landscapes."+id+": landscape does not exist; use an existing landscapeId");
            var profile=catalog.landscapeProfiles().get(landscape.landscapeProfileRef());
            if(profile==null)throw new IllegalArgumentException("Unknown landscape profile: "+landscape.landscapeProfileRef());
            var allowed=landscapeSlots(catalog.surfaceRecipes().get(profile.surfaceRecipeRef()));
            for(String slot:slots.keySet())if(!allowed.containsKey(slot))throw new IllegalArgumentException("surfaceMaterials.landscapes."+id+"."+slot+": this landscape cannot replace that content; available="+allowed.keySet());
        });
        check(materials.defaults(),"defaults");materials.groups().forEach((id,m)->check(m,"groups."+id));
        materials.roads().forEach((id,m)->check(m,"roads."+id));materials.landscapes().forEach((id,m)->check(m,"landscapes."+id));
    }
    private static void check(Map<String,String> slots,String path){slots.forEach((slot,id)->CityBlockMaterials.validateBlock(id,slot,"surfaceMaterials."+path+"."+slot));}
    public static LandUseSurfaceSettings landscapeSettings(LandUseSurfaceSettings s,Map<String,String> overrides){
        if(overrides==null||overrides.isEmpty())return s;
        return new LandUseSurfaceSettings(s.surfacePrintEnabled(),s.autoConnect(),overrides.getOrDefault("surfaceBlockId",s.surfaceBlockId()),
                overrides.getOrDefault("cropBlockId",s.cropBlockId()),s.compatibilityCategory(),s.surfaceAlgorithm(),s.algorithmAnchor(),
                overrides.getOrDefault("channelBankBlockId",s.channelBankBlockId()),overrides.getOrDefault("channelWaterBlockId",s.channelWaterBlockId()),
                overrides.getOrDefault("channelBankOverlayBlockId",s.channelBankOverlayBlockId()),overrides.getOrDefault("boundaryBlockId",s.boundaryBlockId()),
                s.fieldBeforeBlocks(),s.channelWidthBlocks(),s.fieldAfterBlocks());
    }
}
