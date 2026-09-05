package com.rinsing.geomantia.systems.city.application.outdoor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.CityBlueprintCodec;
import com.rinsing.geomantia.systems.city.application.CityBlueprintReferenceCatalog;
import com.rinsing.geomantia.systems.city.application.CityLandscapeCapacityReservationPlanner;
import com.rinsing.geomantia.systems.city.application.landuse.LandUseSourceResolver;
import com.rinsing.geomantia.systems.city.application.landuse.LandUseTerrainFieldCodec;
import com.rinsing.geomantia.systems.city.algorithm.landuse.CityFoundationPlanner;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSeedGroup;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandscapeFillProgram;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSurfaceSettings;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.landuse.BoundaryPolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.CardinalDirection;
import com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseRule;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeSet;

/** Compiles the coordinate-free outdoor decision against D6 and D3 facts. */
public final class CityOutdoorBlueprintCompiler {
    public Result compile(CityBlueprint blueprint,
                          JsonObject structureMaterializationPlan,
                          LandUseTerrainField terrain,
                          CityBlueprintReferenceCatalog catalog) {
        return compile(blueprint, structureMaterializationPlan, terrain, catalog, null);
    }

    public Result compile(CityBlueprint blueprint,
                          JsonObject structureMaterializationPlan,
                          LandUseTerrainField terrain,
                          CityBlueprintReferenceCatalog catalog,
                          JsonObject landscapeCapacityReservationPlan) {
        if (blueprint == null || blueprint.outdoorPlan() == null) {
            throw new IllegalArgumentException("CITY_OUTDOOR_BLUEPRINT_REQUIRED");
        }
        if (structureMaterializationPlan == null || !booleanValue(structureMaterializationPlan, "locked", false)) {
            throw new IllegalArgumentException("CITY_OUTDOOR_REQUIRES_LOCKED_D6_PLAN");
        }
        if (terrain == null || catalog == null) throw new IllegalArgumentException("CITY_OUTDOOR_INPUT_REQUIRED");
        String cityId = requiredString(structureMaterializationPlan, "cityId");
        if (!cityId.equals(blueprint.cityId()) || !cityId.equals(terrain.cityId())) {
            throw new IllegalArgumentException("CITY_OUTDOOR_CITY_ID_MISMATCH");
        }
        SourceHashes sourceHashes = sourceHashes(blueprint, structureMaterializationPlan, terrain, catalog);
        if (blueprint.outdoorPlan().mode() == CityBlueprint.OutdoorMode.PRESERVE) {
            CityOutdoorIntentPlan intent = preserveIntent(blueprint, catalog, sourceHashes);
            return new Result(new LandUseSourceResolver.Resolution(List.of(), List.of(), List.of(),
                    Long.toUnsignedString(blueprint.generationSeed())), CityUrbanResidualResolver.Config.disabled(),
                    intent);
        }

        Map<String, List<AnchorData>> anchorsByGroup = readAnchors(structureMaterializationPlan);
        List<LandUseSourceResolver.RoadBand> roadBands = roadBands(structureMaterializationPlan);
        List<String> outdoorWarnings = new ArrayList<>();
        List<LandUseSourceResolver.GreenParcelSpec> greenParcels = greenParcels(
                blueprint, anchorsByGroup, catalog, outdoorWarnings);
        List<LandUseSourceResolver.OverflowZoneSpec> overflowZones = overflowZones(
                structureMaterializationPlan);
        CapacityReservation capacityReservation = capacityReservation(blueprint, anchorsByGroup,
                landscapeCapacityReservationPlan);
        Map<String, Set<BlockPoint>> capacityDomains = capacityReservation.domains();
        outdoorWarnings.addAll(capacityReservation.warnings());
        List<String> spatialGroupIds = blueprint.outdoorPlan().spatialGrounds().stream()
                .map(CityBlueprint.SpatialGround::sourceGroupId).distinct().sorted().toList();
        List<AnchorData> foundationAnchors = requiredGroups(anchorsByGroup, spatialGroupIds,
                "CITY_OUTDOOR_STRUCTURE_GROUP_UNKNOWN");
        List<BlockBounds> structureFootprints = foundationAnchors.stream()
                .map(AnchorData::footprint).distinct().toList();
        List<BlockBounds> allFootprints = new ArrayList<>(structureFootprints);
        streetBandFootprints(structureMaterializationPlan).stream()
                .filter(footprint -> !allFootprints.contains(footprint))
                .forEach(allFootprints::add);
        List<String> allAnchorIds = foundationAnchors.stream().map(AnchorData::anchorId).sorted().toList();
        List<LandUseAreaPlan.GateSlot> foundationGates =
                foundationAnchors.stream().flatMap(anchor -> anchor.gates().stream())
                        .sorted(Comparator.comparing(LandUseAreaPlan.GateSlot::gateId))
                        .toList();
        CityBlueprintReferenceCatalog.FoundationProfile foundationProfile = catalog.foundationProfiles().get(
                blueprint.outdoorPlan().foundationProfileRef());
        if (foundationProfile == null) {
            throw new IllegalArgumentException("CITY_OUTDOOR_FOUNDATION_PROFILE_UNKNOWN:"
                    + blueprint.outdoorPlan().foundationProfileRef());
        }
        LandUseRule foundationRule = foundationRule(requireRule(catalog, foundationProfile.landUseRuleRef()));
        CityBlueprintReferenceCatalog.SurfaceRecipe foundationRecipe = requireRecipe(catalog,
                foundationProfile.surfaceRecipeRef());
        LandUseSeedGroup.FoundationSettings foundationSettings = new LandUseSeedGroup.FoundationSettings(
                foundationProfile.structureMarginBlocks(), foundationProfile.closeRadiusBlocks(),
                foundationProfile.maxJoinDistanceBlocks());
        CityFoundationPlanner.Plan foundationPlan = new CityFoundationPlanner().plan(terrain.planningBounds(),
                terrain, allFootprints, foundationSettings);
        String foundationGroupId = cityId + "::foundation";
        List<BlockPoint> foundationSeeds = foundationAnchors.stream().map(anchor -> center(anchor.footprint()))
                .distinct().sorted(POINT_ORDER).toList();
        int foundationArea = foundationPlan.claims().size();
        AreaBudget foundationBudget = new AreaBudget(foundationArea, foundationArea, foundationArea);
        LandUseSeedGroup.GrowthRegion foundationRegion = new LandUseSeedGroup.GrowthRegion(foundationGroupId,
                allAnchorIds, foundationSeeds, foundationArea, foundationArea, foundationArea);
        List<LandUseSeedGroup> groups = new ArrayList<>();
        groups.add(new LandUseSeedGroup(foundationGroupId, foundationRule,
                surfaceSettings(foundationRule, foundationRecipe, false), allAnchorIds, structureFootprints,
                foundationSeeds, foundationGates, foundationArea, foundationArea, foundationArea,
                foundationRule.actionBudget(), foundationRule.competitionWeight(), List.of(foundationRegion),
                LandUseSeedGroup.GrowthBias.neutral(), LandUseSeedGroup.TerrainBias.BALANCED, List.of(),
                LandUseSeedGroup.LayerRole.FOUNDATION, foundationSettings));
        List<CityOutdoorIntentPlan.SourceIntent> sourceIntents = new ArrayList<>();
        sourceIntents.add(sourceIntent(foundationGroupId, CityOutdoorIntentPlan.SourceKind.FOUNDATION,
                foundationProfile.foundationProfileRef(), foundationRule.ruleRef(),
                foundationRecipe.surfaceRecipeRef(), CityBlueprint.OutdoorMembership.URBAN, null,
                null, null, null, CityBlueprint.TerrainPolicy.BALANCED, spatialGroupIds, allAnchorIds,
                List.of(), foundationBudget, CityBlueprint.GrowthBias.BALANCED, null, foundationSeeds, true));

        List<ParcelSpec> occupiedLandscapeParcels = new ArrayList<>();
        Map<String, String> resolvedParentParcelIds = new LinkedHashMap<>(
                capacityReservation.parentParcelIds());
        for (CityBlueprint.Landscape landscape : blueprint.outdoorPlan().landscapes()) {
            CityBlueprintReferenceCatalog.LandscapeProfile profile = catalog.landscapeProfiles().get(
                    landscape.landscapeProfileRef());
            if (profile == null) {
                throw new IllegalArgumentException("CITY_OUTDOOR_LANDSCAPE_PROFILE_UNKNOWN:"
                        + landscape.landscapeProfileRef());
            }
            LandUseRule parcelRule = independentParcelRule(requireRule(catalog, profile.landUseRuleRef()));
            CityBlueprintReferenceCatalog.SurfaceRecipe recipe = requireRecipe(catalog, profile.surfaceRecipeRef());
            List<AnchorData> attached = landscape.originMode() == CityBlueprint.LandscapeOriginMode.ATTACHED
                    ? List.of(requiredOwnerAnchor(anchorsByGroup, landscape)) : List.of();
            List<ParcelSpec> parcels = landscapeParcels(blueprint, landscape, profile, parcelRule, attached,
                    anchorsByGroup, terrain, allFootprints, occupiedLandscapeParcels, outdoorWarnings,
                    capacityDomains, capacityReservation.parentParcelIds(), capacityReservation.seeds(),
                    capacityReservation.planPresent());
            for (ParcelSpec parcel : parcels) {
                resolvedParentParcelIds.put(parcel.parcelId(), parcel.parentParcelId());
                List<String> anchorIds = parcel.anchor() == null ? List.of() : List.of(parcel.anchor().anchorId());
                List<BlockPoint> seeds = List.of(parcel.seed());
                LandscapeFillProgram fillProgram = landscapeFillProgram(blueprint, landscape, profile,
                        parcel, catalog);
                LandUseSurfaceSettings fillSurfaceSettings = surfaceSettings(parcelRule, recipe, false)
                        .forRelayRegionGrowth();
                LandUseSeedGroup.GrowthRegion region = new LandUseSeedGroup.GrowthRegion(parcel.parcelId(),
                        anchorIds, seeds, parcel.budget().min(), parcel.budget().preferred(), parcel.budget().max());
                groups.add(new LandUseSeedGroup(parcel.parcelId(), parcelRule,
                        fillSurfaceSettings, anchorIds, allFootprints, seeds, List.of(),
                        parcel.budget().min(), parcel.budget().preferred(), parcel.budget().max(),
                        parcelRule.actionBudget(), parcelRule.competitionWeight(), List.of(region), parcel.bias(),
                        LandUseSeedGroup.TerrainBias.valueOf(landscape.terrainPolicy().name()),
                        landscape.preferredPatchRefs(), LandUseSeedGroup.LayerRole.LANDSCAPE, null, fillProgram,
                        parcel.admissionPolicy()));
                sourceIntents.add(sourceIntent(parcel.parcelId(), CityOutdoorIntentPlan.SourceKind.LANDSCAPE,
                        landscape.landscapeProfileRef(), parcelRule.ruleRef(), recipe.surfaceRecipeRef(),
                        profile.membership(), null, null, null,
                         null, landscape.terrainPolicy(), landscape.owner() == null ? List.of()
                                : List.of(landscape.owner().groupId()),
                         anchorIds, landscape.preferredPatchRefs(), parcel.budget(), parcel.blueprintBias(),
                         parcel.referencePoint(), seeds,
                         parcel.admissionPolicy() == LandUseSeedGroup.AdmissionPolicy.REQUIRED,
                         parcel.landscapeInstanceId(), parcel.parcelId(), parcel.parentParcelId(),
                         parcel.rootSourceId(), parcel.seed()));
            }
            occupiedLandscapeParcels.addAll(parcels);
        }

        groups.sort(Comparator.comparing(LandUseSeedGroup::groupId));
        sourceIntents.sort(Comparator.comparing(CityOutdoorIntentPlan.SourceIntent::sourceId));
        Set<String> urbanGroupIds = Set.of(foundationGroupId);
        CityOutdoorIntentPlan intent = intent(blueprint, catalog, sourceHashes, sourceIntents, urbanGroupIds,
                foundationPlan.resolvedCloseRadiusBlocks());
        return new Result(new LandUseSourceResolver.Resolution(groups, List.of(), outdoorWarnings,
                Long.toUnsignedString(blueprint.generationSeed()), capacityDomains,
                resolvedParentParcelIds, roadBands, greenParcels, overflowZones),
                CityUrbanResidualResolver.Config.disabled(),
                intent);
    }

    private static LandscapeFillProgram landscapeFillProgram(
            CityBlueprint blueprint,
            CityBlueprint.Landscape landscape,
            CityBlueprintReferenceCatalog.LandscapeProfile landscapeProfile,
            ParcelSpec parcel,
            CityBlueprintReferenceCatalog catalog) {
        CityBlueprint.FillVariant variant = selectFillVariant(landscape.fillSelection().variants(),
                blueprint.cityId() + '|' + landscape.landscapeId() + '|' + parcel.parcelId() + "|fill");
        CityBlueprintReferenceCatalog.LandscapeFillProfile fillProfile = catalog.landscapeFillProfiles().get(
                variant.fillProfileRef());
        if (fillProfile == null) {
            throw new IllegalArgumentException("CITY_OUTDOOR_FILL_PROFILE_UNKNOWN:" + variant.fillProfileRef());
        }
        if (fillProfile.algorithm() != CityBlueprintReferenceCatalog.FillAlgorithm.SINGLE_SOURCE_REGION_RELAY
                || !fillProfile.compatibleLandscapeTypes().contains(landscapeProfile.landscapeType())) {
            throw new IllegalArgumentException("CITY_OUTDOOR_FILL_PROFILE_INCOMPATIBLE:"
                    + landscape.landscapeId() + ':' + variant.fillProfileRef());
        }
        Set<String> selectedRoles = new HashSet<>();
        for (CityBlueprint.RoleShare share : variant.roleShares()) {
            selectedRoles.add(share.roleRef());
        }
        if (!selectedRoles.equals(fillProfile.roles().keySet())) {
            throw new IllegalArgumentException("CITY_OUTDOOR_FILL_ROLE_SET_MISMATCH:" + variant.fillProfileRef());
        }
        List<LandscapeFillProgram.RoleDefinition> requestedRoles = variant.roleShares().stream()
                .map(share -> {
                    CityBlueprintReferenceCatalog.FillRole role = fillProfile.roles().get(share.roleRef());
                    return new LandscapeFillProgram.RoleDefinition(role.roleRef(),
                            LandscapeFillProgram.MaterialRole.valueOf(role.materialRole().name()),
                            LandscapeFillProgram.GrowthForm.valueOf(share.growthForm().name()),
                            share.targetShare());
                }).toList();
        int stageLimit = Math.max(1, Math.min(requestedRoles.size(), parcel.budget().max()));
        List<LandscapeFillProgram.RoleDefinition> admittedRoles = requestedRoles.subList(0, stageLimit);
        double admittedShare = admittedRoles.stream()
                .mapToDouble(LandscapeFillProgram.RoleDefinition::targetShare).sum();
        List<LandscapeFillProgram.RoleDefinition> roles = admittedRoles.stream()
                .map(role -> new LandscapeFillProgram.RoleDefinition(role.roleRef(), role.materialRole(),
                        role.growthForm(), role.targetShare() / admittedShare))
                .toList();
        List<LandscapeFillProgram.ContentWeight> contentWeights = variant.contentWeights().stream()
                .sorted(Comparator.comparing(CityBlueprint.ContentWeight::contentRef))
                .map(content -> new LandscapeFillProgram.ContentWeight(content.contentRef(), content.weight()))
                .toList();
        long stableSeed = blueprint.generationSeed()
                ^ Long.rotateLeft(Integer.toUnsignedLong(stableHash(parcel.parcelId())), 32)
                ^ Integer.toUnsignedLong(stableHash(variant.fillProfileRef()));
        return new LandscapeFillProgram(fillProfile.fillProfileRef(), fillProfile.primaryRoleRef(),
                roles, contentWeights, stableSeed);
    }

    private static CityBlueprint.FillVariant selectFillVariant(List<CityBlueprint.FillVariant> variants,
                                                                 String stableKey) {
        if (variants == null || variants.isEmpty()) {
            throw new IllegalArgumentException("CITY_OUTDOOR_FILL_VARIANTS_REQUIRED");
        }
        double totalWeight = variants.stream().mapToDouble(CityBlueprint.FillVariant::selectionWeight).sum();
        double ticket = stableUnit(stableKey) * totalWeight;
        double cumulative = 0.0;
        for (CityBlueprint.FillVariant variant : variants) {
            cumulative += variant.selectionWeight();
            if (ticket < cumulative) return variant;
        }
        return variants.get(variants.size() - 1);
    }

    private static CityOutdoorIntentPlan preserveIntent(CityBlueprint blueprint,
                                                        CityBlueprintReferenceCatalog catalog,
                                                        SourceHashes sourceHashes) {
        return new CityOutdoorIntentPlan(CityOutdoorIntentPlan.SCHEMA, blueprint.cityId(),
                CityBlueprint.OutdoorMode.PRESERVE, catalog.landUseRuleCatalog().profileHash(),
                sourceHashes.blueprint(), sourceHashes.d6(), sourceHashes.terrain(), sourceHashes.catalog(),
                "", List.of(),
                new CityOutdoorIntentPlan.EnvelopeIntent(blueprint.outdoorPlan().envelopeProfile(), 0, List.of()),
                residualIntent()).withComputedHash();
    }

    private static CityOutdoorIntentPlan intent(CityBlueprint blueprint,
                                                CityBlueprintReferenceCatalog catalog,
                                                SourceHashes sourceHashes,
                                                List<CityOutdoorIntentPlan.SourceIntent> sources,
                                                Set<String> urbanGroupIds,
                                                int closeRadius) {
        return new CityOutdoorIntentPlan(CityOutdoorIntentPlan.SCHEMA, blueprint.cityId(),
                blueprint.outdoorPlan().mode(), catalog.landUseRuleCatalog().profileHash(),
                sourceHashes.blueprint(), sourceHashes.d6(), sourceHashes.terrain(), sourceHashes.catalog(),
                "", sources,
                new CityOutdoorIntentPlan.EnvelopeIntent(blueprint.outdoorPlan().envelopeProfile(), closeRadius,
                        urbanGroupIds.stream().sorted().toList()),
                residualIntent()).withComputedHash();
    }

    private static CityOutdoorIntentPlan.ResidualIntent residualIntent() {
        CityUrbanSpacePlan.ResidualDisposition absorb = CityUrbanSpacePlan.ResidualDisposition.ABSORB_NEIGHBOR;
        return new CityOutdoorIntentPlan.ResidualIntent(absorb, absorb, absorb, absorb, absorb);
    }

    private static CityOutdoorIntentPlan.SourceIntent sourceIntent(
            String sourceId,
            CityOutdoorIntentPlan.SourceKind sourceKind,
            String profileRef,
            String ruleRef,
            String recipeRef,
            CityBlueprint.OutdoorMembership membership,
            CityBlueprint.ExtentClass extentClass,
            CityBlueprint.OutdoorIntensity intensity,
            CityBlueprint.LandscapeContinuity continuity,
            CityBlueprint.LandscapeGrowthRelation growthRelation,
            CityBlueprint.TerrainPolicy terrainPolicy,
            List<String> sourceGroupIds,
            List<String> sourceAnchorIds,
            List<String> preferredPatchRefs,
            AreaBudget budget,
            CityBlueprint.GrowthBias growthBias,
            BlockPoint reference,
            List<BlockPoint> seeds,
            boolean required) {
        return sourceIntent(sourceId, sourceKind, profileRef, ruleRef, recipeRef, membership,
                extentClass, intensity, continuity, growthRelation, terrainPolicy, sourceGroupIds,
                sourceAnchorIds, preferredPatchRefs, budget, growthBias, reference, seeds, required,
                "", "", "", "", null);
    }

    private static CityOutdoorIntentPlan.SourceIntent sourceIntent(
            String sourceId, CityOutdoorIntentPlan.SourceKind sourceKind, String profileRef,
            String ruleRef, String recipeRef, CityBlueprint.OutdoorMembership membership,
            CityBlueprint.ExtentClass extentClass, CityBlueprint.OutdoorIntensity intensity,
            CityBlueprint.LandscapeContinuity continuity,
            CityBlueprint.LandscapeGrowthRelation growthRelation, CityBlueprint.TerrainPolicy terrainPolicy,
            List<String> sourceGroupIds, List<String> sourceAnchorIds, List<String> preferredPatchRefs,
            AreaBudget budget, CityBlueprint.GrowthBias growthBias, BlockPoint reference,
            List<BlockPoint> seeds, boolean required, String landscapeInstanceId, String parcelId,
            String parentParcelId, String rootSourceId, BlockPoint sourceFrontier) {
        return new CityOutdoorIntentPlan.SourceIntent(sourceId, sourceKind, profileRef, ruleRef, recipeRef,
                membership, extentClass, intensity, continuity, growthRelation, terrainPolicy, sourceGroupIds,
                sourceAnchorIds, preferredPatchRefs, budget.min(), budget.preferred(), budget.max(), growthBias,
                reference, seeds, required, landscapeInstanceId, parcelId, parentParcelId, rootSourceId,
                sourceFrontier);
    }

    private static SourceHashes sourceHashes(CityBlueprint blueprint,
                                             JsonObject d6,
                                             LandUseTerrainField terrain,
                                             CityBlueprintReferenceCatalog catalog) {
        return new SourceHashes(
                sourceHash(new CityBlueprintCodec().write(blueprint)),
                sourceHash(d6),
                sourceHash(new LandUseTerrainFieldCodec().toJson(terrain)),
                sourceHash(catalog.json()));
    }

    private static String sourceHash(JsonElement value) {
        JsonElement canonical = canonical(value == null ? JsonParser.parseString("null") : value);
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static JsonElement canonical(JsonElement value) {
        if (value == null || value.isJsonNull() || value.isJsonPrimitive()) return value.deepCopy();
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            value.getAsJsonArray().forEach(element -> result.add(canonical(element)));
            return result;
        }
        JsonObject result = new JsonObject();
        for (String key : new TreeSet<>(value.getAsJsonObject().keySet())) {
            result.add(key, canonical(value.getAsJsonObject().get(key)));
        }
        return result;
    }

    private static LandUseRule foundationRule(LandUseRule source) {
        return new LandUseRule(source.ruleRef(), source.landUseType(), source.semanticTerms(),
                source.footprintMultiplier(), source.extraAreaBlocks(), source.minAreaBlocks(),
                source.maxAreaBlocks(), source.actionBudget(), source.baseStepCost(), source.slopeCost(),
                source.reliefCost(), source.waterCost(), source.forestAffinity(), source.competitionWeight(),
                false, source.surfacePolicy(), source.vegetationPolicy(), BoundaryPolicy.OPEN);
    }

    private static LandUseRule independentParcelRule(LandUseRule source) {
        return new LandUseRule(source.ruleRef(), source.landUseType(), source.semanticTerms(),
                source.footprintMultiplier(), source.extraAreaBlocks(), source.minAreaBlocks(),
                source.maxAreaBlocks(), source.actionBudget(), source.baseStepCost(), source.slopeCost(),
                source.reliefCost(), source.waterCost(), source.forestAffinity(), source.competitionWeight(),
                false, source.surfacePolicy(), source.vegetationPolicy(), source.boundaryPolicy());
    }

    private static List<ParcelSpec> landscapeParcels(
            CityBlueprint blueprint,
            CityBlueprint.Landscape landscape,
            CityBlueprintReferenceCatalog.LandscapeProfile profile,
            LandUseRule rule,
            List<AnchorData> attached,
            Map<String, List<AnchorData>> anchorsByGroup,
            LandUseTerrainField terrain,
            List<BlockBounds> structureFootprints,
            List<ParcelSpec> occupiedParcels,
            List<String> warnings,
            Map<String, Set<BlockPoint>> capacityDomains,
            Map<String, String> parentParcelIds,
            Map<String, BlockPoint> capacitySeeds,
            boolean capacityPlanPresent) {
        CityBlueprintReferenceCatalog.ParcelStyle style = profile.parcelStyle();
        int minArea = Math.max(rule.minAreaBlocks(), style.parcelAreaMinBlocks());
        int maxArea = Math.min(rule.maxAreaBlocks(), style.parcelAreaMaxBlocks());
        if (minArea > maxArea) {
            throw new IllegalArgumentException("CITY_OUTDOOR_PARCEL_AREA_RANGE_UNSATISFIED:"
                    + landscape.landscapeId() + ':' + minArea + '>' + maxArea);
        }
        BlockPoint origin = attached.isEmpty() ? center(terrain.planningBounds())
                : centroid(attached.stream().map(AnchorData::footprint).toList());
        WaterGuidance water = landscape.placementDomain() == CityBlueprint.LandscapePlacementDomain.ALONG_WATER
                ? waterGuidance(terrain, origin, profile.landscapeType(), landscape.landscapeId()) : null;
        BlockPoint reference = water == null ? null : water.referencePoint();
        CityBlueprint.GrowthBias blueprintBias = water == null ? CityBlueprint.GrowthBias.BALANCED
                : CityBlueprint.GrowthBias.TOWARD_REFERENCE;
        LandUseSeedGroup.GrowthBias growthBias = water == null ? LandUseSeedGroup.GrowthBias.neutral()
                : new LandUseSeedGroup.GrowthBias(LandUseSeedGroup.GrowthBiasMode.ALONG_WATER,
                water.referencePoint(), water.axisX(), water.axisZ());

        List<ParcelSpec> result = new ArrayList<>();
        Set<BlockPoint> usedSeeds = new HashSet<>();
        String landscapeKey = blueprint.cityId() + '|' + landscape.landscapeId();
        for (int instanceOrdinal = 0; instanceOrdinal < landscape.instanceCount(); instanceOrdinal++) {
            String instanceId = landscape.landscapeId() + "::instance_"
                    + String.format(java.util.Locale.ROOT, "%02d", instanceOrdinal + 1);
            List<ParcelSpec> instance = new ArrayList<>();
            Map<String, ParcelSpec> instanceById = new LinkedHashMap<>();
            AnchorData anchor = attached.isEmpty() ? null : attached.get(0);
            for (int ordinal = 0; ordinal < landscape.parcelCount(); ordinal++) {
                String parcelId = instanceId + "::parcel_"
                        + String.format(java.util.Locale.ROOT, "%02d", ordinal + 1);
                if (landscape.required() && capacityPlanPresent && !capacityDomains.containsKey(parcelId)) {
                    break;
                }
                boolean frozenCapacity = capacityDomains.containsKey(parcelId);
                String frozenParentId = parentParcelIds.get(parcelId);
                ParcelSpec parent = frozenCapacity
                        ? frozenParentId == null || frozenParentId.isBlank() ? null
                        : instanceById.get(frozenParentId)
                        : instance.isEmpty() ? null : instance.get(instance.size() - 1);
                if (frozenCapacity && frozenParentId != null && !frozenParentId.isBlank() && parent == null) {
                    throw new IllegalArgumentException("CITY_OUTDOOR_LANDSCAPE_CAPACITY_PARENT_DRIFT:"
                            + parcelId + ':' + frozenParentId);
                }
                String key = landscapeKey + '|' + instanceOrdinal + '|' + ordinal;
                int preferred = stableBetween(key + "|area", minArea, maxArea);
                Set<BlockPoint> capacity = capacityDomains.getOrDefault(parcelId, Set.of());
                AreaBudget budget = frozenCapacity
                        ? new AreaBudget(capacity.size(), capacity.size(), capacity.size())
                        : new AreaBudget(minArea, preferred, maxArea);
                BlockPoint base = parent == null ? (anchor == null ? origin : center(anchor.footprint()))
                        : parent.seed();
                int[] direction = parcelDirection(CityBlueprint.LandscapeGrowthRelation.AROUND_SOURCE,
                        base, reference, water, key);
                int parcelRadius = Math.max(1, (int) Math.ceil(Math.sqrt(preferred) / 2.0));
                int baseRadius = parent == null
                        ? (anchor == null ? 0 : directionalRadius(anchor.footprint(), direction[0], direction[1]))
                        : Math.max(1, (int) Math.ceil(Math.sqrt(parent.budget().preferred()) / 2.0));
                int distance = Math.max(1, baseRadius + parcelRadius - style.minSharedBoundaryBlocks());
                BlockPoint target = new BlockPoint(base.x() + direction[0] * distance,
                        base.z() + direction[1] * distance);
                BlockPoint seed = capacity.isEmpty() ? nearestParcelSeed(terrain, target, profile.landscapeType(),
                        landscape.preferredPatchRefs(), structureFootprints, usedSeeds,
                        combinedParcels(occupiedParcels, result), 0, parcelRadius)
                        : frozenCapacitySeed(parcelId, capacity, capacitySeeds);
                if (seed == null) {
                    warnings.add("skipped_insufficient_space:" + parcelId);
                    break;
                }
                usedSeeds.add(seed);
                String parentId = parent == null ? "" : parent.parcelId();
                String rootSource = parent == null ? (anchor == null
                        ? landscape.placementDomain().name() : anchor.anchorId()) : parent.parcelId();
                ParcelSpec parcel = new ParcelSpec(parcelId, instanceId, parentId, rootSource,
                        anchor, seed, budget, growthBias, blueprintBias, reference,
                        landscape.required() ? LandUseSeedGroup.AdmissionPolicy.REQUIRED
                                : LandUseSeedGroup.AdmissionPolicy.OPTIONAL);
                instance.add(parcel);
                instanceById.put(parcelId, parcel);
            }
            result.addAll(instance);
        }
        return List.copyOf(result);
    }

    private static BlockPoint frozenCapacitySeed(String parcelId, Set<BlockPoint> capacity,
                                                  Map<String, BlockPoint> capacitySeeds) {
        BlockPoint seed = capacitySeeds.get(parcelId);
        if (seed == null || !capacity.contains(seed)) {
            throw new IllegalArgumentException("CITY_OUTDOOR_LANDSCAPE_CAPACITY_SEED_DRIFT:" + parcelId);
        }
        return seed;
    }

    private static boolean adjacentTo(BlockPoint point, BlockBounds bounds) {
        boolean horizontal = (point.x() == bounds.minX() - 1 || point.x() == bounds.maxX() + 1)
                && point.z() >= bounds.minZ() && point.z() <= bounds.maxZ();
        boolean vertical = (point.z() == bounds.minZ() - 1 || point.z() == bounds.maxZ() + 1)
                && point.x() >= bounds.minX() && point.x() <= bounds.maxX();
        return horizontal || vertical;
    }

    private static List<ParcelSpec> combinedParcels(List<ParcelSpec> occupied, List<ParcelSpec> current) {
        if (occupied.isEmpty()) return current;
        if (current.isEmpty()) return occupied;
        List<ParcelSpec> combined = new ArrayList<>(occupied.size() + current.size());
        combined.addAll(occupied);
        combined.addAll(current);
        return combined;
    }

    private static int[] parcelDirection(CityBlueprint.LandscapeGrowthRelation relation,
                                         BlockPoint base,
                                         BlockPoint reference,
                                         WaterGuidance water,
                                         String key) {
        if (relation == CityBlueprint.LandscapeGrowthRelation.ALONG_WATER) {
            int sign = stableBetween(key + "|sign", 0, 1) == 0 ? -1 : 1;
            return new int[]{water.axisX() * sign, water.axisZ() * sign};
        }
        if (reference != null && relation != CityBlueprint.LandscapeGrowthRelation.AROUND_SOURCE) {
            int dx = Integer.compare(reference.x(), base.x());
            int dz = Integer.compare(reference.z(), base.z());
            if (relation == CityBlueprint.LandscapeGrowthRelation.AWAY_FROM_REFERENCE) {
                dx = -dx;
                dz = -dz;
            }
            if (Math.abs(reference.x() - base.x()) >= Math.abs(reference.z() - base.z())) dz = 0;
            else dx = 0;
            if (dx != 0 || dz != 0) return new int[]{dx, dz};
        }
        int[][] directions = {{0, -1}, {-1, 0}, {1, 0}, {0, 1}, {-1, -1}, {1, -1}, {-1, 1}, {1, 1}};
        return directions[stableBetween(key + "|direction", 0, directions.length - 1)];
    }

    private static int directionalRadius(BlockBounds footprint, int dx, int dz) {
        int xRadius = (footprint.widthBlocks() + 1) / 2;
        int zRadius = (footprint.heightBlocks() + 1) / 2;
        if (dx != 0 && dz != 0) return Math.max(xRadius, zRadius);
        return dx != 0 ? xRadius : zRadius;
    }

    private static BlockPoint nearestParcelSeed(LandUseTerrainField terrain,
                                                BlockPoint target,
                                                CityBlueprintReferenceCatalog.LandscapeType type,
                                                List<String> preferredPatchRefs,
                                                List<BlockBounds> structureFootprints,
                                                Set<BlockPoint> usedSeeds,
                                                List<ParcelSpec> existing,
                                                int gap,
                                                int parcelRadius) {
        Set<String> preferred = new HashSet<>(preferredPatchRefs);
        return terrain.cells().stream().filter(cell -> suitable(cell, type))
                .filter(cell -> {
                    BlockPoint point = cellCenter(cell);
                    return terrain.planningBounds().contains(point.x(), point.z())
                            && structureFootprints.stream().noneMatch(bounds -> bounds.contains(point.x(), point.z()))
                            && !usedSeeds.contains(point)
                            && existing.stream().allMatch(parcel -> {
                                int existingRadius = Math.max(1, (int) Math.ceil(
                                        Math.sqrt(parcel.budget().preferred()) / 2.0));
                                long minimumCenterDistance = (long) parcelRadius + existingRadius + gap;
                                return squaredDistance(point, parcel.seed())
                                        >= minimumCenterDistance * minimumCenterDistance;
                            });
                })
                .min(Comparator.<LandUseTerrainField.Cell>comparingInt(cell -> preferred.isEmpty()
                                || preferred.contains(cell.landformPatchId()) ? 0 : 1)
                        .thenComparingLong(cell -> squaredDistance(cellCenter(cell), target))
                        .thenComparing(cell -> cellCenter(cell), POINT_ORDER))
                .map(CityOutdoorBlueprintCompiler::cellCenter).orElse(null);
    }

    private static int stableBetween(String key, int min, int max) {
        if (min > max) throw new IllegalArgumentException("Invalid stable range");
        if (min == max) return min;
        return min + Math.floorMod(stableHash(key), max - min + 1);
    }

    private static double stableUnit(String key) {
        return (stableHash(key) & 0x7fffffffL) / (double) 0x80000000L;
    }

    private static int stableHash(String key) {
        int hash = 0x811c9dc5;
        for (int index = 0; index < key.length(); index++) hash = (hash ^ key.charAt(index)) * 0x01000193;
        return hash;
    }

    private static String safeId(String value) {
        return value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_.-]+", "_");
    }

    private static LandUseSurfaceSettings surfaceSettings(
            LandUseRule rule,
            CityBlueprintReferenceCatalog.SurfaceRecipe recipe,
            boolean autoConnect) {
        LandUseSurfaceSettings.SurfaceAlgorithm algorithm = LandUseSurfaceSettings.SurfaceAlgorithm.valueOf(
                recipe.surfaceAlgorithm().name());
        LandUseSurfaceSettings.SurfaceMaterials materials = recipe.surfacePrintEnabled()
                ? new LandUseSurfaceSettings.SurfaceMaterials(recipe.surfaceBlockId(), recipe.cropBlockId(),
                recipe.channelBankBlockId(), recipe.channelWaterBlockId(), recipe.channelBankOverlayBlockId())
                : null;
        return LandUseSurfaceSettings.defaults(rule.surfacePolicy()).withOverrides(recipe.surfacePrintEnabled(),
                autoConnect, algorithm, recipe.surfaceBlockId(), recipe.cropBlockId(), recipe.channelBankBlockId(),
                recipe.channelWaterBlockId(), recipe.channelBankOverlayBlockId(), recipe.boundaryBlockId(),
                recipe.fieldBeforeBlocks(), recipe.channelWidthBlocks(), recipe.fieldAfterBlocks(), null, materials);
    }

    private static WaterGuidance waterGuidance(LandUseTerrainField terrain,
                                                BlockPoint origin,
                                                CityBlueprintReferenceCatalog.LandscapeType type,
                                                String landscapeId) {
        Map<CellKey, LandUseTerrainField.Cell> waterCells = new HashMap<>();
        terrain.cells().stream().filter(cell -> cell.sampled() && cell.water())
                .forEach(cell -> waterCells.put(new CellKey(cell.cellX(), cell.cellZ()), cell));
        LandUseTerrainField.Cell closest = waterCells.values().stream()
                .min(Comparator.<LandUseTerrainField.Cell>comparingLong(cell ->
                                squaredDistance(origin, cellCenter(cell)))
                        .thenComparing(cell -> cellCenter(cell), POINT_ORDER))
                .orElseThrow(() -> new IllegalArgumentException("CITY_OUTDOOR_WATER_REFERENCE_UNAVAILABLE:"
                        + landscapeId));
        Set<CellKey> component = new LinkedHashSet<>();
        ArrayDeque<CellKey> queue = new ArrayDeque<>();
        CellKey first = new CellKey(closest.cellX(), closest.cellZ());
        component.add(first);
        queue.add(first);
        while (!queue.isEmpty()) {
            CellKey current = queue.removeFirst();
            for (int[] direction : DIRECTIONS) {
                CellKey next = new CellKey(current.x() + direction[0], current.z() + direction[1]);
                if (waterCells.containsKey(next) && component.add(next)) queue.addLast(next);
            }
        }
        int xSpan = component.stream().mapToInt(CellKey::x).max().orElseThrow()
                - component.stream().mapToInt(CellKey::x).min().orElseThrow();
        int zSpan = component.stream().mapToInt(CellKey::z).max().orElseThrow()
                - component.stream().mapToInt(CellKey::z).min().orElseThrow();
        int axisX = xSpan >= zSpan ? 1 : 0;
        int axisZ = xSpan >= zSpan ? 0 : 1;
        Map<CellKey, LandUseTerrainField.Cell> allCells = new HashMap<>();
        terrain.cells().forEach(cell -> allCells.put(new CellKey(cell.cellX(), cell.cellZ()), cell));
        Set<BlockPoint> shoreline = new LinkedHashSet<>();
        if (type == CityBlueprintReferenceCatalog.LandscapeType.POND) {
            component.stream().map(waterCells::get).map(CityOutdoorBlueprintCompiler::cellCenter)
                    .forEach(shoreline::add);
        } else {
            for (CellKey water : component) {
                for (int[] direction : DIRECTIONS) {
                    LandUseTerrainField.Cell candidate = allCells.get(
                            new CellKey(water.x() + direction[0], water.z() + direction[1]));
                    if (candidate != null && suitable(candidate, type)) shoreline.add(cellCenter(candidate));
                }
            }
        }
        if (shoreline.isEmpty()) {
            throw new IllegalArgumentException("CITY_OUTDOOR_WATER_SHORELINE_UNAVAILABLE:" + landscapeId);
        }
        List<BlockPoint> sorted = shoreline.stream()
                .sorted(Comparator.<BlockPoint>comparingLong(point -> squaredDistance(origin, point))
                        .thenComparing(POINT_ORDER)).toList();
        return new WaterGuidance(cellCenter(closest), axisX, axisZ, sorted);
    }

    private static BlockPoint cellCenter(LandUseTerrainField.Cell cell) {
        return new BlockPoint(cell.blockMinX() + cell.cellStepBlocks() / 2,
                cell.blockMinZ() + cell.cellStepBlocks() / 2);
    }

    private static boolean suitable(LandUseTerrainField.Cell cell,
                                    CityBlueprintReferenceCatalog.LandscapeType type) {
        if (!cell.sampled() || cell.slope() >= 45.0 || cell.localRelief() >= 48.0) return false;
        return type == CityBlueprintReferenceCatalog.LandscapeType.POND ? cell.water() : !cell.water();
    }

    private static LandUseSeedGroup.GrowthBias bias(CityBlueprint.GrowthBias value, BlockPoint reference) {
        return switch (value) {
            case BALANCED -> LandUseSeedGroup.GrowthBias.neutral();
            case AWAY_FROM_REFERENCE -> new LandUseSeedGroup.GrowthBias(
                    LandUseSeedGroup.GrowthBiasMode.AWAY_FROM_REFERENCE, reference);
            case TOWARD_REFERENCE -> new LandUseSeedGroup.GrowthBias(
                    LandUseSeedGroup.GrowthBiasMode.TOWARD_REFERENCE, reference);
        };
    }

    private static Map<String, List<AnchorData>> readAnchors(JsonObject plan) {
        JsonArray items = requiredArray(plan, "plannedWorldgenStructures");
        Map<String, List<AnchorData>> result = new LinkedHashMap<>();
        Set<String> anchorIds = new HashSet<>();
        for (JsonElement element : items) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("D6 structures must be objects");
            JsonObject item = element.getAsJsonObject();
            String anchorId = requiredString(item, "anchorId");
            if (!anchorIds.add(anchorId)) throw new IllegalArgumentException("CITY_OUTDOOR_D6_ANCHOR_DUPLICATE:" + anchorId);
            String groupId = requiredString(item, "placementGroupId");
            BlueprintPlacementPhase phase = blueprintPlacementPhase(anchorId,
                    stringValue(item, "blueprintPlacementPhase", ""));
            JsonObject footprint = object(item, "lockedActualFootprint");
            if (footprint.size() == 0) footprint = object(item, "actualFootprint");
            if (footprint.size() == 0) {
                throw new IllegalArgumentException("CITY_OUTDOOR_D6_FOOTPRINT_MISSING:" + anchorId);
            }
            String structureRef = stringValue(item, "blueprintStructureRef", "");
            JsonObject collision = object(item, "lockedCollisionEnvelope");
            if (collision.size() == 0) collision = object(item, "collisionEnvelope");
            if (collision.size() == 0) {
                throw new IllegalArgumentException("CITY_OUTDOOR_D6_COLLISION_MISSING:" + anchorId);
            }
            List<LandUseAreaPlan.GateSlot> gates = roadEntranceGates(item, anchorId);
            BlockPoint entrance = firstRoadEntrance(item);
            JsonObject parcelPlan = object(item, "buildingParcelPlan");
            BlockBounds buildingParcelBounds = parcelPlan.size() == 0
                    ? bounds(collision) : bounds(object(parcelPlan, "resolvedBounds"));
            boolean greenerySelected = parcelPlan.size() > 0
                    && booleanValue(parcelPlan, "greenerySelected", false);
            CityBlueprintReferenceCatalog.GreenParcelPattern greeneryPattern = parcelPlan.has("greeneryPattern")
                    ? CityBlueprintReferenceCatalog.GreenParcelPattern.valueOf(
                    requiredString(parcelPlan, "greeneryPattern"))
                    : CityBlueprintReferenceCatalog.GreenParcelPattern.FREEFORM;
            CityBlueprintReferenceCatalog.GreenParcelDensity greeneryDensity = parcelPlan.has("greeneryDensity")
                    ? CityBlueprintReferenceCatalog.GreenParcelDensity.valueOf(
                    requiredString(parcelPlan, "greeneryDensity"))
                    : CityBlueprintReferenceCatalog.GreenParcelDensity.LOW;
            result.computeIfAbsent(groupId, ignored -> new ArrayList<>()).add(new AnchorData(anchorId, groupId,
                    structureRef, bounds(footprint), bounds(collision), entrance, gates, phase,
                    buildingParcelBounds, greenerySelected, greeneryPattern, greeneryDensity));
        }
        result.replaceAll((ignored, values) -> values.stream().sorted(Comparator.comparing(AnchorData::anchorId))
                .toList());
        return result;
    }

    private static List<BlockBounds> streetBandFootprints(JsonObject materializationPlan) {
        JsonObject anchorMap = object(materializationPlan, "sourceStructureAnchorMap");
        JsonArray streetBands = anchorMap.has("streetBands") && anchorMap.get("streetBands").isJsonArray()
                ? anchorMap.getAsJsonArray("streetBands") : new JsonArray();
        List<BlockBounds> result = new ArrayList<>();
        for (JsonElement element : streetBands) {
            if (!element.isJsonObject()) continue;
            JsonObject band = element.getAsJsonObject();
            if ("CITY_BRIDGE".equals(stringValue(band, "roadKind", ""))) continue;
            JsonObject bounds = object(band, "platformBounds");
            if (bounds.size() == 0) bounds = object(band, "bounds");
            if (bounds.size() > 0) result.add(bounds(bounds));
        }
        return List.copyOf(result);
    }

    private static List<LandUseSourceResolver.RoadBand> roadBands(JsonObject materializationPlan) {
        JsonObject anchorMap = object(materializationPlan, "sourceStructureAnchorMap");
        List<LandUseSourceResolver.RoadBand> result = new ArrayList<>();
        for (JsonElement element : array(anchorMap, "streetBands")) {
            if (!element.isJsonObject()) continue;
            JsonObject band = element.getAsJsonObject();
            String crossSection = stringValue(band, "crossSectionProfile", "");
            if (!"STAIR_SLAB_STAIR".equals(crossSection)
                    && !"BRIDGE_DECK_RAIL".equals(crossSection)) continue;
            JsonObject start = object(band, "start");
            JsonObject end = object(band, "end");
            JsonObject bounds = object(band, "bounds");
            if (start.size() == 0 || end.size() == 0 || bounds.size() == 0) {
                throw new IllegalArgumentException("CITY_OUTDOOR_ROAD_BAND_GEOMETRY_MISSING:"
                        + stringValue(band, "streetBandId", "unknown"));
            }
            result.add(new LandUseSourceResolver.RoadBand(
                    requiredString(band, "streetBandId"), requiredString(band, "roadNetworkId"),
                    requiredString(band, "roadKind"), point(start), point(end), bounds(bounds),
                    intValue(band, "widthBlocks", 1), requiredString(band, "crossSectionProfile"),
                    roadSurfaceBlockId(requiredString(band, "roadKind")),
                    roadCurbBlockId(requiredString(band, "roadKind")),
                    "CITY_BRIDGE".equals(requiredString(band, "roadKind"))
                            ? "minecraft:spruce_fence" : ""));
        }
        result.sort(Comparator.comparing(LandUseSourceResolver.RoadBand::streetBandId));
        return List.copyOf(result);
    }

    private static String roadSurfaceBlockId(String roadKind) {
        if ("CITY_BRIDGE".equals(roadKind)) return "minecraft:spruce_slab";
        if ("CITY_MAIN_ROAD".equals(roadKind)) return "minecraft:deepslate_tile_slab";
        if ("COMPACT_ALLEY".equals(roadKind)) return "minecraft:mud_brick_slab";
        return "minecraft:polished_andesite_slab";
    }

    private static String roadCurbBlockId(String roadKind) {
        if ("CITY_MAIN_ROAD".equals(roadKind)) return "minecraft:deepslate_tile_stairs";
        if ("COMPACT_ALLEY".equals(roadKind)) return "minecraft:mud_brick_stairs";
        return "minecraft:polished_andesite_stairs";
    }

    private static List<LandUseSourceResolver.GreenParcelSpec> greenParcels(
            CityBlueprint blueprint,
            Map<String, List<AnchorData>> anchorsByGroup,
            CityBlueprintReferenceCatalog catalog,
            List<String> warnings) {
        List<CityBlueprintReferenceCatalog.PlantPaletteEntry> palette =
                catalog.plantPalettesByStyleProfileRef().getOrDefault(
                        blueprint.styleProfile().profileRef(), List.of());
        List<LandUseSourceResolver.GreenParcelSpec> result = new ArrayList<>();
        for (List<AnchorData> anchors : anchorsByGroup.values()) {
            for (AnchorData anchor : anchors) {
                if (!anchor.greenerySelected()) continue;
                CityBlueprintReferenceCatalog.BuildingGreenParcelProfile profile =
                        catalog.buildingGreenParcelsByStructureRef().get(anchor.structureRef());
                if (profile == null) continue;
                if (palette.isEmpty()) {
                    warnings.add("CITY_OUTDOOR_GREEN_PARCEL_PALETTE_MISSING_SKIPPED:"
                            + anchor.anchorId());
                    continue;
                }
                if (anchor.entrance() == null) {
                    warnings.add("CITY_OUTDOOR_GREEN_PARCEL_ENTRANCE_MISSING_SKIPPED:"
                            + anchor.anchorId());
                    continue;
                }
                long seed = blueprint.generationSeed()
                        ^ Long.rotateLeft(Integer.toUnsignedLong(stableHash(anchor.anchorId())), 32);
                result.add(new LandUseSourceResolver.GreenParcelSpec(
                        anchor.anchorId() + "::green_parcel", anchor.anchorId(), anchor.buildingParcelBounds(),
                        anchor.collision(), anchor.entrance(), anchor.greeneryPattern(), anchor.greeneryDensity(),
                        profile.groundBlockId(), profile.pathBlockId(), palette, seed));
            }
        }
        result.sort(Comparator.comparing(LandUseSourceResolver.GreenParcelSpec::parcelId));
        return List.copyOf(result);
    }

    private static List<LandUseAreaPlan.GateSlot> roadEntranceGates(JsonObject item, String anchorId) {
        JsonObject placement = object(item, "templatePlacementPlan");
        JsonObject transformed = object(placement, "transformed");
        JsonArray entrances = array(transformed, "roadEntrances");
        List<LandUseAreaPlan.GateSlot> result = new ArrayList<>();
        int ordinal = 0;
        for (JsonElement element : entrances) {
            if (!element.isJsonObject()) continue;
            JsonObject entrance = element.getAsJsonObject();
            JsonObject position = object(entrance, "worldPosition");
            CardinalDirection direction = CardinalDirection.from(
                    stringValue(entrance, "direction", ""), null);
            if (position.size() == 0 || direction == null) continue;
            String entranceId = stringValue(entrance, "entranceId", "entrance_" + (++ordinal));
            result.add(new LandUseAreaPlan.GateSlot(
                    anchorId + "::" + entranceId, point(position), direction, anchorId));
        }
        return List.copyOf(result);
    }

    private static BlockPoint firstRoadEntrance(JsonObject item) {
        JsonObject placement = object(item, "templatePlacementPlan");
        JsonObject transformed = object(placement, "transformed");
        JsonArray entrances = array(transformed, "roadEntrances");
        if (entrances.isEmpty() || !entrances.get(0).isJsonObject()) return null;
        JsonObject position = object(entrances.get(0).getAsJsonObject(), "worldPosition");
        return position.size() == 0 ? null : point(position);
    }

    private static List<LandUseSourceResolver.OverflowZoneSpec> overflowZones(
            JsonObject materializationPlan) {
        JsonObject anchorMap = object(materializationPlan, "sourceStructureAnchorMap");
        Map<String, BlockBounds> roads = new LinkedHashMap<>();
        for (JsonElement element : array(anchorMap, "streetBands")) {
            if (!element.isJsonObject()) continue;
            JsonObject band = element.getAsJsonObject();
            JsonObject bounds = object(band, "bounds");
            if (bounds.size() > 0) roads.put(stringValue(band, "streetBandId", ""), bounds(bounds));
        }
        JsonObject plan = object(anchorMap, "residentialOverflowPlan");
        List<LandUseSourceResolver.OverflowZoneSpec> result = new ArrayList<>();
        for (JsonElement element : array(plan, "zones")) {
            if (!element.isJsonObject()) continue;
            JsonObject zone = element.getAsJsonObject();
            List<BlockBounds> openings = new ArrayList<>();
            for (JsonElement id : array(zone, "streetBandIds")) {
                if (!id.isJsonPrimitive()) continue;
                BlockBounds opening = roads.get(id.getAsString());
                if (opening != null) openings.add(opening);
            }
            result.add(new LandUseSourceResolver.OverflowZoneSpec(requiredString(zone, "zoneId"),
                    bounds(object(zone, "boundaryBounds")), openings,
                    requiredString(zone, "boundaryBlockId")));
        }
        result.sort(Comparator.comparing(LandUseSourceResolver.OverflowZoneSpec::zoneId));
        return List.copyOf(result);
    }

    private static BlueprintPlacementPhase blueprintPlacementPhase(String anchorId, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("CITY_OUTDOOR_D6_BLUEPRINT_PHASE_MISSING:" + anchorId);
        }
        try {
            return BlueprintPlacementPhase.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("CITY_OUTDOOR_D6_BLUEPRINT_PHASE_INVALID:"
                    + anchorId + ':' + raw, exception);
        }
    }

    private static List<AnchorData> requiredGroups(Map<String, List<AnchorData>> anchorsByGroup,
                                                   List<String> groupIds,
                                                   String reason) {
        return requiredGroups(anchorsByGroup, groupIds, reason, true);
    }

    private static AnchorData requiredOwnerAnchor(Map<String, List<AnchorData>> anchorsByGroup,
                                                  CityBlueprint.Landscape landscape) {
        CityBlueprint.LandscapeOwner owner = landscape.owner();
        if (owner == null) throw new IllegalArgumentException("CITY_OUTDOOR_ATTACHED_OWNER_REQUIRED");
        java.util.stream.Stream<AnchorData> candidates = anchorsByGroup
                .getOrDefault(owner.groupId(), List.of()).stream()
                .sorted(Comparator.comparingInt((AnchorData anchor) ->
                        anchor.phase() == BlueprintPlacementPhase.REQUIRED ? 0 : 1)
                        .thenComparing(AnchorData::anchorId));
        if (!owner.groupOwned()) {
            candidates = candidates.filter(anchor -> owner.requiredStructureRef().equals(anchor.structureRef()));
        }
        return candidates.findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "CITY_OUTDOOR_REQUIRED_LANDSCAPE_OWNER_DRIFT:" + landscape.landscapeId()));
    }

    private static CapacityReservation capacityReservation(
            CityBlueprint blueprint, Map<String, List<AnchorData>> anchorsByGroup, JsonObject plan) {
        boolean hasRequired = blueprint.outdoorPlan().landscapes().stream()
                .anyMatch(CityBlueprint.Landscape::required);
        if (plan == null) {
            if (hasRequired) {
                throw new IllegalArgumentException("CITY_OUTDOOR_REQUIRED_LANDSCAPE_CAPACITY_MISSING");
            }
            return CapacityReservation.empty();
        }
        if (!CityLandscapeCapacityReservationPlanner.SCHEMA.equals(
                stringValue(plan, "schema", ""))
                || !blueprint.cityId().equals(stringValue(plan, "cityId", ""))
                || !"reserved".equals(stringValue(plan, "status", ""))) {
            throw new IllegalArgumentException("CITY_OUTDOOR_LANDSCAPE_CAPACITY_STALE");
        }
        String sourceBlueprintHash = requiredString(plan, "sourceBlueprintHash");
        String sourceD4Hash = requiredString(plan, "sourceD4Hash");
        String planHash = requiredString(plan, "planHash");
        JsonObject hashInput = plan.deepCopy();
        hashInput.remove("planHash");
        if (!sourceBlueprintHash.equals(sourceHash(new CityBlueprintCodec().write(blueprint)))
                || !sourceD4Hash.matches("sha256:[0-9a-f]{64}")
                || !planHash.equals(sourceHash(hashInput))) {
            throw new IllegalArgumentException("CITY_OUTDOOR_LANDSCAPE_CAPACITY_HASH_MISMATCH");
        }
        Map<String, CityBlueprint.Landscape> required = new LinkedHashMap<>();
        blueprint.outdoorPlan().landscapes().stream().filter(CityBlueprint.Landscape::required)
                .forEach(landscape -> required.put(landscape.landscapeId(), landscape));
        Map<String, Set<BlockPoint>> result = new LinkedHashMap<>();
        Map<String, String> parentIds = new LinkedHashMap<>();
        Map<String, BlockPoint> seeds = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        Set<String> warnedInstances = new HashSet<>();
        for (JsonElement warningElement : requiredArray(plan, "warnings")) {
            JsonObject warning = warningElement.getAsJsonObject();
            String reasonCode = requiredString(warning, "reasonCode");
            String landscapeId = requiredString(warning, "landscapeId");
            int instanceOrdinal = requiredInt(warning, "instanceOrdinal");
            if (!required.containsKey(landscapeId) || instanceOrdinal < 0
                    || instanceOrdinal >= required.get(landscapeId).instanceCount()) {
                throw new IllegalArgumentException("CITY_OUTDOOR_LANDSCAPE_CAPACITY_WARNING_DRIFT");
            }
            if ("REQUIRED_LANDSCAPE_NO_TERRAIN_FIT_WARNING".equals(reasonCode)) {
                warnedInstances.add(landscapeId + '\u0000' + instanceOrdinal);
            }
            warnings.add(reasonCode + ':' + landscapeId + ':' + instanceOrdinal);
        }
        Set<String> seenInstances = new HashSet<>();
        for (JsonElement element : requiredArray(plan, "instances")) {
            JsonObject instance = element.getAsJsonObject();
            String landscapeId = requiredString(instance, "landscapeId");
            CityBlueprint.Landscape landscape = required.get(landscapeId);
            if (landscape == null || landscape.owner() == null) {
                throw new IllegalArgumentException("CITY_OUTDOOR_LANDSCAPE_CAPACITY_OWNER_DRIFT:" + landscapeId);
            }
            String instanceId = requiredString(instance, "landscapeInstanceId");
            if (!seenInstances.add(instanceId)) {
                throw new IllegalArgumentException("CITY_OUTDOOR_LANDSCAPE_CAPACITY_INSTANCE_DUPLICATE");
            }
            int instanceOrdinal = Integer.parseInt(instanceId.substring(instanceId.lastIndexOf('_') + 1)) - 1;
            AnchorData owner = requiredOwnerAnchor(anchorsByGroup, landscape);
            if (!owner.anchorId().equals(requiredString(instance, "ownerAnchorId"))
                    || !sameBounds(owner.footprint(), object(instance, "ownerFootprint"))) {
                throw new IllegalArgumentException("CITY_OUTDOOR_REQUIRED_LANDSCAPE_OWNER_DRIFT:" + landscapeId);
            }
            JsonArray parcels = requiredArray(instance, "parcelReservations");
            if (parcels.isEmpty() || parcels.size() > landscape.parcelCount()) {
                throw new IllegalArgumentException("CITY_OUTDOOR_REQUIRED_LANDSCAPE_PARCEL_COUNT_DRIFT:"
                        + landscapeId);
            }
            for (JsonElement parcelElement : parcels) {
                JsonObject parcel = parcelElement.getAsJsonObject();
                String parcelId = requiredString(parcel, "parcelId");
                String parentId = stringValue(parcel, "parentParcelId", "");
                if (parentIds.put(parcelId, parentId) != null) {
                    throw new IllegalArgumentException("CITY_OUTDOOR_LANDSCAPE_CAPACITY_PARCEL_INVALID:"
                            + parcelId);
                }
                Set<BlockPoint> cells = new LinkedHashSet<>();
                for (JsonElement spanElement : requiredArray(parcel, "reservationSpans")) {
                    JsonObject span = spanElement.getAsJsonObject();
                    int z = requiredInt(span, "z");
                    int minX = requiredInt(span, "minX");
                    int maxX = requiredInt(span, "maxX");
                    for (int x = minX; x <= maxX; x++) cells.add(new BlockPoint(x, z));
                }
                if (cells.isEmpty() || result.put(parcelId, Set.copyOf(cells)) != null) {
                    throw new IllegalArgumentException("CITY_OUTDOOR_LANDSCAPE_CAPACITY_PARCEL_INVALID:"
                            + parcelId);
                }
                JsonObject seed = object(parcel, "seed");
                BlockPoint seedPoint = new BlockPoint(requiredInt(seed, "x"), requiredInt(seed, "z"));
                if (!cells.contains(seedPoint)) {
                    throw new IllegalArgumentException("CITY_OUTDOOR_LANDSCAPE_CAPACITY_SEED_DRIFT:"
                            + parcelId);
                }
                seeds.put(parcelId, seedPoint);
            }
            warnedInstances.remove(landscapeId + '\u0000' + instanceOrdinal);
        }
        for (CityBlueprint.Landscape landscape : required.values()) {
            for (int ordinal = 0; ordinal < landscape.instanceCount(); ordinal++) {
                String instanceId = landscape.landscapeId() + "::instance_"
                        + String.format(java.util.Locale.ROOT, "%02d", ordinal + 1);
                if (!seenInstances.contains(instanceId)
                        && !warnedInstances.contains(landscape.landscapeId() + '\u0000' + ordinal)) {
                    throw new IllegalArgumentException("CITY_OUTDOOR_REQUIRED_LANDSCAPE_INSTANCE_DRIFT:"
                            + instanceId);
                }
            }
        }
        return new CapacityReservation(Map.copyOf(result), Map.copyOf(parentIds), Map.copyOf(seeds),
                List.copyOf(warnings), true);
    }

    private static boolean sameBounds(BlockBounds expected, JsonObject actual) {
        return actual != null && expected.minX() == requiredInt(actual, "minX")
                && expected.minZ() == requiredInt(actual, "minZ")
                && expected.maxX() == requiredInt(actual, "maxX")
                && expected.maxZ() == requiredInt(actual, "maxZ");
    }

    private static List<AnchorData> requiredGroups(Map<String, List<AnchorData>> anchorsByGroup,
                                                   List<String> groupIds,
                                                   String reason,
                                                   boolean requireNonEmpty) {
        List<AnchorData> result = new ArrayList<>();
        for (String groupId : groupIds) {
            List<AnchorData> members = anchorsByGroup.get(groupId);
            if (members == null || members.isEmpty()) throw new IllegalArgumentException(reason + ':' + groupId);
            result.addAll(members);
        }
        if (requireNonEmpty && result.isEmpty()) throw new IllegalArgumentException(reason + ":empty");
        return result.stream().distinct().sorted(Comparator.comparing(AnchorData::anchorId)).toList();
    }

    private static LandUseRule requireRule(CityBlueprintReferenceCatalog catalog, String ruleRef) {
        return catalog.landUseRuleCatalog().byRef(ruleRef).orElseThrow(() ->
                new IllegalArgumentException("CITY_OUTDOOR_LAND_USE_RULE_UNKNOWN:" + ruleRef));
    }

    private static CityBlueprintReferenceCatalog.SurfaceRecipe requireRecipe(
            CityBlueprintReferenceCatalog catalog,
            String recipeRef) {
        CityBlueprintReferenceCatalog.SurfaceRecipe recipe = catalog.surfaceRecipes().get(recipeRef);
        if (recipe == null) throw new IllegalArgumentException("CITY_OUTDOOR_SURFACE_RECIPE_UNKNOWN:" + recipeRef);
        return recipe;
    }

    private static BlockPoint centroid(List<BlockBounds> bounds) {
        if (bounds.isEmpty()) throw new IllegalArgumentException("CITY_OUTDOOR_CENTROID_SOURCE_REQUIRED");
        long weightedX = 0;
        long weightedZ = 0;
        long weight = 0;
        for (BlockBounds value : bounds) {
            long area = area(value);
            weightedX += (long) center(value).x() * area;
            weightedZ += (long) center(value).z() * area;
            weight += area;
        }
        return new BlockPoint((int) Math.round(weightedX / (double) weight),
                (int) Math.round(weightedZ / (double) weight));
    }

    private static BlockPoint center(BlockBounds bounds) {
        return new BlockPoint((int) Math.floorDiv((long) bounds.minX() + bounds.maxX(), 2),
                (int) Math.floorDiv((long) bounds.minZ() + bounds.maxZ(), 2));
    }

    private static int area(BlockBounds bounds) {
        return Math.multiplyExact(bounds.widthBlocks(), bounds.heightBlocks());
    }

    private static long squaredDistance(BlockPoint left, BlockPoint right) {
        long dx = (long) left.x() - right.x();
        long dz = (long) left.z() - right.z();
        return dx * dx + dz * dz;
    }

    private static BlockBounds bounds(JsonObject object) {
        return new BlockBounds(requiredInt(object, "minX"), requiredInt(object, "minZ"),
                requiredInt(object, "maxX"), requiredInt(object, "maxZ"));
    }

    private static BlockBounds bounds(List<BlockBounds> values) {
        if (values.isEmpty()) throw new IllegalArgumentException("CITY_OUTDOOR_SPATIAL_GROUP_EMPTY");
        return new BlockBounds(values.stream().mapToInt(BlockBounds::minX).min().orElseThrow(),
                values.stream().mapToInt(BlockBounds::minZ).min().orElseThrow(),
                values.stream().mapToInt(BlockBounds::maxX).max().orElseThrow(),
                values.stream().mapToInt(BlockBounds::maxZ).max().orElseThrow());
    }

    private static JsonObject object(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonObject()
                ? object.getAsJsonObject(key) : new JsonObject();
    }

    private static JsonArray requiredArray(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            throw new IllegalArgumentException(key + " array is required");
        }
        return object.getAsJsonArray(key);
    }

    private static JsonArray array(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonArray()
                ? object.getAsJsonArray(key) : new JsonArray();
    }

    private static String requiredString(JsonObject object, String key) {
        String value = stringValue(object, key, "");
        if (value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private static String stringValue(JsonObject object, String key, String fallback) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsString() : fallback;
    }

    private static int requiredInt(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.getAsJsonPrimitive(key).isNumber()) {
            throw new IllegalArgumentException(key + " integer is required");
        }
        return object.get(key).getAsInt();
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive()
                && object.getAsJsonPrimitive(key).isNumber() ? object.get(key).getAsInt() : fallback;
    }

    private static BlockPoint point(JsonObject object) {
        return new BlockPoint(requiredInt(object, "x"), requiredInt(object, "z"));
    }

    private static boolean booleanValue(JsonObject object, String key, boolean fallback) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive()
                && object.getAsJsonPrimitive(key).isBoolean() ? object.get(key).getAsBoolean() : fallback;
    }

    public record Result(LandUseSourceResolver.Resolution resolution,
                         CityUrbanResidualResolver.Config residualConfig,
                         CityOutdoorIntentPlan intentPlan) {
    }

    private record AnchorData(String anchorId,
                              String groupId,
                              String structureRef,
                              BlockBounds footprint,
                              BlockBounds collision,
                              BlockPoint entrance,
                              List<LandUseAreaPlan.GateSlot> gates,
                              BlueprintPlacementPhase phase,
                              BlockBounds buildingParcelBounds,
                              boolean greenerySelected,
                              CityBlueprintReferenceCatalog.GreenParcelPattern greeneryPattern,
                              CityBlueprintReferenceCatalog.GreenParcelDensity greeneryDensity) {
        private AnchorData {
            gates = List.copyOf(gates);
        }
    }

    private record ParcelSpec(String parcelId,
                              String landscapeInstanceId,
                              String parentParcelId,
                              String rootSourceId,
                              AnchorData anchor,
                              BlockPoint seed,
                              AreaBudget budget,
                              LandUseSeedGroup.GrowthBias bias,
                              CityBlueprint.GrowthBias blueprintBias,
                              BlockPoint referencePoint,
                              LandUseSeedGroup.AdmissionPolicy admissionPolicy) {
    }

    private record CapacityReservation(Map<String, Set<BlockPoint>> domains,
                                       Map<String, String> parentParcelIds,
                                       Map<String, BlockPoint> seeds,
                                       List<String> warnings,
                                       boolean planPresent) {
        private static CapacityReservation empty() {
            return new CapacityReservation(Map.of(), Map.of(), Map.of(), List.of(), false);
        }
    }

    private enum BlueprintPlacementPhase {
        REQUIRED,
        FILL,
        CONNECTIVITY_GROWTH,
        PERCENTAGE_GROWTH
    }

    private record AreaBudget(int min, int preferred, int max) {
    }

    private record SourceHashes(String blueprint, String d6, String terrain, String catalog) {
    }

    private record WaterGuidance(BlockPoint referencePoint,
                                 int axisX,
                                 int axisZ,
                                 List<BlockPoint> shorelineSeeds) {
        private WaterGuidance {
            shorelineSeeds = List.copyOf(shorelineSeeds);
        }
    }

    private record CellKey(int x, int z) {
    }

    private static final int[][] DIRECTIONS = {{0, -1}, {-1, 0}, {1, 0}, {0, 1}};
    private static final Comparator<BlockPoint> POINT_ORDER = Comparator.comparingInt(BlockPoint::z)
            .thenComparingInt(BlockPoint::x);
}
