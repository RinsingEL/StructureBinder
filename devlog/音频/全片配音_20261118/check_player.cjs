const {chromium}=require('../../视频素材/tools/node_modules/playwright');
const fs=require('fs'),path=require('path');
(async()=>{
const browser=await chromium.launch({executablePath:'C:/Users/Rinsing/AppData/Local/ms-playwright/chromium_headless_shell-1217/chrome-headless-shell-win64/chrome-headless-shell.exe',args:['--autoplay-policy=no-user-gesture-required']});
const page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
await page.goto('http://127.0.0.1:8790/devlog/音频/全片配音_20261118/index.html');
const results=[];
for(let i=0;i<5;i++){
 await page.selectOption('#act',String(i));await page.waitForFunction(()=>ready&&!switching&&audio.readyState>=1,{},{timeout:30000});
 await page.evaluate(i=>seek(data.acts[i].offset+(i===1?540:Math.min(data.acts[i].duration*.55,300)),false),i);
 await page.waitForTimeout(700);
 await page.click('#play');const before=await page.evaluate(()=>audio.currentTime);await page.waitForTimeout(900);const after=await page.evaluate(()=>audio.currentTime);await page.click('#play');
 if(after-before<.4)throw Error('Audio did not advance act '+i);
 const state=await page.evaluate(()=>({act:data.acts[idx].name,audioTime:audio.currentTime,cue:document.getElementById('now').textContent,broken:[...frame.contentDocument.images].filter(x=>x.getAttribute('src')&&(!x.complete||!x.naturalWidth)).map(x=>x.getAttribute('src')),atlas:idx===1?frame.contentDocument.querySelector('iframe')?.contentWindow.document.querySelector('canvas')?.width:null}));
 if(state.broken.length)throw Error('Broken assets '+JSON.stringify(state));results.push(state);
 if(i===1||i===3)await page.screenshot({path:path.join(__dirname,'checks','player-act'+(i+1)+'.png')});
}
await page.selectOption('#filterAct','第四段');await page.selectOption('#filterType','接话较紧');await page.locator('.issue').first().click();await page.waitForFunction(()=>ready&&!switching);await page.waitForTimeout(300);
results.push(await page.evaluate(()=>({issueJumpAct:data.acts[idx].name,time:audio.currentTime,playing:!audio.paused})));
await browser.close();fs.writeFileSync(path.join(__dirname,'checks/player-check.json'),JSON.stringify({results,errors},null,2));console.log(JSON.stringify({results,errors},null,2));if(errors.length)process.exitCode=1;
})().catch(e=>{console.error(e);process.exit(1)});
