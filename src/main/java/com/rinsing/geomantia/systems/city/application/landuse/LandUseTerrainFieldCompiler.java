package com.rinsing.geomantia.systems.city.application.landuse;

import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.CityLandformReviewPackage;
import com.rinsing.geomantia.systems.gis.domain.cell.AtlasCell;
import com.rinsing.geomantia.systems.gis.domain.cell.CellStateFlag;
import com.rinsing.geomantia.systems.gis.domain.region.AtlasRegion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class LandUseTerrainFieldCompiler {
    public LandUseTerrainField compile(CityLandformReviewPackage reviewPackage, List<AtlasRegion> regions) {
        Objects.requireNonNull(reviewPackage, "reviewPackage");
        return compile(reviewPackage.cityId(), new BlockBounds(reviewPackage.grid().blockMinX(),
                reviewPackage.grid().blockMinZ(), reviewPackage.grid().blockMaxX() - 1,
                reviewPackage.grid().blockMaxZ() - 1), regions);
    }

    public LandUseTerrainField compile(String cityId, BlockBounds planningBounds, List<AtlasRegion> regions) {
        if (cityId == null || cityId.isBlank()) throw new IllegalArgumentException("cityId is required");
        Objects.requireNonNull(planningBounds, "planningBounds");
        if (regions == null || regions.isEmpty()) {
            throw new IllegalArgumentException("At least one D3 AtlasRegion is required");
        }
        List<AtlasRegion> normalized = regions.stream().filter(Objects::nonNull)
                .sorted(Comparator.comparing(AtlasRegion::regionId)).toList();
        if (normalized.isEmpty()) throw new IllegalArgumentException("At least one D3 AtlasRegion is required");
        int step = normalized.get(0).cellStepBlocks();
        if (normalized.stream().anyMatch(region -> region.cellStepBlocks() != step)) {
            throw new IllegalArgumentException("D3 LandUse terrain field requires one cellStepBlocks value");
        }

        Map<CellKey, LandUseTerrainField.Cell> cells = new LinkedHashMap<>();
        for (AtlasRegion region : normalized) {
            for (AtlasCell cell : region.cells()) {
                BlockBounds cellBounds = new BlockBounds(cell.blockMinX(), cell.blockMinZ(),
                        cell.blockMinX() + step - 1, cell.blockMinZ() + step - 1);
                if (!planningBounds.overlaps(cellBounds)) continue;
                LandUseTerrainField.Cell compiled = new LandUseTerrainField.Cell(
                        cell.globalCellX(), cell.globalCellZ(), cell.blockMinX(), cell.blockMinZ(), step,
                        cell.elevation(), cell.slope(), cell.localRelief(), cell.roughness(), cell.isWater(),
                        cell.waterDepth(), cell.waterDistance(), cell.biomeId(), cell.landformType().contractName(),
                        cell.patchId(), cell.hasFlag(CellStateFlag.SAMPLED));
                cells.putIfAbsent(new CellKey(cell.globalCellX(), cell.globalCellZ()), compiled);
            }
        }
        return new LandUseTerrainField(LandUseTerrainField.SCHEMA, cityId, planningBounds, step,
                new ArrayList<>(cells.values()));
    }

    private record CellKey(int x, int z) {
    }
}
