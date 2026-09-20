package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.application.CityWallTerrainPlanner;
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
    /** Samples the generator only. Never obtains or generates a world chunk. */
    public static void prepare(ServerLevel level, JsonObject plan) {
        if (level == null) return;
        try { CityWallModuleConfig.loadCurrent().freeze(plan); }
        catch (IOException ex) { throw new IllegalArgumentException(ex.getMessage(), ex); }
        var nativeTerrain = com.rinsing.geomantia.systems.realm_planning.adapter.minecraft.RtfTerrainPreviewProvider.probe(level);
        var generator = level.getChunkSource().getGenerator();
        var random = level.getChunkSource().randomState();
        Map<BlockPoint,Integer> sampled = new HashMap<>();
        Map<BlockPoint,Integer> surfaces = new LinkedHashMap<>();
        JsonArray samples = new JsonArray();
        for (String key : List.of("wallUnits", "wallNodes")) for (JsonElement element : array(plan,key)) {
            BlockBounds area = bounds(element.getAsJsonObject().getAsJsonObject("blockBounds"));
            for (int z=area.minZ();z<=area.maxZ();z++) for(int x=area.minX();x<=area.maxX();x++) {
                BlockPoint point = new BlockPoint(x,z);
                if (surfaces.containsKey(point)) continue;
                BlockPoint sample = new BlockPoint(Math.floorDiv(x,4)*4,Math.floorDiv(z,4)*4);
                int y = sampled.computeIfAbsent(sample, p -> nativeTerrain.availability().available()
                        ? (int)Math.floor(nativeTerrain.sample(p.x(),p.z()).elevation())+1
                        : generator.getBaseHeight(p.x(),p.z(),net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG,level,random));
                surfaces.put(point,y);
                JsonObject c=new JsonObject();c.addProperty("x",x);c.addProperty("z",z);c.addProperty("surfaceY",y);
                c.addProperty("fluid",y<=level.getSeaLevel());samples.add(c);
            }
        }
        JsonObject profile = new CityWallTerrainPlanner().plan(plan,samples);
        profile.addProperty("barrierScanBottomY",Math.max(level.getMinBuildHeight(),integer(profile,"minSurfaceY",0)-2));
        plan.add("wallPlacementProfile",profile);
        plan.addProperty("placementMode","chunk_worldgen");
    }

    private static boolean naturalSolid(BlockState state) {
        return state.getFluidState().isEmpty() && (state.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD)
                || state.is(net.minecraft.tags.BlockTags.DIRT)
                || state.is(net.minecraft.world.level.block.Blocks.CLAY));
    }

    static boolean verifiedBarrier(int surface, int base, int bottom, java.util.function.IntPredicate solidAtY) {
        if(surface<=base+12 || bottom>base+12)return false;
        for(int y=bottom;y<=base+12;y++)if(!solidAtY.test(y))return false;
        return true;
    }

    static int foundationStart(int surface, int base, int worldBottom, java.util.function.IntPredicate solidAtY) {
        int y=Math.min(surface,base)-1;
        while(y>worldBottom && !solidAtY.test(y))y--;
        // A void at world bottom is filled rather than silently leaving the footing unsupported.
        return y==worldBottom && !solidAtY.test(y)?worldBottom:y+1;
    }

    public JsonObject execute(ServerLevel level, JsonObject plan) {
        JsonObject report=new JsonObject(); report.addProperty("ok",false); report.addProperty("changedBlocks",0);
        return failure(report,level==null?"CITY_WALL_LEVEL_UNAVAILABLE":"WALL_FIRST_WORLDGEN_REQUIRED");
    }

    public JsonObject executeFragment(net.minecraft.world.level.WorldGenLevel level, JsonObject plan,
                                      BlockBounds owner, CityWallModuleConfig.Loaded modules) {
        JsonObject report=new JsonObject(); report.addProperty("schema","city_wall_placement_report");
        report.addProperty("cityId",string(plan,"cityId","")); report.addProperty("backend","fixed_structure_modules");
        report.addProperty("ok",false); report.addProperty("changedBlocks",0);
        if(level==null) return failure(report,"CITY_WALL_LEVEL_UNAVAILABLE");
        BlockState foundationState;
        BlockState[][][] wallStates;
        List<BlockState> towerStates;
        try {
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
        // A pending intercity link is not authority to deny construction of the reserved wall belt.
        report.addProperty("exitRoadStatus", array(plan,"generatedGates").isEmpty() ? "NO_PLANNED_EXIT" : "planned");
        JsonObject profile=object(plan,"wallPlacementProfile");
        if(!bool(profile,"ok",false)) return failure(report,string(profile,"reasonCode","WALL_SURFACE_PROFILE_REQUIRED"));
        if(!CityWallTerrainPlanner.POLICY.equals(string(profile,"policy",""))) return failure(report,"WALL_TERRAIN_PROFILE_REPLAN_REQUIRED");
        if(integer(profile,"minBaseY",0)<level.getMinBuildHeight()
                || integer(profile,"maxBaseY",0)+15>=level.getMaxBuildHeight()) return failure(report,"WALL_WORLD_HEIGHT_LIMIT");
        String conflict=geometryConflict(plan);
        if(!conflict.isEmpty()) return failure(report,conflict);
        List<BlockBounds> nodes=new ArrayList<>();
        for(JsonElement element:array(plan,"wallNodes")) nodes.add(bounds(element.getAsJsonObject().getAsJsonObject("blockBounds")));
        List<BlockBounds> gates=new ArrayList<>();
        for(var e:array(plan,"wallUnits")) if("gate_gap".equals(string(e.getAsJsonObject(),"unitType","")))
            gates.add(bounds(e.getAsJsonObject().getAsJsonObject("blockBounds")));
        Map<BlockPoint,Integer> surfaces=new HashMap<>(), bases=new HashMap<>(); Set<BlockPoint> natural=new HashSet<>();
        for(JsonElement element:array(profile,"surfaceColumns")) {
            JsonObject cell=element.getAsJsonObject(); BlockPoint point=new BlockPoint(integer(cell,"x",0),integer(cell,"z",0));
            surfaces.put(point,integer(cell,"surfaceY",0));bases.put(point,integer(cell,"baseY",0));
            if("natural_barrier".equals(string(cell,"terrainMode","")))natural.add(point);
        }
        int surveyBottom=integer(profile,"barrierScanBottomY",integer(profile,"minSurfaceY",0)-2);
        natural.clear();
        for (var p : bases.keySet()) {
            if (!owner.contains(p.x(),p.z())) continue;
            int surface=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG,p.x(),p.z());
            surfaces.put(p,surface);
        }
        for (var e : array(plan,"wallUnits")) {
            var unit=e.getAsJsonObject();
            if("gate_gap".equals(string(unit,"unitType","")))continue;
            var area=bounds(unit.getAsJsonObject("blockBounds"));
            boolean horizontal="X".equals(string(unit,"wallAxis",""));
            for(int along=horizontal?area.minX():area.minZ();along<=(horizontal?area.maxX():area.maxZ());along++) {
                List<BlockPoint> slice=new ArrayList<>();boolean verified=true;
                for(int across=0;across<5;across++) {
                    var p=new BlockPoint(horizontal?along:area.minX()+across,horizontal?area.minZ()+across:along);
                    if(!owner.contains(p.x(),p.z()) || contains(nodes,p.x(),p.z()) || contains(gates,p.x(),p.z())) {verified=false;break;}
                    if(!verifiedBarrier(surfaces.get(p),bases.get(p),surveyBottom,y -> {
                        var at=new BlockPos(p.x(),y,p.z());var state=level.getBlockState(at);
                        return naturalSolid(state) && state.isCollisionShapeFullBlock(level,at);
                    })) {verified=false;break;}
                    slice.add(p);
                }
                if(verified)natural.addAll(slice);
            }
        }
        Map<BlockPoint,Integer> supports=new HashMap<>();
        for(var p:bases.keySet()) {
            if(!owner.contains(p.x(),p.z()))continue;
            int start=foundationStart(surfaces.get(p),bases.get(p),level.getMinBuildHeight(),y -> {
                var at=new BlockPos(p.x(),y,p.z());var state=level.getBlockState(at);
                return state.getFluidState().isEmpty() && state.isCollisionShapeFullBlock(level,at);
            });
            supports.put(p,start);
        }
        Map<BlockPos,BlockState> changes=new LinkedHashMap<>();
        for(JsonElement element:array(plan,"wallUnits")) {
            JsonObject unit=element.getAsJsonObject(); BlockBounds area=bounds(unit.getAsJsonObject("blockBounds"));
            boolean horizontal="X".equals(string(unit,"wallAxis",""));
            boolean gate="gate_gap".equals(string(unit,"unitType",""));
            for(int z=area.minZ();z<=area.maxZ();z++) for(int x=area.minX();x<=area.maxX();x++) {
                if(!owner.contains(x,z))continue;
                BlockPoint point=new BlockPoint(x,z);
                if(!surfaces.containsKey(point)) return failure(report,"WALL_SURFACE_PROFILE_INCOMPLETE");
                int surface=surfaces.get(point), base=bases.get(point);
                if(natural.contains(point))continue;
                if(contains(nodes,x,z)) continue;
                if(gate && base+9-surface<4) return failure(report,"WALL_GATE_HEADROOM_CONFLICT");
                if(!gate) foundation(changes,x,z,supports.get(new BlockPoint(x,z)),base,foundationState);
                int across=horizontal?z-area.minZ():x-area.minX();
                int along=horizontal?x:z;
                for(int y=gate?9:0;y<12;y++) {
                    BlockState state = wallStates[Math.floorMod(along,16)][y][horizontal ? across : 4-across];
                    if (!horizontal) state = state.rotate(net.minecraft.world.level.block.Rotation.CLOCKWISE_90);
                    // Embed into hills without excavating the mountain below its walkway.
                    if(!state.isAir() || y>=9) changes.put(new BlockPos(x,base+y,z),state);
                }
            }
            // Water is supported by local retaining foundations, not a city-wide height rejection.
        }
        try {
            CompoundTag template=modules.guardTower();
            ListTag blocks=template.getList("blocks",Tag.TAG_COMPOUND);
            for(JsonElement element:array(plan,"wallNodes")) {
                BlockBounds area=bounds(element.getAsJsonObject().getAsJsonObject("blockBounds"));
                int base=integer(element.getAsJsonObject(),"baseY",0);
                for(int z=area.minZ();z<=area.maxZ();z++) for(int x=area.minX();x<=area.maxX();x++) {
                    if(!owner.contains(x,z))continue;
                    Integer surface=surfaces.get(new BlockPoint(x,z));
                    if(surface==null) return failure(report,"WALL_SURFACE_PROFILE_INCOMPLETE");
                    foundation(changes,x,z,supports.get(new BlockPoint(x,z)),base,foundationState);
                }
                for(int i=0;i<blocks.size();i++) {
                    CompoundTag block=blocks.getCompound(i); ListTag pos=block.getList("pos",Tag.TAG_INT);
                    BlockPos at=new BlockPos(area.minX()+pos.getInt(0),base+pos.getInt(1),area.minZ()+pos.getInt(2));
                    if(!owner.contains(at.getX(),at.getZ()))continue;
                    BlockState state=towerStates.get(block.getInt("state"));
                    if(!state.isAir() || pos.getInt(1)>=9)changes.put(at,state);
                }
            }
        } catch(RuntimeException error) { return failure(report,"WALL_MODULE_LOAD_FAILED:"+error.getMessage()); }
        int sealed=0,stairCount=0;
        int sealBottom=Math.max(level.getMinBuildHeight(),integer(profile,"barrierScanBottomY",integer(profile,"minSurfaceY",0)-2));
        // Seal cross-boundary holes only within the reserved strip and the frozen vertical survey band.
        for(var entry:bases.entrySet()) {
            var p=entry.getKey();int base=entry.getValue();
            if(!owner.contains(p.x(),p.z()))continue;
            if(natural.contains(p))continue;
            boolean gate=false;
            for(var e:array(plan,"wallUnits")) {
                var u=e.getAsJsonObject();if("gate_gap".equals(string(u,"unitType",""))
                        && bounds(u.getAsJsonObject("blockBounds")).contains(p.x(),p.z())) {gate=true;break;}
            }
            if(gate)continue;
            for(int y=sealBottom;y<Math.min(base,surfaces.get(p));y++) {
                var at=new BlockPos(p.x(),y,p.z());var state=level.getBlockState(at);
                if(state.isAir()||!state.getFluidState().isEmpty()) {changes.put(at,foundationState);sealed++;}
            }
        }
        // Stair strips connect different local elevations; tower footprints remain level.
        for(var e:array(plan,"wallUnits")) {
            var u=e.getAsJsonObject();var area=bounds(u.getAsJsonObject("blockBounds"));
            boolean horizontal="X".equals(string(u,"wallAxis",""));
            for(int z=area.minZ();z<=area.maxZ();z++)for(int x=area.minX();x<=area.maxX();x++) {
                if(!owner.contains(x,z))continue;
                var p=new BlockPoint(x,z);if(natural.contains(p)||contains(nodes,x,z))continue;
                int across=horizontal?z-area.minZ():x-area.minX();if(across==0||across==4)continue;
                int base=bases.get(p);net.minecraft.core.Direction up=null;int count=0;
                for(var direction:List.of(net.minecraft.core.Direction.NORTH,net.minecraft.core.Direction.SOUTH,
                        net.minecraft.core.Direction.EAST,net.minecraft.core.Direction.WEST)) {
                    var q=new BlockPoint(x+direction.getStepX(),z+direction.getStepZ());
                    if(!natural.contains(q)&&bases.getOrDefault(q,base)==base+1) {up=direction;count++;}
                }
                if(count==0)continue;
                var state=count==1?net.minecraft.world.level.block.Blocks.STONE_BRICK_STAIRS.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.StairBlock.FACING,up)
                        :net.minecraft.world.level.block.Blocks.STONE_BRICK_SLAB.defaultBlockState();
                changes.put(new BlockPos(x,base+10,z),state);
                changes.put(new BlockPos(x,base+11,z),net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                changes.put(new BlockPos(x,base+12,z),net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());stairCount++;
            }
        }
        // Each owner is atomic, and may never write a neighbour even when a module spans the boundary.
        for(BlockPos pos:changes.keySet()) {
            if(!owner.contains(pos.getX(),pos.getZ()))return failure(report,"WALL_OWNER_WRITE_OUTSIDE_CHUNK");
            if(level.getBlockEntity(pos)!=null)return failure(report,"WALL_BLOCK_ENTITY_CONFLICT");
        }
        Map<BlockPos,BlockState> original=new LinkedHashMap<>(); int changed=0;
        try {
            for(var entry:changes.entrySet()) {
                BlockState before=level.getBlockState(entry.getKey()); if(before.equals(entry.getValue())) continue;
                original.put(entry.getKey(),before);
                if(!level.setBlock(entry.getKey(),entry.getValue(),2) && !level.getBlockState(entry.getKey()).equals(entry.getValue()))
                    throw new IllegalStateException("WALL_BLOCK_WRITE_FAILED");
                changed++;
            }
            // FEATURES writes stay within this owner; do not send cross-chunk neighbour updates.
        } catch(RuntimeException error) {
            List<Map.Entry<BlockPos,BlockState>> reverse=new ArrayList<>(original.entrySet()); Collections.reverse(reverse);
            for(var entry:reverse) level.setBlock(entry.getKey(),entry.getValue(),2);
            return failure(report,"WALL_PLACEMENT_ROLLED_BACK:"+error.getMessage());
        }
        report.addProperty("ok",true); report.addProperty("reasonCode","WALL_MODULES_PLACED");
        report.addProperty("changedBlocks",changed);report.addProperty("heightPolicy",CityWallTerrainPlanner.POLICY);
        report.addProperty("minBaseY",integer(profile,"minBaseY",0));report.addProperty("maxBaseY",integer(profile,"maxBaseY",0));
        report.addProperty("stairBlockCount",stairCount);report.addProperty("sealedCavityBlocks",sealed);
        report.addProperty("naturalBarrierSectionCount",natural.size()/5);
        report.addProperty("executedSegments",array(plan,"wallUnits").size()+nodes.size()); report.addProperty("skippedSegments",0);
        report.add("wallModuleSnapshot", modules.snapshot().deepCopy());
        return report;
    }

    static String geometryConflict(JsonObject plan) {
        List<BlockBounds> owned = new ArrayList<>();
        JsonObject reservation = object(plan, "wallReservationSource");
        for (String key : List.of("wallCorridorMask", "wallNodeSlots", "gateCorridorMask"))
            for (JsonElement element : array(reservation, key))
                owned.add(bounds(element.getAsJsonObject().getAsJsonObject("blockBounds")));
        for (String key : List.of("wallUnits", "wallNodes")) for (JsonElement element : array(plan, key)) {
            BlockBounds area = bounds(element.getAsJsonObject().getAsJsonObject("blockBounds"));
            for (int z = area.minZ(); z <= area.maxZ(); z++) for (int x = area.minX(); x <= area.maxX(); x++) {
                if (!contains(owned, x, z)) return "WALL_OUTSIDE_OWNED_RESERVATION";
            }
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
