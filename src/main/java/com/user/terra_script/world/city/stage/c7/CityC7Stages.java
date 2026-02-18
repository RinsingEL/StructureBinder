package com.user.terra_script.world.city.stage.c7;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.user.terra_script.world.city.stage.c6.CityC6Stages;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CityC7Stages {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final String C7_FILE = "C7_TemplateSelection.json";

    private static final List<String> VILLAGE_CIVIC_L = List.of(
            "minecraft:village/plains/town_centers/plains_meeting_point_1",
            "minecraft:village/plains/town_centers/plains_meeting_point_2",
            "minecraft:village/plains/town_centers/plains_meeting_point_3",
            "minecraft:village/savanna/town_centers/savanna_meeting_point_1",
            "minecraft:village/taiga/town_centers/taiga_meeting_point_1"
    );
    private static final List<String> VILLAGE_HOUSE_M = List.of(
            "minecraft:village/plains/houses/plains_medium_house_1",
            "minecraft:village/plains/houses/plains_medium_house_2",
            "minecraft:village/plains/houses/plains_big_house_1",
            "minecraft:village/taiga/houses/taiga_medium_house_1",
            "minecraft:village/savanna/houses/savanna_medium_house_1",
            "minecraft:village/desert/houses/desert_medium_house_1"
    );
    private static final List<String> VILLAGE_HOUSE_S = List.of(
            "minecraft:village/plains/houses/plains_small_house_1",
            "minecraft:village/plains/houses/plains_small_house_2",
            "minecraft:village/plains/houses/plains_small_house_3",
            "minecraft:village/plains/houses/plains_small_house_4",
            "minecraft:village/savanna/houses/savanna_small_house_1",
            "minecraft:village/taiga/houses/taiga_small_house_1",
            "minecraft:village/desert/houses/desert_small_house_1",
            "minecraft:village/snowy/houses/snowy_small_house_1"
    );

    private CityC7Stages() {}

    public static class C7Selection {
        public String step = "C7";
        public boolean ok = true;
        public String city_id;
        public long generated_at_epoch_ms;
        public String catalog_source = "hardcoded_vanilla_village_templates";
        public int puzzle_depth = 0;
        public List<TemplateSelectionItem> selections = new ArrayList<>();
    }

    public static class TemplateSelectionItem {
        public String group_id;
        public String build_area_id;
        public String module_id;
        public String function_role;
        public String interaction_role = "FRONT_TO_PLAZA";
        public String size_tier = "M";
        public String selected_template;
        public List<String> top_k_templates = new ArrayList<>();
        public List<String> fallback_chain = new ArrayList<>();
        public String notes;
    }

    public static C7Selection generate(String cityId, CityC6Stages.C6Layout c6Layout) {
        C7Selection result = new C7Selection();
        result.city_id = cityId;
        result.generated_at_epoch_ms = System.currentTimeMillis();
        if (c6Layout == null || c6Layout.plans == null) {
            result.ok = false;
            return result;
        }

        for (CityC6Stages.LayoutPlan plan : c6Layout.plans) {
            if (plan == null) continue;
            if (plan.primary_modules == null || plan.primary_modules.isEmpty()) {
                TemplateSelectionItem item = buildItem(plan, null);
                result.selections.add(item);
                continue;
            }
            for (CityC6Stages.PrimaryModule module : plan.primary_modules) {
                TemplateSelectionItem item = buildItem(plan, module);
                result.selections.add(item);
            }
        }
        result.selections.sort(Comparator.comparing(i -> safe(i.build_area_id) + "|" + safe(i.module_id)));
        return result;
    }

    public static void save(Path cityDir, C7Selection selection) throws Exception {
        if (cityDir == null || selection == null) return;
        Files.createDirectories(cityDir);
        Files.writeString(cityDir.resolve(C7_FILE), GSON.toJson(selection), StandardCharsets.UTF_8);
    }

    public static C7Selection load(Path cityDir) throws Exception {
        Path file = cityDir.resolve(C7_FILE);
        if (!Files.exists(file)) return null;
        return GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), C7Selection.class);
    }

    private static TemplateSelectionItem buildItem(CityC6Stages.LayoutPlan plan, CityC6Stages.PrimaryModule module) {
        TemplateSelectionItem item = new TemplateSelectionItem();
        item.group_id = plan.group_id;
        item.build_area_id = plan.build_area_id;
        item.module_id = module != null && module.module_id != null ? module.module_id : ("m_" + safe(plan.group_id) + "_default");

        String category = "residential";
        String sizeTier = "M";
        if (module != null && module.template_hint != null) {
            category = module.template_hint.category != null ? module.template_hint.category : category;
            sizeTier = module.template_hint.size_tier != null ? module.template_hint.size_tier : sizeTier;
        }
        item.size_tier = normalizeTier(sizeTier);
        item.function_role = inferFunctionRole(category, item.module_id);
        item.interaction_role = inferInteractionRole(item.function_role);

        List<String> candidates = chooseCandidates(category, item.size_tier);
        item.top_k_templates = new ArrayList<>(candidates.subList(0, Math.min(3, candidates.size())));
        item.selected_template = item.top_k_templates.isEmpty() ? null : item.top_k_templates.get(0);
        item.fallback_chain = new ArrayList<>();
        for (String c : item.top_k_templates) {
            if (!c.equals(item.selected_template)) item.fallback_chain.add(c);
        }
        if (item.fallback_chain.isEmpty()) {
            List<String> backup = chooseCandidates("residential", "S");
            for (String b : backup) {
                if (item.selected_template != null && item.selected_template.equals(b)) continue;
                item.fallback_chain.add(b);
                if (item.fallback_chain.size() >= 2) break;
            }
        }
        item.notes = "Hardcoded village catalog (phase-1 C7)";
        return item;
    }

    private static List<String> chooseCandidates(String category, String sizeTier) {
        String c = safe(category).toLowerCase(Locale.ROOT);
        String t = normalizeTier(sizeTier);

        if (c.contains("plaza") || c.contains("civic")) {
            if ("L".equals(t)) return VILLAGE_CIVIC_L;
            return merge(VILLAGE_CIVIC_L, VILLAGE_HOUSE_M);
        }
        if ("L".equals(t)) return merge(VILLAGE_HOUSE_M, VILLAGE_CIVIC_L);
        if ("S".equals(t)) return VILLAGE_HOUSE_S;
        return VILLAGE_HOUSE_M;
    }

    private static String inferFunctionRole(String category, String moduleId) {
        String c = safe(category).toLowerCase(Locale.ROOT);
        String m = safe(moduleId).toLowerCase(Locale.ROOT);
        if (c.contains("plaza") || c.contains("civic") || m.contains("core")) return "civic_center";
        if (m.contains("market")) return "market";
        if (m.contains("military") || m.contains("fort")) return "military";
        return "residential";
    }

    private static String inferInteractionRole(String functionRole) {
        if ("civic_center".equals(functionRole) || "market".equals(functionRole)) return "FRONT_TO_PLAZA";
        if ("military".equals(functionRole)) return "EDGE_ATTACH";
        return "FRONT_TO_STREET";
    }

    private static List<String> merge(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>();
        if (a != null) out.addAll(a);
        if (b != null) {
            for (String s : b) {
                if (!out.contains(s)) out.add(s);
            }
        }
        return out;
    }

    private static String normalizeTier(String raw) {
        String s = safe(raw).toUpperCase(Locale.ROOT);
        if (Arrays.asList("S", "M", "L").contains(s)) return s;
        return "M";
    }

    private static String safe(String raw) {
        return raw == null ? "" : raw;
    }
}

