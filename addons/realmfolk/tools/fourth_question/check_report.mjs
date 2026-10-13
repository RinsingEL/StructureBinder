// Read-only browser QA. Resolve Playwright from the existing Studio installation.
import {createRequire} from 'node:module';
import {mkdir, writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
const require=createRequire('E:/Mod_Dev/StructureBinder/tools/structure_studio/package.json');
const {chromium}=require('playwright');
const output=fileURLToPath(new URL('./results/academy/',import.meta.url));
const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});
try{
 const page=await browser.newPage({viewport:{width:1480,height:1050},deviceScaleFactor:1});
 const errors=[];page.on('pageerror',e=>errors.push(String(e)));
 await page.goto(process.env.FOURTH_QUESTION_URL??'http://127.0.0.1:8774/');
 await page.waitForSelector('#groups button');
 const results=[];
 for(const id of ['starfall','inkwell','greenhand','hearth','wayfarer']){
  await page.locator(`#groups button[data-id="${id}"]`).click();
  const state=await page.evaluate(()=>({title:document.getElementById('group-title').textContent,
   residents:document.querySelectorAll('#residents tr').length,
   mapBuildings:document.querySelectorAll('#map rect').length,
   traders:document.querySelectorAll('#npc option').length,
   pending:document.getElementById('pending').textContent,
   overflow:document.documentElement.scrollWidth>innerWidth}));
  if(!state.residents||state.mapBuildings!==51||!state.traders||state.overflow)throw new Error(JSON.stringify(state));
  if(state.traders>1){const values=await page.locator('#npc option').evaluateAll(nodes=>nodes.map(n=>n.value));await page.locator('#npc').selectOption(values[1]);}
  results.push({id,...state});
 }
 await page.locator('#groups button[data-id="starfall"]').click();
 await page.screenshot({path:output+'preview.png',fullPage:true});
 await page.setViewportSize({width:420,height:900});
 if(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth))throw new Error('Mobile horizontal overflow');
 if(errors.length)throw new Error(errors.join('\n'));
 await writeFile(output+'browser-check.json',JSON.stringify({passed:true,groups:results,pageErrors:errors,mobileOverflow:false},null,2));
 console.log(JSON.stringify({passed:true,groups:results.length,pageErrors:errors.length,screenshot:output+'preview.png'}));
}finally{await browser.close();}
