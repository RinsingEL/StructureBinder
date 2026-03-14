package com.user.terra_script.world.city.stage.c7;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.user.terra_script.world.city.stage.CityGroupPathUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
        public String arrangement_type;
        public String selected_template;
        public String status;
        public String reason;
        public String suggested_fix;
    }

    public static Report generate(String cityId, String groupId, CityC7Stages.C7Selection selection) {
        Report report = new Report();
        report.city_id = cityId;
        report.group_id = groupId;
        if (selection == null) {
            report.ok = false;
            report.failure_count = 1;
            return report;
        }

        if (selection.arrangements != null && !selection.arrangements.isEmpty()) {
            for (CityC7Stages.GroupArrangementDecision arrangement : selection.arrangements) {
                if (arrangement == null) continue;
                if (groupId != null && !groupId.isBlank() && !groupId.equals(arrangement.group_id)) continue;
                validateArrangement(report, arrangement);
            }
            return report;
        }

        if (selection.selections == null) {
            report.ok = false;
            report.failure_count = 1;
            return report;
        }
        for (CityC7Stages.TemplateSelectionItem selected : selection.selections) {
            if (selected == null) continue;
            if (groupId != null && !groupId.isBlank() && !groupId.equals(selected.group_id)) continue;
            Item item = new Item();
            item.module_id = selected.module_id;
            item.arrangement_type = selected.arrangement_type;
            item.selected_template = selected.selected_template;
            if (selected.selected_template == null || selected.selected_template.isBlank()) {
                fail(report, item, "missing_template_selection", "pick_template_for_component");
            } else if (selected.arrangement_type == null || selected.arrangement_type.isBlank()) {
                fail(report, item, "missing_arrangement_type", "choose_arrangement_type");
            } else {
                accept(item);
            }
            report.items.add(item);
        }
        return report;
    }

    private static void validateArrangement(Report report, CityC7Stages.GroupArrangementDecision arrangement) {
        if (arrangement.selected_components == null || arrangement.selected_components.isEmpty()) {
            Item item = new Item();
            item.module_id = arrangement.group_id;
            item.arrangement_type = arrangement.arrangement_type;
            fail(report, item, "missing_selected_components", "select_components_from_preset_pool");
            report.items.add(item);
            return;
        }

        for (CityC7Stages.SelectedComponent component : arrangement.selected_components) {
            if (component == null) continue;
            Item item = new Item();
            item.module_id = component.component_id;
            item.arrangement_type = arrangement.arrangement_type;
            item.selected_template = component.template_id;
            if (arrangement.arrangement_type == null || arrangement.arrangement_type.isBlank()) {
                fail(report, item, "missing_arrangement_type", "choose_arrangement_type");
            } else if (component.template_id == null || component.template_id.isBlank()) {
                fail(report, item, "missing_template_selection", "pick_template_for_component");
            } else if (component.rule == null) {
                fail(report, item, "missing_component_rule", "provide_component_rule");
            } else {
                accept(item);
            }
            report.items.add(item);
        }
    }

    private static void fail(Report report, Item item, String reason, String suggestedFix) {
        item.status = "fail";
        item.reason = reason;
        item.suggested_fix = suggestedFix;
        report.ok = false;
        report.failure_count++;
    }

    private static void accept(Item item) {
        item.status = "accept";
        item.reason = "ok";
        item.suggested_fix = "keep";
    }

    public static Path save(Path cityDir, String groupId, Report report) throws Exception {
        Path groupDir = CityGroupPathUtil.resolveGroupDir(cityDir, groupId);
        Path file = groupDir.resolve("c7_validation.json");
        Files.writeString(file, GSON.toJson(report), StandardCharsets.UTF_8);
        return file;
    }
}
