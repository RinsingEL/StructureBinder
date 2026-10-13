package com.rinsing.geomantia.adventure;

import com.rinsing.geomantia.api.regions.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.core.BlockPos;
import net.minecraft.util.random.WeightedRandomList;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.gametest.GameTestHolder;
import java.util.*;

/** Isolated Forge-bus acceptance fixture; no player save or production geography is rewritten. */
@GameTestHolder("geomantia_adventure")
public final class AdventureGameTests {
    @GameTest(template="empty",timeoutTicks=100)
    public static void settlementSuppressesOriginalAndPooledHostiles(GameTestHelper helper) {
        var level=helper.getLevel();var server=level.getServer();var old=AdventureMod.STATES.get(server);
        String reservation="geomantia_adventure:test_"+UUID.randomUUID();
        String[] siteId={""};
        try {
            var original=old.config;
            var controls=new AdventureConfig.Controls(1,original.controls().mobs(),Map.of("minecraft:ruined_portal",true,"minecraft:shipwreck",false));
            var config=new AdventureConfig(original.distribution(),original.mobs(),original.structures(),original.settlements(),controls,original.fingerprint());
            var pos=helper.absolutePos(new BlockPos(1,2,1));int gx=Math.floorDiv(pos.getX(),16),gz=Math.floorDiv(pos.getZ(),16);
            Map<Long,WorldRegionSnapshot.Cell> cells=new HashMap<>();
            for(int z=gz-2;z<=gz+2;z++)for(int x=gx-2;x<=gx+2;x++)cells.put(WorldRegionSnapshot.key(x,z),new WorldRegionSnapshot.Cell("a",true,false));
            var geography=new WorldRegionSnapshot("gametest","minecraft:overworld",16,"a",
                cells,Map.of("a",new WorldRegionSnapshot.Centre(pos.getX(),pos.getZ())));
            var state=new AdventureMod.State(config,new AdventureSites());
            state.world=new AdventureWorld(geography,config,server.getWorldPath(LevelResource.ROOT).resolve("adventure_fixture/tiers.json"));
            AdventureMod.STATES.put(server,state);
            helper.assertTrue(SettlementReservationApi.reserve(server,new SettlementReservationApi.Reservation(reservation,"minecraft:overworld","suburb",new RegionBounds(pos.getX()-8,pos.getZ()-8,pos.getX()+8,pos.getZ()+8))),"Suburb must reserve space");
            var event=new LevelEvent.PotentialSpawns(level,MobCategory.MONSTER,pos,WeightedRandomList.create(new MobSpawnSettings.SpawnerData(EntityType.CREEPER,100,1,1)));
            MinecraftForge.EVENT_BUS.post(event);
            helper.assertTrue(event.getSpawnerDataList().isEmpty(),"Suburb must exclude unmanaged vanilla creepers and pooled zombies/skeletons");
            var zombie=new Zombie(EntityType.ZOMBIE,level);zombie.setPos(pos.getX(),pos.getY(),pos.getZ());
            var spawn=new MobSpawnEvent.FinalizeSpawn(zombie,level,pos.getX(),pos.getY(),pos.getZ(),level.getCurrentDifficultyAt(pos),MobSpawnType.NATURAL,null,null,null);
            MinecraftForge.EVENT_BUS.post(spawn);helper.assertTrue(spawn.isSpawnCancelled(),"Final spawn gate must reject hostiles in suburb");
            helper.assertTrue(!SettlementReservationApi.reserve(server,new SettlementReservationApi.Reservation(reservation+"_dungeon","minecraft:overworld","adventure",new RegionBounds(pos.getX(),pos.getZ(),pos.getX()+16,pos.getZ()+16))),"Dungeon must not race into suburb");
            var disabled=level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE).get(new net.minecraft.resources.ResourceLocation("minecraft:shipwreck"));
            var attempt=new StructureDistributionEvent.Attempt(level,disabled,new net.minecraft.world.level.ChunkPos(pos));MinecraftForge.EVENT_BUS.post(attempt);
            helper.assertTrue(attempt.isCanceled(),"Replaced native structures must be rejected before expensive construction");
            var structure=level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE).get(new net.minecraft.resources.ResourceLocation("minecraft:ruined_portal"));
            var piece=new net.minecraft.world.level.levelgen.structure.structures.JungleTemplePiece(net.minecraft.util.RandomSource.create(1),pos.getX(),pos.getZ());
            var start=new net.minecraft.world.level.levelgen.structure.StructureStart(structure,new net.minecraft.world.level.ChunkPos(pos),0,new net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer(List.of(piece)));
            var rejected=new StructureDistributionEvent.Candidate(level,structure,start);MinecraftForge.EVENT_BUS.post(rejected);rejected.finish(!rejected.isCanceled());
            helper.assertTrue(rejected.isCanceled() && state.sites.snapshot().isEmpty(),"Even preserved original structures must respect suburb envelope");
            SettlementReservationApi.releaseFailed(server,reservation);
            var vetoed=new StructureDistributionEvent.Candidate(level,structure,start);MinecraftForge.EVENT_BUS.post(vetoed);vetoed.setCanceled(true);vetoed.finish(false);
            helper.assertTrue(state.sites.snapshot().isEmpty(),"A later listener veto must not leave a quest site");
            var accepted=new StructureDistributionEvent.Candidate(level,structure,start);MinecraftForge.EVENT_BUS.post(accepted);accepted.finish(!accepted.isCanceled());
            helper.assertTrue(!accepted.isCanceled() && state.sites.snapshot().size()==1,"An eligible start must be recorded");
            siteId[0]=state.sites.snapshot().get(0).id();
            helper.assertTrue(!SettlementReservationApi.reserve(server,new SettlementReservationApi.Reservation(reservation,"minecraft:overworld","village",new RegionBounds(pos.getX(),pos.getZ(),pos.getX()+4,pos.getZ()+4))),"Later village must avoid recorded dungeon");
            helper.succeed();
        } catch(Exception e) {throw new RuntimeException(e);}
        finally {if(old==null)AdventureMod.STATES.remove(server);else AdventureMod.STATES.put(server,old);SettlementReservationApi.releaseFailed(server,reservation);if(!siteId[0].isEmpty())SettlementReservationApi.releaseFailed(server,siteId[0]);}
    }
    @GameTest(template="empty",timeoutTicks=100)
    public static void authoredPoolCreatesARealStructureStartWithoutLoadingChunks(GameTestHelper helper) {
        var level=helper.getLevel();var server=level.getServer();var old=AdventureMod.STATES.get(server);
        AdventureMod.State fixture=null;
        try {
            var c=old.config;var original=c.structures().get("land_ruins");
            var generator=level.getChunkSource().getGenerator();var randomState=level.getChunkSource().randomState();
            int sampleHeight=generator.getFirstOccupiedHeight(1608,1608,net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG,level,randomState);
            var holder=generator.getBiomeSource().getNoiseBiome(402,net.minecraft.core.QuartPos.fromBlock(sampleHeight),402,randomState.sampler());
            var registry=level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
            String portal=registry.keySet().stream().filter(id->id.getNamespace().equals("minecraft") && id.getPath().startsWith("ruined_portal") && registry.get(id).biomes().contains(holder)).sorted().map(Object::toString).findFirst().orElseThrow();
            var entries=List.of(new AdventureConfig.StructureEntry(portal,100,original.entries().get(0).conditions()));
            var pools=new HashMap<>(c.structures());pools.put("land_ruins",new AdventureConfig.StructurePool("land_ruins",1,0,1,entries));
            var structureControls=new HashMap<>(c.controls().structures());structureControls.put(portal,false);
            var controls=new AdventureConfig.Controls(1,c.controls().mobs(),structureControls);
            var config=new AdventureConfig(c.distribution(),c.mobs(),pools,c.settlements(),controls,c.fingerprint());
            Map<Long,WorldRegionSnapshot.Cell> cells=new HashMap<>();
            for(int z=95;z<=110;z++)for(int x=95;x<=110;x++)cells.put(WorldRegionSnapshot.key(x,z),new WorldRegionSnapshot.Cell("a",true,false));
            var geography=new WorldRegionSnapshot("structure-gametest","minecraft:overworld",16,"a",cells,Map.of("a",new WorldRegionSnapshot.Centre(1608,1608)));
            fixture=new AdventureMod.State(config,new AdventureSites());fixture.world=new AdventureWorld(geography,config,server.getWorldPath(LevelResource.ROOT).resolve("adventure_fixture/structure_tiers.json"));
            AdventureMod.STATES.put(server,fixture);int loaded=level.getChunkSource().getLoadedChunksCount();boolean generated=false;
            for(int i=0;i<16 && !generated;i++) {
                var cp=new net.minecraft.world.level.ChunkPos(100+i%4,100+i/4);
                var chunk=new net.minecraft.world.level.chunk.ProtoChunk(cp,net.minecraft.world.level.chunk.UpgradeData.EMPTY,level,level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME),null);
                MinecraftForge.EVENT_BUS.post(new StructureDistributionEvent.Prepare(level,level.getChunkSource().getGenerator(),level.getChunkSource().getGeneratorState(),chunk,level.getStructureManager()));
                generated=chunk.getAllStarts().values().stream().anyMatch(net.minecraft.world.level.levelgen.structure.StructureStart::isValid);
            }
            int sampleY=level.getChunkSource().getGenerator().getFirstOccupiedHeight(1608,1608,net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG,level,level.getChunkSource().randomState());
            var sampleBiome=level.getChunkSource().getGenerator().getBiomeSource().getNoiseBiome(402,net.minecraft.core.QuartPos.fromBlock(sampleY),402,level.getChunkSource().randomState().sampler()).unwrapKey();
            helper.assertTrue(generated,"Author pool must create a valid vanilla StructureStart; y="+sampleY+", biome="+sampleBiome);
            helper.assertTrue(fixture.sites.snapshot().size()==1 && !fixture.sites.snapshot().get(0).complete(),"Registered start must not be reported as completed");
            helper.assertTrue(loaded==level.getChunkSource().getLoadedChunksCount(),"Candidate generation must not load chunks");
            helper.succeed();
        } catch(Exception e){throw new RuntimeException(e);}
        finally {
            if(fixture!=null)for(var site:fixture.sites.snapshot())SettlementReservationApi.releaseFailed(server,site.id());
            if(old==null)AdventureMod.STATES.remove(server);else AdventureMod.STATES.put(server,old);
        }
    }
}
