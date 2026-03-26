package com.user.terra_script.world.city.stage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class StructureTemplateQueryService {
    private static final Gson GSON = new GsonBuilder().create();
    private static final String HEURISTIC_SOURCE = "mcp_path_heuristic";

    private StructureTemplateQueryService() {}

    public static final class QueryRequest {
        public String function_tag;
        public String size_tier;
        public String arrangement_type;
        public boolean require_connector;
        public boolean strict_tag_source = true;
    }

    public static final class QueryResult {
        public boolean ok;
        public String function_tag;
        public int candidate_count;
        public String failure_reason;
        public List<Candidate> candidates = new ArrayList<>();
    }

    public static final class Candidate {
        public String structure_id;
        public String size_tier;
        public String piece_role;
        public String preset_pool;
        public List<CityC35CatalogIO.FunctionCandidate> function_candidates = new ArrayList<>();
        public String function_source;
        public String tag_source_rule;
        public boolean has_connectors;
    }

    public static QueryResult queryTemplates(QueryRequest request) {
        QueryResult result = new QueryResult();
        result.function_tag = request != null ? request.function_tag : null;
        try {
            CityC35CatalogIO.StructureCatalog catalog = CityC35CatalogIO.loadCatalog(GSON, CityC35CatalogIO.StructureCatalog.class);
            if (catalog == null || !catalog.ok || catalog.structures == null) {
                result.ok = false;
                result.failure_reason = "catalog_not_found";
                return result;
            }
            return queryTemplates(catalog.structures, request);
        } catch (Exception e) {
            result.ok = false;
            result.failure_reason = "catalog_load_failed";
            return result;
        }
    }

    public static QueryResult queryTemplates(List<? extends CityC35CatalogIO.CatalogStructure> structures, QueryRequest request) {
        QueryResult result = new QueryResult();
        result.function_tag = request != null ? request.function_tag : null;
        if (request == null || request.function_tag == null || request.function_tag.isBlank()) {
            result.ok = false;
            result.failure_reason = "missing_function_tag";
            return result;
        }
        Set<String> targetFunctions = targetFunctions(request.function_tag);
        if (targetFunctions.isEmpty()) {
            result.ok = false;
            result.failure_reason = "unsupported_function_tag";
            return result;
        }

        for (CityC35CatalogIO.CatalogStructure structure : structures) {
            if (structure == null || structure.structure_id == null || structure.structure_id.isBlank()) continue;
            if (!matchesSizeTier(request.size_tier, structure.size_tier)) continue;
            if (request.require_connector && !hasConnectors(structure)) continue;
            if (!matchesFunction(structure, targetFunctions, request.strict_tag_source)) continue;
            result.candidates.add(toCandidate(structure));
        }
        result.candidates.sort(Comparator.comparing(candidate -> candidate.structure_id));
        result.candidate_count = result.candidates.size();
        result.ok = !result.candidates.isEmpty();
        if (!result.ok) {
            result.failure_reason = "no_candidates_after_strict_function_filter";
        }
        return result;
    }

    public static String normalizeCatalogFunction(String raw) {
        String s = safe(raw).toLowerCase(Locale.ROOT);
        if (s.startsWith("residential")) return "residential";
        if ("watchtower".equals(s) || "fortification".equals(s)) return "military";
        if ("market".equals(s)) return "market";
        if ("commercial".equals(s) || "warehouse".equals(s) || "workshop".equals(s)) return "commercial";
        if ("civic_center".equals(s)) return "civic_center";
        if ("religious".equals(s)) return "religious";
        if ("landmark".equals(s)) return "landmark";
        if ("port".equals(s) || "fishing".equals(s) || "farm".equals(s)) return s;
        return s.isBlank() ? "" : s;
    }

    private static Candidate toCandidate(CityC35CatalogIO.CatalogStructure structure) {
        Candidate candidate = new Candidate();
        candidate.structure_id = structure.structure_id;
        candidate.size_tier = structure.size_tier;
        candidate.piece_role = structure.piece_role;
        candidate.preset_pool = structure.preset_pool;
        if (structure.function_candidates != null) candidate.function_candidates.addAll(structure.function_candidates);
        candidate.function_source = structure.function_definition != null ? structure.function_definition.source : null;
        candidate.tag_source_rule = structure.tag_source != null ? structure.tag_source.preset_rule : null;
        candidate.has_connectors = hasConnectors(structure);
        return candidate;
    }

    private static boolean matchesSizeTier(String requested, String actual) {
        if (requested == null || requested.isBlank()) return true;
        return safe(requested).equalsIgnoreCase(safe(actual));
    }

    private static boolean matchesFunction(
            CityC35CatalogIO.CatalogStructure structure,
            Set<String> targetFunctions,
            boolean strictTagSource
    ) {
        if (structure == null || structure.function_candidates == null || structure.function_candidates.isEmpty()) return false;
        if (strictTagSource && !isTrustedSource(structure)) return false;
        for (CityC35CatalogIO.FunctionCandidate candidate : structure.function_candidates) {
            if (candidate == null) continue;
            if (targetFunctions.contains(normalizeCatalogFunction(candidate.function))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTrustedSource(CityC35CatalogIO.CatalogStructure structure) {
        if (structure == null) return false;
        String functionSource = structure.function_definition != null ? safe(structure.function_definition.source) : "";
        String tagRule = structure.tag_source != null ? safe(structure.tag_source.preset_rule) : "";
        boolean manualOverride = structure.tag_source != null && structure.tag_source.manual_override;
        boolean scannerSource = structure.tag_source != null && structure.tag_source.scanner;
        if (manualOverride || scannerSource) return true;
        return !HEURISTIC_SOURCE.equalsIgnoreCase(functionSource) && !HEURISTIC_SOURCE.equalsIgnoreCase(tagRule);
    }

    private static boolean hasConnectors(CityC35CatalogIO.CatalogStructure structure) {
        return structure != null
                && structure.connectors != null
                && !structure.connectors.isEmpty();
    }

    private static Set<String> targetFunctions(String functionTag) {
        String normalized = normalizeCatalogFunction(functionTag);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        switch (normalized) {
            case "market", "commercial" -> {
                out.add("market");
                out.add("commercial");
            }
            case "port" -> out.add("port");
            case "farm" -> out.add("farm");
            case "military" -> out.add("military");
            case "civic_center" -> out.add("civic_center");
            case "religious" -> out.add("religious");
            case "residential" -> out.add("residential");
            default -> {
            }
        }
        return out;
    }

    private static String safe(String raw) {
        return raw == null ? "" : raw;
    }
}
