import assert from "node:assert/strict";
import test from "node:test";

import { realmTools } from "../dist/src/realm/tools.js";

test("publishes the program-only context tool and one-shot Blueprint submit schema", () => {
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
    ["city_blueprint.v0.4"]);
  assert.equal(groupProperties.connectionPlan.additionalProperties, false);
  assert.deepEqual(groupProperties.connectionPlan.properties.parameters.properties.sideMode.enum,
    ["LEFT", "RIGHT", "BOTH"]);
  assert.deepEqual(groupProperties.connectionPlan.properties.parameters.properties.widthClass.enum,
    ["NARROW", "MEDIUM", "WIDE"]);
  assert.equal(submit.inputSchema.properties.cityBlueprint.properties.groups.items
    .properties.attachedFeatures.maxItems, 0);
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
});
