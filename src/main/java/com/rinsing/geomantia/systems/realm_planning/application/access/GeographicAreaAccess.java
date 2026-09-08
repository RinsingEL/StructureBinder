package com.rinsing.geomantia.systems.realm_planning.application.access;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Immutable regional release snapshot; no chunk IO and no mutation of the save. */
final class GeographicAreaAccess {
    private static final Set<String> READY = Set.of("waiting_for_generation","waiting_for_worldgen","completed");
    final String runId, dimension;
    final GeographicRegions geography;
    final Set<String> openRegions, readyCities, blockedCities;
    final List<CityPlanningReservation> protectedCities;
    final int safety;
    private GeographicAreaAccess(String runId, String dimension, GeographicRegions geography, Set<String> open,
                                 Set<String> ready, Set<String> blocked, List<CityPlanningReservation> reservations, int safety) {
        this.runId=runId; this.dimension=dimension; this.geography=geography; this.openRegions=Set.copyOf(open);
        this.readyCities=Set.copyOf(ready); this.blockedCities=Set.copyOf(blocked); this.protectedCities=List.copyOf(reservations); this.safety=safety;
    }
    static GeographicAreaAccess load(Path root, PlanningAreaAccessConfig config, int safety) throws IOException {
        if (!Files.isDirectory(root)) return null;
        Path run;
        try (var paths=Files.list(root)) {
            run=paths.filter(Files::isDirectory).filter(p->Files.isRegularFile(p.resolve("world_feature_grid.json")))
                    .max(Comparator.comparingLong(GeographicAreaAccess::activity)).orElse(null);
        }
        if (run==null) return null;
        JsonObject grid=read(run.resolve("world_feature_grid.json"));
        GeographicRegions geo=GeographicRegions.build(grid,config.nearSeaDistanceBlocks(),config.oceanRegionSpanBlocks());
        JsonObject manifest=read(run.resolve("world_survey_manifest.json"));
        if (!"sealed".equals(str(manifest,"status",""))) throw new IOException("GEOGRAPHIC_SURVEY_NOT_SEALED");
        if (!str(manifest,"configHash","").equals(str(grid,"configHash",""))) throw new IOException("GEOGRAPHIC_SURVEY_GRID_STALE");
        String dimension=str(obj(manifest,"config"),"dimensionId","minecraft:overworld");
        JsonObject registry=read(run.resolve("city_seed_registry.json"));
        JsonObject territory=read(run.resolve("realm_territory_map.json"));
        boolean currentTerritory=!str(territory,"territoryMapId","").isBlank()
                && str(territory,"territoryMapId","").equals(str(registry,"territoryMapId",""));
        Set<String> closedRealms=new HashSet<>();
        String territoryIdentity=identity(run.resolve("realm_territory_map.json"));
        if (territoryIdentity.equals(str(registry,"finalizedTerritoryIdentity","")))
            for (JsonElement e:arr(registry,"finalizedRealmIds")) closedRealms.add(e.getAsString());
        // Older finalized sessions carry the same proof, provided they refer to the current territory.
        List<JsonObject> openSessions=new ArrayList<>();
        try (var paths=Files.list(run)) {
            for (Path p:paths.filter(p->p.getFileName().toString().startsWith("realm_t4_patch_planning_")).sorted().toList()) {
                JsonObject session=read(p.resolve("planning_session.json"));
                if (!territoryIdentity.equals(str(session,"territoryIdentity",""))) continue;
                if ("finalized".equals(str(session,"status",""))) closedRealms.add(str(session,"realmId",""));
                if ("open".equals(str(session,"status",""))) openSessions.add(session);
            }
        }
        for (JsonObject session:openSessions) closedRealms.remove(str(session,"realmId",""));
        Map<String,Set<String>> regionRealms=new HashMap<>();
        Set<String> allRealms=new HashSet<>();
        for (JsonElement e:arr(territory,"territoryCells")) {
            JsonObject cell=e.getAsJsonObject(); String realm=str(cell,"realmId","");
            if (!"owned".equals(str(cell,"status","")) || realm.isBlank()) continue;
            allRealms.add(realm);
            String id=geo.at(new GeographicRegions.Cell(cell.get("gridX").getAsInt(),cell.get("gridZ").getAsInt()));
            regionRealms.computeIfAbsent(id,k->new HashSet<>()).add(realm);
        }
        Map<String,String> statuses=new HashMap<>();
        for (JsonElement e:arr(read(run.resolve("automation/city_design_queue.json")),"items")) {
            JsonObject item=e.getAsJsonObject(); statuses.put(str(item,"citySeedId",""),str(item,"status",""));
        }
        Set<String> ready=new HashSet<>(), blocked=new HashSet<>(), blockedRegions=new HashSet<>();
        List<CityPlanningReservation> reservations=new ArrayList<>();
        Set<String> activated=activatedCities(root.getParent(),run.getFileName().toString(),dimension);
        Map<String,JsonObject> seeds=new LinkedHashMap<>();
        for(JsonElement e:arr(registry,"citySeeds")) seeds.put(str(e.getAsJsonObject(),"citySeedId",""),e.getAsJsonObject());
        for(JsonObject session:openSessions) for(JsonElement e:arr(session,"citySeeds")) seeds.put(str(e.getAsJsonObject(),"citySeedId",""),e.getAsJsonObject());
        for(JsonObject seed:seeds.values()) {
            CityPlanningReservation reservation=CityPlanningReservation.fromSeed(seed,geo.step());
            String id=reservation.citySeedId();
            String status=statuses.get(id);
            if(status==null) status=str(read(run.resolve("automation/post_d4/"+safe(id)+".json")),"status","");
            if(status.isBlank()) status=str(read(run.resolve("city_test_runs/"+safe(id)+"/test_run_manifest.json")),"status","");
            // Queue success alone is insufficient: construction must have an activated worldgen footprint.
            boolean complete=READY.contains(status) && activated.contains(id);
            if(complete) ready.add(id); else { blocked.add(id); reservations.add(reservation); }
            var b=reservation.protection();
            for(int z=Math.floorDiv(b.minZ(),geo.step());z<=Math.floorDiv(b.maxZ(),geo.step());z++)
                for(int x=Math.floorDiv(b.minX(),geo.step());x<=Math.floorDiv(b.maxX(),geo.step());x++) {
                    String region=geo.at(new GeographicRegions.Cell(x,z));
                    regionRealms.computeIfAbsent(region,k->new HashSet<>()).add(str(seed,"realmId",""));
                    if(!complete) blockedRegions.add(region);
                }
        }
        Set<String> open=new HashSet<>();
        if(currentTerritory && !allRealms.isEmpty()) for(var r:geo.regions().values()) {
            if(r.ocean()) continue;
            Set<String> realms=regionRealms.getOrDefault(r.id(),allRealms);
            if(closedRealms.containsAll(realms) && !blockedRegions.contains(r.id())) open.add(r.id());
        }
        // Every ocean area has its own nearest-continent dependencies (ties retain all dependencies).
        Map<String,Set<String>> oceanDependencies=new HashMap<>(); Map<String,Integer> distances=new HashMap<>();
        ArrayDeque<String> frontier=new ArrayDeque<>();
        for(var r:new TreeMap<>(geo.regions()).values()) if(!r.ocean()) {
            distances.put(r.id(),0); oceanDependencies.put(r.id(),new HashSet<>(Set.of(r.id()))); frontier.add(r.id());
        }
        while(!frontier.isEmpty()) { String id=frontier.remove(); int d=distances.get(id)+1;
            for(String next:geo.regions().get(id).adjacentRegions()) {
                if(!geo.regions().get(next).ocean()) continue;
                int old=distances.getOrDefault(next,Integer.MAX_VALUE);
                if(d<old) { distances.put(next,d); oceanDependencies.put(next,new HashSet<>(oceanDependencies.get(id))); frontier.add(next); }
                else if(d==old && oceanDependencies.get(next).addAll(oceanDependencies.get(id))) frontier.add(next);
            }
        }
        for(var r:geo.regions().values()) if(r.ocean()) {
            Set<String> dependencies=oceanDependencies.getOrDefault(r.id(),Set.of());
            if(!dependencies.isEmpty() && open.containsAll(dependencies) && !blockedRegions.contains(r.id())
                    && closedRealms.containsAll(regionRealms.getOrDefault(r.id(),Set.of()))) open.add(r.id());
        }
        return new GeographicAreaAccess(run.getFileName().toString(),dimension,geo,open,ready,blocked,reservations,safety);
    }
    PlanningAreaAccessPolicy.Decision evaluate(String dim,double x,double z) {
        if(!dimension.equals(dim)) return PlanningAreaAccessPolicy.Decision.denied("PLANNING_AREA_NOT_RELEASED",runId,"");
        for(var city:protectedCities) if(city.protection().expand(safety).contains(x,z))
            return PlanningAreaAccessPolicy.Decision.denied("UNRELEASED_CITY_RESERVED",runId,city.citySeedId());
        String region=geography.at(x,z);
        if(!openRegions.contains(region)) return PlanningAreaAccessPolicy.Decision.denied("CONTINENT_NOT_READY",runId,"");
        double clearance=Double.POSITIVE_INFINITY;
        // Distance to a closed geographical edge, rather than to each individual raster cell edge.
        int gx=(int)Math.floor(x/geography.step()),gz=(int)Math.floor(z/geography.step());
        int radius=(int)Math.ceil((safety+128.0)/geography.step());
        for(int dz=-radius;dz<=radius;dz++) for(int dx=-radius;dx<=radius;dx++) {
            int cx=gx+dx,cz=gz+dz;
            if(openRegions.contains(geography.at(new GeographicRegions.Cell(cx,cz)))) continue;
            double gapX=Math.max(Math.max(cx*(double)geography.step()-x,0),x-(cx+1.0)*geography.step());
            double gapZ=Math.max(Math.max(cz*(double)geography.step()-z,0),z-(cz+1.0)*geography.step());
            clearance=Math.min(clearance,Math.max(gapX,gapZ));
        }
        if(clearance<safety) return PlanningAreaAccessPolicy.Decision.denied("REGION_GENERATION_BUFFER",runId,"");
        return PlanningAreaAccessPolicy.Decision.allowed("RELEASED_GEOGRAPHIC_REGION",runId,"",clearance-safety);
    }
    boolean permitsChunk(String dim,int cx,int cz,int initialRadius) {
        if(!dimension.equals(dim)) return false;
        var bounds=new CityPlanningReservation.Bounds(cx*16,cz*16,cx*16+15,cz*16+15);
        for(var city:protectedCities) if(city.protection().overlaps(bounds)) return false;
        double x=cx*16+8.0,z=cz*16+8.0;
        if(Math.hypot(x,z)<=initialRadius+1024) return true;
        return openRegions.contains(geography.at(bounds.minX(),bounds.minZ()))
                && openRegions.contains(geography.at(bounds.maxX(),bounds.maxZ()));
    }
    private static Set<String> activatedCities(Path worldRoot, String runId, String dimension) throws IOException {
        Path masks=worldRoot.resolve("geomantia_city_masks");
        Set<String> structures=new HashSet<>(), ready=new HashSet<>();
        for(JsonElement e:arr(read(masks.resolve("active_planned_structure_registry.json")),"registries")) {
            JsonObject registry=e.getAsJsonObject();
            if(runId.equals(str(registry,"runId","")) && !arr(registry,"plannedStructures").isEmpty())
                structures.add(str(registry,"cityId",""));
        }
        for(JsonElement e:arr(read(masks.resolve("active_city_land_use_area_plans.json")),"plans")) {
            JsonObject plan=e.getAsJsonObject(); String id=str(plan,"cityId","");
            if(dimension.equals(str(plan,"dimensionId","")) && structures.contains(id)
                    && (!arr(obj(plan,"areaPlan"),"areas").isEmpty()
                    || !arr(obj(plan,"surfacePrintPlan"),"featureCells").isEmpty())) ready.add(id);
        }
        return ready;
    }
    static long activity(Path p) { try { Path q=p.resolve("automation/city_design_queue.json"); return Files.getLastModifiedTime(Files.exists(q)?q:p.resolve("world_feature_grid.json")).toMillis(); } catch(IOException e) { return 0; } }
    static String identity(Path path) throws IOException {
        if(!Files.isRegularFile(path)) return "missing";
        try { return "sha256:"+java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String safe(String id) { return id.replaceAll("[^A-Za-z0-9._-]","_"); }
    private static JsonObject read(Path p) throws IOException { return Files.isRegularFile(p)?JsonParser.parseString(Files.readString(p)).getAsJsonObject():new JsonObject(); }
    private static JsonObject obj(JsonObject j,String key) { return j.has(key)?j.getAsJsonObject(key):new JsonObject(); }
    private static JsonArray arr(JsonObject j,String key) { return j.has(key)?j.getAsJsonArray(key):new JsonArray(); }
    private static String str(JsonObject j,String key,String fallback) { return j.has(key)&&!j.get(key).isJsonNull()?j.get(key).getAsString():fallback; }
}
