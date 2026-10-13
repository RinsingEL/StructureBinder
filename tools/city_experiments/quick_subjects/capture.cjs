const {chromium}=require('../../structure_studio/node_modules/playwright');
const fs=require('node:fs/promises');
const path=require('node:path');
const assert=require('node:assert/strict');

(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',args:['--enable-webgl','--use-angle=swiftshader','--enable-unsafe-swiftshader']});
 try {
  for(const [name,port] of [['market',8771],['craft',8772],['woodland',8773]]) {
   const output=path.resolve(__dirname,'../../../runtime/city-subjects-20261010',name,'captures');
   await fs.mkdir(output,{recursive:true});
   const page=await browser.newPage({viewport:{width:1700,height:1100}});
   const errors=[];page.on('pageerror',e=>errors.push(String(e)));
   page.on('console',m=>{if(m.type()==='error')errors.push(m.text())});
   await page.goto(`http://127.0.0.1:${port}/`);
   await page.waitForFunction(()=>window.campus?.telemetry.ready,{timeout:180000});
   const metadata=await page.evaluate(()=>window.campus.data.metadata);
   assert.equal(metadata.members.filter(m=>m.designRole==='CORE').length,3);
   assert.equal(metadata.terraces.length,3);
   for(const terrace of metadata.terraces){
    assert.equal(new Set(terrace.spans.map(s=>s.targetY)).size,1);
    const members=metadata.members.filter(m=>m.terraceId===terrace.terraceId);
    assert.equal(members.filter(m=>m.designRole==='CORE').length,1);
   }
   await page.locator('#labels-toggle').click();
   for(const [view,pitch] of [['overview',.85],['top',1.55]]){
    await page.evaluate(({pitch})=>{window.campus.overview();const size=window.campus.data.world.size;window.campus.setView({yaw:Math.PI-.65,pitch,distance:Math.max(size[0],size[2])*1.45});},{pitch});
    await page.waitForTimeout(600);
    await page.locator('#viewport').screenshot({path:path.join(output,view+'.png')});
   }
   const telemetry=await page.evaluate(()=>window.campus.telemetry);
   assert.deepEqual(errors,[]);assert.deepEqual(telemetry.missingTextures,[]);assert.deepEqual(telemetry.renderErrors,[]);
   await fs.writeFile(path.join(output,'browser-check.json'),JSON.stringify({summary:metadata.summary,telemetry,errors},null,2));
   console.log(name,JSON.stringify(metadata.summary));await page.close();
  }
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
