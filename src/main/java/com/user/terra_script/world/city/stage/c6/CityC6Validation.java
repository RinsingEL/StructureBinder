package com.user.terra_script.world.city.stage.c6;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.user.terra_script.world.city.stage.CityGroupPathUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CityC6Validation {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private CityC6Validation() {}

    public static final class Report {
        public String step = "C6";
        public String city_id;
        public String group_id;
        public boolean ok = true;
        public int failure_count = 0;
        public List<Item> items = new ArrayList<>();
    }

    public static final class Item {
        public String module_id;
        public String build_area_id;
        public double coverage_ratio;
        public int inside_buildable_blocks;
        public int total_rect_blocks;
        public int out_of_bounds_blocks;
        public int forbidden_overlap_blocks = 0;
        public String terrain_height_range;
        public double slope_avg;
        public int max_local_relief;
        public String status;
        public String reason;
        public String suggested_fix;
    }

    public static Report generate(
            String cityId,
            String groupId,
            CityC6Stages.C6Summary summary,
            CityC6Stages.C6Layout layout,
            Map<Long, Integer> indexByBlock
    ) {
        Report report = new Report();
        report.city_id = cityId;
        report.group_id = groupId;
        if (summary == null || layout == null || indexByBlock == null) {
            report.ok = false;
            report.failure_count = 1;
            return report;
        }

        Map<String, CityC6Stages.BuildAreaSummary> areaById = new HashMap<>();
        for (CityC6Stages.BuildAreaSummary area : summary.areas) {
            if (area != null && area.build_area_id != null) areaById.put(area.build_area_id, area);
        }

        for (CityC6Stages.LayoutPlan plan : layout.plans) {
            if (plan == null) continue;
            if (groupId != null && !groupId.isBlank() && !groupId.equals(plan.group_id)) continue;
            CityC6Stages.BuildAreaSummary area = areaById.get(plan.build_area_id);
            if (area == null) continue;

            int w = 8;
            int h = 8;
            if (plan.rect_sizes != null && !plan.rect_sizes.isEmpty()) {
                CityC6Stages.RectSize best = plan.rect_sizes.get(0);
                for (CityC6Stages.RectSize candidate : plan.rect_sizes) {
                    if (candidate != null && candidate.weight > best.weight) best = candidate;
                }
                w = average(best.w_blocks, 8);
                h = average(best.h_blocks, 8);
            }
            CityC6Stages.Point anchor = plan.primary_modules != null && !plan.primary_modules.isEmpty()
                    ? plan.primary_modules.get(0).anchor
                    : area.centroid;
            int cx = (int) Math.round(anchor != null ? anchor.x : area.centroid.x);
            int cz = (int) Math.round(anchor != null ? anchor.z : area.centroid.z);
            int minX = cx - w / 2;
            int minZ = cz - h / 2;
            int maxX = minX + w - 1;
            int maxZ = minZ + h - 1;
            int total = 0;
            int inside = 0;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    total++;
                    Integer areaId = indexByBlock.get(packBlock(x, z));
                    if (areaId != null && areaId == area.build_area_numeric_id) inside++;
                }
            }

            Item item = new Item();
            item.module_id = plan.primary_modules != null && !plan.primary_modules.isEmpty() && plan.primary_modules.get(0) != null
                    ? plan.primary_modules.get(0).module_id
                    : (plan.group_id + "_primary");
            item.build_area_id = plan.build_area_id;
            item.total_rect_blocks = total;
            item.inside_buildable_blocks = inside;
            item.out_of_bounds_blocks = Math.max(0, total - inside);
            item.coverage_ratio = total <= 0 ? 0.0 : round3(inside / (double) total);
            item.terrain_height_range = String.format(Locale.ROOT, "%.0f..%.0f", area.avg_height, area.avg_height);
            item.slope_avg = 0.0;
            item.max_local_relief = 0;
            if (item.coverage_ratio < 0.80) {
                item.status = "fail";
                item.reason = "coverage_below_threshold";
                item.suggested_fix = "shrink_or_move_primary_rect";
                report.ok = false;
                report.failure_count++;
            } else {
                item.status = "accept";
                item.reason = "ok";
                item.suggested_fix = "keep";
            }
            report.items.add(item);
        }
        return report;
    }

    public static Path save(Path cityDir, String groupId, Report report) throws Exception {
        Path groupDir = CityGroupPathUtil.resolveGroupDir(cityDir, groupId);
        Path file = groupDir.resolve("c6_validation.json");
        Files.writeString(file, GSON.toJson(report), StandardCharsets.UTF_8);
        return file;
    }

    private static int average(List<Integer> values, int fallback) {
        if (values == null || values.isEmpty()) return fallback;
        if (values.size() == 1) return values.get(0);
        return (int) Math.round((values.get(0) + values.get(1)) / 2.0);
    }

    private static long packBlock(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
