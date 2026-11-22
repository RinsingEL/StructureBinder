const {chromium}=require('../../视频素材/tools/node_modules/playwright');
const fs=require('fs'),path=require('path');
const {pathToFileURL}=require('url');
(async()=>{
 const folder=path.resolve(process.argv[2]);const acts=process.argv.slice(3);
 const browser=await chromium.launch({executablePath:'C:/Users/Rinsing/AppData/Local/ms-playwright/chromium_headless_shell-1217/chrome-headless-shell-win64/chrome-headless-shell.exe'});
 const results=[];fs.mkdirSync(path.join(folder,'checks'),{recursive:true});
 for(const act of acts){
  const p=await browser.newPage({viewport:{width:1920,height:1080}});const errors=[];p.on('pageerror',e=>errors.push(e.message));
  await p.goto(pathToFileURL(path.join(folder,act+'_配音预览.html')).href+'?static');await p.evaluate(()=>document.fonts.ready);await p.waitForTimeout(2000);
  const data=await p.evaluate(()=>{
   const invalid=[],samples=[];
   for(const c of window.TIMELINE){const t=(c.start+c.end)/2;window.render(t);const line=document.getElementById('line');if(!line)invalid.push('No subtitle node');if(!Number.isFinite(c.start)||c.end<=c.start)invalid.push(c.id);}
   const broken=[...document.images].filter(i=>i.getAttribute('src')&&(!i.complete||!i.naturalWidth)).map(i=>i.getAttribute('src'));
   return {duration:window.DURATION,cards:window.TIMELINE.length,marks:window.MARKS,invalid,broken,fx:window.TIMELINE.filter(c=>c.fx||Object.keys(c.react||{}).length).length};
  });
  const times=[Math.min(8,data.duration/3),data.duration*.55,data.duration-3];
  for(let i=0;i<times.length;i++){await p.evaluate(t=>window.render(t),times[i]);await p.screenshot({path:path.join(folder,'checks',`${act}-${i+1}.png`)});}
  results.push({act,...data,errors});await p.close();
 }
 await browser.close();fs.writeFileSync(path.join(folder,'checks','qa-'+acts.join('-')+'.json'),JSON.stringify(results,null,2));
 console.log(JSON.stringify(results,null,2));if(results.some(r=>r.errors.length||r.invalid.length||r.broken.length))process.exitCode=1;
})().catch(e=>{console.error(e);process.exit(1)});
