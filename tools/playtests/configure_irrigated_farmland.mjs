// Author-side catalog update for NEW plans only. Does not modify frozen city artifacts or saves.
// Usage: node tools/playtests/configure_irrigated_farmland.mjs <catalog.json> [--apply]
import { readFile, writeFile, copyFile } from 'node:fs/promises';
import { constants } from 'node:fs';
import { resolve } from 'node:path';
const path = resolve(process.argv[2]);
const apply = process.argv.includes('--apply');
const catalog = JSON.parse(await readFile(path, 'utf8'));
const farm = catalog.landscapeProfiles.find(p => p.landscapeProfileRef === 'landscape:farmland');
if (!farm || farm.landscapeType !== 'FARMLAND') throw new Error('Expected authored FARMLAND profile');
const recipe = catalog.surfaceRecipes.find(r => r.surfaceRecipeRef === farm.surfaceRecipeRef);
if (!recipe || recipe.surfaceAlgorithm !== 'CONTOUR_BANDS') throw new Error('Expected authored channel recipe');
const irrigated = catalog.landscapeFillProfiles.find(p => p.fillProfileRef === 'fill:relay_irrigated_farmland');
if (!irrigated?.roles.some(r => r.materialRole === 'WATER')) throw new Error('Irrigation profile required');
recipe.channelBankOverlayBlockId = 'minecraft:stone_brick_slab';
recipe.fieldBeforeBlocks = 3;
recipe.channelWidthBlocks = 3;
recipe.fieldAfterBlocks = 3;
irrigated.displayName = '地形水渠农田';
irrigated.visualIntent = '外部地块自然扩张；内部沿地形形成缓弯田垄、半砖岸沿与地面内水槽，高差处封口。田垄宽度由作者表面配方控制。';
catalog.landscapeFillProfiles = catalog.landscapeFillProfiles.filter(p => p.fillProfileRef !== 'fill:relay_dry_farmland');
if (apply) {
  await copyFile(path, `${path}.before-irrigation-20260906`, constants.COPYFILE_EXCL);
  await writeFile(path, JSON.stringify(catalog, null, 2) + '\n');
}
console.log(JSON.stringify({ path, applied: apply, recipe, dryFarmlandRemovedFromNewChoices: true }));
