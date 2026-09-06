package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import java.util.*;

/** Traffic connectivity is established by generated roads, not building-to-building growth. */
final class CityTrafficConnectivity {
    private final Map<String, Set<String>> neighbors = new HashMap<>();

    CityTrafficConnectivity(JsonObject roadPlan) {
        addEdges(roadPlan, "connections", "sourceGroupId", "targetGroupId");
        addEdges(roadPlan, "bridgeConnections", "fromGroupId", "toGroupId");
    }

    private void addEdges(JsonObject roadPlan, String array, String source, String target) {
        if (!roadPlan.has(array)) return;
        for (JsonElement element : roadPlan.getAsJsonArray(array)) {
            JsonObject edge = element.getAsJsonObject();
            if (!edge.has("streetBandIds") || edge.getAsJsonArray("streetBandIds").isEmpty()) continue;
            if ("bridgeConnections".equals(array) && (!edge.has("status")
                    || !"PLANNED_BY_CITY".equals(edge.get("status").getAsString()))) continue;
            if (!edge.has(source) || !edge.has(target)) continue;
            String from = edge.get(source).getAsString();
            String to = edge.get(target).getAsString();
            neighbors.computeIfAbsent(from, ignored -> new HashSet<>()).add(to);
            neighbors.computeIfAbsent(to, ignored -> new HashSet<>()).add(from);
        }
    }

    boolean directlyConnected(String from, String to) {
        return neighbors.getOrDefault(from, Set.of()).contains(to);
    }

    boolean connected(Set<String> groups) {
        if (groups.isEmpty()) return false;
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(groups.iterator().next());
        while (!pending.isEmpty()) {
            String group = pending.removeFirst();
            if (!visited.add(group)) continue;
            for (String next : neighbors.getOrDefault(group, Set.of()))
                if (groups.contains(next) && !visited.contains(next)) pending.addLast(next);
        }
        return visited.containsAll(groups);
    }
}
