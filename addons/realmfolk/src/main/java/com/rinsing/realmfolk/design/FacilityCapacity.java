package com.rinsing.realmfolk.design;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/** 模板级床位与工位数，来自 Studio 导出的 realmfolk_facility_catalog.v1；只用于配置阶段估算容量。 */
public record FacilityCapacity(Map<String, Row> templates) {
    public record Row(int beds, int workSites) {}

    public static final FacilityCapacity EMPTY = new FacilityCapacity(Map.of());

    public FacilityCapacity {
        templates = Map.copyOf(templates);
    }

    public boolean known() {
        return !templates.isEmpty();
    }

    public Row of(String templateId) {
        return templates.getOrDefault(templateId == null ? "" : templateId, new Row(0, 0));
    }

    /** 目录缺失或格式不符时返回 EMPTY，不阻断既有配置流程。 */
    public static FacilityCapacity load(Path file) {
        if (file == null || !Files.isRegularFile(file)) return EMPTY;
        try {
            var json = JsonParser.parseReader(Files.newBufferedReader(file)).getAsJsonObject();
            if (!json.has("templates") || !json.get("templates").isJsonArray()) return EMPTY;
            Map<String, Row> rows = new TreeMap<>();
            for (var element : json.getAsJsonArray("templates")) {
                if (!element.isJsonObject()) continue;
                var template = element.getAsJsonObject();
                String ref = string(template, "templateRef");
                if (ref.isBlank()) continue;
                int beds = 0, workSites = 0;
                if (template.has("facilities") && template.get("facilities").isJsonArray()) {
                    for (var value : template.getAsJsonArray("facilities")) {
                        if (!value.isJsonObject()) continue;
                        String kind = string(value.getAsJsonObject(), "kind");
                        if ("bed".equals(kind)) beds++;
                        else if ("work".equals(kind)) workSites++;
                    }
                }
                rows.put(ref, new Row(beds, workSites));
            }
            return new FacilityCapacity(rows);
        } catch (IOException | RuntimeException e) {
            return EMPTY;
        }
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }
}
