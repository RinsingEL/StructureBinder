package com.rinsing.geomantia.systems.city.domain.landuse;

import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record LandUseTerrainField(
        String schemaVersion,
        String cityId,
        BlockBounds planningBounds,
        int cellStepBlocks,
        List<Cell> cells) {

    public static final String CURRENT_SCHEMA_VERSION = "city_land_use_terrain_field.v0.1";

    public LandUseTerrainField {
        if (!CURRENT_SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported LandUse terrain field schema: " + schemaVersion);
        }
        if (cityId == null || cityId.isBlank()) throw new IllegalArgumentException("cityId is required");
        Objects.requireNonNull(planningBounds, "planningBounds");
        if (cellStepBlocks <= 0) throw new IllegalArgumentException("cellStepBlocks must be positive");
        cells = cells == null ? List.of() : cells.stream()
                .sorted(Comparator.comparingInt(Cell::cellZ).thenComparingInt(Cell::cellX))
                .toList();
    }

    public Optional<Cell> cellAt(int blockX, int blockZ) {
        return cells.stream().filter(cell -> cell.contains(blockX, blockZ)).findFirst();
    }

    public record Cell(
            int cellX,
            int cellZ,
            int blockMinX,
            int blockMinZ,
            int cellStepBlocks,
            double elevation,
            double slope,
            double localRelief,
            double roughness,
            boolean water,
            double waterDepth,
            double waterDistance,
            String biomeId,
            String landformType,
            String landformPatchId,
            boolean sampled) {

        public Cell {
            if (cellStepBlocks <= 0) throw new IllegalArgumentException("cellStepBlocks must be positive");
            biomeId = normalize(biomeId, "unknown");
            landformType = normalize(landformType, "unknown");
            landformPatchId = normalize(landformPatchId, "");
        }

        public boolean contains(int blockX, int blockZ) {
            return blockX >= blockMinX && blockX < blockMinX + cellStepBlocks
                    && blockZ >= blockMinZ && blockZ < blockMinZ + cellStepBlocks;
        }

        private static String normalize(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }
    }
}
