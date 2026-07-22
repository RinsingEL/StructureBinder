package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan;
import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfaceRunCompiler;
import com.rinsing.geomantia.systems.city.application.terrain.CityContinuousTerrainRunPlanner;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationContentCatalog;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityNbtPrefabBatchPlacer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Compiles frozen lined-channel placements into one owner-chunk NBT batch. */
public final class CityLandUseSurfacePrefabOwnerCompiler {
    public CityNbtPrefabBatchPlacer.BatchRequest compile(CityLandUseSurfacePrintPlan plan,
                                                         CityDecorationContentCatalog catalog,
                                                         int ownerChunkX,
                                                         int ownerChunkZ,
                                                         int worldMinY,
                                                         int worldMaxY) {
        return compilePrepared(prepare(plan, catalog), ownerChunkX, ownerChunkZ, worldMinY, worldMaxY,
                request -> request.coarseTargetY());
    }

    public PreparedPlan prepare(CityLandUseSurfacePrintPlan plan,
                                CityDecorationContentCatalog catalog) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(catalog, "catalog");
        if (!plan.catalogHash().equals(catalog.catalogHash())) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PREFAB_CATALOG_HASH_MISMATCH");
        }
        Map<OwnerChunk, List<IndexedPlacement>> byOwner = new HashMap<>();
        for (CityLandUseSurfacePrintPlan.AreaPrint area : plan.areas()) {
            if (!(area.recipe() instanceof CityLandUseSurfacePrintPlan.CultivateLinedRecipe recipe)) {
                continue;
            }
            CityDecorationContentCatalog.Content straight = validateSpec(
                    recipe.straightPrefab(), CityLandUseSurfaceRunCompiler.STRAIGHT_CONTENT_REF, catalog);
            CityDecorationContentCatalog.Content endCap = validateSpec(
                    recipe.endCapPrefab(), CityLandUseSurfaceRunCompiler.END_CAP_CONTENT_REF, catalog);
            for (CityLandUseSurfacePrintPlan.SurfaceRun run : recipe.runs()) {
                for (CityLandUseSurfacePrintPlan.SurfacePlacement placement : run.placements()) {
                    CityDecorationContentCatalog.Content applied = switch (placement.decision()) {
                        case PLACE -> straight;
                        case END_CAP -> endCap;
                        case TERMINATE, DEFER -> null;
                    };
                    if (applied == null) continue;
                    validatePlacement(placement, recipe.straightPrefab(), applied);
                    IndexedPlacement indexed = new IndexedPlacement(
                            area.printAreaId() + '/' + placement.placementId(), placement, applied);
                    int minChunkX = Math.floorDiv(placement.footprint().minX(), 16);
                    int maxChunkX = Math.floorDiv(placement.footprint().maxX(), 16);
                    int minChunkZ = Math.floorDiv(placement.footprint().minZ(), 16);
                    int maxChunkZ = Math.floorDiv(placement.footprint().maxZ(), 16);
                    for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                            byOwner.computeIfAbsent(new OwnerChunk(chunkX, chunkZ), ignored -> new ArrayList<>())
                                    .add(indexed);
                        }
                    }
                }
            }
        }
        Map<OwnerChunk, List<IndexedPlacement>> frozen = new HashMap<>();
        byOwner.forEach((owner, placements) -> frozen.put(owner, List.copyOf(placements)));
        return new PreparedPlan(plan, catalog, Map.copyOf(frozen));
    }

    public CityNbtPrefabBatchPlacer.BatchRequest compilePrepared(
            PreparedPlan prepared,
            int ownerChunkX,
            int ownerChunkZ,
            int worldMinY,
            int worldMaxY,
            TargetYResolver targetYResolver) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(targetYResolver, "targetYResolver");
        if (worldMinY > worldMaxY) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PREFAB_OWNER_Y_INVALID");
        }
        BoundingBox ownerBounds = ownerBounds(ownerChunkX, ownerChunkZ, worldMinY, worldMaxY);
        List<CityNbtPrefabBatchPlacer.PrefabPlacement> placements = new ArrayList<>();
        for (IndexedPlacement indexed : prepared.byOwner().getOrDefault(
                new OwnerChunk(ownerChunkX, ownerChunkZ), List.of())) {
            PlacementDatumRequest datumRequest = indexed.datumRequest(prepared.plan().planHash());
            int targetY = targetYResolver.resolve(datumRequest);
            if (targetY > worldMaxY
                    || (long) targetY + indexed.content().size().heightBlocks() - 1L < worldMinY) {
                continue;
            }
            CityLandUseSurfacePrintPlan.SurfacePlacement placement = indexed.placement();
            BlockPos anchor = new BlockPos(placement.placementAnchor().x(), targetY,
                    placement.placementAnchor().z());
            CityDecorationContentCatalog.Content content = indexed.content();
            CompoundTag template = content.template();
            boolean ignoreTemplateAir = "preserve".equals(content.clearanceMode());
            if (intersectsOwner(template, anchor, placement.rotationDegrees(), ownerBounds,
                    !ignoreTemplateAir)) {
                placements.add(new CityNbtPrefabBatchPlacer.PrefabPlacement(
                        indexed.placementKey(), placement.appliedContentRef(),
                        placement.appliedContentHash(), template, anchor,
                        placement.rotationDegrees(), ignoreTemplateAir,
                        content.replacePolicy(), content.groundPlaneLocalY()));
            }
        }
        return new CityNbtPrefabBatchPlacer.BatchRequest(
                requestId(prepared.plan(), ownerChunkX, ownerChunkZ), ownerBounds, placements);
    }

    public List<PlacementDatumRequest> datumRequestsForOwner(PreparedPlan prepared,
                                                              int ownerChunkX,
                                                              int ownerChunkZ) {
        Objects.requireNonNull(prepared, "prepared");
        return prepared.byOwner().getOrDefault(new OwnerChunk(ownerChunkX, ownerChunkZ), List.of()).stream()
                .map(indexed -> indexed.datumRequest(prepared.plan().planHash())).toList();
    }

    private static CityDecorationContentCatalog.Content validateSpec(
            CityLandUseSurfaceRunCompiler.PrefabSpec spec,
            String expectedRef,
            CityDecorationContentCatalog catalog) {
        if (!expectedRef.equals(spec.contentRef())) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PREFAB_FIXED_REF_MISMATCH:"
                    + spec.contentRef());
        }
        CityDecorationContentCatalog.Content content = catalog.requireContent(expectedRef);
        if (content.plant() || content.template() == null || !"replace_surface".equals(content.placementMode())) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PREFAB_METADATA_INVALID:" + expectedRef);
        }
        if (!spec.contentHash().equals(content.contentHash())) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PREFAB_CONTENT_HASH_MISMATCH:"
                    + expectedRef);
        }
        CityDecorationContentCatalog.Size size = content.size();
        if (spec.widthBlocks() != size.widthBlocks()
                || spec.heightBlocks() != size.heightBlocks()
                || spec.depthBlocks() != size.depthBlocks()) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PREFAB_CONTENT_SIZE_MISMATCH:"
                    + expectedRef);
        }
        return content;
    }

    private static void validatePlacement(
            CityLandUseSurfacePrintPlan.SurfacePlacement placement,
            CityLandUseSurfaceRunCompiler.PrefabSpec straightSpec,
            CityDecorationContentCatalog.Content applied) {
        if (!CityLandUseSurfaceRunCompiler.STRAIGHT_CONTENT_REF.equals(placement.contentRef())
                || !straightSpec.contentHash().equals(placement.contentHash())) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PREFAB_SOURCE_IDENTITY_MISMATCH:"
                    + placement.placementId());
        }
        String expectedAppliedRef = placement.decision() == CityContinuousTerrainRunPlanner.Decision.END_CAP
                ? CityLandUseSurfaceRunCompiler.END_CAP_CONTENT_REF
                : CityLandUseSurfaceRunCompiler.STRAIGHT_CONTENT_REF;
        if (!expectedAppliedRef.equals(placement.appliedContentRef())) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PREFAB_APPLIED_REF_MISMATCH:"
                    + placement.placementId());
        }
        if (!applied.contentHash().equals(placement.appliedContentHash())) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PREFAB_APPLIED_HASH_MISMATCH:"
                    + placement.placementId());
        }
        if (!applied.allowedRotations().contains(placement.rotationDegrees())) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PREFAB_ROTATION_UNSUPPORTED:"
                    + placement.placementId() + ':' + placement.rotationDegrees());
        }
        int expectedWidth = placement.rotationDegrees() == 0 || placement.rotationDegrees() == 180
                ? applied.size().widthBlocks() : applied.size().depthBlocks();
        int expectedDepth = placement.rotationDegrees() == 0 || placement.rotationDegrees() == 180
                ? applied.size().depthBlocks() : applied.size().widthBlocks();
        if (placement.footprint().widthBlocks() != expectedWidth
                || placement.footprint().heightBlocks() != expectedDepth) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PREFAB_FOOTPRINT_SIZE_MISMATCH:"
                    + placement.placementId());
        }
    }

    private static boolean intersectsOwner(CompoundTag template,
                                           BlockPos anchor,
                                           int rotationDegrees,
                                           BoundingBox ownerBounds,
                                           boolean includeTemplateAir) {
        Rotation rotation = rotation(rotationDegrees);
        ListTag blocks = template.getList("blocks", 10);
        ListTag palette = template.getList("palette", 10);
        for (int index = 0; index < blocks.size(); index++) {
            CompoundTag block = blocks.getCompound(index);
            int stateIndex = block.getInt("state");
            boolean templateAir = stateIndex >= 0 && stateIndex < palette.size()
                    && "minecraft:air".equals(palette.getCompound(stateIndex).getString("Name"));
            if (templateAir && !includeTemplateAir) continue;
            ListTag localValues = block.getList("pos", 3);
            BlockPos local = new BlockPos(localValues.getInt(0), localValues.getInt(1), localValues.getInt(2));
            BlockPos transformed = StructureTemplate.transform(local, Mirror.NONE, rotation, BlockPos.ZERO);
            BlockPos target = new BlockPos(
                    Math.addExact(anchor.getX(), transformed.getX()),
                    Math.addExact(anchor.getY(), transformed.getY()),
                    Math.addExact(anchor.getZ(), transformed.getZ()));
            if (ownerBounds.isInside(target)) return true;
        }
        return false;
    }

    private static Rotation rotation(int degrees) {
        return switch (degrees) {
            case 0 -> Rotation.NONE;
            case 90 -> Rotation.CLOCKWISE_90;
            case 180 -> Rotation.CLOCKWISE_180;
            case 270 -> Rotation.COUNTERCLOCKWISE_90;
            default -> throw new IllegalArgumentException(
                    "CITY_LAND_USE_SURFACE_PREFAB_ROTATION_UNSUPPORTED:" + degrees);
        };
    }

    private static BoundingBox ownerBounds(int chunkX, int chunkZ, int minY, int maxY) {
        int minX = Math.multiplyExact(chunkX, 16);
        int minZ = Math.multiplyExact(chunkZ, 16);
        return new BoundingBox(minX, minY, minZ,
                Math.addExact(minX, 15), maxY, Math.addExact(minZ, 15));
    }

    private static String requestId(CityLandUseSurfacePrintPlan plan, int chunkX, int chunkZ) {
        String planIdentity = plan.planHash().isBlank() ? plan.sourceLandUsePlanHash() : plan.planHash();
        return plan.cityId() + "/land_use_surface_prefabs/" + planIdentity
                + "/chunk/" + chunkX + ',' + chunkZ;
    }

    @FunctionalInterface
    public interface TargetYResolver {
        int resolve(PlacementDatumRequest request);
    }

    public record PlacementDatumRequest(String surfacePrintPlanHash,
                                        String placementId,
                                        com.rinsing.geomantia.systems.city.domain.model.BlockPoint terrainSamplePoint,
                                        int coarseSurfaceY,
                                        int coarseTargetY) {
        public PlacementDatumRequest {
            if (surfacePrintPlanHash == null || surfacePrintPlanHash.isBlank()
                    || placementId == null || placementId.isBlank()
                    || terrainSamplePoint == null) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PREFAB_DATUM_REQUEST_INVALID");
            }
        }
    }

    public static final class PreparedPlan {
        private final CityLandUseSurfacePrintPlan plan;
        private final CityDecorationContentCatalog catalog;
        private final Map<OwnerChunk, List<IndexedPlacement>> byOwner;

        private PreparedPlan(CityLandUseSurfacePrintPlan plan,
                             CityDecorationContentCatalog catalog,
                             Map<OwnerChunk, List<IndexedPlacement>> byOwner) {
            this.plan = Objects.requireNonNull(plan, "plan");
            this.catalog = Objects.requireNonNull(catalog, "catalog");
            this.byOwner = Map.copyOf(byOwner);
        }

        public String surfacePrintPlanHash() {
            return plan.planHash();
        }

        public String catalogHash() {
            return catalog.catalogHash();
        }

        public int indexedOwnerCount() {
            return byOwner.size();
        }

        public int indexedPlacementCount(int ownerChunkX, int ownerChunkZ) {
            return byOwner.getOrDefault(new OwnerChunk(ownerChunkX, ownerChunkZ), List.of()).size();
        }

        private CityLandUseSurfacePrintPlan plan() {
            return plan;
        }

        private Map<OwnerChunk, List<IndexedPlacement>> byOwner() {
            return byOwner;
        }
    }

    private record IndexedPlacement(String placementKey,
                                    CityLandUseSurfacePrintPlan.SurfacePlacement placement,
                                    CityDecorationContentCatalog.Content content) {
        private PlacementDatumRequest datumRequest(String surfacePrintPlanHash) {
            return new PlacementDatumRequest(surfacePrintPlanHash, placementKey,
                    placement.terrainSamplePoint(), placement.surfaceY(), placement.targetY());
        }
    }

    private record OwnerChunk(int chunkX, int chunkZ) {
    }
}
