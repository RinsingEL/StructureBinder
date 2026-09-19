package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import java.io.IOException;
import java.util.*;

/** Executes one fixed module set. All geometry and conflicts are checked before the first write. */
public final class CityWallPlacementBackend {
    public static void prepare(ServerLevel level, JsonObject plan) {
        if (level == null) return;
        try { CityWallModuleConfig.loadCurrent().freeze(plan); }
        catch (IOException ex) { throw new IllegalArgumentException(ex.getMessage(), ex); }
        Map<BlockPoint,Integer> surfaces = new LinkedHashMap<>();
        JsonArray samples = new JsonArray();
        for (String key : List.of("wallUnits", "wallNodes")) for (JsonElement element : array(plan,key)) {
            BlockBounds bounds = bounds(element.getAsJsonObject().getAsJsonObject("blockBounds"));
            for (int z=bounds.minZ();z<=bounds.maxZ();z++) for(int x=bounds.minX();x<=bounds.maxX();x++) {
                BlockPoint point=new BlockPoint(x,z);
                if(surfaces.containsKey(point)) continue;
                CitySurfaceCache.Sample sample=CitySurfaceCache.sample(level,x,z);
                surfaces.put(point,sample.surfaceY());
                JsonObject cell=new JsonObject(); cell.addProperty("x",x); cell.addProperty("z",z);
                cell.addProperty("surfaceY",sample.surfaceY()); cell.addProperty("fluid",sample.fluid()); samples.add(cell);
            }
        }
        JsonObject profile=heightProfile(new ArrayList<>(surfaces.values()), integer(plan,"naturalBoundaryMinDeltaBlocks",17),
                integer(plan,"maxFoundationDepthBlocks",64));
        profile.add("surfaceColumns",samples);
        plan.add("wallPlacementProfile",profile);
    }

    static JsonObject heightProfile(List<Integer> surfaces, int maximumRelief, int maximumFoundation) {
        JsonObject result=new JsonObject();
        if(surfaces.isEmpty()) { result.addProperty("ok",false); result.addProperty("reasonCode","WALL_SURFACE_REQUIRED"); return result; }
        List<Integer> sorted=surfaces.stream().sorted().toList();
        int min=sorted.get(0), max=sorted.get(sorted.size()-1);
        int base=Math.max(sorted.get(sorted.size()/2), max-6);
        boolean valid=max-min<maximumRelief && base-min<=maximumFoundation;
        result.addProperty("ok",valid); result.addProperty("baseY",base);
        result.addProperty("minSurfaceY",min); result.addProperty("maxSurfaceY",max);
        result.addProperty("walkwayFloorY",base+9);
        result.addProperty("reasonCode",valid?"WALL_COMMON_WALKWAY_DATUM":"WALL_TERRAIN_REQUIRES_REDESIGN");
        return result;
    }

    public JsonObject execute(ServerLevel level, JsonObject plan) { return execute(level,plan,false,1); }

    public JsonObject execute(ServerLevel level, JsonObject plan, boolean debugScan, int debugScanStepBlocks) {
        JsonObject report=new JsonObject(); report.addProperty("schema","city_wall_placement_report");
        report.addProperty("cityId",string(plan,"cityId","")); report.addProperty("backend","fixed_structure_modules");
        report.addProperty("ok",false); report.addProperty("changedBlocks",0);
        if(level==null) return failure(report,"CITY_WALL_LEVEL_UNAVAILABLE");
        CityWallModuleConfig.Loaded modules;
        BlockState foundationState;
        BlockState[][][] wallStates;
        List<BlockState> towerStates;
        try {
            modules = CityWallModuleConfig.loadCurrent();
            modules.requireMatches(plan);
            foundationState = configuredBlock(modules.foundationBlock());
            if (foundationState.isAir() || !foundationState.getFluidState().isEmpty() || foundationState.hasBlockEntity())
                throw new IOException("WALL_FOUNDATION_BLOCK_INVALID");
            wallStates = wallGrid(modules.straightWall());
            towerStates = decodePalette(modules.guardTower());
        } catch (IOException | RuntimeException ex) { return failure(report, "WALL_MODULE_LOAD_FAILED:" + ex.getMessage()); }
        if(!"city_wall_plan".equals(string(plan,"schema","")) || !"guard_tower".equals(string(plan,"moduleSet","")))
            return failure(report,"WALL_MODULE_PLAN_REQUIRED");
        if(integer(plan,"nominalWallHeightBlocks",10)!=10) return failure(report,"WALL_FIXED_MODULE_HEIGHT_REQUIRED");
        if(array(plan,"generatedGates").isEmpty()) return failure(report,"WALL_EXIT_ROAD_REQUIRED");
        JsonObject profile=object(plan,"wallPlacementProfile");
        if(!bool(profile,"ok",false)) return failure(report,string(profile,"reasonCode","WALL_SURFACE_PROFILE_REQUIRED"));
        int base=integer(profile,"baseY",0);
        if(base<level.getMinBuildHeight() || base+15>=level.getMaxBuildHeight()) return failure(report,"WALL_WORLD_HEIGHT_LIMIT");
        String conflict=geometryConflict(plan);
        if(!conflict.isEmpty()) return failure(report,conflict);
        List<BlockBounds> nodes=new ArrayList<>();
        for(JsonElement element:array(plan,"wallNodes")) nodes.add(bounds(element.getAsJsonObject().getAsJsonObject("blockBounds")));
        Map<BlockPoint,Integer> surfaces=new HashMap<>(); Set<BlockPoint> fluids=new HashSet<>();
        for(JsonElement element:array(profile,"surfaceColumns")) {
            JsonObject cell=element.getAsJsonObject(); BlockPoint point=new BlockPoint(integer(cell,"x",0),integer(cell,"z",0));
            surfaces.put(point,integer(cell,"surfaceY",base)); if(bool(cell,"fluid",false)) fluids.add(point);
        }
        Map<BlockPos,BlockState> changes=new LinkedHashMap<>();
        for(JsonElement element:array(plan,"wallUnits")) {
            JsonObject unit=element.getAsJsonObject(); BlockBounds area=bounds(unit.getAsJsonObject("blockBounds"));
            boolean horizontal="X".equals(string(unit,"wallAxis",""));
            boolean gate="gate_gap".equals(string(unit,"unitType",""));
            int water=0;
            for(int z=area.minZ();z<=area.maxZ();z++) for(int x=area.minX();x<=area.maxX();x++) {
                BlockPoint point=new BlockPoint(x,z);
                if(!surfaces.containsKey(point)) return failure(report,"WALL_SURFACE_PROFILE_INCOMPLETE");
                int surface=surfaces.get(point); if(fluids.contains(point)) water++;
                if(contains(nodes,x,z)) continue;
                if(gate && base+9-surface<4) return failure(report,"WALL_GATE_HEADROOM_CONFLICT");
                if(!gate) foundation(changes,x,z,surface,base,foundationState);
                int across=horizontal?z-area.minZ():x-area.minX();
                int along=horizontal?x:z;
                for(int y=gate?9:0;y<12;y++) {
                    BlockState state = wallStates[Math.floorMod(along,16)][y][horizontal ? across : 4-across];
                    if (!horizontal) state = state.rotate(net.minecraft.world.level.block.Rotation.CLOCKWISE_90);
                    changes.put(new BlockPos(x,base+y,z),state);
                }
            }
            if(!gate && water>=area.widthBlocks()*area.heightBlocks()*0.8)
                return failure(report,"WALL_WATER_BOUNDARY_REQUIRES_REDESIGN");
        }
        try {
            CompoundTag template=modules.guardTower();
            ListTag blocks=template.getList("blocks",Tag.TAG_COMPOUND);
            for(JsonElement element:array(plan,"wallNodes")) {
                BlockBounds area=bounds(element.getAsJsonObject().getAsJsonObject("blockBounds"));
                for(int z=area.minZ();z<=area.maxZ();z++) for(int x=area.minX();x<=area.maxX();x++) {
                    Integer surface=surfaces.get(new BlockPoint(x,z));
                    if(surface==null) return failure(report,"WALL_SURFACE_PROFILE_INCOMPLETE");
                    foundation(changes,x,z,surface,base,foundationState);
                }
                for(int i=0;i<blocks.size();i++) {
                    CompoundTag block=blocks.getCompound(i); ListTag pos=block.getList("pos",Tag.TAG_INT);
                    changes.put(new BlockPos(area.minX()+pos.getInt(0),base+pos.getInt(1),area.minZ()+pos.getInt(2)),towerStates.get(block.getInt("state")));
                }
            }
        } catch(RuntimeException error) { return failure(report,"WALL_MODULE_LOAD_FAILED:"+error.getMessage()); }
        // Whole-plan preflight prevents partial towers, clipped buildings and silent gaps.
        for(BlockPos pos:changes.keySet()) if(level.getBlockEntity(pos)!=null) return failure(report,"WALL_BLOCK_ENTITY_CONFLICT");
        Map<BlockPos,BlockState> original=new LinkedHashMap<>(); int changed=0;
        try {
            for(var entry:changes.entrySet()) {
                BlockState before=level.getBlockState(entry.getKey()); if(before.equals(entry.getValue())) continue;
                original.put(entry.getKey(),before);
                if(!level.setBlock(entry.getKey(),entry.getValue(),2) && !level.getBlockState(entry.getKey()).equals(entry.getValue()))
                    throw new IllegalStateException("WALL_BLOCK_WRITE_FAILED");
                changed++;
            }
            for(BlockPos pos:original.keySet()) level.updateNeighborsAt(pos,level.getBlockState(pos).getBlock());
        } catch(RuntimeException error) {
            List<Map.Entry<BlockPos,BlockState>> reverse=new ArrayList<>(original.entrySet()); Collections.reverse(reverse);
            for(var entry:reverse) level.setBlock(entry.getKey(),entry.getValue(),2);
            return failure(report,"WALL_PLACEMENT_ROLLED_BACK:"+error.getMessage());
        }
        for(var entry:changes.entrySet()) {
            if(!level.getBlockState(entry.getKey()).equals(entry.getValue())) {
                for(var previous:original.entrySet()) level.setBlock(previous.getKey(),previous.getValue(),2);
                return failure(report,"WALL_MODULE_POST_UPDATE_MISMATCH_ROLLED_BACK");
            }
        }
        report.addProperty("ok",true); report.addProperty("reasonCode","WALL_MODULES_PLACED");
        report.addProperty("changedBlocks",changed); report.addProperty("baseY",base);
        report.addProperty("executedSegments",array(plan,"wallUnits").size()+nodes.size()); report.addProperty("skippedSegments",0);
        report.addProperty("walkwayFloorY",base+9);
        report.add("wallModuleSnapshot", modules.snapshot().deepCopy());
        if(debugScan) report.add("wallTerrainDebugScan",profile.deepCopy());
        return report;
    }

    static String geometryConflict(JsonObject plan) {
        List<BlockBounds> structures=new ArrayList<>(), roads=new ArrayList<>();
        for(JsonElement element:array(object(plan,"sourcePlacedStructureLedger"),"placedStructures")) {
            JsonObject placed=element.getAsJsonObject();
            JsonObject footprint=object(placed,"lockedActualFootprint");
            if(footprint.size()==0) footprint=object(placed,"actualFootprint");
            if(footprint.size()>0) structures.add(bounds(footprint));
        }
        for(JsonElement element:array(object(plan,"actualRoadMask"),"roadMask"))
            if(element.isJsonObject() && element.getAsJsonObject().has("blockBounds")) roads.add(bounds(element.getAsJsonObject().getAsJsonObject("blockBounds")));
        for(String key:List.of("wallUnits","wallNodes")) for(JsonElement element:array(plan,key)) {
            JsonObject module=element.getAsJsonObject(); BlockBounds area=bounds(module.getAsJsonObject("blockBounds"));
            if(structures.stream().anyMatch(area::overlaps)) return "WALL_STRUCTURE_CONFLICT";
            if(!"gate_gap".equals(string(module,"unitType","")) && roads.stream().anyMatch(area::overlaps))
                return "WALL_UNRESERVED_ROAD_CONFLICT";
        }
        return "";
    }

    static BlockState configuredBlock(String name) throws IOException {
        var id = net.minecraft.resources.ResourceLocation.tryParse(name);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) throw new IOException("WALL_BLOCK_UNKNOWN:" + name);
        return BuiltInRegistries.BLOCK.get(id).defaultBlockState();
    }
    static List<BlockState> decodePalette(CompoundTag template) throws IOException {
        List<BlockState> states = new ArrayList<>();
        ListTag palette = template.getList("palette", Tag.TAG_COMPOUND);
        for (int i = 0; i < palette.size(); i++) {
            CompoundTag entry = palette.getCompound(i);
            configuredBlock(entry.getString("Name"));
            BlockState state = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), entry);
            if (state.hasBlockEntity()) throw new IOException("WALL_BLOCK_ENTITY_TEMPLATE_UNSUPPORTED");
            states.add(state);
        }
        return states;
    }
    static BlockState[][][] wallGrid(CompoundTag template) throws IOException {
        var states = decodePalette(template);
        BlockState[][][] grid = new BlockState[16][12][5];
        for (var entry : template.getList("blocks", Tag.TAG_COMPOUND)) {
            CompoundTag block = (CompoundTag) entry;
            ListTag pos = block.getList("pos", Tag.TAG_INT);
            grid[pos.getInt(0)][pos.getInt(1)][pos.getInt(2)] = states.get(block.getInt("state"));
        }
        return grid;
    }
    private static void foundation(Map<BlockPos,BlockState> changes,int x,int z,int surface,int base,BlockState state) {
        for(int y=surface;y<base;y++) changes.put(new BlockPos(x,y,z),state);
    }
    private static boolean contains(List<BlockBounds> areas,int x,int z) { return areas.stream().anyMatch(b->b.contains(x,z)); }
    private static JsonObject failure(JsonObject report,String reason) {report.addProperty("reasonCode",reason);return report;}
    private static JsonArray array(JsonObject object,String key) {return object.has(key)&&object.get(key).isJsonArray()?object.getAsJsonArray(key):new JsonArray();}
    private static JsonObject object(JsonObject object,String key) {return object.has(key)&&object.get(key).isJsonObject()?object.getAsJsonObject(key):new JsonObject();}
    private static String string(JsonObject object,String key,String fallback) {return object.has(key)?object.get(key).getAsString():fallback;}
    private static int integer(JsonObject object,String key,int fallback) {return object.has(key)?object.get(key).getAsInt():fallback;}
    private static boolean bool(JsonObject object,String key,boolean fallback) {return object.has(key)?object.get(key).getAsBoolean():fallback;}
    private static BlockBounds bounds(JsonObject object) {return new BlockBounds(integer(object,"minX",0),integer(object,"minZ",0),integer(object,"maxX",0),integer(object,"maxZ",0));}
}
