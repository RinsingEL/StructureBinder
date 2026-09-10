// Real MCP client: JSON request file in, timestamped unmodified replies out.
import {Client} from '../../country_designer_mcp/node_modules/@modelcontextprotocol/sdk/dist/esm/client/index.js';
import {StdioClientTransport} from '../../country_designer_mcp/node_modules/@modelcontextprotocol/sdk/dist/esm/client/stdio.js';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
const root=resolve(import.meta.dirname,'../..');
const out=resolve(root,'build/playtests/city-scale-20260910');
await mkdir(out,{recursive:true});
const client=new Client({name:'city-scale-real-play',version:'1.0'});
const transport=new StdioClientTransport({command:process.execPath,args:[resolve(root,'country_designer_mcp/dist/index.js')],cwd:root,stderr:'pipe'});
try {
  await client.connect(transport);
  if(!process.argv[2]) {
    const result=await client.listTools();
    await writeFile(resolve(out,'tools.json'),JSON.stringify(result,null,2));
    console.log(result.tools.map(t=>t.name).join('\n'));
  } else {
    const calls=JSON.parse(await readFile(process.argv[2],'utf8'));
    for(const call of calls) {
      const start=new Date().toISOString();
      let result;
      try {result=await client.callTool({name:call.name,arguments:call.arguments??{}},undefined,{timeout:600000});}
      catch(error){result={error:String(error)};}
      const record={start,end:new Date().toISOString(),...call,result};
      const path=resolve(out,`${Date.now()}-${call.name}.json`);
      await writeFile(path,JSON.stringify(record,null,2));
      console.log(JSON.stringify({path,result:{...result,content:result.content?.filter(c=>c.type==="text")}}).slice(0,16000));
    }
  }
} finally {await client.close();}
