import {Structure} from 'deepslate';

// Synthetic context is a separate mesh: it never changes the exported NBT.
export function createSite(model) {
  const spec=model.author.preview_context;
  if(!spec)return null;
  if(!['flat','shore','slope'].includes(spec.kind))throw new Error(`Unknown site kind: ${spec.kind}`);
  const pad=Math.max(2,Math.min(12,spec.padding??5));
  const [w,,d]=model.size;
  const land=spec.land_surface_y??1,water=spec.water_surface_y??0,bed=spec.bed_y??land-3;
  const surfaces={grass:['grass_block','dirt'],sand:['sand','sandstone'],red_sand:['red_sand','red_sandstone'],snow:['snow_block','stone'],podzol:['podzol','dirt']};
  const skin=surfaces[spec.surface??'grass'];
  if(!skin)throw new Error(`Unknown site surface: ${spec.surface}`);
  const minY=Math.min(bed,land-3,water-3),maxY=Math.max(land+8,water+1);
  const origin=[-pad,minY,-pad];
  const structure=new Structure([w+2*pad,maxY-minY+1,d+2*pad]);
  const occupied=new Set(model.blocks.map(b=>b.pos.join(',')));
  function block(x,y,z,name,props={}) {
    if(!occupied.has(`${x},${y},${z}`))structure.addBlock([x+pad,y-minY,z+pad],name,props);
  }
  for(let x=-pad;x<w+pad;x++)for(let z=-pad;z<d+pad;z++) {
    const wet=spec.kind==='shore'&&z>=spec.shore_z;
    const top=wet?bed:land+(spec.kind==='slope'?Math.floor((z-(spec.slope_origin_z??0))/(spec.run??8))*(spec.rise??1):0)-1;
    if(top<minY||top>maxY)throw new Error('Synthetic terrain height outside supported context bounds');
    for(let y=minY;y<=top;y++) {
      const name=wet?'gravel':skin[y===top?0:1];
      block(x,y,z,`minecraft:${name}`,['grass_block','podzol'].includes(name)?{snowy:'false'}:{});
    }
    if(wet)for(let y=top+1;y<water;y++)block(x,y,z,'minecraft:water',{level:'0'});
  }
  return {structure,origin};
}
