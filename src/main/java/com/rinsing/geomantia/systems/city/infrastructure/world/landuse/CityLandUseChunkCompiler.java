package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan;
import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlanCodec;
import com.rinsing.geomantia.systems.city.application.landuse.LandUseAreaPlanCodec;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandscapeFillProgram;
import com.rinsing.geomantia.systems.city.domain.landuse.SurfacePolicy;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure block-to-owner compiler. It never samples terrain and never writes a level. */
public final class CityLandUseChunkCompiler {
    public static final String RESULT_SCHEMA = "city_land_use_chunk_fragment";
    public static final String MICRO_FILL_SUBGRADE_KEY = "MICRO_FILL_SUBGRADE";

    private final MaterialPalette palette;
    private final LandUseAreaPlanCodec codec = new LandUseAreaPlanCodec();
    private final CityLandUseSurfacePrintPlanCodec surfacePrintCodec =
            new CityLandUseSurfacePrintPlanCodec();

    public CityLandUseChunkCompiler() {
        this(MaterialPalette.defaults());
    }

    public CityLandUseChunkCompiler(MaterialPalette palette) {
        this.palette = Objects.requireNonNull(palette, "palette");
    }

    public ChunkFragment compile(LandUseAreaPlan plan,
                                 CityLandUseSurfacePrintPlan surfacePrintPlan,
                                 int chunkX,
                                 int chunkZ) {
        return compilePrepared(prepare(plan, surfacePrintPlan), chunkX, chunkZ);
    }

    public PreparedSurfacePlan prepare(LandUseAreaPlan plan,
                                       CityLandUseSurfacePrintPlan surfacePrintPlan) {
        checkCancelled();
        validateLandUsePlan(plan);
        Map<AreaKey, CityLandUseSurfacePrintPlan.AreaPrint> printAreas =
                validateSurfacePrintPlan(plan, surfacePrintPlan);
        Map<OwnerChunk, Map<AreaKey, CityLandUseSurfacePrintPlan.AreaPrint>> byOwner = new HashMap<>();
        for (Map.Entry<AreaKey, CityLandUseSurfacePrintPlan.AreaPrint> entry : printAreas.entrySet()) {
            Set<OwnerChunk> owners = new HashSet<>();
            for (LandUseAreaPlan.ScanlineSpan span : entry.getValue().memberSpans()) {
                int chunkZ = Math.floorDiv(span.z(), 16);
                int minChunkX = Math.floorDiv(span.minX(), 16);
                int maxChunkX = Math.floorDiv(span.maxX(), 16);
                for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                    owners.add(new OwnerChunk(chunkX, chunkZ));
                }
            }
            for (OwnerChunk owner : owners) {
                byOwner.computeIfAbsent(owner, ignored -> new HashMap<>()).put(entry.getKey(), entry.getValue());
            }
        }
        Map<OwnerChunk, Map<AreaKey, CityLandUseSurfacePrintPlan.AreaPrint>> frozen = new HashMap<>();
        byOwner.forEach((owner, areas) -> frozen.put(owner, Map.copyOf(areas)));
        Map<OwnerChunk, List<CityLandUseSurfacePrintPlan.FeatureCell>> featuresByOwner = new HashMap<>();
        for (CityLandUseSurfacePrintPlan.FeatureCell cell : surfacePrintPlan.featureCells()) {
            OwnerChunk owner = new OwnerChunk(Math.floorDiv(cell.x(), 16), Math.floorDiv(cell.z(), 16));
            featuresByOwner.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(cell);
        }
        Map<OwnerChunk, List<CityLandUseSurfacePrintPlan.FeatureCell>> frozenFeatures = new HashMap<>();
        featuresByOwner.forEach((owner, cells) -> frozenFeatures.put(owner, cells.stream()
                .sorted(Comparator.comparingInt(CityLandUseSurfacePrintPlan.FeatureCell::z)
                        .thenComparingInt(CityLandUseSurfacePrintPlan.FeatureCell::x)
                        .thenComparingInt(CityLandUseSurfacePrintPlan.FeatureCell::surfaceOffset))
                .toList()));
        return new PreparedSurfacePlan(plan, surfacePrintPlan, Map.copyOf(frozen),
                Map.copyOf(frozenFeatures));
    }

    public ChunkFragment compilePrepared(PreparedSurfacePlan prepared, int chunkX, int chunkZ) {
        checkCancelled();
        Objects.requireNonNull(prepared, "prepared");
        OwnerChunk owner = new OwnerChunk(chunkX, chunkZ);
        int halo = CityLandUseMicroGrader.MASK_HALO_BLOCKS;
        int minX = chunkX * 16 - halo;
        int maxX = chunkX * 16 + 15 + halo;
        int minZ = chunkZ * 16 - halo;
        int maxZ = chunkZ * 16 + 15 + halo;
        int ownerRadius = Math.max(1, Math.floorDiv(halo + 15, 16));
        List<CityLandUseSurfacePrintPlan.FeatureCell> gradingFeatures = new ArrayList<>();
        for (int dz = -ownerRadius; dz <= ownerRadius; dz++) {
            for (int dx = -ownerRadius; dx <= ownerRadius; dx++) {
                prepared.featureCellsByOwner().getOrDefault(
                                new OwnerChunk(chunkX + dx, chunkZ + dz), List.of()).stream()
                        .filter(cell -> cell.x() >= minX && cell.x() <= maxX
                                && cell.z() >= minZ && cell.z() <= maxZ)
                        .forEach(gradingFeatures::add);
            }
        }
        return compileInternal(prepared.areaPlan(),
                prepared.printAreasByOwner().getOrDefault(owner, Map.of()),
                prepared.surfacePrintPlan().sharedBoundarySpans(),
                prepared.featureCellsByOwner().getOrDefault(owner, List.of()),
                gradingFeatures,
                chunkX, chunkZ);
    }

    private ChunkFragment compileInternal(
            LandUseAreaPlan plan,
            Map<AreaKey, CityLandUseSurfacePrintPlan.AreaPrint> printAreas,
            List<CityLandUseSurfacePrintPlan.SharedBoundaryPrintSpan> sharedBoundarySpans,
            List<CityLandUseSurfacePrintPlan.FeatureCell> featureCells,
            List<CityLandUseSurfacePrintPlan.FeatureCell> gradingFeatureCells,
            int chunkX,
            int chunkZ) {
        Objects.requireNonNull(plan, "plan");
        int minChunkX = chunkX * 16;
        int minChunkZ = chunkZ * 16;
        int maxChunkX = minChunkX + 15;
        int maxChunkZ = minChunkZ + 15;
        int halo = CityLandUseMicroGrader.MASK_HALO_BLOCKS;
        Set<BlockCell> corridorExclusions = new HashSet<>();
        Set<BlockCell> gradingCorridorExclusions = new HashSet<>();
        for (LandUseAreaPlan.CorridorExclusion exclusion : plan.corridorExclusions()) {
            addBoundsClipped(corridorExclusions, exclusion.blockBounds(),
                    minChunkX, minChunkZ, maxChunkX, maxChunkZ);
            addBoundsClipped(gradingCorridorExclusions, exclusion.blockBounds(),
                    minChunkX - halo, minChunkZ - halo, maxChunkX + halo, maxChunkZ + halo);
        }
        Map<SurfaceCell, SurfaceOperation> surfaces = new HashMap<>();
        Map<BlockCell, BoundaryOperation> boundaries = new HashMap<>();
        Map<BlockCell, GradingMaskCell> gradingMask = new HashMap<>();
        List<PlatformPurposeAnchor> platformPurposeAnchors = new ArrayList<>();
        List<PlatformAccessDemand> platformAccessDemands = new ArrayList<>();
        String microFillBlock = palette.surfaceMaterial(MICRO_FILL_SUBGRADE_KEY);
        int relevantCellCount = 0;
        int footprintExcluded = 0;
        int corridorExcluded = 0;
        int gateExcluded = 0;

        List<LandUseAreaPlan.Area> stableAreas = new ArrayList<>(plan.areas());
        stableAreas.sort(Comparator.comparing(LandUseAreaPlan.Area::areaId));
        Map<BlockCell, String> areaOwners = new HashMap<>();
        for (LandUseAreaPlan.Area area : stableAreas) {
            for (LandUseAreaPlan.ScanlineSpan span : area.memberSpans()) {
                // Ownership is consulted only for shared contacts inside this owner.
                // A one-cell halo retains both sides of a cross-chunk shared boundary.
                if (span.z() < minChunkZ - 1 || span.z() > maxChunkZ + 1) continue;
                for (int x = Math.max(span.minX(), minChunkX - 1);
                     x <= Math.min(span.maxX(), maxChunkX + 1); x++) {
                    areaOwners.put(new BlockCell(x, span.z()), area.areaId());
                }
            }
        }
        Set<BlockCell> sharedContactCells = new HashSet<>();
        int[][] neighbors = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (CityLandUseSurfacePrintPlan.SharedBoundaryPrintSpan span : sharedBoundarySpans) {
            if (span.z() < minChunkZ - 1 || span.z() > maxChunkZ + 1) continue;
            for (int x = Math.max(span.minX(), minChunkX - 1);
                 x <= Math.min(span.maxX(), maxChunkX + 1); x++) {
                BlockCell writerCell = new BlockCell(x, span.z());
                sharedContactCells.add(writerCell);
                for (int[] direction : neighbors) {
                    BlockCell neighbor = new BlockCell(x + direction[0], span.z() + direction[1]);
                    if (span.neighborAreaId().equals(areaOwners.get(neighbor))) {
                        sharedContactCells.add(neighbor);
                    }
                }
            }
        }

        for (LandUseAreaPlan.Area area : stableAreas) {
            String areaId = area.areaId();
            String landUseType = area.landUseType();
            String boundaryPolicy = area.boundaryPolicy().name();
            CityLandUseSurfacePrintPlan.AreaPrint printArea = printAreas.get(AreaKey.from(area));
            Set<BlockCell> frozenSurfaceExclusions = printArea == null ? Set.of()
                    : cellsClipped(printArea.exclusionSpans(), minChunkX, minChunkZ, maxChunkX, maxChunkZ);
            String frozenBoundaryBlock = printArea == null ? "" : printArea.recipe().boundaryBlockId();
            String boundaryBlock = frozenBoundaryBlock.isBlank()
                    ? palette.boundaryMaterial(boundaryPolicy) : frozenBoundaryBlock;
            Set<BlockCell> footprints = new HashSet<>();
            area.structureFootprintExclusions().forEach(bounds -> addBoundsClipped(footprints, bounds,
                    minChunkX, minChunkZ, maxChunkX, maxChunkZ));
            Set<BlockCell> gradingFootprints = new HashSet<>();
            area.structureFootprintExclusions().forEach(bounds -> addBoundsClipped(gradingFootprints, bounds,
                    minChunkX - halo, minChunkZ - halo, maxChunkX + halo, maxChunkZ + halo));
            Set<BlockCell> gates = new HashSet<>();
            area.gateSlots().forEach(gate -> gates.add(cell(gate.block())));

            boolean microGradePave = printArea != null && SurfacePolicy.PAVE.name().equals(
                    printArea.surfaceSettings().compatibilityCategory());
            boolean foundationArea = area.sourceGroupIds().stream()
                    .anyMatch(groupId -> groupId.endsWith("::foundation"));
            if (foundationArea) {
                int footprintOrdinal = 0;
                for (BlockBounds bounds : area.structureFootprintExclusions()) {
                    if (intersects(bounds, minChunkX - halo, minChunkZ - halo,
                            maxChunkX + halo, maxChunkZ + halo)) {
                        platformPurposeAnchors.add(new PlatformPurposeAnchor(areaId,
                                areaId + "::building_footprint::" + footprintOrdinal,
                                PlatformPurpose.BUILDING, bounds));
                    }
                    footprintOrdinal++;
                }
                for (LandUseAreaPlan.GateSlot gate : area.gateSlots()) {
                    if (gate.block().x() >= minChunkX - halo && gate.block().x() <= maxChunkX + halo
                            && gate.block().z() >= minChunkZ - halo && gate.block().z() <= maxChunkZ + halo) {
                        platformAccessDemands.add(new PlatformAccessDemand(areaId, gate.gateId(),
                                gate.block(), gate.direction()));
                        if (gate.sourceAnchorId().isBlank()) {
                            platformPurposeAnchors.add(new PlatformPurposeAnchor(areaId,
                                    gate.gateId() + "::enterable_open_space",
                                    PlatformPurpose.ENTERABLE_OPEN_SPACE,
                                    new BlockBounds(gate.block().x(), gate.block().z(),
                                            gate.block().x(), gate.block().z())));
                        }
                    }
                }
            }
            if (microGradePave && microFillBlock != null) {
                for (LandUseAreaPlan.ScanlineSpan span : area.memberSpans()) {
                    int z = span.z();
                    if (z < minChunkZ - halo || z > maxChunkZ + halo
                            || span.maxX() < minChunkX - halo || span.minX() > maxChunkX + halo) {
                        continue;
                    }
                    for (int x = Math.max(span.minX(), minChunkX - halo);
                         x <= Math.min(span.maxX(), maxChunkX + halo); x++) {
                        BlockCell cell = new BlockCell(x, z);
                        if (!gradingFootprints.contains(cell)
                                && !gradingCorridorExclusions.contains(cell)
                                && !gates.contains(cell)) {
                            gradingMask.putIfAbsent(cell, new GradingMaskCell(areaId, x, z, foundationArea));
                        }
                    }
                }
            }

            for (LandUseAreaPlan.ScanlineSpan span : area.memberSpans()) {
                int z = span.z();
                int minX = span.minX();
                int maxX = span.maxX();
                if (z < minChunkZ || z > maxChunkZ || maxX < minChunkX || minX > maxChunkX) {
                    continue;
                }
                for (int x = Math.max(minX, minChunkX); x <= Math.min(maxX, maxChunkX); x++) {
                    BlockCell cell = new BlockCell(x, z);
                    relevantCellCount++;
                    if (footprints.contains(cell)) {
                        footprintExcluded++;
                    } else if (corridorExclusions.contains(cell)) {
                        corridorExcluded++;
                    } else if (gates.contains(cell)) {
                        gateExcluded++;
                    } else if (frozenSurfaceExclusions.contains(cell)) {
                        corridorExcluded++;
                    } else if (printArea != null) {
                        addPlannedSurfaceOperations(surfaces, printArea, area, cell);
                    }
                }
            }

            if (boundaryBlock == null) {
                continue;
            }
            for (LandUseAreaPlan.BoundaryLoop loop : area.boundaryLoops()) {
                for (BlockPoint point : loop.points()) {
                    BlockCell cell = cell(point);
                    if (!insideChunk(cell, minChunkX, minChunkZ, maxChunkX, maxChunkZ)) {
                        continue;
                    }
                    relevantCellCount++;
                    if (footprints.contains(cell)) {
                        footprintExcluded++;
                    } else if (corridorExclusions.contains(cell)) {
                        corridorExcluded++;
                    } else if (gates.contains(cell)) {
                        gateExcluded++;
                    } else if (!sharedContactCells.contains(cell) && !isContourChannel(printArea, cell)) {
                        boundaries.putIfAbsent(cell,
                                new BoundaryOperation(areaId, landUseType, cell.x(), cell.z(), boundaryBlock));
                    }
                }
            }
        }

        Map<String, LandUseAreaPlan.Area> areasById = new HashMap<>();
        stableAreas.forEach(area -> areasById.put(area.areaId(), area));
        for (CityLandUseSurfacePrintPlan.SharedBoundaryPrintSpan span : sharedBoundarySpans) {
            if (span.boundaryBlockId().isBlank() || span.z() < minChunkZ || span.z() > maxChunkZ) continue;
            LandUseAreaPlan.Area writer = areasById.get(span.writerAreaId());
            if (writer == null) continue;
            for (int x = Math.max(span.minX(), minChunkX); x <= Math.min(span.maxX(), maxChunkX); x++) {
                BlockCell cell = new BlockCell(x, span.z());
                boundaries.put(cell, new BoundaryOperation(writer.areaId(), writer.landUseType(), x,
                        span.z(), span.boundaryBlockId()));
            }
        }

        List<SurfaceOperation> surfaceOperations = new ArrayList<>(surfaces.values());
        surfaceOperations.sort(SurfaceOperation.STABLE_ORDER);
        List<BoundaryOperation> boundaryOperations = new ArrayList<>(boundaries.values());
        boundaryOperations.sort(BoundaryOperation.STABLE_ORDER);
        List<FeatureOperation> featureOperations = featureCells.stream()
                .map(cell -> new FeatureOperation(cell.sourceId(), cell.x(), cell.z(), cell.blockId(),
                        cell.surfaceOffset(), cell.kind(), cell.facing()))
                .sorted(FeatureOperation.STABLE_ORDER).toList();
        List<FeatureOperation> gradingFeatureOperations = gradingFeatureCells.stream()
                .map(cell -> new FeatureOperation(cell.sourceId(), cell.x(), cell.z(), cell.blockId(),
                        cell.surfaceOffset(), cell.kind(), cell.facing()))
                .sorted(FeatureOperation.STABLE_ORDER).toList();
        relevantCellCount += featureOperations.size();
        List<GradingMaskCell> gradingMaskCells = new ArrayList<>(gradingMask.values());
        gradingMaskCells.sort(GradingMaskCell.STABLE_ORDER);
        return new ChunkFragment(RESULT_SCHEMA, plan.cityId(), plan.planHash(), palette.paletteHash(), chunkX, chunkZ,
                relevantCellCount, footprintExcluded, corridorExcluded, gateExcluded,
                microFillBlock, List.copyOf(gradingMaskCells),
                List.copyOf(surfaceOperations), List.copyOf(boundaryOperations), featureOperations,
                gradingFeatureOperations, platformPurposeAnchors, platformAccessDemands);
    }

    private static boolean intersects(BlockBounds bounds, int minX, int minZ, int maxX, int maxZ) {
        return bounds.maxX() >= minX && bounds.minX() <= maxX
                && bounds.maxZ() >= minZ && bounds.minZ() <= maxZ;
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException("CITY_LAND_USE_COMPILATION_CANCELLED");
        }
    }

    private static boolean isContourChannel(CityLandUseSurfacePrintPlan.AreaPrint printArea,
                                            BlockCell cell) {
        if (printArea == null) return false;
        if (printArea.recipe() instanceof CityLandUseSurfacePrintPlan.ContourBandsRecipe contour) {
            CityLandUseSurfacePrintPlan.BandRole role = contour.roleAtOrNull(cell.x(), cell.z());
            return role != null && role != CityLandUseSurfacePrintPlan.BandRole.FIELD;
        }
        if (printArea.recipe() instanceof CityLandUseSurfacePrintPlan.RelayRegionGrowthRecipe relay) {
            CityLandUseSurfacePrintPlan.RegionSpan region = relay.regionAtOrNull(cell.x(), cell.z());
            if (region == null) return false;
            LandscapeFillProgram.MaterialRole role = relay.roleDefinitions().stream()
                    .filter(definition -> definition.roleRef().equals(region.roleRef()))
                    .map(CityLandUseSurfacePrintPlan.RelayRoleDefinition::materialRole).findFirst().orElseThrow();
            return role == LandscapeFillProgram.MaterialRole.BANK
                    || role == LandscapeFillProgram.MaterialRole.WATER;
        }
        return false;
    }

    private static void addPlannedSurfaceOperations(
            Map<SurfaceCell, SurfaceOperation> surfaces,
            CityLandUseSurfacePrintPlan.AreaPrint printArea,
            LandUseAreaPlan.Area area,
            BlockCell cell) {
        if (printArea.recipe() instanceof CityLandUseSurfacePrintPlan.UniformRecipe uniform) {
            addSurfaceOperation(surfaces, new SurfaceOperation(area.areaId(), area.landUseType(),
                    cell.x(), cell.z(), uniform.surfaceBlockId(), 0, false, SurfaceStage.BASE, 0));
            return;
        }
        if (printArea.recipe() instanceof CityLandUseSurfacePrintPlan.ContourBandsRecipe contour) {
            CityLandUseSurfacePrintPlan.BandRole role = contour.roleAt(cell.x(), cell.z());
            switch (role) {
                case FIELD -> {
                    addSurfaceOperation(surfaces, new SurfaceOperation(area.areaId(), area.landUseType(),
                            cell.x(), cell.z(), contour.surfaceBlockId(), 0, false, SurfaceStage.BASE, 0));
                    addSurfaceOperation(surfaces, new SurfaceOperation(area.areaId(), area.landUseType(),
                            cell.x(), cell.z(), contour.cropBlockId(), 1, true, SurfaceStage.CROP, 1));
                }
                case CHANNEL_BEFORE_BANK, CHANNEL_AFTER_BANK -> {
                    addSurfaceOperation(surfaces, new SurfaceOperation(area.areaId(), area.landUseType(),
                            cell.x(), cell.z(), contour.channelBankBlockId(), 0, false,
                            SurfaceStage.BASE, 0));
                    addSurfaceOperation(surfaces, new SurfaceOperation(area.areaId(), area.landUseType(),
                            cell.x(), cell.z(), contour.channelBankOverlayBlockId(), 1, true,
                            SurfaceStage.CHANNEL_OVERLAY, 1));
                }
                case CHANNEL_WATER -> addSurfaceOperation(surfaces,
                        new SurfaceOperation(area.areaId(), area.landUseType(), cell.x(), cell.z(),
                                contour.channelWaterBlockId(), 0, false, SurfaceStage.BASE, 0,
                                contour.channelBankBlockId()));
                case CHANNEL_END_CAP -> {
                    addSurfaceOperation(surfaces, new SurfaceOperation(area.areaId(), area.landUseType(),
                            cell.x(), cell.z(), contour.channelBankBlockId(), 0, false,
                            SurfaceStage.BASE, 0));
                    addSurfaceOperation(surfaces, new SurfaceOperation(area.areaId(), area.landUseType(),
                            cell.x(), cell.z(), contour.channelBankOverlayBlockId(), 1, true,
                            SurfaceStage.CHANNEL_OVERLAY, 1));
                }
            }
            return;
        }
        if (printArea.recipe() instanceof CityLandUseSurfacePrintPlan.RelayRegionGrowthRecipe relay) {
            CityLandUseSurfacePrintPlan.RelayRoleDefinition role = relay.roleDefinitionAt(cell.x(), cell.z());
            switch (role.materialRole()) {
                case PRIMARY_CONTENT -> {
                    addSurfaceOperation(surfaces, new SurfaceOperation(area.areaId(), area.landUseType(),
                            cell.x(), cell.z(), relay.surfaceBlockId(), 0, false, SurfaceStage.BASE, 0));
                    if (!relay.cropBlockId().isBlank()) {
                        addSurfaceOperation(surfaces, new SurfaceOperation(area.areaId(), area.landUseType(),
                                cell.x(), cell.z(), relay.cropBlockId(), 1, true, SurfaceStage.CROP, 1));
                    }
                }
                case BANK -> {
                    addSurfaceOperation(surfaces, new SurfaceOperation(area.areaId(), area.landUseType(),
                            cell.x(), cell.z(), relay.channelBankBlockId(), 0, false,
                            SurfaceStage.BASE, 0));
                    if (!relay.channelBankOverlayBlockId().isBlank()) {
                        addSurfaceOperation(surfaces, new SurfaceOperation(area.areaId(), area.landUseType(),
                                cell.x(), cell.z(), relay.channelBankOverlayBlockId(), 1, true,
                                SurfaceStage.CHANNEL_OVERLAY, 1));
                    }
                }
                case WATER -> addSurfaceOperation(surfaces,
                        new SurfaceOperation(area.areaId(), area.landUseType(), cell.x(), cell.z(),
                                relay.channelWaterBlockId(), 0, false, SurfaceStage.BASE, 0,
                                relay.channelBankBlockId()));
                case GROUND -> addSurfaceOperation(surfaces,
                        new SurfaceOperation(area.areaId(), area.landUseType(), cell.x(), cell.z(),
                                relay.channelBankBlockId(), 0, false, SurfaceStage.BASE, 0));
            }
            return;
        }
        throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_RECIPE_UNSUPPORTED");
    }

    private static void addSurfaceOperation(Map<SurfaceCell, SurfaceOperation> surfaces,
                                            SurfaceOperation operation) {
        surfaces.putIfAbsent(new SurfaceCell(operation.x(), operation.z(), operation.surfaceOffset()), operation);
    }

    private static Set<BlockCell> cellsClipped(List<LandUseAreaPlan.ScanlineSpan> spans,
                                               int minChunkX,
                                               int minChunkZ,
                                               int maxChunkX,
                                               int maxChunkZ) {
        Set<BlockCell> cells = new HashSet<>();
        for (LandUseAreaPlan.ScanlineSpan span : spans) {
            if (span.z() < minChunkZ || span.z() > maxChunkZ
                    || span.maxX() < minChunkX || span.minX() > maxChunkX) {
                continue;
            }
            for (int x = Math.max(span.minX(), minChunkX); x <= Math.min(span.maxX(), maxChunkX); x++) {
                cells.add(new BlockCell(x, span.z()));
            }
        }
        return cells;
    }

    private void validateLandUsePlan(LandUseAreaPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (!codec.isValidPlanHash(plan)) {
            throw new IllegalArgumentException("LAND_USE_PLAN_HASH_MISMATCH");
        }
    }

    private Map<AreaKey, CityLandUseSurfacePrintPlan.AreaPrint> validateSurfacePrintPlan(
            LandUseAreaPlan plan,
            CityLandUseSurfacePrintPlan surfacePrintPlan) {
        Objects.requireNonNull(surfacePrintPlan, "surfacePrintPlan");
        if (surfacePrintPlan.planHash().isBlank()
                || !surfacePrintPlan.planHash().equals(surfacePrintCodec.computePlanHash(surfacePrintPlan))) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_PLAN_HASH_MISMATCH");
        }
        if (!plan.cityId().equals(surfacePrintPlan.cityId())) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_CITY_MISMATCH");
        }
        if (!plan.planHash().equals(surfacePrintPlan.sourceLandUsePlanHash())) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_SOURCE_HASH_MISMATCH");
        }
        Set<AreaKey> landUseAreas = new HashSet<>();
        plan.areas().forEach(area -> landUseAreas.add(AreaKey.from(area)));
        Map<AreaKey, CityLandUseSurfacePrintPlan.AreaPrint> result = new HashMap<>();
        for (CityLandUseSurfacePrintPlan.AreaPrint printArea : surfacePrintPlan.areas()) {
            AreaKey key = AreaKey.from(printArea);
            if (!landUseAreas.contains(key)) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_AREA_MISMATCH:"
                        + printArea.printAreaId());
            }
            if (result.put(key, printArea) != null) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_AREA_DUPLICATE:"
                        + printArea.landUseAreaId());
            }
        }
        return Map.copyOf(result);
    }

    private static void addBoundsClipped(Set<BlockCell> cells,
                                         BlockBounds bounds,
                                         int clipMinX,
                                         int clipMinZ,
                                         int clipMaxX,
                                         int clipMaxZ) {
        for (int z = Math.max(bounds.minZ(), clipMinZ); z <= Math.min(bounds.maxZ(), clipMaxZ); z++) {
            for (int x = Math.max(bounds.minX(), clipMinX); x <= Math.min(bounds.maxX(), clipMaxX); x++) {
                cells.add(new BlockCell(x, z));
            }
        }
    }

    private static BlockCell cell(BlockPoint point) {
        return new BlockCell(point.x(), point.z());
    }

    private static boolean insideChunk(BlockCell cell, int minX, int minZ, int maxX, int maxZ) {
        return cell.x() >= minX && cell.x() <= maxX && cell.z() >= minZ && cell.z() <= maxZ;
    }

    private static String normalizedPolicy(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    public record MaterialPalette(Map<String, String> surfaceMaterials,
                                  Map<String, String> boundaryMaterials,
                                  String paletteHash) {
        public MaterialPalette(Map<String, String> surfaceMaterials,
                               Map<String, String> boundaryMaterials) {
            this(copyNormalized(surfaceMaterials), copyNormalized(boundaryMaterials),
                    computeHash(surfaceMaterials, boundaryMaterials));
        }

        public MaterialPalette {
            surfaceMaterials = Map.copyOf(copyNormalized(
                    Objects.requireNonNull(surfaceMaterials, "surfaceMaterials")));
            boundaryMaterials = Map.copyOf(copyNormalized(
                    Objects.requireNonNull(boundaryMaterials, "boundaryMaterials")));
            Objects.requireNonNull(paletteHash, "paletteHash");
            if (!paletteHash.equals(computeHash(surfaceMaterials, boundaryMaterials))) {
                throw new IllegalArgumentException("CITY_LAND_USE_PALETTE_HASH_MISMATCH");
            }
        }

        public static MaterialPalette defaults() {
            Map<String, String> surfaces = new LinkedHashMap<>();
            surfaces.put("PAVE", "minecraft:stone_bricks");
            surfaces.put("CULTIVATE", "minecraft:farmland");
            surfaces.put(MICRO_FILL_SUBGRADE_KEY, "minecraft:dirt");
            Map<String, String> boundaries = new LinkedHashMap<>();
            boundaries.put("FENCE", "minecraft:oak_fence");
            boundaries.put("HEDGE", "minecraft:oak_leaves");
            boundaries.put("LOW_WALL", "minecraft:cobblestone_wall");
            return new MaterialPalette(surfaces, boundaries);
        }

        public String surfaceMaterial(String policy) {
            return surfaceMaterials.get(normalizedPolicy(policy));
        }

        public String boundaryMaterial(String policy) {
            return boundaryMaterials.get(normalizedPolicy(policy));
        }

        public JsonObject toJson() {
            JsonObject root = new JsonObject();
            root.addProperty("paletteHash", paletteHash);
            root.add("surfaceMaterials", materialsJson(surfaceMaterials));
            root.add("boundaryMaterials", materialsJson(boundaryMaterials));
            return root;
        }

        public static MaterialPalette fromJson(JsonObject root) {
            if (root == null) {
                throw new IllegalArgumentException("CITY_LAND_USE_MATERIAL_PALETTE_REQUIRED");
            }
            return new MaterialPalette(materials(root, "surfaceMaterials"),
                    materials(root, "boundaryMaterials"), string(root, "paletteHash"));
        }

        private static JsonObject materialsJson(Map<String, String> values) {
            JsonObject object = new JsonObject();
            values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> object.addProperty(entry.getKey(), entry.getValue()));
            return object;
        }

        private static Map<String, String> materials(JsonObject root, String key) {
            if (!root.has(key) || !root.get(key).isJsonObject()) {
                throw new IllegalArgumentException("CITY_LAND_USE_MATERIAL_PALETTE_FIELD_REQUIRED: " + key);
            }
            Map<String, String> result = new LinkedHashMap<>();
            for (Map.Entry<String, com.google.gson.JsonElement> entry
                    : root.getAsJsonObject(key).entrySet()) {
                if (!entry.getValue().isJsonPrimitive() || entry.getValue().getAsString().isBlank()) {
                    throw new IllegalArgumentException("CITY_LAND_USE_MATERIAL_PALETTE_FIELD_INVALID: " + key);
                }
                result.put(entry.getKey(), entry.getValue().getAsString());
            }
            return result;
        }

        private static String string(JsonObject root, String key) {
            if (!root.has(key) || !root.get(key).isJsonPrimitive()
                    || root.get(key).getAsString().isBlank()) {
                throw new IllegalArgumentException("CITY_LAND_USE_MATERIAL_PALETTE_FIELD_REQUIRED: " + key);
            }
            return root.get(key).getAsString();
        }

        private static Map<String, String> copyNormalized(Map<String, String> values) {
            Objects.requireNonNull(values, "values");
            Map<String, String> result = new LinkedHashMap<>();
            values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                    result.put(normalizedPolicy(entry.getKey()), Objects.requireNonNull(entry.getValue(), "blockId")));
            return result;
        }

        private static String computeHash(Map<String, String> surfaces, Map<String, String> boundaries) {
            StringBuilder canonical = new StringBuilder();
            copyNormalized(surfaces).forEach((key, value) -> canonical.append("s:").append(key)
                    .append('=').append(value).append('\n'));
            copyNormalized(boundaries).forEach((key, value) -> canonical.append("b:").append(key)
                    .append('=').append(value).append('\n'));
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
                return "sha256:" + HexFormat.of().formatHex(digest);
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException("SHA-256 is required by the Java runtime", ex);
            }
        }
    }

    public record ChunkFragment(String schema,
                                String cityId,
                                String planHash,
                                String paletteHash,
                                int chunkX,
                                int chunkZ,
                                int relevantCellCount,
                                int footprintExcludedCount,
                                int corridorExcludedCount,
                                int gateExcludedCount,
                                String microFillBlockId,
                                List<GradingMaskCell> gradingMaskCells,
                                List<SurfaceOperation> surfaceOperations,
                                List<BoundaryOperation> boundaryOperations,
                                List<FeatureOperation> featureOperations,
                                List<FeatureOperation> gradingFeatureOperations,
                                List<PlatformPurposeAnchor> platformPurposeAnchors,
                                List<PlatformAccessDemand> platformAccessDemands) {
        public ChunkFragment {
            if (!RESULT_SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("CITY_LAND_USE_FRAGMENT_SCHEMA_UNSUPPORTED");
            }
            Objects.requireNonNull(cityId, "cityId");
            Objects.requireNonNull(planHash, "planHash");
            Objects.requireNonNull(paletteHash, "paletteHash");
            microFillBlockId = microFillBlockId == null || microFillBlockId.isBlank()
                    ? null : microFillBlockId;
            gradingMaskCells = List.copyOf(gradingMaskCells);
            surfaceOperations = List.copyOf(surfaceOperations);
            boundaryOperations = List.copyOf(boundaryOperations);
            featureOperations = List.copyOf(featureOperations == null ? List.of() : featureOperations);
            gradingFeatureOperations = List.copyOf(
                    gradingFeatureOperations == null ? List.of() : gradingFeatureOperations);
            platformPurposeAnchors = List.copyOf(
                    platformPurposeAnchors == null ? List.of() : platformPurposeAnchors);
            platformAccessDemands = List.copyOf(
                    platformAccessDemands == null ? List.of() : platformAccessDemands);
        }

        public ChunkFragment(String schema, String cityId, String planHash, String paletteHash,
                             int chunkX, int chunkZ, int relevantCellCount, int footprintExcludedCount,
                             int corridorExcludedCount, int gateExcludedCount, String microFillBlockId,
                             List<GradingMaskCell> gradingMaskCells,
                             List<SurfaceOperation> surfaceOperations,
                             List<BoundaryOperation> boundaryOperations,
                             List<FeatureOperation> featureOperations,
                             List<FeatureOperation> gradingFeatureOperations) {
            this(schema, cityId, planHash, paletteHash, chunkX, chunkZ, relevantCellCount,
                    footprintExcludedCount, corridorExcludedCount, gateExcludedCount, microFillBlockId,
                    gradingMaskCells, surfaceOperations, boundaryOperations, featureOperations,
                    gradingFeatureOperations, List.of(), List.of());
        }

        public ChunkFragment(String schema, String cityId, String planHash, String paletteHash,
                             int chunkX, int chunkZ, int relevantCellCount, int footprintExcludedCount,
                             int corridorExcludedCount, int gateExcludedCount, String microFillBlockId,
                             List<GradingMaskCell> gradingMaskCells,
                             List<SurfaceOperation> surfaceOperations,
                             List<BoundaryOperation> boundaryOperations,
                             List<FeatureOperation> featureOperations) {
            this(schema, cityId, planHash, paletteHash, chunkX, chunkZ, relevantCellCount,
                    footprintExcludedCount, corridorExcludedCount, gateExcludedCount, microFillBlockId,
                    gradingMaskCells, surfaceOperations, boundaryOperations, featureOperations,
                    featureOperations, List.of(), List.of());
        }

        public ChunkFragment(String schema, String cityId, String planHash, String paletteHash,
                             int chunkX, int chunkZ, int relevantCellCount, int footprintExcludedCount,
                             int corridorExcludedCount, int gateExcludedCount, String microFillBlockId,
                             List<GradingMaskCell> gradingMaskCells,
                             List<SurfaceOperation> surfaceOperations,
                             List<BoundaryOperation> boundaryOperations) {
            this(schema, cityId, planHash, paletteHash, chunkX, chunkZ, relevantCellCount,
                    footprintExcludedCount, corridorExcludedCount, gateExcludedCount, microFillBlockId,
                    gradingMaskCells, surfaceOperations, boundaryOperations, List.of(), List.of());
        }

        public boolean hasRelevantCells() {
            return !surfaceOperations.isEmpty()
                    || !boundaryOperations.isEmpty() || !featureOperations.isEmpty();
        }
    }

    public record GradingMaskCell(String areaId, int x, int z, boolean foundation) {
        public static final Comparator<GradingMaskCell> STABLE_ORDER =
                Comparator.comparingInt(GradingMaskCell::z)
                        .thenComparingInt(GradingMaskCell::x)
                        .thenComparing(GradingMaskCell::areaId);

        public GradingMaskCell {
            Objects.requireNonNull(areaId, "areaId");
        }

        public GradingMaskCell(String areaId, int x, int z) {
            this(areaId, x, z, false);
        }
    }

    public record PlatformPurposeAnchor(String areaId,
                                        String purposeId,
                                        PlatformPurpose purpose,
                                        BlockBounds bounds) {
        public PlatformPurposeAnchor {
            Objects.requireNonNull(areaId, "areaId");
            Objects.requireNonNull(purposeId, "purposeId");
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(bounds, "bounds");
        }
    }

    public record PlatformAccessDemand(String areaId,
                                       String demandId,
                                       BlockPoint entrance,
                                       com.rinsing.geomantia.systems.city.domain.landuse.CardinalDirection direction) {
        public PlatformAccessDemand {
            Objects.requireNonNull(areaId, "areaId");
            Objects.requireNonNull(demandId, "demandId");
            Objects.requireNonNull(entrance, "entrance");
            Objects.requireNonNull(direction, "direction");
        }
    }

    public enum PlatformPurpose {
        BUILDING,
        ENTERABLE_OPEN_SPACE,
        LANDSCAPE,
        PRESERVED_TERRAIN
    }

    public record SurfaceOperation(String areaId,
                                   String landUseType,
                                   int x,
                                   int z,
                                   String blockId,
                                   int surfaceOffset,
                                   boolean requireReplaceableTarget,
                                   SurfaceStage stage,
                                   int layerOrder,
                                   String channelClosureBlockId) {
        public SurfaceOperation(String areaId, String landUseType, int x, int z, String blockId,
                                int surfaceOffset, boolean requireReplaceableTarget,
                                SurfaceStage stage, int layerOrder) {
            this(areaId, landUseType, x, z, blockId, surfaceOffset, requireReplaceableTarget,
                    stage, layerOrder, "");
        }
        public static final Comparator<SurfaceOperation> STABLE_ORDER =
                Comparator.comparing(SurfaceOperation::stage)
                        .thenComparingInt(SurfaceOperation::z)
                        .thenComparingInt(SurfaceOperation::x)
                        .thenComparing(SurfaceOperation::areaId)
                        .thenComparingInt(SurfaceOperation::layerOrder)
                        .thenComparingInt(SurfaceOperation::surfaceOffset);

        public SurfaceOperation(String areaId,
                                String landUseType,
                                int x,
                                int z,
                                String blockId) {
            this(areaId, landUseType, x, z, blockId, 0, false, SurfaceStage.BASE, 0);
        }

        public SurfaceOperation(String areaId,
                                String landUseType,
                                int x,
                                int z,
                                String blockId,
                                int surfaceOffset,
                                boolean requireReplaceableTarget,
                                int layerOrder) {
            this(areaId, landUseType, x, z, blockId, surfaceOffset, requireReplaceableTarget,
                    surfaceOffset == 0 ? SurfaceStage.BASE : SurfaceStage.CROP, layerOrder);
        }

        public SurfaceOperation {
            Objects.requireNonNull(areaId, "areaId");
            Objects.requireNonNull(landUseType, "landUseType");
            Objects.requireNonNull(blockId, "blockId");
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(channelClosureBlockId, "channelClosureBlockId");
            if (surfaceOffset < 0 || layerOrder < 0
                    || (surfaceOffset > 0 && !requireReplaceableTarget)
                    || stage == SurfaceStage.BASE && surfaceOffset != 0
                    || stage != SurfaceStage.BASE && surfaceOffset <= 0) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_OPERATION_INVALID");
            }
        }
    }

    public enum SurfaceStage {
        BASE,
        CHANNEL_OVERLAY,
        CROP
    }

    public record BoundaryOperation(String areaId,
                                    String landUseType,
                                    int x,
                                    int z,
                                    String blockId) {
        public static final Comparator<BoundaryOperation> STABLE_ORDER =
                Comparator.comparingInt(BoundaryOperation::z)
                        .thenComparingInt(BoundaryOperation::x)
                        .thenComparing(BoundaryOperation::areaId);

        public BoundaryOperation {
            Objects.requireNonNull(areaId, "areaId");
            Objects.requireNonNull(landUseType, "landUseType");
            Objects.requireNonNull(blockId, "blockId");
        }
    }

    public record FeatureOperation(String sourceId,
                                   int x,
                                   int z,
                                   String blockId,
                                   int surfaceOffset,
                                   CityLandUseSurfacePrintPlan.FeatureKind kind,
                                   CityLandUseSurfacePrintPlan.HorizontalFacing facing) {
        public static final Comparator<FeatureOperation> STABLE_ORDER =
                Comparator.comparingInt(FeatureOperation::z)
                        .thenComparingInt(FeatureOperation::x)
                        .thenComparingInt(FeatureOperation::surfaceOffset)
                        .thenComparing(operation -> operation.kind().name())
                        .thenComparing(FeatureOperation::sourceId);

        public FeatureOperation {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(blockId, "blockId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(facing, "facing");
        }
    }

    private record BlockCell(int x, int z) {
    }

    private record SurfaceCell(int x, int z, int surfaceOffset) {
    }

    private record AreaKey(String areaId,
                           List<String> sourceGroupIds,
                           List<LandUseAreaPlan.ScanlineSpan> memberSpans) {
        private AreaKey {
            sourceGroupIds = List.copyOf(sourceGroupIds);
            memberSpans = List.copyOf(memberSpans);
        }

        private static AreaKey from(LandUseAreaPlan.Area area) {
            return new AreaKey(area.areaId(), area.sourceGroupIds(), area.memberSpans());
        }

        private static AreaKey from(CityLandUseSurfacePrintPlan.AreaPrint area) {
            return new AreaKey(area.landUseAreaId(), area.sourceGroupIds(), area.memberSpans());
        }
    }

    private record OwnerChunk(int chunkX, int chunkZ) {
    }

    public static final class PreparedSurfacePlan {
        private final LandUseAreaPlan areaPlan;
        private final CityLandUseSurfacePrintPlan surfacePrintPlan;
        private final Map<OwnerChunk, Map<AreaKey, CityLandUseSurfacePrintPlan.AreaPrint>> printAreasByOwner;
        private final Map<OwnerChunk, List<CityLandUseSurfacePrintPlan.FeatureCell>> featureCellsByOwner;

        private PreparedSurfacePlan(
                LandUseAreaPlan areaPlan,
                CityLandUseSurfacePrintPlan surfacePrintPlan,
                Map<OwnerChunk, Map<AreaKey, CityLandUseSurfacePrintPlan.AreaPrint>> printAreasByOwner,
                Map<OwnerChunk, List<CityLandUseSurfacePrintPlan.FeatureCell>> featureCellsByOwner) {
            this.areaPlan = Objects.requireNonNull(areaPlan, "areaPlan");
            this.surfacePrintPlan = Objects.requireNonNull(surfacePrintPlan, "surfacePrintPlan");
            this.printAreasByOwner = Map.copyOf(printAreasByOwner);
            this.featureCellsByOwner = Map.copyOf(featureCellsByOwner);
        }

        public String areaPlanHash() {
            return areaPlan.planHash();
        }

        public String surfacePrintPlanHash() {
            return surfacePrintPlan.planHash();
        }

        public int indexedOwnerCount() {
            Set<OwnerChunk> owners = new HashSet<>(printAreasByOwner.keySet());
            owners.addAll(featureCellsByOwner.keySet());
            return owners.size();
        }

        private LandUseAreaPlan areaPlan() {
            return areaPlan;
        }

        private CityLandUseSurfacePrintPlan surfacePrintPlan() {
            return surfacePrintPlan;
        }

        private Map<OwnerChunk, Map<AreaKey, CityLandUseSurfacePrintPlan.AreaPrint>> printAreasByOwner() {
            return printAreasByOwner;
        }

        private Map<OwnerChunk, List<CityLandUseSurfacePrintPlan.FeatureCell>> featureCellsByOwner() {
            return featureCellsByOwner;
        }

    }
}
