package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.application.CityTestRunLayout;
import com.rinsing.geomantia.systems.city.application.intercity.*;
import com.rinsing.geomantia.systems.city.application.landuse.*;
import com.rinsing.geomantia.systems.city.domain.landuse.*;
import com.rinsing.geomantia.systems.city.domain.model.*;
import com.rinsing.geomantia.systems.realm_planning.adapter.minecraft.RtfTerrainPreviewProvider;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static com.rinsing.geomantia.systems.city.application.intercity.CityExitRoadPlanner.*;

/** Invoked only by the explicit Geomantia city compile workflow. Never discovers destinations. */
public final class InterCityRoadService {
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    public record Prepared(Path directory,JsonObject report,LandUseAreaPlan area,CityLandUseSurfacePrintPlan surface) {}

    public static synchronized List<Prepared> prepare(Path run,String cityId,List<InterCityNetwork.Link> network,ServerLevel level) throws IOException {
        List<Prepared> result=new ArrayList<>();
        for(var link:network) {
            if(!link.touches(cityId))continue;
            Path directory=run.resolve("inter_city_roads").resolve(link.id());Files.createDirectories(directory);
            Path compiled=directory.resolve("compiled_road.json");
            if(Files.isRegularFile(compiled)) {
                JsonObject saved=read(compiled);
                result.add(new Prepared(directory,saved.getAsJsonObject("report"),new LandUseAreaPlanCodec().fromJson(saved.getAsJsonObject("area")),
                        new CityLandUseSurfacePrintPlanCodec().fromJson(saved.getAsJsonObject("surface"))));continue;
            }
            JsonObject report=new JsonObject();report.addProperty("linkId",link.id());report.addProperty("realmId",link.a().realm());
            report.addProperty("cityA",link.a().id());report.addProperty("cityB",link.b().id());
            try {
                BlockPoint a=portal(run,link.a().id(),link.id()),b=portal(run,link.b().id(),link.id());
                if(a==null || b==null) {
                    report.addProperty("status","WAITING_FOR_ENDPOINTS");write(directory.resolve("status.json"),report);
                    result.add(new Prepared(directory,report,null,null));continue;
                }
                // Retry failed routing only after endpoint artifacts change, not on every city status poll.
                String signature=a+"|"+b;
                Path previous=directory.resolve("status.json");
                if(Files.isRegularFile(previous)) {
                    JsonObject old=read(previous);
                    if(old.has("endpointSignature") && signature.equals(old.get("endpointSignature").getAsString())
                            && old.has("routeFailed") && old.get("routeFailed").getAsBoolean()) {
                        result.add(new Prepared(directory,old,null,null));continue;
                    }
                }
                report.addProperty("endpointSignature",signature);
                List<BlockBounds> obstacles=obstacles(run,link);
                CachedTerrain terrain=new CachedTerrain(level,obstacles);
                var route=new InterCityRoutePlanner().plan(a,b,terrain);
                report.addProperty("status",route.status());report.addProperty("expandedNodes",route.expanded());
                report.addProperty("routeFailed",route.sections().isEmpty());
                report.add("sections",JSON.toJsonTree(route.sections()));
                if(route.sections().isEmpty()) {write(previous,report);result.add(new Prepared(directory,report,null,null));continue;}
                String owner=link.id()+"_"+UUID.nameUUIDFromBytes(run.getFileName().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                List<LandUseSourceResolver.RoadBand> bands=new ArrayList<>();
                Set<BlockPoint> samples=new HashSet<>();int ordinal=0;
                for(List<BlockPoint> section:route.sections())for(int i=1;i<section.size();i++) {
                    BlockPoint from=section.get(i-1),to=section.get(i);BlockBounds footprint=bandBounds(from,to,2);
                    bands.add(new LandUseSourceResolver.RoadBand(owner+"/"+ordinal++,owner,"CITY_MAIN_ROAD",from,to,footprint,5,
                            "STAIR_SLAB_STAIR","minecraft:deepslate_tile_slab","minecraft:deepslate_tile_stairs","minecraft:stone_brick_wall"));
                    for(BlockPoint p:InterCityRoutePlanner.densify(List.of(from,to)))for(int dx=-4;dx<=4;dx+=4)for(int dz=-4;dz<=4;dz+=4)
                        samples.add(new BlockPoint(Math.floorDiv(p.x()+dx,4)*4,Math.floorDiv(p.z()+dz,4)*4));
                }
                BlockBounds bounds=new BlockBounds(samples.stream().mapToInt(BlockPoint::x).min().orElseThrow(),samples.stream().mapToInt(BlockPoint::z).min().orElseThrow(),
                        samples.stream().mapToInt(BlockPoint::x).max().orElseThrow()+3,samples.stream().mapToInt(BlockPoint::z).max().orElseThrow()+3);
                List<LandUseTerrainField.Cell> cells=new ArrayList<>();
                for(BlockPoint p:samples) {
                    var sample=terrain.sample(p.x(),p.z());
                    cells.add(new LandUseTerrainField.Cell(Math.floorDiv(p.x(),4),Math.floorDiv(p.z(),4),p.x(),p.z(),4,sample.height(),0,0,0,
                            sample.water(),sample.water()?1:0,0,"unknown",sample.ocean()?"ocean":"land","",true));
                }
                var area=new LandUseAreaPlanCodec().withComputedHash(new LandUseAreaPlan(LandUseAreaPlan.SCHEMA,"intercity",owner,"",bounds,List.of(),List.of(),List.of(),List.of()));
                var surface=new CityLandUseSurfacePrintPlanner().plan(area,List.of(),new LandUseTerrainField(LandUseTerrainField.SCHEMA,owner,bounds,4,cells),bands,List.of(),List.of());
                Path citySurface=stage(run,link.a().id(),CityTestRunLayout.LAND_USE).resolve("city_land_use_surface_print_plan.json");
                if(Files.isRegularFile(citySurface)) {
                    var material=new CityLandUseSurfacePrintPlanCodec().fromJson(read(citySurface)).materialField();
                    var field=new CityMaterialField(material.selections(),List.of(),bands.stream().map(r->new CityMaterialField.Road(r.streetBandId(),r.roadKind(),r.bounds())).toList());
                    surface=new CityLandUseSurfacePrintPlanCodec().withComputedHash(surface.withMaterials(field));
                }
                // Validate the actual curb/deck footprint, not merely the centre line.
                for(var cell:surface.featureCells()) {
                    if(terrain.blocked(cell.x(),cell.z()))throw new IllegalArgumentException("INTERCITY_COMPILED_FOOTPRINT_CONFLICT");
                    if(terrain.sample(cell.x(),cell.z()).ocean())throw new IllegalArgumentException("INTERCITY_COMPILED_OCEAN_CONFLICT");
                }
                JsonObject saved=new JsonObject();saved.add("report",report);saved.add("area",new LandUseAreaPlanCodec().toJson(area));saved.add("surface",new CityLandUseSurfacePrintPlanCodec().toJson(surface));
                write(compiled,saved);write(previous,report);result.add(new Prepared(directory,report,area,surface));
            } catch(RuntimeException ex) {
                report.addProperty("status","ROUTE_UNRESOLVED");report.addProperty("reason",ex.getMessage());report.addProperty("routeFailed",true);
                write(directory.resolve("status.json"),report);result.add(new Prepared(directory,report,null,null));
            }
        }
        return List.copyOf(result);
    }
    private static BlockPoint portal(Path run,String city,String link) throws IOException {
        Path d5=stage(run,city,CityTestRunLayout.D5),file=d5.resolve("inter_city_exit_plan.json");
        if(!Files.isRegularFile(file)||!Files.isRegularFile(d5.resolve("active_planned_structure_registry.json")))return null;
        for(JsonElement e:array(read(file),"portals"))if(link.equals(e.getAsJsonObject().get("linkId").getAsString()))return point(e.getAsJsonObject().getAsJsonObject("point"));return null;
    }
    private static List<BlockBounds> obstacles(Path run,InterCityNetwork.Link link)throws IOException {
        List<BlockBounds> result=new ArrayList<>();
        for(JsonElement e:array(read(run.resolve("city_seed_registry.json")),"citySeeds")) {
            JsonObject seed=e.getAsJsonObject();String city=seed.get("citySeedId").getAsString();
            if(!link.touches(city)) {if(seed.has("designBounds"))result.add(bounds(seed.getAsJsonObject("designBounds")));continue;}
            JsonObject wall=read(stage(run,city,CityTestRunLayout.D5).resolve("wall_reservation_plan.json"));
            for(JsonElement c:array(wall,"wallCorridorMask"))result.add(bounds(c.getAsJsonObject().getAsJsonObject("blockBounds")));
            Path anchors=stage(run,city,CityTestRunLayout.D4).resolve("structure_anchor_map.json");
            for(JsonElement anchor:array(read(anchors),"anchors"))for(String key:List.of("plannedFootprint","reservedEnvelope"))
                if(anchor.getAsJsonObject().has(key))result.add(bounds(anchor.getAsJsonObject().getAsJsonObject(key)));
        }
        return result;
    }
    private static Path stage(Path run,String city,String step){return CityTestRunLayout.open(run,city).stepDirectory(step);}
    private static JsonObject read(Path path)throws IOException{return JsonParser.parseString(Files.readString(path)).getAsJsonObject();}
    public static void write(Path path,JsonObject json)throws IOException {
        Path temp=Files.createTempFile(path.getParent(),"intercity-",".tmp");
        try {Files.writeString(temp,JSON.toJson(json));try{Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}}
        finally{Files.deleteIfExists(temp);}
    }
    private static final class CachedTerrain implements InterCityRoutePlanner.Terrain {
        private final ServerLevel level;private final RtfTerrainPreviewProvider nativeProvider;private final List<BlockBounds> obstacles;
        private final Map<BlockPoint,InterCityRoutePlanner.Sample> cache=new HashMap<>();
        private final long deadline=System.nanoTime()+30_000_000_000L;
        CachedTerrain(ServerLevel level,List<BlockBounds> obstacles){this.level=level;this.obstacles=obstacles;nativeProvider=RtfTerrainPreviewProvider.probe(level);}
        public boolean blocked(int x,int z){return obstacles.stream().anyMatch(b->b.contains(x,z));}
        public InterCityRoutePlanner.Sample sample(int x,int z) {
            BlockPoint p=new BlockPoint(Math.floorDiv(x,4)*4,Math.floorDiv(z,4)*4);
            var old=cache.get(p);if(old!=null)return old;
            if(cache.size()>=100000 || System.nanoTime()>deadline || Thread.currentThread().isInterrupted())throw new IllegalStateException("INTERCITY_TERRAIN_BUDGET_EXHAUSTED");
            InterCityRoutePlanner.Sample value;
            if(nativeProvider.availability().available()) {
                var s=nativeProvider.sample(p.x(),p.z());String type=(s.biomeId()+" "+s.terrainId()+" "+s.sourceBiomeId()).toLowerCase(Locale.ROOT);
                value=new InterCityRoutePlanner.Sample(s.elevation(),s.water(),s.water()&&type.contains("ocean"));
            } else {
                var generator=level.getChunkSource().getGenerator();var state=level.getChunkSource().randomState();
                int surface=generator.getBaseHeight(p.x(),p.z(),Heightmap.Types.WORLD_SURFACE_WG,level,state)-1;
                int floor=generator.getBaseHeight(p.x(),p.z(),Heightmap.Types.OCEAN_FLOOR_WG,level,state)-1;
                String biome=level.getUncachedNoiseBiome(Math.floorDiv(p.x(),4),Math.floorDiv(surface,4),Math.floorDiv(p.z(),4)).unwrapKey().map(k->k.location().toString()).orElse("");
                value=new InterCityRoutePlanner.Sample(surface,surface>floor,biome.contains("ocean")&&surface>floor);
            }
            cache.put(p,value);return value;
        }
    }
}
