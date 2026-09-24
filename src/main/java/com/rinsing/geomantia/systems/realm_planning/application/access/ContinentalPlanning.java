package com.rinsing.geomantia.systems.realm_planning.application.access;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Shared geographic ordering for roster discovery and the persistent city queue. */
public final class ContinentalPlanning {
    private static String cachedKey;
    private static ContinentalPlanning cached;
    private final GeographicRegions geography;
    private final Map<String,Integer> ranks = new HashMap<>();
    private final Map<String,Set<String>> realms = new HashMap<>();
    private ContinentalPlanning(GeographicRegions geography, JsonObject territory) {
        this.geography=geography;
        var ordered=geography.regions().values().stream().sorted(
                Comparator.comparingDouble((GeographicRegions.Region r) -> r.cells().stream()
                        .mapToDouble(c -> Math.hypot(c.x()+0.5,c.z()+0.5)).min().orElse(Double.MAX_VALUE))
                        .thenComparing(GeographicRegions.Region::id)).toList();
        for(int i=0;i<ordered.size();i++) ranks.put(ordered.get(i).id(),i);
        if(territory.has("territoryCells")) for(var value:territory.getAsJsonArray("territoryCells")) {
            var cell=value.getAsJsonObject();
            if(!"owned".equals(text(cell,"status")) || text(cell,"realmId").isBlank()) continue;
            String region=geography.at(new GeographicRegions.Cell(cell.get("gridX").getAsInt(),cell.get("gridZ").getAsInt()));
            realms.computeIfAbsent(region,k->new TreeSet<>()).add(text(cell,"realmId"));
        }
    }
    public static synchronized ContinentalPlanning load(Path run) throws IOException {
        if(!Files.isRegularFile(run.resolve("world_feature_grid.json"))) return null;
        Path configRoot=net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get();
        Path configPath=configRoot==null ? run.getParent().getParent().resolve("config/geomantia/planning_area_access.json") : configRoot.resolve("geomantia/planning_area_access.json");
        String key=run.toAbsolutePath().normalize()+"|"+stamp(run.resolve("world_feature_grid.json"))+"|"
                +stamp(run.resolve("realm_territory_map.json"))+"|"+stamp(configPath);
        if(key.equals(cachedKey)) return cached;
        var grid=read(run.resolve("world_feature_grid.json"));
        if(!grid.has("cells") || grid.getAsJsonArray("cells").isEmpty()) return null;
        var config=Files.isRegularFile(configPath)?PlanningAreaAccessConfig.loadOrCreate(configPath):PlanningAreaAccessConfig.defaults();
        var result=new ContinentalPlanning(GeographicRegions.build(grid,config.nearSeaDistanceBlocks(),config.oceanRegionSpanBlocks()),
                read(run.resolve("realm_territory_map.json")));
        cachedKey=key; cached=result; return result;
    }
    public int rank(double x,double z) { return ranks.getOrDefault(geography.at(x,z),Integer.MAX_VALUE); }
    public String region(double x,double z) { return geography.at(x,z); }
    /** The first incomplete geographical group owns the next turn, even across political borders. */
    public String missingRealm(Set<String> registered, JsonObject registry, JsonObject queue) {
        Map<String,Set<String>> scopedRealms=new HashMap<>();
        realms.forEach((region,ids)->scopedRealms.put(region,new TreeSet<>(ids)));
        Map<String,String> statuses=new HashMap<>();
        if(queue!=null && queue.has("items")) for(var value:queue.getAsJsonArray("items")) {
            var item=value.getAsJsonObject(); statuses.put(text(item,"citySeedId"),text(item,"status"));
        }
        Set<String> unfinished=new HashSet<>();
        if(registry!=null && registry.has("citySeeds")) for(var value:registry.getAsJsonArray("citySeeds")) {
            var seed=value.getAsJsonObject(); var block=seed.getAsJsonObject("anchorBlock");
            if(block==null) continue;
            String region=region(block.get("x").getAsDouble(),block.get("z").getAsDouble());
            scopedRealms.computeIfAbsent(region,k->new TreeSet<>()).add(text(seed,"realmId"));
            if(!"waiting_for_generation".equals(statuses.get(text(seed,"citySeedId")))) unfinished.add(region);
        }
        for(String region:ranks.keySet().stream().sorted(Comparator.comparingInt(ranks::get)).toList()) {
            for(String realm:scopedRealms.getOrDefault(region,Set.of())) if(!registered.contains(realm)) return realm;
            if(unfinished.contains(region)) return "";
        }
        return "";
    }
    private static String stamp(Path path) throws IOException { return Files.isRegularFile(path)?Files.size(path)+":"+Files.getLastModifiedTime(path):"missing"; }
    private static JsonObject read(Path path) throws IOException {
        return Files.isRegularFile(path)?JsonParser.parseString(Files.readString(path)).getAsJsonObject():new JsonObject();
    }
    private static String text(JsonObject o,String key) { return o.has(key)?o.get(key).getAsString():""; }
}
