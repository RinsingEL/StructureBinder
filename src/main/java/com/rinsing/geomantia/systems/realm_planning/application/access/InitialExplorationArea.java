package com.rinsing.geomantia.systems.realm_planning.application.access;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** The origin continent and its near sea, shared by access and future city exclusion. */
public final class InitialExplorationArea {
    public static final int GENERATION_HALO_BLOCKS = 1024;
    private final GeographicRegions geography;
    private final String regionId;
    private final Set<GeographicRegions.Cell> generationCells = new HashSet<>();

    public InitialExplorationArea(GeographicRegions geography) {
        this.geography = geography;
        String origin = geography.at(0, 0);
        var region = geography.regions().get(origin);
        regionId = region == null || region.ocean() ? "" : origin;
        if (available()) {
            int halo = (int) Math.ceil(GENERATION_HALO_BLOCKS / (double) geography.step());
            generationCells.addAll(region.cells());
            Set<GeographicRegions.Cell> frontier = region.cells();
            for (int distance = 0; distance < halo; distance++) {
                Set<GeographicRegions.Cell> next = new HashSet<>();
                for (var cell : frontier) {
                    for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                        var neighbor = new GeographicRegions.Cell(cell.x() + dx, cell.z() + dz);
                        if (generationCells.add(neighbor)) next.add(neighbor);
                    }
                }
                frontier = next;
            }
        }
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
        return new InitialExplorationArea(GeographicRegions.build(grid, config.nearSeaDistanceBlocks(), config.oceanRegionSpanBlocks()));
    }

    public boolean available() { return !regionId.isEmpty(); }
    public String regionId() { return regionId; }
    public boolean contains(double x, double z) { return available() && regionId.equals(geography.at(x, z)); }
    public boolean generationContains(double x, double z) {
        return generationCells.contains(new GeographicRegions.Cell((int) Math.floor(x / geography.step()), (int) Math.floor(z / geography.step())));
    }
    public boolean overlapsGenerationArea(CityPlanningReservation.Bounds bounds) {
        for (int z = Math.floorDiv(bounds.minZ(), geography.step()); z <= Math.floorDiv(bounds.maxZ(), geography.step()); z++)
            for (int x = Math.floorDiv(bounds.minX(), geography.step()); x <= Math.floorDiv(bounds.maxX(), geography.step()); x++)
                if (generationCells.contains(new GeographicRegions.Cell(x, z))) return true;
        return false;
    }
}
