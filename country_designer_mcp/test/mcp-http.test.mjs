import assert from 'node:assert/strict';
import test from 'node:test';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';

test('URL clients initialize independently, open the lobby, and preserve session-specific planning tokens', async () => {
  const calls = [];
  const game = createServer(async (req, res) => {
    const chunks=[]; for await (const chunk of req) chunks.push(chunk);
    const args=JSON.parse(Buffer.concat(chunks));
    calls.push({path:req.url,args,token:req.headers['x-geomantia-planning-token']});
    res.setHeader('Content-Type','application/json');
    res.end(JSON.stringify(req.url.endsWith('/lobby') ? {worldName:'HTTP测试城',stage:'CITY',status:'ready',hasSavedProgress:true}
      : req.url.endsWith('/resume') ? {ok:true,status:'ready',leaseToken:args.ownerId,taskId:'task',imageEvidence:[{type:'image',mimeType:'image/png',data:'AQID'}]}
      : {ok:true,status:'waiting'}));
  }); game.listen(0,'127.0.0.1'); await once(game,'listening');
  process.env.GEOMANTIA_MC_API_URL=`http://127.0.0.1:${game.address().port}`;
  const { startMcpHttpServer }=await import('../dist/src/http-server.js');
  const service=await startMcpHttpServer(0);
  const clients=[new Client({name:'first',version:'1'}),new Client({name:'second',version:'1'})];
  const transports=clients.map(()=>new StreamableHTTPClientTransport(new URL(service.url)));
  try {
    await Promise.all(clients.map((c,i)=>c.connect(transports[i])));
    assert.equal(calls.length,0,'connection alone must not start game work');
    assert.notEqual(transports[0].sessionId,transports[1].sessionId);
    for(const client of clients) {
      assert.match(client.getInstructions(),/geomantia_lobby/);
      assert.ok((await client.listTools()).tools.some(t=>t.name==='planning_resume'));
      const lobby=await client.callTool({name:'geomantia_lobby',arguments:{}});
      assert.match(lobby.content[0].text,/HTTP测试城/);
      const task=await client.callTool({name:'planning_resume',arguments:{}});
      assert.equal(task.content[1].type,'image');
    }
    const owners=calls.filter(c=>c.path.endsWith('/resume')).map(c=>c.args.ownerId);
    assert.notEqual(owners[0],owners[1]);
    await clients[0].callTool({name:'planning_wait',arguments:{cursor:'a',timeoutSeconds:0}});
    assert.equal(calls.at(-1).token,owners[0]);
    await clients[1].callTool({name:'planning_wait',arguments:{cursor:'a',timeoutSeconds:0}});
    assert.equal(calls.at(-1).token,owners[1]);
    assert.equal((await fetch(service.url,{method:'POST',headers:{'Content-Type':'application/json','mcp-session-id':'stale'},body:'{}'})).status,404);
    assert.equal((await fetch(service.url,{method:'POST',headers:{'Content-Type':'application/json',Origin:'https://untrusted.example'},body:'{}'})).status,403);
    await assert.rejects(startMcpHttpServer(service.port),/EADDRINUSE/);
    assert.equal((await fetch(service.url.replace('/mcp','/health'))).status,200);
    await transports[0].terminateSession();
    assert.ok((await clients[1].listTools()).tools.length>0,'other client survives a session deletion');
  } finally {
    await Promise.allSettled(transports.map(t=>t.terminateSession()));
    await Promise.allSettled(clients.map(c=>c.close()));
    await service.close(); await new Promise(resolve=>game.close(resolve));
  }
});
