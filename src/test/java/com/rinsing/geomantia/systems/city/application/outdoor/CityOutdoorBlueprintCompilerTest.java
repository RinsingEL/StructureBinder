package com.rinsing.geomantia.systems.city.application.outdoor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.CityBlueprintReferenceCatalog;
import com.rinsing.geomantia.systems.city.application.CityLandscapeCapacityReservationPlanner;
import com.rinsing.geomantia.systems.city.application.landuse.LandUsePlanningService;
import com.rinsing.geomantia.systems.city.application.landuse.LandUseSourceResolver;
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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityOutdoorBlueprintCompilerTest {
    @Test
    void foundationCarriesTransformedBuildingEntrancesIntoLandUse() {
        JsonObject d6 = d6Plan();
        JsonObject core = d6.getAsJsonArray("plannedWorldgenStructures").get(0).getAsJsonObject();
        JsonObject transformed = new JsonObject();
        JsonObject entrance = new JsonObject();
        entrance.addProperty("entranceId", "front");
        entrance.addProperty("direction", "SOUTH");
        entrance.add("worldPosition", JsonParser.parseString("{\"x\":32,\"z\":46}"));
        JsonArray entrances = new JsonArray();
        entrances.add(entrance);
        transformed.add("roadEntrances", entrances);
        JsonObject placement = new JsonObject();
        placement.add("transformed", transformed);
        core.add("templatePlacementPlan", placement);

        CityOutdoorBlueprintCompiler.Result compiled = compile(d6);
        LandUseSeedGroup foundation = compiled.resolution().seedGroups().stream()
                .filter(group -> group.layerRole() == LandUseSeedGroup.LayerRole.FOUNDATION)
                .findFirst().orElseThrow();

        assertEquals(1, foundation.gateSlots().size());
        assertEquals("core::front", foundation.gateSlots().get(0).gateId());
        assertEquals(new BlockPoint(32, 46), foundation.gateSlots().get(0).block());
        assertEquals("core", foundation.gateSlots().get(0).sourceAnchorId());

        LandUsePlanningService.Result planned = new LandUsePlanningService().plan("city",
                compiled.resolution(), d5Corridor(), terrain(), compiled.residualConfig());
        assertTrue(planned.plan().areas().stream()
                .filter(area -> area.sourceGroupIds().contains("city::foundation"))
                .flatMap(area -> area.gateSlots().stream())
                .anyMatch(gate -> gate.gateId().equals("core::front")));
    }

    @Test
    void streetBandsJoinFoundationWithoutBecomingCollisionExclusions() {
        JsonObject d6 = d6Plan();
        JsonObject anchorMap = new JsonObject();
        JsonArray bands = new JsonArray();
        bands.add(JsonParser.parseString("""
                {"schema":"city_internal_street_band","streetBandId":"farm::street",
                 "groupId":"farm_group","widthBlocks":5,
                 "bounds":{"minX":26,"minZ":40,"maxX":38,"maxZ":44},
                 "platformBounds":{"minX":24,"minZ":36,"maxX":40,"maxZ":48}}
                """).getAsJsonObject());
        bands.add(JsonParser.parseString("""
                {"schema":"city_main_road_band","streetBandId":"city_main::segment_001",
                 "groupId":"__city_main_road__","roadKind":"CITY_MAIN_ROAD","widthBlocks":7,
                 "bounds":{"minX":40,"minZ":52,"maxX":72,"maxZ":58},
                 "platformBounds":{"minX":40,"minZ":52,"maxX":72,"maxZ":58}}
                """).getAsJsonObject());
        anchorMap.add("streetBands", bands);
        d6.add("sourceStructureAnchorMap", anchorMap);

        LandUseSeedGroup foundation = compile(d6).resolution().seedGroups().stream()
                .filter(group -> group.layerRole() == LandUseSeedGroup.LayerRole.FOUNDATION)
                .findFirst().orElseThrow();

        assertFalse(foundation.structureFootprints().contains(new BlockBounds(24, 36, 40, 48)));
        assertFalse(foundation.structureFootprints().contains(new BlockBounds(40, 52, 72, 58)));
    }

    @Test
    void buildingMarkerAndCityStylePaletteFreezeGreenParcelSpec() {
        JsonObject d6 = d6Plan();
        JsonObject farmhouse = d6.getAsJsonArray("plannedWorldgenStructures").get(1).getAsJsonObject();
        farmhouse.add("lockedCollisionEnvelope", bounds(38, 38, 47, 47));
        JsonObject placement = new JsonObject();
        JsonObject transformed = new JsonObject();
        JsonArray entrances = new JsonArray();
        JsonObject entrance = new JsonObject();
        JsonObject position = new JsonObject();
        position.addProperty("x", 39);
        position.addProperty("z", 42);
        entrance.add("worldPosition", position);
        entrances.add(entrance);
        transformed.add("roadEntrances", entrances);
        placement.add("transformed", transformed);
        farmhouse.add("templatePlacementPlan", placement);
        farmhouse.add("buildingParcelPlan", JsonParser.parseString("""
                {"schema":"city_building_parcel_plan",
                 "resolvedBounds":{"minX":36,"minZ":36,"maxX":49,"maxZ":49},
                 "greenerySelected":true,"greeneryPattern":"FREEFORM","greeneryDensity":"MEDIUM"}
                """).getAsJsonObject());
        CityBlueprintReferenceCatalog source = catalog();
        CityBlueprintReferenceCatalog greenCatalog = new CityBlueprintReferenceCatalog(source.json(),
                source.structureRefs(), source.fillPoolRefs(), source.algorithmProfileRefs(),
                source.algorithmsByProfileRef(), source.centerAxisStreetEnabledByProfileRef(),
                source.compositionProfileRefs(), source.styleProfileRefs(), source.roadProfileRefs(),
                source.surfaceDetailProfileRefs(), Map.of("farmhouse",
                new CityBlueprintReferenceCatalog.BuildingGreenParcelProfile(
                        CityBlueprintReferenceCatalog.GreenParcelPattern.FREEFORM,
                        CityBlueprintReferenceCatalog.GreenParcelDensity.MEDIUM,
                        "minecraft:grass_block", "minecraft:gravel")),
                Map.of("style:test", List.of(
                        new CityBlueprintReferenceCatalog.PlantPaletteEntry("minecraft:poppy", 1))),
                source.landUseRuleCatalog(), source.surfaceRecipes(), source.foundationProfiles(),
                source.landscapeProfiles(), source.landscapeFillProfiles());

        CityOutdoorBlueprintCompiler.Result result = new CityOutdoorBlueprintCompiler().compile(
                blueprint(), d6, terrain(), greenCatalog, capacity(blueprint(), d6));

        assertEquals(1, result.resolution().greenParcels().size());
        LandUseSourceResolver.GreenParcelSpec parcel = result.resolution().greenParcels().get(0);
        assertEquals(new BlockBounds(36, 36, 49, 49), parcel.parcelBounds());
        assertEquals(new BlockBounds(38, 38, 47, 47), parcel.hardExclusionBounds());
        assertEquals(new BlockPoint(39, 42), parcel.entrance());
        assertEquals("minecraft:poppy", parcel.plantPalette().get(0).blockId());
    }

    @Test
    void d4ResidentialOverflowPlanFreezesBoundaryAndRoadOpeningsForSurfaceExecution() {
        JsonObject d6 = d6Plan();
        JsonObject anchorMap = new JsonObject();
        JsonArray bands = new JsonArray();
        bands.add(JsonParser.parseString("""
                {"streetBandId":"farm::grid_main","roadNetworkId":"farm::grid",
                 "roadKind":"GRID_MAIN_STREET","groupId":"farm_group","widthBlocks":3,
                 "crossSectionProfile":"STAIR_SLAB_STAIR","axisX":1,"axisZ":0,
                 "start":{"x":36,"z":48},"end":{"x":60,"z":48},
                 "bounds":{"minX":36,"minZ":47,"maxX":60,"maxZ":49}}
                """).getAsJsonObject());
        anchorMap.add("streetBands", bands);
        anchorMap.add("residentialOverflowPlan", JsonParser.parseString("""
                {"schema":"city_residential_overflow_plan","minimumBuildingCount":3,
                 "zoneCount":1,"zones":[{"zoneId":"farm::overflow","parentGroupId":"farm_group",
                 "zoneKind":"RESIDENTIAL_OVERFLOW","generationMode":"OUTWARD_GUIDED_FILL_BUILDINGS",
                 "buildingCount":3,"boundaryBounds":{"minX":34,"minZ":38,"maxX":62,"maxZ":60},
                 "boundaryBlockId":"minecraft:stone_brick_wall","anchorIds":["a","b","c"],
                 "streetBandIds":["farm::grid_main"]}]}
                """).getAsJsonObject());
        d6.add("sourceStructureAnchorMap", anchorMap);

        CityOutdoorBlueprintCompiler.Result result = compile(d6);

        assertEquals(1, result.resolution().overflowZones().size());
        LandUseSourceResolver.OverflowZoneSpec zone = result.resolution().overflowZones().get(0);
        assertEquals("farm::overflow", zone.zoneId());
        assertEquals(new BlockBounds(34, 38, 62, 60), zone.boundaryBounds());
        assertEquals(List.of(new BlockBounds(36, 47, 60, 49)), zone.roadOpenings());
    }

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
        assertEquals(7, parcels.stream().filter(group -> group.admissionPolicy()
                == LandUseSeedGroup.AdmissionPolicy.REQUIRED).count());
        assertEquals(0, parcels.stream().filter(group -> group.admissionPolicy()
                == LandUseSeedGroup.AdmissionPolicy.OPTIONAL).count());
        assertTrue(parcels.stream().allMatch(group -> group.anchorIds().equals(List.of("farm_a"))));
        assertTrue(parcels.stream().allMatch(group -> group.groupId().contains("::instance_01::parcel_")));
        assertTrue(parcels.stream().allMatch(group -> group.seedPoints().size() == 1));
        assertTrue(parcels.stream().allMatch(group -> group.growthRegions().size() == 1));
        assertTrue(parcels.stream().noneMatch(group -> group.rule().mergeSameType()));
        assertTrue(parcels.stream().allMatch(group -> group.landscapeFillProgram() != null));
        assertTrue(parcels.stream().allMatch(group -> group.surfaceSettings().surfaceAlgorithm()
                == com.rinsing.geomantia.systems.city.domain.landuse.LandUseSurfaceSettings.SurfaceAlgorithm
                .RELAY_REGION_GROWTH));
        assertTrue(parcels.stream().allMatch(group -> group.landscapeFillProgram().fillProfileRef()
                .equals("fill:irrigated")));
        LandUseSeedGroup rootParcel = parcels.stream()
                .filter(group -> group.groupId().endsWith("::parcel_01")).findFirst().orElseThrow();
        BlockPoint rootSeed = rootParcel.seedPoints().get(0);
        assertTrue(terrain().planningBounds().contains(rootSeed.x(), rootSeed.z()));
        assertFalse(new BlockBounds(40, 40, 45, 45).contains(rootSeed.x(), rootSeed.z()),
                "Frozen root Parcel may use the owner as a seed but cannot overlap the structure");
        assertTrue(parcels.stream().allMatch(group -> group.landscapeFillProgram().roles().stream()
                .map(com.rinsing.geomantia.systems.city.domain.landuse.LandscapeFillProgram.RoleDefinition::roleRef)
                .toList().equals(List.of("CULTIVATED", "BANK", "WATER", "BANK", "CULTIVATED"))));
        assertTrue(result.resolution().corridorExclusions().isEmpty());
        assertFalse(result.residualConfig().enabled());
        assertEquals("city_outdoor_intent_plan", result.intentPlan().schema());
        CityOutdoorIntentPlan.SourceIntent foundationIntent = result.intentPlan().sources().stream()
                .filter(source -> source.sourceKind() == CityOutdoorIntentPlan.SourceKind.FOUNDATION)
                .findFirst().orElseThrow();
        assertNull(foundationIntent.extentClass());
        assertEquals("foundation:test", foundationIntent.profileRef());

        JsonObject withoutConnectivityAnchor = d6Plan();
        withoutConnectivityAnchor.getAsJsonArray("plannedWorldgenStructures").remove(3);
        List<LandUseSeedGroup> withoutConnectivity = compile(withoutConnectivityAnchor).resolution().seedGroups()
                .stream().filter(group -> group.layerRole() == LandUseSeedGroup.LayerRole.LANDSCAPE).toList();
        assertEquals(parcels.stream().map(LandUseSeedGroup::groupId).toList(),
                withoutConnectivity.stream().map(LandUseSeedGroup::groupId).toList());
        assertEquals(parcels.stream().map(LandUseSeedGroup::admissionPolicy).toList(),
                withoutConnectivity.stream().map(LandUseSeedGroup::admissionPolicy).toList());
    }

    @Test
    void fillAnchorsShareOneGroupLevelOptionalParcelBudget() {
        JsonObject manyFillAnchors = d6Plan();
        JsonArray structures = manyFillAnchors.getAsJsonArray("plannedWorldgenStructures");
        for (int index = 0; index < 9; index++) {
            int x = 64 + (index % 3) * 10;
            int z = 64 + (index / 3) * 10;
            structures.add(structure("extra_fill_" + index, "farm_group", "fill", x, z, x + 5, z + 5));
        }

        List<LandUseSeedGroup> parcels = compile(manyFillAnchors).resolution().seedGroups().stream()
                .filter(group -> group.layerRole() == LandUseSeedGroup.LayerRole.LANDSCAPE).toList();

        assertEquals(7, parcels.size());
        assertEquals(7, parcels.stream().filter(group -> group.admissionPolicy()
                == LandUseSeedGroup.AdmissionPolicy.REQUIRED).count());
        assertEquals(0, parcels.stream().filter(group -> group.admissionPolicy()
                == LandUseSeedGroup.AdmissionPolicy.OPTIONAL).count());
    }

    @Test
    void parcelSeedsReserveBothEstimatedRadiiBeforeApplyingGap() {
        List<LandUseSeedGroup> parcels = compile(d6Plan()).resolution().seedGroups().stream()
                .filter(group -> group.layerRole() == LandUseSeedGroup.LayerRole.LANDSCAPE).toList();

        assertEquals(7, parcels.size());
    }

    @Test
    void freeStandingOptionalLandscapeIsAdmittedAfterRequiredReservation() {
        CityBlueprint source = blueprint();
        CityBlueprint.Landscape first = source.outdoorPlan().landscapes().get(0);
        CityBlueprint.Landscape second = new CityBlueprint.Landscape("orchard", first.landscapeProfileRef(),
                CityBlueprint.LandscapePurpose.AMBIENT, CityBlueprint.LandscapeOriginMode.FREE_STANDING,
                null, CityBlueprint.LandscapePlacementDomain.URBAN_RESIDUAL, 1,
                2, first.preferredPatchRefs(), first.terrainPolicy(), false,
                first.fillSelection());
        CityBlueprint.OutdoorPlan outdoor = new CityBlueprint.OutdoorPlan(source.outdoorPlan().mode(),
                source.outdoorPlan().envelopeProfile(), source.outdoorPlan().foundationProfileRef(),
                source.outdoorPlan().spatialGrounds(), List.of(first, second));
        CityBlueprint expanded = new CityBlueprint(source.schema(), source.cityId(), source.sourceD3Ref(),
                source.catalogSnapshotRef(), source.generationSeed(), source.designIntent(), source.styleProfile(),
                source.groups(), source.arrayCompositions(), source.relations(), source.roadProfile(),
                source.surfaceDetailProfile(), outdoor);

        JsonObject d6 = d6Plan();
        List<LandUseSeedGroup> parcels = new CityOutdoorBlueprintCompiler()
                .compile(expanded, d6, terrain(), catalog(), capacity(expanded, d6)).resolution().seedGroups().stream()
                .filter(group -> group.layerRole() == LandUseSeedGroup.LayerRole.LANDSCAPE).toList();

        assertEquals(9, parcels.size());
        assertEquals(2, parcels.stream().filter(group -> group.admissionPolicy()
                == LandUseSeedGroup.AdmissionPolicy.OPTIONAL).count());
    }

    @Test
    void freeStandingOptionalLandscapeKeepsEarlierParcelWhenLaterSeedIsUnavailable() {
        CityBlueprint source = blueprint();
        CityBlueprint.Landscape first = source.outdoorPlan().landscapes().get(0);
        CityBlueprint.Landscape optional = new CityBlueprint.Landscape("orchard", first.landscapeProfileRef(),
                CityBlueprint.LandscapePurpose.AMBIENT, CityBlueprint.LandscapeOriginMode.FREE_STANDING,
                null, CityBlueprint.LandscapePlacementDomain.URBAN_RESIDUAL, 1,
                2, first.preferredPatchRefs(), first.terrainPolicy(), false,
                first.fillSelection());
        CityBlueprint.OutdoorPlan outdoor = new CityBlueprint.OutdoorPlan(source.outdoorPlan().mode(),
                source.outdoorPlan().envelopeProfile(), source.outdoorPlan().foundationProfileRef(),
                source.outdoorPlan().spatialGrounds(), List.of(first, optional));
        CityBlueprint expanded = new CityBlueprint(source.schema(), source.cityId(), source.sourceD3Ref(),
                source.catalogSnapshotRef(), source.generationSeed(), source.designIntent(), source.styleProfile(),
                source.groups(), source.arrayCompositions(), source.relations(), source.roadProfile(),
                source.surfaceDetailProfile(), outdoor);
        JsonObject d6 = d6Plan();
        JsonObject capacity = capacity(expanded, d6);
        CityOutdoorBlueprintCompiler compiler = new CityOutdoorBlueprintCompiler();
        CityOutdoorBlueprintCompiler.Result open = compiler.compile(expanded, d6, terrain(), catalog(), capacity);
        BlockPoint firstOptionalSeed = open.resolution().seedGroups().stream()
                .filter(group -> group.admissionPolicy() == LandUseSeedGroup.AdmissionPolicy.OPTIONAL)
                .findFirst().orElseThrow().seedPoints().get(0);
        LandUseTerrainField fullTerrain = terrain();
        LandUseTerrainField.Cell onlyAvailableCell = fullTerrain.cells().stream()
                .filter(cell -> cell.contains(firstOptionalSeed.x(), firstOptionalSeed.z()))
                .findFirst().orElseThrow();
        LandUseTerrainField constrained = new LandUseTerrainField(LandUseTerrainField.SCHEMA, "city",
                fullTerrain.planningBounds(), fullTerrain.cellStepBlocks(), List.of(onlyAvailableCell));

        CityOutdoorBlueprintCompiler.Result reduced = compiler.compile(
                expanded, d6, constrained, catalog(), capacity);
        List<LandUseSeedGroup> optionalParcels = reduced.resolution().seedGroups().stream()
                .filter(group -> group.admissionPolicy() == LandUseSeedGroup.AdmissionPolicy.OPTIONAL).toList();

        assertEquals(1, optionalParcels.size());
        assertTrue(optionalParcels.get(0).groupId().endsWith("::parcel_01"));
        assertTrue(reduced.resolution().warnings().stream().anyMatch(warning ->
                warning.equals("skipped_insufficient_space:orchard::instance_01::parcel_02")));
    }

    private static void assertParcelRadiiDoNotOverlap(List<LandUseSeedGroup> parcels) {
        for (int left = 0; left < parcels.size(); left++) {
            LandUseSeedGroup a = parcels.get(left);
            int aRadius = Math.max(1, (int) Math.ceil(Math.sqrt(a.preferredAreaBlocks()) / 2.0));
            for (int right = left + 1; right < parcels.size(); right++) {
                LandUseSeedGroup b = parcels.get(right);
                int bRadius = Math.max(1, (int) Math.ceil(Math.sqrt(b.preferredAreaBlocks()) / 2.0));
                long dx = (long) a.seedPoints().get(0).x() - b.seedPoints().get(0).x();
                long dz = (long) a.seedPoints().get(0).z() - b.seedPoints().get(0).z();
                long minimumDistance = (long) aRadius + bRadius;
                assertTrue(dx * dx + dz * dz >= minimumDistance * minimumDistance,
                        () -> a.groupId() + " and " + b.groupId() + " overlap their estimated parcel radii");
            }
        }
    }

    @Test
    void stableInputsFreezeParcelIdsSeedsAndResolvedFoundationRadius() {
        CityOutdoorBlueprintCompiler compiler = new CityOutdoorBlueprintCompiler();
        JsonObject d6 = d6Plan();
        JsonObject capacity = capacity(blueprint(), d6);
        CityOutdoorBlueprintCompiler.Result first = compiler.compile(blueprint(), d6, terrain(), catalog(), capacity);
        CityOutdoorBlueprintCompiler.Result second = compiler.compile(blueprint(), d6, terrain(), catalog(), capacity);

        assertEquals(first.intentPlan(), second.intentPlan());
        assertEquals(first.resolution().seedGroups(), second.resolution().seedGroups());
        assertEquals(first.intentPlan().envelope().closeRadiusBlocks(),
                second.intentPlan().envelope().closeRadiusBlocks());
        assertTrue(first.intentPlan().envelope().closeRadiusBlocks() >= 8);
        assertFalse(first.intentPlan().planHash().isBlank());
    }

    @Test
    void requiredLandscapeUsesTheSeedFrozenByTheCapacityReservation() {
        JsonObject d6 = d6Plan();
        JsonObject capacity = capacity(blueprint(), d6);
        JsonObject reservation = capacity.getAsJsonArray("instances").get(0).getAsJsonObject()
                .getAsJsonArray("parcelReservations").get(0).getAsJsonObject();
        String parcelId = reservation.get("parcelId").getAsString();
        JsonObject frozenSeed = reservation.getAsJsonObject("seed");

        LandUseSeedGroup parcel = new CityOutdoorBlueprintCompiler()
                .compile(blueprint(), d6, terrain(), catalog(), capacity)
                .resolution().seedGroups().stream()
                .filter(group -> parcelId.equals(group.groupId()))
                .findFirst().orElseThrow();

        assertEquals(new BlockPoint(frozenSeed.get("x").getAsInt(), frozenSeed.get("z").getAsInt()),
                parcel.seedPoints().get(0));
    }

    @Test
    void landscapeOwnerCanBeTheExactAuthoredBuildingCommittedDuringLaterGrowth() {
        for (String phase : List.of("fill", "connectivity_growth", "percentage_growth")) {
            JsonObject d6 = d6Plan();
            d6.getAsJsonArray("plannedWorldgenStructures").get(1).getAsJsonObject()
                    .addProperty("blueprintPlacementPhase", phase);
            JsonObject frozen = capacity(blueprint(), d6);
            assertEquals("farm_a", frozen.getAsJsonArray("instances").get(0).getAsJsonObject()
                    .get("ownerAnchorId").getAsString());
            assertDoesNotThrow(() -> new CityOutdoorBlueprintCompiler()
                    .compile(blueprint(), d6, terrain(), catalog(), frozen));
            JsonObject moved = d6.deepCopy();
            moved.getAsJsonArray("plannedWorldgenStructures").get(1).getAsJsonObject()
                    .getAsJsonObject("lockedActualFootprint").addProperty("minX", 39);
            assertTrue(assertThrows(IllegalArgumentException.class, () -> new CityOutdoorBlueprintCompiler()
                    .compile(blueprint(), moved, terrain(), catalog(), frozen))
                    .getMessage().contains("OWNER_DRIFT"));
        }
    }

    @Test
    void landscapeOwnerChoiceIsStableAndPrefersRequiredOverLaterExactInstances() {
        JsonObject d6 = d6Plan();
        JsonObject extra = structure("aaa_later", "farm_group", "fill", 64, 52, 69, 57);
        extra.addProperty("blueprintStructureRef", "farmhouse");
        d6.getAsJsonArray("plannedWorldgenStructures").add(extra);
        JsonObject frozen = capacity(blueprint(), d6);
        assertEquals("farm_a", frozen.getAsJsonArray("instances").get(0).getAsJsonObject()
                .get("ownerAnchorId").getAsString());
        assertDoesNotThrow(() -> new CityOutdoorBlueprintCompiler()
                .compile(blueprint(), d6, terrain(), catalog(), frozen));
    }

    @Test
    void missingD6BlueprintPlacementPhaseFailsFormally() {
        JsonObject d6 = d6Plan();
        d6.getAsJsonArray("plannedWorldgenStructures").get(0).getAsJsonObject()
                .remove("blueprintPlacementPhase");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new CityOutdoorBlueprintCompiler().compile(blueprint(), d6, terrain(), catalog(),
                        capacity(blueprint(), d6)));

        assertTrue(exception.getMessage().startsWith("CITY_OUTDOOR_D6_BLUEPRINT_PHASE_MISSING:core"));
    }

    @Test
    void acceptsPercentageGrowthProducedByBlueprintCompiler() {
        JsonObject d6 = d6Plan();
        d6.getAsJsonArray("plannedWorldgenStructures").add(
                structure("farm_percentage", "farm_group", "percentage_growth", 64, 52, 69, 57));

        assertDoesNotThrow(() -> new CityOutdoorBlueprintCompiler().compile(
                blueprint(), d6, terrain(), catalog(), capacity(blueprint(), d6)));
    }

    @Test
    void requiredLandscapeRejectsMissingOrTamperedD4CapacityPlan() {
        JsonObject d6 = d6Plan();
        CityOutdoorBlueprintCompiler compiler = new CityOutdoorBlueprintCompiler();

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> compiler.compile(blueprint(), d6, terrain(), catalog()));
        assertEquals("CITY_OUTDOOR_REQUIRED_LANDSCAPE_CAPACITY_MISSING", missing.getMessage());

        JsonObject tampered = capacity(blueprint(), d6);
        tampered.getAsJsonArray("instances").get(0).getAsJsonObject()
                .addProperty("capacityCandidateId", "tampered");
        IllegalArgumentException drift = assertThrows(IllegalArgumentException.class,
                () -> compiler.compile(blueprint(), d6, terrain(), catalog(), tampered));
        assertEquals("CITY_OUTDOOR_LANDSCAPE_CAPACITY_HASH_MISMATCH", drift.getMessage());
    }

    @Test
    void requiredLandscapeNoTerrainFitRemainsWarningThroughOutdoorCompile() {
        JsonObject d6 = d6Plan();
        JsonObject capacity = capacity(blueprint(), d6);
        capacity.add("instances", new JsonArray());
        JsonObject warning = new JsonObject();
        warning.addProperty("reasonCode", "REQUIRED_LANDSCAPE_NO_TERRAIN_FIT_WARNING");
        warning.addProperty("landscapeId", "outer_fields");
        warning.addProperty("instanceOrdinal", 0);
        warning.addProperty("message", "No terrain-gated cell was available.");
        JsonArray warnings = new JsonArray();
        warnings.add(warning);
        capacity.add("warnings", warnings);
        CityLandscapeCapacityReservationPlanner.refreshPlanHash(capacity);

        CityOutdoorBlueprintCompiler.Result result = new CityOutdoorBlueprintCompiler()
                .compile(blueprint(), d6, terrain(), catalog(), capacity);

        assertTrue(result.resolution().seedGroups().stream().noneMatch(group ->
                group.layerRole() == LandUseSeedGroup.LayerRole.LANDSCAPE));
        assertTrue(result.resolution().warnings().contains(
                "REQUIRED_LANDSCAPE_NO_TERRAIN_FIT_WARNING:outer_fields:0"));
    }

    @Test
    void terrainReducedSingleCellLandscapeKeepsPrimaryFillStage() {
        JsonObject d6 = d6Plan();
        JsonObject capacity = capacity(blueprint(), d6);
        JsonObject instance = capacity.getAsJsonArray("instances").get(0).getAsJsonObject();
        JsonObject parcel = instance.getAsJsonArray("parcelReservations").get(0).getAsJsonObject();
        JsonObject seed = parcel.getAsJsonObject("seed");
        JsonObject span = new JsonObject();
        span.addProperty("z", seed.get("z").getAsInt());
        span.addProperty("minX", seed.get("x").getAsInt());
        span.addProperty("maxX", seed.get("x").getAsInt());
        JsonArray spans = new JsonArray();
        spans.add(span);
        parcel.add("reservationSpans", spans.deepCopy());
        parcel.addProperty("actualAreaBlocks", 1);
        JsonArray parcels = new JsonArray();
        parcels.add(parcel);
        instance.add("parcelReservations", parcels);
        instance.add("reservationSpans", spans);
        instance.addProperty("parcelCount", 1);
        instance.addProperty("actualAreaBlocks", 1);
        instance.addProperty("capacityStatus", "terrain_reduced");
        JsonObject warning = new JsonObject();
        warning.addProperty("reasonCode", "REQUIRED_LANDSCAPE_TERRAIN_REDUCED_WARNING");
        warning.addProperty("landscapeId", "outer_fields");
        warning.addProperty("instanceOrdinal", 0);
        warning.addProperty("message", "Terrain reduced requested capacity.");
        JsonArray warnings = new JsonArray();
        warnings.add(warning);
        capacity.add("warnings", warnings);
        CityLandscapeCapacityReservationPlanner.refreshPlanHash(capacity);

        CityOutdoorBlueprintCompiler.Result result = new CityOutdoorBlueprintCompiler()
                .compile(blueprint(), d6, terrain(), catalog(), capacity);
        LandUseSeedGroup landscape = result.resolution().seedGroups().stream()
                .filter(group -> group.layerRole() == LandUseSeedGroup.LayerRole.LANDSCAPE)
                .findFirst().orElseThrow();

        assertEquals(1, landscape.maxAreaBlocks());
        assertEquals(1, landscape.landscapeFillProgram().roles().size());
        assertEquals("CULTIVATED", landscape.landscapeFillProgram().roles().get(0).roleRef());
        assertEquals(1.0, landscape.landscapeFillProgram().roles().get(0).targetShare(), 1.0e-9);
    }

    @Test
    void layeredPlanningUsesNoCorridorsAndKeepsOneAreaPerParcelOwner() {
        CityOutdoorBlueprintCompiler.Result compiled = compile(d6Plan());
        assertTrue(compiled.resolution().seedGroups().stream()
                .filter(group -> group.layerRole() == LandUseSeedGroup.LayerRole.LANDSCAPE)
                .allMatch(group -> group.minAreaBlocks() == group.preferredAreaBlocks()
                        && group.preferredAreaBlocks() == group.maxAreaBlocks()
                        && group.maxAreaBlocks() == compiled.resolution().landscapeCapacityDomains()
                        .get(group.groupId()).size()));
        LandUsePlanningService.Result planned = new LandUsePlanningService().plan("city", compiled.resolution(),
                d5Corridor(), terrain(), compiled.residualConfig());

        assertTrue(planned.plan().corridorExclusions().isEmpty());
        assertEquals("city_land_use_planning_trace",
                planned.trace().get("schema").getAsString());
        assertTrue(planned.trace().get("foundationResolvedCloseRadiusBlocks").getAsInt() >= 8);
        assertTrue(planned.trace().get("foundationComponentCount").getAsInt() >= 1);
        List<LandUseAreaPlan.Area> foundationAreas = planned.plan().areas().stream()
                .filter(area -> area.sourceGroupIds().contains("city::foundation")).toList();
        List<LandUseAreaPlan.Area> parcelAreas = planned.plan().areas().stream()
                .filter(area -> area.sourceGroupIds().stream().anyMatch(id -> id.startsWith("outer_fields::")))
                .toList();
        assertEquals(1, foundationAreas.size());
        int skippedOptional = planned.quality().get("skippedOptionalLandscapeCount").getAsInt();
        assertEquals(0, skippedOptional);
        assertEquals(7, parcelAreas.size());
        assertTrue(parcelAreas.stream().allMatch(area -> area.sourceGroupIds().size() == 1));
        assertEquals(7, parcelAreas.stream().flatMap(area -> area.sourceGroupIds().stream()).distinct().count());
        assertEquals(7, planned.surfacePrintPlan().areas().stream()
                .filter(area -> area.recipe() instanceof
                        com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan
                                .RelayRegionGrowthRecipe)
                .count());
        assertTrue(planned.plan().warnings().stream().noneMatch(warning -> warning.startsWith(
                "CITY_LANDSCAPE_OPTIONAL_SKIPPED_INSUFFICIENT_SPACE:")));
        assertTrue(planned.plan().warnings().stream().noneMatch(warning -> warning.contains(
                "REQUIRED_PARCEL_SKIPPED")));
        for (LandUseSeedGroup parcel : compiled.resolution().seedGroups().stream()
                .filter(group -> group.layerRole() == LandUseSeedGroup.LayerRole.LANDSCAPE).toList()) {
            JsonObject parcelTrace = planned.trace().getAsJsonArray("seedGroups").asList().stream()
                    .map(value -> value.getAsJsonObject())
                    .filter(value -> value.get("groupId").getAsString().equals(parcel.groupId()))
                    .findFirst().orElseThrow();
            assertEquals(parcel.preferredAreaBlocks(), parcelTrace.get("claimedAreaBlocks").getAsInt());
            JsonObject origin = parcelTrace.getAsJsonObject("parcelExpansionOrigin");
            assertEquals(parcelTrace.getAsJsonArray("effectiveSeedPoints").get(0), origin.get("start"));
            if (parcel.groupId().endsWith("::parcel_01")) {
                assertEquals("ROOT_SOURCE", origin.get("kind").getAsString());
                assertTrue(origin.get("sourceFrontier").isJsonNull());
            } else {
                assertEquals("PARENT_PARCEL_INTERFACE", origin.get("kind").getAsString());
                assertFalse(origin.get("parentParcelId").getAsString().isBlank());
                assertFalse(origin.get("sourceFrontier").isJsonNull());
            }
        }
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
        CityBlueprint preserve = new CityBlueprint(source.schema(), source.cityId(), source.sourceD3Ref(),
                source.catalogSnapshotRef(), source.generationSeed(), source.designIntent(), source.styleProfile(),
                source.groups(), source.arrayCompositions(), source.relations(), source.roadProfile(),
                source.surfaceDetailProfile(),
                new CityBlueprint.OutdoorPlan(CityBlueprint.OutdoorMode.PRESERVE,
                        CityBlueprint.EnvelopeProfile.BALANCED, "foundation:test", List.of(), List.of()));

        CityOutdoorBlueprintCompiler.Result result = new CityOutdoorBlueprintCompiler().compile(preserve,
                d6Plan(), terrain(), catalog());

        assertTrue(result.resolution().seedGroups().isEmpty());
        assertEquals("city_outdoor_intent_plan", result.intentPlan().schema());
    }

    private static CityOutdoorBlueprintCompiler.Result compile(JsonObject d6) {
        return new CityOutdoorBlueprintCompiler().compile(blueprint(), d6, terrain(), catalog(),
                capacity(blueprint(), d6));
    }

    private static JsonObject capacity(CityBlueprint blueprint, JsonObject d6) {
        CityLandscapeCapacityReservationPlanner.Result result = new CityLandscapeCapacityReservationPlanner()
                .plan(blueprint, catalog(), terrain(), d6.getAsJsonArray("plannedWorldgenStructures"));
        assertTrue(result.ok(), result.plan().toString());
        return result.plan();
    }

    private static CityBlueprint blueprint() {
        CityBlueprint.Landscape landscape = new CityBlueprint.Landscape("outer_fields", "landscape:farmland",
                CityBlueprint.LandscapePurpose.FUNCTIONAL, CityBlueprint.LandscapeOriginMode.ATTACHED,
                new CityBlueprint.LandscapeOwner("farm_group", "farmhouse"), null, 1, 7,
                List.of("farm_patch"), CityBlueprint.TerrainPolicy.CONFORM, true,
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
        return new CityBlueprint(CityBlueprint.SCHEMA, "city",
                new CityBlueprint.ArtifactRef("d3.json", "d3", "sha256:" + "1".repeat(64)),
                new CityBlueprint.ArtifactRef("catalog.json", "catalog", "sha256:" + "2".repeat(64)),
                42, new CityBlueprint.DesignIntent("town", "green", List.of("agriculture")),
                new CityBlueprint.ProfileRef("style:test"), groups(), List.of(), List.of(),
                new CityBlueprint.ProfileRef("road:test"), new CityBlueprint.ProfileRef("surface:test"), outdoor);
    }

    private static List<CityBlueprint.Group> groups() {
        return List.of(group("core_group", CityBlueprint.GroupPriority.CORE),
                group("farm_group", CityBlueprint.GroupPriority.STANDARD));
    }

    private static CityBlueprint.Group group(String id, CityBlueprint.GroupPriority priority) {
        return new CityBlueprint.Group(id, CityBlueprint.GroupKind.STRUCTURE, List.of(),
                CityBlueprint.PreferredPatchZone.CENTER, null, id, priority, CityBlueprint.ExtentClass.MEDIUM,
                CityBlueprint.DensityClass.BALANCED, "algorithm:test", CityBlueprint.TerrainPolicy.BALANCED,
                List.of("farmhouse"), "pool:test", null, "composition:test", List.of());
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
                        new CityBlueprintReferenceCatalog.ParcelStyle(1, 10, 192, 240, 4));
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
                Map.of(), Set.of(), Set.of(), Set.of(), Set.of(), rules,
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
        item.addProperty("blueprintStructureRef", phase.equalsIgnoreCase("required")
                && groupId.equals("farm_group") ? "farmhouse" : "other");
        JsonObject footprint = new JsonObject();
        footprint.addProperty("minX", minX);
        footprint.addProperty("minZ", minZ);
        footprint.addProperty("maxX", maxX);
        footprint.addProperty("maxZ", maxZ);
        item.add("lockedActualFootprint", footprint);
        item.add("lockedCollisionEnvelope", footprint.deepCopy());
        return item;
    }

    private static JsonObject bounds(int minX, int minZ, int maxX, int maxZ) {
        JsonObject value = new JsonObject();
        value.addProperty("minX", minX);
        value.addProperty("minZ", minZ);
        value.addProperty("maxX", maxX);
        value.addProperty("maxZ", maxZ);
        return value;
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
        return new LandUseTerrainField(LandUseTerrainField.SCHEMA, "city",
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
