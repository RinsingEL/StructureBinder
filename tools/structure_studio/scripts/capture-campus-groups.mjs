import {chromium} from 'playwright';
import {mkdir,writeFile} from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';
const output=path.resolve(process.argv[2]??'../../runtime/campus-workbench/campus-groups');await mkdir(output,{recursive:true});
const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',args:['--enable-webgl','--use-angle=swiftshader','--enable-unsafe-swiftshader']});
try{
 const page=await browser.newPage({viewport:{width:1700,height:1150}});const errors=[];page.on('pageerror',e=>errors.push(String(e)));page.on('console',m=>{if(m.type()==='error')errors.push(m.text())});
 await page.goto('http://127.0.0.1:8767/');await page.waitForFunction(()=>window.campus?.telemetry.ready,{timeout:120000});
 const meta=await page.evaluate(()=>window.campus.data.metadata);assert.equal(meta.members.length,23);assert.equal(meta.publicSpacePlans.length,(meta.scene.gapLandscape?4:3)+(meta.scene.outerGreenery&&meta.scene.greenery?1:0));
 async function shot(name,fn){await page.evaluate(fn);await page.evaluate(()=>new Promise(requestAnimationFrame));await page.locator('#viewport').screenshot({path:path.join(output,name+'.png')});}
 await page.locator('#labels-toggle').click();
 await shot('campus',()=>{window.campus.overview();window.campus.setView({yaw:Math.PI-.65,pitch:.80,distance:480});});
 await shot('top',()=>{window.campus.overview();window.campus.setView({yaw:0,pitch:1.55,distance:440});});
 if(meta.externalAccessPlan)await shot('harbor-access',()=>{
  const a=window.campus.data.metadata.externalAccessPlan.axis,o=window.campus.data.world.origin;
  const p=a[Math.floor(a.length*.65)];window.campus.setView({target:[p[0]-o[0],p[2]-o[1]+3,p[1]-o[2]],yaw:Math.PI-.65,pitch:.95,distance:180});
 });
 await shot('teaching',()=>{window.campus.focus('teaching');window.campus.setView({yaw:Math.PI-.65,pitch:.75,distance:410});});
 await shot('living',()=>{window.campus.focus('living');window.campus.setView({yaw:Math.PI-.65,pitch:.75,distance:340});});
 await page.locator('#roof').click();await shot('roof-off',()=>{window.campus.overview();window.campus.setView({yaw:Math.PI-.65,pitch:.8,distance:450});});await page.locator('#roof').click();
 await shot('garden',()=>{window.campus.focusNode('main-court');window.campus.setView({yaw:Math.PI-.65,pitch:.95,distance:235});});
 if(meta.scene.landscapeBorder){
  assert.ok(meta.publicSpacePlans.filter(p=>p.kind==='GARDEN'||p.kind==='PLAZA'||p.kind==='GAP_LANDSCAPE').every(p=>p.borderPlacements.length>0));
  await shot('beds',()=>{window.campus.focusNode('main-court');window.campus.setView({yaw:Math.PI-.65,pitch:.95,distance:145});});
 }
 if(['ROAD_LEVEL','CONNECTION_TERRACE'].includes(meta.scene.gapSurface))await shot('road-gap',()=>{
  const g=window.campus.data.metadata.publicSpacePlans.find(p=>p.kind==='GAP_LANDSCAPE'),o=window.campus.data.world.origin;
  const region=[...g.regions].sort((a,b)=>b.columns.length-a.columns.length)[0],ps=region.columns;
  const x=ps.reduce((s,p)=>s+p[0],0)/ps.length,z=ps.reduce((s,p)=>s+p[1],0)/ps.length;
  const c=g.engineeringColumns.reduce((a,b)=>(b.x-x)**2+(b.z-z)**2<(a.x-x)**2+(a.z-z)**2?b:a);
  window.campus.setView({target:[x-o[0],c.targetY-o[1]+3,z-o[2]],yaw:Math.PI-.65,pitch:.8,distance:145});
 });
 const choices=await page.locator('#array option').allTextContents();assert.equal(choices.length,9);await page.locator('#array').selectOption('main-court');assert.equal(await page.locator('#algorithm').inputValue(),'COURTYARD');assert.equal(await page.locator('#count').inputValue(),'4');await page.locator('#array').selectOption('campus-center');assert.ok(await page.locator('#asset').isDisabled());
 await shot('final',()=>{window.campus.overview();window.campus.setView({yaw:Math.PI-.65,pitch:.8,distance:480});});
 const telemetry=await page.evaluate(()=>window.campus.telemetry);assert.deepEqual(errors,[]);assert.deepEqual(telemetry.missingTextures,[]);assert.deepEqual(telemetry.renderErrors,[]);
 await writeFile(path.join(output,'browser-check.json'),JSON.stringify({revision:meta.revision,summary:meta.summary,choices,errors,telemetry},null,2));console.log(JSON.stringify({passed:true,output,revision:meta.revision}));
}finally{await browser.close();}
