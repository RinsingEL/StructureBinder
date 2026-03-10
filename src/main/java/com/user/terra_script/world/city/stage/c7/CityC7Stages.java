package com.user.terra_script.world.city.stage.c7;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.user.terra_script.world.city.stage.c6.CityC6Stages;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CityC7Stages {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final String C7_FILE = "C7_TemplateSelection.json";
    private static final String C3_5_CATALOG_FILE = "config/structureTemplate/C3_5_StructureCatalog.preprocessed.json";

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

    private static final class Catalog {
        public String step;
        public boolean ok;
        public String city_id;
        public List<CatalogStructure> structures = new ArrayList<>();
    }

    private static final class CatalogStructure {
        public String structure_id;
        public Size size = new Size();
        public Orientation orientation = new Orientation();
        public String piece_role;
        public Map<String, Double> style_score = new LinkedHashMap<>();
        public List<FunctionCandidate> function_candidates = new ArrayList<>();
        public String namespace;
        public String path;
        public String size_tier;
        public List<String> connector_types = new ArrayList<>();
        public List<String> connector_dirs = new ArrayList<>();
        public List<String> allowed_neighbors = new ArrayList<>();
        public String landing_hint;
        public String growth_axis;
        public String vertical_role;
        public int vertical_clearance;
        public TagSource tag_source = new TagSource();
        public String notes;
    }

    private static final class Size {
        public int length;
        public int width;
        public int height;
    }

    private static final class Orientation {
        public List<String> jigsaw_facing = new ArrayList<>();
        public String entry_facing;
        public List<Integer> rotations = new ArrayList<>();
    }

    private static final class FunctionCandidate {
        public String function;
        public double score;
    }

    private static final class TagSource {
        public boolean scanner;
        public String preset_rule;
        public boolean manual_override;
    }

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
        public String landing_hint;
        public String growth_axis;
        public String vertical_role;
        public int vertical_clearance;
        public boolean vertical_capable;
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

        Catalog catalog = loadCatalog();
        result.catalog_source = catalog != null && catalog.ok
                ? FMLPaths.GAMEDIR.get().resolve(C3_5_CATALOG_FILE).toString()
                : "hardcoded_vanilla_village_templates";

        for (CityC6Stages.LayoutPlan plan : c6Layout.plans) {
            if (plan == null) continue;
            if (plan.primary_modules == null || plan.primary_modules.isEmpty()) continue;
            for (CityC6Stages.PrimaryModule module : plan.primary_modules) {
                TemplateSelectionItem item = buildItem(plan, module, catalog);
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

    private static TemplateSelectionItem buildItem(CityC6Stages.LayoutPlan plan, CityC6Stages.PrimaryModule module, Catalog catalog) {
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
        item.function_role = inferFunctionRole(category, item.module_id, plan.group_id);
        item.interaction_role = inferInteractionRole(item.function_role);

        List<String> candidates = chooseCandidates(category, item.size_tier, item.function_role, plan.group_id, catalog);
        item.top_k_templates = new ArrayList<>(candidates.subList(0, Math.min(3, candidates.size())));
        item.selected_template = item.top_k_templates.isEmpty() ? null : item.top_k_templates.get(0);
        item.fallback_chain = new ArrayList<>();
        for (String c : item.top_k_templates) {
            if (!c.equals(item.selected_template)) item.fallback_chain.add(c);
        }
        if (item.fallback_chain.isEmpty()) {
            List<String> backup = chooseCandidates("residential", "S", "residential", plan.group_id, null);
            for (String b : backup) {
                if (item.selected_template != null && item.selected_template.equals(b)) continue;
                item.fallback_chain.add(b);
                if (item.fallback_chain.size() >= 2) break;
            }
        }

        if (catalog != null && catalog.ok && item.selected_template != null && !item.selected_template.isBlank()) {
            CatalogStructure selected = findStructure(catalog, item.selected_template);
            if (selected != null) {
                item.landing_hint = safe(selected.landing_hint);
                item.growth_axis = safe(selected.growth_axis);
                item.vertical_role = safe(selected.vertical_role);
                item.vertical_clearance = Math.max(0, selected.vertical_clearance);
                item.vertical_capable = !item.growth_axis.isBlank() || !item.vertical_role.isBlank() || item.vertical_clearance > 0;
            }
        }

        item.notes = catalog != null && catalog.ok && !item.top_k_templates.isEmpty()
                ? "Catalog-driven selection from C3.5 structure catalog"
                : "Hardcoded village catalog fallback (phase-1 C7)";
        return item;
    }

    private static List<String> chooseCandidates(String category, String sizeTier, String functionRole, String groupId, Catalog catalog) {
        List<String> fromCatalog = chooseCandidatesFromCatalog(sizeTier, functionRole, groupId, catalog);
        if (!fromCatalog.isEmpty()) return fromCatalog;

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

    private static List<String> chooseCandidatesFromCatalog(String sizeTier, String functionRole, String groupId, Catalog catalog) {
        if (catalog == null || !catalog.ok || catalog.structures == null || catalog.structures.isEmpty()) {
            return Collections.emptyList();
        }

        String tier = normalizeTier(sizeTier);
        String normalizedFunction = normalizeCatalogFunction(functionRole);
        List<ScoredTemplate> scored = new ArrayList<>();
        for (CatalogStructure structure : catalog.structures) {
            if (structure == null || structure.structure_id == null || structure.structure_id.isBlank()) continue;
            double score = scoreStructure(structure, tier, normalizedFunction, groupId);
            if (score <= 0.0) continue;
            scored.add(new ScoredTemplate(structure.structure_id, score));
        }
        scored.sort(Comparator.comparingDouble(ScoredTemplate::score).reversed().thenComparing(ScoredTemplate::id));

        List<String> out = new ArrayList<>();
        for (ScoredTemplate candidate : scored) {
            out.add(candidate.id());
            if (out.size() >= 6) break;
        }
        return out;
    }

    private static double scoreStructure(CatalogStructure structure, String sizeTier, String functionRole, String groupId) {
        double score = 0.0;

        String pieceRole = safe(structure.piece_role).toUpperCase(Locale.ROOT);
        if ("START".equals(pieceRole)) score += 0.20;
        else if ("SINGLE".equals(pieceRole)) score += 0.16;
        else if ("MIDDLE".equals(pieceRole)) score += 0.06;

        String structureTier = normalizeTier(structure.size_tier);
        if (sizeTier.equals(structureTier)) score += 0.20;
        else if (isNeighborTier(sizeTier, structureTier)) score += 0.10;

        double bestFunctionScore = 0.0;
        if (structure.function_candidates != null) {
            for (FunctionCandidate candidate : structure.function_candidates) {
                if (candidate == null) continue;
                String actual = normalizeCatalogFunction(candidate.function);
                if (functionRole.equals(actual)) bestFunctionScore = Math.max(bestFunctionScore, candidate.score);
                else if (isCompatibleFunction(functionRole, actual)) bestFunctionScore = Math.max(bestFunctionScore, candidate.score * 0.75);
            }
        }
        score += bestFunctionScore;

        String path = safe(structure.path).toLowerCase(Locale.ROOT);
        String group = safe(groupId).toLowerCase(Locale.ROOT);
        if (group.contains("port") && (path.contains("ocean") || path.contains("ship") || path.contains("lighthouse") || path.contains("harbor") || path.contains("port"))) {
            score += 0.12;
        }
        if ((group.contains("defense") || group.contains("tower")) && (path.contains("tower") || path.contains("outpost"))) {
            score += 0.08;
        }
        if (group.contains("market") && (path.contains("market") || path.contains("shop"))) {
            score += 0.08;
        }
        if (group.contains("farm") && path.contains("farm")) {
            score += 0.08;
        }

        if (!safe(structure.growth_axis).isBlank() || !safe(structure.vertical_role).isBlank() || structure.vertical_clearance > 0) {
            score += 0.04;
        }
        if (safe(structure.notes).toLowerCase(Locale.ROOT).contains("helper/base piece")) {
            score -= 0.22;
        }
        if (path.contains("villagers/")) {
            score -= 0.50;
        }
        return score;
    }

    private static boolean isNeighborTier(String wanted, String actual) {
        if ("M".equals(wanted) && ("S".equals(actual) || "L".equals(actual))) return true;
        if ("S".equals(wanted) && "M".equals(actual)) return true;
        if ("L".equals(wanted) && "M".equals(actual)) return true;
        return false;
    }

    private static boolean isCompatibleFunction(String wanted, String actual) {
        if (wanted.equals(actual)) return true;
        if ("port".equals(wanted) && ("fishing".equals(actual) || "civic_center".equals(actual))) return true;
        if ("civic_center".equals(wanted) && ("civic_center".equals(actual) || "commercial".equals(actual))) return true;
        if ("residential".equals(wanted) && "residential".equals(actual)) return true;
        if ("military".equals(wanted) && ("military".equals(actual) || "port".equals(actual))) return true;
        if ("commercial".equals(wanted) && ("market".equals(actual) || "commercial".equals(actual))) return true;
        return false;
    }

    private static String inferFunctionRole(String category, String moduleId, String groupId) {
        String g = safe(groupId).toLowerCase(Locale.ROOT);
        if (g.contains("port")) return "port";
        if (g.contains("market")) return "market";
        if (g.contains("shop")) return "commercial";
        if (g.contains("school")) return "civic_center";
        if (g.contains("farm")) return "farm";
        if (g.contains("defense") || g.contains("tower") || g.contains("fort")) return "military";
        if (g.contains("residential")) return "residential";

        String c = safe(category).toLowerCase(Locale.ROOT);
        String m = safe(moduleId).toLowerCase(Locale.ROOT);
        if (c.contains("plaza") || c.contains("civic") || m.contains("core")) return "civic_center";
        if (m.contains("market")) return "market";
        if (m.contains("shop")) return "commercial";
        if (m.contains("military") || m.contains("fort")) return "military";
        if (m.contains("port") || m.contains("dock") || m.contains("harbor")) return "port";
        if (m.contains("farm")) return "farm";
        return "residential";
    }

    private static String inferInteractionRole(String functionRole) {
        if ("civic_center".equals(functionRole) || "market".equals(functionRole)) return "FRONT_TO_PLAZA";
        if ("military".equals(functionRole) || "port".equals(functionRole)) return "EDGE_ATTACH";
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

    private static String normalizeCatalogFunction(String raw) {
        String s = safe(raw).toLowerCase(Locale.ROOT);
        if (s.startsWith("residential")) return "residential";
        if ("watchtower".equals(s) || "fortification".equals(s)) return "military";
        if ("market".equals(s)) return "market";
        if ("commercial".equals(s) || "warehouse".equals(s) || "workshop".equals(s)) return "commercial";
        if ("civic_center".equals(s) || "religious".equals(s) || "landmark".equals(s)) return "civic_center";
        if ("port".equals(s) || "fishing".equals(s) || "farm".equals(s)) return s;
        return s.isBlank() ? "residential" : s;
    }

    private static Catalog loadCatalog() {
        try {
            Path path = FMLPaths.GAMEDIR.get().resolve(C3_5_CATALOG_FILE);
            if (!Files.exists(path)) return null;
            Catalog catalog = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Catalog.class);
            if (catalog == null) return null;
            catalog.ok = catalog.ok && catalog.structures != null;
            return catalog;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static CatalogStructure findStructure(Catalog catalog, String structureId) {
        if (catalog == null || catalog.structures == null || structureId == null || structureId.isBlank()) return null;
        for (CatalogStructure structure : catalog.structures) {
            if (structure != null && structureId.equals(structure.structure_id)) return structure;
        }
        return null;
    }

    private static String safe(String raw) {
        return raw == null ? "" : raw;
    }

    private record ScoredTemplate(String id, double score) {}
}
