package com.rinsing.geomantia.systems.city.application;

import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import java.util.*;

/** A group may itself organize children while remaining a member of one outer composition. */
final class CityCompositionHierarchy {
    private CityCompositionHierarchy() { }
    static List<CityBlueprint.ArrayComposition> ordered(List<CityBlueprint.ArrayComposition> values) {
        Map<String, CityBlueprint.ArrayComposition> byCenter = new LinkedHashMap<>();
        Map<String, String> parents = new HashMap<>();
        for (var value : values) {
            if (byCenter.putIfAbsent(value.centerGroupId(), value) != null)
                throw new IllegalArgumentException("一个中心组只能定义一个子阵列组合；请合并重复中心的成员，保留建筑。");
            for (String member : value.memberGroupIds()) if (parents.putIfAbsent(member, value.centerGroupId()) != null)
                throw new IllegalArgumentException("子阵列 " + member + " 只能属于一个父组合；它可以另外组织自己的子阵列。");
        }
        List<CityBlueprint.ArrayComposition> ordered = new ArrayList<>();
        Set<String> done = new HashSet<>(), visiting = new HashSet<>();
        for (String center : byCenter.keySet()) visit(center, byCenter, parents, done, visiting, ordered);
        return List.copyOf(ordered);
    }
    private static void visit(String center, Map<String, CityBlueprint.ArrayComposition> values,
                              Map<String, String> parents, Set<String> done, Set<String> visiting,
                              List<CityBlueprint.ArrayComposition> result) {
        if (done.contains(center)) return;
        if (!visiting.add(center)) throw new IllegalArgumentException("嵌套组合存在循环，请移除循环成员关系；不要删除建筑组。");
        String parent = parents.get(center);
        if (parent != null && values.containsKey(parent)) visit(parent, values, parents, done, visiting, result);
        visiting.remove(center);done.add(center);result.add(values.get(center));
    }

    static Map<String, CityGroupSpatialDemand> demands(List<CityBlueprint.ArrayComposition> compositions,
                                                      Map<String, CityGroupSpatialDemand> leaves, int gap) {
        Map<String, CityGroupSpatialDemand> result = new LinkedHashMap<>(leaves);
        List<CityBlueprint.ArrayComposition> order = new ArrayList<>(ordered(compositions));
        Collections.reverse(order);
        for (var c : order) {
            CityGroupSpatialDemand own = leaves.get(c.centerGroupId());
            int span = own.formationSpanBlocks();
            for (String member : c.memberGroupIds()) span = Math.max(span, result.get(member).formationSpanBlocks());
            // Container envelope only; actual function-area/building targets remain leaf-owned.
            int side = Math.max(3, (int)Math.ceil(Math.sqrt(c.memberGroupIds().size() + 1)));
            if ((side & 1) == 0) side++;
            int container = Math.multiplyExact(span + gap, side);
            result.put(c.centerGroupId(), new CityGroupSpatialDemand(own.minimumAreaBlocks(),own.targetAreaBlocks(),
                    own.maximumAreaBlocks(),own.roadReserveAreaBlocks(),own.maximumTemplateSpanBlocks(),
                    container,container,container,"",own.plannedStructureCount(),own.templateFootprintAreaBlocks(),
                    own.internalStreetAreaBlocks()));
        }
        return Map.copyOf(result);
    }
}
