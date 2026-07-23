import fs from "node:fs";
import path from "node:path";

const repoRoot = path.resolve(import.meta.dirname, "..", "..", "..", "..", "..");
const runId = "new_save_w_20260722_01";
const cityId = "city_new_save_rice_town_20260722_01";
const runRoot = path.join(repoRoot, "run", "realm_debug", runId);
const reviewPath = path.join(runRoot, `city_d3_${cityId}`, "city_landform_review_package.json");
const statePath = path.join(runRoot, `city_d4_array_layout_${cityId}`, "d4_array_layout_loop_state.json");
const outputPath = path.join(import.meta.dirname, "d4_array_layout_loop_state.provenance_repaired.json");

const review = JSON.parse(fs.readFileSync(reviewPath, "utf8"));
const state = JSON.parse(fs.readFileSync(statePath, "utf8"));
const step = review.grid.cellStepBlocks;

function contains(patch, point) {
  if (Array.isArray(patch.memberCells) && patch.memberCells.length > 0) {
    return patch.memberCells.some((cell) =>
      point.x >= cell.blockMinX && point.x < cell.blockMinX + step
      && point.z >= cell.blockMinZ && point.z < cell.blockMinZ + step);
  }
  const bounds = patch.blockBounds;
  return point.x >= bounds.minX && point.x <= bounds.maxX
    && point.z >= bounds.minZ && point.z <= bounds.maxZ;
}

function refsFor(point) {
  const patch = review.landformPatches.find((candidate) => contains(candidate, point));
  if (!patch) {
    throw new Error(`No D3 patch contains anchorBlock ${JSON.stringify(point)}`);
  }
  return patch.mapLabel && patch.mapLabel !== patch.landformPatchId
    ? [patch.landformPatchId, patch.mapLabel]
    : [patch.landformPatchId];
}

let repairedFields = 0;
const repairedAnchors = new Set();

function visit(value) {
  if (Array.isArray(value)) {
    value.forEach(visit);
    return;
  }
  if (!value || typeof value !== "object") {
    return;
  }
  if (value.anchorBlock && (Array.isArray(value.sourcePatchIds) || Array.isArray(value.sourcePatchRefs))) {
    const refs = refsFor(value.anchorBlock);
    if (Array.isArray(value.sourcePatchIds)) {
      value.sourcePatchIds = [...refs];
      repairedFields += 1;
    }
    if (Array.isArray(value.sourcePatchRefs)) {
      value.sourcePatchRefs = [...refs];
      repairedFields += 1;
    }
    if (value.anchorId) {
      repairedAnchors.add(value.anchorId);
    }
  }
  Object.values(value).forEach(visit);
}

visit(state);
fs.writeFileSync(outputPath, `${JSON.stringify(state, null, 2)}\n`, "utf8");
console.log(JSON.stringify({ outputPath, repairedFields, repairedAnchorCount: repairedAnchors.size }, null, 2));
