package com.user.terra_script.world.city.stage.c1.intent;

public final class CityC1IntentValidation {
    private CityC1IntentValidation() {}

    public interface SovereigntyProbe {
        boolean isWithinSovereignty(int worldX, int worldZ);
    }

    public static CityC1ImageIntentModels.BasicCheck validate(
            CityC1ImageIntentModels.UrbanIntentMap map,
            SovereigntyProbe sovereigntyProbe
    ) {
        CityC1ImageIntentModels.BasicCheck check = new CityC1ImageIntentModels.BasicCheck();
        if (map == null) {
            check.blocking_errors.add("missing_urban_intent_map");
            check.ok = false;
            return check;
        }
        if (map.city_boundary == null || map.city_boundary.polygon == null || map.city_boundary.polygon.isEmpty()) {
            check.blocking_errors.add("missing_city_boundary");
        }
        if (map.district_polygons == null || map.district_polygons.isEmpty()) {
            check.blocking_errors.add("missing_district_polygons");
        }
        if (map.road_sketch == null || map.road_sketch.paths == null || map.road_sketch.paths.isEmpty()) {
            check.warnings.add("missing_road_sketch");
        }
        if (map.anchor_points == null || map.anchor_points.isEmpty()) {
            check.warnings.add("missing_anchor_points");
        }
        if (sovereigntyProbe != null && map.city_boundary != null && map.city_boundary.polygon != null) {
            for (CityC1ImageIntentModels.IntentPoint point : map.city_boundary.polygon) {
                if (!sovereigntyProbe.isWithinSovereignty(point.world_x, point.world_z)) {
                    check.boundary_conflicts.add("city_boundary_vertex_outside_territory:" + point.world_x + "," + point.world_z);
                }
            }
        }
        if (!check.boundary_conflicts.isEmpty()) {
            check.warnings.add("boundary_has_territory_conflicts");
            check.repair_actions.add("review_or_redraw_city_boundary");
        }
        check.ok = check.blocking_errors.isEmpty();
        return check;
    }
}

