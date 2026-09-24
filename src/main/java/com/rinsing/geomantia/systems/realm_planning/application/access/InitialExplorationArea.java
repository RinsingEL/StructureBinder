package com.rinsing.geomantia.systems.realm_planning.application.access;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Bounded starter realm. It never releases the rest of the origin continent. */
public final class InitialExplorationArea {
    public static final int GENERATION_HALO_BLOCKS = 1024;
    public static final String REALM_ID = "geomantia_starter";
    public static final String NAME = "新手村国度";
    private final int radius;
    private final int centerX, centerZ;
    public int centerX() { return centerX; }
    public int centerZ() { return centerZ; }
    public int radius() { return radius; }
    public double distance(double x,double z) { return Math.hypot(x-centerX,z-centerZ); }
    public InitialExplorationArea(int x,int z,int radius) { this.centerX=x; this.centerZ=z; this.radius=Math.max(0,radius); }
    public static InitialExplorationArea fromDebugRoot(Path debugRoot,int radius) {
        Path file=debugRoot.toAbsolutePath().normalize().getParent().resolve("geomantia_starter_realm.json");
        if (!Files.isRegularFile(file)) return new InitialExplorationArea(0,0,radius);
        try {
            JsonObject value=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            return new InitialExplorationArea(value.get("centerBlockX").getAsInt(),value.get("centerBlockZ").getAsInt(),radius);
        } catch(IOException ex) { throw new java.io.UncheckedIOException(ex); }
    }
    public InitialExplorationArea(GeographicRegions geography) {
        this(geography, PlanningAreaAccessConfig.DEFAULT_INITIAL_RADIUS_BLOCKS);
    }
    public InitialExplorationArea(GeographicRegions geography, int radius) { this(0,0,radius); }

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
        value.addProperty("schema","geomantia_starter_realm.v1");
        value.addProperty("realmId",REALM_ID); value.addProperty("name",NAME);
        value.addProperty("centerBlockX",centerX); value.addProperty("centerBlockZ",centerZ);
        value.addProperty("radiusBlocks",radius); value.addProperty("shape","circle");
        value.addProperty("unlocked",true); value.addProperty("generateCities",false);
        return value;
    }
    public boolean available() { return true; }
    public String regionId() { return REALM_ID; }
    public boolean contains(double x, double z) { return distance(x,z) <= radius; }
    public boolean generationContains(double x, double z) { return distance(x,z) <= radius + GENERATION_HALO_BLOCKS; }
    public boolean overlapsGenerationArea(CityPlanningReservation.Bounds bounds) {
        double x = Math.max(bounds.minX(), Math.min(centerX, bounds.maxX()));
        double z = Math.max(bounds.minZ(), Math.min(centerZ, bounds.maxZ()));
        return generationContains(x,z);
    }
}
