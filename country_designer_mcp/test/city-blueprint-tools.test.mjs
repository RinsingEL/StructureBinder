import assert from "node:assert/strict";
import test from "node:test";

import { realmTools, blueprintReferenceCatalogSchema } from "../dist/src/realm/tools.js";

test("publishes the program-only context tool and retryable structure plus outdoor Blueprint schema", () => {
  const prepare = realmTools.find((tool) => tool.name === "city_prepare_d4_blueprint_context");
  const submit = realmTools.find((tool) => tool.name === "city_submit_d4_blueprint");
  const autoStatus = realmTools.find((tool) => tool.name === "city_post_d4_auto_compile_status");
  const designQueueRefresh = realmTools.find((tool) => tool.name === "city_design_queue_refresh");
  const designQueueStatus = realmTools.find((tool) => tool.name === "city_design_queue_status");
  assert.ok(prepare);
  assert.ok(submit);
  assert.ok(autoStatus);
  assert.ok(designQueueRefresh);
  assert.ok(designQueueStatus);
  const d3 = realmTools.find((tool) => tool.name === "city_plan_d3");
  const showCandidates = realmTools.find((tool) => tool.name === "patch_explorer_show_candidates");
  assert.match(d3.description, /自动打开 city_d4 Patch Explorer/);
  assert.match(showCandidates.description, /解锁 city_prepare_d4_blueprint_context/);
  assert.match(showCandidates.description, /structurePlacementCapacity/);
  assert.match(showCandidates.description, /hardLegal 只表示地块非空/);
  assert.match(prepare.description, /Top Patch review/);
  assert.match(prepare.description, /5 次程序编译失败预算/);
  assert.match(submit.description, /failureCount<5/);
  assert.match(submit.description, /禁止读取服务端源码/);
  assert.match(autoStatus.description, /workflowResponse/);
  assert.match(autoStatus.description, /禁止转去读取服务端源码/);
  assert.match(designQueueStatus.description, /waiting_for_patch_review/);
  assert.equal(submit.inputSchema.properties.autoAdvanceAfterD4.type, "boolean");
  assert.deepEqual(autoStatus.inputSchema.required, ["runId", "citySeedId"]);
  assert.deepEqual(designQueueRefresh.inputSchema.properties.orderingMode.enum,
    ["global_radial", "realm_grouped"]);
  assert.equal(prepare.inputSchema.additionalProperties, false);
  assert.deepEqual(prepare.inputSchema.required, [
    "runId", "citySeedId",
  ]);
  assert.equal(submit.inputSchema.properties.cityBlueprint.additionalProperties, false);
  assert.deepEqual(submit.inputSchema.properties.cityBlueprint.properties.groups.items
    .properties.groupKind.enum, ["STRUCTURE"]);
  const groupProperties = submit.inputSchema.properties.cityBlueprint.properties.groups.items.properties;
  assert.equal(groupProperties.primaryPatchRef, undefined);
  assert.equal(groupProperties.selectionPolicyRef, undefined);
  assert.equal(groupProperties.preferredPatchRefs.minItems, 1);
  assert.deepEqual(groupProperties.preferredPatchZone.enum,
    ["CENTER", "NORTH", "EAST", "SOUTH", "WEST"]);
  assert.deepEqual(groupProperties.placementRelation.properties.kind.enum,
    ["BETWEEN_PATCHES", "ALONG_PATCH_BOUNDARY", "BETWEEN_GROUPS"]);
  assert.deepEqual(groupProperties.placementRelation.required,
    ["kind", "patchRefs", "groupRefs"]);
  assert.deepEqual(groupProperties.extentClass.enum, ["SMALL", "MEDIUM", "LARGE"]);
  assert.deepEqual(groupProperties.densityClass.enum, ["SPARSE", "BALANCED", "DENSE"]);
  assert.deepEqual(submit.inputSchema.properties.cityBlueprint.properties.schema.enum,
    ["city_blueprint"]);
  assert.equal(groupProperties.connectionPlan.additionalProperties, false);
  const connectionParameters = groupProperties.connectionPlan.properties.parameters;
  assert.match(connectionParameters.description, /严禁混填/);
  assert.equal(connectionParameters.anyOf.length, 2);
  assert.equal(connectionParameters.anyOf[0].additionalProperties, false);
  assert.deepEqual(connectionParameters.anyOf[0].properties.clusterShape.enum,
    ["ORGANIC_COMPACT", "GRID", "COURTYARD", "L_SHAPE", "U_SHAPE"]);
  assert.equal(connectionParameters.anyOf[1].additionalProperties, false);
  assert.deepEqual(connectionParameters.anyOf[1].properties.sideMode.enum,
    ["LEFT", "RIGHT", "BOTH"]);
  assert.deepEqual(connectionParameters.anyOf[1].properties.widthClass.enum,
    ["NARROW", "MEDIUM", "WIDE"]);
  assert.equal(connectionParameters.anyOf[0].properties.sideMode, undefined);
  assert.equal(connectionParameters.anyOf[1].properties.clusterShape, undefined);
  assert.equal(submit.inputSchema.properties.cityBlueprint.properties.groups.items
    .properties.attachedFeatures.maxItems, 0);

  const blueprint = submit.inputSchema.properties.cityBlueprint;
  assert.ok(blueprint.required.includes("arrayCompositions"));
  const composition = blueprint.properties.arrayCompositions.items;
  assert.equal(composition.additionalProperties, false);
  assert.deepEqual(composition.required,
    ["compositionId", "algorithmProfileRef", "centerGroupId", "memberGroupIds"]);
  assert.equal(composition.properties.memberGroupIds.minItems, 1);
  assert.ok(blueprint.required.includes("outdoorPlan"));
  const outdoor = blueprint.properties.outdoorPlan;
  assert.equal(outdoor.additionalProperties, false);
  assert.deepEqual(outdoor.required,
    ["mode", "envelopeProfile", "foundationProfileRef", "spatialGrounds", "landscapes"]);
  assert.deepEqual(outdoor.properties.mode.enum, ["GENERATE", "PRESERVE"]);
  assert.deepEqual(outdoor.properties.envelopeProfile.enum, ["COMPACT", "BALANCED", "LOOSE"]);

  assert.ok(outdoor.properties.foundationProfileRef);
  const ground = outdoor.properties.spatialGrounds.items;
  assert.equal(ground.additionalProperties, false);
  assert.deepEqual(ground.required,
    ["sourceGroupId", "sharedSpaceType", "hierarchyLevel", "membership"]);
  assert.deepEqual(ground.properties.sharedSpaceType.enum,
    ["CIVIC_SQUARE", "MARKET_STREET", "RESIDENTIAL_COURT", "FARMSTEAD", "GENERAL_URBAN"]);
  assert.deepEqual(ground.properties.hierarchyLevel.enum, ["PRIMARY", "SECONDARY", "LOCAL"]);
  assert.deepEqual(ground.properties.membership.enum, ["URBAN", "LANDSCAPE"]);

  const landscape = outdoor.properties.landscapes.items;
  assert.equal(landscape.additionalProperties, false);
  assert.deepEqual(landscape.required, [
    "landscapeId", "landscapeProfileRef", "purpose", "originMode", "instanceCount", "parcelCount",
    "preferredPatchRefs", "terrainPolicy", "required", "fillSelection",
  ]);
  assert.deepEqual(landscape.properties.purpose.enum, ["FUNCTIONAL", "COMPOSITIONAL", "AMBIENT"]);
  assert.deepEqual(landscape.properties.originMode.enum, ["ATTACHED", "FREE_STANDING"]);
  assert.equal(landscape.properties.instanceCount.minimum, 1);
  assert.equal(landscape.properties.parcelCount.minimum, 1);
  assert.equal(landscape.oneOf.length, 2);
  assert.deepEqual(landscape.oneOf[0].required, ["owner"]);
  assert.deepEqual(landscape.oneOf[1].required, ["placementDomain"]);
  assert.equal(landscape.oneOf[1].properties.required.const, false);
  assert.deepEqual(landscape.properties.terrainPolicy.enum, ["CONFORM", "BALANCED", "ASSERTIVE"]);
  const fillVariant = landscape.properties.fillSelection.properties.variants.items;
  assert.equal(fillVariant.additionalProperties, false);
  assert.deepEqual(fillVariant.required,
    ["fillProfileRef", "selectionWeight", "roleShares", "contentWeights"]);
  assert.equal(landscape.properties.fillSelection.properties.variants.minItems, 1);
  assert.equal(fillVariant.properties.roleShares.minItems, 1);
  assert.equal(fillVariant.properties.roleShares.uniqueItems, undefined);
  assert.match(fillVariant.properties.roleShares.description, /有序区域接力/);
  assert.equal(fillVariant.properties.roleShares.items.additionalProperties, false);
  assert.deepEqual(fillVariant.properties.roleShares.items.properties.growthForm.enum,
    ["PATCH", "CORRIDOR"]);
  assert.equal(fillVariant.properties.contentWeights.items.additionalProperties, false);

  assert.equal(outdoor.properties.structureGrounds, undefined);
  assert.equal(outdoor.properties.residualPolicy, undefined);
});

test("does not publish superseded D4 planning tools", () => {
  const removed = new Set([
    "city_plan_d4",
    "city_plan_d4_candidates",
    "city_plan_d4_array_candidates",
    "city_create_d4_array_layout_loop",
    "city_create_d4_design_loop_state",
    "city_plan_d4_structure_cluster_groups",
    "city_create_d4_candidate_session",
  ]);
  assert.deepEqual(realmTools.filter((tool) => removed.has(tool.name)), []);
});

test("publishes the programmatic compiler and defaults workflow to Blueprint", () => {
  const compile = realmTools.find((tool) => tool.name === "city_compile_d4_blueprint");
  const workflow = realmTools.find((tool) => tool.name === "city_run_workflow");
  assert.ok(compile);
  assert.deepEqual(compile.inputSchema.required, ["runId", "citySeedId"]);
  assert.equal(compile.inputSchema.additionalProperties, false);
  assert.match(compile.description, /不调用 AI/);
  assert.match(compile.description, /最多 5 次/);
  assert.match(compile.description, /禁止读取服务端源码/);
  assert.equal(workflow.inputSchema.properties.d4CandidateMode, undefined);
  assert.match(workflow.description, /同一 Blueprint 自动编译户外空间/);
  assert.equal(workflow.inputSchema.properties.enableLandUseLayer, undefined);
  assert.equal(workflow.inputSchema.properties.landUseIntentPlan, undefined);
  assert.deepEqual(workflow.inputSchema.required, ["runId", "citySeedId"]);
});

test("publishes the current strict LandUse intent wire shape", () => {
  const landUse = realmTools.find((tool) => tool.name === "city_plan_land_use");
  const intent = landUse.inputSchema.properties.landUseIntentPlan;
  assert.equal(intent.additionalProperties, false);
  assert.deepEqual(intent.properties.schema.enum, ["city_land_use_intent_plan"]);
  assert.ok(intent.properties.surfaceAlgorithmDefaults);
  assert.ok(intent.properties.surfaceOverrides);

  const defaults = intent.properties.surfaceAlgorithmDefaults.items;
  assert.equal(defaults.additionalProperties, false);
  assert.deepEqual(defaults.required, ["surfaceAlgorithm", "surfaceBlockId"]);
  assert.deepEqual(defaults.properties.surfaceAlgorithm.enum, ["uniform", "contour_bands"]);
  assert.ok(defaults.properties.cropBlockId);
  assert.ok(defaults.properties.channelBankBlockId);
  assert.ok(defaults.properties.channelWaterBlockId);
  assert.ok(defaults.properties.channelBankOverlayBlockId);

  const override = intent.properties.surfaceOverrides.items;
  assert.equal(override.additionalProperties, false);
  assert.deepEqual(override.required, ["targetGroupId"]);
  assert.deepEqual(Object.keys(override.properties).sort(), [
    "algorithmAnchor", "autoConnect", "channelBankBlockId", "channelBankOverlayBlockId",
    "channelWaterBlockId", "cropBlockId", "surfaceAlgorithm", "surfaceBlockId",
    "surfacePrintEnabled", "targetGroupId",
  ]);
  assert.deepEqual(override.properties.surfaceAlgorithm.enum, ["uniform", "contour_bands"]);
  assert.deepEqual(override.properties.algorithmAnchor.required, ["x", "z"]);
});

test("author configuration retains greenery and Landscape profiles without asking the model to supply it", () => {
  const prepare = realmTools.find((tool) => tool.name === "city_prepare_d4_blueprint_context");
  assert.equal(prepare.inputSchema.properties.blueprintReferenceCatalog, undefined);
  const catalog = blueprintReferenceCatalogSchema;
  assert.equal(catalog.additionalProperties, false);
  assert.deepEqual(catalog.required, [
    "schema", "structureRefs", "fillPools", "algorithmProfiles", "compositionProfiles",
    "styleProfiles", "roadProfiles", "surfaceDetailProfiles", "landUseRuleProfile", "foundationProfiles", "surfaceRecipes",
    "landscapeProfiles", "landscapeFillProfiles",
  ]);
  assert.deepEqual(catalog.properties.schema.enum,
    ["city_blueprint_reference_catalog"]);

  for (const namespace of ["structureRefs", "fillPools", "algorithmProfiles", "compositionProfiles",
    "styleProfiles", "roadProfiles", "surfaceDetailProfiles", "foundationProfiles", "surfaceRecipes",
    "landscapeProfiles", "landscapeFillProfiles"]) {
    assert.equal(catalog.properties[namespace].minItems, 1, namespace);
  }
  assert.equal(catalog.properties.structureRefs.items.additionalProperties, false);
  assert.deepEqual(catalog.properties.structureRefs.items.required,
    ["structureRef", "templateCandidates"]);
  assert.equal(catalog.properties.structureRefs.items.properties.templateCandidates.minItems, 1);
  assert.deepEqual(catalog.properties.structureRefs.items.properties.greenParcel.properties.pattern.enum,
    ["FREEFORM", "FIELD_GRID"]);
  assert.deepEqual(catalog.properties.structureRefs.items.properties.greenParcel.properties.density.enum,
    ["LOW", "MEDIUM", "HIGH"]);
  assert.equal(catalog.properties.styleProfiles.items.properties.plantPalette.minItems, 1);
  assert.equal(catalog.properties.styleProfiles.items.properties.plantPalette.items.properties.weight.exclusiveMinimum,
    0);
  assert.deepEqual(catalog.properties.algorithmProfiles.items.properties.algorithm.enum,
    ["COMPACT", "GRID", "LINEAR", "COURTYARD", "ORGANIC_COMPACT", "CENTER_SYMMETRIC"]);
  assert.equal(catalog.properties.algorithmProfiles.items.properties.centerAxisStreetEnabled.type,
    "boolean");
  assert.equal(catalog.properties.algorithmProfiles.items.required.includes("centerAxisStreetEnabled"),
    false);
  assert.deepEqual(catalog.properties.roadProfiles.items.properties.hierarchy.enum,
    ["SIMPLE", "HIERARCHICAL"]);

  const foundation = catalog.properties.foundationProfiles.items;
  assert.equal(foundation.additionalProperties, false);
  assert.deepEqual(foundation.required, ["foundationProfileRef", "landUseRuleRef", "surfaceRecipeRef",
    "structureMarginBlocks", "closeRadiusBlocks", "maxJoinDistanceBlocks"]);
  assert.equal(foundation.properties.structureMarginBlocks.minimum, 0);

  const fillProfile = catalog.properties.landscapeFillProfiles.items;
  assert.equal(fillProfile.additionalProperties, false);
  assert.deepEqual(fillProfile.required, [
    "fillProfileRef", "displayName", "visualIntent", "algorithm", "relayOrigin",
    "compatibleLandscapeTypes", "primaryRoleRef", "roles", "allowedContentRefs", "examples",
  ]);
  assert.deepEqual(fillProfile.properties.algorithm.enum, ["SINGLE_SOURCE_REGION_RELAY"]);
  assert.deepEqual(fillProfile.properties.relayOrigin.enum, ["PARENT_REGION_LOCAL_BOUNDARY"]);
  assert.equal(fillProfile.properties.compatibleLandscapeTypes.minItems, 1);
  assert.equal(fillProfile.properties.roles.minItems, 1);
  assert.deepEqual(fillProfile.properties.roles.items.properties.materialRole.enum,
    ["PRIMARY_CONTENT", "BANK", "WATER", "GROUND"]);
  assert.deepEqual(fillProfile.properties.roles.items.properties.allowedGrowthForms.items.enum,
    ["PATCH", "CORRIDOR"]);
  assert.deepEqual(fillProfile.properties.examples.items.properties.roleShares.items.properties.growthForm.enum,
    ["PATCH", "CORRIDOR"]);
  assert.equal(fillProfile.properties.layerSequence, undefined);
  assert.equal(fillProfile.properties.repeatLayers, undefined);
  assert.equal(fillProfile.properties.fixedShape, undefined);
  assert.equal(fillProfile.properties.geometryFallback, undefined);
  assert.equal(fillProfile.properties.examples.minItems, 1);

  const ruleProfile = catalog.properties.landUseRuleProfile;
  assert.equal(ruleProfile.additionalProperties, false);
  assert.deepEqual(ruleProfile.required, ["schema", "profileId", "rules"]);
  assert.deepEqual(ruleProfile.properties.schema.enum, ["city_land_use_rules"]);
  assert.equal(ruleProfile.properties.rules.minItems, 1);
  const rule = ruleProfile.properties.rules.items;
  assert.equal(rule.additionalProperties, false);
  assert.deepEqual(rule.required, [
    "ruleRef", "landUseType", "semanticTerms", "footprintMultiplier", "extraAreaBlocks",
    "minAreaBlocks", "maxAreaBlocks", "actionBudget", "baseStepCost", "slopeCost", "reliefCost",
    "waterCost", "forestAffinity", "competitionWeight", "mergeSameType", "surfacePolicy",
    "vegetationPolicy", "boundaryPolicy", "decorationPolicy",
  ]);
  assert.deepEqual(rule.properties.surfacePolicy.enum,
    ["PRESERVE", "PAVE", "CULTIVATE", "WATER_ADAPTIVE"]);
  assert.deepEqual(rule.properties.vegetationPolicy.enum,
    ["PRESERVE", "SELECTIVE_CLEAR", "CLEAR"]);
  assert.deepEqual(rule.properties.boundaryPolicy.enum,
    ["OPEN", "FENCE", "HEDGE", "LOW_WALL", "SHORELINE"]);

  const recipes = catalog.properties.surfaceRecipes.items.oneOf;
  assert.equal(recipes.length, 3);
  const [disabled, uniform, contour] = recipes;
  assert.deepEqual(disabled.properties.surfacePrintEnabled.enum, [false]);
  assert.deepEqual(disabled.properties.autoConnectDefault.enum, [false]);
  assert.equal(disabled.properties.surfaceBlockId, undefined);
  assert.deepEqual(uniform.properties.surfacePrintEnabled.enum, [true]);
  assert.deepEqual(uniform.properties.surfaceAlgorithm.enum, ["UNIFORM"]);
  assert.deepEqual(uniform.required, ["surfaceRecipeRef", "surfacePrintEnabled", "autoConnectDefault",
    "surfaceAlgorithm", "surfaceBlockId"]);
  assert.equal(uniform.properties.boundaryBlockId.pattern,
    "^[a-z0-9_.-]+:[a-z0-9/._-]+$");
  assert.deepEqual(contour.properties.surfaceAlgorithm.enum, ["CONTOUR_BANDS"]);
  assert.deepEqual(contour.required, ["surfaceRecipeRef", "surfacePrintEnabled", "autoConnectDefault",
    "surfaceAlgorithm", "surfaceBlockId", "cropBlockId", "channelBankBlockId", "channelWaterBlockId",
    "channelBankOverlayBlockId", "fieldBeforeBlocks", "channelWidthBlocks", "fieldAfterBlocks"]);
  for (const material of ["surfaceBlockId", "cropBlockId", "channelBankBlockId", "channelWaterBlockId",
    "channelBankOverlayBlockId"]) {
    assert.equal(contour.properties[material].pattern,
      "^[a-z0-9_.-]+:[a-z0-9/._-]+$", material);
  }
  assert.equal(contour.properties.boundaryBlockId.pattern,
    "^[a-z0-9_.-]+:[a-z0-9/._-]+$");
  for (const width of ["fieldBeforeBlocks", "channelWidthBlocks", "fieldAfterBlocks"]) {
    assert.equal(contour.properties[width].minimum, 1, width);
  }

  const landscape = catalog.properties.landscapeProfiles.items;
  assert.equal(landscape.additionalProperties, false);
  assert.deepEqual(landscape.properties.landscapeType.enum,
    ["FARMLAND", "COMMON_GREEN", "WOODLAND", "MEADOW", "POND"]);
  assert.equal(landscape.properties.baseAreaSmall.minimum, 1);
  assert.equal(landscape.properties.baseAreaMedium.minimum, 1);
  assert.equal(landscape.properties.baseAreaLarge.minimum, 1);
  assert.deepEqual(landscape.properties.membership.enum, ["URBAN", "LANDSCAPE"]);
  const parcel = landscape.properties.parcelStyle;
  assert.equal(parcel.additionalProperties, false);
  assert.deepEqual(parcel.required, ["parcelCountMin", "parcelCountMax", "parcelAreaMinBlocks",
    "parcelAreaMaxBlocks", "minSharedBoundaryBlocks"]);
  assert.equal(parcel.properties.parcelCountMin.minimum, 1);
  assert.equal(parcel.properties.minSharedBoundaryBlocks.minimum, 1);
});
