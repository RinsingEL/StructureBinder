import {TextureAtlas,BlockDefinition,BlockModel} from 'deepslate';
async function fetchJSON(url) { const r=await fetch(url);if(!r.ok)throw new Error(url+': '+r.status);return r.json(); }
export async function loadResources(telemetry) {
  const [data, registry] = await Promise.all([fetchJSON('/resources/resources.json'), fetchJSON('/resources/registry.json')]);
  const img = new Image(); img.src = '/resources/atlas.png'; await img.decode();
  const ctx = document.createElement('canvas').getContext('2d'); ctx.canvas.width = img.width; ctx.canvas.height = img.height; ctx.drawImage(img, 0, 0);
  const atlas = new TextureAtlas(ctx.getImageData(0, 0, img.width, img.height), data.textures);
  const definitions = Object.fromEntries(Object.entries(data.blocks).map(([k,v]) => [k,BlockDefinition.fromJson(v)]));
  const models = Object.fromEntries(Object.entries(data.models).map(([k,v]) => [k,BlockModel.fromJson(v)]));
  const modelProvider = {getBlockModel: id => models[id.toString()] ?? null};
  for (const model of Object.values(models)) model.flatten(modelProvider);
  function cube(name) {
    const definition = data.blocks[name];
    if (!definition?.variants) return false;
    return Object.values(definition.variants).flat().every(v => {
      const key = v.model.includes(':') ? v.model : `minecraft:${v.model}`;
      const es = models[key]?.elements;
      return es?.length === 1 && !es[0].rotation && es[0].from.join() === '0,0,0' && es[0].to.join() === '16,16,16' && Object.keys(es[0].faces ?? {}).length === 6;
    });
  }
  const flags = Object.fromEntries(Object.entries(registry).map(([id, v]) => [id, {opaque: !v.transparent && cube(id), semi_transparent: /(?:water|stained_glass|^minecraft:glass$|ice$|slime_block|honey_block)/.test(id), self_culling: /glass|ice$|water/.test(id)}]));
  return {...modelProvider,
    getBlockDefinition: id => definitions[id.toString()] ?? null,
    getBlockFlags: id => flags[id.toString()] ?? null,
    getBlockProperties: id => registry[id.toString()]?.properties ?? null,
    getDefaultBlockProperties: id => registry[id.toString()]?.default ?? null,
    getTextureAtlas: () => atlas.getTextureAtlas(),
    getTextureUV: id => { const key=id.toString(); if (!data.textures[key] && !telemetry.missingTextures.includes(key)) telemetry.missingTextures.push(key); return atlas.getTextureUV(id); },
    getPixelSize: () => atlas.getPixelSize(),
  };
}
