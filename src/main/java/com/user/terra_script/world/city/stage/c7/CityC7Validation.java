package com.user.terra_script.world.city.stage.c7;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.user.terra_script.world.city.stage.CityGroupPathUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CityC7Validation {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private CityC7Validation() {}

    public static final class Report {
        public String step = "C7";
        public String city_id;
        public String group_id;
        public boolean ok = true;
        public int failure_count = 0;
        public List<Item> items = new ArrayList<>();
    }

    public static final class Item {
        public String module_id;
        public String selected_template;
        public String size_tier;
        public String status;
        public String reason;
        public String suggested_fix;
    }

    public static Report generate(String cityId, String groupId, CityC7Stages.C7Selection selection) {
        Report report = new Report();
        report.city_id = cityId;
        report.group_id = groupId;
        if (selection == null || selection.selections == null) {
            report.ok = false;
            report.failure_count = 1;
            return report;
        }
        for (CityC7Stages.TemplateSelectionItem selected : selection.selections) {
            if (selected == null) continue;
            if (groupId != null && !groupId.isBlank() && !groupId.equals(selected.group_id)) continue;
            Item item = new Item();
            item.module_id = selected.module_id;
            item.selected_template = selected.selected_template;
            item.size_tier = selected.size_tier;
            if (selected.selected_template == null || selected.selected_template.isBlank()) {
                item.status = "fail";
                item.reason = "missing_template_selection";
                item.suggested_fix = "pick_template_for_primary_module";
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
        Path file = groupDir.resolve("c7_validation.json");
        Files.writeString(file, GSON.toJson(report), StandardCharsets.UTF_8);
        return file;
    }

}
