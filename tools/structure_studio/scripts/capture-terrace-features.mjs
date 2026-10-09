// Exercise the public workbench controls; preserve real voxel/collision evidence.
import {chromium} from 'playwright';
import {mkdir,writeFile} from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';
const output=path.resolve(process.argv[2]??'../../runtime/campus-workbench/terrace-features/browser');
await mkdir(output,{recursive:true});
const browser=await chromium.launch({headless:true,
 executablePath:process.env.STUDIO_CHROMIUM??'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
 args:['--enable-webgl','--use-angle=swiftshader','--enable-unsafe-swiftshader']});
try {
 const page=await browser.newPage({viewport:{width:1600,height:1100},deviceScaleFactor:1});
 const errors=[];page.on('pageerror',e=>errors.push(String(e)));
 page.on('console',m=>{if(m.type()==='error')errors.push(m.text());});
 await page.goto(process.env.CAMPUS_URL??'http://127.0.0.1:8767/?district=teaching');
 await page.waitForFunction(()=>window.campus?.telemetry.ready,{timeout:120000});
 const revisions=[];
 async function build(click) {
  const old=await page.evaluate(()=>window.campus.telemetry.revision);
  await click();
  await page.waitForFunction(old=>window.campus?.telemetry.ready&&window.campus.telemetry.revision>old,old,{timeout:120000});
  const meta=await page.evaluate(()=>window.campus.data.metadata);
  revisions.push({revision:meta.revision,summary:meta.summary,scene:meta.scene});
  assert.equal(meta.fixtureChecks.length,meta.fixtures.length);
  assert.ok(meta.fixtureChecks.every(c=>c.supported&&c.buildingClearance&&c.roadClearance));
  console.log(JSON.stringify({revision:meta.revision,summary:meta.summary}));
  return meta;
 }
 async function example(id) {
  await page.locator('#example-scene').selectOption(id);
  return build(()=>page.locator('#load-example').click());
 }
 function geometry(meta) {
  return JSON.stringify({members:meta.members.map(m=>[m.memberId,m.assetId,m.originBlock,m.surfaceY,m.rotation]),
   terraces:meta.terraces.map(t=>[t.terraceId,t.datumY,t.spans])});
 }
 async function capture(name,district,view) {
  await page.evaluate(({district,view})=>{window.campus.focus(district);window.campus.setView(view);},{district,view});
  await page.evaluate(()=>new Promise(requestAnimationFrame));
  await page.locator('#viewport').screenshot({path:path.join(output,`${name}.png`)});
 }
 await page.locator('#labels-toggle').click();
 const front={yaw:Math.PI-.65,pitch:.65,distance:265};
 const academy=await example('terraces');
 assert.deepEqual(academy.terraces.filter(t=>t.districtId==='teaching').map(t=>t.datumY).sort(),[72,76]);
 await capture('academy-stone','teaching',front);
 await page.locator('#terrace-style').selectOption('timber_edge');
 const wood=await build(()=>page.locator('#build').click());
 assert.equal(geometry(wood),geometry(academy));
 assert.ok(wood.fixtures.every(f=>f.assetId.startsWith('studio:eu-')));
 await capture('academy-wood','teaching',front);
 const town=await example('town');
 assert.ok(town.members.every(m=>m.assetId.startsWith('studio:eu-')));
 assert.ok(town.fixtures.every(m=>m.assetId.startsWith('studio:eu-')));
 await capture('town','civic',{...front,distance:240});
 await page.locator('#terrace-features').uncheck();
 const off=await build(()=>page.locator('#build').click());
 assert.equal(off.fixtures.length,0);assert.equal(off.summary.terraceFeatureBlocks,0);
 assert.equal(geometry(off),geometry(town));
 await capture('town-off','civic',{...front,distance:240});
 const final=await example('terraces');
 await capture('front','teaching',front);
 await capture('front-west','teaching',{yaw:Math.PI+.65,pitch:.65,distance:265});
 await capture('rear','teaching',{yaw:.65,pitch:.60,distance:265});
 await capture('top','teaching',{yaw:0,pitch:1.55,distance:265});
 await page.locator('#roof').click();
 await capture('roof-off','teaching',{yaw:Math.PI-.65,pitch:.75,distance:265});
 await page.locator('#roof').click();
 await page.evaluate(()=>window.campus.overview());
 await page.locator('#viewport').screenshot({path:path.join(output,'campus.png')});
 const telemetry=await page.evaluate(()=>window.campus.telemetry);
 assert.equal(errors.length,0,JSON.stringify(errors));
 assert.equal(telemetry.renderErrors.length,0);assert.equal(telemetry.missingTextures.length,0);
 await writeFile(path.join(output,'browser-check.json'),JSON.stringify({revisions,final,telemetry,errors},null,2)+'\n');
 console.log(JSON.stringify({output,finalRevision:final.revision,passed:true}));
}finally{await browser.close();}
