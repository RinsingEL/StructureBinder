import fs from "node:fs";
import path from "node:path";

const repoRoot = path.resolve(import.meta.dirname, "..", "..", "..", "..", "..");
const runId = "geomantia_stubbs_mining_town_20260718_01";
const citySeedId = "city_w_island_large_town_20260721";
const runRoot = path.join(repoRoot, "run", "realm_debug", runId);
const inputPath = path.join(
  runRoot,
  `city_d4_array_layout_${citySeedId}`,
  "d4_array_layout_loop_state.json",
);
const outputPath = path.join(import.meta.dirname, "d4_array_layout_loop_state.provenance_repaired.json");

const repairs = new Map([
  [
    "farmstead_service_band_farm_home_south_a",
    ["minecraft:overworld:step.16:r.1267.1266:p.14", "坡地68"],
  ],
  [
    "central_plaza_shops_town_shop_east",
    ["minecraft:overworld:step.16:r.1267.1266:p.61", "台地62"],
  ],
  [
    "west_tavern_street_shop_house_b",
    ["minecraft:overworld:step.16:r.1267.1267:p.2", "台地59"],
  ],
]);

const state = JSON.parse(fs.readFileSync(inputPath, "utf8"));
const repairCounts = new Map([...repairs.keys()].map((anchorId) => [anchorId, 0]));

function visit(value) {
  if (Array.isArray(value)) {
    value.forEach(visit);
    return;
  }
  if (!value || typeof value !== "object") {
    return;
  }

  const sourcePatchIds = repairs.get(value.anchorId);
  if (sourcePatchIds) {
    if (Array.isArray(value.sourcePatchIds)) {
      value.sourcePatchIds = [...sourcePatchIds];
      repairCounts.set(value.anchorId, repairCounts.get(value.anchorId) + 1);
    }
    if (Array.isArray(value.sourcePatchRefs)) {
      value.sourcePatchRefs = [...sourcePatchIds];
      repairCounts.set(value.anchorId, repairCounts.get(value.anchorId) + 1);
    }
  }

  Object.values(value).forEach(visit);
}

visit(state);

for (const [anchorId, count] of repairCounts) {
  if (count === 0) {
    throw new Error(`No provenance fields repaired for ${anchorId}`);
  }
}

fs.writeFileSync(outputPath, `${JSON.stringify(state, null, 2)}\n`, "utf8");
console.log(JSON.stringify({ inputPath, outputPath, repairCounts: Object.fromEntries(repairCounts) }, null, 2));
