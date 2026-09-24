package com.rinsing.geomantia.platform;

import com.google.gson.*;
import com.rinsing.geomantia.systems.realm_planning.WorldSurveySettingsConfig;
import com.rinsing.geomantia.systems.realm_planning.application.access.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.nio.file.*;
import java.io.*;

/** Select and freeze the starter realm before player entry, independently of W/AI. */
@Mod.EventBusSubscriber(modid="geomantia")
public final class StarterRealmBootstrap {
    @SubscribeEvent public static void createSpawn(LevelEvent.CreateSpawnPosition event) {
        if(event.getLevel() instanceof ServerLevel level && level.dimension()==Level.OVERWORLD && initialize(level))
            event.setCanceled(true);
    }
    @SubscribeEvent public static void starting(ServerStartingEvent event) { initialize(event.getServer().overworld()); }
    private static boolean initialize(ServerLevel level) {
        Path game=level.getServer().getServerDirectory().toPath();
        Path world=level.getServer().getWorldPath(LevelResource.ROOT);
        Path target=world.resolve("geomantia_starter_realm.json");
        try {
            var config=PlanningAreaAccessConfig.loadOrCreate(game.resolve("config/geomantia/planning_area_access.json"));
            if(!config.enabled() || !config.managedDimensions().contains("minecraft:overworld")) return false;
            if(Files.isRegularFile(target)) {
                JsonObject saved=JsonParser.parseString(Files.readString(target)).getAsJsonObject();
                if(saved.has("selection") && "land_v1".equals(saved.get("selection").getAsString())) return true;
            }
            var survey=WorldSurveySettingsConfig.loadOrCreate(game.resolve("config/geomantia/world_survey.json"));
            var generator=level.getChunkSource().getGenerator();
            var random=level.getChunkSource().randomState();
            var spawn=level.getSharedSpawnPos();
            org.slf4j.LoggerFactory.getLogger(StarterRealmBootstrap.class).info("Selecting starter land within W radius {}",survey.planningRadiusBlocks());
            var site=StarterLandSelector.select(spawn.getX(),spawn.getZ(),survey.planningRadiusBlocks(),config.initialActivityRadiusBlocks(),(x,z)-> {
                int y=generator.getBaseHeight(x,z,Heightmap.Types.OCEAN_FLOOR_WG,level,random);
                if(y<=level.getSeaLevel() || y+2>=level.getMaxBuildHeight()) return new StarterLandSelector.Ground(y,false);
                var column=generator.getBaseColumn(x,z,level,random);
                var floor=column.getBlock(y-1);
                boolean dry=floor.getFluidState().isEmpty() && floor.blocksMotion()
                        && column.getBlock(y).isAir() && column.getBlock(y+1).isAir();
                return new StarterLandSelector.Ground(y,dry);
            });
            var area=new InitialExplorationArea(site.x(),site.z(),config.initialActivityRadiusBlocks());
            JsonObject description=area.description();
            description.addProperty("selection","land_candidate");
            description.addProperty("spawnY",site.y());
            description.addProperty("selectionEvidence",">=7/9 dry neighbourhood samples; four nearby slopes <=4 blocks; generator prior");
            if(Files.isRegularFile(target)) Files.copy(target,world.resolve("geomantia_starter_realm.before_land_"+System.currentTimeMillis()+".json"));
            Path temporary=Files.createTempFile(world,"starter-", ".json.tmp");
            Files.writeString(temporary,description.toString());
            Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);
            PlanningAreaAccessRuntime.invalidate(level.getServer());
            // Persist authority before loading the chosen chunk, so generation protection permits it.
            BlockPos actual=safeGeneratedPosition(level,site.x(),site.z());
            description.addProperty("spawnX",actual.getX());
            description.addProperty("spawnZ",actual.getZ());
            description.addProperty("spawnY",actual.getY());
            description.addProperty("selection","land_v1");
            Files.writeString(target,description.toString());
            level.setDefaultSpawnPos(actual,0);
            org.slf4j.LoggerFactory.getLogger(StarterRealmBootstrap.class).info("Starter realm land selected at {} radius {}",actual,area.radius());
            return true;
        } catch(IOException ex) { throw new UncheckedIOException("Cannot initialize land starter realm",ex); }
    }
    private static BlockPos safeGeneratedPosition(ServerLevel level,int x,int z) {
        for(int r=0;r<=16;r++) for(int dz=-r;dz<=r;dz++) for(int dx=-r;dx<=r;dx++) {
            if(Math.max(Math.abs(dx),Math.abs(dz))!=r) continue;
            // Level.getHeight does not load absent chunks and returns minBuildHeight.
            // During CreateSpawnPosition no spawn chunks exist yet: load the candidate
            // explicitly after its starter-area generation permission was persisted.
            var chunk=level.getChunk(Math.floorDiv(x+dx,16),Math.floorDiv(z+dz,16));
            int feetY=chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,(x+dx)&15,(z+dz)&15)+1;
            BlockPos feet=new BlockPos(x+dx,feetY,z+dz);
            var floor=level.getBlockState(feet.below());
            if(floor.is(BlockTags.LEAVES) || floor.is(Blocks.MAGMA_BLOCK) || floor.is(Blocks.CACTUS)
                    || floor.is(Blocks.CAMPFIRE) || floor.is(Blocks.SOUL_CAMPFIRE)) continue;
            if(level.getFluidState(feet.below()).isEmpty() && floor.isFaceSturdy(level,feet.below(),Direction.UP)
                    && level.getFluidState(feet).isEmpty() && level.getFluidState(feet.above()).isEmpty()
                    && level.getBlockState(feet).getCollisionShape(level,feet).isEmpty()
                    && level.getBlockState(feet.above()).getCollisionShape(level,feet.above()).isEmpty()) return feet;
        }
        throw new IllegalStateException("STARTER_GENERATED_SPAWN_UNSAFE: "+x+","+z);
    }
}
