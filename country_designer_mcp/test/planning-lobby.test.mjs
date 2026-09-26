import assert from 'node:assert/strict';
import test from 'node:test';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';
import { actionResult, renderLobby, createPlanningHandlers } from '../dist/src/planning.js';

test('dispatcher forwards role, preserves images and clears the lease when yielding', async () => {
  const requests=[]; let count=0;
  const api=createServer(async(req,res)=>{
    const chunks=[];for await(const chunk of req) chunks.push(chunk);
    requests.push({path:req.url,role:req.headers['x-geomantia-planning-role'],token:req.headers['x-geomantia-planning-token'],args:JSON.parse(Buffer.concat(chunks))});
    res.setHeader('Content-Type','application/json');
    res.end(JSON.stringify(req.url==='/planning/next' ? ++count===1
      ? {ok:true,dispatch:'task',leaseToken:'private-next-lease',taskId:'one',imageEvidence:[{type:'image',mimeType:'image/png',data:'AQID'}]}
      : {ok:true,dispatch:'waiting_for_role',leaseToken:'',continuePlanning:true,nextCall:'planning_next'} : {ok:true}));
  });
  api.listen(0,'127.0.0.1');await once(api,'listening');
  const bridge=createPlanningHandlers(`http://127.0.0.1:${api.address().port}`,'ADVANCED');
  try {
    const first=await bridge.handlers.planning_next({timeoutSeconds:20});
    assert.equal(JSON.stringify(first).includes('private-next-lease'),false);
    assert.equal(first.content[1].type,'image');
    await bridge.handlers.planning_next({timeoutSeconds:0});
    assert.equal(requests.at(-1).token,'private-next-lease');
    await bridge.handlers.planning_next({timeoutSeconds:0});
    assert.equal(requests.at(-1).token,'');
    assert.equal(requests.at(-1).role,'ADVANCED');
    await bridge.handlers.planning_release({});
    assert.equal(requests.at(-1).args.ownerId,requests[0].args.ownerId);
    assert.match(renderLobby({embeddedFlashStatus:'disabled',requiredRole:'FLASH'}),/自动规划未开启/);
  } finally {await bridge.close();await new Promise(resolve=>api.close(resolve));}
});

test('external MCP initializes with lobby guidance, keeps ownership private and returns actual image content', async () => {
  const requests = [];
  const api = createServer(async (req, res) => {
    const chunks = []; for await (const chunk of req) chunks.push(chunk);
    requests.push({ path: req.url, token: req.headers['x-geomantia-planning-token'], args: JSON.parse(Buffer.concat(chunks)) });
    res.setHeader('Content-Type', 'application/json');
    const data = req.url === '/planning/lobby' ? { worldName:'河谷', stage:'CITY', status:'ready', hasSavedProgress:true, runId:'run-a', owner:'' }
      : req.url === '/planning/resume' ? { ok:true, status:'ready', taskId:'task-a', leaseToken:'private-lease', imageEvidence:[{type:'image',mimeType:'image/png',data:'AQID'}] }
      : req.url === '/planning/action' ? { ok:true, taskFinished:true, output:[{type:'input_text',text:'{"ok":true}'},{type:'input_image',image_url:'data:image/png;base64,AQID'}] }
      : { ok:true, cursor:'next', status:'waiting' };
    res.end(JSON.stringify(data));
  });
  api.listen(0, '127.0.0.1'); await once(api, 'listening');
  const client = new Client({ name:'lobby-smoke', version:'1' });
  try {
    await client.connect(new StdioClientTransport({command:process.execPath,
      args:[fileURLToPath(new URL('../dist/src/index.js', import.meta.url))],
      env:{...process.env, GEOMANTIA_PROVIDER_TOOL_URL:'', GEOMANTIA_MC_API_URL:`http://127.0.0.1:${api.address().port}`}}));
    assert.match(client.getInstructions(), /geomantia_lobby/);
    assert.match(client.getInstructions(), /等待用户选择/);
    assert.equal(requests.length, 0, 'initialization must not start a world operation');
    assert.ok((await client.listTools()).tools.some(t => t.name === 'planning_resume'));
    const lobby = await client.callTool({name:'geomantia_lobby',arguments:{}});
    assert.match(lobby.content[0].text, /河谷/); assert.match(lobby.content[0].text, /继续规划/);
    assert.equal(requests.length, 1);
    const task = await client.callTool({name:'planning_resume',arguments:{}});
    assert.equal(JSON.stringify(task).includes('private-lease'), false);
    assert.deepEqual(task.content[1], {type:'image',mimeType:'image/png',data:'AQID'});
    const action = await client.callTool({name:'planning_action',arguments:{taskId:'task-a',actionId:'one',tool:'city_d4_finalize',arguments:{}}});
    assert.equal(requests.at(-1).token, 'private-lease');
    assert.deepEqual(action.content.at(-1), {type:'image',mimeType:'image/png',data:'AQID'});
    await client.callTool({name:'planning_wait',arguments:{cursor:'old'}});
    assert.equal(requests.at(-1).token, 'private-lease');
    await client.callTool({name:'planning_release',arguments:{}});
  } finally { await client.close(); await new Promise(resolve => api.close(resolve)); }
});

test('embedded bridge does not receive lobby instructions or tools', async () => {
  const api = createServer((req,res) => { res.setHeader('Content-Type','application/json'); res.end('{"tools":[]}'); });
  api.listen(0,'127.0.0.1'); await once(api,'listening');
  const client = new Client({name:'embedded-smoke',version:'1'});
  try {
    await client.connect(new StdioClientTransport({command:process.execPath,
      args:[fileURLToPath(new URL('../dist/src/index.js',import.meta.url))],
      env:{...process.env,GEOMANTIA_PROVIDER_TOOL_URL:`http://127.0.0.1:${api.address().port}`}}));
    assert.equal(client.getInstructions(), undefined);
    assert.deepEqual((await client.listTools()).tools, []);
  } finally { await client.close(); await new Promise(resolve => api.close(resolve)); }
});

test('new-world menu and JPEG/WebP action output do not invent saved progress', () => {
  const extension = renderLobby({worldName:'河谷',stage:'EXTENSION',extensionTitle:'配置居民与商品',status:'ready',hasSavedProgress:true});
  assert.match(extension, /附属内容设计/); assert.match(extension, /配置居民与商品/); assert.match(extension, /继续规划/);
  assert.match(renderLobby({worldName:'新世界',stage:'W',status:'ready',hasSavedProgress:false}), /开始规划/);
  const result = actionResult({ok:false,output:[{type:'input_image',image_url:'data:image/webp;base64,AQID'}]});
  assert.equal(result.isError,true); assert.equal(result.content[1].mimeType,'image/webp');
});
