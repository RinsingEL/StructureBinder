package com.rinsing.geomantia.systems.realm_planning.application.access;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Shared geographical release authority for movement, map fog and chunk scheduling. */
public final class PlanningAreaAccessPolicy {
    // 1.20.1: maximum status distance (12) plus ENTITY_TICKING -> FULL distance (2).
    public static final int PLAYER_DEPENDENCY_RADIUS_CHUNKS = 14;
    public static final int MOVEMENT_SAFETY_BLOCKS = 256;
    private final Map<String,Map<Long,Boolean>> playerTicketCache = new HashMap<>();
    private final Map<String,Map<Long,Boolean>> chunkPermissionCache = new HashMap<>();
    private final PlanningAreaAccessConfig config;
    private final GeographicAreaAccess regional;
    private final InitialExplorationArea initial;
    public PlanningAreaAccessPolicy(Path root,PlanningAreaAccessConfig config) { this(root,config,160); }
    public PlanningAreaAccessPolicy(Path root,PlanningAreaAccessConfig config,int safety) {
        this.config=config;
        initial=InitialExplorationArea.fromDebugRoot(root,config.initialActivityRadiusBlocks());
        GeographicAreaAccess loaded;
        try { loaded=config.enabled()?GeographicAreaAccess.load(root.toAbsolutePath().normalize(),config,Math.max(160,safety)):null; }
        catch(IOException|RuntimeException exception) {
            org.slf4j.LoggerFactory.getLogger(PlanningAreaAccessPolicy.class).warn("Geographic release snapshot invalid; keeping outer regions closed: {}",exception.toString());
            loaded=null;
        }
        regional=loaded;
    }
    public Decision evaluate(String dimension,double x,double z) {
        if(!config.enabled()||!config.managedDimensions().contains(dimension)) return Decision.allowed("UNMANAGED_DIMENSION","","",Double.POSITIVE_INFINITY);
        double clearance=config.initialActivityRadiusBlocks()-initial.distance(x,z);
        if(!initial.geographic() && (regional==null || !regional.initial.available()) && clearance>=0) return Decision.allowed("INITIAL_ACTIVITY_AREA",activeRunId(),"",clearance);
        if(regional==null) return Decision.denied("PLANNING_AREA_NOT_RELEASED",activeRunId(),"");
        return regional.evaluate(dimension,x,z);
    }
    public boolean revealed(String dimension,double x,double z) {
        if(!config.enabled() || !config.managedDimensions().contains(dimension)) return true;
        if(!initial.geographic() && (regional==null || !regional.initial.available()) && initial.contains(x,z)) return true;
        if (regional==null || !regional.dimension.equals(dimension)) return false;
        for (var city : regional.protectedCities) if (city.protection().contains(x,z)) return false;
        return regional.initial.contains(x,z) || regional.openRegions.contains(regional.geography.at(x,z));
    }
    /** A rejected request completes as unavailable; it never advances a chunk status. */
    public boolean permitsChunk(String dimension,int chunkX,int chunkZ) {
        if(!config.enabled()||!config.managedDimensions().contains(dimension)) return true;
        if(regional==null) return !initial.geographic() && initial.generationContains(chunkX*16+8.0,chunkZ*16+8.0);
        return regional.permitsChunk(dimension,chunkX,chunkZ,config.initialActivityRadiusBlocks());
    }
    public String activeRunId() { return regional==null?"":regional.runId; }
    /** Only start a FULL/player demand when its complete conservative generation neighborhood is legal. */
    public boolean permitsPlayerTicket(String dimension,int x,int z) {
        if(!config.enabled() || !config.managedDimensions().contains(dimension)) return true;
        var tickets=playerTicketCache.computeIfAbsent(dimension,k->new HashMap<>());
        var chunks=chunkPermissionCache.computeIfAbsent(dimension,k->new HashMap<>());
        if(tickets.size()>16384) tickets.clear();
        if(chunks.size()>65536) chunks.clear();
        long key=chunkKey(x,z);
        Boolean cached=tickets.get(key);
        if(cached!=null) return cached;
        boolean allowed=true;
        // Centre first rejects forbidden demand without walking its neighborhood.
        if(!permitsChunk(dimension,x,z)) allowed=false;
        else outer: for(int dz=-PLAYER_DEPENDENCY_RADIUS_CHUNKS;dz<=PLAYER_DEPENDENCY_RADIUS_CHUNKS;dz++)
            for(int dx=-PLAYER_DEPENDENCY_RADIUS_CHUNKS;dx<=PLAYER_DEPENDENCY_RADIUS_CHUNKS;dx++) {
                int cx=x+dx,cz=z+dz;
                if(!chunks.computeIfAbsent(chunkKey(cx,cz),k->permitsChunk(dimension,cx,cz))) {
                    allowed=false; break outer;
                }
            }
        tickets.put(key,allowed);
        return allowed;
    }
    private static long chunkKey(int x,int z) { return (x & 0xffffffffL) | ((long)z << 32); }
    public Set<String> connectedCityIds() { return regional==null?Set.of():regional.readyCities; }
    public Set<String> disconnectedCityIds() { return regional==null?Set.of():regional.blockedCities; }
    /** Compare only immutable authority; do not touch the server-owned ticket caches on a reader thread. */
    public boolean sameAccessAs(PlanningAreaAccessPolicy other) {
        return other!=null && config.equals(other.config) && initial.sameArea(other.initial)
                && (regional==null ? other.regional==null : regional.sameAccess(other.regional));
    }
    public static long sourceStamp(Path root) {
        // Hash relevant file identities as well as mtimes: replacing/deleting a session must revoke access.
        long stamp=1;
        try {
            Path starter=root.toAbsolutePath().normalize().getParent().resolve("geomantia_starter_realm.json");
            if(Files.isRegularFile(starter)) stamp=31*stamp+Files.size(starter)+Files.getLastModifiedTime(starter).hashCode();
            // Only visit the directories consumed by GeographicAreaAccess. Structure artifacts,
            // exports and block observations can contain thousands of unrelated files.
            for(Path p:sourceFiles(root))
                if(Files.isRegularFile(p))
                    stamp=31*stamp+p.toString().hashCode()+Files.size(p)+Files.getLastModifiedTime(p).hashCode();
            Path masks=root.toAbsolutePath().normalize().getParent().resolve("geomantia_city_masks");
            for(String name:List.of("active_planned_structure_registry.json","active_city_land_use_area_plans.json")) {
                Path p=masks.resolve(name);
                stamp=31*stamp+(Files.isRegularFile(p)?Files.size(p)+Files.getLastModifiedTime(p).hashCode():0);
            }
            return stamp;
        } catch(IOException exception) { return Long.MIN_VALUE; }
    }
    private static Set<Path> sourceFiles(Path root) throws IOException {
        Set<Path> files=new TreeSet<>();
        for(Path run:children(root)) {
            for(String name:List.of("world_feature_grid.json","world_survey_manifest.json","realm_territory_map.json",
                    "city_seed_registry.json","addon_region_reservations.json","addon_region_generation_ready.json",
                    "automation/city_design_queue.json")) files.add(run.resolve(name));
            for(Path session:children(run))
                if(session.getFileName().toString().startsWith("realm_t4_patch_planning_"))
                    files.add(session.resolve("planning_session.json"));
            files.addAll(children(run.resolve("automation/post_d4")));
            for(Path city:children(run.resolve("city_test_runs"))) {
                files.add(city.resolve("test_run_manifest.json"));
                files.add(city.resolve("steps/blueprint/city_blueprint.json"));
            }
        }
        return files;
    }
    private static List<Path> children(Path directory) throws IOException {
        if(!Files.isDirectory(directory)) return List.of();
        try(var paths=Files.list(directory)) { return paths.toList(); }
    }
    public record Decision(boolean allowed,String reasonCode,String runId,String citySeedId,double clearanceBlocks) {
        static Decision allowed(String reason,String run,String city,double clearance) { return new Decision(true,reason,run,city,clearance); }
        static Decision denied(String reason,String run,String city) { return new Decision(false,reason,run,city,Double.NEGATIVE_INFINITY); }
    }
}
