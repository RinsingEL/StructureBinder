package com.rinsing.geomantia.systems.city.application.outdoor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.application.CityBlueprintReferenceCatalog;
import com.rinsing.geomantia.systems.city.application.landuse.LandUsePlanningService;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSeedGroup;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseRule;
import com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseRuleCatalog;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityOutdoorBlueprintCompilerTest {
    @Test
    void spatialGroundBuildsOneConnectedGroupSpaceFromD6AndBlueprintRelations() {
        CityBlueprint.SpatialGround ground = new CityBlueprint.SpatialGround("farm_group", "agriculture",
                "surface:farmland", CityBlueprint.SharedSpaceType.FARMSTEAD,
                CityBlueprint.SpatialHierarchy.SECONDARY, CityBlueprint.OutdoorMembership.URBAN);
        CityBlueprint blueprint = blueprint(new CityBlueprint.OutdoorPlan(CityBlueprint.OutdoorMode.GENERATE,
                CityBlueprint.EnvelopeProfile.BALANCED, List.of(ground), List.of()));

        CityOutdoorBlueprintCompiler.Result result = new CityOutdoorBlueprintCompiler().compile(blueprint,
                d6Plan(), terrain(), catalog());

        LandUseSeedGroup group = result.resolution().seedGroups().get(0);
        LandUseRule agriculture = catalog().landUseRuleCatalog().byRef("agriculture").orElseThrow();
        assertTrue(group.preferredAreaBlocks() >= agriculture.preferredArea(32));
        assertEquals(1, group.growthRegions().size());
        assertEquals(LandUseSeedGroup.GrowthBiasMode.NEUTRAL, group.growthBias().mode());
        assertTrue(group.seedPoints().stream().anyMatch(point -> point.z() >= 40 && point.z() <= 51));
        assertEquals(com.rinsing.geomantia.systems.city.domain.landuse.BoundaryPolicy.OPEN,
                group.rule().boundaryPolicy());
        assertEquals(Set.of("farm_group"), result.residualConfig().urbanGroupIds());
        assertEquals(16, result.residualConfig().closeRadiusBlocks());
        assertFalse(result.intentPlan().planHash().isBlank());
        assertEquals(result.intentPlan().planHash(), result.intentPlan().withComputedHash().planHash());
        assertTrue(result.intentPlan().sourceBlueprintHash().matches("sha256:[0-9a-f]{64}"));
        assertTrue(result.intentPlan().sourceD6Hash().matches("sha256:[0-9a-f]{64}"));
        assertTrue(result.intentPlan().sourceTerrainFieldHash().matches("sha256:[0-9a-f]{64}"));
        assertTrue(result.intentPlan().sourceOutdoorCatalogHash().matches("sha256:[0-9a-f]{64}"));
    }

    @Test
    void attachedFarmlandLandscapeUsesOneSharedProfileBudget() {
        CityBlueprint.Landscape landscape = new CityBlueprint.Landscape("outer_fields", "landscape:farmland",
                List.of("farm_group"), List.of("farm_patch"), CityBlueprint.ExtentClass.SMALL,
                CityBlueprint.OutdoorIntensity.LOW, CityBlueprint.LandscapeContinuity.MULTI_PARCEL,
                CityBlueprint.LandscapeGrowthRelation.AWAY_FROM_REFERENCE, List.of("core_group"),
                CityBlueprint.TerrainPolicy.CONFORM, true);
        CityBlueprint blueprint = blueprint(new CityBlueprint.OutdoorPlan(CityBlueprint.OutdoorMode.GENERATE,
                CityBlueprint.EnvelopeProfile.COMPACT, List.of(), List.of(landscape)));

        CityOutdoorBlueprintCompiler.Result result = new CityOutdoorBlueprintCompiler().compile(blueprint,
                d6Plan(), terrain(), catalog());

        LandUseSeedGroup group = result.resolution().seedGroups().get(0);
        assertEquals("outer_fields", group.groupId());
        assertEquals(750, group.preferredAreaBlocks());
        assertEquals(525, group.minAreaBlocks());
        assertEquals(900, group.maxAreaBlocks());
        assertEquals(2, group.growthRegions().size());
        assertEquals(group.minAreaBlocks(), group.growthRegions().stream()
                .mapToInt(LandUseSeedGroup.GrowthRegion::minAreaBlocks).sum());
        assertEquals(group.preferredAreaBlocks(), group.growthRegions().stream()
                .mapToInt(LandUseSeedGroup.GrowthRegion::preferredAreaBlocks).sum());
        assertEquals(group.maxAreaBlocks(), group.growthRegions().stream()
                .mapToInt(LandUseSeedGroup.GrowthRegion::maxAreaBlocks).sum());
        assertTrue(group.growthRegions().stream().allMatch(region -> region.anchorIds().size() == 2));
        assertEquals(LandUseSeedGroup.TerrainBias.CONFORM, group.terrainBias());
        assertEquals(List.of("farm_patch"), group.preferredPatchRefs());
        CityOutdoorIntentPlan.SourceIntent intent = result.intentPlan().sources().get(0);
        assertEquals(CityBlueprint.LandscapeContinuity.MULTI_PARCEL, intent.continuity());
        assertEquals(CityBlueprint.TerrainPolicy.CONFORM, intent.terrainPolicy());
        assertEquals(List.of("farm_patch"), intent.preferredPatchRefs());
        assertFalse(result.residualConfig().enabled());
    }

    @Test
    void alongWaterUsesShorelineTangentInsteadOfTowardWaterBias() {
        CityBlueprint.Landscape toward = new CityBlueprint.Landscape("water_fields", "landscape:farmland",
                List.of("farm_group"), List.of("farm_patch"), CityBlueprint.ExtentClass.SMALL,
                CityBlueprint.OutdoorIntensity.LOW, CityBlueprint.LandscapeContinuity.MULTI_PARCEL,
                CityBlueprint.LandscapeGrowthRelation.TOWARD_WATER, List.of(),
                CityBlueprint.TerrainPolicy.BALANCED, true);
        CityBlueprint.Landscape along = new CityBlueprint.Landscape("water_fields", "landscape:farmland",
                List.of("farm_group"), List.of("farm_patch"), CityBlueprint.ExtentClass.SMALL,
                CityBlueprint.OutdoorIntensity.LOW, CityBlueprint.LandscapeContinuity.MULTI_PARCEL,
                CityBlueprint.LandscapeGrowthRelation.ALONG_WATER, List.of(),
                CityBlueprint.TerrainPolicy.BALANCED, true);

        LandUseSeedGroup towardGroup = new CityOutdoorBlueprintCompiler().compile(
                blueprint(new CityBlueprint.OutdoorPlan(CityBlueprint.OutdoorMode.GENERATE,
                        CityBlueprint.EnvelopeProfile.COMPACT, List.of(), List.of(toward))),
                d6Plan(), terrainWithHorizontalWater(), catalog()).resolution().seedGroups().get(0);
        LandUseSeedGroup alongGroup = new CityOutdoorBlueprintCompiler().compile(
                blueprint(new CityBlueprint.OutdoorPlan(CityBlueprint.OutdoorMode.GENERATE,
                        CityBlueprint.EnvelopeProfile.COMPACT, List.of(), List.of(along))),
                d6Plan(), terrainWithHorizontalWater(), catalog()).resolution().seedGroups().get(0);

        assertEquals(LandUseSeedGroup.GrowthBiasMode.TOWARD_REFERENCE, towardGroup.growthBias().mode());
        assertEquals(LandUseSeedGroup.GrowthBiasMode.ALONG_WATER, alongGroup.growthBias().mode());
        assertEquals(1, alongGroup.growthBias().axisX());
        assertEquals(0, alongGroup.growthBias().axisZ());
        assertNotEquals(towardGroup.seedPoints(), alongGroup.seedPoints());
        assertTrue(alongGroup.seedPoints().stream().allMatch(point -> point.z() == 62 || point.z() == 70));
    }

    @Test
    void detachedLandscapeDerivesStableSeedFromPreferredPatchCells() {
        CityBlueprint.Landscape landscape = new CityBlueprint.Landscape("detached_fields", "landscape:farmland",
                List.of(), List.of("farm_patch"), CityBlueprint.ExtentClass.SMALL,
                CityBlueprint.OutdoorIntensity.MEDIUM, CityBlueprint.LandscapeContinuity.CONTINUOUS,
                CityBlueprint.LandscapeGrowthRelation.AROUND_SOURCE, List.of(),
                CityBlueprint.TerrainPolicy.CONFORM, true);
        CityBlueprint blueprint = blueprint(new CityBlueprint.OutdoorPlan(CityBlueprint.OutdoorMode.GENERATE,
                CityBlueprint.EnvelopeProfile.COMPACT, List.of(), List.of(landscape)));

        CityOutdoorBlueprintCompiler.Result first = new CityOutdoorBlueprintCompiler().compile(blueprint,
                d6Plan(), terrain(), catalog());
        CityOutdoorBlueprintCompiler.Result second = new CityOutdoorBlueprintCompiler().compile(blueprint,
                d6Plan(), terrain(), catalog());

        assertEquals(1, first.resolution().seedGroups().get(0).seedPoints().size());
        assertEquals(first.intentPlan(), second.intentPlan());
        assertEquals(first.resolution().seedGroups().get(0).seedPoints(),
                second.resolution().seedGroups().get(0).seedPoints());
    }

    @Test
    void patchyLandscapeSplitsOneTotalBudgetAcrossStableParcels() {
        CityBlueprint.Landscape landscape = new CityBlueprint.Landscape("orchard_patches", "landscape:farmland",
                List.of(), List.of("farm_patch"), CityBlueprint.ExtentClass.SMALL,
                CityBlueprint.OutdoorIntensity.MEDIUM, CityBlueprint.LandscapeContinuity.PATCHY,
                CityBlueprint.LandscapeGrowthRelation.AROUND_SOURCE, List.of(),
                CityBlueprint.TerrainPolicy.ASSERTIVE, true);
        CityBlueprint blueprint = blueprint(new CityBlueprint.OutdoorPlan(CityBlueprint.OutdoorMode.GENERATE,
                CityBlueprint.EnvelopeProfile.COMPACT, List.of(), List.of(landscape)));

        LandUseSeedGroup group = new CityOutdoorBlueprintCompiler().compile(blueprint, d6Plan(), terrain(),
                catalog()).resolution().seedGroups().get(0);

        assertEquals(4, group.growthRegions().size());
        assertEquals(group.minAreaBlocks(), group.growthRegions().stream()
                .mapToInt(LandUseSeedGroup.GrowthRegion::minAreaBlocks).sum());
        assertEquals(group.preferredAreaBlocks(), group.growthRegions().stream()
                .mapToInt(LandUseSeedGroup.GrowthRegion::preferredAreaBlocks).sum());
        assertEquals(group.maxAreaBlocks(), group.growthRegions().stream()
                .mapToInt(LandUseSeedGroup.GrowthRegion::maxAreaBlocks).sum());
        assertEquals(LandUseSeedGroup.TerrainBias.ASSERTIVE, group.terrainBias());
    }

    @Test
    void preserveModeProducesNoSourcesAndDisablesResidualResolver() {
        CityBlueprint blueprint = blueprint(new CityBlueprint.OutdoorPlan(CityBlueprint.OutdoorMode.PRESERVE,
                CityBlueprint.EnvelopeProfile.LOOSE, List.of(), List.of()));

        CityOutdoorBlueprintCompiler.Result result = new CityOutdoorBlueprintCompiler().compile(blueprint,
                d6Plan(), terrain(), catalog());

        assertTrue(result.resolution().seedGroups().isEmpty());
        assertFalse(result.residualConfig().enabled());
        assertEquals(0, result.intentPlan().envelope().closeRadiusBlocks());
        assertEquals("city_outdoor_intent_plan.v0.2",
                result.intentPlan().toJson().get("schemaVersion").getAsString());
    }

    @Test
    void intentHashBindsEveryCompilationInputEvenInPreserveMode() {
        CityBlueprint original = blueprint(new CityBlueprint.OutdoorPlan(CityBlueprint.OutdoorMode.PRESERVE,
                CityBlueprint.EnvelopeProfile.LOOSE, List.of(), List.of()));
        CityOutdoorBlueprintCompiler compiler = new CityOutdoorBlueprintCompiler();
        CityOutdoorIntentPlan base = compiler.compile(original, d6Plan(), terrain(), catalog()).intentPlan();

        CityBlueprint changedBlueprint = new CityBlueprint(original.schemaVersion(), original.cityId(),
                original.sourceD3Ref(), original.catalogSnapshotRef(), 43, original.designIntent(),
                original.styleProfile(), original.groups(), original.relations(), original.roadProfile(),
                original.surfaceDetailProfile(), original.outdoorPlan());
        JsonObject changedD6 = d6Plan();
        changedD6.addProperty("sourceRevision", 2);
        LandUseTerrainField changedTerrain = terrainWithFirstCellSlope(1.0);
        CityBlueprintReferenceCatalog changedCatalog = catalogWithJson("revision", 2);

        Set<String> hashes = new HashSet<>();
        hashes.add(base.planHash());
        hashes.add(compiler.compile(changedBlueprint, d6Plan(), terrain(), catalog()).intentPlan().planHash());
        hashes.add(compiler.compile(original, changedD6, terrain(), catalog()).intentPlan().planHash());
        hashes.add(compiler.compile(original, d6Plan(), changedTerrain, catalog()).intentPlan().planHash());
        hashes.add(compiler.compile(original, d6Plan(), terrain(), changedCatalog).intentPlan().planHash());
        assertEquals(5, hashes.size());
    }

    @Test
    void requiredLandscapeFailsWhenReachableTerrainIsClearlyBelowMinimum() {
        CityBlueprint.Landscape landscape = new CityBlueprint.Landscape("detached_fields", "landscape:farmland",
                List.of(), List.of("farm_patch"), CityBlueprint.ExtentClass.SMALL,
                CityBlueprint.OutdoorIntensity.LOW, CityBlueprint.LandscapeContinuity.CONTINUOUS,
                CityBlueprint.LandscapeGrowthRelation.AROUND_SOURCE, List.of(),
                CityBlueprint.TerrainPolicy.CONFORM, true);
        CityBlueprint blueprint = blueprint(new CityBlueprint.OutdoorPlan(CityBlueprint.OutdoorMode.GENERATE,
                CityBlueprint.EnvelopeProfile.COMPACT, List.of(), List.of(landscape)));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new CityOutdoorBlueprintCompiler().compile(blueprint, d6Plan(), tinyTerrain(), catalog()));

        assertTrue(exception.getMessage().startsWith("CITY_OUTDOOR_REQUIRED_LANDSCAPE_BELOW_MIN:"));
    }

    @Test
    void planningServicePublishesResolvedUrbanCoverageInTraceAndQuality() {
        CityBlueprint.SpatialGround ground = new CityBlueprint.SpatialGround("farm_group", "agriculture",
                "surface:farmland", CityBlueprint.SharedSpaceType.FARMSTEAD,
                CityBlueprint.SpatialHierarchy.SECONDARY, CityBlueprint.OutdoorMembership.URBAN);
        CityOutdoorBlueprintCompiler.Result compiled = new CityOutdoorBlueprintCompiler().compile(
                blueprint(new CityBlueprint.OutdoorPlan(CityBlueprint.OutdoorMode.GENERATE,
                        CityBlueprint.EnvelopeProfile.COMPACT, List.of(ground), List.of())),
                d6Plan(), terrain(), catalog());

        LandUsePlanningService.Result result = new LandUsePlanningService().plan("city", compiled.resolution(),
                null, terrain(), compiled.residualConfig());
        CityUrbanSpacePlan.CoverageSummary coverage = result.urbanSpacePlan().coverageSummary();

        assertTrue(result.trace().get("urbanSpaceEnabled").getAsBoolean());
        assertEquals("resolved", result.quality().get("urbanSpaceStatus").getAsString());
        assertEquals(coverage.envelopeBlocks(), result.trace().get("urbanEnvelopeBlocks").getAsInt());
        assertEquals(coverage.absorbedResidualBlocks(),
                result.quality().get("urbanAbsorbedResidualBlocks").getAsInt());
        assertEquals(coverage.explicitResidualBlocks(),
                result.quality().get("urbanExplicitResidualBlocks").getAsInt());
        assertEquals(0, coverage.explicitResidualBlocks());
        assertEquals(0, result.quality().get("urbanUnknownResidualBlocks").getAsInt());
        assertEquals(result.urbanSpacePlan().planHash(),
                result.trace().get("urbanSpacePlanHash").getAsString());
    }

    private static CityBlueprint blueprint(CityBlueprint.OutdoorPlan outdoorPlan) {
        return new CityBlueprint(CityBlueprint.SCHEMA_VERSION, "city",
                new CityBlueprint.ArtifactRef("d3.json", "d3", "sha256:" + "1".repeat(64)),
                new CityBlueprint.ArtifactRef("catalog.json", "catalog", "sha256:" + "2".repeat(64)),
                42, new CityBlueprint.DesignIntent("town", "green", List.of("agriculture")),
                new CityBlueprint.ProfileRef("style:test"), groups(), List.of(
                        new CityBlueprint.Relation("core_group", "farm_group",
                                CityBlueprint.RelationKind.CONNECTION, CityBlueprint.RelationStrength.HARD,
                                CityBlueprint.DistancePreference.NEAR, CityBlueprint.DirectionPreference.NONE)),
                new CityBlueprint.ProfileRef("road:test"), new CityBlueprint.ProfileRef("surface:test"),
                outdoorPlan);
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
        CityBlueprintReferenceCatalog.SurfaceRecipe farmland = new CityBlueprintReferenceCatalog.SurfaceRecipe(
                "surface:farmland", true, true,
                CityBlueprintReferenceCatalog.SurfaceAlgorithm.CONTOUR_BANDS,
                "minecraft:farmland", "minecraft:wheat", "minecraft:dirt", "minecraft:water",
                "minecraft:oak_slab");
        CityBlueprintReferenceCatalog.LandscapeProfile landscape =
                new CityBlueprintReferenceCatalog.LandscapeProfile("landscape:farmland",
                        CityBlueprintReferenceCatalog.LandscapeType.FARMLAND, "agriculture",
                        "surface:farmland", 1000, 2000, 4000,
                        CityBlueprint.OutdoorMembership.LANDSCAPE);
        return new CityBlueprintReferenceCatalog(new JsonObject(), Set.of(), Set.of(), Set.of(), Map.of(),
                Set.of(), Set.of(), Set.of(), Set.of(), rules,
                Map.of(farmland.surfaceRecipeRef(), farmland),
                Map.of(landscape.landscapeProfileRef(), landscape));
    }

    private static JsonObject d6Plan() {
        JsonObject root = new JsonObject();
        root.addProperty("cityId", "city");
        root.addProperty("locked", true);
        JsonArray structures = new JsonArray();
        structures.add(structure("core", "core_group", 8, 40, 11, 43));
        structures.add(structure("farm_a", "farm_group", 40, 40, 43, 43));
        structures.add(structure("farm_b", "farm_group", 40, 48, 43, 51));
        root.add("plannedWorldgenStructures", structures);
        return root;
    }

    private static JsonObject structure(String anchorId, String groupId,
                                        int minX, int minZ, int maxX, int maxZ) {
        JsonObject item = new JsonObject();
        item.addProperty("anchorId", anchorId);
        item.addProperty("placementGroupId", groupId);
        JsonObject footprint = new JsonObject();
        footprint.addProperty("minX", minX);
        footprint.addProperty("minZ", minZ);
        footprint.addProperty("maxX", maxX);
        footprint.addProperty("maxZ", maxZ);
        item.add("lockedActualFootprint", footprint);
        return item;
    }

    private static LandUseTerrainField terrain() {
        List<LandUseTerrainField.Cell> cells = new java.util.ArrayList<>();
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

    private static LandUseTerrainField tinyTerrain() {
        LandUseTerrainField.Cell cell = new LandUseTerrainField.Cell(0, 0, 0, 0, 4,
                70, 0, 0, 0, false, 0, 32,
                "minecraft:plains", "plain", "farm_patch", true);
        return new LandUseTerrainField(LandUseTerrainField.CURRENT_SCHEMA_VERSION, "city",
                new BlockBounds(0, 0, 3, 3), 4, List.of(cell));
    }

    private static LandUseTerrainField terrainWithHorizontalWater() {
        List<LandUseTerrainField.Cell> cells = new java.util.ArrayList<>();
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                boolean water = z == 16;
                cells.add(new LandUseTerrainField.Cell(x, z, x * 4, z * 4, 4,
                        water ? 62 : 70, 0, 0, 0, water, water ? 8 : 0, 0,
                        "minecraft:plains", water ? "river" : "plain", "farm_patch", true));
            }
        }
        return new LandUseTerrainField(LandUseTerrainField.CURRENT_SCHEMA_VERSION, "city",
                new BlockBounds(0, 0, 127, 127), 4, cells);
    }

    private static LandUseTerrainField terrainWithFirstCellSlope(double slope) {
        LandUseTerrainField source = terrain();
        List<LandUseTerrainField.Cell> cells = new java.util.ArrayList<>(source.cells());
        LandUseTerrainField.Cell first = cells.get(0);
        cells.set(0, new LandUseTerrainField.Cell(first.cellX(), first.cellZ(), first.blockMinX(),
                first.blockMinZ(), first.cellStepBlocks(), first.elevation(), slope, first.localRelief(),
                first.roughness(), first.water(), first.waterDepth(), first.waterDistance(), first.biomeId(),
                first.landformType(), first.landformPatchId(), first.sampled()));
        return new LandUseTerrainField(source.schemaVersion(), source.cityId(), source.planningBounds(),
                source.cellStepBlocks(), cells);
    }

    private static CityBlueprintReferenceCatalog catalogWithJson(String key, int value) {
        CityBlueprintReferenceCatalog source = catalog();
        JsonObject json = new JsonObject();
        json.addProperty(key, value);
        return new CityBlueprintReferenceCatalog(json, source.structureRefs(), source.fillPoolRefs(),
                source.algorithmProfileRefs(), source.algorithmsByProfileRef(), source.compositionProfileRefs(),
                source.styleProfileRefs(), source.roadProfileRefs(), source.surfaceDetailProfileRefs(),
                source.landUseRuleCatalog(), source.surfaceRecipes(), source.landscapeProfiles());
    }
}
