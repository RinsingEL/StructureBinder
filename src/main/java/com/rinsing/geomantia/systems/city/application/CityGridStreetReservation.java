package com.rinsing.geomantia.systems.city.application;

import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Checks GRID street clearance before a structure candidate is committed. */
final class CityGridStreetReservation {
    private CityGridStreetReservation() {
    }

    static String rejectionReason(List<Placement> committed,
                                  Placement candidate,
                                  CityBlueprintGroupLayoutPlanner.Parameters parameters) {
        List<Placement> proposed = new ArrayList<>(committed.size() + 1);
        proposed.addAll(committed);
        proposed.add(candidate);

        int mainStreetClearance = parameters.streetBandWidthBlocks() + 2;
        String rowFailure = firstInsufficientGap(proposed, true, mainStreetClearance);
        if (!rowFailure.isBlank()) return "GRID_ROW_STREET_CLEARANCE_RESERVED:" + rowFailure;

        int laneClearance = Math.max(1, parameters.streetBandWidthBlocks() / 2) + 2;
        String columnFailure = firstInsufficientGap(proposed, false, laneClearance);
        return columnFailure.isBlank() ? "" : "GRID_COLUMN_STREET_CLEARANCE_RESERVED:" + columnFailure;
    }

    private static String firstInsufficientGap(List<Placement> placements,
                                               boolean rows,
                                               int required) {
        Map<Integer, List<BlockBounds>> grouped = new TreeMap<>();
        for (Placement placement : placements) {
            int key = rows ? placement.row() : placement.column();
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(placement.collision());
        }
        List<Map.Entry<Integer, List<BlockBounds>>> entries = new ArrayList<>(grouped.entrySet());
        entries.sort(Comparator.comparingInt(Map.Entry::getKey));
        for (int index = 0; index + 1 < entries.size(); index++) {
            Map.Entry<Integer, List<BlockBounds>> first = entries.get(index);
            Map.Entry<Integer, List<BlockBounds>> second = entries.get(index + 1);
            int firstMax = first.getValue().stream()
                    .mapToInt(bounds -> rows ? bounds.maxX() : bounds.maxZ()).max().orElseThrow();
            int secondMin = second.getValue().stream()
                    .mapToInt(bounds -> rows ? bounds.minX() : bounds.minZ()).min().orElseThrow();
            int available = Math.max(0, secondMin - firstMax - 1);
            if (available < required) {
                return "first=" + first.getKey() + ",second=" + second.getKey()
                        + ",available=" + available + ",required=" + required;
            }
        }
        return "";
    }

    record Placement(int row, int column, BlockBounds collision) {
    }
}
