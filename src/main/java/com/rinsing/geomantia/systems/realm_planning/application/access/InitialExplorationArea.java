package com.rinsing.geomantia.systems.realm_planning.application.access;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Persisted starter continent and near sea; a circle is used only while preparing spawn. */
public final class InitialExplorationArea {
    public static final int GENERATION_HALO_BLOCKS = 256;
    public static final String REALM_ID = "geomantia_starter";
    public static final String NAME = "新手村国度";
    private final int radius;
    private final int centerX, centerZ;
    private final int step;
    private final Set<GeographicRegions.Cell> cells;
    private final String geographicRegionId;
    public int centerX() { return centerX; }
    public int centerZ() { return centerZ; }
    public int radius() { return radius; }
    public double distance(double x,double z) { return Math.hypot(x-centerX,z-centerZ); }
    public InitialExplorationArea(int x,int z,int radius) {
        this(x,z,radius,0,Set.of(),"");
    }
    private InitialExplorationArea(int x,int z,int radius,int step,Set<GeographicRegions.Cell> cells,String id) {
        this.centerX=x; this.centerZ=z; this.radius=Math.max(0,radius);
        this.step=step; this.cells=Set.copyOf(cells); this.geographicRegionId=id;
    }
    public boolean geographic() { return step>0 && !cells.isEmpty(); }
    public record MapView(double centerX,double centerZ,double radius) {}
    public MapView mapView() {
        if(!geographic()) return new MapView(centerX,centerZ,Math.max(1024,radius));
        int minX=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
        for(var cell:cells) { minX=Math.min(minX,cell.x()); minZ=Math.min(minZ,cell.z()); maxX=Math.max(maxX,cell.x()); maxZ=Math.max(maxZ,cell.z()); }
        return new MapView((minX+maxX+1.0)*step/2,(minZ+maxZ+1.0)*step/2,
                Math.max(1024,Math.max(maxX-minX+1.0,maxZ-minZ+1.0)*step*0.55+256));
    }
    public static InitialExplorationArea continent(GeographicRegions geography,int x,int z) {
        var region=geography.regions().get(geography.at(x,z));
        if(region==null || region.ocean()) throw new IllegalArgumentException("STARTER_CONTINENT_NOT_FOUND");
        return new InitialExplorationArea(x,z,0,geography.step(),region.cells(),region.id());
    }
    public static InitialExplorationArea fromDebugRoot(Path debugRoot,int radius) {
        Path file=debugRoot.toAbsolutePath().normalize().getParent().resolve("geomantia_starter_realm.json");
        if (!Files.isRegularFile(file)) return new InitialExplorationArea(0,0,radius);
        try {
            JsonObject value=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if(value.has("shape") && "continent_and_near_sea".equals(value.get("shape").getAsString())) {
                int step=value.get("cellStepBlocks").getAsInt();
                if(step<=0) throw new IllegalArgumentException("STARTER_GRID_STEP_INVALID");
                Set<GeographicRegions.Cell> cells=new HashSet<>();
                for(var entry:value.getAsJsonArray("cells")) {
                    var cell=entry.getAsJsonObject();
                    cells.add(new GeographicRegions.Cell(cell.get("gridX").getAsInt(),cell.get("gridZ").getAsInt()));
                }
                if(cells.isEmpty()) throw new IllegalArgumentException("STARTER_CONTINENT_EMPTY");
                return new InitialExplorationArea(value.get("centerBlockX").getAsInt(),value.get("centerBlockZ").getAsInt(),
                        0,step,cells,value.get("geographicRegionId").getAsString());
            }
            return new InitialExplorationArea(value.get("centerBlockX").getAsInt(),value.get("centerBlockZ").getAsInt(),radius);
        } catch(IOException ex) { throw new java.io.UncheckedIOException(ex); }
    }
    public InitialExplorationArea(GeographicRegions geography) {
        this(geography, PlanningAreaAccessConfig.DEFAULT_INITIAL_RADIUS_BLOCKS);
    }
    public InitialExplorationArea(GeographicRegions geography, int radius) { this(continent(geography,0,0)); }
    private InitialExplorationArea(InitialExplorationArea area) {
        this(area.centerX,area.centerZ,area.radius,area.step,area.cells,area.geographicRegionId);
    }

    public static InitialExplorationArea load(Path run, PlanningAreaAccessConfig config) throws IOException {
        Path manifestPath = run.resolve("world_survey_manifest.json");
        Path gridPath = run.resolve("world_feature_grid.json");
        if (!Files.isRegularFile(manifestPath) || !Files.isRegularFile(gridPath)) return null;
        JsonObject manifest = JsonParser.parseString(Files.readString(manifestPath)).getAsJsonObject();
        if (!manifest.has("status") || !"sealed".equals(manifest.get("status").getAsString())) return null;
        JsonObject grid = JsonParser.parseString(Files.readString(gridPath)).getAsJsonObject();
        if (!java.util.Objects.equals(manifest.get("configHash"), grid.get("configHash")))
            throw new IOException("GEOGRAPHIC_SURVEY_GRID_STALE");
        return fromDebugRoot(run.toAbsolutePath().normalize().getParent(), config.initialActivityRadiusBlocks());
    }

    public static JsonObject description(int radius) {
        return new InitialExplorationArea(0,0,radius).description();
    }
    public JsonObject description() {
        JsonObject value=new JsonObject();
        value.addProperty("schema",geographic()?"geomantia_starter_realm.v2":"geomantia_starter_realm.v1");
        value.addProperty("realmId",REALM_ID); value.addProperty("name",NAME);
        value.addProperty("centerBlockX",centerX); value.addProperty("centerBlockZ",centerZ);
        value.addProperty("radiusBlocks",radius); value.addProperty("shape",geographic()?"continent_and_near_sea":"preparing");
        if(geographic()) {
            value.addProperty("cellStepBlocks",step);
            value.addProperty("geographicRegionId",geographicRegionId);
            var mask=new com.google.gson.JsonArray();
            for(var cell:new java.util.TreeSet<>(cells)) {
                var entry=new JsonObject(); entry.addProperty("gridX",cell.x()); entry.addProperty("gridZ",cell.z()); mask.add(entry);
            }
            value.add("cells",mask);
        }
        value.addProperty("unlocked",true); value.addProperty("generateCities",false);
        return value;
    }
    public boolean available() { return true; }
    public String regionId() { return REALM_ID; }
    public boolean contains(double x, double z) {
        return geographic()?cells.contains(new GeographicRegions.Cell((int)Math.floor(x/step),(int)Math.floor(z/step))):distance(x,z)<=radius;
    }
    public boolean generationContains(double x, double z) {
        if(!geographic()) {
            // Chunk dependencies form a square, including at a circular waiting area's diagonal edge.
            double dx=Math.max(0,Math.abs(x-centerX)-GENERATION_HALO_BLOCKS);
            double dz=Math.max(0,Math.abs(z-centerZ)-GENERATION_HALO_BLOCKS);
            return Math.hypot(dx,dz)<=radius;
        }
        int minX=(int)Math.floor((x-GENERATION_HALO_BLOCKS)/step),maxX=(int)Math.floor((x+GENERATION_HALO_BLOCKS)/step);
        int minZ=(int)Math.floor((z-GENERATION_HALO_BLOCKS)/step),maxZ=(int)Math.floor((z+GENERATION_HALO_BLOCKS)/step);
        for(int cz=minZ;cz<=maxZ;cz++) for(int cx=minX;cx<=maxX;cx++)
            if(cells.contains(new GeographicRegions.Cell(cx,cz))) return true;
        return false;
    }
    public boolean overlapsGenerationArea(CityPlanningReservation.Bounds bounds) {
        if(geographic()) {
            int minX=Math.floorDiv(bounds.minX()-GENERATION_HALO_BLOCKS,step),maxX=Math.floorDiv(bounds.maxX()+GENERATION_HALO_BLOCKS,step);
            int minZ=Math.floorDiv(bounds.minZ()-GENERATION_HALO_BLOCKS,step),maxZ=Math.floorDiv(bounds.maxZ()+GENERATION_HALO_BLOCKS,step);
            for(int z=minZ;z<=maxZ;z++) for(int x=minX;x<=maxX;x++) if(cells.contains(new GeographicRegions.Cell(x,z))) return true;
            return false;
        }
        double x = Math.max(bounds.minX(), Math.min(centerX, bounds.maxX()));
        double z = Math.max(bounds.minZ(), Math.min(centerZ, bounds.maxZ()));
        return generationContains(x,z);
    }
}
