const path=require('path'),fs=require('fs');
const {chromium}=require('../../视频素材/tools/node_modules/playwright');
const {openScene,openAtlas}=require('./record.cjs');
(async()=>{
 const browser=await chromium.launch({executablePath:'C:/Users/Rinsing/AppData/Local/ms-playwright/chromium_headless_shell-1217/chrome-headless-shell-win64/chrome-headless-shell.exe'});
 const page=await openScene(browser,path.resolve(__dirname,'第二段_配音预览.html'),false);
 const atlas=await openAtlas(browser,path.resolve(__dirname,'第二段_配音预览.html'));
 await page.evaluate(()=>window.externalPpt=true);
 const result=[];
 for(const t of [6,135,300,540,804]){
  const beat=await page.evaluate(t=>window.beatAt(t),t);
  if(beat){
   await atlas.evaluate(([i,p])=>window.__seek(i,p),[beat.index,beat.progress]);
   const image=await atlas.screenshot({type:'jpeg',quality:92,clip:{x:0,y:0,width:1600,height:712}});
   await page.evaluate(async src=>{const im=document.getElementById('ppt');im.src=src;await im.decode();},'data:image/jpeg;base64,'+image.toString('base64'));
  }
  await page.evaluate(t=>window.render(t),t);
  result.push(await page.evaluate(t=>({time:t,cue:window.TIMELINE.find(c=>t>=c.start&&t<c.end),beat:window.beatAt(t),brokenImages:[...document.images].filter(i=>!i.complete||!i.naturalWidth).map(i=>i.src)}),t));
  await page.screenshot({path:path.resolve(__dirname,`cache/act2-check-${t}.png`)});
 }
 fs.writeFileSync(path.resolve(__dirname,'visual_checks.json'),JSON.stringify(result,null,2));
 await browser.close();console.log('Visual probes complete');
})().catch(e=>{console.error(e);process.exit(1)});
