package com.rinsing.geomantia.systems.city.application.landuse;

import com.rinsing.geomantia.systems.city.algorithm.landuse.ContourBandSurfaceClassifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityLandUseContourBandFreezerTest {
    @Test
    void marksOnlyTwoCapsOnCoreRepairedOpenDiagonalWater() {
        Map<Cell, ContourBandSurfaceClassifier.BandRole> source = filledSquare(0, 0, 7, 7);
        for (int coordinate = 1; coordinate <= 6; coordinate++) {
            source.put(new Cell(coordinate, coordinate), ContourBandSurfaceClassifier.BandRole.CHANNEL_WATER);
            if (coordinate < 6) {
                source.put(new Cell(coordinate + 1, coordinate),
                        ContourBandSurfaceClassifier.BandRole.CHANNEL_WATER);
            }
        }

        Map<Cell, CityLandUseSurfacePrintPlan.BandRole> frozen = expand(
                CityLandUseSurfacePrintPlanner.freezeBandsWithEndCaps(spans(source)));
        Set<Cell> centerline = cellsWithRoles(frozen,
                CityLandUseSurfacePrintPlan.BandRole.CHANNEL_WATER,
                CityLandUseSurfacePrintPlan.BandRole.CHANNEL_END_CAP);

        assertEquals(1, componentCount(centerline));
        assertEquals(2, cellsWithRoles(frozen,
                CityLandUseSurfacePrintPlan.BandRole.CHANNEL_END_CAP).size());
        assertTrue(fourConnected(cellsWithRoles(frozen,
                CityLandUseSurfacePrintPlan.BandRole.CHANNEL_WATER)));
        source.forEach((cell, role) -> {
            CityLandUseSurfacePrintPlan.BandRole frozenRole = frozen.get(cell);
            if (frozenRole == CityLandUseSurfacePrintPlan.BandRole.CHANNEL_END_CAP) {
                assertEquals(ContourBandSurfaceClassifier.BandRole.CHANNEL_WATER, role);
            } else {
                assertEquals(CityLandUseSurfacePrintPlan.BandRole.valueOf(role.name()), frozenRole);
            }
        });
    }

    @Test
    void preservesCoreRepairedClosedDiagonalContourWithoutInventingCaps() {
        Map<Cell, ContourBandSurfaceClassifier.BandRole> source = filledSquare(0, 0, 6, 6);
        for (Cell cell : List.of(
                new Cell(3, 0), new Cell(4, 1), new Cell(5, 2), new Cell(6, 3),
                new Cell(5, 4), new Cell(4, 5), new Cell(3, 6), new Cell(2, 5),
                new Cell(1, 4), new Cell(0, 3), new Cell(1, 2), new Cell(2, 1),
                new Cell(2, 0), new Cell(4, 0), new Cell(5, 1), new Cell(6, 2),
                new Cell(6, 4), new Cell(5, 5), new Cell(4, 6), new Cell(2, 6),
                new Cell(1, 5), new Cell(0, 4), new Cell(0, 2), new Cell(1, 1))) {
            source.put(cell, ContourBandSurfaceClassifier.BandRole.CHANNEL_WATER);
        }

        Map<Cell, CityLandUseSurfacePrintPlan.BandRole> frozen = expand(
                CityLandUseSurfacePrintPlanner.freezeBandsWithEndCaps(spans(source)));
        Set<Cell> centerline = cellsWithRoles(frozen,
                CityLandUseSurfacePrintPlan.BandRole.CHANNEL_WATER,
                CityLandUseSurfacePrintPlan.BandRole.CHANNEL_END_CAP);

        assertEquals(1, componentCount(centerline));
        assertEquals(0, cellsWithRoles(frozen,
                CityLandUseSurfacePrintPlan.BandRole.CHANNEL_END_CAP).size());
        assertTrue(fourConnected(cellsWithRoles(frozen,
                CityLandUseSurfacePrintPlan.BandRole.CHANNEL_WATER)));
    }

    private static Map<Cell, ContourBandSurfaceClassifier.BandRole> filledSquare(
            int minX, int minZ, int maxX, int maxZ) {
        Map<Cell, ContourBandSurfaceClassifier.BandRole> result = new HashMap<>();
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                result.put(new Cell(x, z), ContourBandSurfaceClassifier.BandRole.CHANNEL_BEFORE_BANK);
            }
        }
        return result;
    }

    private static List<ContourBandSurfaceClassifier.BandSpan> spans(
            Map<Cell, ContourBandSurfaceClassifier.BandRole> roles) {
        List<Cell> cells = roles.keySet().stream()
                .sorted(java.util.Comparator.comparingInt(Cell::z).thenComparingInt(Cell::x)).toList();
        List<ContourBandSurfaceClassifier.BandSpan> result = new ArrayList<>();
        int index = 0;
        while (index < cells.size()) {
            Cell start = cells.get(index);
            ContourBandSurfaceClassifier.BandRole role = roles.get(start);
            int maxX = start.x();
            index++;
            while (index < cells.size()) {
                Cell next = cells.get(index);
                if (next.z() != start.z() || next.x() != maxX + 1 || roles.get(next) != role) break;
                maxX = next.x();
                index++;
            }
            result.add(new ContourBandSurfaceClassifier.BandSpan(start.z(), start.x(), maxX, role));
        }
        return result;
    }

    private static Map<Cell, CityLandUseSurfacePrintPlan.BandRole> expand(
            List<CityLandUseSurfacePrintPlan.BandSpan> spans) {
        Map<Cell, CityLandUseSurfacePrintPlan.BandRole> result = new HashMap<>();
        for (CityLandUseSurfacePrintPlan.BandSpan span : spans) {
            for (int x = span.minX(); x <= span.maxX(); x++) {
                result.put(new Cell(x, span.z()), span.role());
            }
        }
        return result;
    }

    private static Set<Cell> cellsWithRoles(
            Map<Cell, CityLandUseSurfacePrintPlan.BandRole> roles,
            CityLandUseSurfacePrintPlan.BandRole... accepted) {
        Set<CityLandUseSurfacePrintPlan.BandRole> acceptedRoles = Set.of(accepted);
        Set<Cell> result = new HashSet<>();
        roles.forEach((cell, role) -> {
            if (acceptedRoles.contains(role)) result.add(cell);
        });
        return result;
    }

    private static boolean fourConnected(Set<Cell> cells) {
        return cells.isEmpty() || componentCount(cells) == 1;
    }

    private static int componentCount(Set<Cell> cells) {
        Set<Cell> remaining = new HashSet<>(cells);
        int components = 0;
        while (!remaining.isEmpty()) {
            components++;
            ArrayDeque<Cell> queue = new ArrayDeque<>();
            Cell first = remaining.iterator().next();
            remaining.remove(first);
            queue.add(first);
            while (!queue.isEmpty()) {
                Cell cell = queue.removeFirst();
                for (Cell neighbor : List.of(
                        new Cell(cell.x() - 1, cell.z()), new Cell(cell.x() + 1, cell.z()),
                        new Cell(cell.x(), cell.z() - 1), new Cell(cell.x(), cell.z() + 1))) {
                    if (remaining.remove(neighbor)) queue.addLast(neighbor);
                }
            }
        }
        return components;
    }

    private record Cell(int x, int z) {
    }
}
