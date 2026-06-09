package com.user.terra_script.world.city.execution;

import com.google.gson.JsonObject;
import com.user.terra_script.world.StructureInjector;

public final class TerrainPreparationResult {
    private StructureInjector.PlacementBounds bounds;
    private final TerrainClearStats softObstacleClear = new TerrainClearStats("soft_obstacle_clear");
    private final TerrainClearStats embeddedExcavate = new TerrainClearStats("embedded_excavate");
    private final TerrainClearStats postCleanup = new TerrainClearStats("post_cleanup");

    public StructureInjector.PlacementBounds bounds() {
        return bounds;
    }

    public void setBounds(StructureInjector.PlacementBounds bounds) {
        this.bounds = bounds;
    }

    public TerrainClearStats softObstacleClear() {
        return softObstacleClear;
    }

    public TerrainClearStats embeddedExcavate() {
        return embeddedExcavate;
    }

    public TerrainClearStats postCleanup() {
        return postCleanup;
    }

    public int totalClearedBlocks() {
        return softObstacleClear.totalClearedBlocks()
                + embeddedExcavate.totalClearedBlocks()
                + postCleanup.totalClearedBlocks();
    }

    public JsonObject toJson() {
        JsonObject out = new JsonObject();
        if (bounds != null) {
            JsonObject boundsJson = new JsonObject();
            boundsJson.addProperty("min_x", bounds.minX);
            boundsJson.addProperty("min_y", bounds.minY);
            boundsJson.addProperty("min_z", bounds.minZ);
            boundsJson.addProperty("max_x_exclusive", bounds.maxXExclusive);
            boundsJson.addProperty("max_y_exclusive", bounds.maxYExclusive);
            boundsJson.addProperty("max_z_exclusive", bounds.maxZExclusive);
            out.add("placement_bounds", boundsJson);
        }
        out.addProperty("total_cleared_blocks", totalClearedBlocks());
        out.add("soft_obstacle_clear", softObstacleClear.toJson());
        out.add("embedded_excavate", embeddedExcavate.toJson());
        out.add("post_cleanup", postCleanup.toJson());
        return out;
    }
}
