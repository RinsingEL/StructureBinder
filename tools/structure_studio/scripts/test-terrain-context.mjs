import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createTerrainContext} from '../web/terrain-context.js';

test('D3 mesh keeps the construction hole and original coordinate system',()=>{
 const gl={createBuffer:()=>({}),bindBuffer(){},bufferData(){},deleteBuffer(){}};
 const origin=[-20,60,-20],context={bounds:{minX:-16,minZ:-16,maxX:15,maxZ:15},step:16,
  detailedBounds:{minX:-8,minZ:-8,maxX:-1,maxZ:-1},waterDisplayY:62,waterSampleCount:2,
  samples:[[-16,-16,64,false],[0,-16,60,true],[-16,0,68,false],[0,0,61,true]]};
 const result=createTerrainContext(gl,context,origin);
 assert.ok(result.stats.landQuads>0&&result.stats.waterQuads>0&&result.stats.shoreQuads>0);
 for(const quad of result.mesh.quads){
  const points=quad.vertices().map(v=>[v.pos.x+origin[0],v.pos.y+origin[1],v.pos.z+origin[2]]);
  assert.ok(points.flat().every(Number.isFinite));
  assert.ok(points.every(p=>p[0]>=-16&&p[0]<=16&&p[2]>=-16&&p[2]<=16));
  const [minx,maxx,minz,maxz]=[Math.min(...points.map(p=>p[0])),Math.max(...points.map(p=>p[0])),Math.min(...points.map(p=>p[2])),Math.max(...points.map(p=>p[2]))];
  // No surface fills or overlaps the detailed construction rectangle.
  assert.ok(maxx<=-8||minx>=0||maxz<=-8||minz>=0||minx===maxx||minz===maxz);
  if(minx>=0)assert.ok(points.every(p=>p[1]===62)||minx===maxx||minz===maxz);
 }
 result.dispose();
});
