// Same zoom and angles for the baseline and the teaching scale trial.
import {chromium} from 'playwright';
import {mkdir,writeFile} from 'node:fs/promises';
import path from 'node:path';
const output=path.resolve(process.argv[2]??'../../runtime/campus-workbench/teaching-trial');
await mkdir(output,{recursive:true});
const browser=await chromium.launch({headless:true,
 executablePath:process.env.STUDIO_CHROMIUM??'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
 args:['--enable-webgl','--use-angle=swiftshader','--enable-unsafe-swiftshader']});
try {
 const page=await browser.newPage({viewport:{width:1600,height:1100},deviceScaleFactor:1});
 const errors=[];page.on('pageerror',e=>errors.push(String(e)));
 page.on('console',m=>{if(m.type()==='error')errors.push(m.text());});
 await page.goto(process.env.CAMPUS_URL??'http://127.0.0.1:8767');
 await page.waitForFunction(()=>window.campus?.telemetry.ready||window.campus?.telemetry.renderErrors.length,{timeout:120000});
 if(new URL(page.url()).searchParams.get('district')==='teaching') {
  const active=await page.locator('.district.active').getAttribute('data-id');
  if(active!=='teaching')throw new Error('Teaching link must focus the teaching district');
  await page.locator('#viewport').screenshot({path:path.join(output,'direct-link.png')});
 }
 await page.locator('#labels-toggle').click();
 const shots=[];
 async function capture(name,view) {
  await page.evaluate(view=>{window.campus.focus('teaching');window.campus.setView(view);},view);
  await page.evaluate(()=>new Promise(requestAnimationFrame));
  await page.locator('#viewport').screenshot({path:path.join(output,`${name}.png`)});shots.push(name);
 }
 await capture('front',{yaw:Math.PI-.65,pitch:.65,distance:265});
 await capture('front-west',{yaw:Math.PI+.65,pitch:.65,distance:265});
 await capture('rear',{yaw:.65,pitch:.60,distance:265});
 await capture('top',{yaw:0,pitch:1.55,distance:265});
 await page.locator('#roof').click();
 await capture('roof-off',{yaw:Math.PI-.65,pitch:.75,distance:265});
 await page.locator('#roof').click();
 await page.evaluate(()=>window.campus.overview());
 await page.locator('#viewport').screenshot({path:path.join(output,'campus.png')});shots.push('campus');
 const state=await page.evaluate(()=>({telemetry:window.campus.telemetry,
  metadata:window.campus.data.metadata,catalog:window.campus.data.catalog}));
 if(!state.telemetry.ready||state.telemetry.renderErrors.length||state.telemetry.missingTextures.length||errors.length)throw new Error(JSON.stringify({errors,telemetry:state.telemetry}));
 await writeFile(path.join(output,'browser-check.json'),JSON.stringify({...state,errors,shots},null,2)+'\n');
 console.log(JSON.stringify({output,revision:state.metadata.revision,shots:shots.length,summary:state.metadata.summary,errors}));
}finally{await browser.close();}
