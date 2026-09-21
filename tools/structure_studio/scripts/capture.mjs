import {chromium} from 'playwright';
import {mkdir,writeFile} from 'node:fs/promises';
import path from 'node:path';

const args=process.argv.slice(2);
const id=args[0]??'SR-F01-v01';
const out=path.resolve(args[1]??`runtime/captures/${id}`);
const base=process.env.STUDIO_URL??'http://127.0.0.1:8765';
await mkdir(out,{recursive:true});
const browser=await chromium.launch({headless:true,executablePath:process.env.STUDIO_CHROMIUM,args:['--enable-webgl','--use-angle=swiftshader','--enable-unsafe-swiftshader']});
try {
  const page=await browser.newPage({viewport:{width:1600,height:1150},deviceScaleFactor:1});
  const errors=[];
  page.on('pageerror',err=>errors.push(String(err)));
  page.on('console',msg=>{if(msg.type()==='error')errors.push(msg.text());});
  await page.goto(`${base}/?asset=${encodeURIComponent(id)}`);
  await page.waitForFunction(()=>window.studio?.telemetry.ready||window.studio?.telemetry.renderErrors.length,{timeout:60000});
  const state=await page.evaluate(()=>window.studio.telemetry);
  if(!state.ready||state.renderErrors.length||state.missingTextures.length||errors.length)throw new Error(JSON.stringify({state,errors}));
  await page.screenshot({path:path.join(out,'workbench.png')});
  const shots=[];
  async function shot(name,fn,arg) {
    await page.evaluate(fn,arg);
    await page.evaluate(()=>new Promise(requestAnimationFrame));
    await page.locator('#viewport').screenshot({path:path.join(out,`${name}.png`)});
    shots.push(name);
  }
  for(const view of ['front','back','left','right','top'])await shot(view,v=>window.studio.preset(v),view);
  await shot('roof-off',()=>{window.studio.preset('front');window.studio.hideRoof();});
  const floors=await page.evaluate(()=>window.studio.model.author.floors.length);
  for(let i=0;i<floors;i++)await shot(`floor-${i+1}`,n=>{window.studio.floor(n);window.studio.preset('top');},i);
  const rooms=await page.evaluate(()=>window.studio.model.author.rooms);
  for(const room of rooms)await shot(`room-${room.id}`,r=>window.studio.focusRoom(r),room);
  const points=await page.evaluate(()=>window.studio.model.author.points.filter(p=>p.approach||p.kind==='circulation'));
  for(const point of points)await shot(`point-${point.id}`,p=>window.studio.enterWalk(p),point);
  await shot('annotations',()=>{window.studio.reset();window.studio.setMarkers(true);});
  if(await page.evaluate(()=>Boolean(window.studio.model.author.preview_context)))await shot('site-context',()=>{window.studio.reset();window.studio.setMarkers(false);window.studio.showContext(true);});
  const final=await page.evaluate(()=>window.studio.telemetry);
  if(final.renderErrors.length||final.missingTextures.length||errors.length)throw new Error(JSON.stringify({final,errors}));
  await writeFile(path.join(out,'capture.json'),JSON.stringify({schema:'structure-studio.capture.v1',id,nbt_sha256:state.sha256,author_sha256:state.authorSha256,shots,render_errors:errors,missing_textures:final.missingTextures,visual_review:'pending'},null,2)+'\n');
  console.log(JSON.stringify({id,out,shots:shots.length,renderErrors:errors}));
}finally{await browser.close();}
