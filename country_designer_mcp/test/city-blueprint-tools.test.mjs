import assert from "node:assert/strict";
import test from "node:test";

import { realmTools } from "../dist/src/realm/tools.js";

test("publishes the program-only context tool and one-shot structure plus outdoor Blueprint schema", () => {
  const prepare = realmTools.find((tool) => tool.name === "city_prepare_d4_blueprint_context");
  const submit = realmTools.find((tool) => tool.name === "city_submit_d4_blueprint");
  assert.ok(prepare);
  assert.ok(submit);
  assert.equal(prepare.inputSchema.additionalProperties, false);
  assert.deepEqual(prepare.inputSchema.required, [
    "runId", "citySeedId", "terrasenseProfileSource", "templateCatalogSource", "blueprintReferenceCatalog",
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
  assert.deepEqual(groupProperties.extentClass.enum, ["SMALL", "MEDIUM", "LARGE"]);
  assert.deepEqual(groupProperties.densityClass.enum, ["SPARSE", "BALANCED", "DENSE"]);
  assert.deepEqual(submit.inputSchema.properties.cityBlueprint.properties.schemaVersion.enum,
    ["city_blueprint.v0.5"]);
  assert.equal(groupProperties.connectionPlan.additionalProperties, false);
  assert.deepEqual(groupProperties.connectionPlan.properties.parameters.properties.sideMode.enum,
    ["LEFT", "RIGHT", "BOTH"]);
  assert.deepEqual(groupProperties.connectionPlan.properties.parameters.properties.widthClass.enum,
    ["NARROW", "MEDIUM", "WIDE"]);
  assert.equal(submit.inputSchema.properties.cityBlueprint.properties.groups.items
    .properties.attachedFeatures.maxItems, 0);

  const blueprint = submit.inputSchema.properties.cityBlueprint;
  assert.ok(blueprint.required.includes("outdoorPlan"));
  const outdoor = blueprint.properties.outdoorPlan;
  assert.equal(outdoor.additionalProperties, false);
  assert.deepEqual(outdoor.required,
    ["mode", "envelopeProfile", "structureGrounds", "landscapes", "residualPolicy"]);
  assert.deepEqual(outdoor.properties.mode.enum, ["GENERATE", "PRESERVE"]);
  assert.deepEqual(outdoor.properties.envelopeProfile.enum, ["COMPACT", "BALANCED", "LOOSE"]);

  const ground = outdoor.properties.structureGrounds.items;
  assert.equal(ground.additionalProperties, false);
  assert.deepEqual(ground.required, [
    "sourceGroupId", "landUseRuleRef", "surfaceRecipeRef", "extentClass", "growthBias",
    "referenceGroupIds", "autoConnect", "membership",
  ]);
  assert.deepEqual(ground.properties.growthBias.enum,
    ["BALANCED", "AWAY_FROM_REFERENCE", "TOWARD_REFERENCE"]);
  assert.deepEqual(ground.properties.membership.enum, ["URBAN", "LANDSCAPE"]);

  const landscape = outdoor.properties.landscapes.items;
  assert.equal(landscape.additionalProperties, false);
  assert.deepEqual(landscape.required, [
    "landscapeId", "landscapeProfileRef", "attachedGroupIds", "preferredPatchRefs", "extentClass",
    "intensity", "continuity", "growthRelation", "referenceGroupIds", "terrainPolicy", "required",
  ]);
  assert.deepEqual(landscape.properties.intensity.enum, ["LOW", "MEDIUM", "HIGH"]);
  assert.deepEqual(landscape.properties.continuity.enum, ["CONTINUOUS", "MULTI_PARCEL", "PATCHY"]);
  assert.deepEqual(landscape.properties.growthRelation.enum,
    ["AROUND_SOURCE", "AWAY_FROM_REFERENCE", "TOWARD_WATER", "ALONG_WATER"]);
  assert.deepEqual(landscape.properties.terrainPolicy.enum, ["CONFORM", "BALANCED", "ASSERTIVE"]);

  const residual = outdoor.properties.residualPolicy;
  assert.equal(residual.additionalProperties, false);
  assert.deepEqual(residual.required,
    ["smallEnclosed", "narrowGap", "mediumEnclosed", "largeEnclosed", "exteriorConnected"]);
  assert.deepEqual(residual.properties.smallEnclosed.enum, ["ABSORB_NEIGHBOR", "NATURAL_RESERVE"]);
  assert.deepEqual(residual.properties.narrowGap.enum,
    ["ABSORB_NEIGHBOR", "PATH_OR_VERGE", "NATURAL_RESERVE"]);
  assert.deepEqual(residual.properties.mediumEnclosed.enum,
    ["ABSORB_NEIGHBOR", "COMMON_GREEN", "SERVICE_GROUND", "NATURAL_RESERVE"]);
  assert.deepEqual(residual.properties.largeEnclosed.enum, ["COMMON_GREEN", "NATURAL_RESERVE"]);
  assert.deepEqual(residual.properties.exteriorConnected.enum, ["NATURAL_RESERVE"]);
});

test("labels the old direct anchor endpoint as legacy debug", () => {
  const oldD4 = realmTools.find((tool) => tool.name === "city_plan_d4");
  assert.match(oldD4.description, /Legacy\/debug/);
});

test("publishes the programmatic compiler and defaults workflow to Blueprint", () => {
  const compile = realmTools.find((tool) => tool.name === "city_compile_d4_blueprint");
  const workflow = realmTools.find((tool) => tool.name === "city_run_workflow");
  assert.ok(compile);
  assert.deepEqual(compile.inputSchema.required, ["runId", "citySeedId"]);
  assert.equal(compile.inputSchema.additionalProperties, false);
  assert.match(compile.description, /不调用 AI/);
  assert.ok(workflow.inputSchema.properties.d4CandidateMode.enum.includes("blueprint"));
  assert.match(workflow.inputSchema.properties.d4CandidateMode.description, /默认 blueprint/);
  assert.match(workflow.description, /同一 Blueprint 自动编译户外空间/);
  assert.match(workflow.inputSchema.properties.enableLandUseLayer.description, /仅 legacy\/debug/);
  assert.match(workflow.inputSchema.properties.landUseIntentPlan.description, /正式 blueprint 模式从已接受 CityBlueprint 自动派生/);
});

test("publishes the current strict LandUse v0.3 intent wire shape", () => {
  const landUse = realmTools.find((tool) => tool.name === "city_plan_land_use");
  const intent = landUse.inputSchema.properties.landUseIntentPlan;
  assert.equal(intent.additionalProperties, false);
  assert.deepEqual(intent.properties.schemaVersion.enum, ["city_land_use_intent_plan.v0.3"]);
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

test("requires the v0.3 Blueprint reference catalog for outdoor profiles", () => {
  const prepare = realmTools.find((tool) => tool.name === "city_prepare_d4_blueprint_context");
  const catalog = prepare.inputSchema.properties.blueprintReferenceCatalog;
  assert.match(catalog.description, /city_blueprint_reference_catalog\.v0\.3/);
  assert.match(catalog.description, /户外/);
  assert.equal(catalog.additionalProperties, false);
  assert.deepEqual(catalog.required, [
    "schemaVersion", "structureRefs", "fillPools", "algorithmProfiles", "compositionProfiles",
    "styleProfiles", "roadProfiles", "surfaceDetailProfiles", "landUseRuleProfile", "surfaceRecipes",
    "landscapeProfiles",
  ]);
  assert.deepEqual(catalog.properties.schemaVersion.enum,
    ["city_blueprint_reference_catalog.v0.3"]);

  for (const namespace of ["structureRefs", "fillPools", "algorithmProfiles", "compositionProfiles",
    "styleProfiles", "roadProfiles", "surfaceDetailProfiles", "surfaceRecipes", "landscapeProfiles"]) {
    assert.equal(catalog.properties[namespace].minItems, 1, namespace);
  }
  assert.equal(catalog.properties.structureRefs.items.additionalProperties, false);
  assert.deepEqual(catalog.properties.structureRefs.items.required,
    ["structureRef", "templateCandidates"]);
  assert.equal(catalog.properties.structureRefs.items.properties.templateCandidates.minItems, 1);
  assert.deepEqual(catalog.properties.algorithmProfiles.items.properties.algorithm.enum,
    ["COMPACT", "GRID", "LINEAR", "COURTYARD", "ORGANIC_COMPACT"]);
  assert.deepEqual(catalog.properties.roadProfiles.items.properties.hierarchy.enum,
    ["SIMPLE", "HIERARCHICAL"]);

  const ruleProfile = catalog.properties.landUseRuleProfile;
  assert.equal(ruleProfile.additionalProperties, false);
  assert.deepEqual(ruleProfile.required, ["schemaVersion", "profileId", "rules"]);
  assert.deepEqual(ruleProfile.properties.schemaVersion.enum, ["city_land_use_rules.v0.1"]);
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
  assert.deepEqual(contour.properties.surfaceAlgorithm.enum, ["CONTOUR_BANDS"]);
  assert.deepEqual(contour.required, ["surfaceRecipeRef", "surfacePrintEnabled", "autoConnectDefault",
    "surfaceAlgorithm", "surfaceBlockId", "cropBlockId", "channelBankBlockId", "channelWaterBlockId",
    "channelBankOverlayBlockId"]);
  for (const material of ["surfaceBlockId", "cropBlockId", "channelBankBlockId", "channelWaterBlockId",
    "channelBankOverlayBlockId"]) {
    assert.equal(contour.properties[material].pattern,
      "^[a-z0-9_.-]+:[a-z0-9/._-]+$", material);
  }

  const landscape = catalog.properties.landscapeProfiles.items;
  assert.equal(landscape.additionalProperties, false);
  assert.deepEqual(landscape.properties.landscapeType.enum,
    ["FARMLAND", "COMMON_GREEN", "WOODLAND", "MEADOW", "POND"]);
  assert.equal(landscape.properties.baseAreaSmall.minimum, 1);
  assert.equal(landscape.properties.baseAreaMedium.minimum, 1);
  assert.equal(landscape.properties.baseAreaLarge.minimum, 1);
  assert.deepEqual(landscape.properties.membership.enum, ["URBAN", "LANDSCAPE"]);
});
