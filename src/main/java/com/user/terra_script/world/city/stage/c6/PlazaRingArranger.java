package com.user.terra_script.world.city.stage.c6;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class PlazaRingArranger {
    private PlazaRingArranger() {}

    public static CityC6Stages.LayoutPlan createDefaultPlan(CityC6Stages.BuildAreaSummary area) {
        CityC6Stages.LayoutPlan plan = new CityC6Stages.LayoutPlan();
        plan.group_id = area.group_id;
        plan.build_area_id = area.build_area_id;
        plan.fill_style = C6FillStyle.PLAZA_RING.name();
        plan.notes = "Auto-generated PLAZA_RING plan";

        CityC6Stages.PrimaryModule primary = new CityC6Stages.PrimaryModule();
        primary.module_id = "m_" + safeLower(area.function, "module") + "_core";
        primary.anchor = new CityC6Stages.Point();
        primary.anchor.x = area.centroid.x;
        primary.anchor.z = area.centroid.z;
        primary.importance = 1.0;
        primary.template_hint = new CityC6Stages.TemplateHint();
        primary.template_hint.category = "plaza_or_civic";
        primary.template_hint.size_tier = sizeTier(area.area_blocks);
        plan.primary_modules.add(primary);

        CityC6Stages.PlazaRingParams params = defaultParams(area.area_blocks);
        plan.fill_params = params;
        plan.rect_sizes = defaultRectSizes();

        CityC6Stages.SecondaryFill fill = new CityC6Stages.SecondaryFill();
        fill.zone = "around_primary";
        fill.style = "PLAZA_RING";
        fill.params = params;
        plan.secondary_fill.add(fill);
        return plan;
    }

    private static CityC6Stages.PlazaRingParams defaultParams(int areaBlocks) {
        int radius = (int) Math.max(8, Math.min(20, Math.sqrt(Math.max(1, areaBlocks) / Math.PI) * 0.18));
        int outer = Math.max(radius + 4, radius + 8);

        CityC6Stages.PlazaRingParams params = new CityC6Stages.PlazaRingParams();
        params.plaza_shape = "CIRCLE";
        params.plaza_radius_blocks = Arrays.asList(radius, radius + 3);
        params.plaza_padding_blocks = Arrays.asList(2, 4);
        params.ring_count = 1;
        params.ring_depth_blocks.add(Arrays.asList(outer - radius, outer - radius + 4));
        params.ring_gap_blocks = new ArrayList<>();
        params.opening_count = 1;
        params.opening_width_blocks = Arrays.asList(8, 12);
        params.opening_angle_deg = Arrays.asList(30, 60);
        params.opening_prefer_dirs = Arrays.asList("S", "SE");
        params.min_spacing_blocks = Arrays.asList(2, 4);
        params.jitter = 0.35;
        params.rotation_mode = "TANGENT";
        params.rotation_jitter_deg = Arrays.asList(0, 12);
        params.respect_build_area_boundary = true;
        params.reserve_decor_ratio = 0.10;
        return params;
    }

    private static List<CityC6Stages.RectSize> defaultRectSizes() {
        List<CityC6Stages.RectSize> sizes = new ArrayList<>();
        sizes.add(rect("S1", 7, 9, 7, 9, 0.55, 6, 18));
        sizes.add(rect("M1", 10, 14, 8, 12, 0.35, 2, 8));
        sizes.add(rect("L1", 16, 22, 12, 18, 0.10, 0, 2));
        return sizes;
    }

    private static CityC6Stages.RectSize rect(
            String id, int wMin, int wMax, int hMin, int hMax, double weight, int minCount, int maxCount
    ) {
        CityC6Stages.RectSize r = new CityC6Stages.RectSize();
        r.id = id;
        r.w_blocks = Arrays.asList(wMin, wMax);
        r.h_blocks = Arrays.asList(hMin, hMax);
        r.weight = weight;
        r.min_count = minCount;
        r.max_count = maxCount;
        return r;
    }

    private static String sizeTier(int areaBlocks) {
        if (areaBlocks >= 10000) return "L";
        if (areaBlocks >= 3000) return "M";
        return "S";
    }

    private static String safeLower(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }
}
