package com.rinsing.geomantia.systems.realm_planning.application.access;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Shared geographical release authority for movement, map fog and chunk scheduling. */
public final class PlanningAreaAccessPolicy {
    private final PlanningAreaAccessConfig config;
    private final GeographicAreaAccess regional;
    public PlanningAreaAccessPolicy(Path root,PlanningAreaAccessConfig config) { this(root,config,160); }
    public PlanningAreaAccessPolicy(Path root,PlanningAreaAccessConfig config,int safety) {
        this.config=config;
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
        double clearance=config.initialActivityRadiusBlocks()-Math.hypot(x,z);
        if(clearance>=0) return Decision.allowed("INITIAL_ACTIVITY_AREA",activeRunId(),"",clearance);
        if(regional==null) return Decision.denied("PLANNING_AREA_NOT_RELEASED",activeRunId(),"");
        return regional.evaluate(dimension,x,z);
    }
    public boolean revealed(String dimension,double x,double z) {
        if(!config.enabled() || !config.managedDimensions().contains(dimension)) return true;
        if(Math.hypot(x,z)<=config.initialActivityRadiusBlocks()) return true;
        return regional!=null && regional.dimension.equals(dimension) && regional.openRegions.contains(regional.geography.at(x,z));
    }
    /** A rejected request completes as unavailable; it never advances a chunk status. */
    public boolean permitsChunk(String dimension,int chunkX,int chunkZ) {
        if(!config.enabled()||!config.managedDimensions().contains(dimension)) return true;
        if(regional==null) return Math.hypot(chunkX*16+8.0,chunkZ*16+8.0)<=config.initialActivityRadiusBlocks()+1024;
        return regional.permitsChunk(dimension,chunkX,chunkZ,config.initialActivityRadiusBlocks());
    }
    public String activeRunId() { return regional==null?"":regional.runId; }
    public Set<String> connectedCityIds() { return regional==null?Set.of():regional.readyCities; }
    public Set<String> disconnectedCityIds() { return regional==null?Set.of():regional.blockedCities; }
    public static long sourceStamp(Path root) {
        // Hash relevant file identities as well as mtimes: replacing/deleting a session must revoke access.
        long stamp=1;
        try {
            if(Files.isDirectory(root)) try(var paths=Files.walk(root)) {
                for(Path p:paths.filter(Files::isRegularFile).filter(PlanningAreaAccessPolicy::relevant).sorted().toList())
                    stamp=31*stamp+p.toString().hashCode()+Files.size(p)+Files.getLastModifiedTime(p).toMillis();
            }
            Path masks=root.toAbsolutePath().normalize().getParent().resolve("geomantia_city_masks");
            for(String name:List.of("active_planned_structure_registry.json","active_city_land_use_area_plans.json")) {
                Path p=masks.resolve(name);
                stamp=31*stamp+(Files.isRegularFile(p)?Files.size(p)+Files.getLastModifiedTime(p).toMillis():0);
            }
            return stamp;
        } catch(IOException exception) { return Long.MIN_VALUE; }
    }
    private static boolean relevant(Path p) {
        String name=p.getFileName().toString();
        return Set.of("world_feature_grid.json","world_survey_manifest.json","realm_territory_map.json",
                "city_seed_registry.json","city_design_queue.json","planning_session.json","test_run_manifest.json").contains(name)
                || p.getParent().getFileName().toString().equals("post_d4");
    }
    public record Decision(boolean allowed,String reasonCode,String runId,String citySeedId,double clearanceBlocks) {
        static Decision allowed(String reason,String run,String city,double clearance) { return new Decision(true,reason,run,city,clearance); }
        static Decision denied(String reason,String run,String city) { return new Decision(false,reason,run,city,Double.NEGATIVE_INFINITY); }
    }
}
