package com.rinsing.geomantia.systems.city.application.outdoor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.CityBlueprintCodec;
import com.rinsing.geomantia.systems.city.application.CityBlueprintReferenceCatalog;
import com.rinsing.geomantia.systems.city.application.landuse.LandUseSourceResolver;
import com.rinsing.geomantia.systems.city.application.landuse.LandUseTerrainFieldCodec;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import com.rinsing.geomantia.systems.city.domain.landuse.CardinalDirection;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
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
        List<AnchorData> allAnchors = anchorsByGroup.values().stream().flatMap(List::stream)
                .sorted(Comparator.comparing(AnchorData::anchorId)).toList();
        List<BlockBounds> allFootprints = allAnchors.stream().map(AnchorData::footprint).toList();
        List<LandUseSeedGroup> groups = new ArrayList<>();
        List<LandUseAreaPlan.CorridorExclusion> corridors = new ArrayList<>();
        List<CityOutdoorIntentPlan.SourceIntent> sourceIntents = new ArrayList<>();
        Set<String> urbanGroupIds = new LinkedHashSet<>();
        List<BlockBounds> urbanFootprints = new ArrayList<>();
        Map<String, CityBlueprint.Group> blueprintGroups = new LinkedHashMap<>();
        blueprint.groups().forEach(group -> blueprintGroups.put(group.groupId(), group));

        for (CityBlueprint.SpatialGround ground : blueprint.outdoorPlan().spatialGrounds()) {
            List<AnchorData> members = requiredGroups(anchorsByGroup, List.of(ground.sourceGroupId()),
                    "CITY_OUTDOOR_STRUCTURE_GROUP_UNKNOWN");
            CityBlueprint.Group sourceGroup = blueprintGroups.get(ground.sourceGroupId());
            if (sourceGroup == null) {
                throw new IllegalArgumentException("CITY_OUTDOOR_STRUCTURE_GROUP_UNKNOWN:" + ground.sourceGroupId());
            }
            LandUseRule rule = sharedSpaceRule(requireRule(catalog, ground.landUseRuleRef()), ground.membership());
            CityBlueprintReferenceCatalog.SurfaceRecipe recipe = requireRecipe(catalog,
                    ground.surfaceRecipeRef());
            List<BlockPoint> seeds = spatialSeeds(ground, members, anchorsByGroup, blueprint.relations());
            AreaBudget budget = spatialBudget(rule, members, sourceGroup.extentClass(),
                    ground.sharedSpaceType(), ground.hierarchyLevel());
            LandUseSurfaceSettings settings = surfaceSettings(rule, recipe, false);
            List<LandUseAreaPlan.GateSlot> gates = members.stream().flatMap(member -> member.gates().stream())
                    .sorted(Comparator.comparing(LandUseAreaPlan.GateSlot::gateId)).toList();
            gates.stream().map(CityOutdoorBlueprintCompiler::corridor).forEach(corridors::add);
            List<String> anchorIds = members.stream().map(AnchorData::anchorId).sorted().toList();
            LandUseSeedGroup.GrowthBias resolvedBias = LandUseSeedGroup.GrowthBias.neutral();
            LandUseSeedGroup.GrowthRegion region = new LandUseSeedGroup.GrowthRegion(
                    ground.sourceGroupId() + "::shared_space", anchorIds, seeds,
                    budget.min(), budget.preferred(), budget.max());
            groups.add(new LandUseSeedGroup(ground.sourceGroupId(), rule, settings, anchorIds, allFootprints,
                    seeds, gates, budget.min(), budget.preferred(), budget.max(), rule.actionBudget(),
                    rule.competitionWeight(), List.of(region), resolvedBias,
                    LandUseSeedGroup.TerrainBias.BALANCED, List.of()));
            if (ground.membership() == CityBlueprint.OutdoorMembership.URBAN) {
                urbanGroupIds.add(ground.sourceGroupId());
                members.stream().map(AnchorData::footprint).forEach(urbanFootprints::add);
            }
            sourceIntents.add(sourceIntent(ground.sourceGroupId(), CityOutdoorIntentPlan.SourceKind.SPATIAL_GROUND,
                    "", rule.ruleRef(), recipe.surfaceRecipeRef(), ground.membership(), sourceGroup.extentClass(),
                    null, null, null, CityBlueprint.TerrainPolicy.BALANCED, List.of(ground.sourceGroupId()),
                    anchorIds, List.of(), budget, CityBlueprint.GrowthBias.BALANCED, null, seeds, true));
        }

        for (CityBlueprint.Landscape landscape : blueprint.outdoorPlan().landscapes()) {
            CityBlueprintReferenceCatalog.LandscapeProfile profile = catalog.landscapeProfiles().get(
                    landscape.landscapeProfileRef());
            if (profile == null) {
                throw new IllegalArgumentException("CITY_OUTDOOR_LANDSCAPE_PROFILE_UNKNOWN:"
                        + landscape.landscapeProfileRef());
            }
            LandUseRule rule = requireRule(catalog, profile.landUseRuleRef());
            CityBlueprintReferenceCatalog.SurfaceRecipe recipe = requireRecipe(catalog, profile.surfaceRecipeRef());
            List<AnchorData> attached = requiredGroups(anchorsByGroup, landscape.attachedGroupIds(),
                    "CITY_OUTDOOR_LANDSCAPE_ATTACHED_GROUP_UNKNOWN", false);
            BlockPoint origin = attached.isEmpty() ? center(terrain.planningBounds())
                    : centroid(attached.stream().map(AnchorData::footprint).toList());
            WaterGuidance waterGuidance = switch (landscape.growthRelation()) {
                case TOWARD_WATER, ALONG_WATER -> waterGuidance(terrain, origin, profile.landscapeType(),
                        landscape.landscapeId());
                default -> null;
            };
            BlockPoint reference = landscapeReference(anchorsByGroup, landscape, waterGuidance);
            CityBlueprint.GrowthBias blueprintBias = landscape.growthRelation()
                    == CityBlueprint.LandscapeGrowthRelation.AWAY_FROM_REFERENCE
                    ? CityBlueprint.GrowthBias.AWAY_FROM_REFERENCE
                    : landscape.growthRelation() == CityBlueprint.LandscapeGrowthRelation.TOWARD_WATER
                    ? CityBlueprint.GrowthBias.TOWARD_REFERENCE : CityBlueprint.GrowthBias.BALANCED;
            int parcelCount = parcelCount(landscape.continuity());
            List<BlockPoint> seedCandidates = landscape.growthRelation()
                    == CityBlueprint.LandscapeGrowthRelation.ALONG_WATER
                    ? waterGuidance.shorelineSeeds()
                    : !attached.isEmpty()
                    ? seedsForGround(attached, blueprintBias, reference)
                    : patchSeeds(terrain, landscape.preferredPatchRefs(), profile.landscapeType(),
                    parcelCount, origin);
            List<BlockPoint> seeds = landscape.continuity() == CityBlueprint.LandscapeContinuity.CONTINUOUS
                    && landscape.growthRelation() != CityBlueprint.LandscapeGrowthRelation.ALONG_WATER
                    ? seedCandidates
                    : distributedSeeds(seedCandidates, parcelCount, origin);
            if (landscape.required() && seeds.isEmpty()) {
                throw new IllegalArgumentException("CITY_OUTDOOR_REQUIRED_LANDSCAPE_HAS_NO_SEED:"
                        + landscape.landscapeId());
            }
            AreaBudget budget = landscapeBudget(rule, profile.baseArea(landscape.extentClass()),
                    landscape.intensity());
            if (landscape.required()) {
                int capacity = reachableCapacity(terrain, seeds, profile.landscapeType());
                if (capacity < budget.min()) {
                    throw new IllegalArgumentException("CITY_OUTDOOR_REQUIRED_LANDSCAPE_BELOW_MIN:"
                            + landscape.landscapeId() + ':' + capacity + '<' + budget.min());
                }
            }
            LandUseSurfaceSettings settings = surfaceSettings(rule, recipe, recipe.autoConnectDefault());
            List<String> anchorIds = attached.stream().map(AnchorData::anchorId).sorted().toList();
            List<LandUseAreaPlan.GateSlot> gates = attached.stream().flatMap(member -> member.gates().stream())
                    .sorted(Comparator.comparing(LandUseAreaPlan.GateSlot::gateId)).toList();
            gates.stream().map(CityOutdoorBlueprintCompiler::corridor).forEach(corridors::add);
            List<LandUseSeedGroup.GrowthRegion> regions = landscapeRegions(landscape.landscapeId(), anchorIds,
                    seeds, budget, landscape.continuity());
            LandUseSeedGroup.GrowthBias resolvedBias = landscape.growthRelation()
                    == CityBlueprint.LandscapeGrowthRelation.ALONG_WATER
                    ? new LandUseSeedGroup.GrowthBias(LandUseSeedGroup.GrowthBiasMode.ALONG_WATER,
                    waterGuidance.referencePoint(), waterGuidance.axisX(), waterGuidance.axisZ())
                    : bias(blueprintBias, reference);
            groups.add(new LandUseSeedGroup(landscape.landscapeId(), rule, settings, anchorIds, allFootprints,
                    seeds, gates, budget.min(), budget.preferred(), budget.max(), rule.actionBudget(),
                    rule.competitionWeight(), regions, resolvedBias,
                    LandUseSeedGroup.TerrainBias.valueOf(landscape.terrainPolicy().name()),
                    landscape.preferredPatchRefs()));
            if (profile.membership() == CityBlueprint.OutdoorMembership.URBAN) {
                urbanGroupIds.add(landscape.landscapeId());
                attached.stream().map(AnchorData::footprint).forEach(urbanFootprints::add);
            }
            sourceIntents.add(sourceIntent(landscape.landscapeId(), CityOutdoorIntentPlan.SourceKind.LANDSCAPE,
                    landscape.landscapeProfileRef(), rule.ruleRef(), recipe.surfaceRecipeRef(), profile.membership(),
                    landscape.extentClass(), landscape.intensity(), landscape.continuity(),
                    landscape.growthRelation(), landscape.terrainPolicy(), landscape.attachedGroupIds(),
                    anchorIds, landscape.preferredPatchRefs(), budget, blueprintBias, reference, seeds,
                    landscape.required()));
        }

        groups.sort(Comparator.comparing(LandUseSeedGroup::groupId));
        corridors = corridors.stream().collect(java.util.stream.Collectors.toMap(
                LandUseAreaPlan.CorridorExclusion::exclusionId, value -> value, (left, right) -> left,
                LinkedHashMap::new)).values().stream().toList();
        sourceIntents.sort(Comparator.comparing(CityOutdoorIntentPlan.SourceIntent::sourceId));
        int closeRadius = closeRadius(blueprint.outdoorPlan().envelopeProfile());
        CityUrbanResidualResolver.ResidualPolicy residualPolicy = CityUrbanResidualResolver.ResidualPolicy.absorb();
        CityUrbanResidualResolver.Config residualConfig = urbanGroupIds.isEmpty()
                ? CityUrbanResidualResolver.Config.disabled()
                : new CityUrbanResidualResolver.Config(true, closeRadius, urbanGroupIds, urbanFootprints,
                residualPolicy);
        CityOutdoorIntentPlan intent = intent(blueprint, catalog, sourceHashes, sourceIntents, urbanGroupIds,
                closeRadius);
        return new Result(new LandUseSourceResolver.Resolution(groups, corridors, List.of(),
                Long.toUnsignedString(blueprint.generationSeed())), residualConfig, intent);
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

    private static AreaBudget spatialBudget(LandUseRule rule,
                                            List<AnchorData> anchors,
                                            CityBlueprint.ExtentClass extent,
                                            CityBlueprint.SharedSpaceType spaceType,
                                            CityBlueprint.SpatialHierarchy hierarchy) {
        BlockBounds ensemble = bounds(anchors.stream().map(AnchorData::footprint).toList());
        int footprintArea = anchors.stream().mapToInt(anchor -> area(anchor.footprint())).sum();
        int interstitialArea = Math.max(0, area(ensemble) - footprintArea);
        double multiplier = switch (extent) {
            case SMALL -> 0.75;
            case MEDIUM -> 1.0;
            case LARGE -> 1.35;
        };
        double hierarchyMultiplier = switch (hierarchy) {
            case PRIMARY -> 1.35;
            case SECONDARY -> 1.1;
            case LOCAL -> 0.9;
        };
        double typeMultiplier = switch (spaceType) {
            case CIVIC_SQUARE -> 1.25;
            case MARKET_STREET -> 1.15;
            case RESIDENTIAL_COURT, FARMSTEAD -> 1.0;
            case GENERAL_URBAN -> 1.05;
        };
        int ensembleTarget = interstitialArea + anchors.size() * 96;
        int preferred = clamp((int) Math.round(Math.max(rule.preferredArea(footprintArea), ensembleTarget)
                        * multiplier * hierarchyMultiplier * typeMultiplier),
                rule.minAreaBlocks(), rule.maxAreaBlocks());
        return budgetWithinRule(rule, preferred);
    }

    private static LandUseRule sharedSpaceRule(LandUseRule source, CityBlueprint.OutdoorMembership membership) {
        BoundaryPolicy boundary = membership == CityBlueprint.OutdoorMembership.URBAN
                ? BoundaryPolicy.OPEN : source.boundaryPolicy();
        return new LandUseRule(source.ruleRef(), source.landUseType(), source.semanticTerms(),
                source.footprintMultiplier(), source.extraAreaBlocks(), source.minAreaBlocks(),
                source.maxAreaBlocks(), source.actionBudget(), source.baseStepCost(), source.slopeCost(),
                source.reliefCost(), source.waterCost(), source.forestAffinity(), source.competitionWeight(),
                false, source.surfacePolicy(), source.vegetationPolicy(), boundary, source.decorationPolicy());
    }

    private static AreaBudget landscapeBudget(LandUseRule rule,
                                              int baseArea,
                                              CityBlueprint.OutdoorIntensity intensity) {
        double multiplier = switch (intensity) {
            case LOW -> 0.75;
            case MEDIUM -> 1.0;
            case HIGH -> 1.25;
        };
        int preferred = clamp((int) Math.round(baseArea * multiplier),
                rule.minAreaBlocks(), rule.maxAreaBlocks());
        return budgetWithinRule(rule, preferred);
    }

    private static AreaBudget budgetWithinRule(LandUseRule rule, int preferred) {
        int min = clamp((int) Math.floor(preferred * 0.7), rule.minAreaBlocks(), preferred);
        int max = clamp((int) Math.ceil(preferred * 1.2), preferred, rule.maxAreaBlocks());
        return new AreaBudget(min, preferred, max);
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
                recipe.channelWaterBlockId(), recipe.channelBankOverlayBlockId(), null, materials);
    }

    private static List<BlockPoint> seedsForGround(List<AnchorData> members,
                                                   CityBlueprint.GrowthBias growthBias,
                                                   BlockPoint reference) {
        Set<BlockPoint> result = new LinkedHashSet<>();
        for (AnchorData member : members) {
            if (growthBias == CityBlueprint.GrowthBias.BALANCED || reference == null) {
                result.addAll(perimeterSeeds(member.footprint()));
            } else {
                result.addAll(directionalSeeds(member.footprint(), reference,
                        growthBias == CityBlueprint.GrowthBias.AWAY_FROM_REFERENCE));
            }
        }
        return result.stream().sorted(POINT_ORDER).toList();
    }

    private static List<BlockPoint> spatialSeeds(CityBlueprint.SpatialGround ground,
                                                 List<AnchorData> members,
                                                 Map<String, List<AnchorData>> anchorsByGroup,
                                                 List<CityBlueprint.Relation> relations) {
        Set<BlockPoint> seeds = new LinkedHashSet<>();
        BlockPoint ensembleCenter = centroid(members.stream().map(AnchorData::footprint).toList());
        for (AnchorData member : members) {
            if (!member.gates().isEmpty()) {
                member.gates().stream().map(LandUseAreaPlan.GateSlot::block).forEach(seeds::add);
            } else {
                seeds.add(thresholdToward(member.footprint(), ensembleCenter));
            }
        }

        List<AnchorData> connected = new ArrayList<>();
        for (AnchorData member : members.stream().sorted(Comparator.comparing(AnchorData::anchorId)).toList()) {
            if (!connected.isEmpty()) {
                AnchorData nearest = connected.stream().min(Comparator
                        .comparingInt((AnchorData value) -> manhattan(center(value.footprint()), center(member.footprint())))
                        .thenComparing(AnchorData::anchorId)).orElseThrow();
                seeds.addAll(line(center(nearest.footprint()), center(member.footprint())));
            }
            connected.add(member);
        }

        for (CityBlueprint.Relation relation : relations) {
            String otherId = relation.fromGroupId().equals(ground.sourceGroupId()) ? relation.toGroupId()
                    : relation.toGroupId().equals(ground.sourceGroupId()) ? relation.fromGroupId() : "";
            if (otherId.isBlank() || (!relation.relationKind().equals(CityBlueprint.RelationKind.CONNECTION)
                    && !relation.relationKind().equals(CityBlueprint.RelationKind.HIERARCHY)
                    && !relation.relationKind().equals(CityBlueprint.RelationKind.ADJACENCY))) continue;
            List<AnchorData> other = anchorsByGroup.getOrDefault(otherId, List.of());
            if (!other.isEmpty()) {
                BlockPoint otherCenter = centroid(other.stream().map(AnchorData::footprint).toList());
                seeds.addAll(line(ensembleCenter, otherCenter));
            }
        }
        return seeds.stream().sorted(POINT_ORDER).toList();
    }

    private static BlockPoint thresholdToward(BlockBounds footprint, BlockPoint target) {
        BlockPoint source = center(footprint);
        int dx = target.x() - source.x();
        int dz = target.z() - source.z();
        if (Math.abs(dx) > Math.abs(dz)) {
            return new BlockPoint(dx < 0 ? footprint.minX() - 1 : footprint.maxX() + 1, source.z());
        }
        return new BlockPoint(source.x(), dz < 0 ? footprint.minZ() - 1 : footprint.maxZ() + 1);
    }

    private static List<BlockPoint> line(BlockPoint start, BlockPoint end) {
        List<BlockPoint> result = new ArrayList<>();
        int x = start.x();
        int z = start.z();
        int dx = Math.abs(end.x() - x);
        int dz = Math.abs(end.z() - z);
        int sx = x < end.x() ? 1 : -1;
        int sz = z < end.z() ? 1 : -1;
        int error = dx - dz;
        while (true) {
            result.add(new BlockPoint(x, z));
            if (x == end.x() && z == end.z()) break;
            int doubled = error * 2;
            if (doubled > -dz) {
                error -= dz;
                x += sx;
            }
            if (doubled < dx) {
                error += dx;
                z += sz;
            }
        }
        return result;
    }

    private static int manhattan(BlockPoint left, BlockPoint right) {
        return Math.abs(left.x() - right.x()) + Math.abs(left.z() - right.z());
    }

    private static List<BlockPoint> perimeterSeeds(BlockBounds bounds) {
        Set<BlockPoint> points = new LinkedHashSet<>();
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            points.add(new BlockPoint(x, bounds.minZ() - 1));
            points.add(new BlockPoint(x, bounds.maxZ() + 1));
        }
        for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
            points.add(new BlockPoint(bounds.minX() - 1, z));
            points.add(new BlockPoint(bounds.maxX() + 1, z));
        }
        return points.stream().sorted(POINT_ORDER).toList();
    }

    private static List<BlockPoint> directionalSeeds(BlockBounds bounds,
                                                     BlockPoint reference,
                                                     boolean away) {
        BlockPoint center = center(bounds);
        int dx = center.x() - reference.x();
        int dz = center.z() - reference.z();
        if (!away) {
            dx = -dx;
            dz = -dz;
        }
        Set<BlockPoint> result = new LinkedHashSet<>();
        if (Math.abs(dx) >= Math.abs(dz)) {
            int x = dx >= 0 ? bounds.maxX() + 1 : bounds.minX() - 1;
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) result.add(new BlockPoint(x, z));
        }
        if (Math.abs(dz) >= Math.abs(dx)) {
            int z = dz >= 0 ? bounds.maxZ() + 1 : bounds.minZ() - 1;
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) result.add(new BlockPoint(x, z));
        }
        return result.stream().sorted(POINT_ORDER).toList();
    }

    private static BlockPoint referencePoint(Map<String, List<AnchorData>> anchorsByGroup,
                                             List<String> referenceGroupIds,
                                             CityBlueprint.GrowthBias growthBias) {
        if (growthBias == CityBlueprint.GrowthBias.BALANCED) return null;
        List<AnchorData> references = requiredGroups(anchorsByGroup, referenceGroupIds,
                "CITY_OUTDOOR_REFERENCE_GROUP_UNKNOWN");
        return centroid(references.stream().map(AnchorData::footprint).toList());
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

    private static List<BlockPoint> patchSeeds(LandUseTerrainField terrain,
                                               List<String> preferredPatchRefs,
                                               CityBlueprintReferenceCatalog.LandscapeType type,
                                               int count,
                                               BlockPoint origin) {
        Set<String> refs = new HashSet<>(preferredPatchRefs);
        List<BlockPoint> candidates = terrain.cells().stream()
                .filter(cell -> suitable(cell, type) && refs.contains(cell.landformPatchId()))
                .map(CityOutdoorBlueprintCompiler::cellCenter)
                .distinct().sorted(POINT_ORDER).toList();
        return distributedSeeds(candidates, count, origin);
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

    private static int parcelCount(CityBlueprint.LandscapeContinuity continuity) {
        return switch (continuity) {
            case CONTINUOUS -> 1;
            case MULTI_PARCEL -> 2;
            case PATCHY -> 4;
        };
    }

    private static List<BlockPoint> distributedSeeds(List<BlockPoint> candidates,
                                                     int count,
                                                     BlockPoint origin) {
        List<BlockPoint> remaining = new ArrayList<>(candidates.stream().distinct().sorted(POINT_ORDER).toList());
        if (remaining.size() <= count) return List.copyOf(remaining);
        List<BlockPoint> selected = new ArrayList<>();
        BlockPoint first = remaining.stream().min(Comparator.<BlockPoint>comparingLong(point ->
                        squaredDistance(origin, point)).thenComparing(POINT_ORDER)).orElseThrow();
        selected.add(first);
        remaining.remove(first);
        while (selected.size() < count && !remaining.isEmpty()) {
            BlockPoint next = remaining.stream().max(Comparator.<BlockPoint>comparingLong(point -> selected.stream()
                            .mapToLong(chosen -> squaredDistance(chosen, point)).min().orElse(0L))
                    .thenComparing(POINT_ORDER.reversed())).orElseThrow();
            selected.add(next);
            remaining.remove(next);
        }
        return selected.stream().sorted(POINT_ORDER).toList();
    }

    private static List<LandUseSeedGroup.GrowthRegion> landscapeRegions(
            String landscapeId,
            List<String> anchorIds,
            List<BlockPoint> seeds,
            AreaBudget budget,
            CityBlueprint.LandscapeContinuity continuity) {
        int count = Math.min(parcelCount(continuity), Math.max(1, seeds.size()));
        if (continuity == CityBlueprint.LandscapeContinuity.CONTINUOUS) count = 1;
        List<List<BlockPoint>> seedsByRegion = new ArrayList<>();
        for (int index = 0; index < count; index++) seedsByRegion.add(new ArrayList<>());
        for (int index = 0; index < seeds.size(); index++) seedsByRegion.get(index % count).add(seeds.get(index));
        List<LandUseSeedGroup.GrowthRegion> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String suffix = count == 1 ? "landscape" : "parcel_" + (index + 1);
            result.add(new LandUseSeedGroup.GrowthRegion(landscapeId + "::" + suffix, anchorIds,
                    seedsByRegion.get(index), share(budget.min(), count, index),
                    share(budget.preferred(), count, index), share(budget.max(), count, index)));
        }
        return List.copyOf(result);
    }

    private static int share(int total, int count, int index) {
        return total / count + (index < total % count ? 1 : 0);
    }

    private static int reachableCapacity(LandUseTerrainField terrain,
                                         List<BlockPoint> seeds,
                                         CityBlueprintReferenceCatalog.LandscapeType type) {
        Map<CellKey, LandUseTerrainField.Cell> cells = new HashMap<>();
        terrain.cells().stream().filter(cell -> suitable(cell, type))
                .forEach(cell -> cells.put(new CellKey(cell.cellX(), cell.cellZ()), cell));
        ArrayDeque<CellKey> queue = new ArrayDeque<>();
        Set<CellKey> visited = new HashSet<>();
        for (BlockPoint seed : seeds) {
            CellKey key = new CellKey(Math.floorDiv(seed.x(), terrain.cellStepBlocks()),
                    Math.floorDiv(seed.z(), terrain.cellStepBlocks()));
            if (cells.containsKey(key) && visited.add(key)) queue.add(key);
        }
        while (!queue.isEmpty()) {
            CellKey key = queue.removeFirst();
            for (int[] direction : DIRECTIONS) {
                CellKey next = new CellKey(key.x() + direction[0], key.z() + direction[1]);
                if (cells.containsKey(next) && visited.add(next)) queue.addLast(next);
            }
        }
        long blocks = (long) visited.size() * terrain.cellStepBlocks() * terrain.cellStepBlocks();
        return blocks > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) blocks;
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

    private static int closeRadius(CityBlueprint.EnvelopeProfile profile) {
        return switch (profile) {
            case COMPACT -> 8;
            case BALANCED -> 16;
            case LOOSE -> 24;
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
            JsonObject footprint = object(item, "lockedActualFootprint");
            if (footprint.size() == 0) footprint = object(item, "actualFootprint");
            if (footprint.size() == 0) {
                throw new IllegalArgumentException("CITY_OUTDOOR_D6_FOOTPRINT_MISSING:" + anchorId);
            }
            result.computeIfAbsent(groupId, ignored -> new ArrayList<>()).add(new AnchorData(anchorId, groupId,
                    bounds(footprint), gates(item, anchorId)));
        }
        result.replaceAll((ignored, values) -> values.stream().sorted(Comparator.comparing(AnchorData::anchorId))
                .toList());
        return result;
    }

    private static List<LandUseAreaPlan.GateSlot> gates(JsonObject item, String anchorId) {
        JsonObject transformed = object(object(item, "templatePlacementPlan"), "transformed");
        List<LandUseAreaPlan.GateSlot> result = new ArrayList<>();
        int ordinal = 0;
        for (JsonElement element : array(transformed, "roadEntrances")) {
            if (!element.isJsonObject()) continue;
            JsonObject entrance = element.getAsJsonObject();
            JsonObject world = object(entrance, "worldPosition");
            if (world.size() == 0) continue;
            CardinalDirection direction = CardinalDirection.from(stringValue(entrance, "direction", ""), null);
            if (direction == null) continue;
            String entranceId = stringValue(entrance, "entranceId", "entrance_" + (++ordinal));
            result.add(new LandUseAreaPlan.GateSlot(anchorId + "::" + entranceId,
                    new BlockPoint(requiredInt(world, "x"), requiredInt(world, "z")), direction, anchorId));
        }
        return result;
    }

    private static LandUseAreaPlan.CorridorExclusion corridor(LandUseAreaPlan.GateSlot gate) {
        int endX = gate.block().x() + gate.direction().dx() * 3;
        int endZ = gate.block().z() + gate.direction().dz() * 3;
        return new LandUseAreaPlan.CorridorExclusion(gate.gateId() + "_corridor",
                new BlockBounds(Math.min(gate.block().x(), endX), Math.min(gate.block().z(), endZ),
                        Math.max(gate.block().x(), endX), Math.max(gate.block().z(), endZ)), gate.gateId());
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

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
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

    private static JsonArray array(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonArray()
                ? object.getAsJsonArray(key) : new JsonArray();
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
                              List<LandUseAreaPlan.GateSlot> gates) {
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
