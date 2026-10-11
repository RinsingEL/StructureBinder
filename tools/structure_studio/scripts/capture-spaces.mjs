import {chromium} from 'playwright';
import {mkdir,writeFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
const dir='../../runtime/space-composer/captures';await mkdir(dir,{recursive:true});
const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',args:['--enable-webgl','--use-angle=swiftshader','--enable-unsafe-swiftshader']});
try{
 const page=await browser.newPage({viewport:{width:1500,height:1000}}),errors=[];page.on('pageerror',e=>errors.push(String(e)));
 await page.goto('http://127.0.0.1:8767/spaces');await page.waitForFunction(()=>window.spaces?.telemetry.ready,{timeout:120000});
 for(const id of ['fountain','garden','ritual']){
  if(id!=='fountain'){await page.selectOption('#example',id);await page.waitForFunction(id=>window.spaces?.telemetry.ready&&document.getElementById('title').textContent===window.spaces.plan.name&&document.getElementById('example').value===id,id);}
  await page.screenshot({path:`${dir}/${id}.png`});await page.click('#top');await page.screenshot({path:`${dir}/${id}-top.png`});await page.click('#overview');
 }
 await page.selectOption('#example','garden');await page.waitForFunction(()=>window.spaces.telemetry.ready&&window.spaces.result.plan.name.startsWith('阅读'));
 const richPlants=await page.evaluate(()=>window.spaces.result.summary.plants);assert.ok(richPlants>80);
 await page.locator('#planting-density').fill('0');await page.locator('#planting-density').dispatchEvent('input');await page.click('#generate');
 await page.waitForFunction(()=>window.spaces.telemetry.ready&&window.spaces.result.summary.plants===0);
 await page.locator('#planting-density').fill('100');await page.locator('#planting-density').dispatchEvent('input');await page.click('#generate');
 await page.waitForFunction(()=>window.spaces.telemetry.ready&&window.spaces.result.summary.plants>80);
 await page.selectOption('#example','fountain');await page.waitForFunction(()=>window.spaces.telemetry.ready&&window.spaces.result.plan.name.startsWith('星泉'));
 await page.selectOption('#node','1');await page.fill('#count','6');await page.locator('#count').dispatchEvent('change');
 await page.click('#generate');await page.waitForFunction(()=>window.spaces.telemetry.ready&&window.spaces.result.summary.members===11);
 await page.click('#edit');const next=await page.locator('#json').inputValue();assert.equal(JSON.parse(next).nodes[1].count,6);await page.click('#cancel');
 await page.selectOption('#example','fountain');await page.waitForFunction(()=>window.spaces.telemetry.ready&&window.spaces.result.summary.members===13);
 const telemetry=await page.evaluate(()=>window.spaces.telemetry);assert.deepEqual(errors,[]);assert.deepEqual(telemetry.missingTextures,[]);assert.deepEqual(telemetry.renderErrors,[]);
 await writeFile(`${dir}/browser-check.json`,JSON.stringify({passed:true,examples:3,editedCount:6,errors,telemetry},null,2));console.log('3 examples and count edit passed');
}finally{await browser.close();}
