import {spawn} from 'node:child_process';
import {readFileSync, writeFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';
const dir=dirname(fileURLToPath(import.meta.url));
const startedAt=new Date();
const request=JSON.parse(readFileSync(process.argv[2],'utf8').replace(/^\uFEFF/,''));
const child=spawn(process.execPath,['E:/Mod_Dev/StructureBinder/country_designer_mcp/dist/index.js'],{
  cwd:'E:/Mod_Dev/StructureBinder/country_designer_mcp',stdio:['pipe','pipe','pipe'],windowsHide:true});
let buffer='',stderr='';
const timer=setTimeout(()=>{child.kill();console.error('MCP request timed out');process.exitCode=1},180000);
const send=value=>child.stdin.write(JSON.stringify({jsonrpc:'2.0',...value})+'\n');
child.stderr.on('data',data=>{stderr+=data; console.error(String(data).slice(0,2000));});
child.stdout.on('data',data=>{
 buffer+=data;
 while(buffer.includes('\n')){
  const end=buffer.indexOf('\n'),line=buffer.slice(0,end);buffer=buffer.slice(end+1);
  let response;try{response=JSON.parse(line)}catch{continue}
  if(response.id===1){send({method:'notifications/initialized'});send({id:2,method:request.method,params:request.params});}
  if(response.id===2){
   writeFileSync(join(dir,request.recordName+'.result.json'),JSON.stringify({startedAt:startedAt.toISOString(),elapsedMs:Date.now()-startedAt.getTime(),request,response,stderr},null,2));
   if(request.method==='tools/list') console.log(JSON.stringify(response.result?.tools?.map(t=>({name:t.name,description:t.description?.slice(0,130)}))));
   else console.log(JSON.stringify(response).slice(0,14000));
   clearTimeout(timer);child.kill();
  }
 }
});
child.on('error',error=>{clearTimeout(timer);console.error(error);process.exitCode=1});
child.on('exit',code=>{clearTimeout(timer);if(code)process.exitCode=code;});
send({id:1,method:'initialize',params:{protocolVersion:'2024-11-05',capabilities:{},clientInfo:{name:'design-first-playtest',version:'1.0'}}});
