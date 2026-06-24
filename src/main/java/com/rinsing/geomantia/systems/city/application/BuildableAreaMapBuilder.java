package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BuildableAreaMapBuilder {
    private static final int TEMPLATE_RESERVE_RADIUS_BLOCKS = 8;

    public BuildableAreaMap build(FunctionZoneMap zoneMap, BuildOperationPlan operationPlan) {
        if (zoneMap == null) {
            throw new IllegalArgumentException("zoneMap is required");
        }
        if (operationPlan == null) {
            throw new IllegalArgumentException("operationPlan is required");
        }

        PlanningGrid grid = zoneMap.grid();
        Map<CellKey, Reservation> reservations = reservations(grid, operationPlan);
        List<String> warnings = new ArrayList<>();
        List<BuildableAreaMap.ZoneBuildability> zones = new ArrayList<>();
        int originalCellCount = 0;
        int reservedCellCount = 0;
        int buildableCellCount = 0;

        for (FunctionZonePatch zone : zoneMap.zones()) {
            Set<CellKey> zoneCells = zoneCells(grid, zone);
            if (zoneCells.isEmpty()) {
                warnings.add("Buildable area for " + zone.zonePatchId() + " has no zone cells.");
            }
            List<BuildableAreaMap.ReservedCell> reserved = new ArrayList<>();
            List<BuildableAreaMap.BuildableCell> buildable = new ArrayList<>();
            for (CellKey cell : sorted(zoneCells)) {
                Reservation reservation = reservations.get(cell);
                if (reservation == null) {
                    buildable.add(new BuildableAreaMap.BuildableCell(
                            cell.x(), cell.z(), grid.cellToBlockX(cell.x()), grid.cellToBlockZ(cell.z())));
                    continue;
                }
                reserved.add(new BuildableAreaMap.ReservedCell(
                        cell.x(), cell.z(), grid.cellToBlockX(cell.x()), grid.cellToBlockZ(cell.z()),
                        new ArrayList<>(reservation.refs()), new ArrayList<>(reservation.types())));
            }
            int cellArea = grid.cellStepBlocks() * grid.cellStepBlocks();
            zones.add(new BuildableAreaMap.ZoneBuildability(
                    zone.zonePatchId(),
                    zone.functionType(),
                    zoneCells.size(),
                    reserved.size(),
                    buildable.size(),
                    zoneCells.size() * cellArea,
                    reserved.size() * cellArea,
                    buildable.size() * cellArea,
                    reserved,
                    buildable));
            originalCellCount += zoneCells.size();
            reservedCellCount += reserved.size();
            buildableCellCount += buildable.size();
        }

        JsonObject metrics = new JsonObject();
        metrics.addProperty("zoneCount", zones.size());
        metrics.addProperty("originalCellCount", originalCellCount);
        metrics.addProperty("reservedCellCount", reservedCellCount);
        metrics.addProperty("buildableCellCount", buildableCellCount);
        metrics.addProperty("reservationSource", "build_operation_plan.v0.1");
        int score = Math.max(0, 100 - warnings.size() * 5);
        CityQualityReport quality = new CityQualityReport(true, score, List.of(), warnings, List.of(), metrics);
        return new BuildableAreaMap(
                BuildableAreaMap.CURRENT_SCHEMA_VERSION,
                zoneMap.cityId(),
                grid,
                zones,
                quality);
    }

    private Map<CellKey, Reservation> reservations(PlanningGrid grid, BuildOperationPlan plan) {
        Map<CellKey, Reservation> result = new LinkedHashMap<>();
        for (BuildOperationPlan.Operation operation : plan.operations()) {
            Set<CellKey> cells = operationCells(grid, operation);
            for (CellKey cell : cells) {
                result.computeIfAbsent(cell, ignored -> new Reservation())
                        .add(operation.operationId(), operation.operationType());
            }
        }
        return result;
    }

    private Set<CellKey> operationCells(PlanningGrid grid, BuildOperationPlan.Operation operation) {
        if (operation.operationType().equals("pasteTemplate")) {
            int radius = Math.max(TEMPLATE_RESERVE_RADIUS_BLOCKS, operation.widthBlocks() / 2);
            return diskCells(grid, operation.anchorBlock(), radius);
        }
        if (operation.polyline().size() < 2) {
            return Set.of();
        }
        Set<CellKey> cells = new LinkedHashSet<>();
        int radius = Math.max(0, operation.widthBlocks() / 2);
        for (int i = 1; i < operation.polyline().size(); i++) {
            BlockPoint a = operation.polyline().get(i - 1);
            BlockPoint b = operation.polyline().get(i);
            int steps = Math.max(Math.abs(b.x() - a.x()), Math.abs(b.z() - a.z()));
            steps = Math.max(1, steps);
            for (int s = 0; s <= steps; s++) {
                int x = a.x() + Math.round((b.x() - a.x()) * (s / (float) steps));
                int z = a.z() + Math.round((b.z() - a.z()) * (s / (float) steps));
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (dx * dx + dz * dz > radius * radius + radius) {
                            continue;
                        }
                        addCell(grid, cells, x + dx, z + dz);
                    }
                }
            }
        }
        return cells;
    }

    private Set<CellKey> diskCells(PlanningGrid grid, BlockPoint center, int radius) {
        Set<CellKey> cells = new LinkedHashSet<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius + radius) {
                    continue;
                }
                addCell(grid, cells, center.x() + dx, center.z() + dz);
            }
        }
        return cells;
    }

    private void addCell(PlanningGrid grid, Set<CellKey> cells, int blockX, int blockZ) {
        if (!grid.containsBlock(blockX, blockZ)) {
            return;
        }
        cells.add(cellForBlock(grid, blockX, blockZ));
    }

    private Set<CellKey> zoneCells(PlanningGrid grid, FunctionZonePatch zone) {
        Set<CellKey> cells = new LinkedHashSet<>();
        if (!zone.memberCells().isEmpty()) {
            for (PatchMemberCell member : zone.memberCells()) {
                addCell(grid, cells, member.blockMinX(), member.blockMinZ());
            }
            return cells;
        }
        CellKey min = cellForBlock(grid, zone.cellShape().minX(), zone.cellShape().minZ());
        CellKey max = cellForBlock(grid, zone.cellShape().maxX(), zone.cellShape().maxZ());
        for (int x = Math.min(min.x(), max.x()); x <= Math.max(min.x(), max.x()); x++) {
            for (int z = Math.min(min.z(), max.z()); z <= Math.max(min.z(), max.z()); z++) {
                cells.add(new CellKey(x, z));
            }
        }
        return cells;
    }

    private CellKey cellForBlock(PlanningGrid grid, int blockX, int blockZ) {
        int x = Math.floorDiv(blockX - grid.originBlockX(), grid.cellStepBlocks());
        int z = Math.floorDiv(blockZ - grid.originBlockZ(), grid.cellStepBlocks());
        x = Math.max(0, Math.min(grid.cellsX() - 1, x));
        z = Math.max(0, Math.min(grid.cellsZ() - 1, z));
        return new CellKey(x, z);
    }

    private List<CellKey> sorted(Set<CellKey> cells) {
        List<CellKey> sorted = new ArrayList<>(cells);
        sorted.sort(Comparator.comparingInt(CellKey::z).thenComparingInt(CellKey::x));
        return sorted;
    }

    private record CellKey(int x, int z) {
    }

    private static final class Reservation {
        private final Set<String> refs = new LinkedHashSet<>();
        private final Set<String> types = new LinkedHashSet<>();

        private Reservation add(String ref, String type) {
            refs.add(ref);
            types.add(type);
            return this;
        }

        private Set<String> refs() {
            return refs;
        }

        private Set<String> types() {
            return types;
        }
    }
}
