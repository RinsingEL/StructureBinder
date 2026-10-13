import { Client } from '../../country_designer_mcp/node_modules/@modelcontextprotocol/sdk/dist/esm/client/index.js';
import { StdioClientTransport } from '../../country_designer_mcp/node_modules/@modelcontextprotocol/sdk/dist/esm/client/stdio.js';
import fs from 'node:fs/promises';
const root='E:/Mod_Dev/StructureBinder', evidence=root+'/runtime/academy-d4-refresh-20261011';
const client=new Client({name:'codex-dev-d4-20261011',version:'1.0.0'});
const transport=new StdioClientTransport({command:process.execPath,args:[root+'/country_designer_mcp/dist/index.js'],cwd:root+'/country_designer_mcp',stderr:'pipe'});
transport.stderr?.on('data',()=>{});
await fs.mkdir(evidence,{recursive:true});
try {
 await client.connect(transport);
 if(!process.argv[2]) {const r=await client.listTools();await fs.writeFile(evidence+'/tools.json',JSON.stringify(r,null,2));console.log(JSON.stringify(r.tools.map(t=>({name:t.name,description:t.description?.slice(0,150)}))));}
 else {const path=process.argv[2],request=JSON.parse(await fs.readFile(path,'utf8')); const result=await client.callTool({name:request.tool,arguments:request.args},undefined, {timeout:120000});
 const output=path.replace(/\.json$/,'.response.json');await fs.writeFile(output,JSON.stringify(result,null,2));
 await fs.appendFile(evidence+'/calls.jsonl',JSON.stringify({time:new Date().toISOString(),request,responsePath:output,isError:result.isError??false})+'\n');
 for(const [i,c] of (result.content??[]).entries())if(c.type==='text')console.log(c.text);else if(c.type==='image'){const img=path.replace(/\.json$/,`.${i}.png`);await fs.writeFile(img,Buffer.from(c.data,'base64'));console.log('IMAGE: '+img);}
 }
}finally{await client.close();}
