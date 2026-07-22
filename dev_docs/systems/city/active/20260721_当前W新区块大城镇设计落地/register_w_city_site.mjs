import { copyFile, mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const runRoot = process.argv[2];
const selectedCandidateId = process.argv[3];
const citySeedId = process.argv[4];

if (!runRoot || !selectedCandidateId || !citySeedId) {
  throw new Error("Usage: node register_w_city_site.mjs <runRoot> <candidateId> <citySeedId>");
}

const manifestPath = join(runRoot, "world_survey_manifest.json");
const patchMapPath = join(runRoot, "world_patch_map.json");
const registryPath = join(runRoot, "city_seed_registry.json");
const outputRoot = dirname(fileURLToPath(import.meta.url));

const manifest = JSON.parse(await readFile(manifestPath, "utf8"));
const patchMap = JSON.parse(await readFile(patchMapPath, "utf8"));
const registry = JSON.parse(await readFile(registryPath, "utf8"));

if (manifest.status !== "sealed") {
  throw new Error(`W run must be sealed, got ${manifest.status}`);
}
if (registry.citySeeds.some((seed) => seed.citySeedId === citySeedId)) {
  throw new Error(`City seed already exists: ${citySeedId}`);
}

const candidates = patchMap.cells
  .filter((cell) => cell.landWater !== "water")
  .map((cell) => ({
    candidateId: `w_site_${cell.gridX}_${cell.gridZ}`,
    gridX: cell.gridX,
    gridZ: cell.gridZ,
    landform: cell.landform,
    continentId: cell.continentId,
    patchId: cell.patchId,
    heightAvg: cell.heightAvg,
    waterDistanceBlocks: cell.waterDistanceBlocks,
    heightStats: cell.heightStats,
    slopeStats: cell.slopeStats,
    block: { x: cell.blockX, z: cell.blockZ },
  }));

const selected = candidates.find((candidate) => candidate.candidateId === selectedCandidateId);
if (!selected) {
  throw new Error(`Unknown W land candidate: ${selectedCandidateId}`);
}

const seed = {
  citySeedId,
  realmId: "realm_unassigned_acceptance",
  role: "large_island_town",
  theoreticalScale: "town",
  anchorGrid: { x: selected.gridX, z: selected.gridZ },
  anchorBlock: { x: selected.block.x, z: selected.block.z },
  candidateRangeCells: 16,
  planningRadiusCells: 12,
  subregionId: "w_island_large_town_design_20260721",
  candidateId: selectedCandidateId,
  graphDistanceToNearestCity: -1.0,
  requiredConditions: ["land", "near_water", "fresh_chunks"],
  coreFunctions: [
    "administration",
    "plaza",
    "market_and_services",
    "agriculture",
    "residential",
    "defense",
  ],
  trigger: "design_acceptance",
  source: {
    reason: "selected_from_sealed_w_by_candidate_id_after_fresh_chunk_distance_review_20260721",
  },
};

await mkdir(outputRoot, { recursive: true });
await writeFile(
  join(outputRoot, "w_city_site_candidate_set.json"),
  `${JSON.stringify({
    schemaVersion: "geomantia_city_w_site_candidate_set.v0.1",
    runId: manifest.runId,
    selectedCandidateId,
    selected,
    candidates,
  }, null, 2)}\n`,
  "utf8",
);

const backupPath = join(outputRoot, "city_seed_registry.before_new_town.json");
await copyFile(registryPath, backupPath);
registry.citySeeds.push(seed);

const temporaryPath = `${registryPath}.tmp-${process.pid}`;
await writeFile(temporaryPath, `${JSON.stringify(registry, null, 2)}\n`, "utf8");
await rename(temporaryPath, registryPath);

process.stdout.write(`${JSON.stringify({
  runId: manifest.runId,
  selectedCandidateId,
  citySeedId,
  anchorGrid: seed.anchorGrid,
  anchorBlockDerivedFromW: seed.anchorBlock,
  registryBackupPath: backupPath,
}, null, 2)}\n`);
