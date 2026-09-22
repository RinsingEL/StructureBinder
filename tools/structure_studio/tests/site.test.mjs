import assert from 'node:assert/strict';
import test from 'node:test';
import {createSite} from '../web/site.js';

function canal() {
  return {size:[12,16,16],blocks:[{pos:[5,3,2],state:0},{pos:[6,1,12],state:1}],
    author:{preview_context:{kind:'canal',land_surface_y:6,bed_y:0,padding:2,
      canal_x0:4,canal_x1:7,upper_end_z:6,upstream_surface_y:5,downstream_surface_y:3}}};
}
function at(site,x,y,z) {
  return site.structure.getBlock([x-site.origin[0],y-site.origin[1],z-site.origin[2]])?.state.getName().toString();
}

test('canal preserves the two reference levels and continuous external reaches',()=>{
  const s=createSite(canal());
  assert.equal(at(s,4,4,-1),'minecraft:water');
  assert.equal(at(s,4,5,-1),undefined);
  assert.equal(at(s,4,4,6),'minecraft:water');
  assert.equal(at(s,4,3,7),undefined);
  assert.equal(at(s,4,2,17),'minecraft:water');
  assert.equal(at(s,4,3,17),undefined);
  assert.equal(at(s,4,0,12),'minecraft:gravel');
  assert.equal(at(s,3,5,12),'minecraft:grass_block');
  assert.equal(at(s,8,5,12),'minecraft:grass_block');
});

test('context does not replace authored blocks, including explicit air',()=>{
  const model=canal(),before=JSON.stringify(model),s=createSite(model);
  assert.equal(at(s,5,3,2),undefined);
  assert.equal(at(s,6,1,12),undefined);
  assert.equal(JSON.stringify(model),before);
});

test('invalid canal extents and levels fail instead of drawing misleading terrain',()=>{
  for(const invalid of [{canal_x0:8,canal_x1:4},{canal_x1:12},{upper_end_z:16},
    {upstream_surface_y:0},{downstream_surface_y:2.5}]) {
    const m=canal();Object.assign(m.author.preview_context,invalid);
    assert.throws(()=>createSite(m),/Invalid canal context/);
  }
});

test('shore context keeps the existing land-water boundary',()=>{
  const m={size:[8,12,10],blocks:[],author:{preview_context:{kind:'shore',shore_z:5,
    land_surface_y:4,water_surface_y:3,bed_y:0,padding:2}}},s=createSite(m);
  assert.equal(at(s,2,3,4),'minecraft:grass_block');
  assert.equal(at(s,2,2,5),'minecraft:water');
  assert.equal(at(s,2,3,5),undefined);
});
