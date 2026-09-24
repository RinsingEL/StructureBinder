package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

/** Derived browsing meanings only. Authored semantics and placement authority stay in the frozen snapshot. */
public final class CityMaterialCatalogBrowser {
    private static final JsonObject TAXONOMY = loadTaxonomy();
    private static final Map<String, JsonObject> FUNCTIONS = index(TAXONOMY.getAsJsonArray("functions"), "id");
    private static final Map<String, JsonObject> ROLES = index(TAXONOMY.getAsJsonArray("roles"), "id");
    private static final Map<String, Set<String>> TERMS = termIndex();
    public static final String INSTRUCTION = "用 city_d4_materials 的 filters 联合筛选 roles、functionIds、styles、rawFunctionTerms。"
            + "先看核心/填充，再结合功能区用途、子功能数量与城市风格选材，也可一次组合多个条件。"
            + "facets 是应用全部条件后的完整匹配集计数，按 structureRef 去重，不受分页影响；切换条件时移除旧条件。"
            + "functions 的 parent 表示层级；子功能包含于父功能，父标签不能证明子功能。directCount 表示直接标注映射到该层的数量。"
            + "空子功能结果表示没有足够细的标签证据，可退回父层或原始标签查看，不能自行补标签。"
            + "角色、功能、风格相互独立，风格不限定使用种族；原始 terrainModes 由落地程序校验，分类和推荐情境不授予地形可行性。"
            + "limit=0 只取统计，默认20，最大100；用 nextOffset 翻页。确认时单独提交 structureRefs/fillPoolRefs，不带 filters/limit/offset。";

    private CityMaterialCatalogBrowser() { }

    public static JsonObject summary(JsonObject snapshot) {
        JsonObject request = new JsonObject(); request.addProperty("limit", 0);
        JsonObject result = browse(snapshot, request);
        result.remove("candidates");
        result.addProperty("instruction", INSTRUCTION);
        return result;
    }

    static JsonObject browse(JsonObject snapshot, JsonObject request) {
        JsonObject filters = new JsonObject();
        if (request.has("filters")) {
            if (!request.get("filters").isJsonObject()) throw invalid("filters must be an object.");
            filters = request.getAsJsonObject("filters");
        }
        for (String field : filters.keySet()) if (!Set.of("roles", "functionIds", "functionMode", "styles", "rawFunctionTerms").contains(field))
            throw invalid("Unknown filters field: " + field);
        Set<String> roles = strings(filters, "roles"), functions = strings(filters, "functionIds"),
                styles = strings(filters, "styles"), raw = strings(filters, "rawFunctionTerms");
        if (!ROLES.keySet().containsAll(roles)) throw invalid("Unknown role; use " + ROLES.keySet());
        if (!FUNCTIONS.keySet().containsAll(functions)) throw invalid("Unknown functionIds; use IDs from materialCatalog.facets.functions or materialResults.facets.functions.");
        String mode = optionalText(filters, "functionMode", "all");
        if (!Set.of("all", "any").contains(mode)) throw invalid("functionMode must be all or any.");
        String query = normalize(optionalText(request, "query", "")).trim();
        int limit = integer(request, "limit", 20, 0, 100), offset = integer(request, "offset", 0, 0, Integer.MAX_VALUE);
        List<Entry> entries = entries(snapshot);
        List<Entry> matched = entries.stream().filter(e ->
                (roles.isEmpty() || roles.contains(e.role())) &&
                (styles.isEmpty() || !Collections.disjoint(styles, e.styles())) &&
                e.terms().containsAll(raw) &&
                (functions.isEmpty() || (mode.equals("any") ? !Collections.disjoint(functions, e.expanded()) : e.expanded().containsAll(functions))) &&
                (query.isEmpty() || Arrays.stream(query.split("\\s+")).allMatch(e.search()::contains))).toList();
        JsonObject result = new JsonObject();
        result.addProperty("taxonomySchema", TAXONOMY.get("schema").getAsString());
        result.add("appliedFilters", filters.deepCopy()); result.addProperty("query", optionalText(request, "query", ""));
        result.addProperty("totalCount", entries.size()); result.addProperty("matchedCount", matched.size());
        result.add("facets", facets(matched));
        JsonArray candidates = new JsonArray();
        matched.stream().skip(offset).limit(limit).forEach(e -> candidates.add(e.candidate()));
        result.add("candidates", candidates); result.addProperty("offset", offset); result.addProperty("limit", limit);
        result.addProperty("returnedCount", candidates.size());
        boolean more = (long) offset + candidates.size() < matched.size();
        result.addProperty("hasMore", more);
        if (more && limit > 0) result.addProperty("nextOffset", offset + candidates.size());
        return result;
    }

    /** Explicit confirmation preserves the original unpaged selection behavior. */
    static JsonArray selected(JsonObject snapshot, Set<String> refs) {
        JsonArray result = new JsonArray();
        entries(snapshot).stream().filter(e -> refs.contains(e.ref())).forEach(e -> result.add(e.candidate()));
        return result;
    }

    private record Entry(String ref, JsonObject authored, Set<String> terms, Set<String> styles, String role,
                         Set<String> direct, Set<String> expanded, JsonArray paths, Set<String> unmapped, String search) {
        JsonObject candidate() {
            JsonObject result = new JsonObject(); result.addProperty("structureRef", ref);
            result.add("authoredMetadata", authored.deepCopy());
            JsonObject classification = new JsonObject(); classification.addProperty("role", role);
            classification.add("functionIds", jsonStrings(expanded)); classification.add("functionPaths", paths.deepCopy());
            classification.add("unmappedFunctionTerms", jsonStrings(unmapped));
            result.add("classification", classification); return result;
        }
    }

    private static List<Entry> entries(JsonObject snapshot) {
        Map<String, JsonObject> semantics = index(array(snapshot.getAsJsonObject("structureCatalog"), "semanticProfiles"), "semanticProfileId");
        Map<String, JsonObject> refs = index(array(snapshot.getAsJsonObject("referenceCatalog"), "structureRefs"), "structureRef");
        List<Entry> result = new ArrayList<>();
        for (String ref : new TreeSet<>(refs.keySet())) {
            JsonObject authored = semantics.getOrDefault(ref, refs.get(ref));
            Set<String> terms = strings(authored, "functionTerms"), styles = strings(authored, "styleTerms");
            Set<String> direct = new LinkedHashSet<>(), unmapped = new LinkedHashSet<>();
            for (String term : terms) {
                if (TERMS.containsKey(term)) direct.addAll(TERMS.get(term)); else unmapped.add(term);
            }
            // Exact reviewed asset IDs only; never infer uses from names, suffixes or incidental room descriptions.
            for (JsonElement element : TAXONOMY.getAsJsonArray("assetRules")) {
                JsonObject rule = element.getAsJsonObject();
                if (strings(rule, "ids").contains(ref) && terms.containsAll(strings(rule, "allTerms"))) direct.addAll(strings(rule, "functionIds"));
            }
            Set<String> expanded = new LinkedHashSet<>(); direct.forEach(id -> expanded.addAll(ancestors(id)));
            JsonArray paths = new JsonArray();
            for (String id : direct) {
                if (direct.stream().anyMatch(other -> !other.equals(id) && ancestors(other).contains(id))) continue;
                JsonObject path = new JsonObject(); path.addProperty("id", id);
                JsonArray labels = new JsonArray(); ancestors(id).forEach(parent -> labels.add(FUNCTIONS.get(parent).get("label")));
                path.add("labels", labels); paths.add(path);
            }
            String role = role(strings(authored, "planningRoleTerms"));
            result.add(new Entry(ref, authored, terms, styles, role, direct, expanded, paths, unmapped,
                    normalize(ref + " " + authored + " " + paths)));
        }
        return result;
    }

    private static JsonObject facets(List<Entry> entries) {
        JsonObject result = new JsonObject(); JsonArray functions = new JsonArray();
        for (var node : FUNCTIONS.values()) {
            String id = node.get("id").getAsString();
            long count = entries.stream().filter(e -> e.expanded().contains(id)).count();
            if (count == 0) continue;
            JsonObject item = new JsonObject();
            for (String key : List.of("id", "label", "parent")) item.add(key, node.get(key).deepCopy());
            item.addProperty("count", count);
            item.addProperty("directCount", entries.stream().filter(e -> e.direct().contains(id)).count()); functions.add(item);
        }
        result.add("functions", functions);
        Map<String, Integer> roles = new TreeMap<>(), styles = new TreeMap<>(), terms = new TreeMap<>();
        for (Entry entry : entries) {
            roles.merge(entry.role(), 1, Integer::sum);
            entry.styles().forEach(style -> styles.merge(style, 1, Integer::sum));
            entry.terms().forEach(term -> terms.merge(term, 1, Integer::sum));
        }
        JsonArray roleFacets = new JsonArray();
        roles.forEach((id, count) -> { JsonObject item = new JsonObject(); item.addProperty("id", id);
            item.add("label", ROLES.get(id).get("label")); item.addProperty("count", count); roleFacets.add(item); });
        result.add("roles", roleFacets); result.add("styles", termCounts(styles, false));
        result.add("rawFunctionTerms", termCounts(terms, true));
        result.addProperty("unclassifiedCount", entries.stream().filter(e -> e.expanded().isEmpty()).count());
        return result;
    }

    private static JsonArray termCounts(Map<String, Integer> counts, boolean function) {
        JsonArray result = new JsonArray();
        counts.forEach((term, count) -> { JsonObject item = new JsonObject(); item.addProperty("term", term);
            item.addProperty("count", count); if (function) item.addProperty("mapped", TERMS.containsKey(term)); result.add(item); });
        return result;
    }

    private static String role(Set<String> terms) {
        // A protected role must not become repeatable fill when an old profile carries both tags.
        for (String id : List.of("core", "self_contained", "structure", "fill"))
            if (!Collections.disjoint(strings(ROLES.get(id), "values"), terms)) return id;
        return "unknown";
    }

    private static List<String> ancestors(String id) {
        LinkedList<String> result = new LinkedList<>();
        for (String current = id; !current.isEmpty(); current = FUNCTIONS.get(current).get("parent").getAsString()) {
            if (!FUNCTIONS.containsKey(current) || result.contains(current)) throw new IllegalStateException("Invalid function tree: " + current);
            result.addFirst(current);
        }
        return result;
    }
    private static Map<String, Set<String>> termIndex() {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        FUNCTIONS.forEach((id, node) -> { ancestors(id); strings(node, "terms").forEach(term ->
                result.computeIfAbsent(term, ignored -> new LinkedHashSet<>()).add(id)); });
        return result;
    }
    private static JsonObject loadTaxonomy() {
        try (InputStream in = CityMaterialCatalogBrowser.class.getResourceAsStream("/geomantia/catalog/structure_functions.json")) {
            if (in == null) throw new IOException("Missing structure function taxonomy");
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    private static Map<String, JsonObject> index(JsonArray rows, String key) {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        for (JsonElement row : rows) { JsonObject item = row.getAsJsonObject();
            if (result.putIfAbsent(item.get(key).getAsString(), item) != null) throw new IllegalStateException("Duplicate catalog ID: " + item.get(key)); }
        return result;
    }
    private static Set<String> strings(JsonObject object, String key) {
        if (!object.has(key)) return Set.of();
        if (!object.get(key).isJsonArray()) throw invalid(key + " must be an array of strings.");
        Set<String> result = new LinkedHashSet<>();
        for (JsonElement item : object.getAsJsonArray(key)) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString() || item.getAsString().isBlank()) throw invalid(key + " must contain non-empty strings.");
            result.add(item.getAsString());
        }
        return result;
    }
    private static String optionalText(JsonObject object, String key, String fallback) {
        if (!object.has(key)) return fallback;
        JsonElement value = object.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid(key + " must be a string.");
        return value.getAsString();
    }
    private static int integer(JsonObject object, String key, int fallback, int min, int max) {
        if (!object.has(key)) return fallback;
        try {
            JsonElement value = object.get(key);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
            int number = value.getAsBigDecimal().intValueExact();
            if (number < min || number > max) throw new ArithmeticException();
            return number;
        } catch (ArithmeticException | NumberFormatException failure) { throw invalid(key + " must be an integer in " + min + ".." + max); }
    }
    private static JsonArray array(JsonObject object, String key) { return CityDesignSession.array(object, key); }
    private static JsonArray jsonStrings(Collection<String> values) { JsonArray result = new JsonArray(); values.forEach(result::add); return result; }
    private static String normalize(String value) { return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT); }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException("CITY_DESIGN_SESSION_INVALID: " + message); }
}
