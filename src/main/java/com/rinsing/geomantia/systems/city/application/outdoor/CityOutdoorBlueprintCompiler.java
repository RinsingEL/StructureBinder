package com.rinsing.geomantia.systems.city.application.outdoor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.CityBlueprintCodec;
import com.rinsing.geomantia.systems.city.application.CityBlueprintReferenceCatalog;
import com.rinsing.geomantia.systems.city.application.landuse.LandUseSourceResolver;
import com.rinsing.geomantia.systems.city.application.landuse.LandUseTerrainFieldCodec;
import com.rinsing.geomantia.systems.city.algorithm.landuse.CityFoundationPlanner;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSeedGroup;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSurfaceSettings;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.landuse.BoundaryPolicy;
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
        List<String> spatialGroupIds = blueprint.outdoorPlan().spatialGrounds().stream()
                .map(CityBlueprint.SpatialGround::sourceGroupId).distinct().sorted().toList();
        List<AnchorData> foundationAnchors = requiredGroups(anchorsByGroup, spatialGroupIds,
                "CITY_OUTDOOR_STRUCTURE_GROUP_UNKNOWN");
        List<BlockBounds> allFootprints = foundationAnchors.stream().map(AnchorData::footprint).distinct().toList();
        List<String> allAnchorIds = foundationAnchors.stream().map(AnchorData::anchorId).sorted().toList();
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
                surfaceSettings(foundationRule, foundationRecipe, false), allAnchorIds, allFootprints,
                foundationSeeds, List.of(), foundationArea, foundationArea, foundationArea,
                foundationRule.actionBudget(), foundationRule.competitionWeight(), List.of(foundationRegion),
                LandUseSeedGroup.GrowthBias.neutral(), LandUseSeedGroup.TerrainBias.BALANCED, List.of(),
                LandUseSeedGroup.LayerRole.FOUNDATION, foundationSettings));
        List<CityOutdoorIntentPlan.SourceIntent> sourceIntents = new ArrayList<>();
        sourceIntents.add(sourceIntent(foundationGroupId, CityOutdoorIntentPlan.SourceKind.FOUNDATION,
                foundationProfile.foundationProfileRef(), foundationRule.ruleRef(),
                foundationRecipe.surfaceRecipeRef(), CityBlueprint.OutdoorMembership.URBAN, null,
                null, null, null, CityBlueprint.TerrainPolicy.BALANCED, spatialGroupIds, allAnchorIds,
                List.of(), foundationBudget, CityBlueprint.GrowthBias.BALANCED, null, foundationSeeds, true));

        for (CityBlueprint.Landscape landscape : blueprint.outdoorPlan().landscapes()) {
            CityBlueprintReferenceCatalog.LandscapeProfile profile = catalog.landscapeProfiles().get(
                    landscape.landscapeProfileRef());
            if (profile == null) {
                throw new IllegalArgumentException("CITY_OUTDOOR_LANDSCAPE_PROFILE_UNKNOWN:"
                        + landscape.landscapeProfileRef());
            }
            LandUseRule parcelRule = independentParcelRule(requireRule(catalog, profile.landUseRuleRef()));
            CityBlueprintReferenceCatalog.SurfaceRecipe recipe = requireRecipe(catalog, profile.surfaceRecipeRef());
            List<AnchorData> attached = requiredGroups(anchorsByGroup, landscape.attachedGroupIds(),
                    "CITY_OUTDOOR_LANDSCAPE_ATTACHED_GROUP_UNKNOWN", false);
            List<ParcelSpec> parcels = landscapeParcels(blueprint, landscape, profile, parcelRule, attached,
                    anchorsByGroup, terrain, allFootprints);
            if (landscape.required() && parcels.isEmpty()) {
                throw new IllegalArgumentException("CITY_OUTDOOR_REQUIRED_LANDSCAPE_HAS_NO_PARCEL:"
                        + landscape.landscapeId());
            }
            for (ParcelSpec parcel : parcels) {
                List<String> anchorIds = parcel.anchor() == null ? List.of() : List.of(parcel.anchor().anchorId());
                List<BlockPoint> seeds = List.of(parcel.seed());
                LandUseSeedGroup.GrowthRegion region = new LandUseSeedGroup.GrowthRegion(parcel.parcelId(),
                        anchorIds, seeds, parcel.budget().min(), parcel.budget().preferred(), parcel.budget().max());
                groups.add(new LandUseSeedGroup(parcel.parcelId(), parcelRule,
                        surfaceSettings(parcelRule, recipe, false), anchorIds, allFootprints, seeds, List.of(),
                        parcel.budget().min(), parcel.budget().preferred(), parcel.budget().max(),
                        parcelRule.actionBudget(), parcelRule.competitionWeight(), List.of(region), parcel.bias(),
                        LandUseSeedGroup.TerrainBias.valueOf(landscape.terrainPolicy().name()),
                        landscape.preferredPatchRefs(), LandUseSeedGroup.LayerRole.LANDSCAPE, null));
                sourceIntents.add(sourceIntent(parcel.parcelId(), CityOutdoorIntentPlan.SourceKind.LANDSCAPE,
                        landscape.landscapeProfileRef(), parcelRule.ruleRef(), recipe.surfaceRecipeRef(),
                        profile.membership(), landscape.extentClass(), landscape.intensity(), landscape.continuity(),
                        landscape.growthRelation(), landscape.terrainPolicy(), landscape.attachedGroupIds(),
                        anchorIds, landscape.preferredPatchRefs(), parcel.budget(), parcel.blueprintBias(),
                        parcel.referencePoint(), seeds, landscape.required()));
            }
        }

        groups.sort(Comparator.comparing(LandUseSeedGroup::groupId));
        sourceIntents.sort(Comparator.comparing(CityOutdoorIntentPlan.SourceIntent::sourceId));
        Set<String> urbanGroupIds = Set.of(foundationGroupId);
        CityOutdoorIntentPlan intent = intent(blueprint, catalog, sourceHashes, sourceIntents, urbanGroupIds,
                foundationPlan.resolvedCloseRadiusBlocks());
        return new Result(new LandUseSourceResolver.Resolution(groups, List.of(), List.of(),
                Long.toUnsignedString(blueprint.generationSeed())), CityUrbanResidualResolver.Config.disabled(),
                intent);
    }

    private static CityOutdoorIntentPlan preserveIntent(CityBlueprint blueprint,
                                                        CityBlueprintReferenceCatalog catalog,
                                                        SourceHashes sourceHashes) {
        return new CityOutdoorIntentPlan(CityOutdoorIntentPlan.SCHEMA_VERSION, blueprint.cityId(),
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
        return new CityOutdoorIntentPlan(CityOutdoorIntentPlan.SCHEMA_VERSION, blueprint.cityId(),
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
        return new CityOutdoorIntentPlan.SourceIntent(sourceId, sourceKind, profileRef, ruleRef, recipeRef,
                membership, extentClass, intensity, continuity, growthRelation, terrainPolicy, sourceGroupIds,
                sourceAnchorIds, preferredPatchRefs, budget.min(), budget.preferred(), budget.max(), growthBias,
                reference, seeds, required);
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
                false, source.surfacePolicy(), source.vegetationPolicy(), BoundaryPolicy.OPEN,
                source.decorationPolicy());
    }

    private static LandUseRule independentParcelRule(LandUseRule source) {
        return new LandUseRule(source.ruleRef(), source.landUseType(), source.semanticTerms(),
                source.footprintMultiplier(), source.extraAreaBlocks(), source.minAreaBlocks(),
                source.maxAreaBlocks(), source.actionBudget(), source.baseStepCost(), source.slopeCost(),
                source.reliefCost(), source.waterCost(), source.forestAffinity(), source.competitionWeight(),
                false, source.surfacePolicy(), source.vegetationPolicy(), source.boundaryPolicy(),
                source.decorationPolicy());
    }

    private static List<ParcelSpec> landscapeParcels(
            CityBlueprint blueprint,
            CityBlueprint.Landscape landscape,
            CityBlueprintReferenceCatalog.LandscapeProfile profile,
            LandUseRule rule,
            List<AnchorData> attached,
            Map<String, List<AnchorData>> anchorsByGroup,
            LandUseTerrainField terrain,
            List<BlockBounds> structureFootprints) {
        CityBlueprintReferenceCatalog.ParcelStyle style = profile.parcelStyle();
        int minArea = Math.max(rule.minAreaBlocks(), style.parcelAreaMinBlocks());
        int maxArea = Math.min(rule.maxAreaBlocks(), style.parcelAreaMaxBlocks());
        if (minArea > maxArea) {
            throw new IllegalArgumentException("CITY_OUTDOOR_PARCEL_AREA_RANGE_UNSATISFIED:"
                    + landscape.landscapeId() + ':' + minArea + '>' + maxArea);
        }
        BlockPoint origin = attached.isEmpty() ? center(terrain.planningBounds())
                : centroid(attached.stream().map(AnchorData::footprint).toList());
        WaterGuidance water = switch (landscape.growthRelation()) {
            case TOWARD_WATER, ALONG_WATER -> waterGuidance(terrain, origin, profile.landscapeType(),
                    landscape.landscapeId());
            default -> null;
        };
        BlockPoint reference = landscapeReference(anchorsByGroup, landscape, water);
        CityBlueprint.GrowthBias blueprintBias = switch (landscape.growthRelation()) {
            case AWAY_FROM_REFERENCE -> CityBlueprint.GrowthBias.AWAY_FROM_REFERENCE;
            case TOWARD_WATER -> CityBlueprint.GrowthBias.TOWARD_REFERENCE;
            default -> CityBlueprint.GrowthBias.BALANCED;
        };
        LandUseSeedGroup.GrowthBias growthBias = landscape.growthRelation()
                == CityBlueprint.LandscapeGrowthRelation.ALONG_WATER
                ? new LandUseSeedGroup.GrowthBias(LandUseSeedGroup.GrowthBiasMode.ALONG_WATER,
                water.referencePoint(), water.axisX(), water.axisZ()) : bias(blueprintBias, reference);

        List<ParcelSpec> result = new ArrayList<>();
        Set<BlockPoint> usedSeeds = new HashSet<>();
        List<AnchorData> parcelAnchors = attached.isEmpty() ? java.util.Collections.singletonList(null)
                : attached.stream().sorted(Comparator.comparing(AnchorData::anchorId)).toList();
        int sequence = 0;
        for (AnchorData anchor : parcelAnchors) {
            String owner = anchor == null ? "detached" : anchor.anchorId();
            int count = anchor == null ? 1 : parcelCount(style, anchor.phase(),
                    blueprint.cityId() + '|' + landscape.landscapeId() + '|' + anchor.anchorId());
            ParcelSpec priorForOwner = null;
            for (int ordinal = 0; ordinal < count; ordinal++) {
                sequence++;
                String key = blueprint.cityId() + '|' + landscape.landscapeId() + '|' + owner + '|' + ordinal;
                int preferred = stableBetween(key + "|area", minArea, maxArea);
                AreaBudget budget = new AreaBudget(minArea, preferred, maxArea);
                ParcelSpec prior = priorForOwner;
                boolean branch = ordinal > 0 && prior != null
                        && stableUnit(key + "|branch") < style.branchFromExistingChance();
                BlockPoint base = branch ? prior.seed()
                        : anchor == null ? origin : center(anchor.footprint());
                int gap = stableBetween(key + "|gap", style.gapMinBlocks(), style.gapMaxBlocks());
                int[] direction = parcelDirection(landscape.growthRelation(), base, reference, water, key);
                int parcelRadius = Math.max(1, (int) Math.ceil(Math.sqrt(preferred) / 2.0));
                int baseRadius = branch ? Math.max(1,
                        (int) Math.ceil(Math.sqrt(prior.budget().preferred()) / 2.0))
                        : anchor == null ? 0 : directionalRadius(anchor.footprint(), direction[0], direction[1]);
                int distance = baseRadius + gap + parcelRadius;
                BlockPoint target = new BlockPoint(base.x() + direction[0] * distance,
                        base.z() + direction[1] * distance);
                BlockPoint seed = nearestParcelSeed(terrain, target, profile.landscapeType(),
                        landscape.preferredPatchRefs(), structureFootprints, usedSeeds, result, gap);
                if (seed == null) {
                    if (landscape.required()) {
                        throw new IllegalArgumentException("CITY_OUTDOOR_REQUIRED_LANDSCAPE_HAS_NO_SEED:"
                                + landscape.landscapeId() + ':' + owner + ':' + ordinal);
                    }
                    continue;
                }
                usedSeeds.add(seed);
                String parcelId = landscape.landscapeId() + "::" + safeId(owner) + "::parcel_"
                        + String.format(java.util.Locale.ROOT, "%02d", ordinal + 1);
                ParcelSpec parcel = new ParcelSpec(parcelId, anchor, seed, budget, growthBias, blueprintBias,
                        reference);
                result.add(parcel);
                priorForOwner = parcel;
            }
        }
        return List.copyOf(result);
    }

    private static int parcelCount(CityBlueprintReferenceCatalog.ParcelStyle style,
                                   BlueprintPlacementPhase phase,
                                   String key) {
        return switch (phase) {
            case REQUIRED -> stableBetween(key + "|count", style.coreParcelCountMin(),
                    style.coreParcelCountMax());
            case FILL -> stableBetween(key + "|count", style.fillParcelCountMin(), style.fillParcelCountMax());
            case CONNECTIVITY_GROWTH -> 0;
        };
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
                                                int gap) {
        Set<String> preferred = new HashSet<>(preferredPatchRefs);
        long minimumDistance = (long) gap * gap;
        return terrain.cells().stream().filter(cell -> suitable(cell, type))
                .filter(cell -> {
                    BlockPoint point = cellCenter(cell);
                    return terrain.planningBounds().contains(point.x(), point.z())
                            && structureFootprints.stream().noneMatch(bounds -> bounds.contains(point.x(), point.z()))
                            && !usedSeeds.contains(point)
                            && existing.stream().allMatch(parcel -> squaredDistance(point, parcel.seed())
                            >= minimumDistance);
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

    private static BlockPoint landscapeReference(Map<String, List<AnchorData>> anchorsByGroup,
                                                 CityBlueprint.Landscape landscape,
                                                 WaterGuidance waterGuidance) {
        if (landscape.growthRelation() == CityBlueprint.LandscapeGrowthRelation.AWAY_FROM_REFERENCE) {
            return centroid(requiredGroups(anchorsByGroup, landscape.referenceGroupIds(),
                    "CITY_OUTDOOR_REFERENCE_GROUP_UNKNOWN").stream().map(AnchorData::footprint).toList());
        }
        if (landscape.growthRelation() == CityBlueprint.LandscapeGrowthRelation.TOWARD_WATER
                || landscape.growthRelation() == CityBlueprint.LandscapeGrowthRelation.ALONG_WATER) {
            return waterGuidance.referencePoint();
        }
        return null;
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
            result.computeIfAbsent(groupId, ignored -> new ArrayList<>()).add(new AnchorData(anchorId, groupId,
                    bounds(footprint), phase));
        }
        result.replaceAll((ignored, values) -> values.stream().sorted(Comparator.comparing(AnchorData::anchorId))
                .toList());
        return result;
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
                              BlockBounds footprint,
                              BlueprintPlacementPhase phase) {
    }

    private record ParcelSpec(String parcelId,
                              AnchorData anchor,
                              BlockPoint seed,
                              AreaBudget budget,
                              LandUseSeedGroup.GrowthBias bias,
                              CityBlueprint.GrowthBias blueprintBias,
                              BlockPoint referencePoint) {
    }

    private enum BlueprintPlacementPhase {
        REQUIRED,
        FILL,
        CONNECTIVITY_GROWTH
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
