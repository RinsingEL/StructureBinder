package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.domain.blueprint.CitySurfaceMaterials;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.core.BlockPos;
import java.nio.file.*;
import java.util.*;

/** Bounded, read-only discovery. Never returns the full registry or changes a city draft. */
public final class CityBlockMaterials {
    private CityBlockMaterials() { }
    public static JsonObject defaults() {
        try {
            Path configRoot=net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get();
            Path config=configRoot==null?null:configRoot.resolve("geomantia/city_material_candidates.json");
            try(var input=CityBlockMaterials.class.getResourceAsStream("/geomantia/city_material_candidates.json")) {
                if(input==null)throw new IllegalStateException("Missing city_material_candidates.json");
                JsonObject values=JsonParser.parseString(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                if(config!=null && Files.exists(config)) {
                    var overrides=JsonParser.parseString(Files.readString(config)).getAsJsonObject();
                    overrides.entrySet().forEach(entry->values.add(entry.getKey(),entry.getValue()));
                }
                JsonObject result=new JsonObject();
                for(var entry:values.entrySet()) {
                    requireSlot(entry.getKey());JsonArray bounded=new JsonArray();
                    for(var id:entry.getValue().getAsJsonArray()) {if(bounded.size()==4)break;bounded.add(id.getAsString());}
                    result.add(entry.getKey(),bounded);
                } return result;
            }
        } catch(java.io.IOException e){throw new IllegalStateException("Cannot read material candidates",e);}
    }
    static void requireSlot(String slot){
        if(!CitySurfaceMaterials.CITY_SLOTS.contains(slot)&&!CitySurfaceMaterials.LANDSCAPE_SLOTS.contains(slot))
            throw new IllegalArgumentException("Unknown material slot: "+slot+"; choose a slot from surfaceMaterials guide");
    }
    enum Shape { BLOCK, SLAB, STAIR, LIQUID, MULTIBLOCK, BLOCK_ENTITY }
    record BlockInfo(String id,String name,String tags,Shape shape,Boolean supportsGround) { }
    interface Registry {
        Set<String> ids();
        BlockInfo get(String id);
    }
    private static Registry installed() {
        return new Registry() {
            public Set<String> ids(){return BuiltInRegistries.BLOCK.keySet().stream().map(Object::toString).collect(java.util.stream.Collectors.toSet());}
            public BlockInfo get(String id){
                ResourceLocation key=ResourceLocation.tryParse(id);
                if(key==null||!BuiltInRegistries.BLOCK.containsKey(key))return null;
                Block block=BuiltInRegistries.BLOCK.get(key);
                Shape shape=block instanceof EntityBlock?Shape.BLOCK_ENTITY:block instanceof DoublePlantBlock?Shape.MULTIBLOCK:
                        block instanceof StairBlock?Shape.STAIR:block instanceof SlabBlock?Shape.SLAB:block instanceof LiquidBlock?Shape.LIQUID:Shape.BLOCK;
                Boolean support=null;
                try {support=!block.defaultBlockState().isAir() && !block.defaultBlockState().getCollisionShape(EmptyBlockGetter.INSTANCE,BlockPos.ZERO).isEmpty();}
                catch(RuntimeException unknownShape) { /* Some mod shapes require a real level. Do not claim verification. */ }
                return new BlockInfo(id,block.getName().getString(),block.builtInRegistryHolder().tags()
                        .map(t->t.location().toString()).sorted().collect(java.util.stream.Collectors.joining(" ")),shape,
                        support);
            }
        };
    }
    public static void validateBlock(String id,String slot,String path){validateBlock(id,slot,path,installed());}
    static void validateBlock(String id,String slot,String path,Registry registry) {
        BlockInfo block=registry.get(id);
        if(block==null)throw new IllegalArgumentException(path+": block "+id+" is not installed; search an installed replacement");
        String reason=incompatibility(block,slot);
        if(reason!=null)throw new IllegalArgumentException(path+": "+id+" - "+reason+"; choose another block for this slot");
    }
    static String incompatibility(BlockInfo block,String slot) {
        if(Set.of("minecraft:air","minecraft:cave_air","minecraft:void_air").contains(block.id()))return "air cannot be used as a construction material";
        if(Set.of("roadStair","roadCurb","accessStair").contains(slot)&&block.shape()!=Shape.STAIR)return "this slot requires a stair block";
        if(Set.of("roadSurface","accessSurface","bridgeSurface").contains(slot)&&block.shape()!=Shape.SLAB)return "the current road cross section requires a slab block";
        if("channelWaterBlockId".equals(slot)&&!"minecraft:water".equals(block.id()))return "the current irrigation implementation supports water only";
        if(block.shape()==Shape.LIQUID&&!"channelWaterBlockId".equals(slot))return "fluid is not a solid surface or decoration";
        if(block.shape()==Shape.BLOCK_ENTITY)return "block-entity initialization is not supported by this surface placement path";
        if(block.shape()==Shape.MULTIBLOCK)return "two-block plants need paired placement, which this content slot does not provide";
        if(Set.of("ground","roadBase","retainingWall","wallColumn","wallCap","wallBand","bridgeBeam","bridgePost","bridgePier","deck","fill","surfaceBlockId","channelBankBlockId").contains(slot)
                && Boolean.FALSE.equals(block.supportsGround()))return "this slot needs a supporting ground block";
        return null;
    }
    public static JsonObject query(JsonObject request,CityBlueprintReferenceCatalog catalog) {
        return query(request,catalog,installed());
    }
    static JsonObject query(JsonObject request,CityBlueprintReferenceCatalog catalog,Registry registry) {
        for(String key:request.keySet())if(!Set.of("slot","query","page","landscapeProfileRef","previewBlockId").contains(key))throw new IllegalArgumentException("Unknown blockMaterials field: "+key);
        String slot=request.has("slot")?request.get("slot").getAsString():"";requireSlot(slot);
        if(CitySurfaceMaterials.LANDSCAPE_SLOTS.contains(slot)) {
            String profileId=request.has("landscapeProfileRef")?request.get("landscapeProfileRef").getAsString():"";
            var profile=catalog.landscapeProfiles().get(profileId);
            if(profile==null)throw new IllegalArgumentException("Supply landscapeProfileRef from the design guide for landscape slot "+slot);
            var slots=CityMaterialSupport.landscapeSlots(catalog.surfaceRecipes().get(profile.surfaceRecipeRef()));
            if(!slots.containsKey(slot))throw new IllegalArgumentException(profileId+" does not expose "+slot+"; available="+slots.keySet());
        }
        JsonObject result=new JsonObject();result.addProperty("ok",true);result.addProperty("designInProgress",true);
        result.addProperty("nextAction","city_submit_d4_blueprint");result.addProperty("slot",slot);
        result.addProperty("notice","Candidates are discovered, not world-placement verified. Local support, survival and mod-specific behavior may still reject placement.");
        if(request.has("previewBlockId")) {
            String id=request.get("previewBlockId").getAsString();validateBlock(id,slot,"blockMaterials.previewBlockId",registry);
            result.add("material",describe(registry.get(id),slot,registry));
            com.rinsing.geomantia.systems.city.infrastructure.preview.CityBlockMaterialPreview.attach(result,id);
            return result;
        }
        int page=0;
        if(request.has("page")) {try {page=new java.math.BigDecimal(request.get("page").getAsString()).intValueExact();}catch(Exception ex){throw new IllegalArgumentException("blockMaterials.page must be a nonnegative integer");}}
        if(page<0||page>100000)throw new IllegalArgumentException("blockMaterials.page must be between 0 and 100000");
        String query=request.has("query")?request.get("query").getAsString().strip().toLowerCase(Locale.ROOT):"";
        if(query.length()>128)throw new IllegalArgumentException("blockMaterials.query must be at most 128 characters");
        List<BlockInfo> matches=new ArrayList<>();
        if(query.isEmpty()) {
            var candidates=defaults().getAsJsonArray(slot);
            if(candidates!=null)for(var id:candidates){var entry=registry.get(id.getAsString());if(entry!=null)matches.add(entry);}
        } else for(String id:registry.ids()) {
            BlockInfo entry=registry.get(id);if(entry==null)continue;
            String names=(entry.id()+" "+entry.name()+" "+entry.tags()).toLowerCase(Locale.ROOT);
            if(Arrays.stream(query.split("\\s+")).allMatch(names::contains))matches.add(entry);
        }
        matches.sort(Comparator.comparing((BlockInfo entry)->incompatibility(entry,slot)!=null).thenComparing(BlockInfo::id));
        int start=Math.min(matches.size(),page*12),end=Math.min(matches.size(),start+12);
        JsonArray items=new JsonArray();for(var entry:matches.subList(start,end))items.add(describe(entry,slot,registry));
        result.add("candidates",items);result.addProperty("page",page);result.addProperty("hasMore",end<matches.size());
        if(end<matches.size())result.addProperty("nextPage",page+1);
        return result;
    }
    private static JsonObject describe(BlockInfo block,String slot,Registry registry){
        JsonObject result=new JsonObject();result.addProperty("blockId",block.id());
        result.addProperty("displayName",block.name());String namespace=block.id().split(":",2)[0];result.addProperty("modId",namespace);
        String issue=incompatibility(block,slot);result.addProperty("compatibility",issue!=null?"incompatible":block.supportsGround()==null?"unknown_requires_world_check":"basic_checks_passed_not_placement_verified");
        if(issue!=null)result.addProperty("reason",issue);
        if(block.supportsGround()==null)result.addProperty("unknown","The block shape requires a real world; local support has not been verified");
        JsonArray known=new JsonArray();for(String candidate:new TreeSet<>(CitySurfaceMaterials.CITY_SLOTS))if(incompatibility(block,candidate)==null)known.add(candidate);
        result.add("basicCompatibleSlots",known);
        JsonArray pairs=new JsonArray();String stem=block.id().replaceFirst("_(slab|stairs|wall|fence)$","");
        for(String suffix:List.of("_slab","_stairs","_wall")) {
            String paired=stem+suffix;if(!paired.equals(block.id())&&registry.get(paired)!=null)pairs.add(paired);
        }
        result.add("sameNameCandidatesUnverified",pairs);return result;
    }
}
