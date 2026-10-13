import {createRequire} from 'node:module';
import {writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
const require=createRequire('E:/Mod_Dev/StructureBinder/tools/structure_studio/package.json');
const {chromium}=require('playwright');
const output=fileURLToPath(new URL('./results/',import.meta.url));
const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});
const results=[];
try{
 for(const program of ['starfall_welcome','greenhand_welcome'])for(const route of ['arcane','industry','provisions']){
  const context=await browser.newContext({viewport:{width:1500,height:1100},deviceScaleFactor:1});
  const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(String(e)));
  await page.goto('http://127.0.0.1:8775/');await page.waitForFunction(()=>document.querySelectorAll('#program option').length===2);
  await page.selectOption('#program',program);await page.selectOption('#loadout',route);
  async function click(selector){await page.locator(selector).click();await page.waitForFunction(()=>document.getElementById('loading').classList.contains('hidden'));const error=await page.locator('#error').textContent();if(error)throw new Error(error);}
  await click('#start');
  async function act(name,target){await click(`[data-action="${name}"]${target?`[data-target="${target}"]`:''}:not(:disabled)`);}
  await click('.scene [data-action="talk"]');
  await click('.scene [data-action="travel"]');
  await click('.scene [data-action="talk"]');
  await page.reload();await page.waitForSelector('[data-action="contribute"]');
  // All buttons remain unavailable while away from the host, even with sufficient items.
  if(await page.locator('[data-action="contribute"]:not(:disabled)').count())throw new Error('Remote contribution became available');
  await click('.scene [data-action="travel"]');
  if(await page.locator('[data-action="contribute"]:not(:disabled)').count()!==1)throw new Error('Wrong starting inventory or route gate');
  if(program==='starfall_welcome'&&route==='industry')await page.screenshot({path:output+'choice-preview.png',fullPage:true});
  await act('contribute',route);
  if(await page.locator('.scene [data-action="travel"]').count())await click('.scene [data-action="travel"]');
  await act('record');
  if(await page.locator('.scene [data-action="travel"]').count())await click('.scene [data-action="travel"]');
  await act('report');
  if(await page.locator('[data-action="claim"]').count()!==3)throw new Error('Reward choices missing');
  const reward=route==='industry'?'arcane_starter':route==='arcane'?'workshop_starter':'field_starter';
  await act('claim',reward);
  await page.waitForSelector('[data-action="buy"]');
  const before=await page.locator('.money').textContent();
  await page.locator('[data-action="buy"]:not(:disabled)').first().click();await page.waitForFunction(()=>document.getElementById('loading').classList.contains('hidden'));
  if(before===await page.locator('.money').textContent())throw new Error('Purchase did not change balance');
  await click('.scene [data-action="talk"]');
  const completed=await page.locator('.banner').textContent();
  if(!completed.includes('合作完成'))throw new Error('Completion not visible');
  await page.reload();await page.waitForSelector('[data-action="buy"]');
  if(await page.locator('[data-action="claim"]').count())throw new Error('Reward became claimable after reload');
  if(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth))throw new Error('Desktop overflow');
  if(program==='starfall_welcome'&&route==='industry')await page.screenshot({path:output+'completed-preview.png',fullPage:true});
  await page.setViewportSize({width:420,height:900});
  if(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth))throw new Error('Mobile overflow');
  if(errors.length)throw new Error(errors.join('\n'));
  const evidence=await page.evaluate(async()=>{const sid=localStorage.getItem('realmfolk-core-task-lab-v1');return await (await fetch('/api/state?session='+sid)).json()});
  results.push({program,route,reward,state:evidence.state,passed:true,errors});
  await context.close();
 }
 await writeFile(output+'browser-verification.json',JSON.stringify({passed:true,paths:results.length,results},null,2));
 console.log(JSON.stringify({passed:true,browserPaths:results.length,pageErrors:0}));
}finally{await browser.close();}
