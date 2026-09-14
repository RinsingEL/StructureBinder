package com.rinsing.geomantia.systems.city.application;

import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import java.util.*;

/** Functional hierarchy and geometric prerequisites are separate graphs. */
final class CityBlueprintDependencies {
    static final String CYCLE = "CITY_BLUEPRINT_DEPENDENCY_CYCLE";
    record Edge(String from, String to, String fieldPath, String source) { }
    record Conflict(String fieldPath, String message) { }
    private CityBlueprintDependencies() { }

    static List<Edge> geometry(List<CityBlueprint.Group> groups,
                               List<CityBlueprint.ArrayComposition> compositions) {
        List<Edge> edges = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            var group = groups.get(i); var placement = group.placementRelation();
            if (placement != null && placement.kind() == CityBlueprint.PlacementRelationKind.BETWEEN_GROUPS)
                for (String dependency : placement.groupRefs()) edges.add(new Edge(dependency, group.groupId(),
                        "$.groups[" + i + "].placementRelation.groupRefs", "BETWEEN_GROUPS"));
        }
        for (int i = 0; i < compositions.size(); i++) {
            var composition = compositions.get(i);
            for (String member : composition.memberGroupIds()) edges.add(new Edge(composition.centerGroupId(), member,
                    "$.arrayCompositions[" + i + "].memberGroupIds", composition.compositionId()));
        }
        return List.copyOf(edges);
    }

    static Optional<Conflict> conflict(CityBlueprint blueprint) {
        var geometric = cycle(geometry(blueprint.groups(), blueprint.arrayCompositions()));
        if (!geometric.isEmpty()) return Optional.of(describe(geometric, false));
        List<Edge> hierarchy = new ArrayList<>();
        for (int i = 0; i < blueprint.relations().size(); i++) {
            var relation = blueprint.relations().get(i);
            if (relation.relationKind() == CityBlueprint.RelationKind.HIERARCHY)
                hierarchy.add(new Edge(relation.fromGroupId(), relation.toGroupId(),
                        "$.relations[" + i + "]", "HIERARCHY"));
        }
        var functional = cycle(hierarchy);
        return functional.isEmpty() ? Optional.empty() : Optional.of(describe(functional, true));
    }

    private static Conflict describe(List<Edge> cycle, boolean functional) {
        String details = cycle.stream().map(edge -> edge.from() + " -> " + edge.to()
                + " (" + edge.source() + ", " + edge.fieldPath() + ")")
                .collect(java.util.stream.Collectors.joining("; "));
        return new Conflict(cycle.get(0).fieldPath(),
                (functional ? "功能层级自身成环：" : "几何落位依赖成环：") + details
                + (functional ? "。请调整环内一条 HIERARCHY 的方向或移除该关系；功能层级不决定几何编译顺序，不必修改嵌套。"
                : "。请修改环内 BETWEEN_GROUPS 的 groupRefs，或调整列出的组合成员/中心，解除互相等待；只改其中一条冲突依赖即可。HIERARCHY 不参与几何排序，修改它不能解除此几何环。")
                + "保留其他功能区、素材和数量，再提交 DRAFT；不是宿主故障。");
    }

    private static List<Edge> cycle(List<Edge> edges) {
        Map<String, List<Edge>> graph = new LinkedHashMap<>();
        for (Edge edge : edges) {
            graph.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge);
            graph.computeIfAbsent(edge.to(), ignored -> new ArrayList<>());
        }
        Map<String, Integer> state = new HashMap<>();
        List<Edge> path = new ArrayList<>();
        for (String node : graph.keySet()) {
            var found = visit(node, graph, state, path);
            if (!found.isEmpty()) return found;
        }
        return List.of();
    }

    private static List<Edge> visit(String node, Map<String, List<Edge>> graph,
                                     Map<String, Integer> state, List<Edge> path) {
        if (state.getOrDefault(node, 0) != 0) return List.of();
        state.put(node, 1);
        for (Edge edge : graph.get(node)) {
            if (state.getOrDefault(edge.to(), 0) == 1) {
                int start = 0;
                while (start < path.size() && !path.get(start).from().equals(edge.to())) start++;
                var found = new ArrayList<>(path.subList(start, path.size())); found.add(edge); return found;
            }
            path.add(edge);
            var found = visit(edge.to(), graph, state, path);
            path.remove(path.size() - 1);
            if (!found.isEmpty()) return found;
        }
        state.put(node, 2); return List.of();
    }
}
