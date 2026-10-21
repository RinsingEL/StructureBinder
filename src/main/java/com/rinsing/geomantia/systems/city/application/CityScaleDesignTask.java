package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import com.rinsing.geomantia.systems.city.domain.model.CityScale;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Same designer-facing task is used at prepare and submit; counts are advice, not a rejection gate. */
final class CityScaleDesignTask {
    private CityScaleDesignTask() { }
    static JsonObject describe(CityScale scale) {
        JsonObject task = new JsonObject();
        if (scale == null) return task;
        int[] range = switch (scale) {
            case HAMLET -> new int[]{1, 2}; case VILLAGE -> new int[]{2, 4};
            case TOWN -> new int[]{4, 7}; case CITY -> new int[]{8, 12};
            case LARGE_CITY -> new int[]{12, 18};
        };
        task.addProperty("scale", scale.contractName());
        task.addProperty("suggestedInitialLeafArraysMin", range[0]);
        task.addProperty("suggestedInitialLeafArraysMax", range[1]);
        task.addProperty("counting", "Count actual building groups once, not parent containers; several arrays may share one functional purpose. Automatic building expansion is disabled. The min/max describe an INITIAL suggestion, not a final upper limit or completion criterion. Exceed the suggested range when additional designed arrays serve the city; never use reaching it to reject refinement. Counts are advice, never an area or building-count gate.");
        task.addProperty("designTask", switch (scale) {
            case HAMLET -> "Design one small complete cluster and optional supporting array; nesting is optional.";
            case VILLAGE -> "Design a few related terrain-aware clusters with useful gaps; nesting is optional.";
            case TOWN -> "Design a recognizable center and surrounding depth; freely mix composed and independent arrays.";
            case CITY -> "Organize the main body using real child arrays, including CORE and its supporting main districts. A token small composition with the rest of the main body scattered does not fulfill this design task. Peripheral arrays may remain independent. Choose algorithms and functions yourself, not a prescribed district template.";
            case LARGE_CITY -> "Design multiple complete nested clusters with main and secondary spaces. Include CORE in a composition and use at least two non-empty compositions; these may be recursively connected. A tiny nested core with the rest left for procedural road-chain expansion does not fulfill the task. Do not stretch a village into a long road.";
        });
        task.addProperty("placementResponsibility", "Express relationships with existing arrays/compositions; exact spacing, terrain fit and road ports belong to the host. No side-by-side road-port forms. Freeze planned layouts before terrain filtering; preserve successful members and report skipped members, including completely empty arrays. The designer decides whether to revise or accept.");
        return task;
    }

    static JsonObject describe(CityScale scale, JsonObject seed) {
        JsonObject task = describe(scale);
        JsonObject responsibility = new JsonObject();
        for (String key : List.of("role", "serviceHierarchy", "coreFunctions", "positioning", "gameplayRequirements"))
            if (seed.has(key)) responsibility.add(key, seed.get(key).deepCopy());
        task.add("cityResponsibility", responsibility);
        // Free-text hierarchy is interpreted by the designer, not guessed by keyword matching.
        task.remove("suggestedInitialLeafArraysMin");
        task.remove("suggestedInitialLeafArraysMax");
        task.addProperty("designTask", "先结合 cityResponsibility 的 role、serviceHierarchy、coreFunctions 与定位展开职责，再结合真实地形安排子区。首都或全国唯一城市应充分承担全国服务，不能因 theoreticalScale=town 而压成几个大标签或小镇。普通地方聚落按实际职责设计，不盲目扩大。先工作空间，再公共与居住，最后核对人口。城市连续、留白有具体理由，不等于城市要小。");
        task.addProperty("counting", "物理规模仅为场地参考和初版起点；不提供脱离职责的阵列数量目标，不设建筑数、子区数、覆盖率门槛或上限。依据职责展开、主要用地去向与全城预览判断完成。");
        return task;
    }

    static String violation(CityBlueprint blueprint, CityScale scale) {
        if (scale != CityScale.CITY && scale != CityScale.LARGE_CITY) return "";
        Set<String> ids = blueprint.groups().stream().map(CityBlueprint.Group::groupId).collect(Collectors.toSet());
        List<CityBlueprint.ArrayComposition> meaningful = blueprint.arrayCompositions().stream()
                .filter(c -> ids.contains(c.centerGroupId()) && !c.memberGroupIds().isEmpty()
                        && c.memberGroupIds().stream().allMatch(id -> ids.contains(id) && !id.equals(c.centerGroupId())))
                .toList();
        String core = blueprint.groups().stream().filter(g -> g.priority() == CityBlueprint.GroupPriority.CORE)
                .map(CityBlueprint.Group::groupId).findFirst().orElse("");
        if (meaningful.stream().noneMatch(c -> c.centerGroupId().equals(core) || c.memberGroupIds().contains(core)))
            return "本城规模为 " + scale.contractName() + "，主体必须使用嵌套。请将现有 CORE 组 " + core
                    + " 与配套组纳入一个 arrayCompositions 组合，保留原有素材和用途；无需重写整城，也不要只在外围增加一个无关嵌套。底层阵列数量仅为建议。";
        if (scale == CityScale.LARGE_CITY && meaningful.size() < 2)
            return "大城市需要多个实际嵌套组团；当前只有 " + meaningful.size()
                    + " 个。请用现有子阵列形成第二个组合，或让某个子阵列继续组织其成员；不要求专用功能区类型或增加道路接口字段。";
        return "";
    }
}
