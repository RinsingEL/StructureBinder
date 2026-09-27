import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {mkdir} from 'node:fs/promises';
import path from 'node:path';
const out=path.resolve('../../dev_docs/tools/structure_studio/active/20260926_作者地面标记与Studio展示');
await mkdir(out,{recursive:true});
const browser=await chromium.launch({headless:true,executablePath:process.env.STUDIO_CHROMIUM,args:['--enable-webgl','--use-angle=swiftshader','--enable-unsafe-swiftshader']});
try {
 const page=await browser.newPage({viewport:{width:1600,height:1150}}),errors=[];let marked=true;
 page.on('pageerror',e=>errors.push(String(e)));
 await page.route('**/api/model?*',async route=>{
  const response=await route.fetch(),data=await response.json();
  if(marked)data.author.ground_plane={y:2,note:'浏览器测试注入：外部站立地面'};else delete data.author.ground_plane;
  await route.fulfill({response,json:data});
 });
 await page.goto('http://127.0.0.1:8765/?asset=DS-09-v01');
 await page.waitForFunction(()=>window.studio?.telemetry.ready);
 assert.match(await page.locator('#ground-info').textContent(),/Y=2/);
 await page.locator('#ground-plane').click();assert.equal(await page.locator('#ground-overlay polygon').count(),1);
 await page.locator('#context').click();
 await page.screenshot({path:path.join(out,'ground-plane-ui.png')});
 await page.locator('#ground-plane').click();assert.equal(await page.locator('#ground-overlay polygon').count(),0);
 marked=false;await page.reload();await page.waitForFunction(()=>window.studio?.telemetry.ready);
 assert.match(await page.locator('#ground-info').textContent(),/未标/);assert.equal(await page.locator('#ground-plane').isDisabled(),true);
 assert.equal(await page.locator('#ground-overlay polygon').count(),0);assert.deepEqual(errors,[]);
 console.log('Ground UI: explicit Y, overlay on/off, context, unmarked reload all passed; no author writes.');
} finally {await browser.close();}
