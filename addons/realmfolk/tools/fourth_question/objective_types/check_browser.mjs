import {createRequire} from 'node:module';
import {mkdir,writeFile,readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
const require=createRequire('E:/Mod_Dev/StructureBinder/tools/structure_studio/package.json');
const {chromium}=require('playwright');
const out=fileURLToPath(new URL('./results/',import.meta.url));await mkdir(out,{recursive:true});
const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});
const results=[],errors=[];
async function ready(page){await page.waitForFunction(()=>document.body.dataset.ready==='objective-types-20261013-v1')}
async function click(page,selector){await page.locator(selector).click();assert.equal(await page.locator('#error').textContent(),'')}
async function learn(page,name){await page.selectOption('#glyph','ars_nouveau:glyph_'+name);await click(page,'#getGlyph');await click(page,'#learnGlyph')}
async function measure(page){assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false,'horizontal overflow')}
try{
  for(const variant of ['starfall','greenhand']){
    const context=await browser.newContext({viewport:{width:1460,height:1080},deviceScaleFactor:1}),page=await context.newPage();page.on('pageerror',e=>errors.push(String(e)));
    await page.goto('http://127.0.0.1:8776/');await ready(page);await page.selectOption('#variant',variant);await click(page,'#restart');
    assert.equal(await page.locator('#campusMap [data-building]').count(),51);
    assert.equal(await page.locator('#campusMap [data-map-npc]').count(),3);
    await page.locator('[data-building="archives_required_001"]').press('Enter');assert.match(await page.locator('#buildingDetail').textContent(),/书籍借阅/);
    const mentor=variant==='starfall'?'sel':'mira',librarian=variant==='starfall'?'tasha':'ilan';
    await click(page,`[data-map-npc="${librarian}"]`);assert.equal(await page.locator('[data-quest="academy:copying"]').count(),1);
    await click(page,`[data-map-npc="${mentor}"]`);assert.equal(await page.locator('[data-quest="academy:glyph_apprentice"]').count(),1);
    results.push({variant,scenario:'d4_map_51_buildings',keyboardInspect:true,npcClickChangesDialogue:true});
    assert.match(await page.title(),/实验 03/);await measure(page);
    if(variant==='starfall')await page.screenshot({path:out+'new-experiment.png',fullPage:true});
    await click(page,'[data-action="accept"]');
    const spells=variant==='starfall'?['light','heal','grow']:['grow','heal'];
    for(const name of spells){await page.selectOption('#glyph','ars_nouveau:glyph_'+name);await click(page,'#getGlyph')}
    assert.match(await page.locator('[data-progress]').textContent(),/^0 /);assert.equal(await page.locator('[data-action="turnin"]').isDisabled(),true);
    for(const name of spells){await page.selectOption('#glyph','ars_nouveau:glyph_'+name);await click(page,'#learnGlyph');assert.equal(await page.locator('#learnGlyph').isDisabled(),true)}
    await page.reload();await ready(page);assert.equal(await page.locator('[data-action="turnin"]').isEnabled(),true);
    if(variant==='starfall')await page.screenshot({path:out+'learning-ready.png',fullPage:true});
    await click(page,'[data-action="turnin"]');assert.equal(await page.locator('[data-coins]').textContent(),'48');assert.equal(await page.locator('[data-action="turnin"]').count(),0);
    results.push({variant,path:'learn_glyphs',inventoryIsNotLearning:true,reloadPreserved:true,rewardOnce:true});
    const actors=variant==='starfall'?['tasha','loren']:['ilan','vera'];
    await click(page,`[data-action="visit"][data-target="${actors[0]}"]`);await click(page,'[data-action="accept"]');
    // The learning reward supplied paper; returning it is valid and must deduct it.
    const paperBefore=Number(await page.locator('[data-item="minecraft:paper"] strong').textContent());await click(page,'[data-action="turnin"]');
    assert.equal(Number(await page.locator('[data-item="minecraft:paper"] strong').textContent()),paperBefore-(variant==='starfall'?4:6));
    results.push({variant,path:'deliver_items',deducted:true});
    await click(page,`[data-action="visit"][data-target="${actors[1]}"]`);await click(page,'[data-action="accept"]');
    await page.selectOption('#mob','minecraft:cow');await click(page,'#kill');assert.match(await page.locator('[data-progress]').textContent(),/^0 /);
    await page.selectOption('#mob',variant==='starfall'?'minecraft:zombie':'minecraft:skeleton');for(let n=0;n<(variant==='starfall'?2:1);n++)await click(page,'#kill');
    await click(page,'[data-action="turnin"]');assert.equal(await page.locator('[data-coins]').textContent(),'80');
    await page.reload();await ready(page);assert.equal(await page.locator('[data-coins]').textContent(),'80');results.push({variant,path:'kill_entities',wrongTargetIgnored:true,allThreeCompleted:true});
    await context.close();
  }
  const context=await browser.newContext({viewport:{width:1460,height:1080}}),page=await context.newPage();page.on('pageerror',e=>errors.push(String(e)));
  await page.goto('http://127.0.0.1:8776/');await ready(page);await page.selectOption('#loadout','returning');await click(page,'#restart');await click(page,'[data-action="accept"]');assert.match(await page.locator('[data-progress]').textContent(),/^2 /);await learn(page,'grow');await click(page,'[data-action="turnin"]');
  results.push({scenario:'count_existing',pass:true});
  await page.locator('#config summary').click();await page.uncheck('#existing');await click(page,'#restart');await click(page,'[data-action="accept"]');assert.match(await page.locator('[data-progress]').textContent(),/^0 /);for(const name of ['harm','freeze','grow'])await learn(page,name);await click(page,'[data-action="turnin"]');results.push({scenario:'exclude_existing',pass:true});
  await page.selectOption('#variant','greenhand');await click(page,'#restart');await page.locator('[data-action="accept"]').click();assert.match(await page.locator('#error').textContent(),/候选不足/);results.push({scenario:'unreachable_new_learning_rejected',pass:true});
  await page.uncheck('#ars');await click(page,'#restart');assert.equal(await page.locator('[data-action="accept"]').count(),0);assert.equal(await page.locator('#learnGlyph').isDisabled(),true);await click(page,'[data-action="visit"][data-target="ilan"]');assert.equal(await page.locator('[data-action="accept"]').count(),1);results.push({scenario:'missing_mod',pass:true});
  await page.check('#ars');await page.check('#existing');await page.selectOption('#variant','starfall');await page.selectOption('#loadout','new');await click(page,'#restart');await page.locator('#config summary').click();
  await page.setViewportSize({width:390,height:844});await measure(page);await page.screenshot({path:out+'mobile-preview.png',fullPage:true});results.push({scenario:'mobile_390',pass:true});
  assert.deepEqual(errors,[]);await context.close();
  const hashes={};for(const file of ['author-catalog.json','ai-instances.json','engine.mjs','app.mjs','index.html','map.mjs','campus-layout.json'])hashes[file]=createHash('sha256').update(await readFile(new URL('./'+file,import.meta.url))).digest('hex');
  await writeFile(out+'browser-verification.json',JSON.stringify({experiment:'objective-types-03',time:new Date().toISOString(),results,pageErrors:errors,hashes},null,2));console.log(JSON.stringify({paths:results.length,pageErrors:errors.length,output:out}));
}finally{await browser.close()}
