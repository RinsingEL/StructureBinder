import {chromium} from 'playwright';
import {mkdir,writeFile} from 'node:fs/promises';
import path from 'node:path';
const output=path.resolve(process.argv[2]??'../../runtime/campus-workbench/captures');await mkdir(output,{recursive:true});
const browser=await chromium.launch({headless:true,executablePath:process.env.STUDIO_CHROMIUM??'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',args:['--enable-webgl','--use-angle=swiftshader','--enable-unsafe-swiftshader',...(process.env.CAMPUS_NO_PROXY==='1'?['--no-proxy-server']:[])]});
try {
 const page=await browser.newPage({viewport:{width:1600,height:1100},deviceScaleFactor:1});const errors=[],expectedConsoleErrors=[];
 page.on('pageerror',e=>errors.push(String(e)));page.on('console',m=>{if(m.type()==='error'){
  if(process.argv.includes('--rebuild')&&m.text()==='Failed to load resource: the server responded with a status of 409 (Conflict)'&&m.location().url.endsWith('/api/campus/build'))expectedConsoleErrors.push(m.text());
  else errors.push(m.text());
 }});
 await page.goto(process.env.CAMPUS_URL??'http://127.0.0.1:8767');
 await page.waitForFunction(()=>window.campus?.telemetry.ready||window.campus?.telemetry.renderErrors.length,{timeout:120000});
 const telemetry=await page.evaluate(()=>window.campus.telemetry);
 if(!telemetry.ready||telemetry.renderErrors.length||telemetry.missingTextures.length||errors.length)throw new Error(JSON.stringify({telemetry,errors}));
 const interactions=[];
 if(process.argv.includes('--rebuild')) {
  const base=await page.evaluate(()=>window.campus.data.metadata.revision);
  await page.locator('#array').selectOption('living-homes');await page.locator('#count').fill('3');await page.locator('#count').dispatchEvent('change');
  await page.locator('#build').click();await page.waitForFunction(r=>window.campus.telemetry.ready&&window.campus.data.metadata.revision>r,base,{timeout:120000});
  const edited=await page.evaluate(()=>({revision:window.campus.data.metadata.revision,members:window.campus.data.metadata.members.filter(m=>m.arrayId==='living-homes').length}));
  if(edited.members!==3)throw new Error('UI did not regenerate the edited array');interactions.push({action:'array count 4 to 3',...edited});
  const stale=await page.evaluate(async r=>{const response=await fetch('/api/campus/build',{method:'POST',headers:{'Content-Type':'application/json','X-Campus-Write':'build'},body:JSON.stringify({scene:window.campus.scene,baseRevision:r})});return {status:response.status,body:await response.json()};},base);
  if(stale.status!==409)throw new Error('Stale revision must be rejected');interactions.push({action:'stale revision',...stale});
  await page.locator('#edit-plan').click();await page.locator('#plan-json').fill('{');await page.locator('#apply-plan').click();
  if(!await page.locator('#plan-error').textContent())throw new Error('Invalid JSON must show an error');interactions.push({action:'invalid JSON rejected'});
  await page.locator('#default-plan').click();await page.waitForFunction(()=>document.getElementById('plan-json').value.includes('"living-homes"'));
  await page.locator('#apply-plan').click();await page.waitForFunction(r=>window.campus.telemetry.ready&&window.campus.data.metadata.revision>r,edited.revision,{timeout:120000});
  if(await page.evaluate(()=>window.campus.data.metadata.summary.buildings)!==17)throw new Error('Default scene restoration failed');interactions.push({action:'restore via full scene editor'});
 }
 await page.screenshot({path:path.join(output,'workbench.png')});
 await page.locator('#viewport').screenshot({path:path.join(output,'overview.png')});
 await page.locator('#landscape').click();
 const context=await page.evaluate(()=>({data:window.campus.context,stats:window.campus.telemetry.terrainContext,view:window.campus.telemetry.view}));
 if(context.view!=='landscape'||context.data.samples.length!==4096||context.data.waterSampleCount!==2214||context.stats.waterQuads<2214||context.stats.landQuads===0)throw new Error('D3 coast/water context missing');
 if(context.data.bounds.maxX-context.data.bounds.minX+1!==1024||context.data.bounds.maxZ-context.data.bounds.minZ+1!==1024)throw new Error('Full D3 bounds changed');
 if(context.data.waterDisplayMode!=='INFERRED_DISPLAY_LEVEL'||!await page.locator('#terrain-detail').textContent().then(s=>s.includes('水面高度为示意')))throw new Error('Water display must disclose its inferred level');
 await page.locator('.scene').screenshot({path:path.join(output,'landscape.png')});
 await page.locator('#terrain').click();
 if(await page.evaluate(()=>window.campus.telemetry.terrainVisible)!==false)throw new Error('Terrain toggle did not hide context');
 await page.locator('#terrain').click();await page.locator('#overview').click();
 interactions.push({action:'D3 landscape and terrain toggle',waterSampleCount:context.data.waterSampleCount,...context.stats});
 for(const district of ['teaching','living','agroforestry']) {
  await page.evaluate(id=>window.campus.focus(id),district);await page.waitForTimeout(250);
  await page.locator('.scene').screenshot({path:path.join(output,`${district}.png`)});
 }
 await page.locator('#top').click();await page.locator('.scene').screenshot({path:path.join(output,'top.png')});
 await page.locator('#roof').click();await page.locator('.scene').screenshot({path:path.join(output,'roof-off.png')});
 await page.locator('#terrain').click();await page.locator('#terrain').click();await page.locator('#labels-toggle').click();await page.locator('#labels-toggle').click();
 const count=await page.locator('#count').inputValue();await page.locator('#count').fill(count);await page.locator('#count').dispatchEvent('change');
 const final=await page.evaluate(()=>({telemetry:window.campus.telemetry,summary:window.campus.data.metadata.summary,revision:window.campus.data.metadata.revision}));
 if(errors.length||final.telemetry.missingTextures.length||expectedConsoleErrors.length>1)throw new Error(JSON.stringify({errors,final,expectedConsoleErrors}));
 await page.goto('http://127.0.0.1:8767/index.html');await page.waitForFunction(()=>window.studio?.telemetry.ready||window.studio?.telemetry.renderErrors.length,{timeout:120000});
 const studio=await page.evaluate(()=>window.studio.telemetry);
 if(!studio.ready||studio.renderErrors.length||studio.missingTextures.length)throw new Error('Original Studio regression: '+JSON.stringify(studio));
 await writeFile(path.join(output,'browser-check.json'),JSON.stringify({...final,errors,expectedConsoleErrors,interactions,studio},null,2));console.log(JSON.stringify({output,...final,interactions,studio}));
}finally{await browser.close();}
