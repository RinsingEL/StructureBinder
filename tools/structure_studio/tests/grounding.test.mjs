import test from 'node:test';
import assert from 'node:assert/strict';
import {groundPlane,previewContext} from '../web/grounding.js';
import {createSite} from '../web/site.js';
const model=()=>({size:[4,9,4],blocks:[],author:{preview_context:{kind:'flat',land_surface_y:1}}});
test('unmarked legacy terrain is preserved without guessing',()=>{
 const m=model();assert.equal(groundPlane(m).status,'unmarked');assert.equal(previewContext(m).land_surface_y,1);
});
test('explicit exterior plane overrides context without mutating author',()=>{
 const m=model();m.author.ground_plane={y:5,note:'external surface'};const before=JSON.stringify(m),site=createSite(m);
 assert.equal(previewContext(m).land_surface_y,5);assert.equal(JSON.stringify(m),before);
 const block=(y)=>site.structure.getBlock([1-site.origin[0],y-site.origin[1],-1-site.origin[2]]);
 assert.equal(block(4).state.getName().toString(),'minecraft:grass_block');assert.equal(block(5),null);
});
test('ground-only model has flat context; invalid marking is not silently ignored',()=>{
 const m=model();delete m.author.preview_context;m.author.ground_plane={y:0,note:'zero ground'};
 assert.equal(previewContext(m).kind,'flat');assert.equal(previewContext(m).land_surface_y,0);
 for(const p of [null,[],{}, {y:true,note:'bool'}, {y:1.5,note:'float'},{y:9,note:'bounds'},{y:1,note:' '}]){
  m.author.ground_plane=p;assert.equal(groundPlane(m).status,'invalid');assert.throws(()=>createSite(m));
 }
});
