package com.rinsing.geomantia.adventure;

import com.mojang.logging.LogUtils;
import com.rinsing.geomantia.api.regions.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.*;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.event.level.*;
import net.minecraftforge.event.server.*;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Mod(AdventureMod.ID)
@Mod.EventBusSubscriber(modid=AdventureMod.ID,bus=Mod.EventBusSubscriber.Bus.FORGE)
public final class AdventureMod {
    public static final String ID="geomantia_adventure";
    private static final Logger LOG=LogUtils.getLogger();
    static final Map<MinecraftServer,State> STATES=new ConcurrentHashMap<>();
    static final class State {
        final AdventureConfig config;final AdventureSites sites;
        volatile AdventureWorld world;
        volatile List<WorldRegionApi.Settlement> settlements=List.of();
        final Map<String,Long> cooldowns=new HashMap<>();
        long nextRefresh;boolean failed;
        State(AdventureConfig c,AdventureSites s) {config=c;sites=s;}
    }
    @SubscribeEvent public static void starting(ServerStartingEvent event) {
        MinecraftServer server=event.getServer();
        try {
            AdventureConfig c=AdventureConfig.load(server.getServerDirectory().toPath().resolve("config/geomantia_adventure"));
            for(String id:c.controls().mobs().keySet()) if(!BuiltInRegistries.ENTITY_TYPE.containsKey(new ResourceLocation(id)))throw new IllegalArgumentException("Unknown adventure entity "+id);
            var registry=server.registryAccess().registryOrThrow(Registries.STRUCTURE);
            for(String id:c.controls().structures().keySet()) if(!registry.containsKey(new ResourceLocation(id)))throw new IllegalArgumentException("Unknown adventure structure "+id);
            var sites=server.overworld().getDataStorage().computeIfAbsent(AdventureSites::load,AdventureSites::new,"geomantia_adventure_sites");
            SettlementReservationApi.initialize(server);
            STATES.put(server,new State(c,sites));
            refresh(server,STATES.get(server));
        } catch(Exception e) {throw new IllegalStateException("Cannot initialize adventure distribution",e);}
    }
    @SubscribeEvent public static void geographyReady(GeographyReadyEvent event) {
        State s=STATES.get(event.server);if(s==null)return;
        try {refresh(event.server,s);}catch(Exception e){s.failed=true;throw new IllegalStateException("Adventure geography preparation failed",e);}
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {STATES.remove(event.getServer());}
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if(event.phase!=TickEvent.Phase.END)return;
        var server=event.getServer();State s=STATES.get(server);if(s==null || s.failed)return;
        long now=server.overworld().getGameTime();if(now<s.nextRefresh)return;s.nextRefresh=now+100;
        try {refresh(server,s);}catch(Exception e){s.failed=true;LOG.error("Adventure distribution disabled after snapshot error",e);}
        s.cooldowns.values().removeIf(expiry->expiry<=now);
        // Only inspect already loaded chunks; quest readiness must never force world generation.
        for(var chunk:s.sites.pendingChunks(256)) {
            var level=server.getLevel(net.minecraft.resources.ResourceKey.create(Registries.DIMENSION,new ResourceLocation(chunk.dimension())));
            if(level!=null && level.getChunkSource().getChunkNow(chunk.x(),chunk.z())!=null)s.sites.completedChunk(chunk.dimension(),chunk.x(),chunk.z());
        }
    }
    private static void refresh(MinecraftServer server,State state) throws Exception {
        if(state.world==null) {
            var snapshot=WorldRegionApi.load(server);
            if(snapshot.isPresent()) {
                var world=new AdventureWorld(snapshot.get(),state.config,server.getWorldPath(LevelResource.ROOT).resolve("geomantia_adventure/tiers.json"));
                state.settlements=WorldRegionApi.settlements(server,world.geography.step());
                state.world=world;
                LOG.info("Adventure distribution ready: {} continents, {} tiers",world.tiers.size(),state.config.distribution().tiers().size());
            }
        } else state.settlements=WorldRegionApi.settlements(server,state.world.geography.step());
    }
    /** Public read-only quest input. complete=false is a registered start, not a completed dungeon. */
    public static List<AdventureSites.Site> sites(MinecraftServer server) {State s=STATES.get(server);return s==null?List.of():s.sites.snapshot();}
    private static ServerLevel serverLevel(Object level) {return level instanceof ServerLevel l?l:level instanceof WorldGenLevel w?w.getLevel():null;}
    private static AdventureWorld.Location location(State s,ServerLevel level,BlockPos pos) {
        return s==null || s.failed || s.world==null?null:s.world.at(level.dimension().location().toString(),pos.getX(),pos.getZ());
    }
    private static String biome(LevelAccessor level,BlockPos pos) {return level.getBiome(pos).unwrapKey().map(k->k.location().toString()).orElse("");}
    private static boolean blocked(State s,ServerLevel level,RegionBounds box,String id,boolean structure) {
        String dimension=level.dimension().location().toString();
        for(var v:s.settlements) if(blockedBy(s.config,v.kind(),v.bounds(),box,id,structure))return true;
        for(var v:s.config.settlements().zones()) if(v.dimension().equals(dimension) && blockedBy(s.config,v.kind(),new RegionBounds(v.minX(),v.minZ(),v.maxX(),v.maxZ()),box,id,structure))return true;
        for(var v:SettlementReservationApi.settlements(level.getServer())) if(!v.kind().equals("adventure") && v.dimension().equals(dimension) && blockedBy(s.config,v.kind(),v.bounds(),box,id,structure,true))return true;
        return false;
    }
    private static boolean blockedBy(AdventureConfig c,String kind,RegionBounds zone,RegionBounds box,String id,boolean structure) {
        return blockedBy(c,kind,zone,box,id,structure,false);
    }
    private static boolean blockedBy(AdventureConfig c,String kind,RegionBounds zone,RegionBounds box,String id,boolean structure,boolean alreadyBuffered) {
        var r=c.settlements().rules().get(kind);if(r==null)return false;
        if(structure?(!r.blockStructures() || r.allowedStructures().contains(id)):(!r.blockMobs() || r.allowedMobs().contains(id)))return false;
        long n=alreadyBuffered?0:r.bufferBlocks();return zone.minX()-n<=box.maxX() && zone.maxX()+n>=box.minX() && zone.minZ()-n<=box.maxZ() && zone.maxZ()+n>=box.minZ();
    }
    private static RegionBounds point(BlockPos p) {return new RegionBounds(p.getX(),p.getZ(),p.getX(),p.getZ());}
    @SubscribeEvent public static void potential(LevelEvent.PotentialSpawns event) {
        ServerLevel level=serverLevel(event.getLevel());if(level==null)return;
        State s=STATES.get(level.getServer());var loc=location(s,level,event.getPos());if(loc==null)return;
        for(var old:List.copyOf(event.getSpawnerDataList())) {
            String id=BuiltInRegistries.ENTITY_TYPE.getKey(old.type).toString();
            if(Boolean.FALSE.equals(s.config.controls().mobs().get(id)) || (event.getMobCategory()==MobCategory.MONSTER || s.config.controls().mobs().containsKey(id)) && blocked(s,level,point(event.getPos()),id,false))event.removeSpawnerData(old);
        }
        String biome=biome(event.getLevel(),event.getPos());Set<String> added=new HashSet<>();
        for(String name:loc.selection().mobs()) for(var entry:s.config.mobs().get(name).entries()) {
            EntityType<?> type=BuiltInRegistries.ENTITY_TYPE.get(new ResourceLocation(entry.entity()));
            if(type.getCategory()!=event.getMobCategory() || !entry.conditions().allows(biome,event.getPos().getY()) || blocked(s,level,point(event.getPos()),entry.entity(),false) || !added.add(entry.entity()))continue;
            event.addSpawnerData(new MobSpawnSettings.SpawnerData(type,entry.weight(),entry.minCount(),entry.maxCount()));
        }
    }
    private record SpawnRule(AdventureConfig.MobPool pool,AdventureConfig.MobEntry entry) {}
    private static SpawnRule spawnRule(State s,AdventureWorld.Location loc,String id,String biome,int y) {
        for(String name:loc.selection().mobs()) {var pool=s.config.mobs().get(name);for(var e:pool.entries())if(e.entity().equals(id) && e.conditions().allows(biome,y))return new SpawnRule(pool,e);}
        return null;
    }
    @SubscribeEvent public static void spawn(MobSpawnEvent.FinalizeSpawn event) {
        if(event.getSpawnType()!=MobSpawnType.NATURAL && event.getSpawnType()!=MobSpawnType.CHUNK_GENERATION)return;
        ServerLevel level=event.getLevel().getLevel();State s=STATES.get(level.getServer());BlockPos pos=event.getEntity().blockPosition();var loc=location(s,level,pos);if(loc==null)return;
        String id=BuiltInRegistries.ENTITY_TYPE.getKey(event.getEntity().getType()).toString();
        if((event.getEntity().getType().getCategory()==MobCategory.MONSTER || s.config.controls().mobs().containsKey(id)) && blocked(s,level,point(pos),id,false)){event.setSpawnCancelled(true);return;}
        if(!s.config.controls().mobs().containsKey(id))return;
        var rule=spawnRule(s,loc,id,biome(event.getLevel(),pos),pos.getY());
        if(rule==null) {if(!s.config.controls().mobs().get(id))event.setSpawnCancelled(true);return;}
        var pool=rule.pool();
        // Finalization can occur on worldgen workers. Natural-only throttling never touches a level entity list off-thread.
        if(event.getEntity().getRandom().nextDouble()>=pool.chance()){event.setSpawnCancelled(true);return;}
        if(event.getSpawnType()==MobSpawnType.CHUNK_GENERATION)return;
        long now=level.getGameTime();String key=level.dimension().location()+":"+pool.id()+":"+Math.floorDiv(pos.getX(),64)+":"+Math.floorDiv(pos.getZ(),64);
        if(s.cooldowns.getOrDefault(key,Long.MIN_VALUE)>now || level.getEntitiesOfClass(Mob.class,new AABB(pos).inflate(pool.nearbyRadius()),m->m.getType()==event.getEntity().getType()).size()>=pool.nearbyLimit()) {
            event.setSpawnCancelled(true);return;
        }
        if(pool.cooldownTicks()>0)s.cooldowns.put(key,now+pool.cooldownTicks());
    }
    @SubscribeEvent public static void originalAttempt(StructureDistributionEvent.Attempt event) {
        State s=STATES.get(event.level.getServer());
        var loc=location(s,event.level,new BlockPos(event.chunk.getMiddleBlockX(),64,event.chunk.getMiddleBlockZ()));if(loc==null)return;
        String id=event.level.registryAccess().registryOrThrow(Registries.STRUCTURE).getKey(event.structure).toString();
        if(Boolean.FALSE.equals(s.config.controls().structures().get(id)))event.setCanceled(true);
    }
    @SubscribeEvent public static void originalStructure(StructureDistributionEvent.Candidate event) {
        State s=STATES.get(event.level.getServer());if(s==null)return;
        String id=event.level.registryAccess().registryOrThrow(Registries.STRUCTURE).getKey(event.structure).toString();
        if(!s.config.controls().structures().containsKey(id))return;
        var b=event.start.getBoundingBox();var loc=location(s,event.level,b.getCenter());if(loc==null)return;
        if(!s.config.controls().structures().get(id) || blocked(s,event.level,bounds(b),id,true))event.setCanceled(true);
        else {
            var reservation=reservation(s,event.level,id,event.start);
            boolean existing=SettlementReservationApi.snapshot(event.level.getServer()).stream().anyMatch(r->r.id().equals(reservation.id()));
            if(!SettlementReservationApi.reserve(event.level.getServer(),reservation))event.setCanceled(true);
            else event.afterDecision(()->recordSite(s,event.level,id,event.start,loc),()->{if(!existing)SettlementReservationApi.releaseFailed(event.level.getServer(),reservation.id());});
        }
    }
    @SubscribeEvent public static void prepare(StructureDistributionEvent.Prepare event) {
        State s=STATES.get(event.level.getServer());var cp=event.chunk.getPos();var loc=location(s,event.level,new BlockPos(cp.getMiddleBlockX(),64,cp.getMiddleBlockZ()));if(loc==null)return;
        var registry=event.level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        for(String poolId:loc.selection().structures()) {
            var pool=s.config.structures().get(poolId);var candidate=s.world.candidateAt(event.level.getSeed(),pool,cp.x,cp.z);if(candidate==null)continue;
            int y=event.generator.getFirstOccupiedHeight(cp.getMiddleBlockX(),cp.getMiddleBlockZ(),Heightmap.Types.WORLD_SURFACE_WG,event.chunk,event.state.randomState());
            var biomeHolder=event.generator.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(cp.getMiddleBlockX()),QuartPos.fromBlock(y),QuartPos.fromBlock(cp.getMiddleBlockZ()),event.state.randomState().sampler());
            String biome=biomeHolder.unwrapKey().map(k->k.location().toString()).orElse("");
            List<AdventureConfig.StructureEntry> entries=pool.entries().stream().filter(e->e.conditions().allows(biome,y) && registry.get(new ResourceLocation(e.structure())).biomes().contains(biomeHolder)).toList();
            if(entries.isEmpty())continue;
            long sum=entries.stream().mapToLong(AdventureConfig.StructureEntry::weight).sum();long roll=new Random(candidate.seed()).nextLong(sum);AdventureConfig.StructureEntry chosen=entries.get(0);
            for(var e:entries){roll-=e.weight();if(roll<0){chosen=e;break;}}
            Structure structure=registry.get(new ResourceLocation(chosen.structure()));var existing=event.chunk.getStartForStructure(structure);if(existing!=null && existing.isValid())continue;
            if(blocked(s,event.level,new RegionBounds(cp.getMinBlockX(),cp.getMinBlockZ(),cp.getMaxBlockX(),cp.getMaxBlockZ()),chosen.structure(),true))continue;
            var start=structure.generate(event.level.registryAccess(),event.generator,event.generator.getBiomeSource(),event.state.randomState(),event.templates,event.level.getSeed(),cp,0,event.chunk,structure.biomes()::contains);
            if(!start.isValid())continue;var box=start.getBoundingBox();
            if(box.minX()<cp.getMinBlockX()-128 || box.maxX()>cp.getMaxBlockX()+128 || box.minZ()<cp.getMinBlockZ()-128 || box.maxZ()>cp.getMaxBlockZ()+128)continue;
            if(WorldRegionApi.protects(event.level,box) || blocked(s,event.level,bounds(box),chosen.structure(),true))continue;
            var actual=location(s,event.level,box.getCenter());if(actual==null || !actual.region().equals(loc.region()))continue;
            if(!register(s,event.level,chosen.structure(),start,loc))continue;
            event.chunk.setStartForStructure(structure,start);
        }
    }
    private static RegionBounds bounds(BoundingBox b) {return new RegionBounds(b.minX(),b.minZ(),b.maxX(),b.maxZ());}
    private static SettlementReservationApi.Reservation reservation(State s,ServerLevel level,String structure,StructureStart start) {
        var b=start.getBoundingBox();var p=start.getChunkPos();String dimension=level.dimension().location().toString();String id=dimension+":"+structure+":"+p.x+":"+p.z;
        int margin=s.config.settlements().rules().values().stream().mapToInt(AdventureConfig.Rule::bufferBlocks).max().orElse(0);
        return new SettlementReservationApi.Reservation(id,dimension,"adventure",new RegionBounds(b.minX()-margin,b.minZ()-margin,b.maxX()+margin,b.maxZ()+margin));
    }
    private static boolean register(State s,ServerLevel level,String structure,StructureStart start,AdventureWorld.Location loc) {
        if(!SettlementReservationApi.reserve(level.getServer(),reservation(s,level,structure,start)))return false;
        recordSite(s,level,structure,start,loc);return true;
    }
    private static void recordSite(State s,ServerLevel level,String structure,StructureStart start,AdventureWorld.Location loc) {
        var b=start.getBoundingBox();var center=b.getCenter();var r=reservation(s,level,structure,start);
        s.sites.register(new AdventureSites.Site(r.id(),r.dimension(),structure,loc.region(),loc.tier(),center.getX(),center.getY(),center.getZ(),b.minX(),b.minZ(),b.maxX(),b.maxZ(),false,Set.of()));
    }
    @SubscribeEvent public static void chunkLoaded(ChunkEvent.Load event) {
        ServerLevel level=serverLevel(event.getLevel());if(level==null)return;State s=STATES.get(level.getServer());if(s!=null && event.getChunk().getStatus().isOrAfter(net.minecraft.world.level.chunk.ChunkStatus.FULL)){var p=event.getChunk().getPos();s.sites.completedChunk(level.dimension().location().toString(),p.x,p.z);}
    }
}
