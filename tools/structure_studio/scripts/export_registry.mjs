import minecraftData from 'minecraft-data';
import {writeFileSync, mkdirSync} from 'node:fs';
const data = minecraftData('1.20.1');
const registry = {};
for (const block of data.blocksArray) {
  const properties = {}, defaults = {};
  let state = block.defaultState - block.minStateId;
  for (const spec of [...block.states].reverse()) {
    const values = spec.type === 'bool' ? ['true', 'false'] : spec.values?.map(String) ?? Array.from({length: spec.num_values}, (_, i) => String(i));
    properties[spec.name] = values;
    defaults[spec.name] = values[state % spec.num_values];
    state = Math.floor(state / spec.num_values);
  }
  const shapeIds=data.blockCollisionShapes.blocks[block.name];
  const ids=Array.isArray(shapeIds)?shapeIds:[shapeIds];
  registry[`minecraft:${block.name}`] = {properties, default: defaults, transparent: block.transparent, boundingBox: block.boundingBox,
    state_order:block.states.map(s=>s.name), collisions:ids.map(id=>data.blockCollisionShapes.shapes[id]??null)};
}
mkdirSync('.cache', {recursive: true});
writeFileSync('.cache/registry.json', JSON.stringify(registry));
console.log(`Exported ${Object.keys(registry).length} Minecraft 1.20.1 block definitions`);
