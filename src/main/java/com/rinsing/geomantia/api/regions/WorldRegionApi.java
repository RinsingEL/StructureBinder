package com.rinsing.geomantia.api.regions;

import com.google.gson.*;
import com.rinsing.geomantia.platform.WorldScopedPlanningPaths;
import com.rinsing.geomantia.systems.realm_planning.application.access.*;
import com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityGenerationProtection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

/** Server-thread snapshot preparation. Call once after the sealed survey and starter are ready. */
public final class WorldRegionApi {
    private WorldRegionApi() {}
    public static Optional<WorldRegionSnapshot> load(MinecraftServer server) throws IOException {
        Path root=WorldScopedPlanningPaths.realmDebugRoot(server), run=WorldEntrySurvey.savedRun(root);
        if(run==null || !WorldEntrySurvey.samplesSealed(run)) return Optional.empty();
        var config=PlanningAreaAccessConfig.loadOrCreate(server.getServerDirectory().toPath().resolve("config/geomantia/planning_area_access.json"));
        var initial=InitialExplorationArea.fromDebugRoot(root,config.initialActivityRadiusBlocks());
        if(!initial.geographic()) return Optional.empty();
        JsonObject grid=WorldEntrySurvey.read(run.resolve("world_feature_grid.json"));
        var geo=GeographicRegions.build(grid,config.nearSeaDistanceBlocks(),config.oceanRegionSpanBlocks());
        String first=geo.at(initial.centerX(),initial.centerZ());
        Map<Long,WorldRegionSnapshot.Cell> cells=new HashMap<>(); Map<String,double[]> sums=new TreeMap<>();
        for(var e:grid.getAsJsonArray("cells")) {
            var c=e.getAsJsonObject(); int x=c.get("gridX").getAsInt(),z=c.get("gridZ").getAsInt();
            String id=geo.at(new GeographicRegions.Cell(x,z)); boolean land=c.get("waterFrac").getAsDouble()<0.5;
            cells.put(WorldRegionSnapshot.key(x,z),new WorldRegionSnapshot.Cell(id,land,geo.regions().get(id).ocean()));
            if(land) { var sum=sums.computeIfAbsent(id,k->new double[3]); sum[0]+=(x+0.5)*geo.step(); sum[1]+=(z+0.5)*geo.step(); sum[2]++; }
        }
        Map<String,WorldRegionSnapshot.Centre> centres=new TreeMap<>();
        // A representative centre must lie on that continent: a ring and its island must not share a centroid.
        Map<String,Double> best=new HashMap<>();
        for(var e:new TreeMap<>(cells).entrySet()) if(e.getValue().land()) {
            String id=e.getValue().region();var sum=sums.get(id);int gx=(int)(e.getKey()>>32),gz=(int)(long)e.getKey();
            double x=(gx+0.5)*geo.step(),z=(gz+0.5)*geo.step(),distance=Math.hypot(x-sum[0]/sum[2],z-sum[1]/sum[2]);
            if(distance<best.getOrDefault(id,Double.POSITIVE_INFINITY)){best.put(id,distance);centres.put(id,new WorldRegionSnapshot.Centre(x,z));}
        }
        if(!centres.containsKey(first)) throw new IOException("Adventure starter continent not in sealed geography");
        var manifest=WorldEntrySurvey.read(run.resolve("world_survey_manifest.json"));
        String dimension=manifest.getAsJsonObject("config").get("dimensionId").getAsString();
        String identity=run.getFileName()+":"+grid.get("configHash").getAsString()+":"+first+":"+config.nearSeaDistanceBlocks()+":"+config.oceanRegionSpanBlocks();
        return Optional.of(new WorldRegionSnapshot(identity,dimension,geo.step(),first,cells,centres));
    }
    public record Settlement(String kind, RegionBounds bounds) {}
    public static List<Settlement> settlements(MinecraftServer server,int step) throws IOException {
        Path run=WorldEntrySurvey.savedRun(WorldScopedPlanningPaths.realmDebugRoot(server));
        if(run==null || !Files.isRegularFile(run.resolve("city_seed_registry.json"))) return List.of();
        JsonObject registry=WorldEntrySurvey.read(run.resolve("city_seed_registry.json"));
        List<Settlement> result=new ArrayList<>();
        if(registry.has("citySeeds")) for(var e:registry.getAsJsonArray("citySeeds")) {
            var seed=e.getAsJsonObject(); var b=CityPlanningReservation.fromSeed(seed,step).protection();
            String kind=seed.has("theoreticalScale") && "village".equals(seed.get("theoreticalScale").getAsString())?"village":"city";
            result.add(new Settlement(kind,new RegionBounds(b.minX(),b.minZ(),b.maxX(),b.maxZ())));
        }
        return List.copyOf(result);
    }
    public static boolean protects(ServerLevel level,BoundingBox bounds) { return CityGenerationProtection.intersects(level,bounds); }
}
