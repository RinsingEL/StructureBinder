// Capture actual exported NBT through the local workbench's Studio endpoint.
import {chromium} from 'playwright';
import {mkdir,writeFile} from 'node:fs/promises';
import path from 'node:path';

const ids=process.argv.slice(2);
if(!ids.length)throw new Error('Supply exact asset IDs');
const base=process.env.STUDIO_URL??'http://127.0.0.1:8767/index.html';
const browser=await chromium.launch({headless:true,executablePath:process.env.STUDIO_CHROMIUM,
  args:['--enable-webgl','--use-angle=swiftshader','--enable-unsafe-swiftshader']});
try {
  const page=await browser.newPage({viewport:{width:1600,height:1150},deviceScaleFactor:1});
  const errors=[];
  page.on('pageerror',err=>errors.push(String(err)));
  page.on('console',msg=>{if(msg.type()==='error')errors.push(msg.text());});
  for(const [index,id] of ids.entries()) {
    const out=path.resolve(`runtime/captures/${id}`);await mkdir(out,{recursive:true});
    if(index===0) {
      const url=new URL(base);url.searchParams.set('asset',id);await page.goto(url.toString());
      await page.waitForFunction(id=>window.studio?.telemetry.ready&&window.studio.model.author.id===id,id,{timeout:60000});
    } else await page.evaluate(async id=>{await window.studio.load(id);},id);
    const state=await page.evaluate(()=>window.studio.telemetry);
    if(!state.ready||state.renderErrors.length||state.missingTextures.length||errors.length)throw new Error(JSON.stringify({id,state,errors}));
    const shots=[];
    async function shot(name,fn,arg) {
      await page.evaluate(fn,arg);
      await page.evaluate(()=>new Promise(requestAnimationFrame));
      await page.locator('#viewport').screenshot({path:path.join(out,`${name}.png`)});shots.push(name);
    }
    for(const view of ['front','back','left','right','top'])await shot(view,v=>{window.studio.reset();window.studio.setMarkers(false);window.studio.preset(v);},view);
    await shot('roof-off',()=>{window.studio.reset();window.studio.hideRoof();});
    const floors=await page.evaluate(()=>window.studio.model.author.floors.length);
    for(let i=0;i<floors;i++)await shot(`floor-${i+1}`,n=>{window.studio.floor(n);window.studio.preset('top');},i);
    await shot('annotations',()=>{window.studio.reset();window.studio.setMarkers(true);});
    await shot('site-context',()=>{window.studio.reset();window.studio.setMarkers(false);window.studio.showContext(true);});
    const final=await page.evaluate(()=>window.studio.telemetry);
    if(final.renderErrors.length||final.missingTextures.length||errors.length)throw new Error(JSON.stringify({id,final,errors}));
    await writeFile(path.join(out,'capture.json'),JSON.stringify({schema:'structure-studio.capture.v1',id,
      nbt_sha256:state.sha256,author_sha256:state.authorSha256,shots,render_errors:errors,
      missing_textures:final.missingTextures,visual_review:'pending'},null,2)+'\n');
    console.log(JSON.stringify({id,out,shots:shots.length,renderErrors:errors}));
  }
}finally{await browser.close();}
