package com.rinsing.realmfolk.server;

import com.google.gson.*;
import com.rinsing.geomantia.platform.WorldScopedPlanningPaths;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityReservationMaskRegistry;
import com.rinsing.realmfolk.Realmfolk;
import com.rinsing.realmfolk.model.Society;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.rinsing.realmfolk.model.Society.*;
import static com.rinsing.realmfolk.server.SocietyRuntime.*;

/** Consumes only Geomantia's authored semantics and proven worldgen placements. Never creates buildings. */
public final class GeomantiaBridge {
    private static MinecraftServer currentServer;
    private static final Map<String,JsonObject> CATALOGS=new HashMap<>(), SEEDS=new HashMap<>();
    private static final Set<String> REPORTED=new HashSet<>();
    private static final Map<Long,List<CityReservationMaskRegistry.PlannedStructure>> BUILDINGS=new HashMap<>();
    private static long registryStamp=-1;
    private static int registryCount=-1;
    public static int refresh(MinecraftServer server){
        if(currentServer!=server){currentServer=server;CATALOGS.clear();SEEDS.clear();REPORTED.clear();BUILDINGS.clear();registryStamp=-1;registryCount=-1;}
        if(PopulationConfig.current==null)return 0;
        separateSharedHomes(server);
        refreshBuildingIndex(server);
        Society s=society(server);int created=0;
        // Query only chunks already active around players, avoiding global population simulation.
        Set<ChunkPos> chunks=new HashSet<>();
        for(var p:server.getPlayerList().getPlayers())if(p.level()==server.overworld())for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)chunks.add(new ChunkPos(p.chunkPosition().x+x,p.chunkPosition().z+z));
        Set<String> visited=new HashSet<>();ServerLevel level=server.overworld();
        for(ChunkPos chunk:chunks)for(var planned:BUILDINGS.getOrDefault(chunk.toLong(),List.of())) {
            String key=planned.runId()+":"+planned.cityId()+":"+planned.anchorId();
            if(!visited.add(key)||!level.hasChunk(chunk.x,chunk.z))continue;
            Property previous=s.properties.get(key);
            Town existingTown=s.towns.get(planned.runId()+":"+planned.cityId());
            if(existingTown!=null)existingTown.planningKey=planned.runId()+":"+planned.citySeedId();
            if(previous!=null&&previous.terms!=null&&!previous.terms.isEmpty()){
                if(previous.livingSites==null)FacilityRuntime.discover(level,previous,planned);
                continue;
            }
            var datum=CityReservationMaskRegistry.resolvedTemplateDatum(planned);if(datum.isEmpty())continue;
            var bounds=planned.lockedActualFootprint();
            boolean complete=true;
            for(int cx=Math.floorDiv(bounds.minX(),16);cx<=Math.floorDiv(bounds.maxX(),16);cx++)for(int cz=Math.floorDiv(bounds.minZ(),16);cz<=Math.floorDiv(bounds.maxZ(),16);cz++)
                if(!CityReservationMaskRegistry.hasTemplateFragmentProof(planned,new ChunkPos(cx,cz)))complete=false;
            if(!complete)continue;
            try {
                Path run=WorldScopedPlanningPaths.realmDebugRoot(server).resolve(planned.runId()).normalize();
                if(!run.startsWith(WorldScopedPlanningPaths.realmDebugRoot(server)))continue;
                JsonObject seed=seed(run,planned.citySeedId());if(seed==null)continue;
                JsonObject catalog=catalog(run,planned.citySeedId());
                JsonObject semantic=findProfile(catalog,planned.templateId());
                List<String> terms=new ArrayList<>();
                if(semantic!=null&&"approved".equals(string(semantic,"reviewState",""))&&semantic.has("functionTerms"))
                    for(JsonElement term:semantic.getAsJsonArray("functionTerms"))terms.add(term.getAsString());
                String legacyRole=PopulationConfig.current.templateRoles.get(planned.templateId());
                if(legacyRole!=null){
                    var config=DesignService.active(server);var profession=config.professions.get(legacyRole);
                    if(legacyRole.equals("resident"))terms.addAll(config.realms.get("default").population.housingTerms);
                    else if(profession!=null)terms.addAll(profession.workplaceTerms);
                }
                if(terms.isEmpty())continue;
                int height=templateHeight(catalog,planned.templateId());if(height<=0)continue;
                String townId=planned.runId()+":"+planned.cityId();
                Town town=s.towns.get(townId);
                if(town==null){Point center=new Point(bounds.minX(),datum.getAsInt()+1,bounds.minZ());
                    town=new Town(townId,seed.get("realmId").getAsString(),string(seed,"name","未命名城镇"),center);s.towns.put(townId,town);}
                town.sourceCity=planned.cityId();town.planningKey=planned.runId()+":"+planned.citySeedId();
                Point home=findWalkable(level,bounds.minX(),bounds.maxX(),datum.getAsInt(),bounds.minZ(),bounds.maxZ(),Set.of());
                if(home==null)continue;
                Property property=new Property(key,townId,String.join(" / ",terms),new Bounds(bounds.minX(),datum.getAsInt(),bounds.minZ(),bounds.maxX(),datum.getAsInt()+height-1,bounds.maxZ()),home);
                property.templateId=planned.templateId();property.terms=new ArrayList<>(new TreeSet<>(terms));
                property.work=home;
                var settings=DesignService.active(server).resolve(town.realm,com.rinsing.realmfolk.design.PopulationPlanner.cityKey(town)).settings();
                FacilityRuntime.discover(level,property,planned);
                if(!Collections.disjoint(terms,settings.housingTerms)&&settings.saleHouseEvery>1){
                    long houses=s.properties.values().stream().filter(p->p.town.equals(townId)&&p.terms!=null&&!Collections.disjoint(p.terms,settings.housingTerms)).count();
                    if((houses+1)%settings.saleHouseEvery==0){property.price=settings.housePrice;property.owner="town:"+townId;property.name+=" · 待售";}
                }
                if(previous!=null){previous.templateId=property.templateId;previous.terms=property.terms;previous.slots=property.slots;previous.livingSites=property.livingSites;previous.entrances=property.entrances;previous.facilitySource=property.facilitySource;}
                else {s.properties.put(key,property);created++;}
                String fauna=PopulationConfig.current.entityTypesByTemplate.get(planned.templateId());
                if(previous==null&&fauna!=null&&!fauna.equals("realmfolk:citizen")){
                    String id=UUID.nameUUIDFromBytes((key+":0").getBytes(StandardCharsets.UTF_8)).toString();
                    Citizen animal=new Citizen(id,property.name,townId,key,"resident",home,property.work);animal.entityType=fauna;
                    s.citizens.putIfAbsent(id,animal);property.owner=id;property.slots.clear();
                }
                s.imported.add(key);dirty(server);
            }catch(Exception ex){if(REPORTED.add(key))Realmfolk.LOG.warn("Cannot populate Geomantia building {}: {}",key,ex.toString());}
        }
        for(Town t:s.towns.values())if(t.nextTown.isBlank())s.towns.values().stream().filter(other->!other.id.equals(t.id)&&other.dimension.equals(t.dimension))
            .min(Comparator.comparingDouble(other->t.center.distanceSquared(other.center))).ifPresent(other->{t.nextTown=other.id;dirty(server);});
        FacilityRuntime.refresh(server);
        DesignService.populate(server);
        return created;
    }
    private static void refreshBuildingIndex(MinecraftServer server){
        try {
            Path registry=CityReservationMaskRegistry.plannedRegistryPath(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT));
            long stamp=Files.exists(registry)?Files.getLastModifiedTime(registry).toMillis():0;
            int count=CityReservationMaskRegistry.activePlannedStructureCount();
            if(stamp==registryStamp&&count==registryCount)return;
            BUILDINGS.clear();
            // plannedStructuresForChunk is a worldgen work queue: completed buildings are deliberately absent.
            // Enumerate the official active registry, then resolve each exact identity through the base API.
            for(JsonElement e:CityReservationMaskRegistry.plannedRegistrySummary().getAsJsonArray("plannedStructures")){
                JsonObject building=e.getAsJsonObject(),anchor=building.getAsJsonObject("anchorBlock");
                var planned=CityReservationMaskRegistry.findTemplatePlacement(string(building,"anchorId",""),string(building,"templateRef",""),string(building,"templateHash",""),
                    new com.rinsing.geomantia.systems.city.domain.model.BlockPoint(anchor.get("x").getAsInt(),anchor.get("z").getAsInt()));
                if(planned.isEmpty())continue;
                var p=planned.get();var bounds=p.lockedActualFootprint();
                for(int x=Math.floorDiv(bounds.minX(),16);x<=Math.floorDiv(bounds.maxX(),16);x++)for(int z=Math.floorDiv(bounds.minZ(),16);z<=Math.floorDiv(bounds.maxZ(),16);z++)
                    BUILDINGS.computeIfAbsent(ChunkPos.asLong(x,z),key->new ArrayList<>()).add(p);
            }
            registryStamp=stamp;registryCount=count;
        }catch(Exception ex){if(REPORTED.add("registry"))Realmfolk.LOG.error("Cannot read Geomantia's active building registry",ex);}
    }
    private static Point findWalkable(ServerLevel level,int minX,int maxX,int y,int minZ,int maxZ,Set<Point> occupied){
        for(int dy=0;dy<12;dy++)for(int x=minX+1;x<maxX;x+=2)for(int z=minZ+1;z<maxZ;z+=2) {
            BlockPos p=new BlockPos(x,y+dy,z);if(!level.hasChunkAt(p))continue;
            if(occupied.stream().anyMatch(home->home.distanceSquared(point(p))<4))continue;
            if(level.getBlockState(p.below()).isCollisionShapeFullBlock(level,p.below())&&level.getBlockState(p).isAir()&&level.getBlockState(p.above()).isAir())return point(p);
        }return null;
    }
    private static Point workPoint(ServerLevel level,Bounds bounds,Point home){
        for(Point candidate:List.of(home.offset(4,0,0),home.offset(0,0,4),home.offset(-4,0,0),home.offset(0,0,-4))){
            if(!bounds.contains(candidate))continue;BlockPos p=pos(candidate);
            if(level.hasChunkAt(p)&&level.getBlockState(p.below()).isCollisionShapeFullBlock(level,p.below())&&level.getBlockState(p).isAir()&&level.getBlockState(p.above()).isAir())return candidate;
        }
        return home;
    }
    /** Repair the first preview's shared home coordinates without replacing identities or entities. */
    public static void separateSharedHomes(MinecraftServer server){
        Map<String,Set<Point>> occupied=new HashMap<>();Society s=society(server);
        for(Citizen c:s.citizens.values()){
            Property prop=s.properties.get(c.property);Town town=s.towns.get(c.town);
            if(prop==null||prop.livingSites!=null||town==null||!town.dimension.equals(dimension(server.overworld()))||!c.mode.equals("HOME"))continue;
            Set<Point> homes=occupied.computeIfAbsent(prop.id,key->new HashSet<>());
            if(!homes.add(c.home)){
                Bounds b=prop.bounds;Point home=findWalkable(server.overworld(),b.minX(),b.maxX(),b.minY(),b.minZ(),b.maxZ(),homes);
                if(home!=null){c.home=home;c.work=workPoint(server.overworld(),b,home);homes.add(home);dirty(server);}
            }
        }
    }
    private static JsonObject seed(Path run,String city)throws Exception {
        String key=run+":"+city;if(SEEDS.containsKey(key))return SEEDS.get(key);
        JsonObject root=JsonParser.parseString(Files.readString(run.resolve("city_seed_registry.json"))).getAsJsonObject();
        for(JsonElement e:root.getAsJsonArray("citySeeds")){JsonObject seed=e.getAsJsonObject();if(city.equals(string(seed,"citySeedId",""))){
            if(!seed.has("name"))for(JsonElement profile:JsonParser.parseString(Files.readString(run.resolve("realm_profiles.json"))).getAsJsonArray()){
                JsonObject realm=profile.getAsJsonObject();if(string(seed,"realmId","").equals(string(realm,"realmId",""))){seed.addProperty("name",string(realm,"name","未命名国度")+("capital".equals(string(seed,"role",""))?"首府":"城镇"));break;}
            }
            SEEDS.put(key,seed);return seed;}}return null;
    }
    private static JsonObject catalog(Path run,String city)throws Exception {
        String key=run+":"+city;if(CATALOGS.containsKey(key))return CATALOGS.get(key);
        Path file=run.resolve("city_test_runs").resolve(city).resolve("steps/blueprint/city_blueprint_catalog_snapshot.json").normalize();
        if(!file.startsWith(run))throw new IllegalArgumentException("城市目录越界");
        JsonObject catalog=JsonParser.parseString(Files.readString(file)).getAsJsonObject();CATALOGS.put(key,catalog);return catalog;
    }
    private static JsonObject findProfile(JsonObject catalog,String id){
        if(!catalog.has("structureCatalog"))return null;JsonArray profiles=catalog.getAsJsonObject("structureCatalog").getAsJsonArray("semanticProfiles");
        if(profiles!=null)for(JsonElement e:profiles){JsonObject p=e.getAsJsonObject();if(id.equals(string(p,"semanticProfileId","")))return p;}return null;
    }
    private static int templateHeight(JsonObject catalog,String id){
        JsonArray templates=catalog.getAsJsonObject("templateCatalog").getAsJsonArray("templates");
        for(JsonElement e:templates){JsonObject template=e.getAsJsonObject();if(id.equals(string(template,"templateId","")))return template.getAsJsonObject("rawSize").get("height").getAsInt();}
        return 0;
    }
    private static String string(JsonObject object,String key,String fallback){return object.has(key)&&!object.get(key).isJsonNull()?object.get(key).getAsString():fallback;}
}
