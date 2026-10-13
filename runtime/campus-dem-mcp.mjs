import { Client } from '../country_designer_mcp/node_modules/@modelcontextprotocol/sdk/dist/esm/client/index.js';
import { StdioClientTransport } from '../country_designer_mcp/node_modules/@modelcontextprotocol/sdk/dist/esm/client/stdio.js';
import fs from 'node:fs';
const transport = new StdioClientTransport({command:'node',args:['dist/index.js'],cwd:'E:/Mod_Dev/StructureBinder/country_designer_mcp',stderr:'pipe'});
const client = new Client({name:'campus-dem-acceptance',version:'1.0.0'});
try { await client.connect(transport);
if (process.argv[2]) {
 const name=process.argv[2];const args=JSON.parse(process.argv[3] && fs.existsSync(process.argv[3]) ? fs.readFileSync(process.argv[3],'utf-8') : process.argv[3] || '{}');
 const result=await client.callTool({name,arguments:args},undefined,{timeout:1200000});
 const path='runtime/campus-dem-'+name+'-response.json';
 fs.writeFileSync(path,JSON.stringify(result,null,2));
 fs.appendFileSync('runtime/campus-dem-calls.jsonl',JSON.stringify({tool:name,args,responsePath:path,isError:result.isError||false})+'\n');
 for (const c of result.content||[]) if(c.type==='text') console.log(c.text.slice(0,16000));
} else {
 const result=await client.listTools();fs.writeFileSync('runtime/campus-dem-tools.json',JSON.stringify(result,null,2));
 for (const tool of result.tools) if(/scan|gis|dem|terrain|status|world/i.test(tool.name)) console.log(JSON.stringify(tool));
}
} finally {await client.close();}
