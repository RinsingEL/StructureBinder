package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.*;
import java.util.*;

/** A designer's view of a frozen context, not a second compiler input or a new truth source. */
final class CityPlanningDecisionView {
    private CityPlanningDecisionView() { }

    static JsonObject from(JsonObject source) {
        JsonObject view = source.deepCopy();
        view.addProperty("schema", "city_blueprint_decision_context.v0.1");
        JsonObject d3 = source.getAsJsonObject("d3ReviewPackage");
        if (d3 != null && d3.has("landformPatches")) {
            JsonObject terrain = d3.deepCopy();
            // D4 uses coordinates and map evidence; no ranked patch candidates or selection tools.
            Map<String, Double> areas = new TreeMap<>();
            for (JsonElement item : d3.getAsJsonArray("landformPatches")) {
                JsonObject region = item.getAsJsonObject();
                areas.merge(string(region, "landformType"), number(region, "areaBlocks"), Double::sum);
            }
            JsonObject areaByType = new JsonObject();
            areas.forEach(areaByType::addProperty);
            terrain.remove("landformPatches");
            terrain.remove("patchReviewEvidence");
            terrain.remove("candidateCoverage");
            terrain.remove("availablePatchCountsByType");
            terrain.add("landformAreaBlocksByType", areaByType);
            terrain.addProperty("geographicEvidence", "Use the D3 map, world-coordinate bounds and biome evidence to design root anchorBlock coordinates. Landform totals describe terrain, not selectable sites. Local detail remains in sourceD3Ref and may be read through the current artifact tools.");
            terrain.remove("aiPromptContext"); // Legacy D3 district instructions are not the Blueprint design contract.
            terrain.remove("debugRefs");
            view.add("d3ReviewPackage", terrain);
        }
        view.remove("patchReviewEvidence");
        // Designer guidance is a projection: never change frozen Context identity or saved drafts.
        if (view.has("scaleDesignTask")) {
            view.add("scaleDesignTask", scaleTask(view.getAsJsonObject("scaleDesignTask")));
        }
        if (view.has("designGuide")) {
            view.getAsJsonObject("designGuide").addProperty("recovery", "正常生成先形成各区有效初版，再看当前整城图复核；只对影响城市要求、整体性或规模的明确问题作必要修订，避免没有目标的反复重画。用户明确要求测试或修订时，可以复用同一 D3 多轮比较，以当前版本预览和宿主失败预算为准。使用 targetDistrictId 定向修改，保留其他区。最终依据实际预览保存。");
        }
        JsonObject snapshot = source.getAsJsonObject("catalogSnapshot");
        if (snapshot != null && snapshot.has("templateCatalog") && snapshot.has("referenceCatalog")) {
            JsonObject catalog = snapshot.deepCopy();
            view.add("materialCatalog", com.rinsing.geomantia.systems.city.application.CityMaterialCatalogBrowser.summary(snapshot));
            JsonObject semanticCatalog = catalog.getAsJsonObject("structureCatalog");
            if (semanticCatalog != null && semanticCatalog.has("semanticProfiles")) {
                for (JsonElement profile : semanticCatalog.getAsJsonArray("semanticProfiles")) {
                    // The frozen snapshot reference already carries provenance; this repeated URI is not a choice.
                    profile.getAsJsonObject().remove("sourceProfileRef");
                }
            }
            Map<String, JsonObject> templates = new HashMap<>();
            for (JsonElement entry : snapshot.getAsJsonObject("templateCatalog").getAsJsonArray("templates")) {
                JsonObject template = entry.getAsJsonObject();
                templates.put(string(template, "templateId"), template);
            }
            JsonObject references = snapshot.getAsJsonObject("referenceCatalog").deepCopy();
            JsonArray refs = new JsonArray();
            JsonObject geometry = new JsonObject();
            for (JsonElement entry : references.getAsJsonArray("structureRefs")) {
                JsonObject reference = entry.getAsJsonObject();
                String ref = string(reference, "structureRef");
                refs.add(ref);
                JsonArray sizes = new JsonArray();
                for (JsonElement candidate : reference.getAsJsonArray("templateCandidates")) {
                    JsonObject template = templates.get(string(candidate.getAsJsonObject(), "templateId"));
                    if (template == null) continue; // Import validation remains authoritative.
                    JsonObject size = template.getAsJsonObject("rawSize");
                    JsonArray dimensions = new JsonArray();
                    for (String key : List.of("width", "height", "depth")) dimensions.add(size.get(key));
                    dimensions.add(0); // Legacy slot; template safety padding has been retired.
                    sizes.add(dimensions);
                }
                geometry.add(ref, sizes);
            }
            references.add("structureRefs", refs);
            catalog.add("referenceCatalog", references);
            catalog.remove("templateCatalog");
            catalog.add("structureGeometry", geometry);
            catalog.addProperty("geometryAuthority", "Variant resolution, rotations, entrance coordinates and hashes "
                    + "are frozen in catalogSnapshotRef and owned by the compiler. structureGeometry rows contain "
                    + "[width,height,depth,clearanceBlocks] for each available variant, in blocks.");
            // Every authored function/style/role and every selectable pool/profile remains intact.
            view.add("catalogSnapshot", catalog);
        }
        return view;
    }

    static JsonObject scaleTask(JsonObject source) {
        JsonObject task = source.deepCopy();
        task.remove("suggestedInitialLeafArraysMin");
        task.remove("suggestedInitialLeafArraysMax");
        task.remove("counting");
        task.addProperty("designTask", "结合本城服务地位、功能需求、实际地形和素材体量组织城市；规模名称提供背景，不规定建筑或阵列数量。通过当前预览判断功能、地形关联、整体性和体量是否充分。");
        task.addProperty("placementResponsibility", "设计者用世界方块坐标确定根组或根组合的位置，嵌套成员由父布局组织；程序负责成员编译、边界/碰撞校验与实际保留反馈。");
        return task;
    }

    private static JsonObject pick(JsonObject source, String... keys) {
        JsonObject result = new JsonObject();
        for (String key : keys) if (source.has(key)) result.add(key, source.get(key).deepCopy());
        return result;
    }
    private static String string(JsonObject source, String key) {
        return source.has(key) ? source.get(key).getAsString() : "";
    }
    private static double number(JsonObject source, String key) {
        return source.has(key) ? source.get(key).getAsDouble() : 0;
    }
}
