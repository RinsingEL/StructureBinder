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

        CityC6Stages.RectGuidance guidance = CityC6Stages.deriveRectGuidance(area, List.of());
        CityC6Stages.PlazaRingParams params = defaultParams(area.area_blocks, guidance);
        plan.fill_params = params;
        plan.rect_sizes = defaultRectSizes(guidance);
        plan.rect_guidance = guidance;

        CityC6Stages.SecondaryFill fill = new CityC6Stages.SecondaryFill();
        fill.zone = "around_primary";
        fill.style = "PLAZA_RING";
        fill.params = params;
        plan.secondary_fill.add(fill);
        return plan;
    }

    public static CityC6Stages.PlazaRingParams defaultParams(int areaBlocks) {
        return defaultParams(areaBlocks, null);
    }

    public static CityC6Stages.PlazaRingParams defaultParams(int areaBlocks, CityC6Stages.RectGuidance guidance) {
        int radius = (int) Math.max(8, Math.min(20, Math.sqrt(Math.max(1, areaBlocks) / Math.PI) * 0.18));
        int footprintSpan = guidance != null
                ? Math.max(guidance.main_template_width_blocks.recommended, guidance.main_template_height_blocks.recommended)
                : 0;
        int outer = Math.max(radius + 4, radius + Math.max(8, (int) Math.ceil(footprintSpan * 0.5)));

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
        params.reserve_decor_ratio = guidance != null && guidance.fallback_path ? 0.10 : 0.08;
        return params;
    }

    public static List<CityC6Stages.RectSize> defaultRectSizes() {
        return defaultRectSizes(null);
    }

    public static List<CityC6Stages.RectSize> defaultRectSizes(CityC6Stages.RectGuidance guidance) {
        if (guidance == null || guidance.rect_width_blocks.max <= 0 || guidance.rect_height_blocks.max <= 0) {
            List<CityC6Stages.RectSize> sizes = new ArrayList<>();
            sizes.add(rect("S1", 7, 9, 7, 9, 0.55, 6, 18));
            sizes.add(rect("M1", 10, 14, 8, 12, 0.35, 2, 8));
            sizes.add(rect("L1", 16, 22, 12, 18, 0.10, 0, 2));
            return sizes;
        }

        List<CityC6Stages.RectSize> sizes = new ArrayList<>();
        int minCount = Math.max(1, guidance.recommended_rect_count.min);
        int recommended = Math.max(minCount, guidance.recommended_rect_count.recommended);
        int maxCount = Math.max(recommended, guidance.recommended_rect_count.max);
        int narrowWidth = Math.max(4, guidance.rect_width_blocks.min);
        int narrowHeight = Math.max(4, guidance.rect_height_blocks.min);
        int midWidth = Math.max(narrowWidth, guidance.rect_width_blocks.recommended);
        int midHeight = Math.max(narrowHeight, guidance.rect_height_blocks.recommended);
        int largeWidth = Math.max(midWidth, guidance.rect_width_blocks.max);
        int largeHeight = Math.max(midHeight, guidance.rect_height_blocks.max);
        sizes.add(rect("F1", narrowWidth, Math.max(narrowWidth, midWidth - 1), narrowHeight, Math.max(narrowHeight, midHeight - 1), 0.40, minCount, maxCount));
        sizes.add(rect("F2", Math.max(narrowWidth, midWidth - 1), midWidth + 1, Math.max(narrowHeight, midHeight - 1), midHeight + 1, 0.40, Math.max(1, recommended - 1), maxCount));
        sizes.add(rect("F3", Math.max(midWidth, largeWidth - 3), largeWidth, Math.max(midHeight, largeHeight - 3), largeHeight, 0.20, 0, Math.max(1, maxCount - minCount + 1)));
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
