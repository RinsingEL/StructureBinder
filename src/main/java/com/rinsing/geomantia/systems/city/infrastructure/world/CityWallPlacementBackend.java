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
    public static boolean regionAvailable(ServerLevel level, JsonObject plan) {
        if (level == null) return true;
        Set<Long> checked = new HashSet<>();
        for (String key : List.of("wallUnits", "wallNodes")) for (JsonElement element : array(plan,key)) {
            BlockBounds area = bounds(element.getAsJsonObject().getAsJsonObject("blockBounds"));
            // FULL generation can depend on neighbouring chunks. Respect unopened regions;
            // report a wait before requesting any chunks rather than interpreting UNLOADED as corruption.
            for (int z=Math.floorDiv(area.minZ(),16)-8; z<=Math.floorDiv(area.maxZ(),16)+8; z++)
                for (int x=Math.floorDiv(area.minX(),16)-8; x<=Math.floorDiv(area.maxX(),16)+8; x++)
                    if (checked.add(net.minecraft.world.level.ChunkPos.asLong(x,z))
                            && !com.rinsing.geomantia.platform.PlanningAreaAccessRuntime.permitsChunk(level,x,z))
                        return false;
        }
        return true;
    }

    public static void prepare(ServerLevel level, JsonObject plan) {
        if (level == null) return;
        try { CityWallModuleConfig.loadCurrent().freeze(plan); }
        catch (IOException ex) { throw new IllegalArgumentException(ex.getMessage(), ex); }
        Map<BlockPoint,Integer> surfaces = new LinkedHashMap<>();
        JsonArray samples = new JsonArray();
        Set<Long> loadedChunks = new HashSet<>();
        for (String key : List.of("wallUnits", "wallNodes")) for (JsonElement element : array(plan,key)) {
            BlockBounds bounds = bounds(element.getAsJsonObject().getAsJsonObject("blockBounds"));
            for (int z=bounds.minZ();z<=bounds.maxZ();z++) for(int x=bounds.minX();x<=bounds.maxX();x++) {
                BlockPoint point=new BlockPoint(x,z);
                if(surfaces.containsKey(point)) continue;
                long chunkKey = net.minecraft.world.level.ChunkPos.asLong(Math.floorDiv(x,16),Math.floorDiv(z,16));
                if (loadedChunks.add(chunkKey)) level.getChunk(Math.floorDiv(x,16),Math.floorDiv(z,16));
                CitySurfaceCache.Sample sample=CitySurfaceCache.sample(level,x,z);
                surfaces.put(point,sample.surfaceY());
                JsonObject cell=new JsonObject(); cell.addProperty("x",x); cell.addProperty("z",z);
                cell.addProperty("surfaceY",sample.surfaceY()); cell.addProperty("fluid",sample.fluid()); samples.add(cell);
            }
        }
        JsonObject profile = new CityWallTerrainPlanner().plan(plan, samples);
        int scanBottom = Math.max(level.getMinBuildHeight(), integer(profile,"minSurfaceY",0)-2);
        Map<BlockPoint,JsonObject> columns = new HashMap<>();
        for (var e:array(profile,"surfaceColumns")) {
            JsonObject c=e.getAsJsonObject(); BlockPoint p=new BlockPoint(integer(c,"x",0),integer(c,"z",0));
            columns.put(p,c);
            int base=integer(c,"baseY",0);
            boolean solid=verifiedBarrier(integer(c,"surfaceY",0),base,scanBottom,y -> {
                var at=new BlockPos(p.x(),y,p.z());var state=level.getBlockState(at);
                return naturalSolid(state) && state.isCollisionShapeFullBlock(level,at);
            });
            c.addProperty("solidBarrierVerified",solid);
        }
        List<BlockBounds> towers=new ArrayList<>();
        for(var e:array(plan,"wallNodes")) towers.add(bounds(e.getAsJsonObject().getAsJsonObject("blockBounds")));
        int natural=0;
        for(var e:array(plan,"wallUnits")) {
            var unit=e.getAsJsonObject();if("gate_gap".equals(string(unit,"unitType","")))continue;
            var area=bounds(unit.getAsJsonObject("blockBounds"));boolean horizontal="X".equals(string(unit,"wallAxis",""));
            int from=horizontal?area.minX():area.minZ(),to=horizontal?area.maxX():area.maxZ();
            int verified=0;
            for(int along=from;along<=to;along++) {
                List<JsonObject> slice=new ArrayList<>();boolean safe=true;
                for(int across=0;across<5;across++) {
                    int x=horizontal?along:area.minX()+across,z=horizontal?area.minZ()+across:along;
                    var c=columns.get(new BlockPoint(x,z));slice.add(c);
                    if(contains(towers,x,z)||!bool(c,"solidBarrierVerified",false))safe=false;
                }
                if(safe) {verified++;natural++;for(var c:slice)c.addProperty("terrainMode","natural_barrier");}
            }
            if(verified>0)unit.addProperty("terrainMode",verified==to-from+1?"natural_barrier":"mountain_embed");
        }
        profile.addProperty("naturalBarrierSectionCount",natural);
        profile.addProperty("barrierScanBottomY",scanBottom);
        plan.add("wallPlacementProfile",profile);
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
        if(!CityWallTerrainPlanner.POLICY.equals(string(profile,"policy",""))) return failure(report,"WALL_TERRAIN_PROFILE_REPLAN_REQUIRED");
        if(integer(profile,"minBaseY",0)<level.getMinBuildHeight()
                || integer(profile,"maxBaseY",0)+15>=level.getMaxBuildHeight()) return failure(report,"WALL_WORLD_HEIGHT_LIMIT");
        String conflict=geometryConflict(plan);
        if(!conflict.isEmpty()) return failure(report,conflict);
        List<BlockBounds> nodes=new ArrayList<>();
        for(JsonElement element:array(plan,"wallNodes")) nodes.add(bounds(element.getAsJsonObject().getAsJsonObject("blockBounds")));
        Map<BlockPoint,Integer> surfaces=new HashMap<>(), bases=new HashMap<>(); Set<BlockPoint> natural=new HashSet<>();
        for(JsonElement element:array(profile,"surfaceColumns")) {
            JsonObject cell=element.getAsJsonObject(); BlockPoint point=new BlockPoint(integer(cell,"x",0),integer(cell,"z",0));
            surfaces.put(point,integer(cell,"surfaceY",0));bases.put(point,integer(cell,"baseY",0));
            if("natural_barrier".equals(string(cell,"terrainMode","")))natural.add(point);
        }
        // A frozen classification cannot authorize skipping a mountain changed since planning.
        int surveyBottom=integer(profile,"barrierScanBottomY",integer(profile,"minSurfaceY",0)-2);
        boolean barrierChanged=false;
        for(var p:natural) {
            if(!verifiedBarrier(surfaces.get(p),bases.get(p),surveyBottom,y -> {
                var at=new BlockPos(p.x(),y,p.z());var state=level.getBlockState(at);
                return naturalSolid(state) && state.isCollisionShapeFullBlock(level,at);
            })) {barrierChanged=true;break;}
        }
        if(barrierChanged)natural.clear(); // Fall back to masonry and cave seals in the same reserved strip.
        Map<BlockPoint,Integer> supports=new HashMap<>();
        for(var p:bases.keySet()) {
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
                    Integer surface=surfaces.get(new BlockPoint(x,z));
                    if(surface==null) return failure(report,"WALL_SURFACE_PROFILE_INCOMPLETE");
                    foundation(changes,x,z,supports.get(new BlockPoint(x,z)),base,foundationState);
                }
                for(int i=0;i<blocks.size();i++) {
                    CompoundTag block=blocks.getCompound(i); ListTag pos=block.getList("pos",Tag.TAG_INT);
                    BlockPos at=new BlockPos(area.minX()+pos.getInt(0),base+pos.getInt(1),area.minZ()+pos.getInt(2));
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
            BlockState expected=entry.getValue();
            if(expected.is(net.minecraft.world.level.block.Blocks.STONE_BRICK_STAIRS))
                expected=net.minecraft.world.level.block.Block.updateFromNeighbourShapes(expected,level,entry.getKey());
            if(!level.getBlockState(entry.getKey()).equals(expected)) {
                for(var previous:original.entrySet()) level.setBlock(previous.getKey(),previous.getValue(),2);
                return failure(report,"WALL_MODULE_POST_UPDATE_MISMATCH_ROLLED_BACK");
            }
        }
        report.addProperty("ok",true); report.addProperty("reasonCode","WALL_MODULES_PLACED");
        report.addProperty("changedBlocks",changed);report.addProperty("heightPolicy",CityWallTerrainPlanner.POLICY);
        report.addProperty("minBaseY",integer(profile,"minBaseY",0));report.addProperty("maxBaseY",integer(profile,"maxBaseY",0));
        report.addProperty("stairBlockCount",stairCount);report.addProperty("sealedCavityBlocks",sealed);
        report.addProperty("naturalBarrierSectionCount",natural.size()/5);
        report.addProperty("executedSegments",array(plan,"wallUnits").size()+nodes.size()); report.addProperty("skippedSegments",0);
        report.add("wallModuleSnapshot", modules.snapshot().deepCopy());
        if(debugScan) report.add("wallTerrainDebugScan",profile.deepCopy());
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
