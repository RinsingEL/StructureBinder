package com.rinsing.geomantia.systems.city.application.outdoor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.application.CityBlueprintReferenceCatalog;
import com.rinsing.geomantia.systems.city.application.landuse.LandUsePlanningService;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSeedGroup;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseRuleCatalog;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityOutdoorBlueprintCompilerTest {
    @Test
    void compilesOneFoundationAndPhaseDrivenIndependentParcels() {
        CityOutdoorBlueprintCompiler.Result result = compile(d6Plan());

        List<LandUseSeedGroup> foundations = result.resolution().seedGroups().stream()
                .filter(group -> group.layerRole() == LandUseSeedGroup.LayerRole.FOUNDATION).toList();
        List<LandUseSeedGroup> parcels = result.resolution().seedGroups().stream()
                .filter(group -> group.layerRole() == LandUseSeedGroup.LayerRole.LANDSCAPE).toList();
        assertEquals(1, foundations.size());
        assertEquals("city::foundation", foundations.get(0).groupId());
        assertEquals(4, foundations.get(0).anchorIds().size());
        assertEquals(7, parcels.size());
        assertEquals(5, parcels.stream().filter(group -> group.groupId().contains("farm_a")).count());
        assertEquals(2, parcels.stream().filter(group -> group.groupId().contains("farm_b")).count());
        assertTrue(parcels.stream().filter(group -> group.groupId().contains("farm_a"))
                .allMatch(group -> group.anchorIds().equals(List.of("farm_a"))));
        assertTrue(parcels.stream().filter(group -> group.groupId().contains("farm_b"))
                .allMatch(group -> group.anchorIds().equals(List.of("farm_b"))));
        assertTrue(parcels.stream().noneMatch(group -> group.groupId().contains("farm_c")));
        assertTrue(parcels.stream().allMatch(group -> group.seedPoints().size() == 1));
        assertTrue(parcels.stream().allMatch(group -> group.growthRegions().size() == 1));
        assertTrue(parcels.stream().noneMatch(group -> group.rule().mergeSameType()));
        assertTrue(parcels.stream().allMatch(group -> group.landscapeFillProgram() != null));
        assertTrue(parcels.stream().allMatch(group -> group.surfaceSettings().surfaceAlgorithm()
                == com.rinsing.geomantia.systems.city.domain.landuse.LandUseSurfaceSettings.SurfaceAlgorithm
                .RELAY_REGION_GROWTH));
        assertTrue(parcels.stream().allMatch(group -> group.landscapeFillProgram().fillProfileRef()
                .equals("fill:irrigated")));
        assertTrue(parcels.stream().allMatch(group -> group.landscapeFillProgram().roles().stream()
                .map(com.rinsing.geomantia.systems.city.domain.landuse.LandscapeFillProgram.RoleDefinition::roleRef)
                .toList().equals(List.of("CULTIVATED", "BANK", "WATER", "BANK", "CULTIVATED"))));
        assertTrue(result.resolution().corridorExclusions().isEmpty());
        assertFalse(result.residualConfig().enabled());
        assertEquals("city_outdoor_intent_plan.v0.3", result.intentPlan().schemaVersion());
        CityOutdoorIntentPlan.SourceIntent foundationIntent = result.intentPlan().sources().stream()
                .filter(source -> source.sourceKind() == CityOutdoorIntentPlan.SourceKind.FOUNDATION)
                .findFirst().orElseThrow();
        assertNull(foundationIntent.extentClass());
        assertEquals("foundation:test", foundationIntent.profileRef());

        JsonObject withoutFirstOwnerParcels = d6Plan();
        withoutFirstOwnerParcels.getAsJsonArray("plannedWorldgenStructures").get(1).getAsJsonObject()
                .addProperty("blueprintPlacementPhase", "connectivity_growth");
        LandUseSeedGroup isolatedFarmBFirst = compile(withoutFirstOwnerParcels).resolution().seedGroups().stream()
                .filter(group -> group.groupId().equals("outer_fields::farm_b::parcel_01"))
                .findFirst().orElseThrow();
        LandUseSeedGroup fullFarmBFirst = parcels.stream()
                .filter(group -> group.groupId().equals("outer_fields::farm_b::parcel_01"))
                .findFirst().orElseThrow();
        assertEquals(isolatedFarmBFirst.seedPoints(), fullFarmBFirst.seedPoints());
    }

    @Test
    void stableInputsFreezeParcelIdsSeedsAndResolvedFoundationRadius() {
        CityOutdoorBlueprintCompiler compiler = new CityOutdoorBlueprintCompiler();
        CityOutdoorBlueprintCompiler.Result first = compiler.compile(blueprint(), d6Plan(), terrain(), catalog());
        CityOutdoorBlueprintCompiler.Result second = compiler.compile(blueprint(), d6Plan(), terrain(), catalog());

        assertEquals(first.intentPlan(), second.intentPlan());
        assertEquals(first.resolution().seedGroups(), second.resolution().seedGroups());
        assertEquals(first.intentPlan().envelope().closeRadiusBlocks(),
                second.intentPlan().envelope().closeRadiusBlocks());
        assertTrue(first.intentPlan().envelope().closeRadiusBlocks() >= 8);
        assertFalse(first.intentPlan().planHash().isBlank());
    }

    @Test
    void missingD6BlueprintPlacementPhaseFailsFormally() {
        JsonObject d6 = d6Plan();
        d6.getAsJsonArray("plannedWorldgenStructures").get(0).getAsJsonObject()
                .remove("blueprintPlacementPhase");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new CityOutdoorBlueprintCompiler().compile(blueprint(), d6, terrain(), catalog()));

        assertTrue(exception.getMessage().startsWith("CITY_OUTDOOR_D6_BLUEPRINT_PHASE_MISSING:core"));
    }

    @Test
    void layeredPlanningUsesNoCorridorsAndKeepsOneAreaPerParcelOwner() {
        CityOutdoorBlueprintCompiler.Result compiled = compile(d6Plan());
        LandUsePlanningService.Result planned = new LandUsePlanningService().plan("city", compiled.resolution(),
                d5Corridor(), terrain(), compiled.residualConfig());

        assertTrue(planned.plan().corridorExclusions().isEmpty());
        assertEquals("city_land_use_planning_trace.v0.5",
                planned.trace().get("schemaVersion").getAsString());
        assertTrue(planned.trace().get("foundationResolvedCloseRadiusBlocks").getAsInt() >= 8);
        List<LandUseAreaPlan.Area> foundationAreas = planned.plan().areas().stream()
                .filter(area -> area.sourceGroupIds().contains("city::foundation")).toList();
        List<LandUseAreaPlan.Area> parcelAreas = planned.plan().areas().stream()
                .filter(area -> area.sourceGroupIds().stream().anyMatch(id -> id.startsWith("outer_fields::")))
                .toList();
        assertEquals(1, foundationAreas.size());
        assertEquals(7, parcelAreas.size());
        assertTrue(parcelAreas.stream().allMatch(area -> area.sourceGroupIds().size() == 1));
        assertEquals(7, parcelAreas.stream().flatMap(area -> area.sourceGroupIds().stream()).distinct().count());
        assertEquals(7, planned.surfacePrintPlan().areas().stream()
                .filter(area -> area.recipe() instanceof
                        com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan
                                .RelayRegionGrowthRecipe)
                .count());
        JsonObject firstParcelTrace = planned.trace().getAsJsonArray("seedGroups").asList().stream()
                .map(value -> value.getAsJsonObject())
                .filter(value -> value.get("groupId").getAsString().startsWith("outer_fields::"))
                .findFirst().orElseThrow();
        assertEquals("fill:irrigated", firstParcelTrace.get("fillProfileRef").getAsString());
        assertEquals(5, firstParcelTrace.getAsJsonArray("fillRelayStages").size());
        Set<BlockPoint> claimedOnce = new java.util.HashSet<>();
        for (LandUseAreaPlan.Area area : planned.plan().areas()) {
            for (LandUseAreaPlan.ScanlineSpan span : area.memberSpans()) {
                for (int x = span.minX(); x <= span.maxX(); x++) {
                    assertTrue(claimedOnce.add(new BlockPoint(x, span.z())),
                            "LandUse claims must have one final owner");
                }
            }
        }
        assertFalse(planned.urbanSpacePlan().enabled());
        assertTrue(planned.plan().warnings().stream().noneMatch(warning -> warning.contains("foundation")));
    }

    @Test
    void overlayReportsOnlyVisibleFoundationClaims() {
        CityOutdoorBlueprintCompiler.Result compiled = compile(d6Plan());
        LandUsePlanningService.Result planned = new LandUsePlanningService().plan("city", compiled.resolution(),
                null, terrain(), compiled.residualConfig());
        int visibleFoundation = planned.plan().areas().stream()
                .filter(area -> area.sourceGroupIds().contains("city::foundation"))
                .flatMap(area -> area.memberSpans().stream())
                .mapToInt(span -> span.maxX() - span.minX() + 1).sum();
        JsonObject foundationTrace = planned.trace().getAsJsonArray("seedGroups").asList().stream()
                .map(value -> value.getAsJsonObject())
                .filter(value -> value.get("groupId").getAsString().equals("city::foundation"))
                .findFirst().orElseThrow();

        assertEquals(visibleFoundation, foundationTrace.get("claimedAreaBlocks").getAsInt());
        assertEquals(visibleFoundation, foundationTrace.get("foundationVisibleAreaBlocks").getAsInt());
        assertEquals(foundationTrace.get("preferredAreaBlocks").getAsInt(),
                foundationTrace.get("foundationBaseAreaBlocks").getAsInt());
        assertTrue(visibleFoundation < foundationTrace.get("foundationBaseAreaBlocks").getAsInt());
    }

    @Test
    void preserveModeStillProducesNoLandUseSources() {
        CityBlueprint source = blueprint();
        CityBlueprint preserve = new CityBlueprint(source.schemaVersion(), source.cityId(), source.sourceD3Ref(),
                source.catalogSnapshotRef(), source.generationSeed(), source.designIntent(), source.styleProfile(),
                source.groups(), source.relations(), source.roadProfile(), source.surfaceDetailProfile(),
                new CityBlueprint.OutdoorPlan(CityBlueprint.OutdoorMode.PRESERVE,
                        CityBlueprint.EnvelopeProfile.BALANCED, "foundation:test", List.of(), List.of()));

        CityOutdoorBlueprintCompiler.Result result = new CityOutdoorBlueprintCompiler().compile(preserve,
                d6Plan(), terrain(), catalog());

        assertTrue(result.resolution().seedGroups().isEmpty());
        assertEquals("city_outdoor_intent_plan.v0.3", result.intentPlan().schemaVersion());
    }

    private static CityOutdoorBlueprintCompiler.Result compile(JsonObject d6) {
        return new CityOutdoorBlueprintCompiler().compile(blueprint(), d6, terrain(), catalog());
    }

    private static CityBlueprint blueprint() {
        CityBlueprint.Landscape landscape = new CityBlueprint.Landscape("outer_fields", "landscape:farmland",
                List.of("farm_group"), List.of("farm_patch"), CityBlueprint.ExtentClass.SMALL,
                CityBlueprint.OutdoorIntensity.MEDIUM, CityBlueprint.LandscapeContinuity.MULTI_PARCEL,
                CityBlueprint.LandscapeGrowthRelation.AROUND_SOURCE, List.of(),
                CityBlueprint.TerrainPolicy.CONFORM, true,
                new CityBlueprint.FillSelection(List.of(new CityBlueprint.FillVariant(
                        "fill:irrigated", 1.0,
                        List.of(new CityBlueprint.RoleShare("CULTIVATED",
                                        CityBlueprint.RegionGrowthForm.PATCH, 0.41),
                                new CityBlueprint.RoleShare("BANK",
                                        CityBlueprint.RegionGrowthForm.CORRIDOR, 0.06),
                                new CityBlueprint.RoleShare("WATER",
                                        CityBlueprint.RegionGrowthForm.CORRIDOR, 0.06),
                                new CityBlueprint.RoleShare("BANK",
                                        CityBlueprint.RegionGrowthForm.CORRIDOR, 0.06),
                                new CityBlueprint.RoleShare("CULTIVATED",
                                        CityBlueprint.RegionGrowthForm.PATCH, 0.41)),
                        List.of(new CityBlueprint.ContentWeight("crop:wheat", 1.0))))));
        CityBlueprint.OutdoorPlan outdoor = new CityBlueprint.OutdoorPlan(CityBlueprint.OutdoorMode.GENERATE,
                CityBlueprint.EnvelopeProfile.BALANCED, "foundation:test",
                List.of(new CityBlueprint.SpatialGround("core_group", CityBlueprint.SharedSpaceType.CIVIC_SQUARE,
                                CityBlueprint.SpatialHierarchy.PRIMARY, CityBlueprint.OutdoorMembership.URBAN),
                        new CityBlueprint.SpatialGround("farm_group", CityBlueprint.SharedSpaceType.FARMSTEAD,
                                CityBlueprint.SpatialHierarchy.SECONDARY, CityBlueprint.OutdoorMembership.URBAN)),
                List.of(landscape));
        return new CityBlueprint(CityBlueprint.SCHEMA_VERSION, "city",
                new CityBlueprint.ArtifactRef("d3.json", "d3", "sha256:" + "1".repeat(64)),
                new CityBlueprint.ArtifactRef("catalog.json", "catalog", "sha256:" + "2".repeat(64)),
                42, new CityBlueprint.DesignIntent("town", "green", List.of("agriculture")),
                new CityBlueprint.ProfileRef("style:test"), groups(), List.of(),
                new CityBlueprint.ProfileRef("road:test"), new CityBlueprint.ProfileRef("surface:test"), outdoor);
    }

    private static List<CityBlueprint.Group> groups() {
        return List.of(group("core_group", CityBlueprint.GroupPriority.CORE),
                group("farm_group", CityBlueprint.GroupPriority.STANDARD));
    }

    private static CityBlueprint.Group group(String id, CityBlueprint.GroupPriority priority) {
        return new CityBlueprint.Group(id, CityBlueprint.GroupKind.STRUCTURE, List.of(),
                CityBlueprint.PreferredPatchZone.CENTER, id, priority, CityBlueprint.ExtentClass.MEDIUM,
                CityBlueprint.DensityClass.BALANCED, "algorithm:test", CityBlueprint.TerrainPolicy.BALANCED,
                List.of(), "pool:test", null, "composition:test", List.of());
    }

    private static CityBlueprintReferenceCatalog catalog() {
        LandUseRuleCatalog rules = LandUseRuleCatalog.defaults();
        CityBlueprintReferenceCatalog.SurfaceRecipe foundationRecipe =
                new CityBlueprintReferenceCatalog.SurfaceRecipe("surface:foundation", true, false,
                        CityBlueprintReferenceCatalog.SurfaceAlgorithm.UNIFORM, "minecraft:stone_bricks",
                        null, null, null, null, null, 0, 0, 0);
        CityBlueprintReferenceCatalog.SurfaceRecipe farmlandRecipe =
                new CityBlueprintReferenceCatalog.SurfaceRecipe("surface:farmland", true, false,
                        CityBlueprintReferenceCatalog.SurfaceAlgorithm.CONTOUR_BANDS, "minecraft:farmland",
                        "minecraft:wheat", "minecraft:dirt", "minecraft:water", "minecraft:oak_slab",
                        "minecraft:oak_fence", 5, 3, 5);
        CityBlueprintReferenceCatalog.FoundationProfile foundation =
                new CityBlueprintReferenceCatalog.FoundationProfile("foundation:test", "plaza",
                        "surface:foundation", 3, 8, 32);
        CityBlueprintReferenceCatalog.LandscapeProfile landscape =
                new CityBlueprintReferenceCatalog.LandscapeProfile("landscape:farmland",
                        CityBlueprintReferenceCatalog.LandscapeType.FARMLAND, "agriculture",
                        "surface:farmland", 1000, 2000, 4000, CityBlueprint.OutdoorMembership.LANDSCAPE,
                        new CityBlueprintReferenceCatalog.ParcelStyle(5, 5, 2, 2,
                                192, 240, 1.0, 4, 12));
        CityBlueprintReferenceCatalog.LandscapeFillProfile fillProfile =
                new CityBlueprintReferenceCatalog.LandscapeFillProfile("fill:irrigated", "Irrigated fields",
                        "Cultivated fields separated by narrow banks and water",
                        CityBlueprintReferenceCatalog.FillAlgorithm.SINGLE_SOURCE_REGION_RELAY,
                        CityBlueprintReferenceCatalog.RelayOrigin.PARENT_REGION_LOCAL_BOUNDARY,
                        Set.of(CityBlueprintReferenceCatalog.LandscapeType.FARMLAND), "CULTIVATED",
                        Map.of(
                                "CULTIVATED", new CityBlueprintReferenceCatalog.FillRole("CULTIVATED",
                                        CityBlueprintReferenceCatalog.MaterialRole.PRIMARY_CONTENT,
                                        Set.of(CityBlueprint.RegionGrowthForm.PATCH),
                                        CityBlueprint.RegionGrowthForm.PATCH,
                                        0.65, 0.92, 0.82),
                                "BANK", new CityBlueprintReferenceCatalog.FillRole("BANK",
                                        CityBlueprintReferenceCatalog.MaterialRole.BANK,
                                        Set.of(CityBlueprint.RegionGrowthForm.CORRIDOR),
                                        CityBlueprint.RegionGrowthForm.CORRIDOR,
                                        0.06, 0.2, 0.12),
                                "WATER", new CityBlueprintReferenceCatalog.FillRole("WATER",
                                        CityBlueprintReferenceCatalog.MaterialRole.WATER,
                                        Set.of(CityBlueprint.RegionGrowthForm.CORRIDOR),
                                        CityBlueprint.RegionGrowthForm.CORRIDOR,
                                        0.02, 0.12, 0.06)),
                        Set.of("crop:wheat"), List.of());
        return new CityBlueprintReferenceCatalog(new JsonObject(), Set.of(), Set.of(), Set.of(), Map.of(),
                Set.of(), Set.of(), Set.of(), Set.of(), rules,
                Map.of(foundationRecipe.surfaceRecipeRef(), foundationRecipe,
                        farmlandRecipe.surfaceRecipeRef(), farmlandRecipe),
                Map.of(foundation.foundationProfileRef(), foundation),
                Map.of(landscape.landscapeProfileRef(), landscape),
                Map.of(fillProfile.fillProfileRef(), fillProfile));
    }

    private static JsonObject d6Plan() {
        JsonObject root = new JsonObject();
        root.addProperty("cityId", "city");
        root.addProperty("locked", true);
        JsonArray structures = new JsonArray();
        structures.add(structure("core", "core_group", "required", 20, 40, 25, 45));
        structures.add(structure("farm_a", "farm_group", "required", 40, 40, 45, 45));
        structures.add(structure("farm_b", "farm_group", "fill", 40, 52, 45, 57));
        structures.add(structure("farm_c", "farm_group", "connectivity_growth", 52, 52, 57, 57));
        root.add("plannedWorldgenStructures", structures);
        return root;
    }

    private static JsonObject structure(String anchorId, String groupId, String phase,
                                        int minX, int minZ, int maxX, int maxZ) {
        JsonObject item = new JsonObject();
        item.addProperty("anchorId", anchorId);
        item.addProperty("placementGroupId", groupId);
        item.addProperty("blueprintPlacementPhase", phase);
        JsonObject footprint = new JsonObject();
        footprint.addProperty("minX", minX);
        footprint.addProperty("minZ", minZ);
        footprint.addProperty("maxX", maxX);
        footprint.addProperty("maxZ", maxZ);
        item.add("lockedActualFootprint", footprint);
        return item;
    }

    private static LandUseTerrainField terrain() {
        List<LandUseTerrainField.Cell> cells = new ArrayList<>();
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                cells.add(new LandUseTerrainField.Cell(x, z, x * 4, z * 4, 4,
                        70, 0, 0, 0, false, 0, 32,
                        "minecraft:plains", "plain", "farm_patch", true));
            }
        }
        return new LandUseTerrainField(LandUseTerrainField.CURRENT_SCHEMA_VERSION, "city",
                new BlockBounds(0, 0, 127, 127), 4, cells);
    }

    private static JsonObject d5Corridor() {
        JsonObject root = new JsonObject();
        JsonArray exclusions = new JsonArray();
        JsonObject exclusion = new JsonObject();
        exclusion.addProperty("reservationId", "road_should_be_ignored");
        exclusion.addProperty("minX", 0);
        exclusion.addProperty("minZ", 0);
        exclusion.addProperty("maxX", 127);
        exclusion.addProperty("maxZ", 127);
        exclusions.add(exclusion);
        root.add("reservations", exclusions);
        return root;
    }
}
