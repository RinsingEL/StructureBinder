import test from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {mkdtemp,mkdir,writeFile,rm} from 'node:fs/promises';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {spawn} from 'node:child_process';
import {createInterface} from 'node:readline';
import sharp from 'sharp';

test('model can discover and request several images; resumed turn retires old images', {timeout:30000}, async()=>{
 const home=await mkdtemp(join(tmpdir(),'geomantia-autonomous-'));
 const root=join(home,'run');await mkdir(root);
 const images=[];
 for(const color of ['red','blue']) {
  const bytes=await sharp({create:{width:20,height:20,channels:3,background:color}}).png().toBuffer();
  images.push(`data:image/png;base64,${bytes.toString('base64')}`);await writeFile(join(root,`${color}.png`),bytes);
 }
 const requests=[];
 const calls=[['artifact_list',{}],['artifact_search',{query:'.png'}],['artifact_view_image',{path:'red.png'}],['artifact_view_image',{path:'blue.png'}],['artifact_view_image',{path:'red.png'}]];
 const server=createServer(async(req,res)=>{
  let raw='';for await(const c of req)raw+=c;
  if(!req.url.endsWith('/chat/completions')){res.writeHead(404);res.end();return;}
  const body=JSON.parse(raw);const index=requests.length;requests.push(body);
  const call=calls[index];res.writeHead(200,{'content-type':'text/event-stream'});
  const delta=call?{tool_calls:[{index:0,id:`call_${index}`,type:'function',function:{name:call[0],arguments:JSON.stringify(call[1])}}]}:{content:'visual comparison complete'};
  for(const choice of [{index:0,delta,finish_reason:null},{index:0,delta:{},finish_reason:call?'tool_calls':'stop'}])res.write('data: '+JSON.stringify({choices:[choice]})+'\n\n');
  res.end('data: [DONE]\n\n');
 });
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const msg={type:'run',home,cwd:home,artifactRoot:root,sessionId:'autonomous',baseUrl:`http://127.0.0.1:${server.address().port}/v1`,model:'deepseek-v4.1-flash',timeoutSeconds:10,apiKey:'test',affinity:'test',instructions:'Inspect artifacts.',tools:[],content:[{type:'input_text',text:'Compare requested pictures.'}]};
 async function run(){
  const child=spawn(process.execPath,[process.env.GEOMANTIA_HARNESS_ENTRY||'src/main.mjs']);let error='',done;
  child.stderr.on('data',d=>error+=d);
  createInterface({input:child.stdout}).on('line',s=>{const e=JSON.parse(s);if(e.type==='done')done=e;});
  child.stdin.write(JSON.stringify(msg)+'\n');await new Promise(r=>child.once('exit',r));
  assert.equal(done?.ok,true,JSON.stringify(done)+error);
 }
 try{
  await run();assert.equal(requests.length,6);
  const count=r=>r.messages.flatMap(m=>Array.isArray(m.content)?m.content:[]).filter(p=>p.type==='image_url').length;
  assert.equal(count(requests[0]),0);
  assert.equal(count(requests[3]),1);
  assert.equal(count(requests[4]),2,'distinct requested images coexist for comparison');
  assert.equal(count(requests[5]),2,'repeat request deduplicates actual attachment');
  msg.content=[{type:'input_text',text:'New host state. Inspect current overview.'},{type:'input_image',image_url:images[1]}];
  await run();assert.equal(count(requests[6]),1,'only old visual payload is retired on host resume');
  assert.match(JSON.stringify(requests[6]),/visual comparison complete/);
 }finally{server.closeAllConnections();await new Promise(r=>server.close(r));await rm(home,{recursive:true,force:true});}
});
